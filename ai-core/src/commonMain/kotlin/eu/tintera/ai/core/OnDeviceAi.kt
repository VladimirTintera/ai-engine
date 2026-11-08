package eu.tintera.ai.core

import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * Jazykový model běžící v zařízení.
 *
 * Za rozhraním jsou na Androidu DVA runtime — Gemini Nano v AICore přes ML Kit Prompt API
 * (`com.google.mlkit.genai.*`, [MlKitOnDeviceAi]) a stahované modely přes LiteRT-LM nebo llama.cpp
 * ([LiteRtOnDeviceAi]; knihovnu runtime nevidí ani tenhle modul, jen rozhraní enginu
 * :ai-engine a [LiteRtRuntime]). Na iOS je to Apple Intelligence (`AppleOnDeviceAi`). Který
 * se použije, určuje typ [ModelVariant] a rozhoduje o tom [RoutingOnDeviceAi]. Kterou variantu
 * dostane která úloha, je věc aplikace — zapnuté mohou být oba modely a každá úloha jde jinému.
 *
 * **Knihovna zná runtime, aplikace zná modely.** Které modely se dají stáhnout, dodává aplikace
 * ([DownloadedModel]); knihovna o žádném konkrétním neví.
 *
 * Rozhraní, ne `expect class`: ViewModel se pak dá testovat s podstrčeným modelem.
 *
 * Každé volání bere [ModelVariant] — varianty jsou různé modely, každá se stahuje zvlášť a má
 * vlastní stav i limit. Prompty skládá aplikace; knihovna je jen předá modelu.
 */
interface OnDeviceAi {

    /** V jakém stavu je model. Levný dotaz na systém, nic nestahuje. */
    suspend fun status(variant: ModelVariant): OnDeviceAiStatus

    /** Název a limit vstupu; `null`, když model v zařízení není (limit zná až stažený model). */
    suspend fun info(variant: ModelVariant): ModelInfo?

    /**
     * Stáhne model a hlásí průběh. Tok skončí po dokončení, selhání se vyhodí jako výjimka,
     * zastavení ([stopDownload]) jako [DownloadStoppedException]. Volání ve stavu
     * [OnDeviceAiStatus.Downloading] se připojí k běžícímu stahování, ve stavu
     * [OnDeviceAiStatus.Paused] naváže na staženou část.
     *
     * [mobileData] `false` = stahovat jen na neplacené síti (Wi-Fi), na placené počkat. Platí jen
     * u modelu, který stahuje aplikace, a jen kde platforma placenou síť pozná ([MeteredNetwork]);
     * stahování AICore řídí systém. U běžícího stahování se nemění, na to je [setDownloadNetwork].
     */
    fun download(variant: ModelVariant, mobileData: Boolean = true): Flow<ModelDownload>

    /**
     * Zastaví stahování; stažená část zůstává a příští [download] na ni naváže. Jen tam, kde
     * model stahuje aplikace sama ([ModelVariant.appManaged]) — stahování AICore řídí systém.
     */
    suspend fun stopDownload(variant: ModelVariant) {}

    /**
     * Změní, přes jakou síť smí rozběhnuté (nebo na síť čekající) stahování jet — jako
     * [download] s [mobileData]. Stahování, které neběží, se tím nespustí.
     */
    suspend fun setDownloadNetwork(variant: ModelVariant, mobileData: Boolean) {}

    /** Kolik tokenů zabere [prompt] na vstupu; `null`, když to model neumí říct. */
    suspend fun countTokens(variant: ModelVariant, prompt: String): Int?

    /**
     * Vygeneruje odpověď na [prompt] a vydává ji postupně.
     *
     * Každá hodnota nese CELÝ dosavadní text, ne přírůstek — obrazovka ho jen vykreslí a nemusí
     * nic skládat.
     */
    fun generate(variant: ModelVariant, prompt: String): Flow<Generated>

    /**
     * Umí model vrátit odpověď rovnou jako typ ([generateStructured]), ne jako text? Jen
     * PŘIPRAVENÝ Gemini Nano, jehož AICore strukturovaný výstup hlásí; stahované modely ho
     * nemají. Kdo dostane `false`, pokračuje textem a vlastním parserem.
     */
    suspend fun supportsStructuredOutput(variant: ModelVariant): Boolean = false

