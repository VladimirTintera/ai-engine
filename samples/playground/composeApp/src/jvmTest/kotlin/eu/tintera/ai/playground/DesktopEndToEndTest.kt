package eu.tintera.ai.playground

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Celý playground na desktopu, jak ho použije člověk: v obrazovce modelů stáhne Qwen3 0.6B Q4,
 * otevře konverzaci, pošle dvě zprávy (druhá jde s historií) a počká na odpovědi s měřením.
 * Snímky obrazovky ukládá do `build/e2e`.
 *
 * Stahuje ≈ 400 MB, proto jen s `-Pplayground.e2e=true`; model zůstává v `build/e2e-data`.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopEndToEndTest {

    private val enabled = System.getProperty("playground.e2e") == "true"
    private val shots = File(System.getProperty("playground.e2e.dir") ?: "build/e2e")
    private val data = File(System.getProperty("playground.e2e.data") ?: "build/e2e-data")

    // v2 API pouští korutiny přes StandardTestDispatcher; tady běží stav aplikace na Dispatchers.Main
    // (Swing) a test jen čeká na UI, takže stačí původní.
    @Suppress("DEPRECATION")
    @Test
    fun downloadQwenAndChat() {
        if (!enabled) return
        shots.mkdirs()
        val playground = desktopPlayground(data.apply { mkdirs() })
        runDesktopComposeUiTest(width = 1000, height = 1100) {
            setContent { App(playground) }
            val variant = hasTestTag("variant-qwen3-0.6b-q4km")
            fun inVariant(text: String) = hasText(text) and hasAnyAncestor(variant)

            onNodeWithTag("models").performScrollToNode(variant)
            waitUntil(timeoutMillis = 30_000) { exists(inVariant("Download")) || exists(inVariant("Chat")) }
            shot("1-models")
            if (exists(inVariant("Download"))) {
                onNode(inVariant("Download")).performClick()
                waitUntil(timeoutMillis = 10_000) { exists(hasContentDescription("Pause") and hasAnyAncestor(variant)) }
                shot("2-downloading")
                waitUntil(timeoutMillis = DOWNLOAD_TIMEOUT_MS) { exists(inVariant("Chat")) }
            }
            onNodeWithTag("models").performScrollToNode(variant)
            shot("3-ready")
            onNode(inVariant("Chat")).performClick()

            send("What is the capital of the Czech Republic? Answer in one sentence.")
            waitUntil(timeoutMillis = ANSWER_TIMEOUT_MS) { exists(hasText("tokens/s", substring = true)) }
            shot("4-first-answer")

            send("And how many people live there, roughly?")
            waitUntil(timeoutMillis = ANSWER_TIMEOUT_MS) { all(hasText("tokens/s", substring = true)) >= 2 }
            shot("5-second-answer")

            val answers = playground.chat.value!!.messages.value.filter { !it.fromUser }
            answers.forEach { println("ANSWER: ${it.text}\nMETRICS: ${it.metrics}") }
            assertTrue(answers.size == 2 && answers.all { it.error == null && it.text.isNotBlank() })
            assertTrue(answers.first().metrics?.loadMs != null, "the first answer loads the model")
            // Knihovna skládá prompt šablonou modelu s vypnutým přemýšlením (Qwen3 bez `<think>`).
            assertTrue(answers.none { "<think>" in it.text }, "thinking should be off")
        }
        // Model zůstává načtený: uvolní ho knihovna při konci JVM (dřív na macOS pád při ukončení).
    }

    private fun androidx.compose.ui.test.ComposeUiTest.send(text: String) {
        onNodeWithTag("chat-input").performTextInput(text)
        onNode(hasContentDescription("Send")).performClick()
    }

    private fun SemanticsNodeInteractionsProvider.exists(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNodeInteractionsProvider.all(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        onAllNodes(matcher).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "desktop-$name.png"))
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 15 * 60_000L
        const val ANSWER_TIMEOUT_MS = 3 * 60_000L
    }
}
