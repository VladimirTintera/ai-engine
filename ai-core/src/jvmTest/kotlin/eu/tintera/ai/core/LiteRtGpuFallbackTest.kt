package eu.tintera.ai.core

import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.engine.LlmContent
import eu.tintera.ai.engine.LlmConversation
import eu.tintera.ai.engine.LlmEngine
import eu.tintera.ai.engine.LlmEngineConfig
import eu.tintera.ai.engine.LlmEngineFactory
import eu.tintera.ai.engine.LlmSampling
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * GPU, které se načte bez chyby a selže až při generování (bez OpenCL), nahradí CPU
 * (`LiteRtOnDeviceAi.withEngine`): dotaz se zopakuje jen před prvním tokenem a GPU se pak
 * do konce procesu nezkouší.
 */
class LiteRtGpuFallbackTest {

    private val root = Files.createTempDirectory("ai-litert-gpu").toFile()
    private var gpuRuntimes = setOf(ModelRuntime.LiteRt)
    private val models = File(root, "ai-models").apply { mkdirs() }

    private val platform = object : LiteRtPlatform {
        override val modelsDir = models.path
        override val cacheDir = File(root, "ai-cache").path
        override fun canRun(model: DownloadedModel) = true
        override fun gpu(runtime: ModelRuntime) = runtime in gpuRuntimes
        override fun png(path: String, maxSide: Int): ByteArray? = null
        override fun wavClips(path: String): List<ByteArray> = emptyList()
        override fun startDownload(model: DownloadedModel, unmeteredOnly: Boolean, run: suspend () -> Unit) {}
        override fun cancelDownload(model: DownloadedModel) {}
        override suspend fun downloadPending(model: DownloadedModel) = false
    }

    private val litert = testModel("litert")
    private val other = testModel("other")
    private val gguf = testModel("gguf", ModelRuntime.LlamaCpp)

    private val engines = FakeEngines()
    private val ai = LiteRtOnDeviceAi(platform, { FakeLiteRtRuntime(engines = engines) }, AlwaysOnline)

