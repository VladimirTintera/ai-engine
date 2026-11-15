package eu.tintera.ai.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eu.tintera.ai.engine.LlmContent
import eu.tintera.ai.engine.LlmConversation
import eu.tintera.ai.engine.LlmEngine
import eu.tintera.ai.engine.LlmEngineConfig
import eu.tintera.ai.engine.LlmEngineFactory
import eu.tintera.ai.engine.LlmSampling
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

/**
 * Do nativního runtime jde jen soubor, který sedí s katalogem ([DownloadedModel.sizeBytes],
 * [DownloadedModel.sha256]). Stahování z lokálního serveru, který posílá podvržený obsah, cizí
 * rozsah nebo víc dat, než má model; načtení souboru, který kontrolou při stažení neprošel.
 */
class LiteRtModelIntegrityTest {

    private val root = Files.createTempDirectory("ai-litert-integrity").toFile()
    private val models = File(root, "ai-models").apply { mkdirs() }

    private val platform = object : LiteRtPlatform {
        override val modelsDir = models.path
        override val cacheDir = File(root, "ai-cache").path
        override fun canRun(model: DownloadedModel) = true
        override fun png(path: String, maxSide: Int): ByteArray? = null
        override fun wavClips(path: String): List<ByteArray> = emptyList()
        override fun startDownload(model: DownloadedModel, unmeteredOnly: Boolean, run: suspend () -> Unit) {}
        override fun cancelDownload(model: DownloadedModel) {}
        override suspend fun downloadPending(model: DownloadedModel) = false
    }

    /** Odpověď serveru na jeden požadavek; dostává hlavičku `Range` (nebo `null`) a pořadí požadavku od nuly. */
    @Volatile
    private var respond: (range: String?, attempt: Int) -> Response = { _, _ -> Response(200, TEST_MODEL_BYTES) }

