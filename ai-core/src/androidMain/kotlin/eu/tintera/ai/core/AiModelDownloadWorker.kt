package eu.tintera.ai.core

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Stáhne model jako úloha WorkManageru se službou v popředí a notifikací průběhu.
 *
 * **Abstraktní: podtřídu dává aplikace** ([dependencies]). WorkManager si v naplánované úloze
 * pamatuje JMÉNO třídy a po zabití procesu ji vytváří sám, bez obrazovky i bez DI — podtřída
 * v aplikaci tak drží jméno, které se nemění ani s přesunem knihovny, a ví, odkud vzít runtime
 * a texty notifikací ([ModelDownloadNotifications]). Plánuje [ModelDownloadScheduler].
 *
 * Stahuje se gigabajty, tedy minuty. Dokud to dělala korutina v procesu aplikace, stahování
 * skončilo s procesem — a ten systém v pozadí zabíjí běžně — a navázalo až dalším ťuknutím
 * v Nastavení. Úlohu WorkManager spustí znovu i po zabití procesu a stahování naváže na `.part`
 * (`Range`, viz [LiteRtOnDeviceAi]).
 *
 * - **Stahuje pořád [LiteRtOnDeviceAi]**, i s navazováním po výpadku sítě; úloha jen drží proces
 *   naživu a ukazuje průběh. Stav pro obrazovku zapisuje tatáž instance, takže Nastavení vidí
 *   průběh, ať stahování spustilo ťuknutí, nebo WorkManager po restartu procesu.
 * - **Podmínka sítě.** Bez připojení se úloha nespustí a když síť zmizí, WorkManager ji zastaví
 *   a po návratu spustí znovu — služba v popředí tak nevisí hodiny bez sítě. Krátký výpadek,
 *   o kterém systém ještě neví, dál přečká smyčka v [LiteRtOnDeviceAi]. „Jen přes Wi-Fi" je
 *   tatáž podmínka, jen přísnější (`UNMETERED`, [ModelDownloadScheduler]).
 * - **Zákaz dat za běhu** ([WifiRequiredException]) úlohu ukončí s `retry`: WorkManager ji pak
 *   pustí znovu, až podmínka sítě (už přísnější) zase platí. Zrušení by znamenalo zastavení.
 * - **Služba v popředí typu `dataSync`.** Bez ní by běžná úloha skončila po deseti minutách.
 *   Od Androidu 12 se ale služba v popředí z pozadí spustit nesmí (úloha naplánovaná po zabití
 *   procesu běží právě tam) — pak se stahuje jako běžná úloha: po limitu ji systém zastaví,
 *   WorkManager ji spustí znovu a stahování naváže. Pomalejší, ale dojde se.
 * - **Zastavit jde z notifikace i z aplikace** ([OnDeviceAi.stopDownload]). Bez toho by stahování,
 *   které přežije zabití aplikace, nešlo zastavit vůbec. Zrušení úlohy pozná [onStopped] podle důvodu
 *   ([WorkInfo.STOP_REASON_CANCELLED_BY_APP]) od zastavení systémem.
 */
