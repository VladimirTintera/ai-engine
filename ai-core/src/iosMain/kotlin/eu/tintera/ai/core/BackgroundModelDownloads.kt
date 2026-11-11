package eu.tintera.ai.core

import co.touchlab.kermit.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSPropertyListImmutable
import platform.Foundation.NSPropertyListSerialization
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskStateRunning
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.darwin.NSObject
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * Stahování modelů na pozadí, které aplikace zapojí ve Swiftu: z
 * `application(_:handleEventsForBackgroundURLSession:completionHandler:)` zavolá
 * [handleEventsForBackgroundURLSession]. Bez toho se stahuje taky, jen hotový soubor uložený, zatímco
 * aplikace nebyla spuštěná, se zpracuje až při jejím dalším spuštění a systém aplikaci po
 * dokončení neprobudí (README, „iOS").
 *
 * Sezení jsou dvě: pro všechny sítě ([sessionIdentifier]) a „jen přes Wi-Fi"
 * ([wifiSessionIdentifier], `allowsExpensiveNetworkAccess = false`). Podmínka musí být na sezení,
 * ne na požadavku: úloha navázaná z resume data si nese původní požadavek a ten změnit nejde.
 *
 * Používá ho [IosLiteRtPlatform]; sezení se stejným identifikátorem smí být v procesu jen jedno,
 * proto objekt, ne instance platformy.
 */
object IosModelDownloads {

    /** Identifikátor sezení na pozadí pro všechny sítě: id balíčku aplikace + `.ai-models`. */
    val sessionIdentifier: String = "${NSBundle.mainBundle.bundleIdentifier ?: "eu.tintera.ai"}.ai-models"

    /** Identifikátor sezení „jen přes Wi-Fi": [sessionIdentifier] + `.wifi`. */
    val wifiSessionIdentifier: String = "$sessionIdentifier.wifi"

    internal val downloads: BackgroundModelDownloads by lazy {
        BackgroundModelDownloads(
            configuration = { unmeteredOnly ->
                val identifier = if (unmeteredOnly) wifiSessionIdentifier else sessionIdentifier
                NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(identifier).apply {
                    // Stahování si vyžádal uživatel: ne „až se to systému hodí" (discretionary), a po
                    // dokončení se aplikace má probudit, aby soubor přesunula a stav ukázal hotovo.
                    discretionary = false
                    sessionSendsLaunchEvents = true
                    requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
                    URLCache = null
                    // Mobilní data a hotspot; na nich úloha počká, systém ji pustí na Wi-Fi sám.
                    allowsExpensiveNetworkAccess = !unmeteredOnly
                }
            },
            modelsDir = { iosModelsDir },
            metered = IosNetworkPath::expensive,
        )
    }

    /**
     * Předá události sezení na pozadí: systém aplikaci spustil (nebo probudil), protože stahování
     * modelu skončilo. Vrací `false`, když [identifier] není sezení knihovny — pak má aplikace
     * [completionHandler] vyřídit sama (nebo předat svým sezením). Jinak ho knihovna zavolá na
     * hlavním vlákně, až soubor zpracuje.
     */
    fun handleEventsForBackgroundURLSession(identifier: String, completionHandler: () -> Unit): Boolean {
        val unmeteredOnly = when (identifier) {
            sessionIdentifier -> false
            wifiSessionIdentifier -> true
            else -> return false
        }
        downloads.handleEvents(unmeteredOnly, completionHandler)
        return true
    }
}

/**
 * Přenos souboru modelu sezením `NSURLSession` ([ModelFileDownloader]), na iOS sezením na pozadí:
 * stahuje systémový proces (`nsurlsessiond`), takže stahování jde dál, i když aplikaci systém uspí
 * nebo ukončí. Uživatel, který aplikaci zavře přepínačem aplikací, ho zruší — systém pak předá
 * resume data a stahování po dalším spuštění naváže.
 *
 * - **Rozpracovaný kus drží systém, ne `.part`.** Při přerušení (zastavení, výpadek, ukončení
 *   aplikace) dá resume data a ta leží vedle modelu jako `<soubor>.resume`; další úloha z nich
 *   naváže. Resume data jdou použít jednou, proto se po založení úlohy mažou. Kolik je staženo,
 *   říká jejich `NSURLSessionResumeBytesReceived` ([partialBytes]).
 * - **Hotový soubor se přesune hned v delegátovi** (`didFinishDownloadingToURL`): systém dočasný
 *   soubor po návratu smaže a aplikace v té chvíli nemusí mít žádnou korutinu, která by čekala
 *   (spustil ji systém jen kvůli událostem sezení). `Range` se neposílá — navázání řídí resume
 *   data, a vlastní rozsah by se s nimi potkal špatně; starý `.part` dotáhne společné stahování.
 * - **Úloha z minulého běhu procesu se jen připojí** ([download]): sezení se stejným
 *   identifikátorem po spuštění vrátí běžící úlohy (`getAllTasks`), poznají se podle
 *   `taskDescription` = jméno souboru.
 * - **„Jen přes Wi-Fi" je druhé sezení** ([configuration] s `true`). Změna volby u běžící úlohy
 *   ([setNetwork], i připojení s jinou volbou v [download]) ji zastaví s resume daty a naváže
 *   ve druhém sezení; kdo na úlohu čeká, čeká dál na tu novou ([SWITCHING]).
 * - **Všechen stav jen na frontě sezení** ([queue], sériová); na ní volá i delegát obou sezení.
 */
@OptIn(ExperimentalForeignApi::class)
internal class BackgroundModelDownloads(
    /** Konfigurace sezení; `true` = jen neplacená síť. */
    private val configuration: (unmeteredOnly: Boolean) -> NSURLSessionConfiguration,
    private val modelsDir: () -> String,
    /** Je síť teď placená? Jen pro stav „čeká na Wi-Fi" na začátku stahování. */
    private val metered: () -> Boolean = { false },
) : ModelFileDownloader {

    private val logger = Logger.withTag("OnDeviceAi")

    private val queue = NSOperationQueue().apply {
        maxConcurrentOperationCount = 1
        name = "eu.tintera.ai.model-downloads"
    }

    private val delegate = Delegate(this)

    private val anyNetworkSession = lazy {
        NSURLSession.sessionWithConfiguration(configuration(false), delegate = delegate, delegateQueue = queue)
    }

    private val wifiSession = lazy {
        NSURLSession.sessionWithConfiguration(configuration(true), delegate = delegate, delegateQueue = queue)
    }

    private fun session(unmeteredOnly: Boolean): NSURLSession =
        if (unmeteredOnly) wifiSession.value else anyNetworkSession.value

    // --- Jen na [queue]. ---

    /** Běžící úloha podle jména souboru. */
    private val tasks = mutableMapOf<String, Live>()

    /** Kdo čeká na konec úlohy ([download]). */
    private val waiters = mutableMapOf<String, MutableList<Waiter>>()

    /** Výsledek `didFinishDownloadingToURL` do `didCompleteWithError` téže úlohy. */
    private val finished = mutableMapOf<ULong, Result<Unit>>()

    /** Zastavené úlohy, jejichž resume data ještě nedorazila; nová úloha na ně počká. */
    private val pausing = mutableMapOf<String, Pause>()

    /** Smazané modely: resume data, která dorazí pozdě, se už neuloží. */
    private val discarded = mutableSetOf<String>()

    // ---

    /** Čeká systém na dokončení událostí sezení (podle [configuration])? */
    private val eventsDone = ConcurrentMap<Boolean, () -> Unit>()

    /**
     * Úlohy z minulého běhu procesu jsou načtené a jejich události doručené. Úlohu, kterou zrušil
     * uživatel zavřením aplikace, vrátí `getAllTasks` jako dokončenou a resume data k ní přijdou
     * až v `didCompleteWithError` hned potom. Kdo by se rozhodoval dřív, neviděl by je a stáhl by
     * model znovu od nuly — proto se na ty události čeká ([awaitingEvents]), nejvýš [RESTORE_TIMEOUT_NS].
     */
    private val restored = CompletableDeferred<Unit>()

    /** Dokončené úlohy z `getAllTasks`, jejichž `didCompleteWithError` ještě nepřišlo. Jen na [queue]. */
    private val awaitingEvents = mutableSetOf<ULong>()

    /** Úlohy, jejichž `didCompleteWithError` přišlo dřív, než se načetly všechny úlohy. Jen na [queue]. */
    private val completedBeforeRestore = mutableSetOf<ULong>()

    /** Sezení, jejichž úlohy se ještě nenačetly. Jen na [queue]. */
    private var sessionsToRestore = 2

    private val restoreStarted: Unit by lazy {
        listOf(false, true).forEach { unmeteredOnly ->
            session(unmeteredOnly).getAllTasksWithCompletionHandler { all ->
                queue.addOperationWithBlock { restoreTasks(unmeteredOnly, all.orEmpty()) }
            }
        }
    }

    private fun restoreTasks(unmeteredOnly: Boolean, all: List<*>) {
        all.filterIsInstance<NSURLSessionDownloadTask>().forEach { task ->
            val name = task.taskDescription ?: return@forEach
            when (task.state) {
                NSURLSessionTaskStateRunning, NSURLSessionTaskStateSuspended -> {
                    logger.i { "Stahování $name běží z minulého spuštění${if (unmeteredOnly) " (jen Wi-Fi)" else ""}" }
                    tasks.getOrPut(name) { Live(task, unmeteredOnly) }
                }
                else -> if (task.taskIdentifier !in completedBeforeRestore) awaitingEvents += task.taskIdentifier
            }
        }
        if (--sessionsToRestore > 0) return
        completedBeforeRestore.clear()
        if (awaitingEvents.isEmpty()) {
            restored.complete(Unit)
        } else {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, RESTORE_TIMEOUT_NS), dispatch_get_main_queue()) {
                queue.addOperationWithBlock {
                    if (!restored.isCompleted) logger.w { "Události úloh z minulého spuštění nepřišly: $awaitingEvents" }
                    restored.complete(Unit)
                }
            }
        }
    }

    private suspend fun awaitRestored() {
        restoreStarted
        restored.await()
    }

    override suspend fun download(model: DownloadedModel, unmeteredOnly: Boolean, onProgress: (ModelDownload) -> Unit) {
        awaitRestored()
        val name = model.fileName
        onQueue { pausing[name]?.takeIf { it.then == null } }?.done?.await()
        // Než přijde první průběh: co je stažené, a jestli se na placené síti čeká na Wi-Fi.
        onProgress(
            ModelDownload(
                partialBytes(model) ?: 0L,
                model.sizeBytes,
                waitingForNetwork = unmeteredOnly && metered(),
                wifiOnly = unmeteredOnly,
            ),
        )
        val waiter = Waiter(model, onProgress, CompletableDeferred())
        if (!onQueue { attachOrStart(model, unmeteredOnly, waiter) }) return
        try {
            waiter.done.await()
        } catch (e: CancellationException) {
            queue.addOperationWithBlock {
                waiters[name]?.remove(waiter)
                if (waiter.taskId == SWITCHING) {
                    // Přepnutí sítě ještě nedoběhlo: novou úlohu už nezakládat, nečeká-li na ni nikdo.
                    if (waiters[name].orEmpty().none { it.taskId == SWITCHING }) pausing[name]?.then = null
                } else if (tasks[name]?.task?.taskIdentifier == waiter.taskId) {
                    pauseOnQueue(name)
                }
            }
            throw e
        }
    }

    /**
     * Připojí se k běžící úloze, nebo založí novou (z resume data, když jsou); `false` = soubor už
     * je. Běžící úloha s jinou podmínkou sítě se přepne ([switchOnQueue]).
     */
    private fun attachOrStart(model: DownloadedModel, unmeteredOnly: Boolean, waiter: Waiter): Boolean {
        val name = model.fileName
        if (Path(file(name)).exists()) return false
        discarded -= name
        waiters.getOrPut(name) { mutableListOf() } += waiter
        val pending = pausing[name]
        when {
            // Přepnutí už běží; nová úloha převezme i tohohle.
            pending?.then != null -> {
                waiter.taskId = SWITCHING
                pending.then = { startOnQueue(model, unmeteredOnly) }
            }
            tasks[name]?.let { it.unmeteredOnly != unmeteredOnly } == true -> {
                waiter.taskId = SWITCHING
                switchOnQueue(model, unmeteredOnly)
            }
            else -> {
                val live = tasks[name] ?: newTask(model, unmeteredOnly).also { tasks[name] = it }
                waiter.taskId = live.task.taskIdentifier
                live.task.resume()
            }
        }
        return true
    }

    private fun newTask(model: DownloadedModel, unmeteredOnly: Boolean): Live {
        val session = session(unmeteredOnly)
        val resume = resumeFile(model.fileName)
        val data = NSData.dataWithContentsOfFile(resume)
        val task = if (data != null) {
            logger.i { "Stahování ${model.displayName} navazuje (${resumeBytes(data) ?: "?"} B)" }
            session.downloadTaskWithResumeData(data)
        } else {
            val url = NSURL.URLWithString(model.url) ?: throw IOException("Invalid URL ${model.url}")
            session.downloadTaskWithRequest(
                NSMutableURLRequest.requestWithURL(url).apply { setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData) },
            )
        }
        // Resume data jdou použít jen jednou; nová dá systém při dalším přerušení.
        deleteFile(resume)
        task.taskDescription = model.fileName
        return Live(task, unmeteredOnly)
    }

    /**
     * Zastaví úlohu tak, aby z ní zbyla resume data; nová úloha na ně počká ([pausing]). S [then]
     * se po jejich uložení rovnou spustí další (přepnutí sítě).
     */
    private fun pauseOnQueue(name: String, then: (() -> Unit)? = null) {
        val live = tasks.remove(name) ?: return
        val pause = Pause(CompletableDeferred(), then)
        pausing[name] = pause
        live.task.cancelByProducingResumeData { data ->
            queue.addOperationWithBlock {
                if (data != null) saveResume(name, data)
                if (pausing[name] === pause) pausing.remove(name)
                pause.done.complete(Unit)
                pause.then?.invoke()
            }
        }
    }

    /**
     * Běžící úlohu [model] přenese do sezení s podmínkou [unmeteredOnly]: zastaví ji s resume daty
     * a z nich založí novou. Čekající přejdou na novou úlohu; dokončení té staré (zrušení) je mine.
     */
    private fun switchOnQueue(model: DownloadedModel, unmeteredOnly: Boolean) {
        val name = model.fileName
        val old = tasks[name] ?: return
        waiters[name]?.filter { it.taskId == old.task.taskIdentifier }?.forEach { it.taskId = SWITCHING }
        logger.i { "Stahování ${model.displayName} přechází na ${if (unmeteredOnly) "jen Wi-Fi" else "všechny sítě"}" }
        pauseOnQueue(name) { startOnQueue(model, unmeteredOnly) }
    }

    /** Dokončení přepnutí: nová úloha, na kterou přejdou čekající ze [SWITCHING]. */
    private fun startOnQueue(model: DownloadedModel, unmeteredOnly: Boolean) {
        val name = model.fileName
        val switching = waiters[name].orEmpty().filter { it.taskId == SWITCHING }
        if (name in discarded || Path(file(name)).exists()) return
        try {
            val live = tasks[name] ?: newTask(model, unmeteredOnly).also { tasks[name] = it }
            switching.forEach { it.taskId = live.task.taskIdentifier }
            live.task.resume()
        } catch (e: Exception) {
            waiters[name]?.removeAll(switching)
            switching.forEach { it.done.completeExceptionally(e) }
        }
    }

    /**
     * Změna „jen přes Wi-Fi" u běžící (nebo na síť čekající) úlohy, i bez čekající korutiny
     * (úloha z minulého spuštění). Nic neběží-li, nic nespustí.
     */
    suspend fun setNetwork(model: DownloadedModel, unmeteredOnly: Boolean) {
        awaitRestored()
        onQueue {
            val pending = pausing[model.fileName]
            when {
                pending?.then != null -> pending.then = { startOnQueue(model, unmeteredOnly) }
                tasks[model.fileName]?.let { it.unmeteredOnly != unmeteredOnly } == true -> switchOnQueue(model, unmeteredOnly)
            }
        }
    }

    /**
     * Zastaví stahování [model] i bez čekající korutiny (úloha z minulého spuštění) a počká, až
     * systém vydá resume data a ta se uloží ([partialBytes]) — `cancelByProducingResumeData` je
     * vydá až za chvíli a `status` by mezitím neviděl nic staženého. Nejvýš [PAUSE_TIMEOUT].
     */
    override suspend fun pause(model: DownloadedModel) {
        awaitRestored()
        val pause = onQueue {
            pausing[model.fileName]?.then = null
            pauseOnQueue(model.fileName)
            pausing[model.fileName]
        } ?: return
        if (withTimeoutOrNull(PAUSE_TIMEOUT) { pause.done.await() } == null) {
            logger.w { "Resume data pro ${model.displayName} nepřišla do $PAUSE_TIMEOUT" }
        }
    }

    /** Běží (nebo čeká na síť) úloha [model]? I z minulého běhu procesu. */
    suspend fun running(model: DownloadedModel): Boolean {
        awaitRestored()
        return onQueue { model.fileName in tasks || pausing[model.fileName]?.then != null }
    }

    /** Stahuje se [model] jen přes Wi-Fi? `null` = neběží. Pro testy. */
    internal suspend fun runningUnmeteredOnly(model: DownloadedModel): Boolean? {
        awaitRestored()
        return onQueue { tasks[model.fileName]?.unmeteredOnly }
    }

    override fun partialBytes(model: DownloadedModel): Long? {
        val data = NSData.dataWithContentsOfFile(resumeFile(model.fileName)) ?: return null
        return resumeBytes(data) ?: 0L
    }

    override suspend fun discard(model: DownloadedModel) {
        awaitRestored()
        val name = model.fileName
        onQueue {
            discarded += name
            tasks.remove(name)?.task?.cancel()
            pausing.remove(name)?.let { pause ->
                pause.then = null
                pause.done.complete(Unit)
            }
            deleteFile(resumeFile(name))
        }
    }

    /** Viz [IosModelDownloads.handleEventsForBackgroundURLSession]. */
    fun handleEvents(unmeteredOnly: Boolean, completionHandler: () -> Unit) {
        eventsDone[unmeteredOnly] = completionHandler
        // Sezení se stejným identifikátorem musí existovat, jinak události nepřijdou.
        session(unmeteredOnly)
    }

    /** Jen pro testy s obyčejnými sezeními: sezení na pozadí žijí s procesem. */
    internal fun invalidate() {
        listOf(anyNetworkSession, wifiSession).filter { it.isInitialized() }.forEach { it.value.invalidateAndCancel() }
    }

    // --- Delegát, vždycky na [queue]. ---

    private fun onProgress(task: NSURLSessionDownloadTask, written: Long, expected: Long) {
        val name = task.taskDescription ?: return
        waiters[name]?.filter { it.taskId == task.taskIdentifier }?.forEach { waiter ->
            // Hlásí se po megabajtech jako ve společném stahování — každé volání by zahlcovalo UI.
            if (written - waiter.reported >= REPORT_EVERY_BYTES) {
                waiter.reported = written
                waiter.onProgress(ModelDownload(written, expected.takeIf { it > 0 } ?: waiter.model.sizeBytes))
            }
        }
    }

    private fun onFinished(task: NSURLSessionDownloadTask, location: NSURL) {
        val name = task.taskDescription ?: return
        finished[task.taskIdentifier] = runCatching { moveFinished(name, location, task.response) }
            .onFailure { logger.i(it) { "Stažený soubor $name nejde použít" } }
    }

    /**
     * Ověří odpověď a dočasný soubor od systému přesune na místo modelu. Musí stihnout dřív, než
     * se delegát vrátí — pak systém soubor smaže.
     */
    private fun moveFinished(name: String, location: NSURL, response: NSURLResponse?) {
        val http = response as? NSHTTPURLResponse ?: throw IOException("Not an HTTP response")
        val code = http.statusCode.toInt()
        // 206 = úloha navázaná z resume data; dočasný soubor je i tak celý.
        if (code != HTTP_OK && code != HTTP_PARTIAL) throw HttpStatusException(code, name)
        val source = location.path ?: throw IOException("No file for $name")
        val size = Path(source).size()
        val expected = http.valueForHTTPHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?: http.expectedContentLength.takeIf { code == HTTP_OK && it > 0 }
        if (expected != null && size != expected) throw IOException("Incomplete download: $size of $expected B")
        val target = file(name)
        deleteFile(target)
        moveFile(source, target)
        // Pozdě uložená resume data ze zastavení, které už nic neznamená.
        deleteFile(resumeFile(name))
        logger.i { "Model $name stažený na pozadí ($size B)" }
    }

    private fun onCompleted(task: NSURLSessionTask, error: NSError?) {
        val name = task.taskDescription ?: return
        if (tasks[name]?.task == task) tasks.remove(name)
        val outcome: Throwable? = if (error != null) {
            val resume = error.userInfo[NSURLSessionDownloadTaskResumeData] as? NSData
            if (resume != null) saveResume(name, resume)
            // Výpadek sítě, časový limit i zrušení jsou pro společný kód IOException; zrušení od
            // výpadku rozliší sám (korutina je zrušená).
            IOException("${error.domain} ${error.code}: ${error.localizedDescription}")
        } else {
            finished[task.taskIdentifier]?.exceptionOrNull()
                ?: if (task.taskIdentifier in finished) null else IOException("Download of $name finished without a file")
        }
        finished.remove(task.taskIdentifier)
        if (outcome != null) logger.i { "Stahování $name přerušené: ${outcome.message}" }
        waiters[name]?.let { list ->
            val done = list.filter { it.taskId == task.taskIdentifier }
            list.removeAll(done)
            done.forEach { waiter -> if (outcome == null) waiter.done.complete(Unit) else waiter.done.completeExceptionally(outcome) }
        }
        if (!restored.isCompleted) {
            completedBeforeRestore += task.taskIdentifier
            if (awaitingEvents.remove(task.taskIdentifier) && awaitingEvents.isEmpty()) restored.complete(Unit)
        }
    }

    private fun onEventsFinished(session: NSURLSession) {
        val unmeteredOnly = wifiSession.isInitialized() && session == wifiSession.value
        val done = eventsDone.remove(unmeteredOnly) ?: return
        dispatch_async(dispatch_get_main_queue()) { done() }
    }

    // --- Soubory. ---

    private fun file(name: String) = "${modelsDir()}/$name"

    private fun resumeFile(name: String) = "${modelsDir()}/$name.resume"

    private fun saveResume(name: String, data: NSData) {
        if (name in discarded) return
        NSFileManager.defaultManager.createDirectoryAtPath(modelsDir(), withIntermediateDirectories = true, attributes = null, error = null)
        if (!data.writeToFile(resumeFile(name), atomically = true)) logger.w { "Resume data pro $name nejde uložit" }
    }

    private fun deleteFile(path: String) {
        Path(path).delete()
    }

    /** Přejmenování, ne IOException: opakováním po výpadku sítě se nespraví. */
    private fun moveFile(source: String, target: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(modelsDir(), withIntermediateDirectories = true, attributes = null, error = null)
        if (!NSFileManager.defaultManager.moveItemAtPath(source, toPath = target, error = null)) {
            throw IllegalStateException("Cannot move downloaded file to $target")
        }
    }

    /**
     * Kolik bajtů resume data drží (`NSURLSessionResumeBytesReceived`); jen pro obrazovku. Od iOS 12
     * jsou to slovník zabalený `NSKeyedArchiver`em (bez secure coding, ten by chtěl vyjmenovat
     * i třídy požadavků uvnitř — data jsou od systému, ne zvenku), dřív obyčejný plist.
     */
    private fun resumeBytes(data: NSData): Long? {
        val archived = runCatching {
            NSKeyedUnarchiver(forReadingFromData = data, error = null).run {
                requiresSecureCoding = false
                decodeObjectForKey(RESUME_ROOT_KEY).also { finishDecoding() }
            }
        }.getOrNull()
        val dictionary = archived
            ?: NSPropertyListSerialization.propertyListWithData(data, options = NSPropertyListImmutable, format = null, error = null)
        return when (val bytes = (dictionary as? Map<*, *>)?.get(RESUME_BYTES_KEY)) {
            is NSNumber -> bytes.longLongValue
            is Number -> bytes.toLong()
            else -> null
        }
    }

    private suspend fun <T> onQueue(block: () -> T): T = suspendCoroutine { continuation ->
        queue.addOperationWithBlock {
            try {
                continuation.resume(block())
            } catch (e: Throwable) {
                continuation.resumeWithException(e)
            }
        }
    }

    /** Běžící úloha a sezení, ve kterém běží. */
    private class Live(val task: NSURLSessionDownloadTask, val unmeteredOnly: Boolean)

    /** Zastavení, na jehož resume data se čeká; [then] = co spustit po nich (přepnutí sítě). */
    private class Pause(val done: CompletableDeferred<Unit>, var then: (() -> Unit)?)

    private class Waiter(
        val model: DownloadedModel,
        val onProgress: (ModelDownload) -> Unit,
        val done: CompletableDeferred<Unit>,
    ) {
        var taskId: ULong = 0u
        var reported = -1L
    }

    private class Delegate(private val owner: BackgroundModelDownloads) : NSObject(), NSURLSessionDownloadDelegateProtocol {

        override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didFinishDownloadingToURL: NSURL) {
            owner.onFinished(downloadTask, didFinishDownloadingToURL)
        }

        override fun URLSession(
            session: NSURLSession,
            downloadTask: NSURLSessionDownloadTask,
            didWriteData: Long,
            totalBytesWritten: Long,
            totalBytesExpectedToWrite: Long,
        ) {
            owner.onProgress(downloadTask, totalBytesWritten, totalBytesExpectedToWrite)
        }

        override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didResumeAtOffset: Long, expectedTotalBytes: Long) {
            owner.onProgress(downloadTask, didResumeAtOffset, expectedTotalBytes)
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            owner.onCompleted(task, didCompleteWithError)
        }

        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
            owner.onEventsFinished(session)
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val REPORT_EVERY_BYTES = 4L * 1024 * 1024

        /** Jak dlouho se po spuštění čeká na události úloh z minulého běhu procesu. */
        const val RESTORE_TIMEOUT_NS = 3_000_000_000L

        /** Jak dlouho [pause] čeká na resume data od systému. */
        val PAUSE_TIMEOUT = 10.seconds

        /** [Waiter.taskId] během přepnutí sítě: stará úloha už ne, nová ještě není. */
        const val SWITCHING: ULong = 0u
        const val RESUME_BYTES_KEY = "NSURLSessionResumeBytesReceived"

        /** Kořen archivu resume data — doslova tohle jméno, ne hodnota `NSKeyedArchiveRootObjectKey` („root"). */
        const val RESUME_ROOT_KEY = "NSKeyedArchiveRootObjectKey"
    }
}
