package eu.tintera.ai.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Které soubory keše runtime se mažou s modelem ([DownloadedModel.ownsCacheFile]); tvary z disku. */
class ModelCacheFilesTest {

    private val large = testModel("large")
    private val small = testModel("small")

    @Test
    fun kesModeluIZvukovychCasti() {
        assertTrue(large.ownsCacheFile("large.litertlm_1793947790_3659530240.xnnpack_cache"))
        assertTrue(large.ownsCacheFile("large.litertlm_1793947790_3659530240.audio_adapter.xnnpack_cache"))
        assertTrue(large.ownsCacheFile("large.litertlm_1793947790_3659530240.static_audio_encoder.xnnpack_cache"))
    }

    /** Keš pro GPU zůstane i po pokusu, který skončil na procesoru (emulátor bez OpenCL). */
    @Test
    fun kesProGpu() {
        assertTrue(large.ownsCacheFile("large.litertlm_1794000000_343490560_mldrift_program_cache.bin"))
        assertTrue(large.ownsCacheFile("large.litertlm_1794000000_343490560_mldrift_weight_cache.bin"))
        assertTrue(large.ownsCacheFile("large.litertlm_1793947790_3659530240_mldrift_compiled_cache.bin"))
        assertFalse(large.ownsCacheFile("small.litertlm_1793947572_2588147712_mldrift_weight_cache.bin"))
        assertFalse(large.ownsCacheFile("large.litertlm_1793947790_3659530240.bin"))
    }

    /** Keš jiného modelu ani model sám se nesmažou. */
    @Test
    fun ciziSouboryZustanou() {
        assertFalse(large.ownsCacheFile("small.litertlm_1793947572_2588147712.xnnpack_cache"))
        assertFalse(large.ownsCacheFile("large.litertlm"))
        assertFalse(large.ownsCacheFile("large.litertlm.part"))
        assertFalse(small.ownsCacheFile("notification-icon.png"))
    }

    /** Keše jsou věc LiteRT-LM; llama.cpp na disk žádnou nedělá. */
    @Test
    fun llamaCppKesNema() {
        val gguf = testModel("gguf", ModelRuntime.LlamaCpp)
        assertFalse(gguf.ownsCacheFile("gguf.gguf_1793947790_3659530240.xnnpack_cache"))
    }
}
