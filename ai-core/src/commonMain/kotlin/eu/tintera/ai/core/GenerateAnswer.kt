package eu.tintera.ai.core

import co.touchlab.kermit.Logger
import kotlin.reflect.KClass

/**
 * Odpověď modelu strukturovaně, kde to model umí ([OnDeviceAi.supportsStructuredOutput]),
 * jinak textem — výsledek je v obou případech týž [R].
 *
 * [prompt] dostane, jestli jde o strukturovaný dotaz: ten o formátu mlčí, protože schéma do
 * promptu přidá runtime sám a pokyny k řádkům by se s ním praly.
 *
 * **Na text se jde jen při špatném tvaru** ([StructuredOutputException]). `null` ze
 * [fromStructured] je platná odpověď („nevím", „žádný termín") — kdyby se po ní zkoušel
 * text, platilo by se dvakrát a opatrnost klasifikace by přebil druhý pokus. Rozbitý model
 * ([OnDeviceAiException]) letí ven jako u textu.
 */
suspend fun <A : Any, R> OnDeviceAi.generateAnswer(
    variant: ModelVariant,
    type: KClass<A>,
    prompt: (structured: Boolean) -> String,
    fromStructured: (A) -> R?,
    fromText: (String) -> R?,
): R? {
    if (supportsStructuredOutput(variant)) {
        try {
            val answer = generateStructured(variant, prompt(true), type)
            logger.d { "Strukturovaná odpověď: $answer" }
            return fromStructured(answer)
        } catch (e: StructuredOutputException) {
            logger.w(e) { "Strukturovaná odpověď (${type.simpleName}) se nepovedla, zkouším text" }
        }
    }
    var last = ""
    generate(variant, prompt(false)).collect { last = it.text }
    logger.d { "Model odpověděl: $last" }
    return fromText(last)
}

private val logger = Logger.withTag("OnDeviceAi")