    /**
     * Vygeneruje odpověď na [prompt] rovnou jako instanci [type] — tvar drží model, ne parser.
     *
     * **Typ musí mít v platformě popis tvaru** (na Androidu `GenerableProvider` v aplikaci
     * a řádek v `META-INF/services`, viz [generableStringField]); neznámý typ selže. Nestreamuje se: polovina objektu
     * nic neznamená. Kde strukturovaný výstup není ([supportsStructuredOutput] `false`), selže.
     */
    suspend fun <T : Any> generateStructured(variant: ModelVariant, prompt: String, type: KClass<T>): T =
        throw OnDeviceAiException(AiFailureReason.Unavailable, variant)

    /**
     * Umí model přepsat řeč ze zvuku ([transcribe])? Jen PŘIPRAVENÝ model se zvukovým vstupem
     * ([DownloadedModel.multimodal]; Gemini Nano přes ML Kit zvuk nebere). Levný dotaz, nic nenačítá.
     */
    suspend fun canTranscribe(variant: ModelVariant): Boolean = false

    /**
     * Přepíše řeč ze zvukového souboru [audioPath] (hlasovka OGG/Opus, kolečko MP4) do textu podle
     * pokynu [prompt] (ten skládá aplikace, i s nápovědou jazyka). Vydává celý dosavadní text jako
     * [generate]. Kde přepis není ([canTranscribe] `false`), selže.
     */
    fun transcribe(variant: ModelVariant, audioPath: String, prompt: String): Flow<Generated> =
        flow { throw OnDeviceAiException(AiFailureReason.Unavailable, variant) }

    /**
     * Umí model popsat obrázek ([describeImage])? Jen PŘIPRAVENÝ model s obrazovým vstupem
     * ([DownloadedModel.multimodal]; Gemini Nano přes Prompt API bere jen text). Levný dotaz, nic nenačítá.
     */
    suspend fun canDescribeImage(variant: ModelVariant): Boolean = false

    /**
     * Popíše fotku ze souboru [imagePath] podle pokynu [prompt] (popis pro toho, kdo ji nevidí,
     * návrh popisku k odeslání… — skládá aplikace). Vydává celý dosavadní text jako [generate].
     * Kde popis není ([canDescribeImage] `false`), selže.
     */
    fun describeImage(variant: ModelVariant, imagePath: String, prompt: String): Flow<Generated> =
        flow { throw OnDeviceAiException(AiFailureReason.Unavailable, variant) }

    /**
     * Smaže stažený model a uvolní místo. Jen tam, kde model stahuje aplikace sama
     * ([ModelVariant.appManaged]); systémový model (AICore) spravuje telefon a tohle je u něj
     * no-op.
     */
    suspend fun delete(variant: ModelVariant) {}
}

/**
 * Který model chceme: typ určuje runtime, který ho počítá. Pro kterou úlohu který, vybírá aplikace.
 */
sealed interface ModelVariant {

    /** Soubor, který si aplikace stáhne a počítá sama; `null` u systémového modelu. */
    val model: DownloadedModel? get() = null

    /** Model stahuje a maže aplikace sama, ne systém. */
    val appManaged: Boolean get() = model != null

    /**
     * Systémový model v AICore. Stahuje a aktualizuje ho telefon, aplikace nic nebalí, ale je jen
     * na některých zařízeních. Volby jsou PŘÁNÍ — co opravdu běží, řekne [ModelInfo.name].
     *
     * @property preview nejnovější verze v předběžném vydání místo stabilní. Bývá jen na novějších
     *   zařízeních a stahuje se zvlášť.
     * @property fast rychlejší a méně přesná varianta místo plné.
     */
    data class GeminiNano(val preview: Boolean = false, val fast: Boolean = false) : ModelVariant

    /**
     * Apple Intelligence (Foundation Models) na iOS 26+: systémový model jako Gemini Nano —
     * stahuje a aktualizuje ho systém, aplikace ho jen používá (`AppleOnDeviceAi`, Swift most).
     */
    data object Apple : ModelVariant

    /** Soubor z katalogu aplikace, který se stáhne a počítá uvnitř procesu ([LiteRtOnDeviceAi]). */
    data class Downloaded(override val model: DownloadedModel) : ModelVariant
}

/**
 * Čím se stahovaný model počítá. Na Androidu je každý runtime vlastní součást z Google Play.
 * Jestli umí GPU, záleží na platformě (`LiteRtPlatform.gpu`).
 */
