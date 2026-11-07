package eu.tintera.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Katalog stahovaných modelů a jejich varianty ([LiteRtModel], [ModelVariant.model]). */
class LiteRtModelTest {

    /** Varianta souboru vede zpátky na tentýž soubor — Úložiště podle ní maže. */
    @Test
    fun variantaVedeNaSvujSoubor() {
        LiteRtModel.entries.forEach { assertEquals(it, it.variant.model) }
    }

    @Test
    fun systemovyModelNemaSoubor() {
        assertNull(ModelVariant(provider = AiProvider.GeminiNano).model)
        assertNull(ModelVariant(provider = AiProvider.Apple).model)
        assertEquals(false, ModelVariant(provider = AiProvider.GeminiNano).appManaged)
        assertEquals(true, ModelVariant(provider = AiProvider.Qwen).appManaged)
    }

    /** Zvuk a obraz jen u Gemmy; Qwen se přepisu ani popisu fotky nenabídne. */
    @Test
    fun multimodalniJenGemma() {
        LiteRtModel.entries.forEach { assertEquals(it.provider == AiProvider.Gemma, it.multimodal) }
    }

    /** Qweny i EuroLLM jdou od největšího — náhrada za nedostupnou velikost bere první, která jde. */
    @Test
    fun velikostiOdNejvetsi() {
        listOf(AiProvider.Qwen, AiProvider.EuroLlm).forEach { provider ->
            val sizes = LiteRtModel.entries.filter { it.provider == provider }.map { it.minMemoryGb }
            assertEquals(sizes.sortedDescending(), sizes, "$provider")
        }
    }

    /** EuroLLM a Aya jsou GGUF pro llama.cpp, Gemma a Qwen soubory LiteRT-LM — runtime se podle toho doinstaluje. */
    @Test
    fun runtimePodlePoskytovatele() {
        LiteRtModel.entries.forEach { model ->
            val gguf = model.provider == AiProvider.EuroLlm || model.provider == AiProvider.Aya
            val expected = if (gguf) ModelRuntime.LlamaCpp else ModelRuntime.LiteRt
            assertEquals(expected, model.runtime, model.name)
            assertEquals(expected == ModelRuntime.LlamaCpp, model.fileName.endsWith(".gguf"), model.name)
        }
    }

    /** Jména se ukládají (volba velikosti, úloha stahování) — přejmenování by volbu ztratilo. */
    @Test
    fun jmenaPolozekSeNemeni() {
        assertEquals(
            listOf("E2B", "E4B", "Qwen3_4B", "Qwen2_5_1_5B", "Qwen3_0_6B", "EuroLlm9B", "EuroMoe2_6B", "AyaExpanse8B"),
            LiteRtModel.entries.map { it.name },
        )
    }
}
