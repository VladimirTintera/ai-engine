package eu.tintera.ai.core

import kotlinx.io.IOException

/**
 * Jeden GET na soubor modelu, volitelně od bajtu [offset] (hlavička `Range`). Přesměrování
 * (Hugging Face → CDN) následuje sám a `Range` při něm přenese.
 *
 * Android a desktop přes `HttpURLConnection`, iOS přes `NSURLSession`. Logika navazování,
 * kontroly místa a hlášení průběhu je společná ([LiteRtOnDeviceAi]).
 *
 * [close] smí přijít z jiného vlákna kdykoli, i uprostřed [connect] nebo [read]: tak se ruší
 * stahování (blokující čtení na JVM zrušení korutiny nepozná). Druhé zavření nic nedělá.
 */
internal interface HttpDownload : AutoCloseable {

    /** Pošle dotaz a počká na hlavičky odpovědi. Vrací stavový kód; výpadek sítě = [IOException]. */
    suspend fun connect(): Int

    /** Délka těla odpovědi v bajtech; -1, když ji server neřekl. Až po [connect]. */
    val contentLength: Long

    /** Přečte další kus těla do [buffer]; vrací počet bajtů, -1 na konci. Výpadek = [IOException]. */
    suspend fun read(buffer: ByteArray): Int

    override fun close()
}

/** [timeoutMs] = nejdelší ticho při spojení i mezi dvěma kusy dat. */
internal expect fun httpDownload(url: String, offset: Long, timeoutMs: Int): HttpDownload
