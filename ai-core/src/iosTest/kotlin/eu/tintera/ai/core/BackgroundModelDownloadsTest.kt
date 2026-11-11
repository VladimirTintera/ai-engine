@file:OptIn(ExperimentalForeignApi::class)

package eu.tintera.ai.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUUID

/**
 * Stahování souboru modelu přes `NSURLSession` download task ([BackgroundModelDownloads]) proti
 * lokálnímu serveru s rozsahy: celé stažení, chyba serveru, zastavení s resume daty a navázání,
 * smazání, připojení k běžící úloze, „jen přes Wi-Fi" a jeho změna za běhu, a totéž přes celý
 * [LiteRtOnDeviceAi] s [IosLiteRtPlatform].
 *
 * Síť simulátoru placená není, takže sezení „jen přes Wi-Fi" stahuje; že na mobilních datech
 * čeká, zařídí systém (`allowsExpensiveNetworkAccess`) a v simulátoru ověřit nejde.
 *
 * Sezení je obyčejné (`defaultSessionConfiguration`): na pozadí jde jen v aplikaci s id balíčku,
 * ne v testovací binárce. Delegát, resume data i přesun souboru jsou stejné; že stahování přežije
 * uspání aplikace, je ověřené ručně v simulátoru (docs/cs/ai-v-zarizeni.md).
 */
class BackgroundModelDownloadsTest {

    private val dir = "${NSTemporaryDirectory()}ai-models-${NSUUID().UUIDString}"
    private val content = ByteArray(3 * 1024 * 1024) { (it * 31 % 251).toByte() }
    private val server = TestHttpServer(content)
    private val downloads = BackgroundModelDownloads(
        { unmeteredOnly -> NSURLSessionConfiguration.defaultSessionConfiguration.apply { allowsExpensiveNetworkAccess = !unmeteredOnly } },
        { dir },
    )
    private val model = testModel(runtime = ModelRuntime.LlamaCpp).copy(
        url = server.url,
        sizeBytes = content.size.toLong(),
        minMemoryGb = 1,
    )
    private val file = Path(dir, model.fileName)
    private val resume = Path(dir, "${model.fileName}.resume")

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    @AfterTest
    fun cleanUp() {
        downloads.invalidate()
        server.stop()
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    }

    @Test
    fun stahneSoubor() = runBlocking<Unit> {
        withTimeout(20.seconds) { downloads.download(model, unmeteredOnly = false) {} }

        assertContentEquals(content, file.readBytes())
        assertNull(downloads.partialBytes(model))
        assertEquals(listOf<String?>(null), server.ranges.load())
        assertFalse(downloads.running(model))
    }

    @Test
    fun chybaServeruJeHttpStatus() = runBlocking<Unit> {
        server.status.store(403)

        val error = assertFailsWith<HttpStatusException> { withTimeout(20.seconds) { downloads.download(model, unmeteredOnly = false) {} } }

        assertEquals(403, error.code)
        assertFalse(file.exists())
    }

    @Test
    fun zastaveniNechaResumeDataAPristeNavaze() = runBlocking<Unit> {
        stopHalfway()
        val partial = assertNotNull(downloads.partialBytes(model))
        assertTrue(partial in 1 until content.size, "staženo $partial B")

        server.chunkDelayUs.store(0)
        withTimeout(20.seconds) { downloads.download(model, unmeteredOnly = false) {} }

        assertContentEquals(content, file.readBytes())
        assertFalse(resume.exists())
        val ranges = server.ranges.load()
        assertEquals(2, ranges.size, "dotazy: $ranges")
        val resumedAt = ranges[1]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
        assertNotNull(resumedAt, "navázání bez Range: $ranges")
        assertTrue(resumedAt > 0)
    }

    @Test
    fun smazaniZahodiRozpracovane() = runBlocking<Unit> {
        stopHalfway()

        downloads.discard(model)

        assertNull(downloads.partialBytes(model))
        assertFalse(resume.exists())
        assertFalse(downloads.running(model))
    }

    @Test
    fun druheStahovaniSePripojiKBezicimu() = runBlocking<Unit> {
        server.chunkDelayUs.store(2_000)
        withTimeout(30.seconds) {
            List(2) { async(Dispatchers.IO) { downloads.download(model, unmeteredOnly = false) {} } }.awaitAll()
        }

        assertContentEquals(content, file.readBytes())
        assertEquals(1, server.ranges.load().size)
    }

    @Test
    fun celeStahovaniPresLiteRtOnDeviceAi() = runBlocking<Unit> {
        val ai = liteRt()

        withTimeout(20.seconds) { ai.download(model.variant).collect {} }

        assertEquals(OnDeviceAiStatus.Ready, ai.status(model.variant))
        ai.delete(model.variant)
        assertEquals(OnDeviceAiStatus.Downloadable, ai.status(model.variant))
        assertFalse(file.exists())
    }

