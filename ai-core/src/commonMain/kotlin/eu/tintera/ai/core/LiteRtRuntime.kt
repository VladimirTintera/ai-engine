package eu.tintera.ai.core

import eu.tintera.ai.engine.LlmEngineFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Odkud se vezme runtime modelu (:ai-litert, :ai-llamacpp). Na desktopu je přibalený
 * (`BundledLiteRtRuntime`), na Androidu je to součást z Google Play stahovaná až na požádání
 * (`PlayLiteRtRuntime`, v Plume :client:litertFeature) — nativní knihovna má 23 MB a model
 * v zařízení si zapne málokdo. Na iOS je slinkovaný v aplikaci ([LinkedLiteRtRuntime]).
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
 * Runtime, který je v aplikaci vždycky a jehož továrnu aplikace vytvoří sama — na iOS
 * (`LlamaCppEngineFactory()` z :ai-llamacpp-ios), kde reflexe ani doinstalování není. Nic se
 * nedoinstalovává; továrna vznikne až při prvním načtení modelu.
 */
class LinkedLiteRtRuntime(factory: () -> LlmEngineFactory) : LiteRtRuntime {

    private val engines by lazy(factory)

    override fun installed(): Boolean = true

    override fun install(): Flow<ModelDownload> = emptyFlow()

    override fun engines(): LlmEngineFactory = engines
}
