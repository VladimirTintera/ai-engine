package eu.tintera.ai.core

import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

/**
 * Předá volání tomu runtime, který uživatel zvolil (typ [ModelVariant]).
 *
 * Díky tomu je volba modelu jedna pro CELOU aplikaci: každá funkce jen předá variantu z nastavení
 * a nemusí vědět, kolik je runtime. Všechny stahované modely jdou do téhož runtime ([liteRt]);
 * který soubor načíst, mu řekne [ModelVariant.model].
 *
 * Na Androidu Gemini Nano a stahované modely; na iOS Apple Intelligence ([apple]) a stahované
 * modely, Gemini Nano tam je [NoopOnDeviceAi].
 */
class RoutingOnDeviceAi(
    private val geminiNano: OnDeviceAi,
    private val liteRt: OnDeviceAi,
    /** `AppleOnDeviceAi` na iOS; jinde Apple Intelligence není a varianta nikam nejde. */
    private val apple: OnDeviceAi = NoopOnDeviceAi,
) : OnDeviceAi {

    private fun target(variant: ModelVariant): OnDeviceAi = when (variant) {
        is ModelVariant.GeminiNano -> geminiNano
        is ModelVariant.Downloaded -> liteRt
        ModelVariant.Apple -> apple
    }

    private inline fun <T> route(variant: ModelVariant, call: OnDeviceAi.(ModelVariant) -> T): T =
        target(variant).call(variant)

    override suspend fun status(variant: ModelVariant): OnDeviceAiStatus = route(variant) { status(it) }

    override suspend fun info(variant: ModelVariant): ModelInfo? = route(variant) { info(it) }

    override fun download(variant: ModelVariant, mobileData: Boolean): Flow<ModelDownload> =
        route(variant) { download(it, mobileData) }

    override suspend fun stopDownload(variant: ModelVariant) = route(variant) { stopDownload(it) }

    override suspend fun setDownloadNetwork(variant: ModelVariant, mobileData: Boolean) =
        route(variant) { setDownloadNetwork(it, mobileData) }

    override suspend fun countTokens(variant: ModelVariant, prompt: String): Int? = route(variant) { countTokens(it, prompt) }

    override fun generate(variant: ModelVariant, prompt: String): Flow<Generated> =
        route(variant) { generate(it, prompt) }

    override suspend fun supportsStructuredOutput(variant: ModelVariant): Boolean =
        route(variant) { supportsStructuredOutput(it) }

    override suspend fun <T : Any> generateStructured(variant: ModelVariant, prompt: String, type: KClass<T>): T =
        route(variant) { generateStructured(it, prompt, type) }

    override suspend fun canTranscribe(variant: ModelVariant): Boolean = route(variant) { canTranscribe(it) }

    override fun transcribe(variant: ModelVariant, audioPath: String, prompt: String): Flow<Generated> =
        route(variant) { transcribe(it, audioPath, prompt) }

    override suspend fun canDescribeImage(variant: ModelVariant): Boolean = route(variant) { canDescribeImage(it) }

    override fun describeImage(variant: ModelVariant, imagePath: String, prompt: String): Flow<Generated> =
        route(variant) { describeImage(it, imagePath, prompt) }

    override suspend fun delete(variant: ModelVariant) = route(variant) { delete(it) }
}
