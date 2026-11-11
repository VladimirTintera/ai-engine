package eu.tintera.ai.core

import co.touchlab.kermit.Logger
import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.engine.LlmContent
import eu.tintera.ai.engine.LlmEngine
import eu.tintera.ai.engine.LlmEngineConfig
import eu.tintera.ai.engine.LlmSampling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Modely, které si stáhne a spravuje aplikace sama, přes LiteRT-LM, nebo llama.cpp. Který soubor,
 * říká [ModelVariant.model] ([DownloadedModel]), a kterým runtime se počítá, [DownloadedModel.runtime].
 * Stahování, stav i správa enginu jsou pro oba runtime společné; jméno třídy zůstalo z doby, kdy byl
 * jen LiteRT-LM.
 *
 * Na rozdíl od Gemini Nano (`MlKitOnDeviceAi`) tu není systémová služba — soubor modelu (stovky MB
 * až gigabajty) leží v adresáři od platformy ([LiteRtPlatform.modelsDir]) a počítá ho engine uvnitř
 * našeho procesu. **Kód je společný pro Android, desktop i iOS** (`commonMain`); co se liší,
 * je za [LiteRtPlatform], síť za `httpDownload` (`HttpURLConnection`, na iOS `NSURLSession`).
 *
 * - **Samotnou knihovnu LiteRT-LM tu nevidíme**, jen rozhraní enginu (:ai-engine). Runtime
 *   dává [LiteRtRuntime]: na desktopu přibalený, na Androidu součást z Google Play, která se
 *   doinstaluje SPOLU s modelem ([download]), na iOS slinkovaný v aplikaci. Model je připravený,
 *   až když je tu obojí.
 * - **Stahuje se do `.part` a přejmenovává až hotový.** Přítomnost výsledného souboru tedy
 *   znamená „kompletní model" a nic dalšího se kontrolovat nemusí. Přerušené stahování
 *   navazuje (`Range`) — u gigabajtů by začínat od nuly bylo nepoužitelné — a po výpadku
 *   sítě naváže samo, když se připojení vrátí ([fetch]).
 * - **Stahování drží naživu platforma** ([LiteRtPlatform.startDownload]): na Androidu úloha
 *   WorkManageru, která přežije i zabití procesu a naváže na `.part`, na desktopu a iOS korutina
 *   procesu (na iOS se po uspání aplikace naváže při dalším stahování). Tahle třída drží jen stav pro
 *   obrazovku ([downloads]) a stahuje v [runDownload].
 * - **Engine je v paměti nejvýš jeden**, i když je zapnutých víc modelů — přechod na jiný model
 *   ten první zavře. Po chvíli bez použití se uvolní ([idleUnloadMs]).
 *   Drží gigabajty RAM; nechat ho viset po jednom shrnutí by znamenalo, že systém dřív zabije
 *   aplikaci v pozadí. Načtení trvá do deseti sekund (dokumentace LiteRT-LM), proto se neuvolňuje
 *   hned po odpovědi — návrhy odpovědí přicházejí často po sobě. O prodlevě rozhoduje aplikace
 *   ([idleUnloadMs]): model, který dělá jen zvuk a obraz, může uvolnit dřív.
 * - **Generování je jedno po druhém** ([engineLock]). Shrnutí a návrh odpovědi se můžou
 *   potkat a dvě konverzace nad jedním enginem LiteRT-LM výslovně neslibuje.
 * - **GPU, které selže až při generování, nahradí CPU** ([withEngine]): bez OpenCL se GPU engine
 *   načte bez chyby a spadne až u první zprávy. Dotaz se pak na CPU zopakuje a GPU se do konce
 *   procesu nezkouší ([gpuFailed]).
 * - **Počítání tokenů LiteRT-LM v Kotlinu nemá**, [countTokens] proto vrací HORNÍ odhad
 *   ([estimatedTokenCount]) a shrnutí podle něj přepis zkrátí nebo rozdělí. Limit je velikost
 *   KV keše ([MAX_NUM_TOKENS]) minus místo na odpověď ([OUTPUT_RESERVE_TOKENS]) — vstup
 *   a výstup se do ní vejít musí SPOLU.
 * - **Strop výstupu je dvojí**: v tokenech pro knihovnu (`maxOutputToken` = [OUTPUT_RESERVE_TOKENS],
 *   od LiteRT-LM 0.19), ať odpověď nepřeteče keš, a ve znacích pro obrazovku
 *   ([MAX_OUTPUT_CHARS], pak `Generated.truncated`).
 *
 * Kde model nejde (paměť, systém, chybějící nativní knihovna), řekne platforma ([LiteRtPlatform.canRun]).
 * Selhání nese variantu, takže hláška jmenuje model, který selhal.
 */
