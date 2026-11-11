package eu.tintera.ai.core

import eu.tintera.ai.engine.LITERT_ENGINE_FACTORY
import eu.tintera.ai.engine.LlmEngineFactory
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.io.files.Path

internal actual fun usableSpace(dir: Path): Long = File(dir.toString()).usableSpace

internal actual fun isLinkageError(error: Throwable): Boolean = error is LinkageError

/**
 * Implementace z :ai-litert podle jména. Reflexe, protože na Androidu leží v součásti,
 * na kterou základ aplikace při překladu nevidí; na desktopu kvůli tomu, že by modul nad
 * `shared` jinak tvořil kruh závislostí.
 */
internal fun loadEngineFactory(classLoader: ClassLoader, className: String = LITERT_ENGINE_FACTORY): LlmEngineFactory =
    Class.forName(className, true, classLoader).getDeclaredConstructor().newInstance() as LlmEngineFactory

internal actual fun httpDownload(url: String, offset: Long, timeoutMs: Int): HttpDownload =
    UrlConnectionDownload(url, offset, timeoutMs)

/** `HttpURLConnection` přesměrování v rámci HTTPS následuje sám a hlavičku `Range` přenese. */
private class UrlConnectionDownload(url: String, offset: Long, timeoutMs: Int) : HttpDownload {

    private val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
    }

    private var input: InputStream? = null

    override suspend fun connect(): Int = connection.responseCode

    override val contentLength: Long get() = connection.contentLengthLong

    override suspend fun read(buffer: ByteArray): Int =
        (input ?: connection.inputStream.also { input = it }).read(buffer)

    override fun close() {
        runCatching { input?.close() }
        connection.disconnect()
    }
}

/** Android a desktop stahují soubor společně (`HttpURLConnection` do `.part`). */
internal actual fun modelFileDownloader(platform: LiteRtPlatform): ModelFileDownloader? = null
