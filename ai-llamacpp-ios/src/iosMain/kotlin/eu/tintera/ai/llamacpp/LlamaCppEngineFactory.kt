package eu.tintera.ai.llamacpp

import cnames.structs.ael_session
import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.engine.LlmContent
import eu.tintera.ai.engine.LlmConversation
import eu.tintera.ai.engine.LlmEngine
import eu.tintera.ai.engine.LlmEngineConfig
import eu.tintera.ai.engine.LlmEngineFactory
import eu.tintera.ai.engine.LlmSampling
import eu.tintera.ai.llamacpp.native.ael_free
import eu.tintera.ai.llamacpp.native.ael_has_gpu
import eu.tintera.ai.llamacpp.native.ael_init
import eu.tintera.ai.llamacpp.native.ael_load
import eu.tintera.ai.llamacpp.native.ael_next
import eu.tintera.ai.llamacpp.native.ael_start
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSProcessInfo

/**
 * llama.cpp (modely GGUF) za rozhraním [LlmEngineFactory] na iOS. Jen text. Týž jádro
 * (`ai_engine_llama.h`) jako na Androidu a desktopu, jen ho místo JNI volá cinterop.
 *
 * Aplikace ji předá do `LiteRtOnDeviceAi` přes `LinkedLiteRtRuntime { LlamaCppEngineFactory() }`;
 * reflexe ani doinstalování na iOS nejsou.
 *
 * - **GPU přes Metal jen na zařízení** (`IosLiteRtPlatform.gpu`). Když přesto přijde
 *   [LlmBackend.Gpu] a build GPU nemá (simulátor), [open] vyhodí dřív, než začne načítat,
 *   a aplikace načte CPU.
 * - **Zvuk a obraz neumí** (engine s nimi se neotevře).
 */
@OptIn(ExperimentalForeignApi::class)
class LlamaCppEngineFactory : LlmEngineFactory {

    override fun open(config: LlmEngineConfig): LlmEngine {
        require(!config.audio && !config.vision) { "llama.cpp runtime is text only" }
        LlamaBackend.ensureInitialized()
        val gpu = config.backend == LlmBackend.Gpu
        if (gpu && !ael_has_gpu()) throw IllegalStateException("llama.cpp has no GPU backend in this build")
        val session = ael_load(config.modelPath, config.maxNumTokens, threads(), gpu)
            ?: throw IllegalStateException("llama.cpp failed to load ${config.modelPath}")
        return LlamaCppEngine(session)
    }

    /**
     * Vlákna na výpočet: jádra bez dvou, 2 až 4. iPhone má dvě velká a čtyři úsporná jádra
     * a generování stojí hlavně na propustnosti paměti; víc vláken na úsporných jádrech jen
     * zdržuje (příklad llama.cpp pro Swift bere taky počet jader bez dvou).
     */
    private fun threads(): Int = (NSProcessInfo.processInfo.activeProcessorCount.toInt() - 2).coerceIn(2, 4)
}

/** `llama_backend_init` jednou za proces, před prvním modelem. */
@OptIn(ExperimentalForeignApi::class)
private object LlamaBackend {
    private val initialized: Unit by lazy { ael_init() }

    fun ensureInitialized() = initialized
}

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private class LlamaCppEngine(private val session: CPointer<ael_session>) : LlmEngine {

    private val closed = AtomicBoolean(false)

    override fun conversation(sampling: LlmSampling): LlmConversation = LlamaCppConversation(session, sampling)

    override fun close() {
        if (closed.compareAndSet(false, true)) ael_free(session)
    }
}

/**
 * Jedna konverzace = nová paměť kontextu (`ael_start` ji smaže). Aplikace generuje jedno po
 * druhém (zámek enginu), takže dvě konverzace nad jedním kontextem se nepotkají.
 */
@OptIn(ExperimentalForeignApi::class)
private class LlamaCppConversation(
    private val session: CPointer<ael_session>,
    private val sampling: LlmSampling,
) : LlmConversation {

    @Volatile
    private var cancelled = false

    override fun send(content: List<LlmContent>, maxOutputTokens: Int?): Flow<String> = flow {
        val prompt = content.joinToString("\n") { part ->
            when (part) {
                is LlmContent.Text -> part.text
                is LlmContent.Audio, is LlmContent.Image -> throw UnsupportedOperationException("llama.cpp runtime is text only")
            }
        }.encodeToByteArray()
        val started = ael_start(
            session,
            prompt.toCValues(),
            prompt.size.convert(),
            sampling.topK,
            sampling.topP.toFloat(),
            sampling.temperature.toFloat(),
            maxOutputTokens ?: Int.MAX_VALUE,
        )
        if (started != 0) throw IllegalStateException("llama.cpp could not start the reply (code $started)")
        memScoped {
            val piece = alloc<CPointerVar<ByteVar>>()
            while (!cancelled) {
                currentCoroutineContext().ensureActive()
                val length = ael_next(session, piece.ptr)
                if (length < 0) break
                if (length > 0) emit(piece.value!!.readBytes(length).decodeToString())
            }
        }
    }

    override fun cancel() {
        cancelled = true
    }

    override fun close() = Unit
}
