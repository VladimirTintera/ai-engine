package eu.tintera.ai.playground

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import com.google.android.play.core.splitcompat.SplitCompat
import eu.tintera.ai.core.DownloadedModel
import eu.tintera.ai.core.LiteRtOnDeviceAi
import eu.tintera.ai.core.AndroidLiteRtPlatform
import eu.tintera.ai.core.AndroidMeteredNetwork
import eu.tintera.ai.core.LiteRtRuntime
import eu.tintera.ai.core.MlKitOnDeviceAi
import eu.tintera.ai.core.ModelRuntime
import eu.tintera.ai.core.ModelVariant
import eu.tintera.ai.core.PlayLiteRtRuntime
import eu.tintera.ai.core.RoutingOnDeviceAi
import eu.tintera.ai.core.SplitInstallConfirmations
import eu.tintera.ai.engine.LITERT_ENGINE_FACTORY
import eu.tintera.ai.engine.LLAMACPP_ENGINE_FACTORY
import eu.tintera.ai.engine.LLAMACPP_NATIVE_LIBRARY
import eu.tintera.ai.playground.catalog.Catalog

/**
 * Zapojení knihovny na Androidu (README, „Wire the providers"). Jedna instance na proces —
 * `PlaygroundDownloadWorker` po zabití procesu potřebuje TÝŽ `LiteRtOnDeviceAi`, jehož stav
 * ukazuje obrazovka, a najde ho tady.
 */
class PlaygroundApplication : Application() {

    /** Žádosti Google Play o souhlas s instalací runtime; dialog ukazuje `MainActivity`. */
    val confirmations = SplitInstallConfirmations()

    lateinit var liteRt: LiteRtOnDeviceAi
        private set

    lateinit var playground: Playground
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Součást z Play doinstalovaná za běhu je vidět až po SplitCompat.install.
        SplitCompat.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        val runtimes: Map<ModelRuntime, LiteRtRuntime> = mapOf(
            // Jméno součásti = jméno modulu Gradle (:litertFeature, :llamacppFeature).
            ModelRuntime.LiteRt to TimedRuntime(
                PlayLiteRtRuntime(
                    this,
                    module = "litertFeature",
                    nativeLibrary = "litertlm_jni",
                    factoryClass = LITERT_ENGINE_FACTORY,
                    confirmations = confirmations,
                ),
            ),
            ModelRuntime.LlamaCpp to TimedRuntime(
                PlayLiteRtRuntime(
                    this,
                    module = "llamacppFeature",
                    nativeLibrary = LLAMACPP_NATIVE_LIBRARY,
                    factoryClass = LLAMACPP_ENGINE_FACTORY,
                    confirmations = confirmations,
                ),
            ),
        )
        liteRt = LiteRtOnDeviceAi(
            // WorkManager si pamatuje JMÉNO třídy úlohy, proto podtřída v aplikaci.
            AndroidLiteRtPlatform(this, PlaygroundDownloadWorker::class.java),
            runtimes::getValue,
            AndroidConnectivity(this),
            meteredNetwork = AndroidMeteredNetwork(this),
        )
        playground = Playground(
            PlaygroundEnvironment(
                ai = RoutingOnDeviceAi(geminiNano = MlKitOnDeviceAi(), liteRt = liteRt),
                dataDir = filesDir.absolutePath,
                platformName = "Android",
                wifiOnlySupported = true,
                systemModels = listOf(
                    SystemModel(
                        ModelVariant.GeminiNano(),
                        "Gemini Nano",
                        "Android AICore via ML Kit GenAI; only on supported phones, not on the emulator.",
                    ),
                ),
                unsupportedReason = ::unsupportedReason,
            ),
        )
    }

    /**
     * Najde model podle id, i vlastní: úloha stahování po zabití procesu zná jen id.
     */
    fun modelById(id: String): DownloadedModel? =
        Catalog.asSequence().flatMap { it.models }.flatMap { it.variants }.map { it.model }.firstOrNull { it.id == id }
            ?: playground.models.customModels.value.map { it.variant.model }.firstOrNull { it.id == id }

    /** Proč model nejde; podmínky `AndroidLiteRtPlatform.canRun`. */
    private fun unsupportedReason(model: DownloadedModel): String {
        if (Build.VERSION.SDK_INT < 31) return "downloaded models need Android 12 or newer."
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) return "downloaded models need an arm64 device (or an arm64 emulator)."
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return "needs ${model.minMemoryGb} GB of RAM, this device has ${oneDecimal(memory.totalMem / 1_073_741_824.0)} GB."
    }
}
