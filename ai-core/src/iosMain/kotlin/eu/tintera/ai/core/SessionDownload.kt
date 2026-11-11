package eu.tintera.ai.core

import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.io.IOException
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.memcpy

internal actual fun httpDownload(url: String, offset: Long, timeoutMs: Int): HttpDownload =
    SessionDownload(url, offset, timeoutMs)

/**
 * GET přes `NSURLSession` s delegátem, který tělo posílá po kusech do kanálu: stahuje se do
 * `.part` stejně jako na Androidu a desktopu, takže navazování (`Range`) je společné.
 *
 * - **Sezení na jedno stahování** (ephemeral, bez HTTP keše — ta by gigabajty modelu uložila
 *   podruhé). Drží delegáta, dokud se nezavře, proto [close] vždycky.
 * - **Kanál bez stropu**: zápis na disk je rychlejší než síť, kusy se nehromadí.
 * - **Jen v popředí.** iOS aplikaci v pozadí po chvíli uspí a spojení spadne; `.part` zůstane
 *   a příští stahování naváže. Stahování na pozadí (`backgroundSessionConfiguration`) by chtělo
 *   soubor od systému místo `.part` a obnovu po spuštění aplikace — zatím ne.
 */
@OptIn(ExperimentalForeignApi::class)
private class SessionDownload(url: String, offset: Long, timeoutMs: Int) : HttpDownload {

    private val response = CompletableDeferred<NSHTTPURLResponse>()
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)

    private val session: NSURLSession = NSURLSession.sessionWithConfiguration(
        configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
            // Ticho mezi dvěma kusy dat, jako `readTimeout` na JVM; celé stahování může trvat minuty.
            timeoutIntervalForRequest = timeoutMs / 1000.0
            requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            URLCache = null
        },
        delegate = Delegate(response, chunks),
        delegateQueue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 },
    )

    private val task: NSURLSessionDataTask = session.dataTaskWithRequest(
        NSMutableURLRequest.requestWithURL(NSURL.URLWithString(url) ?: throw IOException("Invalid URL $url")).apply {
            if (offset > 0) setValue("bytes=$offset-", forHTTPHeaderField = "Range")
        },
    )

    private var current: ByteArray? = null
    private var position = 0

    override var contentLength: Long = -1
        private set

    override suspend fun connect(): Int {
        task.resume()
        val http = response.await()
        // NSURLResponseUnknownLength je -1, stejně jako u `HttpURLConnection`.
        contentLength = http.expectedContentLength
        return http.statusCode.toInt()
    }

    override suspend fun read(buffer: ByteArray): Int {
        var chunk = current
        if (chunk == null || position >= chunk.size) {
            val next = chunks.receiveCatching()
            chunk = next.getOrNull() ?: next.exceptionOrNull()?.let { throw it } ?: return -1
            current = chunk
            position = 0
        }
        val count = minOf(buffer.size, chunk.size - position)
        chunk.copyInto(buffer, destinationOffset = 0, startIndex = position, endIndex = position + count)
        position += count
        return count
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        task.cancel()
        session.invalidateAndCancel()
    }
}

/** Volá ho `NSURLSession` na jednom vlákně fronty sezení. */
@OptIn(ExperimentalForeignApi::class)
private class Delegate(
    private val response: CompletableDeferred<NSHTTPURLResponse>,
    private val chunks: Channel<ByteArray>,
) : NSObject(), NSURLSessionDataDelegateProtocol {

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        val http = didReceiveResponse as? NSHTTPURLResponse
        if (http == null) {
            response.completeExceptionally(IOException("Not an HTTP response"))
            completionHandler(NSURLSessionResponseCancel)
            return
        }
        response.complete(http)
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        chunks.trySend(didReceiveData.toByteArray())
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        // Výpadek sítě, časový limit i zrušení ([SessionDownload.close]) jsou pro společný kód
        // IOException; zrušení od chyby sítě rozliší sám (`ensureActive`).
        val error = didCompleteWithError?.let { IOException("${it.domain} ${it.code}: ${it.localizedDescription}") }
        response.completeExceptionally(error ?: IOException("No response"))
        chunks.close(error)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
