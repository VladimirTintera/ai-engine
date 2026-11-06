package eu.tintera.plume.ai.llamacpp

import eu.tintera.plume.ai.engine.LlmBackend
import eu.tintera.plume.ai.engine.LlmContent
import eu.tintera.plume.ai.engine.LlmEngineConfig
import eu.tintera.plume.ai.engine.LlmSampling
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Knihovna z jaru se vybalí, načte a JNI sedí na `LlamaNative` — bez modelu, takže běží
 * při každém `test`. Model se pustí jen s `-PllamaCppTestModel=<soubor.gguf>` (gigabajty).
 *
 * Bez knihovny v jaru (build bez CMake nebo překladače) se testy přeskočí: přeskočil ji už
 * `buildLlamaCppDesktop` a na CI by tam spadl dřív.
 */
class LlamaCppDesktopTest {

    private val cacheDir = createTempDirectory("plume-llamacpp-test").toFile().apply { deleteOnExit() }

    private val bundled = LlamaCppEngineFactory::class.java.getResource("/eu/tintera/plume/ai/llamacpp/native") != null

    @Test
    fun missingModelFailsInsteadOfCrashing() {
        if (!bundled) return println("Knihovna llama.cpp v jaru není, test přeskočen.")
        val missing = File(cacheDir, "chybí 😊.gguf").path
        assertFailsWith<IOException> { LlamaCppEngineFactory().open(config(missing)) }
    }

    @Test
    fun modelAnswers() {
        val model = System.getProperty("llamaCppTestModel") ?: return
        check(bundled) { "Knihovna llama.cpp v jaru není" }
        val started = System.nanoTime()
        LlamaCppEngineFactory().open(config(model)).use { engine ->
            val loaded = System.nanoTime()
            val pieces = runBlocking {
                engine.conversation(LlmSampling(topK = 40, topP = 0.95, temperature = 0.7))
                    .send(listOf(LlmContent.Text("Ahoj 😊 Napiš jednou větou česky, co je Praha.")), maxOutputTokens = 64)
                    .toList()
            }
            val answer = pieces.joinToString("")
            val done = System.nanoTime()
            println("Načtení ${(loaded - started) / 1_000_000} ms, odpověď ${(done - loaded) / 1_000_000} ms, ${pieces.size} kusů: $answer")
            assertTrue(answer.isNotBlank())
        }
    }

    private fun config(model: String) = LlmEngineConfig(
        modelPath = model,
        backend = LlmBackend.Cpu,
        audio = false,
        vision = false,
        maxNumTokens = 2_048,
        cacheDir = cacheDir.path,
    )
}
