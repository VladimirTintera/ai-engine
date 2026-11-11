package eu.tintera.ai.playground

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.tintera.ai.core.BundledLiteRtRuntime
import eu.tintera.ai.core.Connectivity
import eu.tintera.ai.core.DesktopLiteRtPlatform
import eu.tintera.ai.core.DownloadedModel
import eu.tintera.ai.core.LiteRtOnDeviceAi
import eu.tintera.ai.core.LiteRtRuntime
import eu.tintera.ai.core.ModelRuntime
import eu.tintera.ai.core.NoopOnDeviceAi
import eu.tintera.ai.core.RoutingOnDeviceAi
import eu.tintera.ai.engine.LITERT_ENGINE_FACTORY
import eu.tintera.ai.engine.LLAMACPP_ENGINE_FACTORY
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Desktop: `DesktopLiteRtPlatform` + `BundledLiteRtRuntime` (README). Runtime jsou v aplikaci na
 * classpath (`runtimeOnly` :ai-litert a :ai-llamacpp-desktop v build.gradle.kts), aplikace běží na
 * Javě 21 (`compose.desktop.application.javaHome`). Placenou síť JVM nepozná, volba „jen přes
 * Wi-Fi" se proto nenabízí, a systémový model tu žádný není.
 */
fun main() {
    val playground = desktopPlayground(desktopDataDir())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "AI Engine Playground",
            state = rememberWindowState(width = 960.dp, height = 900.dp),
        ) {
            App(playground)
        }
    }
}

/** Zapojení knihovny na desktopu; [dataDir] = místní adresář dat aplikace (modely v `ai-models`). */
fun desktopPlayground(dataDir: File): Playground {
    val runtimes: Map<ModelRuntime, LiteRtRuntime> = mapOf(
        ModelRuntime.LiteRt to TimedRuntime(BundledLiteRtRuntime(LITERT_ENGINE_FACTORY)),
        ModelRuntime.LlamaCpp to TimedRuntime(BundledLiteRtRuntime(LLAMACPP_ENGINE_FACTORY)),
    )
    val liteRt = LiteRtOnDeviceAi(DesktopLiteRtPlatform(dataDir), runtimes::getValue, AlwaysOnline)
    return Playground(
        PlaygroundEnvironment(
            ai = RoutingOnDeviceAi(geminiNano = NoopOnDeviceAi, liteRt = liteRt),
            dataDir = dataDir.absolutePath,
            platformName = "Desktop",
            wifiOnlySupported = false,
            systemModels = emptyList(),
            unsupportedReason = ::desktopUnsupportedReason,
        ),
    )
}

/** Desktop o síti nic neví; stahování po výpadku naváže samo (opakování v knihovně). */
private object AlwaysOnline : Connectivity {
    override val online: Flow<Boolean> = flowOf(true)
}

/**
 * Místní (neroamingový) adresář dat: modely mají gigabajty (`DesktopLiteRtPlatform` dá modely do
 * podsložky `ai-models`).
 */
private fun desktopDataDir(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        "mac" in os -> File(home, "Library/Application Support")
        "win" in os -> System.getenv("LOCALAPPDATA")?.let(::File) ?: File(home, "AppData/Local")
        else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
    }
    return File(base, "ai-engine-playground").apply { mkdirs() }
}

private fun desktopUnsupportedReason(model: DownloadedModel): String {
    val totalGb = ((ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.totalMemorySize ?: 0L) / 1_073_741_824.0
    if (totalGb < model.minMemoryGb * 0.85) {
        return "needs ${model.minMemoryGb} GB of RAM, this computer has ${oneDecimal(totalGb)} GB."
    }
    return when (model.runtime) {
        ModelRuntime.LiteRt -> "LiteRT-LM needs Java 21 and its native library for this OS and CPU (none for Intel Macs)."
        ModelRuntime.LlamaCpp -> "the llama.cpp library was not built for this computer (needs CMake at build time; AVX2 on x86_64)."
    }
}
