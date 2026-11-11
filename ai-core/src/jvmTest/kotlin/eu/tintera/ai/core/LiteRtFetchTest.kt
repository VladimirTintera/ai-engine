package eu.tintera.ai.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/**
 * Stažení souboru modelu ([LiteRtOnDeviceAi.runDownload]) proti lokálnímu serveru: navázání
 * na `.part` hlavičkou `Range`, server bez rozsahů, celý `.part` (416) a chyba serveru. Logika je
 * společná pro všechny platformy, tady jede přes `HttpURLConnection`.
 */
class LiteRtFetchTest {

    private val root = Files.createTempDirectory("ai-litert-fetch").toFile()
    private val models = File(root, "ai-models")
    private val content = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** `false` = server rozsah přejde a pošle celý soubor (200). */
    @Volatile
    private var honorRange = true

    @Volatile
    private var status: Int? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/model") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            ranges += range
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()?.takeIf { honorRange }
            when {
                status != null -> exchange.sendResponseHeaders(status!!, -1)
                start != null && start >= content.size -> exchange.sendResponseHeaders(416, -1)
                start != null -> {
                    exchange.responseHeaders.add("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                    exchange.sendResponseHeaders(206, (content.size - start).toLong())
                    exchange.responseBody.use { it.write(content, start, content.size - start) }
                }
                else -> {
                    exchange.sendResponseHeaders(200, content.size.toLong())
                    exchange.responseBody.use { it.write(content) }
                }
            }
            exchange.close()
        }
        start()
    }

    private val model = testModel().copy(
        url = "http://127.0.0.1:${server.address.port}/model",
        sizeBytes = content.size.toLong(),
    )
    private val file = File(models, model.fileName)
    private val part = File(models, "${model.fileName}.part")

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

    private val ai = LiteRtOnDeviceAi(platform, { FakeLiteRtRuntime(installed = true) }, AlwaysOnline)

    @AfterTest
    fun cleanUp() {
        server.stop(0)
        root.deleteRecursively()
    }

    @Test
    fun stahneCelySoubor() = runBlocking {
        ai.runDownload(model)

        assertContentEquals(content, file.readBytes())
        assertFalse(part.exists())
        assertEquals(listOf<String?>(null), ranges.toList())
    }

    @Test
    fun navazeNaPart() = runBlocking {
        models.mkdirs()
        part.writeBytes(content.copyOf(100_000))

        ai.runDownload(model)

        assertContentEquals(content, file.readBytes())
        assertEquals(listOf<String?>("bytes=100000-"), ranges.toList())
    }

    @Test
    fun serverBezRozsahuPosleSouborZnovu() = runBlocking {
        honorRange = false
        models.mkdirs()
        part.writeBytes(content.copyOf(100_000))

        ai.runDownload(model)

        assertContentEquals(content, file.readBytes())
    }

    @Test
    fun celyPartSeJenPrejmenuje() = runBlocking {
        models.mkdirs()
        part.writeBytes(content)

        ai.runDownload(model)

        assertContentEquals(content, file.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun chybaServeruSeNeopakuje() {
        status = 403
        assertFailsWith<IOException> { runBlocking { ai.runDownload(model) } }
        assertEquals(1, ranges.size)
        assertFalse(file.exists())
    }
}
