package eu.tintera.ai.core

import eu.tintera.ai.engine.LITERT_ENGINE_FACTORY
import eu.tintera.ai.engine.LlmEngineFactory
import kotlinx.coroutines.flow.Flow

/**
 * Odkud se vezme runtime LiteRT-LM (:ai-litert). Na desktopu je přibalený, na Androidu je
 * to součást z Google Play stahovaná až na požádání (v Plume :client:litertFeature) — nativní knihovna
 * má 23 MB a model v zařízení si zapne málokdo.
 *
 * Stahuje se SPOLU s modelem ([LiteRtOnDeviceAi.download]): dokud runtime chybí, model není
 * připravený, ani když je jeho soubor stažený.
 */
interface LiteRtRuntime {

    /** Je runtime v aplikaci? Levný dotaz; nenačítá ho. */
    fun installed(): Boolean

    /**
     * Doinstaluje runtime a skončí, až je použitelný. Průběh v bajtech. Vyhodí
     * [RuntimeInstallException], když to nejde (aplikace není z Google Play, chybí síť…);
     * zrušení sběru instalaci zastaví.
     */
    fun install(): Flow<ModelDownload>

    /**
     * Továrna enginů. Jen po [installed]; vyhodí, když se runtime načíst nepodaří (i `LinkageError`
     * u nativní knihovny — volající ho chytá spolu s výjimkami).
     */
    fun engines(): LlmEngineFactory
}

/**
 * Implementace z :ai-litert podle jména. Reflexe, protože na Androidu leží v součásti,
 * na kterou základ aplikace při překladu nevidí; na desktopu kvůli tomu, že by modul nad
 * `shared` jinak tvořil kruh závislostí.
 */
internal fun loadEngineFactory(classLoader: ClassLoader, className: String = LITERT_ENGINE_FACTORY): LlmEngineFactory =
    Class.forName(className, true, classLoader).getDeclaredConstructor().newInstance() as LlmEngineFactory
