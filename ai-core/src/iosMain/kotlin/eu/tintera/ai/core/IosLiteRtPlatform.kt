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
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSProcessInfo

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
 * - **Soubor stahuje systém** sezením `NSURLSession` na pozadí ([IosModelDownloads],
 *   [BackgroundModelDownloads]): stahování jde dál i v uspané nebo systémem ukončené aplikaci.
 *   Korutina procesu ([startDownload]) na něj jen čeká a hlásí průběh; po dalším spuštění se
 *   k běžící úloze připojí ([downloadPending]).
 * - **„Jen přes Wi-Fi" hlídá systém**: úloha běží v sezení bez placených sítí a na mobilních datech
 *   nebo hotspotu čeká, i v uspané aplikaci. Změna volby za běhu úlohu přenese do druhého sezení
 *   ([setDownloadNetwork]). Placenou síť pro obrazovku hlásí [IosMeteredNetwork].
 * - **GPU přes Metal** jen na zařízení; simulátor počítá na CPU ([gpu]).
 * - **Obrázky zatím ne** ([png]); zvuk dekodérem od aplikace ([audio]), ale llama.cpp ho stejně
 *   neumí — dává smysl až s LiteRT-LM.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLiteRtPlatform internal constructor(
    private val runtimes: Set<ModelRuntime>,
    private val audio: AudioClipDecoder,
    /** Přenos souboru modelu; v aplikaci sezení na pozadí, v testech obyčejné. */
    internal val fileDownloader: BackgroundModelDownloads,
    modelsDir: () -> String,
) : LiteRtPlatform {

    constructor(
        /** Runtime, jejichž továrnu aplikace předává do [LiteRtOnDeviceAi]; modely ostatních se nenabídnou. */
        runtimes: Set<ModelRuntime> = setOf(ModelRuntime.LlamaCpp),
        audio: AudioClipDecoder = NoAudioClips,
    ) : this(runtimes, audio, IosModelDownloads.downloads, { iosModelsDir })

    private val logger = Logger.withTag("OnDeviceAi")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloads = ConcurrentMap<DownloadedModel, Job>()

    override val modelsDir: String by lazy(modelsDir)

    override val cacheDir: String by lazy { iosDirectory(NSCachesDirectory, CACHE_DIR, backup = true) }

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

    /**
     * [unmeteredOnly] tu nic nemění: podmínku dostane přenos souboru od `LiteRtOnDeviceAi`
     * ([BackgroundModelDownloads.download]) a hlídá ji systém.
     */
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

    /**
     * Zastaví korutinu i úlohu sezení na pozadí — ta může běžet i bez korutiny (z minulého spuštění,
     * dokud se k ní obrazovka nepřipojila). Stažená část zůstane jako resume data.
     */
    /** Úlohu, která běží nebo čeká na síť, přenese do sezení s novou podmínkou; korutina čeká dál. */
    override fun setDownloadNetwork(model: DownloadedModel, unmeteredOnly: Boolean) {
        scope.launch { fileDownloader.setNetwork(model, unmeteredOnly) }
    }

    override fun cancelDownload(model: DownloadedModel) {
        downloads.remove(model)?.cancel()
        scope.launch { fileDownloader.pause(model) }
    }

    /** Běží korutina, nebo úloha sezení na pozadí (i z minulého spuštění aplikace)? */
    override suspend fun downloadPending(model: DownloadedModel): Boolean =
        downloads[model]?.isActive == true || fileDownloader.running(model)

    private companion object {
        const val CACHE_DIR = "ai-cache"
        const val GIB = 1024.0 * 1024 * 1024

        /** `physicalMemory` hlásí o kus méně, než udává Apple (6 GB iPhone kolem 5,7 GB). */
        const val MEMORY_TOLERANCE = 0.85

        val simulator: Boolean = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null
    }
}