    init {
        listOf(other, litert, gguf).forEach { File(models, it.fileName).writeText("x") }
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun gpuSelzePredPrvnimTokenem_dotazSeZopakujeNaCpu() = runBlocking {
        engines.reply = { backend -> if (backend == LlmBackend.Gpu) noOpenCl() else flowOf("Ahoj", " světe") }

        val result = ai.generate(litert.variant, "Shrň").last()

        assertEquals("Ahoj světe", result.text)
        assertEquals(listOf(LlmBackend.Gpu, LlmBackend.Cpu), engines.opened.map { it.backend })
        assertEquals(listOf(LlmBackend.Gpu), engines.closed)
    }

    @Test
    fun poNahradeSeGpuDoKonceProcesuNezkousi() = runBlocking {
        engines.reply = { backend -> if (backend == LlmBackend.Gpu) noOpenCl() else flowOf("ok") }
        ai.generate(litert.variant, "Shrň").last()

        // Jiný model zavře ten první a načte se znovu — rovnou na CPU.
        ai.generate(other.variant, "Shrň").last()

        assertEquals(listOf(LlmBackend.Gpu, LlmBackend.Cpu, LlmBackend.Cpu), engines.opened.map { it.backend })
    }

    @Test
    fun chybaPoPrvnimTokenuSeNeopakuje() = runBlocking {
        // Chyba až po tom, co sběrač přírůstek dostal: `generate` běží přes `flowOn`, a chyba
        // hned po `emit` by sběrače zrušila dřív, než si přírůstek z bufferu vezme (na CI s dvěma
        // jádry se to stávalo).
        val received = CompletableDeferred<Unit>()
        engines.reply = {
            flow {
                emit("Za")
                received.await()
                throw IllegalStateException("Model failed mid-answer")
            }
        }

        val emitted = mutableListOf<Generated>()
        val error = assertFailsWith<OnDeviceAiException> {
            ai.generate(litert.variant, "Shrň").collect {
                emitted += it
                received.complete(Unit)
            }
        }

        assertEquals(AiFailureReason.ModelError, error.reason)
        assertEquals(listOf(Generated("Za")), emitted)
        assertEquals(listOf(LlmBackend.Gpu), engines.opened.map { it.backend })
    }

    @Test
    fun zruseniSeNeopakuje() = runBlocking {
        val sending = CompletableDeferred<Unit>()
        engines.reply = {
            flow {
                sending.complete(Unit)
                awaitCancellation()
            }
        }

        val job = launch(Dispatchers.Default) { ai.generate(litert.variant, "Shrň").collect {} }
        sending.await()
        job.cancel()
        job.join()

        assertEquals(listOf(LlmBackend.Gpu), engines.opened.map { it.backend })
        assertEquals(1, engines.opened.single().cancels)
    }

    @Test
    fun selzeLiCpuStejneBrzy_gpuSePristeZkusiZnovu() = runBlocking {
        engines.reply = { backend -> flow { throw IllegalArgumentException("$backend: input too long") } }

        val error = assertFailsWith<OnDeviceAiException> {
            ai.generate(litert.variant, "Shrň").toList()
        }
        // Ven jde chyba z CPU, ta z GPU je u ní. Korutiny při obnově stack trace výjimku
        // zkopírují a původní dají za příčinu, proto se hledá v celém řetězu.
        val causes = generateSequence(error.cause) { it.cause }.toList()
        assertEquals("Cpu: input too long", causes.last().message)
        assertEquals(listOf("Gpu: input too long"), causes.flatMap { it.suppressed.toList() }.map { it.message }.distinct())

        engines.reply = { flowOf("ok") }
        ai.generate(other.variant, "Shrň").last()

        assertEquals(listOf(LlmBackend.Gpu, LlmBackend.Cpu, LlmBackend.Gpu), engines.opened.map { it.backend })
    }

    @Test
    fun gpuKtereNejdeNacist_nahradiCpuHned() = runBlocking {
        engines.failOpen = { it == LlmBackend.Gpu }
        engines.reply = { flowOf("ok") }

        ai.generate(litert.variant, "Shrň").last()
        ai.generate(other.variant, "Shrň").last()

        assertEquals(listOf(LlmBackend.Gpu, LlmBackend.Cpu, LlmBackend.Cpu), engines.attempts)
    }

    @Test
    fun runtimeBezGpu_seNaciteRovnouNaCpu() = runBlocking {
        engines.reply = { flowOf("ok") }

        ai.generate(gguf.variant, "Shrň").last()

        assertEquals(listOf(LlmBackend.Cpu), engines.attempts)
    }

    @Test
    fun selhaniGpuJednohoRuntime_nevezmeGpuDruhemu() = runBlocking {
        // Mac: Metal v llama.cpp selže, GPU v LiteRT-LM se pořád zkusí.
        gpuRuntimes = setOf(ModelRuntime.LiteRt, ModelRuntime.LlamaCpp)
        engines.failOpen = { it == LlmBackend.Gpu }
        engines.reply = { flowOf("ok") }
        ai.generate(gguf.variant, "Shrň").last()

        engines.failOpen = { false }
        ai.generate(litert.variant, "Shrň").last()
        ai.generate(gguf.variant, "Shrň").last()

        assertEquals(listOf(LlmBackend.Gpu, LlmBackend.Cpu, LlmBackend.Gpu, LlmBackend.Cpu), engines.attempts)
    }

    /** Prodlevu před uvolněním aplikace určuje podle modelu; čte se po každém použití. */
    @Test
    fun prodlevaUvolneniSePtaNaModel() = runBlocking {
        engines.reply = { flowOf("ok") }
        val asked = CompletableDeferred<DownloadedModel>()
        val ai = LiteRtOnDeviceAi(
            platform,
            { FakeLiteRtRuntime(engines = engines) },
            AlwaysOnline,
            idleUnloadMs = { model ->
                asked.complete(model)
                60_000
            },
        )

        ai.generate(litert.variant, "Shrň").last()

        assertEquals(litert, withTimeout(5.seconds) { asked.await() })
    }

    /** Tak to hlásí LiteRT-LM 0.19.1 na zařízení bez OpenCL, až z `onError` konverzace. */
    private fun noOpenCl(): Flow<String> = flow {
        throw IllegalStateException("UNKNOWN: Can not find OpenCL library on this device")
    }

    /** Engine LiteRT-LM pro test: odpověď podle backendu, záznam načtení a zavření. */
    private class FakeEngines : LlmEngineFactory {
        var failOpen: (LlmBackend) -> Boolean = { false }
        var reply: (LlmBackend) -> Flow<String> = { flowOf() }

        val attempts = mutableListOf<LlmBackend>()
        val opened = mutableListOf<FakeEngine>()
        val closed = mutableListOf<LlmBackend>()

        override fun open(config: LlmEngineConfig): LlmEngine {
            attempts += config.backend
            if (failOpen(config.backend)) throw IllegalStateException("No ${config.backend}")
            return FakeEngine(config.backend).also { opened += it }
        }

        inner class FakeEngine(val backend: LlmBackend) : LlmEngine {
            var cancels = 0

            override fun conversation(sampling: LlmSampling) = object : LlmConversation {
                override fun send(content: List<LlmContent>, maxOutputTokens: Int?) = reply(backend)
                override fun cancel() {
                    cancels++
                }
                override fun close() {}
            }

            override fun close() {
                closed += backend
            }
        }
    }
}
