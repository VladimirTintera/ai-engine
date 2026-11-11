package eu.tintera.ai.llamacpp

import eu.tintera.ai.engine.LLAMACPP_NATIVE_LIBRARY
import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.engine.LlmContent
import eu.tintera.ai.engine.LlmConversation
import eu.tintera.ai.engine.LlmEngine
import eu.tintera.ai.engine.LlmEngineConfig
import eu.tintera.ai.engine.LlmEngineFactory
import eu.tintera.ai.engine.LlmSampling
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * llama.cpp (modely GGUF) za rozhraním [LlmEngineFactory]. Jen text.
 * Týž kód na Androidu i desktopu; liší se jen původ nativní knihovny ([LlamaNative.loadLibrary])
 * a počet vláken ([threads]).
 *
 * Vytváří se reflexí podle jména ([eu.tintera.ai.engine.LLAMACPP_ENGINE_FACTORY]), proto
 * veřejný konstruktor bez parametrů.
 *
 * - **GPU jen přes Metal na Macu s Apple Silicon** — jinde se GPU backend nepřekládá a aplikace
 *   o něj nežádá (`LiteRtPlatform.gpu`). Když přesto přijde [LlmBackend.Gpu] a build žádné GPU
 *   nemá, [open] vyhodí dřív, než začne načítat, a aplikace načte CPU. Selhání GPU si aplikace
 *   pamatuje pro každý runtime zvlášť (`gpuFailed` v `LiteRtOnDeviceAi`), takže Metal v llama.cpp
 *   nevezme GPU Gemmě v LiteRT-LM.
 * - **Zvuk a obraz neumí** (engine s nimi se neotevře).
 */
class LlamaCppEngineFactory : LlmEngineFactory {

    override fun open(config: LlmEngineConfig): LlmEngine {
        require(!config.audio && !config.vision) { "llama.cpp runtime is text only" }
        LlamaNative.ensureInitialized(File(config.cacheDir))
        val gpu = config.backend == LlmBackend.Gpu
        if (gpu && !LlamaNative.hasGpu()) throw IOException("llama.cpp has no GPU backend in this build")
        val handle = LlamaNative.load(config.modelPath.encodeToByteArray(), config.maxNumTokens, threads(), gpu)
        if (handle == 0L) throw IOException("llama.cpp failed to load ${config.modelPath}")
        return LlamaCppEngine(handle)
    }

    /**
     * Vlákna na výpočet: polovina logických jader, nejméně 2. Telefon má velká a malá jádra
     * a vlákno na malém jádře zdržuje ostatní; víc než 4 dává podle příkladu llama.cpp pro
     * Android jen horší časy. Na desktopu je polovina zhruba počet fyzických jader (Intel a AMD
     * mají dvě vlákna na jádro, Apple Silicon půlku úsporných jader), strop 8 drží výpočet
     * pryč od úsporných jader velkých procesorů a nechá stroj použitelný.
     */
    private fun threads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, if (android) 4 else 8)

    private val android = System.getProperty("java.vm.name") == "Dalvik"
}

/**
 * Načtený model. Volání do nativní strany jdou pod zámkem [lock], aby [close] — i z háku při
 * ukončení JVM ([LlamaNative.closeAllAtExit]) — neuvolnil model uprostřed počítání tokenu.
 */
private class LlamaCppEngine(private val handle: Long) : LlmEngine {

    private val lock = Any()

    /** Pod [lock]. */
    private var closed = false

    init {
        LlamaNative.opened(this)
    }

    override fun conversation(sampling: LlmSampling): LlmConversation = LlamaCppConversation(this, sampling)

    /** Viz `ael_start`; zavřený engine = [IOException]. */
    fun start(prompt: ByteArray, sampling: LlmSampling, maxTokens: Int): Int = synchronized(lock) {
        if (closed) throw IOException("llama.cpp engine is closed")
        LlamaNative.start(handle, prompt, sampling.topK, sampling.topP.toFloat(), sampling.temperature.toFloat(), maxTokens)
    }

    /** Další kus odpovědi, `null` na konci — i když engine mezitím někdo zavřel. */
    fun next(): ByteArray? = synchronized(lock) {
        if (closed) null else LlamaNative.next(handle)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            LlamaNative.free(handle)
        }
        LlamaNative.closed(this)
    }
}

/**
 * Jedna konverzace = nová paměť kontextu (`start` ji smaže). Aplikace generuje jedno po druhém
 * (zámek enginu), takže dvě konverzace nad jedním kontextem se nepotkají.
 */
private class LlamaCppConversation(private val engine: LlamaCppEngine, private val sampling: LlmSampling) : LlmConversation {

    @Volatile
    private var cancelled = false