    /** Hlavičky `Range` požadavků v pořadí, jak přišly. */
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/model") { exchange -> serve(exchange) }
        start()
    }

    private val model = testModel().copy(url = "http://127.0.0.1:${server.address.port}/model")
    private val engines = FakeEngines()
    private val ai = LiteRtOnDeviceAi(platform, { FakeLiteRtRuntime(engines = engines) }, AlwaysOnline)

    private val modelFile = File(models, model.fileName)
    private val partFile = File(models, "${model.fileName}.part")

    @AfterTest
    fun cleanUp() {
        server.stop(0)
        root.deleteRecursively()
    }

    @Test
    fun spravnySouborSeStahneAPrejmenuje() = runBlocking {
        ai.runDownload(model)

        assertContentEquals(TEST_MODEL_BYTES, modelFile.readBytes())
        assertFalse(partFile.exists())
        assertEquals(OnDeviceAiStatus.Ready, ai.status(model.variant))
    }

    @Test
    fun podvrzenySouborSeSmazeAStahneZnovu() = runBlocking {
        respond = { _, attempt -> Response(200, if (attempt == 0) tampered() else TEST_MODEL_BYTES) }

        ai.runDownload(model)

        assertEquals(2, ranges.size)
        assertContentEquals(TEST_MODEL_BYTES, modelFile.readBytes())
    }

    @Test
    fun podvrzenySouborNaServeruSeNeprejmenuje() = runBlocking {
        respond = { _, _ -> Response(200, tampered()) }

        assertFailsWith<ModelIntegrityException> { ai.runDownload(model) }

        // Jednou znovu od začátku, pak konec: další stahování by dopadlo stejně.
        assertEquals(2, ranges.size)
        assertFalse(modelFile.exists())
        assertFalse(partFile.exists())
        assertEquals(OnDeviceAiStatus.Downloadable, ai.status(model.variant))
    }

    @Test
    fun navazaniPokracujeOdKonceCasti() = runBlocking {
        partFile.writeBytes(TEST_MODEL_BYTES.copyOfRange(0, 400))
        respond = { _, _ -> Response(206, TEST_MODEL_BYTES.copyOfRange(400, 1_000), contentRange = "bytes 400-999/1000") }

        ai.runDownload(model)

        assertEquals(listOf<String?>("bytes=400-"), ranges.toList())
        assertContentEquals(TEST_MODEL_BYTES, modelFile.readBytes())
    }

    @Test
    fun rozsahOdJinehoMistaSeNeslepi() = runBlocking {
        partFile.writeBytes(TEST_MODEL_BYTES.copyOfRange(0, 400))
        respond = { range, _ ->
            // Na žádost o zbytek pošle server celý soubor jako 206 od nuly; slepený `.part` by měl 1 400 B.
            if (range != null) Response(206, TEST_MODEL_BYTES, contentRange = "bytes 0-999/1000") else Response(200, TEST_MODEL_BYTES)
        }

        ai.runDownload(model)

        assertEquals(listOf("bytes=400-", null), ranges.toList())
        assertContentEquals(TEST_MODEL_BYTES, modelFile.readBytes())
    }

    @Test
    fun serverBezDelkyNezapiseViceNezMaModel() = runBlocking {
        respond = { _, _ -> Response(200, TEST_MODEL_BYTES + ByteArray(5_000), withLength = false) }

        assertFailsWith<ModelIntegrityException> { ai.runDownload(model) }

        assertFalse(modelFile.exists())
        assertFalse(partFile.exists())
    }

    @Test
    fun jinaDelkaOdServeruSeNestahuje() = runBlocking {
        respond = { _, _ -> Response(200, TEST_MODEL_BYTES + ByteArray(1)) }

        assertFailsWith<ModelIntegrityException> { ai.runDownload(model) }

        assertFalse(modelFile.exists())
    }

    /** Celý `.part` z minulého běhu (proces zabitý před přejmenováním) se bez kontroly nepřejmenuje. */
    @Test
    fun celaCastSCizimObsahemSeNeprejmenuje() = runBlocking {
        partFile.writeBytes(tampered())

        ai.runDownload(model)

        assertEquals(listOf<String?>(null), ranges.toList())
        assertContentEquals(TEST_MODEL_BYTES, modelFile.readBytes())
    }

    /** Soubor stažený verzí bez kontroly (nebo obnovený ze zálohy) se před prvním načtením ověří. */
    @Test
    fun neoverenySpravnySouborSeNacte() = runBlocking {
        modelFile.writeBytes(TEST_MODEL_BYTES)

        assertEquals("ok", ai.generate(model.variant, "Shrň").last().text)
        assertEquals(1, engines.opened)
    }

    @Test
    fun podvrzenySouborSeNenacteASmaze() = runBlocking {
        modelFile.writeBytes(tampered())

        val error = assertFailsWith<OnDeviceAiException> { ai.generate(model.variant, "Shrň").last() }

        assertEquals(AiFailureReason.Unavailable, error.reason)
        assertIs<ModelIntegrityException>(error.cause)
        assertEquals(0, engines.opened)
        assertFalse(modelFile.exists())
        assertEquals(OnDeviceAiStatus.Downloadable, ai.status(model.variant))
    }

    @Test
    fun uriznutySouborSeNenacte() = runBlocking {
        modelFile.writeBytes(TEST_MODEL_BYTES.copyOfRange(0, 500))

        assertFailsWith<OnDeviceAiException> { ai.generate(model.variant, "Shrň").last() }

        assertEquals(0, engines.opened)
        assertFalse(modelFile.exists())
    }

    /** Ověřený soubor vyměněný za jiný (obnova ze zálohy, přenos) se ověří znovu. */
    @Test
    fun vymenenySouborSeOveriZnovu() = runBlocking {
        ai.runDownload(model)
        val verifiedAt = modelFile.lastModified()
        modelFile.writeBytes(tampered())
        modelFile.setLastModified(verifiedAt - 60_000)

        assertFailsWith<OnDeviceAiException> { ai.generate(model.variant, "Shrň").last() }

        assertEquals(0, engines.opened)
        assertFalse(modelFile.exists())
    }

    /** Stejně dlouhý soubor s jedním změněným bajtem. */
    private fun tampered() = TEST_MODEL_BYTES.copyOf().also { it[500] = (it[500] + 1).toByte() }

    private fun serve(exchange: HttpExchange) {
        val range = exchange.requestHeaders.getFirst("Range")
        val response = respond(range, ranges.size)
        ranges += range
        response.contentRange?.let { exchange.responseHeaders.add("Content-Range", it) }
        // Délka 0 = chunked, tedy bez `Content-Length`.
        exchange.sendResponseHeaders(response.code, if (response.withLength) response.body.size.toLong() else 0)
        exchange.responseBody.use { it.write(response.body) }
    }

    private class Response(
        val code: Int,
        val body: ByteArray,
        val contentRange: String? = null,
        val withLength: Boolean = true,
    )

    private class FakeEngines : LlmEngineFactory {
        @Volatile
        var opened = 0

        override fun open(config: LlmEngineConfig): LlmEngine {
            opened++
            return object : LlmEngine {
                override fun conversation(sampling: LlmSampling) = object : LlmConversation {
                    override fun send(content: List<LlmContent>, maxOutputTokens: Int?): Flow<String> = flowOf("ok")
                    override fun cancel() {}
                    override fun close() {}
                }

                override fun close() {}
            }
        }
    }
}
