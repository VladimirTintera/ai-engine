package eu.tintera.ai.core

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import eu.tintera.ai.engine.LlmEngineFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Po zabití procesu zbude úloze stahování jen [DownloadedModel.id]; model si podle něj vezme
 * z katalogu aplikace ([AiModelDownloadWorker.Dependencies.modelById]). Celou cestou: naplánuje
 * [ModelDownloadScheduler], spustí opravdový WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiModelDownloadWorkerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val root = Files.createTempDirectory("ai-model-worker").toFile()
    private val model = testModel("catalog-model")
    private val notifications = RecordingNotifications()

    @BeforeTest
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        // Stažený soubor: `runDownload` skončí hned, netahá se ze sítě.
        File(root, model.fileName).writeText("x")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        CatalogWorker.dependencies = null
    }

    private fun dependencies(catalog: List<DownloadedModel>) = AiModelDownloadWorker.Dependencies(
        ai = LiteRtOnDeviceAi(FilesOnlyPlatform(root), { InstalledRuntime }, AlwaysOnline),
        notifications = notifications,
        modelById = { id -> catalog.find { it.id == id } },
    )

    /** Naplánuje [model], splní podmínku sítě a počká na konec úlohy. */
    private fun runWorker(): WorkInfo.State {
        ModelDownloadScheduler(context, CatalogWorker::class.java).enqueue(model, unmeteredOnly = false)
        val manager = WorkManager.getInstance(context)
        val work = manager.getWorkInfosForUniqueWork("ai-model-download-${model.id}").get().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val state = manager.getWorkInfoById(work.id).get()!!.state
            if (state.isFinished) return state
            Thread.sleep(10)
        }
        error("Úloha nedoběhla")
    }

    @Test
    fun idZKataloguVyresiModel() {
        CatalogWorker.dependencies = dependencies(listOf(testModel("other"), model))

        assertEquals(WorkInfo.State.SUCCEEDED, runWorker())
        assertEquals(listOf(model), notifications.finished)
    }

    @Test
    fun neznameIdUlohaSelze() {
        CatalogWorker.dependencies = dependencies(listOf(testModel("other")))

        assertEquals(WorkInfo.State.FAILED, runWorker())
        assertTrue(notifications.finished.isEmpty())
    }
}

/** Podtřída z aplikace; WorkManager ji vytváří sám, proto závislosti ve statickém poli. */
class CatalogWorker(context: Context, params: WorkerParameters) : AiModelDownloadWorker(context, params) {
    override fun dependencies(): Dependencies? = Companion.dependencies

    companion object {
        @Volatile
        var dependencies: Dependencies? = null
    }
}

private class RecordingNotifications : ModelDownloadNotifications {
    val finished = mutableListOf<DownloadedModel>()
    override val smallIcon = android.R.drawable.stat_sys_download
    override suspend fun channel(context: Context) = "downloads"
    override suspend fun progressTitle(model: DownloadedModel) = model.displayName
    override suspend fun progressText(progress: ModelDownload): String? = null
    override suspend fun stopAction() = "Stop"
    override suspend fun result(model: DownloadedModel, error: Exception?): Pair<String, String> {
        finished += model
        return model.displayName to "Done"
    }
}

private class FilesOnlyPlatform(private val root: File) : LiteRtPlatform {
    override val modelsDir get() = root
    override val cacheDir get() = root
    override fun canRun(model: DownloadedModel) = true
    override fun png(path: String, maxSide: Int): ByteArray? = null
    override fun wavClips(path: String): List<ByteArray> = emptyList()
    override fun startDownload(model: DownloadedModel, unmeteredOnly: Boolean, run: suspend () -> Unit) {}
    override fun cancelDownload(model: DownloadedModel) {}
    override suspend fun downloadPending(model: DownloadedModel) = false
}

private object InstalledRuntime : LiteRtRuntime {
    override fun installed() = true
    override fun install(): Flow<ModelDownload> = emptyFlow()
    override fun engines(): LlmEngineFactory = error("This test never loads a model")
}

private object AlwaysOnline : Connectivity {
    override val online = flowOf(true)
}
