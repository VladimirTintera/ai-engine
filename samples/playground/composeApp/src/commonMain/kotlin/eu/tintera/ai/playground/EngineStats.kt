package eu.tintera.ai.playground

import eu.tintera.ai.core.LiteRtRuntime
import eu.tintera.ai.core.ModelDownload
import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.engine.LlmContent
import eu.tintera.ai.engine.LlmConversation
import eu.tintera.ai.engine.LlmEngine
import eu.tintera.ai.engine.LlmEngineConfig
import eu.tintera.ai.engine.LlmEngineFactory
import eu.tintera.ai.engine.LlmSampling
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Měření, které `OnDeviceAi` samo nehlásí: jak dlouho se model načítal a kolik kousků (zhruba
 * tokenů) runtime vydal. Knihovna kvůli tomu měnit nejde a nemusí — runtime jí dodává aplikace
 * ([LiteRtRuntime]), takže ho tu jen obalíme ([TimedRuntime]). Generuje se vždy jen jedno
 * najednou (`LiteRtOnDeviceAi`), proto stačí globální počitadla.
 */
object EngineStats {

    /** Součet doby všech načtení (i nepovedených pokusů o GPU) v ms. */
    val totalLoadMs = AtomicLong(0)

    /** Počet úspěšných načtení enginu. */
    val loads = AtomicLong(0)

    /** Na čem běží naposledy načtený engine. */
    val lastBackend = AtomicReference<LlmBackend?>(null)

    /** Kousků odpovědi od spuštění procesu; llama.cpp i LiteRT-LM vydávají zhruba token na kousek. */
    val chunks = AtomicLong(0)

    /** Načtený engine (knihovna drží nejvýš jeden), aby šel před koncem procesu zavřít. */
    internal val open = AtomicReference<LlmEngine?>(null)

    /**
     * Zavře načtený engine před ukončením aplikace. Na macOS llama.cpp při konci procesu
     * s nezavřeným modelem spadne (`GGML_ASSERT([rsets->data count] == 0)` ve statickém
     * destruktoru ggml-metal) a systém pak hlásí pád aplikace. Knihovna engine sama uvolní až
     * po nečinnosti a veřejné „uvolni teď" nemá; volá se jen při ukončení, kdy už nic negeneruje.
     */
    fun closeOpenEngine() {
        open.exchange(null)?.close()
    }
}

/** [LiteRtRuntime], který měří načtení modelu a počítá kousky odpovědi ([EngineStats]). */
class TimedRuntime(private val delegate: LiteRtRuntime) : LiteRtRuntime {

    override fun installed(): Boolean = delegate.installed()

    override fun install(): Flow<ModelDownload> = delegate.install()

    override fun engines(): LlmEngineFactory = TimedFactory(delegate.engines())
}

private class TimedFactory(private val delegate: LlmEngineFactory) : LlmEngineFactory {
    override fun open(config: LlmEngineConfig): LlmEngine {
        val start = TimeSource.Monotonic.markNow()
        try {
            val engine = delegate.open(config)
            EngineStats.loads.incrementAndFetch()
            EngineStats.lastBackend.store(config.backend)
            return TimedEngine(engine).also { EngineStats.open.store(it) }
        } finally {
            EngineStats.totalLoadMs.addAndFetch(start.elapsedNow().inWholeMilliseconds)
        }
    }
}

private class TimedEngine(private val delegate: LlmEngine) : LlmEngine {
    private val closed = AtomicBoolean(false)

    override fun conversation(sampling: LlmSampling): LlmConversation = TimedConversation(delegate.conversation(sampling))

    /** Jednou: zavřít ho může knihovna (nečinnost, jiný model) i [EngineStats.closeOpenEngine]. */
    override fun close() {
        if (!closed.exchange(true)) {
            EngineStats.open.compareAndSet(this, null)
            delegate.close()
        }
    }
}

private class TimedConversation(private val delegate: LlmConversation) : LlmConversation {
    override fun send(content: List<LlmContent>, maxOutputTokens: Int?): Flow<String> =
        delegate.send(content, maxOutputTokens).onEach { EngineStats.chunks.incrementAndFetch() }

    override fun cancel() = delegate.cancel()
    override fun close() = delegate.close()
}