enum class ModelRuntime {
    /** LiteRT-LM (soubory `.litertlm`), Android i desktop. */
    LiteRt,

    /** llama.cpp (soubory GGUF), Android i desktop; GPU jen Metal na Macu. */
    LlamaCpp,
}

/**
 * Model, který si aplikace umí stáhnout a počítá ho sama přes LiteRT-LM, nebo llama.cpp ([runtime]).
 * **Katalog drží aplikace**, knihovna žádný konkrétní model nezná.
 *
 * Dvě instance se stejnými hodnotami jsou tentýž model (stav stahování, načtený engine).
 *
 * @property id stálé: ukládá se (nastavení aplikace) a je ve jméně úlohy stahování a v jejích datech
 *   (`AiModelDownloadWorker`), takže přejmenování ztratí rozběhnutou úlohu i uloženou volbu.
 * @property url odkud se soubor stáhne. **Má mířit na pevnou verzi**, ať se stahuje pořád tentýž
 *   soubor, i když autor nahraje novější ([huggingFaceUrl]).
 * @property fileName jméno souboru v adresáři modelů a začátek jmen jeho keší ([ownsCacheFile]).
 * @property sizeBytes velikost souboru; kolik se bude stahovat, než to řekne server.
 * @property minMemoryGb pod touhle pamětí se model nenabízí, protože by systém aplikaci při načítání
 *   zabil (`LiteRtPlatform.canRun`).
 * @property runtime čím se soubor počítá.
 * @property contextTokens kolik tokenů (vstup i výstup dohromady) soubor unese; víc engine nedostane,
 *   ani když by aplikace chtěla delší kontext. Strop dává i knihovna (8 192).
 * @property multimodal bere i zvuk a obraz (přepis hlasovek, popis fotek), ne jen text.
 */
data class DownloadedModel(
    val id: String,
    val displayName: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val minMemoryGb: Int,
    val runtime: ModelRuntime,
    val contextTokens: Int = 8_192,
    val multimodal: Boolean = false,
) {
    /** Varianta, která tenhle soubor počítá — na dotazy po souborech (Úložiště, dostupnost). */
    val variant: ModelVariant get() = ModelVariant.Downloaded(this)

    /**
     * Patří soubor [name] z keše runtime k tomuhle modelu? LiteRT-LM si při prvním načtení
     * zkompiluje model pro procesor a uloží ho vedle (`<soubor>_<čas>_<velikost>.xnnpack_cache`,
     * u modelu se zvukem navíc `….audio_adapter.xnnpack_cache` a `….static_audio_encoder.xnnpack_cache`).
     * Pokus o GPU nechá i keš pro grafiku (`…_mldrift_weight_cache.bin` a
     * `…_mldrift_program_cache.bin`), a to i když GPU nakonec selže a jede se na procesoru.
     * Keš je velká skoro jako model a bez smazání s modelem zůstávala. llama.cpp keš modelu na disk nedělá.
     */
    fun ownsCacheFile(name: String): Boolean = when (runtime) {
        ModelRuntime.LiteRt -> name.startsWith("${fileName}_") && LITERT_CACHE_SUFFIXES.any { name.endsWith(it) }
        ModelRuntime.LlamaCpp -> false
    }
}

/**
 * Adresa souboru [file] z repozitáře [repo] na Hugging Face. **Commit je součást adresy**, takže se
 * stahuje pořád tentýž soubor, i když autor nahraje novější — nová verze je vědomá změna v katalogu.
 */
fun huggingFaceUrl(repo: String, commit: String, file: String): String =
    "https://huggingface.co/$repo/resolve/$commit/$file?download=true"

/** Přípony keší LiteRT-LM 0.19 (řetězce z `liblitertlm_jni.so`): procesor a GPU (ML Drift). */
private val LITERT_CACHE_SUFFIXES = listOf(
    ".xnnpack_cache",
    "_mldrift_weight_cache.bin",
    "_mldrift_program_cache.bin",
    "_mldrift_compiled_cache.bin",
)

/**
 * Model odpověděl, ale ne v požadovaném tvaru ([OnDeviceAi.generateStructured]): JSON, který
 * do typu nesedí, nebo useknutý strop výstupu. **Není to rozbitý model** ([OnDeviceAiException]
 * by šel do Crashlytics jako problém) — volající má jít textem, jako by strukturovaný výstup
 * nebyl.
 *
 * @property code kód runtime (ML Kit `STRUCTURED_OUTPUT_…`), když nějaký je.
 */
