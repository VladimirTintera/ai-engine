package eu.tintera.ai.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** Smazání modelu uvolní i jeho zkompilovanou keš ([LiteRtOnDeviceAi.delete], [LiteRtModel.ownsCacheFile]). */
class LiteRtDeleteTest {

    private val root = Files.createTempDirectory("ai-litert-delete").toFile()
    private val models = File(root, "ai-models").apply { mkdirs() }
    private val cache = File(root, "ai-cache").apply { mkdirs() }

    private val platform = object : LiteRtPlatform {
        override val modelsDir = models
        override val cacheDir = cache
        override fun canRun(model: LiteRtModel) = true
        override fun png(path: String, maxSide: Int): ByteArray? = null
        override fun wavClips(path: String): List<ByteArray> = emptyList()
        override fun startDownload(model: LiteRtModel, unmeteredOnly: Boolean, run: suspend () -> Unit) {}
        override fun cancelDownload(model: LiteRtModel) {}
        override suspend fun downloadPending(model: LiteRtModel) = false
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun smazaniModeluSmazeIJehoKes() = runBlocking {
        val files = listOf(
            File(models, "gemma-4-E4B-it.litertlm"),
            File(cache, "gemma-4-E4B-it.litertlm_1793947790_3659530240.xnnpack_cache"),
            File(cache, "gemma-4-E4B-it.litertlm_1793947790_3659530240.audio_adapter.xnnpack_cache"),
            File(models, "gemma-4-E4B-it.litertlm_1793947790_3659530240.xnnpack_cache"),
            File(cache, "gemma-4-E4B-it.litertlm_1793947790_3659530240_mldrift_weight_cache.bin"),
            File(cache, "gemma-4-E4B-it.litertlm_1793947790_3659530240_mldrift_program_cache.bin"),
            // Cizí: jiný model a jeho keš zůstávají.
            File(models, "gemma-4-E2B-it.litertlm"),
            File(cache, "gemma-4-E2B-it.litertlm_1793947572_2588147712.xnnpack_cache"),
        )
        files.forEach { it.writeText("x") }

        LiteRtOnDeviceAi(platform, { FakeLiteRtRuntime() }, AlwaysOnline).delete(LiteRtModel.E4B.variant)

        assertEquals(
            setOf("gemma-4-E2B-it.litertlm", "gemma-4-E2B-it.litertlm_1793947572_2588147712.xnnpack_cache"),
            files.filter { it.exists() }.map { it.name }.toSet(),
        )
    }
}
