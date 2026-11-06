package eu.tintera.plume.ai.llamacpp

import eu.tintera.plume.ai.engine.LlmBackend
import eu.tintera.plume.ai.engine.LlmContent
import eu.tintera.plume.ai.engine.LlmConversation
import eu.tintera.plume.ai.engine.LlmEngine
import eu.tintera.plume.ai.engine.LlmEngineConfig
import eu.tintera.plume.ai.engine.LlmEngineFactory
import eu.tintera.plume.ai.engine.LlmSampling
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * llama.cpp (modely GGUF, třeba EuroLLM) za rozhraním [LlmEngineFactory]. Jen text a jen CPU.
 *
 * Vytváří se reflexí podle jména ([eu.tintera.plume.ai.engine.LLAMACPP_ENGINE_FACTORY]), proto
 * veřejný konstruktor bez parametrů.
 *
 * - **Jen CPU.** Aplikace o GPU nežádá (`ModelRuntime.gpu`): selhání GPU si pamatuje pro CELÝ
 *   proces (`gpuFailed` v `LiteRtOnDeviceAi`), takže pokus tady by Gemmě vzal GPU kvůli modelu,
 *   který GPU neumí. Backendy llama.cpp pro GPU (Vulkan, OpenCL) se nepřekládají.
 * - **Zvuk a obraz neumí** (engine s nimi se neotevře); EuroLLM je jen textový model.
 */
class LlamaCppEngineFactory : LlmEngineFactory {

    override fun open(config: LlmEngineConfig): LlmEngine {
        require(!config.audio && !config.vision) { "llama.cpp runtime is text only" }
        require(config.backend == LlmBackend.Cpu) { "llama.cpp runtime runs on the CPU only" }
        LlamaNative.ensureInitialized()
        val handle = LlamaNative.load(config.modelPath, config.maxNumTokens, threads())
        if (handle == 0L) throw IOException("llama.cpp failed to load ${config.modelPath}")
        return LlamaCppEngine(handle)
    }

    /**
     * Vlákna na výpočet: polovina jader, nejvýš 4 a nejméně 2. Telefon má velká a malá jádra
     * a vlákno na malém jádře zdržuje ostatní; víc než 4 dává podle příkladu llama.cpp pro
     * Android jen horší časy.
     */
    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}

private class LlamaCppEngine(private val handle: Long) : LlmEngine {

    @Volatile
    private var closed = false

    override fun conversation(sampling: LlmSampling): LlmConversation = LlamaCppConversation(handle, sampling)

    override fun close() {
        if (closed) return
        closed = true
        LlamaNative.free(handle)
    }
}

/**
 * Jedna konverzace = nová paměť kontextu (`start` ji smaže). Aplikace generuje jedno po druhém
 * (zámek enginu), takže dvě konverzace nad jedním kontextem se nepotkají.
 */
private class LlamaCppConversation(private val handle: Long, private val sampling: LlmSampling) : LlmConversation {

    @Volatile
    private var cancelled = false

    override fun send(content: List<LlmContent>, maxOutputTokens: Int?): Flow<String> = flow {
        val prompt = content.joinToString("\n") { part ->
            when (part) {
                is LlmContent.Text -> part.text
                is LlmContent.Audio, is LlmContent.Image -> throw UnsupportedOperationException("llama.cpp runtime is text only")
            }
        }
        val started = LlamaNative.start(
            handle,
            prompt.encodeToByteArray(),
            sampling.topK,
            sampling.topP.toFloat(),
            sampling.temperature.toFloat(),
            maxOutputTokens ?: Int.MAX_VALUE,
        )
        if (started != 0) throw IOException("llama.cpp could not start the reply (code $started)")
        while (!cancelled) {
            currentCoroutineContext().ensureActive()
            val piece = LlamaNative.next(handle) ?: break
            if (piece.isNotEmpty()) emit(piece.decodeToString())
        }
    }

    override fun cancel() {
        cancelled = true
    }

    override fun close() = Unit
}

/** Nativní strana (`plume_llama.cpp`). Knihovna jen v součásti z Google Play. */
internal object LlamaNative {

    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary(LIBRARY)
            init()
            initialized = true
        }
    }

    @JvmStatic
    private external fun init()

    @JvmStatic
    external fun load(path: String, contextTokens: Int, threads: Int): Long

    @JvmStatic
    external fun start(handle: Long, prompt: ByteArray, topK: Int, topP: Float, temperature: Float, maxTokens: Int): Int

    @JvmStatic
    external fun next(handle: Long): ByteArray?

    @JvmStatic
    external fun free(handle: Long)

    /** `libplume_llama.so` (src/main/cpp/CMakeLists.txt); totéž jméno načítá i `PlayLiteRtRuntime`. */
    const val LIBRARY = "plume_llama"
}
