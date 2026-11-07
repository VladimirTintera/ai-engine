package eu.tintera.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Volba poskytovatele platí pro celou aplikaci, takže o ní rozhoduje jediné místo. Tady se hlídá,
 * že volání dojde ke správnému runtime a že každý dostane jen pole, která se ho týkají.
 */
class RoutingOnDeviceAiTest {

    private class Recorder(private val status: OnDeviceAiStatus) : OnDeviceAi {
        val seen = mutableListOf<ModelVariant>()
        override suspend fun status(variant: ModelVariant): OnDeviceAiStatus = status.also { seen += variant }
        override suspend fun info(variant: ModelVariant): ModelInfo? = null.also { seen += variant }
        override fun download(variant: ModelVariant, mobileData: Boolean): Flow<ModelDownload> = emptyFlow<ModelDownload>().also { seen += variant }
        override suspend fun countTokens(variant: ModelVariant, prompt: String): Int? = null.also { seen += variant }
        override fun generate(variant: ModelVariant, prompt: String): Flow<Generated> =
            flowOf(Generated(prompt)).also { seen += variant }
        override suspend fun delete(variant: ModelVariant) {
            seen += variant
        }
        override suspend fun stopDownload(variant: ModelVariant) {
            seen += variant
        }
        override fun describeImage(variant: ModelVariant, imagePath: String, prompt: String): Flow<Generated> =
            flowOf(Generated("$imagePath $prompt")).also { seen += variant }
    }

    /** Gemini Nano přes ML Kit obraz nebere (výchozí `false`); popis jde jen Gemmě. */
    @Test
    fun popisFotkyJdeGemme() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val gemma = Recorder(OnDeviceAiStatus.Ready)
        val ai = RoutingOnDeviceAi(nano, gemma)

        assertEquals(false, ai.canDescribeImage(ModelVariant(provider = AiProvider.GeminiNano)))
        val text = ai.describeImage(ModelVariant(provider = AiProvider.Gemma, fast = true), "/fotka.jpg", "popiš").toList()

        assertEquals(listOf(Generated("/fotka.jpg popiš")), text)
        assertEquals(listOf(ModelVariant(provider = AiProvider.Gemma)), gemma.seen)
    }

    /** Qwen jde do téhož runtime jako Gemma, bez voleb, které se ho netýkají. */
    @Test
    fun qwenJdeDoLiteRt() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val liteRt = Recorder(OnDeviceAiStatus.Downloadable)
        val ai = RoutingOnDeviceAi(nano, liteRt)

        val status = ai.status(
            ModelVariant(provider = AiProvider.Qwen, fast = true, gemma = LiteRtModel.E4B, qwen = LiteRtModel.Qwen3_0_6B),
        )

        assertEquals(OnDeviceAiStatus.Downloadable, status)
        assertEquals(listOf(ModelVariant(provider = AiProvider.Qwen, qwen = LiteRtModel.Qwen3_0_6B)), liteRt.seen)
        assertEquals(emptyList(), nano.seen)
        assertEquals(LiteRtModel.Qwen3_0_6B, liteRt.seen.single().model)
    }

    @Test
    fun geminiNanoDostaneJenSvojeVolby() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val gemma = Recorder(OnDeviceAiStatus.Downloadable)
        val ai = RoutingOnDeviceAi(nano, gemma)

        val status = ai.status(ModelVariant(provider = AiProvider.GeminiNano, fast = true, gemma = LiteRtModel.E4B))

        assertEquals(OnDeviceAiStatus.Ready, status)
        // Model Gemmy do klíče klienta ML Kitu nepatří — jinak by vznikl druhý klient téhož modelu.
        assertEquals(listOf(ModelVariant(provider = AiProvider.GeminiNano, fast = true)), nano.seen)
        assertEquals(emptyList(), gemma.seen)
    }

    @Test
    fun gemmaDostaneJenModel() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val gemma = Recorder(OnDeviceAiStatus.Downloadable)
        val ai = RoutingOnDeviceAi(nano, gemma)

        val text = ai.generate(
            ModelVariant(provider = AiProvider.Gemma, preview = true, fast = true, gemma = LiteRtModel.E4B),
            "ahoj",
        ).toList()

        assertEquals(listOf(Generated("ahoj")), text)
        assertEquals(listOf(ModelVariant(provider = AiProvider.Gemma, gemma = LiteRtModel.E4B)), gemma.seen)
        assertEquals(emptyList(), nano.seen)
    }

    @Test
    fun smazaniJdeZvolenemuPoskytovateli() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val gemma = Recorder(OnDeviceAiStatus.Ready)
        RoutingOnDeviceAi(nano, gemma).delete(ModelVariant(provider = AiProvider.Gemma))
        assertEquals(1, gemma.seen.size)
        assertEquals(emptyList(), nano.seen)
    }

    @Test
    fun zastaveniStahovaniJdeZvolenemuPoskytovateli() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Downloading)
        val gemma = Recorder(OnDeviceAiStatus.Downloading)
        RoutingOnDeviceAi(nano, gemma).stopDownload(ModelVariant(provider = AiProvider.Gemma, fast = true, gemma = LiteRtModel.E4B))
        assertEquals(listOf(ModelVariant(provider = AiProvider.Gemma, gemma = LiteRtModel.E4B)), gemma.seen)
        assertEquals(emptyList(), nano.seen)
    }

    @Test
    fun modelStahujeJenAplikaceUGemmy() {
        assertEquals(true, ModelVariant(provider = AiProvider.Gemma).appManaged)
        assertEquals(false, ModelVariant(provider = AiProvider.GeminiNano).appManaged)
    }

    @Test
    fun adresaModeluNeseCommit() {
        // Commit v adrese drží stažený soubor na verzi z katalogu, i když autor nahraje novější.
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true",
            LiteRtModel.E2B.downloadUrl,
        )
    }
}
