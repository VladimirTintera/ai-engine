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
 * se použije, určuje [ModelVariant.provider] a rozhoduje o tom [RoutingOnDeviceAi]. Kterou variantu
 * dostane která úloha, je věc aplikace — zapnuté mohou být oba modely a každá úloha jde jinému.
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
     * PŘIPRAVENÝ Gemini Nano, jehož AICore strukturovaný výstup hlásí; Gemma přes LiteRT-LM ho
     * nemá. Kdo dostane `false`, pokračuje textem a vlastním parserem.
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
        throw OnDeviceAiException(AiFailureReason.Unavailable, variant.provider)

    /**
     * Umí model přepsat řeč ze zvuku ([transcribe])? Jen PŘIPRAVENÝ model se zvukovým vstupem
     * (dnes Gemma 4; Gemini Nano přes ML Kit zvuk nebere). Levný dotaz, nic nenačítá.
     */
    suspend fun canTranscribe(variant: ModelVariant): Boolean = false

    /**
     * Přepíše řeč ze zvukového souboru [audioPath] (hlasovka OGG/Opus, kolečko MP4) do textu podle
     * pokynu [prompt] (ten skládá aplikace, i s nápovědou jazyka). Vydává celý dosavadní text jako
     * [generate]. Kde přepis není ([canTranscribe] `false`), selže.
     */
    fun transcribe(variant: ModelVariant, audioPath: String, prompt: String): Flow<Generated> =
        flow { throw OnDeviceAiException(AiFailureReason.Unavailable, variant.provider) }

    /**
     * Umí model popsat obrázek ([describeImage])? Jen PŘIPRAVENÝ model s obrazovým vstupem
     * (dnes Gemma 4; Gemini Nano přes Prompt API bere jen text). Levný dotaz, nic nenačítá.
     */
    suspend fun canDescribeImage(variant: ModelVariant): Boolean = false

    /**
     * Popíše fotku ze souboru [imagePath] podle pokynu [prompt] (popis pro toho, kdo ji nevidí,
     * návrh popisku k odeslání… — skládá aplikace). Vydává celý dosavadní text jako [generate].
     * Kde popis není ([canDescribeImage] `false`), selže.
     */
    fun describeImage(variant: ModelVariant, imagePath: String, prompt: String): Flow<Generated> =
        flow { throw OnDeviceAiException(AiFailureReason.Unavailable, variant.provider) }

    /**
     * Smaže stažený model a uvolní místo. Jen tam, kde model stahuje aplikace sama
     * ([ModelVariant.appManaged]); systémový model (AICore) spravuje telefon a tohle je u něj
     * no-op.
     */
    suspend fun delete(variant: ModelVariant) {}
}

/**
 * Kterou podobu modelu chceme. Volby jsou PŘÁNÍ — co opravdu běží, řekne [ModelInfo.name].
 *
 * @property provider který runtime model počítá. Pro kterou úlohu který, vybírá aplikace.
 * @property preview (jen Gemini Nano) nejnovější verze v předběžném vydání místo stabilní.
 *   Bývá jen na novějších zařízeních a stahuje se zvlášť.
 * @property fast (jen Gemini Nano) rychlejší a méně přesná varianta místo plné.
 * @property gemma (jen Gemma) kterou velikost si aplikace stáhne.
 * @property qwen (jen Qwen) kterou velikost si aplikace stáhne.
 * @property euroLlm (jen EuroLLM) kterou velikost si aplikace stáhne.
 * @property aya (jen Aya) který soubor si aplikace stáhne; dnes je jediný.
 */
