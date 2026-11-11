package eu.tintera.ai.core

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * Stahované modely na iOS ([LiteRtOnDeviceAi]); runtime dodá aplikace ([LinkedLiteRtRuntime]
 * s `LlamaCppEngineFactory` z :ai-llamacpp-ios).
 *
 * - **Jen runtime, které aplikace má** ([runtimes]): LiteRT-LM na iOS zatím není, jeho modely se
 *   nenabídnou (jako na desktopu bez nativní knihovny).
 * - **Modely v `Application Support/ai-models`, vyřazené ze zálohy** do iCloudu: gigabajty, které
 *   jdou kdykoli stáhnout znovu, tam podle pravidel App Store nepatří. Keš v `Caches/ai-cache`.
 * - **Paměť**: model se nenabídne pod [DownloadedModel.minMemoryGb]. Aplikace navíc potřebuje
 *   entitlement `com.apple.developer.kernel.increased-memory-limit`, jinak ji systém při načtení
 *   modelu nad zhruba polovinu paměti zabije (README).
 * - **Stahování drží korutina procesu** jako na desktopu, jen v popředí: po uspání aplikace zůstane
 *   `.part` a další stahování naváže. Placenou síť platforma nerozlišuje ([startDownload]).
 * - **GPU přes Metal** jen na zařízení; simulátor počítá na CPU ([gpu]).
 * - **Obrázky zatím ne** ([png]); zvuk dekodérem od aplikace ([audio]), ale llama.cpp ho stejně
 *   neumí — dává smysl až s LiteRT-LM.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLiteRtPlatform(
    /** Runtime, jejichž továrnu aplikace předává do [LiteRtOnDeviceAi]; modely ostatních se nenabídnou. */
    private val runtimes: Set<ModelRuntime> = setOf(ModelRuntime.LlamaCpp),
    private val audio: AudioClipDecoder = NoAudioClips,
) : LiteRtPlatform {

    private val logger = Logger.withTag("OnDeviceAi")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloads = ConcurrentMap<DownloadedModel, Job>()

    override val modelsDir: String by lazy { directory(NSApplicationSupportDirectory, MODELS_DIR, backup = false) }

    override val cacheDir: String by lazy { directory(NSCachesDirectory, CACHE_DIR, backup = true) }

    override fun canRun(model: DownloadedModel): Boolean =
        model.runtime in runtimes &&
            NSProcessInfo.processInfo.physicalMemory.toDouble() >= model.minMemoryGb * GIB * MEMORY_TOLERANCE

    /** Metal v simulátoru nemá všechno, co llama.cpp chce; LiteRT-LM by GPU zkusil a spadl na CPU sám. */
    override fun gpu(runtime: ModelRuntime): Boolean = when (runtime) {
        ModelRuntime.LiteRt -> true
        ModelRuntime.LlamaCpp -> !simulator
    }

    override fun png(path: String, maxSide: Int): ByteArray? = null

    override fun wavClips(path: String): List<ByteArray> = audio.wavClips(path)

    /** [unmeteredOnly] se přejde jako na desktopu: aplikace volbu „jen přes Wi-Fi" na iOS zatím nemá nabízet. */
    override fun startDownload(model: DownloadedModel, unmeteredOnly: Boolean, run: suspend () -> Unit) {
        while (true) {
            val running = downloads[model]
            if (running?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Stav pro obrazovku už zapsal runtime; hlášku ukáže Nastavení.
                    logger.i(e) { "Stahování ${model.displayName} skončilo chybou" }
                }
            }
            if (downloads.compareAndPut(model, running, job)) {
                job.start()
                return
            }
            job.cancel()
        }
    }

    override fun cancelDownload(model: DownloadedModel) {
        downloads.remove(model)?.cancel()
    }

    override suspend fun downloadPending(model: DownloadedModel): Boolean = downloads[model]?.isActive == true

    private fun directory(base: NSSearchPathDirectory, name: String, backup: Boolean): String {
        val root = NSFileManager.defaultManager.URLForDirectory(base, NSUserDomainMask, appropriateForURL = null, create = true, error = null)
            ?: error("No directory $base")
        val dir = root.URLByAppendingPathComponent(name, isDirectory = true) ?: error("No directory $name")
        NSFileManager.defaultManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        if (!backup && !dir.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)) {
            logger.w { "Adresář modelů se nepodařilo vyřadit ze zálohy" }
        }
        return dir.path ?: error("No path for $dir")
    }

    private companion object {
        const val MODELS_DIR = "ai-models"
        const val CACHE_DIR = "ai-cache"
        const val GIB = 1024.0 * 1024 * 1024

        /** `physicalMemory` hlásí o kus méně, než udává Apple (6 GB iPhone kolem 5,7 GB). */
        const val MEMORY_TOLERANCE = 0.85

        val simulator: Boolean = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null
    }
}

