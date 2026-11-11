package eu.tintera.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Volba modelu platí pro celou aplikaci, takže o ní rozhoduje jediné místo. Tady se hlídá,
 * že volání dojde ke správnému runtime.
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

    /** Gemini Nano přes ML Kit obraz nebere (výchozí `false`); popis jde stahovanému modelu. */
    @Test
    fun popisFotkyJdeStahovanemuModelu() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val liteRt = Recorder(OnDeviceAiStatus.Ready)
        val ai = RoutingOnDeviceAi(nano, liteRt)
        val model = testModel(multimodal = true).variant

        assertEquals(false, ai.canDescribeImage(ModelVariant.GeminiNano()))
        val text = ai.describeImage(model, "/fotka.jpg", "popiš").toList()

        assertEquals(listOf(Generated("/fotka.jpg popiš")), text)
        assertEquals(listOf(model), liteRt.seen)
    }

    /** Stahované modely jdou do téhož runtime, ať je počítá LiteRT-LM, nebo llama.cpp. */
    @Test
    fun stahovaneModelyJdouDoLiteRt() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val liteRt = Recorder(OnDeviceAiStatus.Downloadable)
        val ai = RoutingOnDeviceAi(nano, liteRt)
        val litert = testModel("litert").variant
        val gguf = testModel("gguf", ModelRuntime.LlamaCpp).variant

        assertEquals(OnDeviceAiStatus.Downloadable, ai.status(litert))
        assertEquals(OnDeviceAiStatus.Downloadable, ai.status(gguf))

        assertEquals(listOf(litert, gguf), liteRt.seen)
        assertEquals(emptyList(), nano.seen)
    }

    /** Varianta drží jen svoje pole, takže ML Kit nedostane nic, co by založilo druhého klienta. */
    @Test
    fun geminiNanoDostaneSvojeVolby() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val liteRt = Recorder(OnDeviceAiStatus.Downloadable)
        val ai = RoutingOnDeviceAi(nano, liteRt)

        assertEquals(OnDeviceAiStatus.Ready, ai.status(ModelVariant.GeminiNano(fast = true)))

        assertEquals(listOf<ModelVariant>(ModelVariant.GeminiNano(fast = true)), nano.seen)
        assertEquals(emptyList(), liteRt.seen)
    }

    @Test
    fun generovaniJdeStahovanemuModelu() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val liteRt = Recorder(OnDeviceAiStatus.Downloadable)
        val ai = RoutingOnDeviceAi(nano, liteRt)

        val text = ai.generate(testModel().variant, "ahoj").toList()

        assertEquals(listOf(Generated("ahoj")), text)
        assertEquals(listOf(testModel().variant), liteRt.seen)
        assertEquals(emptyList(), nano.seen)
    }

    /** Bez `apple` (Android, desktop) Apple Intelligence není: žádný runtime nedostane nic. */
    @Test
    fun appleSeNikamNeposila() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val liteRt = Recorder(OnDeviceAiStatus.Ready)

        assertEquals(OnDeviceAiStatus.Unsupported, RoutingOnDeviceAi(nano, liteRt).status(ModelVariant.Apple))

        assertEquals(emptyList(), nano.seen)
        assertEquals(emptyList(), liteRt.seen)
    }

    /** Na iOS jde Apple Intelligence svému runtime a stahované modely dál do runtime stahovaných modelů. */
    @Test
    fun naIosJdeAppleSvemuRuntime() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Unsupported)
        val liteRt = Recorder(OnDeviceAiStatus.Ready)
        val apple = Recorder(OnDeviceAiStatus.Ready)
        val ai = RoutingOnDeviceAi(nano, liteRt, apple)

        assertEquals(OnDeviceAiStatus.Ready, ai.status(ModelVariant.Apple))
        assertEquals(OnDeviceAiStatus.Ready, ai.status(testModel().variant))

        assertEquals(listOf<ModelVariant>(ModelVariant.Apple), apple.seen)
        assertEquals(listOf(testModel().variant), liteRt.seen)
        assertEquals(emptyList(), nano.seen)
    }

    @Test
    fun smazaniJdeZvolenemuRuntime() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Ready)
        val liteRt = Recorder(OnDeviceAiStatus.Ready)
        RoutingOnDeviceAi(nano, liteRt).delete(testModel().variant)
        assertEquals(1, liteRt.seen.size)
        assertEquals(emptyList(), nano.seen)
    }

    @Test
    fun zastaveniStahovaniJdeZvolenemuRuntime() = runTest {
        val nano = Recorder(OnDeviceAiStatus.Downloading)
        val liteRt = Recorder(OnDeviceAiStatus.Downloading)
        RoutingOnDeviceAi(nano, liteRt).stopDownload(testModel().variant)
        assertEquals(listOf(testModel().variant), liteRt.seen)
        assertEquals(emptyList(), nano.seen)
    }
}
