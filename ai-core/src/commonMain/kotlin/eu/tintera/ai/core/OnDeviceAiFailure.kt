package eu.tintera.ai.core

/**
 * Proč generování selhalo, převedené z chyby runtime na věc, se kterou uživatel umí něco dělat.
 * Větu pro uživatele z toho skládá aplikace (v Plume `userMessage()`): knihovna texty nenese.
 *
 * Dřív se každé selhání hlásilo jednou větou „Shrnutí se nepovedlo" a z obrazovky ani z logu se
 * nedalo poznat, jestli je model vytížený, nebo rozbitý v systému. Na Pixelu 11 Pro Foldu po
 * aktualizaci AICore přestal Gemini Nano nacházet vlastní soubory (`config.binarypb …
 * NOT_FOUND`, ML Kit kód 5) a každé shrnutí den a půl tiše selhávalo.
 */
enum class AiFailureReason {
    /** Model je vytížený nebo došla kvóta baterie — stačí počkat. */
    Busy,

    /** Vstup je na model moc dlouhý. */
    TooLarge,

    /** Na model není v telefonu místo. */
    NotEnoughSpace,

    /** Systém (AICore) je na model moc starý. */
    NeedsUpdate,

    /** Model teď není k dispozici, i když status tvrdil opak. */
    Unavailable,

    /** Model neumí jazyk textu (Apple Intelligence `unsupportedLanguageOrLocale`). */
    UnsupportedLanguage,

    /** Model text odmítl zpracovat (ochranné filtry, Apple Intelligence `guardrailViolation`). */
    Refused,

    /** Model selhal sám od sebe — typicky poškozený model v systému. Tohle patří do Crashlytics. */
    ModelError,
}

/**
 * Selhání generování s důvodem. Zpráva výjimky je anglická a bez textu promptu: jde do logu
 * a tím do Crashlytics, kde podle ní má být poznat, co se stalo („GeminiNano failed: ModelError
 * (code 5)"), a nesmí v ní být obsah konverzace.
 *
 * @property variant model, který selhal; podle něj aplikace hlášku pojmenuje.
 * @property code chybový kód runtime (ML Kit `GenAiException.errorCode`), když nějaký je.
 */
class OnDeviceAiException(
    val reason: AiFailureReason,
    val variant: ModelVariant,
    val code: Int? = null,
    cause: Throwable? = null,
) : Exception("${variant.logName} failed: $reason" + (code?.let { " (code $it)" } ?: ""), cause)

/** Jméno do zprávy výjimky: typ systémového modelu, nebo [DownloadedModel.id]. */
private val ModelVariant.logName: String
    get() = when (this) {
        is ModelVariant.GeminiNano -> "GeminiNano"
        ModelVariant.Apple -> "Apple"
        is ModelVariant.Downloaded -> model.id
    }

/**
 * Patří selhání do hlášení pádů jako samostatný problém? Jen to, co ukazuje na rozbitý model
 * nebo systém. Vytížený model, dlouhý vstup a plný disk jsou očekávané a jako problémy by
 * zaplevelily konzoli.
 */
val OnDeviceAiException.isReportable: Boolean
    get() = reason == AiFailureReason.ModelError ||
        reason == AiFailureReason.NeedsUpdate ||
        reason == AiFailureReason.Unavailable
