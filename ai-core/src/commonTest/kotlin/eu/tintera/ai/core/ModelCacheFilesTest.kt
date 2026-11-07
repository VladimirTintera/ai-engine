package eu.tintera.ai.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Které soubory keše runtime se mažou s modelem ([LiteRtModel.ownsCacheFile]); jména z disku. */
class ModelCacheFilesTest {

    @Test
    fun kesModeluIZvukovychCasti() {
        assertTrue(LiteRtModel.E4B.ownsCacheFile("gemma-4-E4B-it.litertlm_1793947790_3659530240.xnnpack_cache"))
        assertTrue(LiteRtModel.E4B.ownsCacheFile("gemma-4-E4B-it.litertlm_1793947790_3659530240.audio_adapter.xnnpack_cache"))
        assertTrue(LiteRtModel.E4B.ownsCacheFile("gemma-4-E4B-it.litertlm_1793947790_3659530240.static_audio_encoder.xnnpack_cache"))
        assertTrue(LiteRtModel.Qwen3_4B.ownsCacheFile("qwen3_4b_mixed_int4.litertlm_1793950031_2272935936.xnnpack_cache"))
    }

    /** Keš jiné velikosti téže rodiny ani model sám se nesmažou. */
    @Test
    fun ciziSouboryZustanou() {
        assertFalse(LiteRtModel.E4B.ownsCacheFile("gemma-4-E2B-it.litertlm_1793947572_2588147712.xnnpack_cache"))
        assertFalse(LiteRtModel.E4B.ownsCacheFile("gemma-4-E4B-it.litertlm"))
        assertFalse(LiteRtModel.E4B.ownsCacheFile("gemma-4-E4B-it.litertlm.part"))
        assertFalse(LiteRtModel.E2B.ownsCacheFile("notification-icon.png"))
    }
}
