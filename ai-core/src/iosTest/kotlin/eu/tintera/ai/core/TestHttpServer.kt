@file:OptIn(ExperimentalForeignApi::class)

package eu.tintera.ai.core

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSThread
import platform.posix.AF_INET
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.SHUT_RDWR
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.usleep
import kotlinx.cinterop.IntVar

/**
 * HTTP server pro testy stahování na iOS (Kotlin/Native nemá `HttpServer` z JDK): jeden soubor
 * na `/model`, s rozsahy (`Range` → 206) a s `ETag` a `Last-Modified`, bez kterých `NSURLSession`
 * resume data nedá. Každé spojení ve vlastním vlákně; [chunkDelayUs] zpomalí posílání, aby šlo
 * stahování zastavit uprostřed.
 */
internal class TestHttpServer(private val content: ByteArray) {

    /** Hlavička `Range` každého dotazu (`null` = bez ní). */
    val ranges = AtomicReference<List<String?>>(emptyList())

    /** Bajtů těla odeslaných celkem. */
    val sent = AtomicLong(0)

    /** Jiná odpověď místo souboru (403…). */
    val status = AtomicInt(0)

    /** Prodleva po každých [CHUNK] bajtech. */
    val chunkDelayUs = AtomicInt(0)

    private val listener: Int = socket(AF_INET, SOCK_STREAM, 0)

    val port: Int

    val url: String get() = "http://127.0.0.1:$port/model"

    init {
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val address = alloc<sockaddr_in>().apply {
                sin_family = AF_INET.convert()
                sin_port = 0u
                sin_addr.s_addr = swap(INADDR_LOOPBACK.toUInt())
            }
            check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind" }
            check(listen(listener, 16) == 0) { "listen" }
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            getsockname(listener, address.ptr.reinterpret(), length.ptr)
            port = address.sin_port.toInt().let { ((it and 0xff) shl 8) or (it shr 8) }
        }
        NSThread {
            while (true) {
                val connection = accept(listener, null, null)
                if (connection < 0) break
                NSThread { serve(connection) }.start()
            }
        }.start()
    }

    fun stop() {
        shutdown(listener, SHUT_RDWR)
        close(listener)
    }

    private fun serve(connection: Int) {
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(connection, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
        }
        try {
            val request = readHeaders(connection) ?: return
            val range = request.lineSequence()
                .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            ranges.update { it + range }
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
            val forced = status.load()
            val common = "Accept-Ranges: bytes\r\nETag: \"test-etag\"\r\nLast-Modified: Wed, 11 Nov 2026 08:00:00 GMT\r\nConnection: close\r\n"
            when {
                forced != 0 -> write(connection, "HTTP/1.1 $forced Error\r\nContent-Length: 0\r\n$common\r\n".encodeToByteArray())
                start != null && start >= content.size ->
                    write(connection, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n$common\r\n".encodeToByteArray())
                start != null -> {
                    write(
                        connection,
                        ("HTTP/1.1 206 Partial Content\r\nContent-Length: ${content.size - start}\r\n" +
                            "Content-Range: bytes $start-${content.size - 1}/${content.size}\r\n$common\r\n").encodeToByteArray(),
                    )
                    body(connection, start)
                }
                else -> {
                    write(connection, "HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\n$common\r\n".encodeToByteArray())
                    body(connection, 0)
                }
            }
        } finally {
            close(connection)
        }
    }

    private fun body(connection: Int, start: Int) {
        var offset = start
        while (offset < content.size) {
            val end = minOf(offset + CHUNK, content.size)
            if (!write(connection, content.copyOfRange(offset, end))) return
            sent.addAndFetch((end - offset).toLong())
            offset = end
            chunkDelayUs.load().takeIf { it > 0 }?.let { usleep(it.convert()) }
        }
    }

    private fun readHeaders(connection: Int): String? {
        val buffer = ByteArray(4096)
        val text = StringBuilder()
        while (!text.contains("\r\n\r\n")) {
            val read = buffer.usePinned { recv(connection, it.addressOf(0), buffer.size.convert(), 0) }
            if (read <= 0) return null
            text.append(buffer.decodeToString(0, read.toInt()))
        }
        return text.toString()
    }

    /** `false`, když klient spojení zavřel. */
    private fun write(connection: Int, bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            val written = bytes.usePinned { send(connection, it.addressOf(offset), (bytes.size - offset).convert(), 0) }
            if (written <= 0) return false
            offset += written.toInt()
        }
        return true
    }

    private fun swap(value: UInt): UInt =
        ((value and 0xffu) shl 24) or ((value and 0xff00u) shl 8) or ((value shr 8) and 0xff00u) or (value shr 24)

    private inline fun <T> AtomicReference<T>.update(change: (T) -> T) {
        while (true) {
            val current = load()
            if (compareAndSet(current, change(current))) return
        }
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}