class LiteRtOnDeviceAi(
    private val platform: LiteRtPlatform,
    /** Runtime pro model podle [DownloadedModel.runtime]; na Androidu každý jiná součást z Play. */
    private val runtimes: (ModelRuntime) -> LiteRtRuntime,
    /** Připojení k síti: stav „čeká na síť" a navázání po výpadku. */
    private val connectivity: Connectivity,
    /**
     * Za jak dlouho po poslední práci engine modelu uvolnit. Čte se po každém použití, ne jednou:
     * zapnutí Nano nebo jiné přiřazení úloh to mění za běhu.
     */
    private val idleUnloadMs: suspend (DownloadedModel) -> Long = { DEFAULT_IDLE_UNLOAD_MS },
    /** Je síť placená? Jen pro stav „čeká na Wi-Fi" a zákaz dat za běhu ([setDownloadNetwork]). */
    private val meteredNetwork: MeteredNetwork = NoMeteredNetwork,
) : OnDeviceAi {

    private val logger = Logger.withTag("OnDeviceAi")

    /** Stahování a úklid enginu přežívají obrazovky; model je jeden na proces. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val modelsDir: Path get() = Path(platform.modelsDir)
    private fun runtimeOf(model: DownloadedModel): LiteRtRuntime = runtimes(model.runtime)
    private fun DownloadedModel.file() = Path(modelsDir, fileName)
    private fun DownloadedModel.partFile() = Path(modelsDir, "$fileName.part")

    private val downloads = ConcurrentMap<DownloadedModel, MutableStateFlow<DownloadState>>()

    private fun stateOf(model: DownloadedModel) = downloads.getOrPut(model) { MutableStateFlow(DownloadState.Idle) }

    /** Doinstalování runtime před stahováním modelu ([download]); [stopDownload] ho zastaví. */
    private val runtimeInstalls = ConcurrentMap<DownloadedModel, Job>()

    /**
     * Smí stahování modelu přes mobilní data ([download], [setDownloadNetwork])? Jen v paměti:
     * úloha přežije proces i s podmínkou sítě, tohle je jen pro stav na obrazovce — po restartu
     * ho obnoví první připojení obrazovky (`download` s volbou z Nastavení).
     */
    private val mobileData = ConcurrentMap<DownloadedModel, Boolean>()

    private fun wifiOnly(model: DownloadedModel) = mobileData[model] == false

    /** Běžící stahování souboru v [runDownload]; zákaz dat za běhu ho zruší. */
    private val fetches = ConcurrentMap<DownloadedModel, Job>()

    /** Stahování zrušené zákazem dat ([setDownloadNetwork]), ne zastavením úlohy. */
    private val wifiRequired = ConcurrentSet<DownloadedModel>()

    /** Runtime k už staženému modelu se doinstaloval sám ([restoreRuntime]); jednou za proces. */
    private val runtimeRestoreStarted = ConcurrentSet<ModelRuntime>()

    private val engineLock = Mutex()
    private var loaded: LoadedEngine? = null
    private var idleUnload: Job? = null

    /**
     * Runtime, kterým tu GPU selhalo (při načtení, nebo při generování) a CPU uspělo. Do konce
     * procesu se jim pak rovnou načítá CPU: zkoušet GPU znovu by každé načtení prodloužilo
     * o načtení, které stejně spadne. Jen v paměti — po aktualizaci systému nebo ovladačů se GPU
     * zkusí znovu. **Pro každý runtime zvlášť**: Metal v llama.cpp a GPU v LiteRT-LM jsou různé
     * backendy, selhání jednoho nic neříká o druhém.
     */
    private val gpuFailed = ConcurrentSet<ModelRuntime>()

    override suspend fun status(variant: ModelVariant): OnDeviceAiStatus {
        val model = variant.model ?: return OnDeviceAiStatus.Unsupported
        return when {
            !platform.canRun(model) -> OnDeviceAiStatus.Unsupported
            model.file().exists() && runtimeOf(model).installed() -> OnDeviceAiStatus.Ready
            // Úloha naplánovaná z minulého běhu procesu (zabitého uprostřed stahování) se
            // rozběhne sama, jen ještě nezapsala stav — pořád je to stahování.
            stateOf(model).value is DownloadState.Running ||
                platform.downloadPending(model) -> OnDeviceAiStatus.Downloading
            model.file().exists() && restoreRuntime(model) -> OnDeviceAiStatus.Downloading
            // Stažený model bez runtime, který se sám doinstalovat nepovedl (aplikace mimo
            // Play, chyba): „Pokračovat ve stahování" doinstaluje jen runtime.
            model.partFile().exists() || model.file().exists() -> OnDeviceAiStatus.Paused
            else -> OnDeviceAiStatus.Downloadable
        }
    }

    /**
     * Model je stažený, ale runtime chybí — aktualizace z verze, která runtime nesla v sobě, nebo
     * součást odinstalovaná. Uživatel si model už jednou vybral a stáhl, takže se runtime (desítka
     * MB) doinstaluje SÁM, jakmile se na model někdo zeptá; ťukat na „Pokračovat ve stahování"
     * kvůli aktualizaci aplikace by nedávalo smysl. Ptá se jen funkce se zapnutou AI, takže kdo
     * AI vypnul, nic nestahuje.
     *
     * Jen jednou za proces: kdyby instalace selhávala (aplikace mimo Play), každý dotaz na stav
     * by ji pouštěl znovu. Pak zůstává „Pokračovat ve stahování". Vrací, jestli se instaluje.
     */
    private fun restoreRuntime(model: DownloadedModel): Boolean {
        if (!runtimeRestoreStarted.add(model.runtime)) return false
        logger.i { "Model ${model.displayName} je stažený bez runtime, doinstalovávám ho" }
        val state = stateOf(model)
        state.value = DownloadState.Running(ModelDownload(0, null))
        runtimeInstalls[model] = scope.launch { installRuntimeThenDownload(model, state) }
        return true
    }

    override suspend fun info(variant: ModelVariant): ModelInfo? {
        val model = variant.model ?: return null
        if (!model.file().exists()) return null
        return ModelInfo(name = model.displayName, tokenLimit = model.maxTokens() - OUTPUT_RESERVE_TOKENS)
    }

    /**
     * Naplánuje stahování a sleduje jeho průběh. Běžící stahování (i úloha čekající na síť)
     * se jen sleduje; `KEEP` při plánování zaručí, že se nespustí podruhé.
     *
     * Chybí-li runtime ([LiteRtRuntime]), doinstaluje se NAPŘED, v [scope], aby přežil odchod
     * z obrazovky. Je to desítka megabajtů proti gigabajtům modelu, a když selže (aplikace není
     * z Google Play), má se to uživatel dozvědět hned, ne až po stažení modelu. Průběh se zatím
     * ukazuje v bajtech runtime, pak v bajtech modelu.
     *
     * Runtime z Play [mobileData] neřídí: je to desítka megabajtů a o síti rozhoduje Play (velké
     * stahování přes data si potvrdí sám, `SplitInstallConfirmations`).
     */
    override fun download(variant: ModelVariant, mobileData: Boolean): Flow<ModelDownload> = flow {
        val model = variant.file()
        val state = stateOf(model)
        if (state.value !is DownloadState.Running) {
            this@LiteRtOnDeviceAi.mobileData[model] = mobileData
            // Než se úloha rozběhne, ukazuje se, co už je stažené — a bez sítě rovnou čekání:
            // úloha se bez připojení (u „jen Wi-Fi" bez neplacené sítě) vůbec nespustí, takže by
            // stav jinak tvrdil, že se stahuje.
            val offline = withTimeoutOrNull(NETWORK_CHECK_MS) {
                !connectivity.online.first()
            } == true
            state.value = DownloadState.Running(
                ModelDownload(
                    model.partFile().size(),
                    model.sizeBytes,
                    waitingForNetwork = offline || (!mobileData && meteredNetwork.isMetered()),
                    wifiOnly = !mobileData,
                ),
            )
            if (runtimeOf(model).installed()) {
                platform.startDownload(model, unmeteredOnly = !mobileData) { runDownload(model) }
            } else {
                runtimeInstalls[model] = scope.launch { installRuntimeThenDownload(model, state) }
            }
        }
        state.transformWhile { current ->
            when (current) {
                is DownloadState.Running -> {
                    emit(current.progress)
                    true
                }
                DownloadState.Done -> false
                is DownloadState.Failed -> throw current.error
                DownloadState.Stopped -> throw DownloadStoppedException()
                DownloadState.Idle -> true
            }
        }.collect { emit(it) }
    }

    /** Doinstaluje runtime a pak naplánuje model, nebo rovnou skončí, když už je stažený. */
    private suspend fun installRuntimeThenDownload(model: DownloadedModel, state: MutableStateFlow<DownloadState>) {
        try {
            runtimeOf(model).install().collect { progress -> state.value = DownloadState.Running(progress) }
            logger.i { "Runtime ${model.runtime} připravený (${model.displayName})" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = if (e is DownloadStoppedException) DownloadState.Stopped else DownloadState.Failed(e)
            return
        } finally {
            runtimeInstalls.remove(model)
        }
        if (model.file().exists()) {
            state.value = DownloadState.Done
        } else {
            state.value = DownloadState.Running(
                ModelDownload(
                    model.partFile().size(),
                    model.sizeBytes,
                    waitingForNetwork = wifiOnly(model) && meteredNetwork.isMetered(),
                    wifiOnly = wifiOnly(model),
                ),
            )
            platform.startDownload(model, unmeteredOnly = wifiOnly(model)) { runDownload(model) }
        }
    }

    override suspend fun stopDownload(variant: ModelVariant) {
        val model = variant.file()
        runtimeInstalls.remove(model)?.cancel()
        platform.cancelDownload(model)
        // Úloha to ohlásí taky ([onWorkerStopped]), jenže až doběhne zrušení; obrazovka má
        // odpovědět hned. Úloha, která ještě nezačala, neohlásí nic.
        stateOf(model).value = DownloadState.Stopped
        logger.i { "Stahování zastavené (${model.displayName})" }
    }

    /**
     * Změna „jen přes Wi-Fi" u stahování, které běží nebo čeká. Úloha dostane novou podmínku sítě
     * bez zrušení ([LiteRtPlatform.setDownloadNetwork]) — čekající na Wi-Fi se po povolení dat
     * rozběhne. Běžící úlohu nová podmínka nezastaví, takže když se data zakážou zrovna na
     * placené síti, zruší se tu stahování souboru a úloha skončí s opakováním
     * ([WifiRequiredException]): pustí ji znovu až Wi-Fi.
     */
    override suspend fun setDownloadNetwork(variant: ModelVariant, mobileData: Boolean) {
        val model = variant.file()
        this.mobileData[model] = mobileData
        withContext(Dispatchers.IO) { platform.setDownloadNetwork(model, unmeteredOnly = !mobileData) }
        val state = stateOf(model)
        val current = state.value
        val stopNow = !mobileData && meteredNetwork.isMetered()
        if (current is DownloadState.Running) {
            state.value = DownloadState.Running(
                current.progress.copy(waitingForNetwork = current.progress.waitingForNetwork || stopNow, wifiOnly = !mobileData),
            )
        }
        if (stopNow) {
            fetches[model]?.let { fetch ->
                wifiRequired += model
                fetch.cancel()
            }
        }
        logger.i { "Stahování ${model.displayName}: ${if (mobileData) "i přes data" else "jen přes Wi-Fi"}" }
    }

    /** Průběh stahování pro notifikaci úlohy. */
    internal fun downloadProgress(model: DownloadedModel): Flow<ModelDownload> =
        stateOf(model).filterIsInstance<DownloadState.Running>().map { it.progress }

    /**
     * Stáhne [model]; volá ho to, co stahování drží naživu ([LiteRtPlatform.startDownload]).
     * Vyhodí, když se stáhnout nepodařilo —
     * stav pro obrazovku je pak už zapsaný. Zrušení (zastavení úlohy) jde dál beze změny
     * stavu; co znamená, ví až úloha podle důvodu zastavení ([onWorkerStopped]).
     */
    internal suspend fun runDownload(model: DownloadedModel) {
        val state = stateOf(model)
        if (model.file().exists()) {
            state.value = DownloadState.Done
            return
        }
        // Vždycky načisto: úloha se spouští jen s připojením, takže čekání na síť ze zastavené
        // úlohy už neplatí — a první hlášení průběhu přijde až po pár megabajtech.
        state.value = DownloadState.Running(ModelDownload(model.partFile().size(), model.sizeBytes, wifiOnly = wifiOnly(model)))
        try {
            // Ve vlastní korutině, aby šla zrušit jen ona ([setDownloadNetwork]); zastavení
            // úlohy zruší celou [runDownload] a projde `join` jako dřív.
            coroutineScope {
                val fetch = launch { fetch(model, state) }
                fetches[model] = fetch
                try {
                    fetch.join()
                } finally {
                    fetches.remove(model, fetch)
                }
                if (wifiRequired.remove(model)) throw WifiRequiredException()
            }
            state.value = DownloadState.Done
            logger.i { "Model stažený (${model.displayName})" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WifiRequiredException) {
            state.value = DownloadState.Running(
                ModelDownload(model.partFile().size(), model.sizeBytes, waitingForNetwork = true, wifiOnly = true),
            )
            logger.i { "Stahování ${model.displayName} počká na Wi-Fi" }
            throw e
        } catch (e: Exception) {
            state.value = DownloadState.Failed(e)
            throw e
        }
    }

    /**
     * Úloha stahování skončila dřív, než model stáhla. Zastavil ji uživatel ([byUser]) →
     * stahování stojí; jinak ji zastavil systém (ztráta sítě, kvóta úloh, časový limit služby
     * v popředí) a WorkManager ji spustí znovu — pro obrazovku je to pořád stahování, jen při
     * [waitingForNetwork] s větou, že se čeká na připojení.
     */
    internal fun onWorkerStopped(model: DownloadedModel, byUser: Boolean, waitingForNetwork: Boolean) {
        val state = stateOf(model)
        val current = state.value
        when {
            byUser -> state.value = DownloadState.Stopped
            current is DownloadState.Running -> state.value = DownloadState.Running(
                current.progress.copy(waitingForNetwork = waitingForNetwork, wifiOnly = wifiOnly(model)),
            )
        }
    }

    /** Horní odhad, ne přesný počet — viz [estimatedTokenCount]. */
    override suspend fun countTokens(variant: ModelVariant, prompt: String): Int? =
        estimatedTokenCount(platform.modelText(prompt))

    override fun generate(variant: ModelVariant, prompt: String): Flow<Generated> = flow {
        withEngine(variant.file()) { engine ->
            val conversation = engine.conversation(SAMPLING)
            var finished = false
            try {
                val text = StringBuilder()
                try {
                    // Každá zpráva streamu je PŘÍRŮSTEK (tak ho vypisuje i ukázka LiteRT-LM),
                    // rozhraní slibuje celý text. Přemýšlení je vypnuté (`LlmConversation.send`):
                    // šlo by do samostatného kanálu a jen by prodloužilo čekání.
                    conversation.send(listOf(LlmContent.Text(platform.modelText(prompt))), maxOutputTokens = OUTPUT_RESERVE_TOKENS)
                        .collect { message ->
                            text.append(message)
                            if (text.length >= MAX_OUTPUT_CHARS) {
                                emit(Generated(text.substring(0, MAX_OUTPUT_CHARS), truncated = true))
                                throw OutputLimitReached
                            }
                            emit(Generated(text.toString()))
                        }
                    finished = true
                } catch (e: OutputLimitReached) {
                    // Useknutí je platný výsledek, ne chyba — `truncated` už odešlo.
                }
            } finally {
                // Zrušené nebo useknuté generování by nativní strana jinak dopočítala do konce.
                if (!finished) quietly { conversation.cancel() }
                quietly { conversation.close() }
            }
        }
    }.flowOn(Dispatchers.Default) // Zakládání konverzace a úklid jsou nativní volání; ne na hlavním vlákně.
        .catch { e ->
            // LiteRT-LM chybové kódy nemá; cokoli z ní je selhání modelu. Zrušení `catch` nechytá.
            throw if (e is Exception && e !is OnDeviceAiException) {
                OnDeviceAiException(AiFailureReason.ModelError, variant, cause = e)
            } else {
                e
            }
        }

    /** Zvuk umí jen model s [DownloadedModel.multimodal]; stačí, aby byl stažený a zařízení ho uneslo. */
    override suspend fun canTranscribe(variant: ModelVariant): Boolean =
        variant.model?.multimodal == true && status(variant) == OnDeviceAiStatus.Ready

    /**
     * Přepis řeči: zvuk po kusech ([LiteRtPlatform.wavClips], WAV 16 kHz mono, nejvýš 30 s — tak ho modelu dává
     * Gallery), každý kus vlastní konverzací, text se skládá za sebe. Engine musí mít zvukový
     * backend (`audioBackend`, CPU — „must be CPU" i v Gallery), proto se načte
     * se zvukem; pak poslouží i textovým funkcím, aby se nepřenačítal.
     */
    override fun transcribe(variant: ModelVariant, audioPath: String, prompt: String): Flow<Generated> = flow {
        val clips = withContext(Dispatchers.IO) { platform.wavClips(audioPath) }
        if (clips.isEmpty()) throw IOException("No audio in $audioPath")
        withEngine(variant.file(), audio = true) { engine ->
            val done = StringBuilder()
            for (clip in clips) {
                val conversation = engine.conversation(SAMPLING)
                var finished = false
                try {
                    val part = StringBuilder()
                    conversation.send(
                        listOf(LlmContent.Audio(clip), LlmContent.Text(platform.modelText(prompt))),
                    ).collect { message ->
                        part.append(message)
                        emit(Generated(joinTranscript(done, part)))
                    }
                    finished = true
                    if (done.isNotEmpty() && part.isNotBlank()) done.append(' ')
                    done.append(part.trim())
                } finally {
                    if (!finished) quietly { conversation.cancel() }
                    quietly { conversation.close() }
                }
            }
            emit(Generated(done.toString()))
        }
    }.flowOn(Dispatchers.Default)
        .catch { e ->
            throw if (e is Exception && e !is OnDeviceAiException) {
                OnDeviceAiException(AiFailureReason.ModelError, variant, cause = e)
            } else {
                e
            }
        }

    /** Obrázky umí jen model s [DownloadedModel.multimodal]; stačí připravený model. */
    override suspend fun canDescribeImage(variant: ModelVariant): Boolean =
        variant.model?.multimodal == true && status(variant) == OnDeviceAiStatus.Ready

    /**
     * Popis fotky: obrázek zmenšený na [MAX_IMAGE_SIDE] ([LiteRtPlatform.png]) a pokyn vedle něj, jedna
     * konverzace. Engine musí mít obrazový backend (`visionBackend`), proto se načte s ním;
     * pak poslouží i textovým funkcím.
     */
    override fun describeImage(variant: ModelVariant, imagePath: String, prompt: String): Flow<Generated> = flow {
        val image = withContext(Dispatchers.IO) { platform.png(imagePath, MAX_IMAGE_SIDE) }
            ?: throw IOException("Image $imagePath cannot be decoded")
        withEngine(variant.file(), vision = true) { engine ->
            val conversation = engine.conversation(SAMPLING)
            var finished = false
            try {
                val text = StringBuilder()
                try {
                    conversation.send(
                        listOf(LlmContent.Image(image), LlmContent.Text(platform.modelText(prompt))),
                        maxOutputTokens = OUTPUT_RESERVE_TOKENS,
                    ).collect { message ->
                        text.append(message)
                        if (text.length >= MAX_OUTPUT_CHARS) {
                            emit(Generated(text.substring(0, MAX_OUTPUT_CHARS), truncated = true))
                            throw OutputLimitReached
                        }
                        emit(Generated(text.toString().trimStart()))
                    }
                    finished = true
                } catch (e: OutputLimitReached) {
                    // Useknutí je platný výsledek, ne chyba — `truncated` už odešlo.
                }
            } finally {
                if (!finished) quietly { conversation.cancel() }
                quietly { conversation.close() }
            }
        }
    }.flowOn(Dispatchers.Default)
        .catch { e ->
            throw if (e is Exception && e !is OnDeviceAiException) {
                OnDeviceAiException(AiFailureReason.ModelError, variant, cause = e)
            } else {
                e
            }
        }

    /** Velikost KV keše: [MAX_NUM_TOKENS], nebo méně, když víc soubor neunese. */
    private fun DownloadedModel.maxTokens(): Int = minOf(MAX_NUM_TOKENS, contextTokens)

    /** Soubor modelu; varianta systémového modelu se sem dostat nemá (směrovač ji pošle jinam). */
    private fun ModelVariant.file(): DownloadedModel =
        model ?: throw OnDeviceAiException(AiFailureReason.Unavailable, this)

    private fun joinTranscript(done: StringBuilder, part: StringBuilder): String =
        if (done.isEmpty()) part.toString().trimStart() else "$done ${part.toString().trimStart()}"

    override suspend fun delete(variant: ModelVariant) {
        val model = variant.file()
        runtimeInstalls.remove(model)?.cancel()
        platform.cancelDownload(model)
        downloads.remove(model)
        engineLock.withLock {
            if (loaded?.model == model) unloadLocked()
        }
        withContext(Dispatchers.IO) {
            model.file().delete()
            model.partFile().delete()
            // Zkompilovaná keš modelu: skoro tak velká jako model a s ním k ničemu. Leží v keši
            // platformy, kterou engine dostává, a ze sond bez `cacheDir` i vedle modelu.
            listOf(Path(platform.cacheDir), modelsDir).forEach { dir ->
                dir.listNames().filter { model.ownsCacheFile(it) }.forEach { Path(dir, it).delete() }
            }
        }
        logger.i { "Model smazán (${model.displayName})" }
    }

    /**
     * Generování nad enginem pro [model]: načtený se použije, jiný se nejdřív zavře (dva by se do
     * paměti nevešly). Po doběhnutí se naplánuje uvolnění — příští použití ho zase zruší.
     *
     * **Selže-li [block] na GPU dřív, než cokoli vydal, zopakuje se jednou na CPU.** Bez OpenCL
     * (emulátor x86_64, nejspíš i některé telefony) se GPU engine NAČTE BEZ CHYBY a spadne až u první
     * zprávy (`Can not find OpenCL library on this device` z `onError` konverzace), takže to
     * [createEngine] nepozná. Po prvním vydaném kusu se neopakuje: obrazovka už text ukazuje
     * a odpověď by začala znovu. Zrušení se neopakuje nikdy.
     */
    private suspend fun FlowCollector<Generated>.withEngine(
        model: DownloadedModel,
        audio: Boolean = false,
        vision: Boolean = false,
        block: suspend FlowCollector<Generated>.(LlmEngine) -> Unit,
    ): Unit = engineLock.withLock {
        idleUnload?.cancel()
        var emitted = false
        val counting = FlowCollector<Generated> { value ->
            emitted = true
            this@withEngine.emit(value)
        }
        try {
            val first = loadLocked(model, audio, vision)
            try {
                counting.block(first.engine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (first.backend != LlmBackend.Gpu || emitted) throw e
                logger.w(e) { "GPU selhalo při generování, zkouším CPU (${model.displayName})" }
                try {
                    counting.block(loadLocked(model, audio, vision, cpuOnly = true).engine)
                    gpuFailed += model.runtime
                } catch (cpuError: Exception) {
                    // CPU už něco vydalo: GPU tu opravdu nejde. Selhalo-li CPU stejně brzy,
                    // chyba nejspíš na backendu nezávisí a GPU se příště zkusí znovu.
                    if (emitted) gpuFailed += model.runtime
                    if (cpuError !is CancellationException) cpuError.addSuppressed(e)
                    throw cpuError
                }
            }
        } finally {
            idleUnload = scope.launch {
                delay(runCatching { idleUnloadMs(model) }.getOrDefault(DEFAULT_IDLE_UNLOAD_MS))
                engineLock.withLock { unloadLocked() }
            }
        }
    }

    /**
     * Engine pro [model]; [cpuOnly] ho vynutí na CPU (načtený na GPU se zavře). Engine se
     * zvukem (obrazem) poslouží i textu; bez něj se pro přepis (popis) musí načíst znovu. Při
     * přenačtení si nechá i to, co uměl dosud, ať se přepis a popis nestřídají přenačítáním.
     */
    private suspend fun loadLocked(model: DownloadedModel, audio: Boolean, vision: Boolean, cpuOnly: Boolean = false): LoadedEngine {
        val current = loaded?.takeIf { it.model == model }
        current?.takeIf { (it.audio || !audio) && (it.vision || !vision) && (!cpuOnly || it.backend == LlmBackend.Cpu) }
            ?.let { return it }
        val withAudio = audio || current?.audio == true
        val withVision = vision || current?.vision == true
        unloadLocked()
        if (!model.file().exists()) throw IOException("Model ${model.displayName} is not downloaded")
        return createEngine(model, withAudio, withVision, cpuOnly).also { loaded = it }
    }

    private fun unloadLocked() {
        loaded?.engine?.let { engine -> quietly { engine.close() } }
        loaded = null
    }

    /**
     * GPU, a když nejde, CPU. GPU backend potřebuje `libOpenCL.so` (manifest aplikace,
     * `uses-native-library`), kterou některá zařízení nemají — na CPU je model pomalejší, ale
     * funguje. Že GPU nejde, se ale často ukáže až při generování ([withEngine]). Po selhání GPU
     * (při načtení i při generování) se do konce procesu rovnou načítá CPU ([gpuFailed]), stejně
     * jako s [cpuOnly]. `LinkageError` se chytá kvůli nativní
     * knihovně, stejně jako u ML Kitu: na zařízení, kde se nenačte, nesmí aplikace spadnout jen
     * proto, že si někdo zapnul volitelnou funkci.
     */
    private suspend fun createEngine(model: DownloadedModel, audio: Boolean, vision: Boolean, cpuOnly: Boolean): LoadedEngine =
        withContext(Dispatchers.Default) {
            try {
                val engines = runtimeOf(model).engines()
                fun start(backend: LlmBackend) = LoadedEngine(
                    model, audio, vision, backend,
                    engines.open(
                        LlmEngineConfig(
                            modelPath = model.file().toString(),
                            // Obraz počítá týž backend jako text (Gallery: GPU, když jde) — na CPU se
                            // spadne spolu s ním.
                            backend = backend,
                            audio = audio,
                            vision = vision,
                            maxNumTokens = model.maxTokens(),
                            // Keš zkompilovaného modelu zrychlí druhé načtení; do keše platformy, ať ji
                            // systém smí uklidit — soubor modelu vedle ní je drahý, keš ne.
                            cacheDir = Path(platform.cacheDir).also { it.mkdirs() }.toString(),
                        ),
                    ),
                )
                if (cpuOnly || model.runtime in gpuFailed || !platform.gpu(model.runtime)) return@withContext start(LlmBackend.Cpu)
                val gpu = try {
                    start(LlmBackend.Gpu)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.i(e) { "GPU backend nejde, zkouším CPU (${model.displayName})" }
                    null
                }
                gpu ?: start(LlmBackend.Cpu).also { gpuFailed += model.runtime }
            } catch (e: Error) {
                // `LinkageError` je Error, ne výjimka: `catch (Exception)` volajících by ho nechytil
                // a aplikace by spadla.
                if (!isLinkageError(e)) throw e
                throw IOException("${model.runtime} runtime failed to load", e)
            }
        }

    /**
     * Stáhne model a po výpadku sítě naváže SÁM, jakmile se připojení vrátí.
     *
     * Gigabajty se stahují minuty, a přejít z Wi-Fi na data nebo projet tunelem je za tu dobu
     * běžné. Dokud výpadek končil hláškou „nepodařilo se stáhnout", musel se uživatel vrátit
     * do Nastavení a ťuknout znovu, přestože se vlastně nic nepokazilo.
     *
     * Opakuje se jen chyba spojení ([IOException]). Odpověď serveru ([HttpStatusException])
     * ani nedostatek místa se čekáním nespraví. Pokusů bez jediného nového bajtu je nejvýš
     * [MAX_RETRIES_WITHOUT_PROGRESS] — server, který spojení pokaždé shodí, jinak nekončí.
     *
     * Selhání se tu NELOGUJE, loguje ho volající ([OnDeviceAiSettingsViewModel]), který ho
     * dostane i od Gemini Nano. Ve dvou místech by v logu bylo dvakrát.
     */
    private suspend fun fetch(model: DownloadedModel, state: MutableStateFlow<DownloadState>) {
        var failures = 0
        while (true) {
            val before = model.partFile().size()
            try {
                fetchFile(model) { progress -> state.value = DownloadState.Running(progress) }
                break
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: IOException) {
                failures = if (model.partFile().size() > before) 1 else failures + 1
                if (failures > MAX_RETRIES_WITHOUT_PROGRESS) throw e
                logger.i { "Stahování přerušené (${e.message}), čekám na síť (${model.displayName})" }
                state.value = DownloadState.Running(
                    ModelDownload(model.partFile().size(), model.sizeBytes, waitingForNetwork = true, wifiOnly = wifiOnly(model)),
                )
                // Nejdřív síť, pak prodleva: po návratu sítě chvíli trvá, než opravdu vede
                // ven, a výpadek, o kterém systém ještě neví, se tím nepřepálí na pokusy.
                connectivity.online.first { it }
                delay(RETRY_DELAY_MS * failures)
            }
        }
    }

    /**
     * Stáhne soubor do `.part` s navázáním na to, co už je staženo, a hotový přejmenuje.
     *
     * Hugging Face přesměrovává na CDN; HTTP klient platformy ([httpDownload]) přesměrování
     * následuje sám a hlavičku `Range` přenese. Server, který rozsah nevezme (200 místo 206), dostane
     * soubor od začátku. **401/403 znamená model za souhlasem s licencí** — to knihovna neumí
     * (chtělo by to přihlášení k Hugging Face) a hlásí se jako obyčejné selhání.
     */
    private suspend fun fetchFile(model: DownloadedModel, onProgress: (ModelDownload) -> Unit) = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val part = model.partFile()
        var offset = part.size()

        val download = httpDownload(model.url, offset, TIMEOUT_MS)
        /*
         * Zrušení (Zastavit, zákaz dat za běhu) zavře spojení. Blokující `read` zrušení korutiny
         * nepozná a na síti, která přestala posílat data, by čekal až do [TIMEOUT_MS]: zákaz dat
         * na emulátoru se tak projevil až po 23 s.
         */
        val closeOnCancel = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) download.close()
        }
        try {
            val code = download.connect()
            if (code == HTTP_RANGE_NOT_SATISFIABLE && part.size() == model.sizeBytes) {
                // `.part` je celý, jen se nestihl přejmenovat (proces zabitý mezi posledním
                // zápisem a přejmenováním). Bez tohohle by každý další pokus skončil 416.
                moveFinished(model)
                onProgress(ModelDownload(model.sizeBytes, model.sizeBytes))
                return@withContext
            }
            if (code == HTTP_OK) offset = 0L
            else if (code != HTTP_PARTIAL) throw HttpStatusException(code, model)

            val remaining = download.contentLength.takeIf { it > 0 } ?: (model.sizeBytes - offset)
            val total = offset + remaining
            // Rezerva nad velikost souboru: plný disk v půli stahování je horší než odmítnutí předem.
            if (usableSpace(modelsDir) < remaining + FREE_SPACE_MARGIN) {
                throw NotEnoughSpaceException(remaining + FREE_SPACE_MARGIN)
            }

            var written = offset
            var reported = -1L
            SystemFileSystem.sink(part, append = offset > 0).buffered().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    ensureActive()
                    val read = download.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    // Hlásí se po megabajtech — každý buffer by zahlcoval UI.
                    if (written - reported >= REPORT_EVERY_BYTES) {
                        reported = written
                        onProgress(ModelDownload(written, total))
                    }
                }
            }
            if (written != total) throw IOException("Incomplete download: $written of $total B")
            moveFinished(model)
            onProgress(ModelDownload(total, total))
        } catch (e: IOException) {
            // Spojení zavřené zrušením není výpadek sítě: nečekat na síť, skončit jako zrušení.
            ensureActive()
            throw e
        } finally {
            closeOnCancel.dispose()
            download.close()
        }
    }

    /** Hotový `.part` přejmenuje na model. Není to chyba sítě, takže ne [IOException]. */
    private fun moveFinished(model: DownloadedModel) {
        try {
            model.partFile().moveTo(model.file())
        } catch (e: IOException) {
            throw IllegalStateException("Cannot move ${model.partFile().name}", e)
        }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.i(e) { "LiteRT-LM úklid selhal" }
        }
    }

    private sealed interface DownloadState {
        data object Idle : DownloadState
        data class Running(val progress: ModelDownload) : DownloadState
        data object Done : DownloadState
        data class Failed(val error: Throwable) : DownloadState

        /** Zastavil uživatel; stažená část zůstává. */
        data object Stopped : DownloadState
    }

    /** Server odpověděl jinak než daty. Čekáním na síť se to nespraví, takže se neopakuje. */
    private class HttpStatusException(code: Int, model: DownloadedModel) :
        IOException("HTTP $code for ${model.displayName}")

    /** Vnitřní signál, že výstup dosáhl stropu; ven se nedostane. */
    private object OutputLimitReached : RuntimeException()

    private companion object {
        /** Shrnutí má držet fakta, ne vymýšlet — nízká teplota, jako u Gemini Nano. */
        val SAMPLING = LlmSampling(topK = 40, topP = 0.95, temperature = 0.2)

        /**
         * Velikost KV keše = vstup a výstup DOHROMADY. Soubor může unést víc, ale keš stojí paměť
         * úměrně délce, a 8 192 je totéž, co dává Gemini Nano — shrnutí se tak chová stejně
         * u systémového i staženého modelu.
         */
        const val MAX_NUM_TOKENS = 8_192

        /** Kolik z keše zůstane na odpověď. Víc než [MAX_OUTPUT_CHARS] v tokenech s rezervou. */
        const val OUTPUT_RESERVE_TOKENS = 1_536

        /** Strop výstupu; zhruba stejně dlouhé shrnutí, jaké unese Gemini Nano (800 tokenů). */
        const val MAX_OUTPUT_CHARS = 4_000

        /** Delší strana obrázku pro model. Kodér si ho zmenší sám; větší jen zdrží dekódování. */
        const val MAX_IMAGE_SIDE = 1_024

        /** Pokusy o navázání, během kterých nepřibyl ani bajt; pak se stahování vzdá. */
        const val MAX_RETRIES_WITHOUT_PROGRESS = 5

        /** Prodleva před dalším pokusem, násobená počtem pokusů bez pokroku. */
        const val RETRY_DELAY_MS = 2_000L

        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** Jak dlouho se při plánování čeká na první hlášení sítě; monitor odpovídá hned. */
        const val NETWORK_CHECK_MS = 1_000L

        /**
         * Výchozí prodleva před uvolněním enginu, když aplikace neřekne jinak ([idleUnloadMs]).
         * Textové úlohy chodí po sobě a načtení trvá i deset vteřin; dvě minuty je pokryjí.
         */
        const val DEFAULT_IDLE_UNLOAD_MS = 120_000L

        const val TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 256 * 1024
        const val REPORT_EVERY_BYTES = 4L * 1024 * 1024
        const val FREE_SPACE_MARGIN = 500L * 1024 * 1024
    }
}

/** Načtený engine: který soubor, jestli má zvukový (obrazový) backend a na čem počítá. */
private class LoadedEngine(
    val model: DownloadedModel,
    val audio: Boolean,
    val vision: Boolean,
    val backend: LlmBackend,
    val engine: LlmEngine,
)

/**
 * Data se zakázala uprostřed stahování na placené síti ([LiteRtOnDeviceAi.setDownloadNetwork]).
 * Úloha s ním skončí s opakováním a WorkManager ji pustí znovu na Wi-Fi; stav už ukazuje čekání.
 */
internal class WifiRequiredException : Exception("Model download waits for an unmetered network")
