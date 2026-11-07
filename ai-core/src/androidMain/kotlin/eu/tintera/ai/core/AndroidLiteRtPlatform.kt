package eu.tintera.ai.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * LiteRT-LM na Androidu: modely v `filesDir/ai-models`, stahování úlohou WorkManageru
 * ([AiModelDownloadWorker], podtřída [worker] od aplikace), obraz přes `BitmapFactory`
 * ([ImageInput]) a zvuk dekodérem od aplikace ([audio]).
 *
 * Nenabízí se pod Androidem 12 ([MIN_SDK]), bez arm64 a pod [LiteRtModel.minMemoryGb].
 */
class AndroidLiteRtPlatform(
    private val context: Context,
    /**
     * Třída úlohy stahování, kterou WorkManager vytvoří. Jméno si pamatuje v naplánované úloze,
     * takže ho aplikace měnit nesmí — proto podtřída v aplikaci, ne třída knihovny.
     */
    worker: Class<out AiModelDownloadWorker>,
    private val audio: AudioClipDecoder = NoAudioClips,
) : LiteRtPlatform {

    private val downloads = ModelDownloadScheduler(context, worker)

    override val modelsDir: File get() = File(context.filesDir, MODELS_DIR)

    override val cacheDir: File get() = context.cacheDir

    override fun canRun(model: LiteRtModel): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SDK) return false
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) return false
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        // `totalMem` hlásí o kus méně než udává výrobce (část si vezme jádro), proto rezerva.
        return memory.totalMem >= model.minMemoryGb * GIB * MEMORY_TOLERANCE
    }

    override fun png(path: String, maxSide: Int): ByteArray? = ImageInput.png(path, maxSide)

    override fun wavClips(path: String): List<ByteArray> = audio.wavClips(path)

    /** Úloha si runtime vezme od aplikace a stáhne model sama; [run] se tu nevolá. */
    override fun startDownload(model: LiteRtModel, unmeteredOnly: Boolean, run: suspend () -> Unit) {
        downloads.enqueue(model, unmeteredOnly)
    }

    override fun setDownloadNetwork(model: LiteRtModel, unmeteredOnly: Boolean) {
        downloads.updateNetwork(model, unmeteredOnly)
    }

    override fun cancelDownload(model: LiteRtModel) {
        downloads.cancel(model)
    }

    override suspend fun downloadPending(model: LiteRtModel): Boolean = downloads.isPending(model)

    private companion object {
        const val MODELS_DIR = "ai-models"

        /** Gallery, ze které je katalog modelů, jede od Androidu 12; níž to nikdo neověřil. */
        const val MIN_SDK = 31

        const val GIB = 1024.0 * 1024 * 1024
        const val MEMORY_TOLERANCE = 0.85
    }
}
