package eu.tintera.ai.core

/**
 * Je telefon zrovna pod tlakem — úsporný režim, nebo přehřátí? Pak stahovaný model SÁM OD SEBE
 * nic nepočítá ([avoidUnderLoad]): načtení modelu bere gigabajty paměti a desítky vteřin procesoru
 * (GPU), a to je přesně to, co úsporný režim i přehřátý telefon chtějí omezit.
 *
 * Čte se při volání, ne jako tok: proaktivní úloha se ptá těsně před tím, než by model pustila,
 * a když zrovna nejde, zkusí to s další změnou (nová zpráva, otevřený chat) jako u nepřipraveného
 * modelu.
 */
interface DeviceLoad {
    val strained: Boolean
}

/** Platforma bez Gemmy (iOS, desktop) a testy: zátěž se neřeší. */
object NoDeviceLoad : DeviceLoad {
    override val strained: Boolean = false
}

/**
 * [variant], pokud ji zátěž nevylučuje; stahovaný model (Gemma, Qwen…) pod zátěží ([strained])
 * = `null`. Pro úlohy, které aplikace pouští SAMA, ne na ťuknutí. **Gemini Nano a Apple jedou
 * i pod zátěží** — počítá je systém (AICore, Foundation Models), který si úsporu řídí sám,
 * a aplikace kvůli nim nic nenačítá.
 */
fun avoidUnderLoad(variant: ModelVariant, strained: Boolean): ModelVariant? =
    variant.takeUnless { strained && it.appManaged }
