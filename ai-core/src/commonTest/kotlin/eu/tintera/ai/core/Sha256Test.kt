package eu.tintera.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** SHA-256 knihovnou platformy ([sha256Digest]) proti známým hodnotám z FIPS 180-2. */
class Sha256Test {

    @Test
    fun abc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "abc".encodeToByteArray().sha256Hex())
    }

    @Test
    fun prazdny() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ByteArray(0).sha256Hex())
    }

    /** Po kusech, jak ho počítá čtení souboru: [Sha256.update] bere jen prvních `count` bajtů. */
    @Test
    fun poKusech() {
        val digest = sha256Digest()
        val buffer = ByteArray(8)
        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray().toList().chunked(5).forEach { chunk ->
            chunk.forEachIndexed { i, byte -> buffer[i] = byte }
            digest.update(buffer, chunk.size)
        }
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", digest.hex())
    }
}