class StructuredOutputException(val code: Int?, cause: Throwable? = null) :
    Exception("Structured output failed" + (code?.let { " (code $it)" } ?: ""), cause)

/**
 * Na model není v telefonu dost místa. Hlásí se ZVLÁŠŤ, ne jako obecné selhání stahování:
 * u modelu o gigabajtech je to nejčastější důvod a uživatel s ním jako jediným může něco dělat.
 */
class NotEnoughSpaceException(val neededBytes: Long) : Exception("Not enough space: $neededBytes B")

/**
 * Runtime modelu se nepodařilo doinstalovat (na Androidu součást z Google Play, viz
 * `PlayLiteRtRuntime`). Hlásí se ZVLÁŠŤ, ne jako selhání stahování modelu: model se ani nezačal
 * stahovat, chyba je v doručení aplikace, a kdo ji hlásí, má z obrazovky poznat, kterou z nich
 * vidí. [code] je kód od Google Play (`SplitInstallErrorCode`), když nějaký je.
 */
class RuntimeInstallException(val code: Int? = null, cause: Throwable? = null) :
    Exception("On-device AI runtime install failed" + (code?.let { " (code $it)" } ?: ""), cause)

/**
 * Stahování zastavil uživatel ([OnDeviceAi.stopDownload]). Není to selhání: stažená část
 * zůstává a obrazovka nabídne pokračování. Schválně ne `CancellationException` — tu volající
 * posílají dál a o zastavení by se nedozvěděli.
 */
class DownloadStoppedException : Exception("Model download stopped")

/**
 * Kus generované odpovědi.
 *
 * [text] je celý dosavadní text. [truncated] znamená, že model NEDOMLUVIL: došel mu strop
 * výstupu a text prostě končí, klidně uprostřed věty. Není to chyba — odpověď je platná, jen
 * neúplná —, takže se nevyhazuje výjimka; uživateli to ale musí UI říct, jinak si useknuté
 * shrnutí přečte jako úplné. Model to hlásí sám (ML Kit `Candidate.FinishReason.MAX_TOKENS`),
 * nepoznává se to odhadem z délky textu.
 */
data class Generated(val text: String, val truncated: Boolean = false)

/** Co o modelu víme. [tokenLimit] je strop VSTUPU v tokenech. */
data class ModelInfo(val name: String?, val tokenLimit: Int?)

enum class OnDeviceAiStatus {
    /** Zařízení model (v téhle variantě) nemá a mít nebude (bez AICore, málo paměti, iOS). */
    Unsupported,

    /** Model jde stáhnout. */
    Downloadable,

    /** Model se právě stahuje (nebo na stahování čeká, třeba na síť). */
    Downloading,

    /**
     * Model je stažený zčásti a stahování neběží — uživatel ho zastavil. Příští stažení naváže
     * na to, co už je v telefonu. Jen u modelu, který stahuje aplikace.
     */
    Paused,

    /** Model je v zařízení a jde hned použít. */
    Ready,
}

/**
 * Průběh stahování modelu. [totalBytes] je `null`, dokud ho systém neohlásí.
 *
 * @property waitingForNetwork stahování se přerušilo se sítí a naváže samo, až se připojení
 *   vrátí. Pořád je to stahování, ne selhání: uživatel nemá nic dělat.
 * @property wifiOnly stahuje se jen přes Wi-Fi; s [waitingForNetwork] tedy čeká na Wi-Fi, ne na
 *   jakékoli připojení (na mobilních datech stojí).
 */
data class ModelDownload(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val waitingForNetwork: Boolean = false,
    val wifiOnly: Boolean = false,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

/** Platforma (nebo runtime) bez modelu v zařízení. Funkce se pak v UI vůbec nenabízí. */
object NoopOnDeviceAi : OnDeviceAi {
    override suspend fun status(variant: ModelVariant) = OnDeviceAiStatus.Unsupported
    override suspend fun info(variant: ModelVariant): ModelInfo? = null
    override fun download(variant: ModelVariant, mobileData: Boolean): Flow<ModelDownload> = emptyFlow()
    override suspend fun countTokens(variant: ModelVariant, prompt: String): Int? = null
    override fun generate(variant: ModelVariant, prompt: String): Flow<Generated> = emptyFlow()
}