abstract class AiModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /**
     * Co úloha potřebuje od aplikace. [ai] musí být TÁŽ instance, jejíž stav ukazuje obrazovka.
     * [modelById] najde model z katalogu aplikace podle [DownloadedModel.id]: po zabití procesu
     * zbude úloze jen to; neznámé id (`null`, model z katalogu zmizel) úlohu ukončí se selháním.
     */
    class Dependencies(
        val ai: LiteRtOnDeviceAi,
        val notifications: ModelDownloadNotifications,
        val modelById: (String) -> DownloadedModel?,
    )

    /**
     * Runtime a notifikace z aplikace (typicky z DI); `null` = aplikace ještě není připravená
     * a úloha selže. Volá se na začátku každého běhu.
     */
    protected abstract fun dependencies(): Dependencies?

    private lateinit var notifications: ModelDownloadNotifications

    private val logger = Logger.withTag("OnDeviceAi")

    /** Úlohu se podařilo povýšit na službu v popředí; jinak se průběh jen zobrazuje. */
    private var foreground = true

    /** Naposledy ukázaný průběh (procenta, čekání na síť) — notifikace se mění jen při změně. */
    private var shown: Pair<Int, Boolean>? = null

    override suspend fun doWork(): Result {
        val dependencies = dependencies() ?: return Result.failure()
        val ai = dependencies.ai
        notifications = dependencies.notifications
        val model = inputData.getString(ModelDownloadScheduler.KEY_MODEL)?.let(dependencies.modelById)
            ?: return Result.failure()
        val channel = notifications.channel(applicationContext)

        return try {
            coroutineScope {
                val progress = launch {
                    ai.downloadProgress(model).conflate().collect { showProgress(model, channel, it) }
                }
                try {
                    ai.runDownload(model)
                } finally {
                    progress.cancel()
                }
            }
            notifyResult(model, channel, error = null)
            Result.success()
        } catch (e: CancellationException) {
            ai.onWorkerStopped(
                model,
                byUser = stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP,
                waitingForNetwork = stopReason == WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
            )
            hideProgress(model)
            throw e
        } catch (e: WifiRequiredException) {
            hideProgress(model)
            Result.retry()
        } catch (e: Exception) {
            notifyResult(model, channel, error = e)
            Result.failure()
        }
    }

    private suspend fun showProgress(model: DownloadedModel, channel: String, progress: ModelDownload) {
        val percent = progress.fraction?.let { (it * 100).toInt() } ?: -1
        val key = percent to progress.waitingForNetwork
        if (key == shown) return
        shown = key

        val info = foregroundInfo(model, channel, progress, percent)
        if (foreground) {
            try {
                setForeground(info)
                return
            } catch (e: CancellationException) {
                // Je to taky `IllegalStateException` — bez tohohle by zrušená úloha vypadala
                // jako odepřená služba v popředí a notifikaci průběhu by ukázala znovu.
                throw e
            } catch (e: IllegalStateException) {
                // `ForegroundServiceStartNotAllowedException` (Android 12+, úloha běží z pozadí).
                // Očekávaný stav po zabití procesu, ne chyba — proto bez stack trace.
                logger.i { "Stahování modelu poběží bez služby v popředí (${model.displayName}): ${e.message}" }
                foreground = false
            }
        }
        notify(info.notificationId, info.notification)
    }

    private suspend fun foregroundInfo(model: DownloadedModel, channel: String, progress: ModelDownload, percent: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(notifications.smallIcon)
            .setContentTitle(notifications.progressTitle(model))
            .setContentText(notifications.progressText(progress))
            .setProgress(100, percent.coerceAtLeast(0), percent < 0 || progress.waitingForNetwork)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // Bez toho by Android 12+ notifikaci služby ukázal až po deseti vteřinách.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(
                0,
                notifications.stopAction(),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        val notificationId = PROGRESS_NOTIFICATION_ID + model.notificationOffset
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Notifikace průběhu po zastavení. Službu v popředí uklidí WorkManager sám; tohle je pro
     * případ, kdy úloha běžela bez ní a notifikaci ukazovala sama.
     */
    private fun hideProgress(model: DownloadedModel) {
        if (!foreground) NotificationManagerCompat.from(applicationContext).cancel(PROGRESS_NOTIFICATION_ID + model.notificationOffset)
    }

    /**
     * Výsledek, i když je aplikace zavřená: kdo stahování spustil a odešel, se jinak nedozví,
     * že model je připravený — nebo že se stahování zastavilo a čeká na něj.
     */
    private suspend fun notifyResult(model: DownloadedModel, channel: String, error: Exception?) {
        hideProgress(model)
        val (title, text) = notifications.result(model, error)
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(notifications.smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(if (error == null) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        notify(RESULT_NOTIFICATION_ID + model.notificationOffset, notification)
    }

    private fun notify(id: Int, notification: Notification) {
        // Od Androidu 13 bez oprávnění `notify` tiše nic neudělá; o oprávnění se ptá aplikace.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }

    /** Otevře aplikaci tam, kde byla. Cíl přes launcher intent, `MainActivity` je ve vstupním bodu. */
    private fun openApp(): PendingIntent? {
        val component = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.component
            ?: return null
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(component)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(applicationContext, OPEN_APP_REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** Vlastní notifikace pro každý model; z id, takže stejná i po restartu procesu. */
    private val DownloadedModel.notificationOffset: Int get() = id.hashCode() and 0xFFFF

    private companion object {
        /** Oddělené od notifikací zpráv (ty mají id skupin TDLib, malá kladná čísla). */
        const val PROGRESS_NOTIFICATION_ID = 0x6A10_0000
        const val RESULT_NOTIFICATION_ID = 0x6A20_0000
        const val OPEN_APP_REQUEST_CODE = 0x6A30_0000
    }
}

/**
 * Plánuje stahování modelu jako úlohu WorkManageru — třídu [worker] (podtřídu
 * [AiModelDownloadWorker] od aplikace), jedna jedinečná úloha na model.
 */
class ModelDownloadScheduler(context: Context, private val worker: Class<out AiModelDownloadWorker>) {

    private val context = context.applicationContext

    private fun workName(model: DownloadedModel) = "ai-model-download-${model.id}"

    /**
     * `KEEP`: běžící i naplánovaná úloha (třeba čekající na síť) zůstává, druhá by stahovala
     * do téhož `.part` souběžně. Dokončená nebo zrušená úloha `KEEP` neblokuje.
     */
    fun enqueue(model: DownloadedModel, unmeteredOnly: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request(model, unmeteredOnly))
    }

    /**
     * Změní podmínku sítě naplánované nebo běžící úlohy (`updateWork`), aniž by ji zrušil:
     * zrušení by stav ohlásil jako zastavení uživatelem ([AiModelDownloadWorker.onStopped])
     * a obrazovka by ukázala „Pokračovat ve stahování". Úloha čekající na Wi-Fi se po povolení
     * dat rozběhne hned. **Běžící úlohu ale nová podmínka nezastaví** (platí až pro příští běh)
     * — to, že po zákazu dat na mobilu přestane, zařídí [LiteRtOnDeviceAi.setDownloadNetwork] sám.
     */
    fun updateNetwork(model: DownloadedModel, unmeteredOnly: Boolean) {
        val manager = WorkManager.getInstance(context)
        val pending = manager.getWorkInfosForUniqueWork(workName(model)).get().firstOrNull { !it.state.isFinished } ?: return
        manager.updateWork(request(model, unmeteredOnly, id = pending.id))
    }

    /**
     * Podmínka sítě: `UNMETERED` stahuje jen na neplacené síti — Wi-Fi z hotspotu telefonu
     * systém vede jako placenou, a právě o tu jde. Po návratu takové sítě se úloha rozběhne
     * sama, stejně jako po výpadku připojení.
     */
    private fun request(model: DownloadedModel, unmeteredOnly: Boolean, id: UUID? = null) =
        OneTimeWorkRequest.Builder(worker)
            .setInputData(workDataOf(KEY_MODEL to model.id))
            .setConstraints(
                Constraints(requiredNetworkType = if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED),
            )
            // Po zákazu dat za běhu ([WifiRequiredException]) se opakuje; výchozí exponenciální
            // prodleva by stahování po návratu na Wi-Fi zdržela o desítky vteřin a víc.
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .apply { if (id != null) setId(id) }
            .build()

    fun cancel(model: DownloadedModel) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(model))
    }

    /** Je úloha naplánovaná nebo běží? Po zabití procesu ji WorkManager spustí sám. */
    suspend fun isPending(model: DownloadedModel): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(model)).first()
            .any { !it.state.isFinished }

    internal companion object {
        const val KEY_MODEL = "model"
    }
}

/**
 * Notifikace stahování modelu ([AiModelDownloadWorker]): kanál, ikona a texty jsou aplikace.
 * Texty se čtou těsně před ukázáním (`suspend`), ať platí jazyk aplikace platný TEĎ.
 */
interface ModelDownloadNotifications {

    /** Malá ikona notifikací (drawable aplikace). */
    val smallIcon: Int

    /** Založí kanál notifikací stahování, není-li, a vrátí jeho id (jméno kanálu je text aplikace). */
    suspend fun channel(context: Context): String

    /** Název notifikace průběhu. */
    suspend fun progressTitle(model: DownloadedModel): String

    /** Text pod ním: průběh v bajtech, nebo čekání na síť; `null` = bez textu (velikost neznámá). */
    suspend fun progressText(progress: ModelDownload): String?

    /** Popisek tlačítka Zastavit. */
    suspend fun stopAction(): String

    /** Název a text notifikace o konci; [error] `null` = model je připravený. */
    suspend fun result(model: DownloadedModel, error: Exception?): Pair<String, String>
}
