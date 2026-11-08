package eu.tintera.ai.core

import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runtime LiteRT-LM se stahuje spolu s modelem ([LiteRtOnDeviceAi.download], [LiteRtRuntime]):
 * model je připravený, až když je tu obojí, a runtime jde napřed.
 */
class LiteRtRuntimeTest {

    private val root = Files.createTempDirectory("ai-litert-runtime").toFile()
    private val models = File(root, "ai-models").apply { mkdirs() }
    private val model = testModel()
    private val started: MutableList<DownloadedModel> = Collections.synchronizedList(mutableListOf())

    private val platform = object : LiteRtPlatform {
        override val modelsDir = models
        override val cacheDir = File(root, "ai-cache")
        override fun canRun(model: DownloadedModel) = true
        override fun png(path: String, maxSide: Int): ByteArray? = null
        override fun wavClips(path: String): List<ByteArray> = emptyList()
        override fun startDownload(model: DownloadedModel, unmeteredOnly: Boolean, run: suspend () -> Unit) {
            started += model
        }
        override fun cancelDownload(model: DownloadedModel) {}
        override suspend fun downloadPending(model: DownloadedModel) = false
    }

    private fun modelFile() = File(models, model.fileName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun stazenyModelSiRuntimeDoinstalujeSam() = runBlocking {
        modelFile().writeText("x")
        val runtime = FakeLiteRtRuntime(installed = false)
        val ai = LiteRtOnDeviceAi(platform, { runtime }, AlwaysOnline)

        assertEquals(OnDeviceAiStatus.Downloading, ai.status(model.variant))
        withTimeout(5.seconds) { while (runtime.installs == 0) delay(10) }
        // Další dotazy na stav instalaci nepouštějí znovu.
        assertEquals(OnDeviceAiStatus.Downloading, ai.status(model.variant))

        runtime.finish.complete(null)
        withTimeout(5.seconds) { while (ai.status(model.variant) != OnDeviceAiStatus.Ready) delay(10) }
        assertEquals(1, runtime.installs)
        assertTrue(started.isEmpty(), "model je stažený, stahovat se nemá")
    }

    @Test
    fun nepovedenaSamoinstalaceSeNeopakuje() = runBlocking {
        modelFile().writeText("x")
        val runtime = FakeLiteRtRuntime(installed = false)
        runtime.finish.complete(RuntimeInstallException(code = -2))
        val ai = LiteRtOnDeviceAi(platform, { runtime }, AlwaysOnline)

        ai.status(model.variant)
        withTimeout(5.seconds) { while (ai.status(model.variant) != OnDeviceAiStatus.Paused) delay(10) }

        assertEquals(OnDeviceAiStatus.Paused, ai.status(model.variant))
        assertEquals(1, runtime.installs)
    }

    @Test
    fun runtimeSeStahujeNapredAModelAzPoNem() = runBlocking {
        val runtime = FakeLiteRtRuntime(installed = false)
        val ai = LiteRtOnDeviceAi(platform, { runtime }, AlwaysOnline)
        val seen: MutableList<ModelDownload> = Collections.synchronizedList(mutableListOf())
        val collecting = launch { ai.download(model.variant).collect { seen += it } }

        withTimeout(5.seconds) { while (runtime.progress.last() !in seen) delay(10) }
        assertTrue(started.isEmpty(), "model se nesmí stahovat dřív, než je runtime")
        assertEquals(OnDeviceAiStatus.Downloading, ai.status(model.variant))

        runtime.finish.complete(null)
        withTimeout(5.seconds) { while (started.isEmpty()) delay(10) }
        assertEquals(listOf(model), started.toList())
        assertEquals(1, runtime.installs)
        collecting.cancel()
    }

    @Test
    fun stazenyModelDoinstalujeJenRuntime() = runBlocking {
        modelFile().writeText("x")
        val runtime = FakeLiteRtRuntime(installed = false)
        runtime.finish.complete(null)
        val ai = LiteRtOnDeviceAi(platform, { runtime }, AlwaysOnline)

        withTimeout(5.seconds) { ai.download(model.variant).toList() }

        assertTrue(started.isEmpty())
        assertEquals(OnDeviceAiStatus.Ready, ai.status(model.variant))
    }

    /** Model GGUF si doinstaluje llama.cpp, ne LiteRT-LM — každý runtime je jiná součást. */
    @Test
    fun modelSiBereSvujRuntime() = runBlocking {
        val liteRt = FakeLiteRtRuntime(installed = true)
        val llamaCpp = FakeLiteRtRuntime(installed = false)
        llamaCpp.finish.complete(null)
        val runtimes = mapOf(ModelRuntime.LiteRt to liteRt, ModelRuntime.LlamaCpp to llamaCpp)
        val ai = LiteRtOnDeviceAi(platform, runtimes::getValue, AlwaysOnline)
        val gguf = testModel("gguf", ModelRuntime.LlamaCpp)
        val collecting = launch { ai.download(gguf.variant).collect {} }

        withTimeout(5.seconds) { while (started.isEmpty()) delay(10) }
        assertEquals(listOf(gguf), started.toList())
        assertEquals(1, llamaCpp.installs)
        assertEquals(0, liteRt.installs)
        collecting.cancel()
    }

    /** Aplikace může pokaždé poslat novou instanci: je to tentýž model, stahování se jen sleduje. */
    @Test
    fun jinaInstanceTehozModeluSeNestahujePodruhe() = runBlocking {
        val ai = LiteRtOnDeviceAi(platform, { FakeLiteRtRuntime(installed = true) }, AlwaysOnline)
        val first = launch { ai.download(testModel().variant).collect {} }
        withTimeout(5.seconds) { while (started.isEmpty()) delay(10) }

        val second = launch { ai.download(testModel().variant).collect {} }
        delay(100)

        assertEquals(1, started.size)
        first.cancel()
        second.cancel()
    }

    @Test
    fun nainstalovanyRuntimeSeNestahuje() = runBlocking {
        val runtime = FakeLiteRtRuntime(installed = true)
        val ai = LiteRtOnDeviceAi(platform, { runtime }, AlwaysOnline)
        val collecting = launch { ai.download(model.variant).collect {} }

        withTimeout(5.seconds) { while (started.isEmpty()) delay(10) }
        assertEquals(0, runtime.installs)
        collecting.cancel()
    }

    @Test
    fun selhaniRuntimeUkonciStahovani() = runBlocking {
        val runtime = FakeLiteRtRuntime(installed = false)
        runtime.finish.complete(RuntimeInstallException(code = -2))
        val ai = LiteRtOnDeviceAi(platform, { runtime }, AlwaysOnline)

        assertFailsWith<RuntimeInstallException> {
            withTimeout(5.seconds) { ai.download(model.variant).toList() }
        }
        assertTrue(started.isEmpty())
    }

    @Test
    fun zastaveniBehemInstalaceRuntime() = runBlocking {
        val runtime = FakeLiteRtRuntime(installed = false)
        val ai = LiteRtOnDeviceAi(platform, { runtime }, AlwaysOnline)
        val result = async { runCatching { ai.download(model.variant).toList() }.exceptionOrNull() }

        withTimeout(5.seconds) { while (runtime.installs == 0) delay(10) }
        ai.stopDownload(model.variant)

        assertTrue(withTimeout(5.seconds) { result.await() } is DownloadStoppedException)
        // Zrušená instalace model nenaplánuje, ani kdyby Play doběhl.
        runtime.finish.complete(null)
        delay(100)
        assertTrue(started.isEmpty())
    }
}
