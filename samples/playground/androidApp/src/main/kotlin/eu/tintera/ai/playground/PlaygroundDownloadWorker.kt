package eu.tintera.ai.playground

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkerParameters
import eu.tintera.ai.core.AiModelDownloadWorker
import eu.tintera.ai.core.DownloadedModel
import eu.tintera.ai.core.ModelDownload
import eu.tintera.ai.core.ModelDownloadNotifications

/**
 * Úloha stahování modelu. WorkManager si pamatuje jméno TÉHLE třídy a po zabití procesu ji vytvoří
 * sám; závislosti si vezme z [PlaygroundApplication].
 */
class PlaygroundDownloadWorker(context: Context, params: WorkerParameters) : AiModelDownloadWorker(context, params) {

    override fun dependencies(): Dependencies {
        val app = applicationContext as PlaygroundApplication
        return Dependencies(app.liteRt, DownloadNotifications, app::modelById)
    }
}

/** Kanál, ikona a texty notifikace stahování (knihovna texty nenese). */
object DownloadNotifications : ModelDownloadNotifications {

    private const val CHANNEL = "model-downloads"

    override val smallIcon: Int = R.drawable.ic_download

    override suspend fun channel(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        }
        return CHANNEL
    }

    override suspend fun progressTitle(model: DownloadedModel): String = "Downloading ${model.displayName}"

    override suspend fun progressText(progress: ModelDownload): String? = when {
        progress.waitingForNetwork && progress.wifiOnly -> "Waiting for Wi-Fi"
        progress.waitingForNetwork -> "Waiting for network"
        else -> progress.totalBytes?.let { "${formatBytes(progress.downloadedBytes)} of ${formatBytes(it)}" }
    }

    override suspend fun stopAction(): String = "Stop"

    override suspend fun result(model: DownloadedModel, error: Exception?): Pair<String, String> =
        if (error == null) {
            "${model.displayName} is ready" to "Open the playground to chat with it."
        } else {
            "${model.displayName} was not downloaded" to (error.message ?: error::class.simpleName.orEmpty())
        }
}
