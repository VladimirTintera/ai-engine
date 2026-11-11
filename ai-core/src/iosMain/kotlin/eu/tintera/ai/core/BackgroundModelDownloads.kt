package eu.tintera.ai.core

import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * Používá ho [IosLiteRtPlatform]; sezení je jedno na proces, proto objekt, ne instance platformy.
 */
object IosModelDownloads {

    /** Identifikátor sezení na pozadí: id balíčku aplikace + `.ai-models`. */
    val sessionIdentifier: String = "${NSBundle.mainBundle.bundleIdentifier ?: "eu.tintera.ai"}.ai-models"

    internal val downloads: BackgroundModelDownloads by lazy {
        BackgroundModelDownloads(
            configuration = {
                NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(sessionIdentifier).apply {
                    // Stahování si vyžádal uživatel: ne „až se to systému hodí" (discretionary), a po
                    // dokončení se aplikace má probudit, aby soubor přesunula a stav ukázal hotovo.
                    discretionary = false
                    sessionSendsLaunchEvents = true
                    requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
                    URLCache = null
                }
            },
            modelsDir = { iosModelsDir },
        )
    }

    /**
     * Předá události sezení na pozadí: systém aplikaci spustil (nebo probudil), protože stahování
     * modelu skončilo. Vrací `false`, když [identifier] není sezení knihovny — pak má aplikace
     * [completionHandler] vyřídit sama (nebo předat svým sezením). Jinak ho knihovna zavolá na
     * hlavním vlákně, až soubor zpracuje.
     */
    fun handleEventsForBackgroundURLSession(identifier: String, completionHandler: () -> Unit): Boolean {
        if (identifier != sessionIdentifier) return false
        downloads.handleEvents(completionHandler)
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
 * - **Všechen stav jen na frontě sezení** ([queue], sériová); na ní volá i delegát.
 */
@OptIn(ExperimentalForeignApi::class)
internal class BackgroundModelDownloads(
    private val configuration: () -> NSURLSessionConfiguration,
    private val modelsDir: () -> String,
) : ModelFileDownloader {

    private val logger = Logger.withTag("OnDeviceAi")

    private val queue = NSOperationQueue().apply {
        maxConcurrentOperationCount = 1
        name = "eu.tintera.ai.model-downloads"
    }

    private val session: NSURLSession by lazy {
        NSURLSession.sessionWithConfiguration(configuration(), delegate = Delegate(this), delegateQueue = queue)
    }

    // --- Jen na [queue]. ---

    /** Běžící úloha podle jména souboru. */
    private val tasks = mutableMapOf<String, NSURLSessionDownloadTask>()

    /** Kdo čeká na konec úlohy ([download]). */
    private val waiters = mutableMapOf<String, MutableList<Waiter>>()

    /** Výsledek `didFinishDownloadingToURL` do `didCompleteWithError` téže úlohy. */
    private val finished = mutableMapOf<ULong, Result<Unit>>()

    /** Zastavené úlohy, jejichž resume data ještě nedorazila; nová úloha na ně počká. */
    private val pausing = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Smazané modely: resume data, která dorazí pozdě, se už neuloží. */
    private val discarded = mutableSetOf<String>()

    // ---

    private val eventsDone = AtomicReference<(() -> Unit)?>(null)

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

    private val restoreStarted: Unit by lazy {
        session.getAllTasksWithCompletionHandler { all ->
            queue.addOperationWithBlock {
                all.orEmpty().filterIsInstance<NSURLSessionDownloadTask>().forEach { task ->
                    val name = task.taskDescription ?: return@forEach
                    when (task.state) {
                        NSURLSessionTaskStateRunning, NSURLSessionTaskStateSuspended -> {
                            logger.i { "Stahování $name běží z minulého spuštění" }
                            tasks.getOrPut(name) { task }
                        }
                        else -> if (task.taskIdentifier !in completedBeforeRestore) awaitingEvents += task.taskIdentifier
                    }
                }
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
        }
    }

    private suspend fun awaitRestored() {
        restoreStarted
        restored.await()
    }

    override suspend fun download(model: DownloadedModel, onProgress: (ModelDownload) -> Unit) {
        awaitRestored()
        val name = model.fileName
        onQueue { pausing[name] }?.await()
        val done = CompletableDeferred<Unit>()
        val task = onQueue { attachOrStart(model, Waiter(model, onProgress, done)) } ?: return
        try {
            done.await()
        } catch (e: CancellationException) {
            queue.addOperationWithBlock {
                waiters[name]?.removeAll { it.done === done }
                pauseOnQueue(name, task)
            }
            throw e
        }
    }

    /** Připojí se k běžící úloze, nebo založí novou (z resume data, když jsou); `null` = soubor už je. */
    private fun attachOrStart(model: DownloadedModel, waiter: Waiter): NSURLSessionDownloadTask? {
        val name = model.fileName
        if (Path(file(name)).exists()) return null
        discarded -= name
        val task = tasks[name] ?: newTask(model).also { tasks[name] = it }
        waiter.taskId = task.taskIdentifier
        waiters.getOrPut(name) { mutableListOf() } += waiter
        task.resume()
        return task
    }

    private fun newTask(model: DownloadedModel): NSURLSessionDownloadTask {
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
        return task
    }

    /** Zastaví úlohu tak, aby z ní zbyla resume data; nová úloha na ně počká ([pausing]). */
    private fun pauseOnQueue(name: String, task: NSURLSessionDownloadTask? = tasks[name]) {
        if (task == null || tasks[name] != task) return
        tasks.remove(name)
        val paused = CompletableDeferred<Unit>()
        pausing[name] = paused
        task.cancelByProducingResumeData { data ->
            queue.addOperationWithBlock {
                if (data != null) saveResume(name, data)
                if (pausing[name] === paused) pausing.remove(name)
                paused.complete(Unit)
            }
        }
    }

    /** Zastaví stahování [model] i bez čekající korutiny (úloha z minulého spuštění). */
    suspend fun pause(model: DownloadedModel) {
        awaitRestored()
        onQueue { pauseOnQueue(model.fileName) }
    }

    /** Běží (nebo čeká na síť) úloha [model]? I z minulého běhu procesu. */
    suspend fun running(model: DownloadedModel): Boolean {
        awaitRestored()
        return onQueue { model.fileName in tasks }
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
            tasks.remove(name)?.cancel()
            pausing.remove(name)?.complete(Unit)
            deleteFile(resumeFile(name))
        }
    }

    /** Viz [IosModelDownloads.handleEventsForBackgroundURLSession]. */
    fun handleEvents(completionHandler: () -> Unit) {
        eventsDone.store(completionHandler)
        // Sezení se stejným identifikátorem musí existovat, jinak události nepřijdou.
        session
    }

    /** Jen pro testy s obyčejným sezením: sezení na pozadí žije s procesem. */
    internal fun invalidate() {
        session.invalidateAndCancel()
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
        if (tasks[name] == task) tasks.remove(name)
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

    private fun onEventsFinished() {
        val done = eventsDone.exchange(null) ?: return
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
            owner.onEventsFinished()
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val REPORT_EVERY_BYTES = 4L * 1024 * 1024

        /** Jak dlouho se po spuštění čeká na události úloh z minulého běhu procesu. */
        const val RESTORE_TIMEOUT_NS = 3_000_000_000L
        const val RESUME_BYTES_KEY = "NSURLSessionResumeBytesReceived"

        /** Kořen archivu resume data — doslova tohle jméno, ne hodnota `NSKeyedArchiveRootObjectKey` („root"). */
        const val RESUME_ROOT_KEY = "NSKeyedArchiveRootObjectKey"
    }
}
