package eu.tintera.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Model z katalogu aplikace a jeho varianty ([DownloadedModel], [ModelVariant.model]). */
class DownloadedModelTest {

    /** Varianta souboru vede zpátky na tentýž soubor — Úložiště podle ní maže. */
    @Test
    fun variantaVedeNaSvujSoubor() {
        val model = testModel()
        assertEquals(model, model.variant.model)
        assertEquals(true, model.variant.appManaged)
    }

    @Test
    fun systemovyModelNemaSoubor() {
        assertNull(ModelVariant.GeminiNano().model)
        assertNull(ModelVariant.Apple.model)
        assertEquals(false, ModelVariant.GeminiNano().appManaged)
        assertEquals(false, ModelVariant.Apple.appManaged)
    }

    /** Stav stahování i načtený engine jsou v mapách podle modelu; aplikace může dát novou instanci. */
    @Test
    fun dveInstanceJsouTentyzModel() {
        assertEquals(testModel(), testModel())
        assertEquals(testModel().hashCode(), testModel().hashCode())
        assertEquals(testModel().variant, testModel().variant)
    }

    @Test
    fun vychoziKontextAJenText() {
        val model = DownloadedModel("id", "Model", "https://example.invalid/m", "m", 1, 1, ModelRuntime.LlamaCpp)
        assertEquals(8_192, model.contextTokens)
        assertEquals(false, model.multimodal)
    }

    @Test
    fun adresaHuggingFaceNeseCommit() {
        // Commit v adrese drží stažený soubor na verzi z katalogu, i když autor nahraje novější.
        assertEquals(
            "https://huggingface.co/org/model-repo/resolve/0123abc/file.litertlm?download=true",
            huggingFaceUrl(repo = "org/model-repo", commit = "0123abc", file = "file.litertlm"),
        )
    }

    /** Zpráva jde do hlášení pádů: jméno modelu ano, adresa ani nic dalšího z katalogu ne. */
    @Test
    fun zpravaVyjimkyJmenujeModel() {
        assertEquals("GeminiNano failed: ModelError (code 5)", OnDeviceAiException(AiFailureReason.ModelError, ModelVariant.GeminiNano(preview = true), 5).message)
        assertEquals("Apple failed: Busy", OnDeviceAiException(AiFailureReason.Busy, ModelVariant.Apple).message)
        assertEquals("test-model failed: Unavailable", OnDeviceAiException(AiFailureReason.Unavailable, testModel().variant).message)
    }
}
