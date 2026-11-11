package eu.tintera.ai.playground.chat

import eu.tintera.ai.core.ModelVariant
import eu.tintera.ai.core.OnDeviceAi
import eu.tintera.ai.core.OnDeviceAiException
import eu.tintera.ai.core.estimatedTokenCount
import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.playground.EngineStats
import eu.tintera.ai.playground.models.describe
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Zpráva v konverzaci. U odpovědi modelu [metrics], když doběhla. */
data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val metrics: AnswerMetrics? = null,
    val truncated: Boolean = false,
    val stopped: Boolean = false,
    val error: String? = null,
    /** Kolik nejstarších tahů se kvůli limitu do promptu nevešlo. */
    val droppedTurns: Int = 0,
) {
    private val parts by lazy { splitThinking(text) }

    /** Přemýšlení modelu (`<think>…</think>`), když ho vypsal; `null` = bez něj. */
    val thinking: String? get() = parts.first

    /** Odpověď bez přemýšlení; ta jde i do historie. */
    val answer: String get() = parts.second
}

/**
 * Oddělí přemýšlení od odpovědi. Rozhraní enginu slibuje přemýšlení vypnuté, ale llama.cpp skládá
 * prompt starší šablonou bez `enable_thinking`, takže Qwen3 (a jiné „thinking" modely) blok
 * `<think>` vypíšou stejně. Obrazovka ho ukáže zvlášť a do historie nejde — zbytečně by zabíral
 * kontext.
 */
fun splitThinking(text: String): Pair<String?, String> {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith(THINK_START)) return null to text
    val end = trimmed.indexOf(THINK_END)
    return if (end < 0) {
        trimmed.removePrefix(THINK_START).trim() to ""
    } else {
        trimmed.substring(THINK_START.length, end).trim() to trimmed.substring(end + THINK_END.length).trim()
    }
}

private const val THINK_START = "<think>"
private const val THINK_END = "</think>"

/**
 * Měření jedné odpovědi.
 *
 * @property loadMs jak dlouho se během odpovědi načítal model (`null` = byl už načtený, nebo
 *   systémový model, u kterého načtení nevidíme).
 * @property firstTokenMs od odeslání po první text, BEZ načtení modelu.
 * @property chunks kousků od runtime (≈ tokenů); `null` u systémového modelu.
 * @property generationMs od prvního textu po poslední.
 */
data class AnswerMetrics(
    val loadMs: Long?,
    val backend: LlmBackend?,
    val firstTokenMs: Long?,
    val generationMs: Long,
    val chars: Int,
    val chunks: Long?,
    val promptTokens: Int,
    val tokenLimit: Int,
) {
    val charsPerSecond: Double? get() = if (generationMs > 0) chars * 1000.0 / generationMs else null
    val tokensPerSecond: Double? get() = if (generationMs > 0 && chunks != null && chunks > 1) (chunks - 1) * 1000.0 / generationMs else null
}

/**
 * Konverzace s jedním modelem. Historii skládá do promptu [ConversationPrompt], odpověď streamuje
 * (`generate` vydává celý dosavadní text) a [stop] generování zastaví — zrušení sběru zastaví
 * i runtime (`LlmConversation.cancel` v knihovně).
 */
class ChatSession(
    private val ai: OnDeviceAi,
    val variant: ModelVariant,
    val title: String,
    val subtitle: String,
    private val scope: CoroutineScope,
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private var job: Job? = null
    private var nextId = 0L

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || job?.isActive == true) return
        val history = turns()
        val userId = nextId++
        val answerId = nextId++
        _messages.update {
            it + ChatMessage(userId, fromUser = true, text = message) +
                ChatMessage(answerId, fromUser = false, text = "", streaming = true)
        }
        _generating.value = true
        job = scope.launch {
            try {
                generate(answerId, history, message)
            } finally {
                _generating.value = false
                edit(answerId) { if (it.streaming) it.copy(streaming = false, stopped = true) else it }
            }
        }
    }

    private suspend fun generate(answerId: Long, history: List<Turn>, message: String) {
        val limit = runCatching { ai.info(variant)?.tokenLimit }.getOrNull() ?: FALLBACK_TOKEN_LIMIT
        val fitted = ConversationPrompt.fit(history, message, limit) { prompt ->
            runCatching { ai.countTokens(variant, prompt) }.getOrNull() ?: estimatedTokenCount(prompt)
        }
        if (fitted == null) {
            edit(answerId) {
                it.copy(streaming = false, error = "The message is too long for this model (limit $limit tokens).")
            }
            return
        }
        edit(answerId) { it.copy(droppedTurns = fitted.droppedTurns) }

        val loadsBefore = EngineStats.loads.load()
        val loadMsBefore = EngineStats.totalLoadMs.load()
        val chunksBefore = EngineStats.chunks.load()
        val start = TimeSource.Monotonic.markNow()
        var firstAt: TimeSource.Monotonic.ValueTimeMark? = null
        var lastAt = start
        var text = ""
        var truncated = false

        fun metrics(): AnswerMetrics {
            val loaded = EngineStats.loads.load() != loadsBefore
            val loadMs = (EngineStats.totalLoadMs.load() - loadMsBefore).takeIf { loaded || it > 0 }
            val first = firstAt?.let { (it - start).inWholeMilliseconds - (loadMs ?: 0) }?.coerceAtLeast(0)
            val chunks = (EngineStats.chunks.load() - chunksBefore).takeIf { it > 0 }
            return AnswerMetrics(
                loadMs = loadMs,
                backend = if (variant.model != null) EngineStats.lastBackend.load() else null,
                firstTokenMs = first,
                generationMs = firstAt?.let { (lastAt - it).inWholeMilliseconds } ?: 0,
                chars = text.length,
                chunks = chunks,
                promptTokens = fitted.tokens,
                tokenLimit = limit,
            )
        }

        try {
            ai.generate(variant, fitted.prompt).collect { generated ->
                val now = TimeSource.Monotonic.markNow()
                if (firstAt == null && generated.text.isNotEmpty()) firstAt = now
                lastAt = now
                text = generated.text
                truncated = generated.truncated
                edit(answerId) { it.copy(text = text, truncated = truncated) }
            }
            edit(answerId) { it.copy(streaming = false, metrics = metrics()) }
        } catch (e: CancellationException) {
            // Zastaveno tlačítkem (nebo zavřená konverzace): co přišlo, zůstává.
            edit(answerId) { it.copy(streaming = false, stopped = true, metrics = metrics()) }
            throw e
        } catch (e: OnDeviceAiException) {
            edit(answerId) { it.copy(streaming = false, error = "Generation failed: ${e.reason.describe()}") }
        } catch (e: Exception) {
            edit(answerId) { it.copy(streaming = false, error = "Generation failed: ${e.message ?: e::class.simpleName}") }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Nová konverzace se stejným modelem. */
    fun clear() {
        stop()
        _messages.value = emptyList()
    }

    /** Dosavadní dokončené tahy pro prompt; nepovedené odpovědi se vynechají. */
    private fun turns(): List<Turn> {
        val list = _messages.value
        return list.zipWithNext()
            .filter { (user, answer) -> user.fromUser && !answer.fromUser && answer.error == null && answer.answer.isNotBlank() }
            .map { (user, answer) -> Turn(user.text, answer.answer) }
    }

    private fun edit(id: Long, change: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private companion object {
        /** Model, který limit neřekne (systémový bez `info`): kontext Apple Intelligence je 4 096. */
        const val FALLBACK_TOKEN_LIMIT = 3_000
    }
}
