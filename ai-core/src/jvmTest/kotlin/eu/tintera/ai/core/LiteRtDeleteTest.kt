package eu.tintera.ai.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** Smazání modelu uvolní i jeho zkompilovanou keš ([LiteRtOnDeviceAi.delete], [DownloadedModel.ownsCacheFile]). */
class LiteRtDeleteTest {

    private val root = Files.createTempDirectory("ai-litert-delete").toFile()
    private val models = File(root, "ai-models").apply { mkdirs() }
    private val cache = File(root, "ai-cache").apply { mkdirs() }

    private val platform = object : LiteRtPlatform {
        override val modelsDir = models.path
        override val cacheDir = cache.path
        override fun canRun(model: DownloadedModel) = true
        override fun png(path: String, maxSide: Int): ByteArray? = null
        override fun wavClips(path: String): List<ByteArray> = emptyList()
        override fun startDownload(model: DownloadedModel, unmeteredOnly: Boolean, run: suspend () -> Unit) {}
        override fun cancelDownload(model: DownloadedModel) {}
        override suspend fun downloadPending(model: DownloadedModel) = false
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun smazaniModeluSmazeIJehoKes() = runBlocking {
        val files = listOf(
            File(models, "large.litertlm"),
            File(cache, "large.litertlm_1793947790_3659530240.xnnpack_cache"),
            File(cache, "large.litertlm_1793947790_3659530240.audio_adapter.xnnpack_cache"),
            File(models, "large.litertlm_1793947790_3659530240.xnnpack_cache"),
            File(cache, "large.litertlm_1793947790_3659530240_mldrift_weight_cache.bin"),
            File(cache, "large.litertlm_1793947790_3659530240_mldrift_program_cache.bin"),
            // Cizí: jiný model a jeho keš zůstávají.
            File(models, "small.litertlm"),
            File(cache, "small.litertlm_1793947572_2588147712.xnnpack_cache"),
        )
        files.forEach { it.writeText("x") }

        LiteRtOnDeviceAi(platform, { FakeLiteRtRuntime() }, AlwaysOnline).delete(testModel("large").variant)

        assertEquals(
            setOf("small.litertlm", "small.litertlm_1793947572_2588147712.xnnpack_cache"),
            files.filter { it.exists() }.map { it.name }.toSet(),
        )
    }
}
