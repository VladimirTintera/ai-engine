package eu.tintera.ai.core

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow

/**
 * Jazykový model systému iOS, který dodá Swift (`AppleTextModel` v `iosApp`, nad Apple
 * Foundation Models). Kotlin/Native na `FoundationModels` přímo nedosáhne: je to čistě Swiftové
 * API bez Objective-C, stejný důvod jako u hlášení pádů.
 *
 * Swift ho implementuje jako protokol (Kotlin rozhraní se do frameworku exportuje jako
 * Objective-C protokol) a předá ho při startu přes [IosOnDeviceModel].
 */
interface OnDeviceTextModel {
    /** Stav modelu, jedna z hodnot [OnDeviceTextStatus]. */
    fun availability(): Int

    /** Strop kontextu v tokenech — vstup i výstup dohromady. */
    fun contextSize(): Int

    /**
     * Spustí generování. [onText] dostává CELÝ dosavadní text (snímek, ne přírůstek), [onFinish]
     * jednou na konci: `null` = hotovo, jinak kód chyby ([OnDeviceTextError]). Volá se z vlákna,
     * které si vybere Swift.
     */
    fun generate(prompt: String, onText: (String) -> Unit, onFinish: (String?) -> Unit): OnDeviceTextTask
}

/** Rozběhnuté generování; zrušení ho zastaví (Swift `Task.cancel`). */
interface OnDeviceTextTask {
    fun cancel()
}

/** Stavy modelu z [OnDeviceTextModel.availability] (`SystemLanguageModel.availability`). */
object OnDeviceTextStatus {
    const val READY = 0

    /** Zařízení Apple Intelligence neumí. */
    const val UNSUPPORTED = 1

    /** Apple Intelligence je vypnutá v Nastavení. */
    const val NOT_ENABLED = 2

    /** Systém model ještě stahuje. */
    const val NOT_READY = 3
}

/** Kódy chyb z [OnDeviceTextModel.generate]; Swift je rozliší podle druhu chyby. */
object OnDeviceTextError {
    const val CONTEXT = "context"
    const val LANGUAGE = "language"
    const val REFUSED = "refused"
    const val BUSY = "busy"
    const val UNAVAILABLE = "unavailable"
}

/**
 * Sem Swift zaregistruje model při startu, PŘED `initKoin`. Bez něj (iOS pod 26, starší build)
 * AI v zařízení na iOS není a Koin dostane [NoopOnDeviceAi].
 */
object IosOnDeviceModel {
    var model: OnDeviceTextModel? = null
}

/**
 * [OnDeviceAi] nad Apple Intelligence ([AiProvider.Apple]).
 *
 * - **Systémový model jako Gemini Nano**: stahuje a aktualizuje ho iOS, aplikace nic nestahuje
 *   ani nemaže ([download] je prázdný). Vypnutá Apple Intelligence nebo model, který systém
 *   teprve stahuje, se hlásí jako [OnDeviceAiStatus.Unsupported] — sekce AI se pak nenabídne
 *   (nastavení na ni nemá stav „zapni si ji v systému"; poznatek v `ai-v-zarizeni.md`).
 * - **Ostatní poskytovatelé tu nejsou**: Gemini Nano i Gemma hlásí [OnDeviceAiStatus.Unsupported],
 *   takže nastavení samo přepne volbu na Apple (`supportedModels`).
 * - **Strukturovaný výstup, přepis řeči a popis obrázků zatím ne** (výchozí `false` rozhraní):
 *   Foundation Models je umí (`@Generable`), ale každý by chtěl vlastní Swift most.
 */
class AppleOnDeviceAi(private val model: OnDeviceTextModel) : OnDeviceAi {

    private val logger = Logger.withTag("OnDeviceAi")

    override suspend fun status(variant: ModelVariant): OnDeviceAiStatus {
        if (variant.provider != AiProvider.Apple) return OnDeviceAiStatus.Unsupported
        return when (val state = model.availability()) {
            OnDeviceTextStatus.READY -> OnDeviceAiStatus.Ready
            else -> {
                logger.i { "Apple Intelligence není připravená (stav $state)" }
                OnDeviceAiStatus.Unsupported
            }
        }
    }

    override suspend fun info(variant: ModelVariant): ModelInfo? =
        if (variant.provider == AiProvider.Apple) ModelInfo(name = MODEL_NAME, tokenLimit = model.contextSize()) else null

    override fun download(variant: ModelVariant, mobileData: Boolean): Flow<ModelDownload> = emptyFlow()

    // Počítání tokenů je až od iOS 26.4 a jen asynchronně; délka se odhaduje z limitu.
    override suspend fun countTokens(variant: ModelVariant, prompt: String): Int? = null

    override fun generate(variant: ModelVariant, prompt: String): Flow<Generated> = callbackFlow {
        val task = model.generate(
            prompt = prompt,
            onText = { text -> trySend(Generated(text)) },
            onFinish = { error -> if (error == null) close() else close(failure(error)) },
        )
        awaitClose { task.cancel() }
    }
        // Každý snímek je celý dosavadní text, takže pomalý sběrač smí mezikroky přeskočit.
        .conflate()

    private fun failure(code: String): OnDeviceAiException {
        val reason = when (code) {
            OnDeviceTextError.CONTEXT -> AiFailureReason.TooLarge
            OnDeviceTextError.LANGUAGE -> AiFailureReason.UnsupportedLanguage
            OnDeviceTextError.REFUSED -> AiFailureReason.Refused
            OnDeviceTextError.BUSY -> AiFailureReason.Busy
            OnDeviceTextError.UNAVAILABLE -> AiFailureReason.Unavailable
            else -> AiFailureReason.ModelError
        }
        if (reason == AiFailureReason.ModelError) logger.w { "Apple Intelligence selhala: $code" }
        return OnDeviceAiException(reason, AiProvider.Apple)
    }

    private companion object {
        const val MODEL_NAME = "Apple Intelligence"
    }
}
