package eu.tintera.ai.llamacpp

import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.engine.LlmContent
import eu.tintera.ai.engine.LlmEngineConfig
import eu.tintera.ai.engine.LlmSampling
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory

/**
 * Knihovna je slinkovaná a cinterop sedí na jádro — bez modelu, takže běží při každém
 * `iosSimulatorArm64Test`. Model se pustí jen s `-PllamaCppTestModel=<soubor.gguf>`.
 * V simulátoru jen CPU (Metal tam build nemá).
 */
class LlamaCppIosTest {

    @Test
    fun missingModelFailsInsteadOfCrashing() {
        assertFailsWith<IllegalStateException> { LlamaCppEngineFactory().open(config(NSTemporaryDirectory() + "chybí 😊.gguf")) }
    }

    @Test
    fun gpuWithoutBackendFailsBeforeLoading() {
        assertFailsWith<IllegalStateException> {
            LlamaCppEngineFactory().open(config(NSTemporaryDirectory() + "chybí.gguf", LlmBackend.Gpu))
        }
    }

    @Test
    fun modelAnswers() {
        val model = NSProcessInfo.processInfo.environment["LLAMA_TEST_MODEL"] as String? ?: return
        val started = TimeSource.Monotonic.markNow()
        LlamaCppEngineFactory().open(config(model)).use { engine ->
            val loaded = started.elapsedNow()
            val pieces = runBlocking {
                engine.conversation(LlmSampling(topK = 40, topP = 0.95, temperature = 0.7))
                    .send(listOf(LlmContent.Text("Ahoj 😊 Napiš jednou větou česky, co je Praha.")), maxOutputTokens = 64)
                    .toList()
            }
            val answer = pieces.joinToString("")
            println("CPU: načtení $loaded, odpověď ${started.elapsedNow() - loaded}, ${pieces.size} kusů: $answer")
            assertTrue(answer.isNotBlank())
            // Šablona z GGUF: bez přemýšlení (Qwen3) a bez textu cizí šablony (Gemma 4 s ChatML).
            assertFalse("<think>" in answer, "Přemýšlení mělo být vypnuté: $answer")
            assertFalse("<|im_end|>" in answer, "Šablona chatu nesedí: $answer")
        }
    }

    private fun config(path: String, backend: LlmBackend = LlmBackend.Cpu) = LlmEngineConfig(
        modelPath = path,
        backend = backend,
        audio = false,
        vision = false,
        maxNumTokens = 2048,
        cacheDir = NSTemporaryDirectory(),
    )
}
