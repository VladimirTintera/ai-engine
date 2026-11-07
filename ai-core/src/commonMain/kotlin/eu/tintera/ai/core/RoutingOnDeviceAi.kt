package eu.tintera.ai.core

import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

/**
 * Předá volání tomu runtime, který uživatel zvolil ([ModelVariant.provider]).
 *
 * Díky tomu je poskytovatel jedna volba pro CELOU aplikaci: každá funkce jen předá variantu
 * z nastavení a nemusí vědět, že runtime jsou dva. Gemma
 * i Qwen jdou do téhož runtime ([liteRt]); který soubor načíst, mu řekne [ModelVariant.model].
 *
 * **Variantu normalizuje** ([forGeminiNano], [forGemma], [forQwen]): každý runtime dostane jen
 * pole, která se ho týkají. ML Kit drží klienta na variantu v mapě, takže by jinak kvůli změně
 * velikosti Gemmy vznikl další klient Gemini Nano pro tentýž model.
 */
class RoutingOnDeviceAi(
    private val geminiNano: OnDeviceAi,
    private val liteRt: OnDeviceAi,
) : OnDeviceAi {

    /** Runtime pro [variant] a varianta normalizovaná na jeho pole. */
    private fun target(variant: ModelVariant): Pair<OnDeviceAi, ModelVariant> = when (variant.provider) {
        AiProvider.GeminiNano -> geminiNano to variant.forGeminiNano()
        AiProvider.Gemma -> liteRt to variant.forGemma()
        AiProvider.Qwen -> liteRt to variant.forQwen()
        AiProvider.EuroLlm -> liteRt to variant.forEuroLlm()
        AiProvider.Aya -> liteRt to variant.forAya()
        // Apple Intelligence je jen na iOS, kde se směrovač nepoužívá (`AppleOnDeviceAi`).
        AiProvider.Apple -> NoopOnDeviceAi to variant
    }

    private inline fun <T> route(variant: ModelVariant, call: OnDeviceAi.(ModelVariant) -> T): T {
        val (runtime, normalized) = target(variant)
        return runtime.call(normalized)
    }

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

/** Jen to, co se týká Gemini Nano: preview a rychlá varianta. */
internal fun ModelVariant.forGeminiNano() = ModelVariant(provider = AiProvider.GeminiNano, preview = preview, fast = fast)

/** Jen to, co se týká Gemmy: který model. */
internal fun ModelVariant.forGemma() = ModelVariant(provider = AiProvider.Gemma, gemma = gemma)

/** Jen to, co se týká Qwenu: který model. */
internal fun ModelVariant.forQwen() = ModelVariant(provider = AiProvider.Qwen, qwen = qwen)

/** Jen to, co se týká EuroLLM: který model. */
internal fun ModelVariant.forEuroLlm() = ModelVariant(provider = AiProvider.EuroLlm, euroLlm = euroLlm)

/** Jen to, co se týká Ayi: který model. */
internal fun ModelVariant.forAya() = ModelVariant(provider = AiProvider.Aya, aya = aya)
