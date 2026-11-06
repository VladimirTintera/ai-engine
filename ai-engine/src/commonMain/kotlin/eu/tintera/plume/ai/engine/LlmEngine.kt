package eu.tintera.plume.ai.engine

import kotlinx.coroutines.flow.Flow

/**
 * Runtime jazykového modelu, který počítá v našem procesu (LiteRT-LM). Stahování souboru modelu,
 * stav pro obrazovku a výběr modelu jsou věc aplikace (`LiteRtOnDeviceAi` v :client:shared);
 * tady je jen to, co se nad souborem děje uvnitř runtime.
 *
 * Implementace musí mít veřejný konstruktor bez parametrů: aplikace ji vytváří reflexí podle
 * jména ([LITERT_ENGINE_FACTORY]), protože na Androidu leží v modulu, který se doinstaluje
 * z Google Play až na požádání, a základ aplikace na jeho třídy při překladu nevidí.
 */
interface LlmEngineFactory {

    /**
     * Načte model a vrátí připravený engine. Drahé (sekundy, gigabajty RAM) a blokující; volá
     * se mimo hlavní vlákno. Vyhodí, když model na [LlmEngineConfig.backend] nejde načíst —
     * volající pak zkusí jiný backend.
     */
    fun open(config: LlmEngineConfig): LlmEngine
}

/** Na čem model počítá. Zvuk jde vždycky přes CPU, obraz přes týž backend jako text. */
enum class LlmBackend { Gpu, Cpu }

/**
 * @property audio engine bude umět zvukový vstup (přepis hlasovky).
 * @property vision engine bude umět jeden obrázek ke zprávě (popis fotky).
 * @property maxNumTokens velikost KV keše, tedy vstup a výstup DOHROMADY.
 * @property cacheDir kam smí runtime uložit model přeložený pro procesor; systém ho smí uklidit.
 */
class LlmEngineConfig(
    val modelPath: String,
    val backend: LlmBackend,
    val audio: Boolean,
    val vision: Boolean,
    val maxNumTokens: Int,
    val cacheDir: String,
)

/** Načtený model. Drží gigabajty paměti, dokud se nezavře. */
interface LlmEngine : AutoCloseable {

    /** Nová konverzace bez historie; jedna na dotaz. */
    fun conversation(sampling: LlmSampling): LlmConversation
}

data class LlmSampling(val topK: Int, val topP: Double, val temperature: Double)

interface LlmConversation : AutoCloseable {

    /**
     * Pošle zprávu a vrací PŘÍRŮSTKY odpovědi (ne celý text). Přemýšlení modelu je vypnuté.
     * [maxOutputTokens] `null` = bez stropu od nás.
     */
    fun send(content: List<LlmContent>, maxOutputTokens: Int? = null): Flow<String>

    /** Zastaví generování, které ještě běží; zrušený collector by ho jinak nechal dopočítat. */
    fun cancel()
}

/** Kus zprávy pro model. */
sealed interface LlmContent {
    class Text(val text: String) : LlmContent

    /** WAV 16 kHz mono 16 bitů, nejvýš 30 s. */
    class Audio(val wav: ByteArray) : LlmContent

    /** PNG. */
    class Image(val png: ByteArray) : LlmContent
}

/** Jméno implementace nad LiteRT-LM (:client:ai-litert); keep pravidlo pro R8 je v androidApp. */
const val LITERT_ENGINE_FACTORY = "eu.tintera.plume.ai.litert.LiteRtEngineFactory"

/** Jméno implementace nad llama.cpp (:client:ai-llamacpp, jen Android); keep pravidlo v androidApp. */
const val LLAMACPP_ENGINE_FACTORY = "eu.tintera.plume.ai.llamacpp.LlamaCppEngineFactory"