    @Test
    fun zastaveniPresLiteRtOnDeviceAiJePauza() = runBlocking<Unit> {
        server.chunkDelayUs.store(5_000)
        val ai = liteRt()
        val job = CoroutineScope(Dispatchers.IO).launch {
            runCatching { ai.download(model.variant).collect {} }
        }
        withTimeout(20.seconds) { while (server.sent.load() < 500_000) delay(20) }
        assertEquals(OnDeviceAiStatus.Downloading, ai.status(model.variant))

        ai.stopDownload(model.variant)
        job.join()
        withTimeout(10.seconds) { while (!resume.exists()) delay(20) }

        assertEquals(OnDeviceAiStatus.Paused, ai.status(model.variant))
        server.chunkDelayUs.store(0)
        withTimeout(20.seconds) { ai.download(model.variant).collect {} }
        assertEquals(OnDeviceAiStatus.Ready, ai.status(model.variant))
        assertContentEquals(content, file.readBytes())
        assertTrue(server.ranges.load().last() != null, "navázání bez Range: ${server.ranges.load()}")
    }

    @Test
    fun jenPresWifiBeziVSezeniBezPlacenychSiti() = runBlocking<Unit> {
        server.chunkDelayUs.store(2_000)
        val job = CoroutineScope(Dispatchers.IO).launch { downloads.download(model, unmeteredOnly = true) {} }
        withTimeout(20.seconds) { while (server.sent.load() < 100_000) delay(20) }

        assertEquals(true, downloads.runningUnmeteredOnly(model))
        server.chunkDelayUs.store(0)
        withTimeout(20.seconds) { job.join() }
        assertContentEquals(content, file.readBytes())
    }

    @Test
    fun zmenaSiteZaBehuPrenesStahovaniDoDruhehoSezeni() = runBlocking<Unit> {
        server.chunkDelayUs.store(5_000)
        val result = CoroutineScope(Dispatchers.IO).async { runCatching { downloads.download(model, unmeteredOnly = false) {} } }
        withTimeout(20.seconds) { while (server.sent.load() < 500_000) delay(20) }

        downloads.setNetwork(model, unmeteredOnly = true)
        withTimeout(10.seconds) { while (downloads.runningUnmeteredOnly(model) != true) delay(20) }
        server.chunkDelayUs.store(0)

        val outcome = withTimeout(20.seconds) { result.await() }
        assertTrue(outcome.isSuccess, "stahování skončilo: ${outcome.exceptionOrNull()}")
        assertContentEquals(content, file.readBytes())
        val ranges = server.ranges.load()
        assertEquals(2, ranges.size, "dotazy: $ranges")
        assertNotNull(ranges[1], "přepnutí bez navázání: $ranges")
    }

    @Test
    fun zakazDatZaBehuPresLiteRtOnDeviceAiStahovaniNezrusi() = runBlocking<Unit> {
        server.chunkDelayUs.store(5_000)
        val ai = liteRt(metered = true)
        val result = CoroutineScope(Dispatchers.IO).async { runCatching { ai.download(model.variant, mobileData = true).collect {} } }
        withTimeout(20.seconds) { while (server.sent.load() < 500_000) delay(20) }

        ai.setDownloadNetwork(model.variant, mobileData = false)
        withTimeout(10.seconds) { while (downloads.runningUnmeteredOnly(model) != true) delay(20) }
        server.chunkDelayUs.store(0)

        val outcome = withTimeout(20.seconds) { result.await() }
        assertTrue(outcome.isSuccess, "stahování skončilo: ${outcome.exceptionOrNull()}")
        assertEquals(OnDeviceAiStatus.Ready, ai.status(model.variant))
        assertContentEquals(content, file.readBytes())
    }

    /** Pustí zpomalené stahování, zastaví ho v půli a počká na resume data. */
    private suspend fun stopHalfway() {
        server.chunkDelayUs.store(5_000)
        val job = CoroutineScope(Dispatchers.IO).launch { downloads.download(model, unmeteredOnly = false) {} }
        withTimeout(20.seconds) { while (server.sent.load() < 500_000) delay(20) }
        assertTrue(downloads.running(model))
        job.cancel()
        withTimeout(10.seconds) { while (!resume.exists()) delay(20) }
    }

    private fun liteRt(metered: Boolean = false) = LiteRtOnDeviceAi(
        IosLiteRtPlatform(setOf(ModelRuntime.LlamaCpp), NoAudioClips, downloads) { dir },
        { LinkedLiteRtRuntime { error("Engine se v testu nenačítá") } },
        object : Connectivity {
            override val online: Flow<Boolean> = flowOf(true)
        },
        meteredNetwork = object : MeteredNetwork {
            override fun isMetered() = metered
        },
    )

    private fun Path.readBytes(): ByteArray = SystemFileSystem.source(this).buffered().use { it.readByteArray() }
}