data class ModelVariant(
    val provider: AiProvider = AiProvider.GeminiNano,
    val preview: Boolean = false,
    val fast: Boolean = false,
    val gemma: LiteRtModel = LiteRtModel.E2B,
    val qwen: LiteRtModel = LiteRtModel.Qwen3_4B,
    val euroLlm: LiteRtModel = LiteRtModel.EuroLlm9B,
    val aya: LiteRtModel = LiteRtModel.AyaExpanse8B,
) {
    /** Soubor, který si aplikace stáhne a počítá sama; `null` u systémového modelu. */
    val model: LiteRtModel?
        get() = when (provider) {
            AiProvider.Gemma -> gemma
            AiProvider.Qwen -> qwen
            AiProvider.EuroLlm -> euroLlm
            AiProvider.Aya -> aya
            AiProvider.GeminiNano, AiProvider.Apple -> null
        }

    /** Model stahuje a maže aplikace sama, ne systém. */
    val appManaged: Boolean get() = model != null
}

/**
 * Kdo model počítá.
 *
 * - [GeminiNano]: systémový model v AICore. Stahuje a aktualizuje ho telefon, aplikace nic
 *   nebalí, ale je jen na některých zařízeních a ML Kit nepustí k větším variantám.
 * - [Gemma]: otevřený model, který si stáhne aplikace (gigabajty) a počítá ho přes LiteRT-LM.
 *   Běží na každém dost silném telefonu a jde vybrat i větší E4B.
 * - [Qwen]: druhý otevřený model přes tentýž runtime, jen na text. Je tu kvůli češtině — jestli
 *   ji umí líp než Gemma, má ukázat porovnání modelů na skutečné konverzaci.
 * - [EuroLlm]: evropský otevřený model (EuroLLM a EuroMoE od utter-project), trénovaný na všech
 *   jazycích EU včetně češtiny. Jen text, přes llama.cpp ([ModelRuntime.LlamaCpp]) — ve formátu
 *   LiteRT-LM není.
 * - [Aya]: vícejazyčný model Aya Expanse od Cohere Labs, 23 jazyků včetně češtiny. Jen text, přes
 *   llama.cpp. **Licence CC BY-NC 4.0** (jen nekomerčně, s uvedením autora) — aplikace ji má ukázat
 *   před stažením, protože licenci přijímá uživatel, který si model stahuje.
 */
enum class AiProvider {
    GeminiNano,
    Gemma,
    Qwen,
    EuroLlm,
    Aya,

    /**
     * Apple Intelligence (Foundation Models) na iOS 26+: systémový model jako Gemini Nano —
     * stahuje a aktualizuje ho systém, aplikace ho jen používá (`AppleOnDeviceAi`, Swift most).
     */
    Apple,
}

/**
 * Čím se stahovaný model počítá. Na Androidu je každý runtime vlastní součást z Google Play.
 * Jestli umí GPU, záleží na platformě (`LiteRtPlatform.gpu`).
 */
enum class ModelRuntime {
    /** LiteRT-LM (soubory `.litertlm`): Gemma a Qwen, Android i desktop. */
    LiteRt,

    /** llama.cpp (soubory GGUF): EuroLLM a Aya, Android i desktop; GPU jen Metal na Macu. */
    LlamaCpp,
}

/**
 * Modely, které si aplikace umí stáhnout a počítá je sama — přes LiteRT-LM, nebo llama.cpp
 * ([runtime]). Jméno zůstalo z doby, kdy byl jen LiteRT-LM.
 *
 * **Commit je součást adresy**, takže se stahuje pořád tentýž soubor, i když autor na Hugging
 * Face nahraje novější — nová verze = nový řádek tady, vědomě. Pod [minMemoryGb] se model
 * nenabízí, protože by systém aplikaci při načítání zabil. Jména položek se ukládají
 * (nastavení aplikace, jméno úlohy stahování), takže se nepřejmenovávají.
 *
 * Pořadí položek téhož poskytovatele je pořadí, ve kterém se hledá náhrada za velikost, na
 * kterou zařízení nestačí (v Plume `supportedModels` v Nastavení). Qweny jdou od největšího, takže
 * telefon dostane největší, který unese; u Gemmy je první výchozí menší E2B.
 *
 * @property provider komu model patří: volba velikosti v Nastavení je jen mezi modely téhož.
 * @property multimodal bere i zvuk a obraz (přepis hlasovek, popis fotek), ne jen text.
 * @property contextTokens kolik tokenů (vstup i výstup dohromady) soubor unese; víc engine
 *   nedostane, ani když by aplikace chtěla delší kontext.
 * @property runtime čím se soubor počítá.
 */