    override fun send(content: List<LlmContent>, maxOutputTokens: Int?): Flow<String> = flow {
        val prompt = content.joinToString("\n") { part ->
            when (part) {
                is LlmContent.Text -> part.text
                is LlmContent.Audio, is LlmContent.Image -> throw UnsupportedOperationException("llama.cpp runtime is text only")
            }
        }
        val started = engine.start(prompt.encodeToByteArray(), sampling, maxOutputTokens ?: Int.MAX_VALUE)
        if (started != 0) throw IOException("llama.cpp could not start the reply (code $started)")
        while (!cancelled) {
            currentCoroutineContext().ensureActive()
            val piece = engine.next() ?: break
            if (piece.isNotEmpty()) emit(piece.decodeToString())
        }
    }

    override fun cancel() {
        cancelled = true
    }

    override fun close() = Unit
}

/**
 * Nativní strana (`ai_engine_llama.cpp`). Na Androidu je knihovna v součásti z Google Play, na
 * desktopu v jaru :ai-llamacpp-desktop.
 */
internal object LlamaNative {

    @Volatile
    private var initialized = false

    /** Načtené modely, které ještě nikdo nezavřel; uvolní je [closeAllAtExit]. */
    private val open = ConcurrentHashMap.newKeySet<LlmEngine>()

    fun opened(engine: LlmEngine) {
        open += engine
    }

    fun closed(engine: LlmEngine) {
        open -= engine
    }

    fun ensureInitialized(cacheDir: File) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            loadLibrary(cacheDir)
            init()
            initialized = true
        }
    }

    /**
     * Knihovna v jaru (desktop) se vybalí do [cacheDir] a načte cestou; jinak se načte jménem
     * (Android: součást z Play, kterou už načetl `SplitInstallHelper`, viz `PlayLiteRtRuntime`).
     *
     * Vybaluje se do složky pojmenované podle otisku obsahu, ne přes starou kopii: nahranou DLL
     * Windows přepsat ani smazat nedovolí, takže by nová verze aplikace vedle běžící staré
     * neměla kam. Starší složky se uklidí, pokud jdou smazat (nahraná zůstane do příště).
     */
    private fun loadLibrary(cacheDir: File) {
        val name = System.mapLibraryName(LIBRARY)
        val bytes = LlamaNative::class.java.getResourceAsStream("$RESOURCE_ROOT/${desktopPlatform()}/$name")
            ?.use { it.readBytes() }
        if (bytes == null) {
            System.loadLibrary(LIBRARY)
            return
        }
        val root = File(cacheDir, "llamacpp")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val file = File(root, "$digest/$name")
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            file.parentFile.mkdirs()
            val part = File(file.parentFile, "$name.part")
            part.writeBytes(bytes)
            file.delete()
            if (!part.renameTo(file)) throw IOException("Could not unpack $name to ${file.parentFile}")
        }
        root.listFiles()?.filter { it.name != digest }?.forEach { it.deleteRecursively() }
        System.load(file.absolutePath)
        closeAllAtExit()
    }

    /**
     * Desktop: modely nezavřené do konce procesu uvolní hák při ukončení JVM. Jinak by na Macu
     * proces při ukončení spadl (SIGABRT): statický destruktor Metal backendu v ggml hlídá, že
     * žádný buffer nezůstal (`GGML_ASSERT([rsets->data count] == 0)`), a ten běží až po hácích
     * JVM — aplikace by musela před koncem sama zavřít `OnDeviceAi`. Na Androidu proces nikdo
     * neukončuje `exit()`em a Metal tam není, proto jen tady.
     */
    private fun closeAllAtExit() {
        Runtime.getRuntime().addShutdownHook(
            Thread({ open.toList().forEach { runCatching { it.close() } } }, "llama.cpp shutdown"),
        )
    }

    /** `darwin-aarch64`, `windows-x86_64`… jako složky v jaru (ai-llamacpp-desktop). */
    private fun desktopPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val system = when {
            "mac" in os -> "darwin"
            "windows" in os -> "windows"
            else -> "linux"
        }
        val arch = System.getProperty("os.arch").lowercase()
        return "$system-" + if ("aarch64" in arch || "arm64" in arch) "aarch64" else "x86_64"
    }

    @JvmStatic
    private external fun init()

    /** Má build GPU backend a našel zařízení (Metal na Macu)? Levné, nic nenačítá. */
    @JvmStatic
    external fun hasGpu(): Boolean

    /** [path] jako bajty UTF-8, viz hlavička `ai_engine_llama.cpp`; [gpu] = všechny vrstvy na GPU. */
    @JvmStatic
    external fun load(path: ByteArray, contextTokens: Int, threads: Int, gpu: Boolean): Long

    @JvmStatic
    external fun start(handle: Long, prompt: ByteArray, topK: Int, topP: Float, temperature: Float, maxTokens: Int): Int

    @JvmStatic
    external fun next(handle: Long): ByteArray?

    @JvmStatic
    external fun free(handle: Long)

    /** `libai_engine_llama.so` (src/main/cpp/CMakeLists.txt); totéž jméno načítá i `PlayLiteRtRuntime`. */
    const val LIBRARY = LLAMACPP_NATIVE_LIBRARY

    /** Kde je knihovna v jaru na desktopu; totéž hledá `DesktopLiteRtPlatform`. */
    private const val RESOURCE_ROOT = "/eu/tintera/ai/llamacpp/native"
}
