package eu.tintera.ai.llamacpp

import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.engine.LlmContent
import eu.tintera.ai.engine.LlmEngineConfig
import eu.tintera.ai.engine.LlmSampling
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private val cacheDir = createTempDirectory("ai-llamacpp-test").toFile().apply { deleteOnExit() }

    private val bundled = LlamaCppEngineFactory::class.java.getResource("/eu/tintera/ai/llamacpp/native") != null

    /** GPU jen tam, kde ho build má (Metal na Macu s Apple Silicon); jinde ho factory odmítne. */
    private val gpu = System.getProperty("os.name").lowercase().contains("mac") && System.getProperty("os.arch") == "aarch64"

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
        for (backend in listOfNotNull(LlmBackend.Cpu, LlmBackend.Gpu.takeIf { gpu })) {
            val started = System.nanoTime()
            LlamaCppEngineFactory().open(config(model, backend)).use { engine ->
                val loaded = System.nanoTime()
                val pieces = runBlocking {
                    engine.conversation(LlmSampling(topK = 40, topP = 0.95, temperature = 0.7))
                        .send(listOf(LlmContent.Text("Ahoj 😊 Napiš jednou větou česky, co je Praha.")), maxOutputTokens = 64)
                        .toList()
                }
                val answer = pieces.joinToString("")
                val done = System.nanoTime()
                println("$backend: načtení ${(loaded - started) / 1_000_000} ms, odpověď ${(done - loaded) / 1_000_000} ms, ${pieces.size} kusů: $answer")
                assertTrue(answer.isNotBlank())
                // Prompt jde šablonou z GGUF: přemýšlení vypnuté (Qwen3) a konec odpovědi jako token
                // modelu, ne text cizí šablony (Gemma 4 s ChatML končila „<|im_end|>").
                assertFalse("<think>" in answer, "Přemýšlení mělo být vypnuté: $answer")
                assertFalse("<|im_end|>" in answer, "Šablona chatu nesedí: $answer")
            }
        }
    }

    /**
     * Model nechaný otevřený do konce procesu. Bez háku při ukončení JVM by na Macu proces spadl
     * (SIGABRT ve statickém destruktoru Metal backendu) a Gradle by testovací JVM ohlásil jako
     * selhané — kontrolou je tedy samotný konec testu.
     */
    @Test
    fun engineLeftOpenDoesNotCrashAtExit() {
        val model = System.getProperty("llamaCppTestModel") ?: return
        val engine = LlamaCppEngineFactory().open(config(model, if (gpu) LlmBackend.Gpu else LlmBackend.Cpu))
        val pieces = runBlocking {
            engine.conversation(LlmSampling(topK = 40, topP = 0.95, temperature = 0.7))
                .send(listOf(LlmContent.Text("Hi")), maxOutputTokens = 4)
                .toList()
        }
        assertTrue(pieces.isNotEmpty())
        // Záměrně bez engine.close().
    }

    @Test
    fun gpuWithoutBackendFailsBeforeLoading() {
        if (!bundled) return println("Knihovna llama.cpp v jaru není, test přeskočen.")
        if (gpu) return
        val error = assertFailsWith<IOException> { LlamaCppEngineFactory().open(config(File(cacheDir, "x.gguf").path, LlmBackend.Gpu)) }
        assertTrue("GPU" in error.message.orEmpty())
    }

    private fun config(model: String, backend: LlmBackend = LlmBackend.Cpu) = LlmEngineConfig(
        modelPath = model,
        backend = backend,
        audio = false,
        vision = false,
        maxNumTokens = 2_048,
        cacheDir = cacheDir.path,
    )
}