enum class LiteRtModel(
    val displayName: String,
    val provider: AiProvider,
    val repository: String,
    val fileName: String,
    val commit: String,
    val sizeBytes: Long,
    val minMemoryGb: Int,
    val multimodal: Boolean,
    val contextTokens: Int = 8_192,
    val runtime: ModelRuntime = ModelRuntime.LiteRt,
) {
    // Gemma: soubor, commit, velikost i paměť z katalogu Google AI Edge Gallery
    // (`model_allowlists/1_0_19.json`).
    E2B(
        displayName = "Gemma 4 E2B",
        provider = AiProvider.Gemma,
        repository = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        commit = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
        sizeBytes = 2_588_147_712,
        minMemoryGb = 8,
        multimodal = true,
    ),
    E4B(
        displayName = "Gemma 4 E4B",
        provider = AiProvider.Gemma,
        repository = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName = "gemma-4-E4B-it.litertlm",
        commit = "28299f30ee4d43294517a4ac93abd6163412f07f",
        sizeBytes = 3_659_530_240,
        minMemoryGb = 12,
        multimodal = true,
    ),

    /*
     * Qwen3 4B v Gallery není; soubor je z litert-community (Apache 2.0, bez souhlasu s licencí).
     * Dynamický INT4 s GPU kompozity (export z 31. 10. 2026) potřebuje LiteRT-LM 0.19 — se
     * starší verzí GPU engine na souboru s vnějšími vahami spadne (LiteRT-LM#3986). Paměť podle
     * karty modelu: na 8GB Pixelu 8a při prvním výpočtu došla, proto 12 GB jako u E4B.
     */
    Qwen3_4B(
        displayName = "Qwen3 4B",
        provider = AiProvider.Qwen,
        repository = "litert-community/Qwen3-4B",
        fileName = "qwen3_4b_mixed_int4.litertlm",
        commit = "2e62465be458fb02f7a92e339f98270f42dc9b32",
        sizeBytes = 2_272_935_936,
        minMemoryGb = 12,
        multimodal = false,
        contextTokens = 4_096,
    ),

    /** Qwen2.5 1,5B: soubor, commit, velikost i paměť z katalogu Gallery (`1_0_23.json`). */
    Qwen2_5_1_5B(
        displayName = "Qwen2.5 1.5B",
        provider = AiProvider.Qwen,
        repository = "litert-community/Qwen2.5-1.5B-Instruct",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        commit = "5e6e7a58d03777898b31330a15d2e0a5a9107de3",
        sizeBytes = 1_597_922_856,
        minMemoryGb = 6,
        multimodal = false,
        contextTokens = 4_096,
    ),

    /*
     * Qwen3 0,6B z litert-community, týž export jako Qwen3 4B (INT4, GPU kompozity, LiteRT-LM
     * 0.19). Paměť: proces s ním podle karty modelu na Pixelu 8a sahá na ~1,9 GB, proto 4 GB.
     */
    Qwen3_0_6B(
        displayName = "Qwen3 0.6B",
        provider = AiProvider.Qwen,
        repository = "litert-community/Qwen3-0.6B",
        fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        commit = "7c650468cc1598861b1a0deb0ab25be6b20cab13",
        sizeBytes = 343_490_560,
        minMemoryGb = 4,
        multimodal = false,
        contextTokens = 4_096,
    ),

    /*
     * EuroLLM 9B Instruct (verze 2512) od utter-project, Apache 2.0, GGUF Q4_K_M od mradermacher
     * (originál je za souhlasem, kvantizace ne). Kontext 4 096 kvůli paměti: KV keš 9B modelu roste
     * s délkou a soubor sám má 5,6 GB. Paměť 16 GB — 12GB telefon by po načtení měl na zbytek
     * systému a aplikace jen pár GB.
     */
    EuroLlm9B(
        displayName = "EuroLLM 9B",
        provider = AiProvider.EuroLlm,
        repository = "mradermacher/EuroLLM-9B-Instruct-2512-GGUF",
        fileName = "EuroLLM-9B-Instruct-2512.Q4_K_M.gguf",
        commit = "5138603fccb9dc1f35c25cfd1a4981a78617e9ff",
        sizeBytes = 5_582_838_912,
        minMemoryGb = 16,
        multimodal = false,
        contextTokens = 4_096,
        runtime = ModelRuntime.LlamaCpp,
    ),

    /*
     * EuroMoE 2,6B (aktivních 0,6B) Instruct 2512 od utter-project: směs expertů, takže počítá
     * rychlostí malého modelu a zná jazyky jako větší. Apache 2.0, GGUF Q4_K_M od mradermacher.
     */
    EuroMoe2_6B(
        displayName = "EuroMoE 2.6B",
        provider = AiProvider.EuroLlm,
        repository = "mradermacher/EuroMoE-2.6B-A0.6B-Instruct-2512-GGUF",
        fileName = "EuroMoE-2.6B-A0.6B-Instruct-2512.Q4_K_M.gguf",
        commit = "11be4a9e68eb5aba18e4c3b341cbef2d018b2da0",
        sizeBytes = 1_616_056_480,
        minMemoryGb = 6,
        multimodal = false,
        contextTokens = 4_096,
        runtime = ModelRuntime.LlamaCpp,
    ),

    /*
     * Aya Expanse 8B od Cohere Labs (architektura Command R), CC BY-NC 4.0 a pravidla použití
     * Cohere Labs. GGUF Q4_K_M od bartowski — originál je za souhlasem a sdílením kontaktu,
     * kvantizace ne; licence nekomerční šíření s uvedením autora dovoluje. Jediná velikost
     * (druhá je 32B), paměť a kontext jako EuroLLM 9B: soubor má 5,1 GB.
     */
    AyaExpanse8B(
        displayName = "Aya Expanse 8B",
        provider = AiProvider.Aya,
        repository = "bartowski/aya-expanse-8b-GGUF",
        fileName = "aya-expanse-8b-Q4_K_M.gguf",
        commit = "f9d62ed0c58e6f2ae17975df990b1b8a4013b596",
        sizeBytes = 5_056_982_720,
        minMemoryGb = 16,
        multimodal = false,
        contextTokens = 4_096,
        runtime = ModelRuntime.LlamaCpp,
    ),
    ;

    /** Varianta, která tenhle soubor počítá — na dotazy po souborech (Úložiště, dostupnost). */
    val variant: ModelVariant
        get() = when (provider) {
            AiProvider.Gemma -> ModelVariant(provider = provider, gemma = this)
            AiProvider.Qwen -> ModelVariant(provider = provider, qwen = this)
            AiProvider.EuroLlm -> ModelVariant(provider = provider, euroLlm = this)
            AiProvider.Aya -> ModelVariant(provider = provider, aya = this)
            AiProvider.GeminiNano, AiProvider.Apple -> ModelVariant(provider = provider)
        }

    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$commit/$fileName?download=true"

    /**
     * Patří soubor [name] z keše runtime k tomuhle modelu? LiteRT-LM si při prvním načtení
     * zkompiluje model pro procesor a uloží ho vedle (`<soubor>_<čas>_<velikost>.xnnpack_cache`,
     * u Gemmy navíc `….audio_adapter.xnnpack_cache` a `….static_audio_encoder.xnnpack_cache`).
     * Keš je velká skoro jako model (Gemma 4 E4B 2,3 GB) a bez smazání s modelem zůstávala.
     */
    fun ownsCacheFile(name: String): Boolean = name.startsWith("${fileName}_") && name.endsWith(CACHE_SUFFIX)
}

private const val CACHE_SUFFIX = ".xnnpack_cache"

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
