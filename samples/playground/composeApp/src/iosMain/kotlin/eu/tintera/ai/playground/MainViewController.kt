package eu.tintera.ai.playground

import androidx.compose.ui.window.ComposeUIViewController
import eu.tintera.ai.core.AppleOnDeviceAi
import eu.tintera.ai.core.Connectivity
import eu.tintera.ai.core.DownloadedModel
import eu.tintera.ai.core.IosLiteRtPlatform
import eu.tintera.ai.core.IosMeteredNetwork
import eu.tintera.ai.core.IosOnDeviceModel
import eu.tintera.ai.core.LinkedLiteRtRuntime
import eu.tintera.ai.core.LiteRtOnDeviceAi
import eu.tintera.ai.core.LiteRtRuntime
import eu.tintera.ai.core.ModelRuntime
import eu.tintera.ai.core.ModelVariant
import eu.tintera.ai.core.NoopOnDeviceAi
import eu.tintera.ai.core.RoutingOnDeviceAi
import eu.tintera.ai.llamacpp.LlamaCppEngineFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Foundation.NSFileManager
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDomainMask
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIViewController
import platform.darwin.dispatch_get_main_queue

/**
 * Zapojení knihovny na iOS (README): GGUF přes llama.cpp slinkovaný ve frameworku
 * (`LinkedLiteRtRuntime { LlamaCppEngineFactory() }`), `IosLiteRtPlatform` se stahováním v sezení
 * `NSURLSession` na pozadí a `IosMeteredNetwork` pro „jen přes Wi-Fi". Apple Intelligence, když
 * Swift před startem zaregistroval model (`IosOnDeviceModel.model`, ios/AppleTextModel.swift).
 *
 * Swift musí ještě předávat události sezení z app delegate
 * (`IosModelDownloads.shared.handleEventsForBackgroundURLSession`, iosApp/iOSApp.swift).
 */
private val playground: Playground by lazy {
    val runtimes: Map<ModelRuntime, LiteRtRuntime> = mapOf(
        ModelRuntime.LlamaCpp to TimedRuntime(LinkedLiteRtRuntime { LlamaCppEngineFactory() }),
    )
    val liteRt = LiteRtOnDeviceAi(
        IosLiteRtPlatform(), // modely v Application Support/ai-models, mimo zálohu do iCloudu
        runtimes::getValue,
        IosConnectivity,
        meteredNetwork = IosMeteredNetwork(),
    )
    val apple = IosOnDeviceModel.model?.let(::AppleOnDeviceAi)
    Playground(
        PlaygroundEnvironment(
            ai = RoutingOnDeviceAi(geminiNano = NoopOnDeviceAi, liteRt = liteRt, apple = apple ?: NoopOnDeviceAi),
            dataDir = applicationSupportDir(),
            platformName = "iOS",
            wifiOnlySupported = true,
            systemModels = listOf(
                SystemModel(
                    ModelVariant.Apple,
                    "Apple Intelligence",
                    "Foundation Models on iOS 26+ with Apple Intelligence enabled.",
                ),
            ),
            unsupportedReason = ::iosUnsupportedReason,
        ),
    )
}

@Suppress("FunctionName", "unused") // Volá Swift (ContentView.swift).
fun MainViewController(): UIViewController = ComposeUIViewController { App(playground) }

@OptIn(ExperimentalForeignApi::class)
private fun applicationSupportDir(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        NSApplicationSupportDirectory,
        NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return requireNotNull(url?.path) { "No Application Support directory" } + "/playground"
}

private fun iosUnsupportedReason(model: DownloadedModel): String {
    if (model.runtime != ModelRuntime.LlamaCpp) return "LiteRT-LM is not available on iOS yet; use a GGUF variant."
    val memoryGb = NSProcessInfo.processInfo.physicalMemory.toDouble() / 1_073_741_824.0
    return "needs ${model.minMemoryGb} GB of RAM, this device has ${oneDecimal(memoryGb)} GB."
}

/** Připojení podle `NWPathMonitor`; `Connectivity` dodává aplikace (README). */
private object IosConnectivity : Connectivity {
    override val online: Flow<Boolean> = callbackFlow {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            trySend(nw_path_get_status(path) == nw_path_status_satisfied)
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
        awaitClose { nw_path_monitor_cancel(monitor) }
    }.distinctUntilChanged()
}
