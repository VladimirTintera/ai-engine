package eu.tintera.ai.core

import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Stahování jen přes Wi-Fi ([LiteRtOnDeviceAi.download] s `mobileData = false`): podmínku sítě
 * dostane platforma (na Androidu úloha `UNMETERED`) a stav na placené síti rovnou říká, že se
 * čeká na Wi-Fi — úloha se tam vůbec nespustí, takže by jinak tvrdil, že se stahuje.
 */
class LiteRtDownloadNetworkTest {

    private val root = Files.createTempDirectory("ai-litert-network").toFile()
    private val model = testModel()
    private val started: MutableList<Pair<DownloadedModel, Boolean>> = Collections.synchronizedList(mutableListOf())
    private val changed: MutableList<Pair<DownloadedModel, Boolean>> = Collections.synchronizedList(mutableListOf())

    private val platform = object : LiteRtPlatform {
        override val modelsDir = File(root, "ai-models").apply { mkdirs() }.path
        override val cacheDir = File(root, "ai-cache").path
        override fun canRun(model: DownloadedModel) = true
        override fun png(path: String, maxSide: Int): ByteArray? = null
        override fun wavClips(path: String): List<ByteArray> = emptyList()
        override fun startDownload(model: DownloadedModel, unmeteredOnly: Boolean, run: suspend () -> Unit) {
            started += model to unmeteredOnly
        }
        override fun setDownloadNetwork(model: DownloadedModel, unmeteredOnly: Boolean) {
            changed += model to unmeteredOnly
        }
        override fun cancelDownload(model: DownloadedModel) {}
        override suspend fun downloadPending(model: DownloadedModel) = false
    }

    private fun ai(metered: Boolean) = LiteRtOnDeviceAi(
        platform,
        { FakeLiteRtRuntime(installed = true) },
        AlwaysOnline,
        meteredNetwork = object : MeteredNetwork {
            override fun isMetered() = metered
        },
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun jenPresWifiNaDatechCeka() = runBlocking {
        val first = withTimeout(5.seconds) { ai(metered = true).download(model.variant, mobileData = false).first() }

        assertTrue(first.waitingForNetwork)
        assertTrue(first.wifiOnly)
        assertEquals(listOf(model to true), started.toList())
    }

    @Test
    fun jenPresWifiNaWifiStahuje() = runBlocking {
        val first = withTimeout(5.seconds) { ai(metered = false).download(model.variant, mobileData = false).first() }

        assertFalse(first.waitingForNetwork)
        assertTrue(first.wifiOnly)
        assertEquals(listOf(model to true), started.toList())
    }

    @Test
    fun sDatySeNaSitNehledi() = runBlocking {
        val first = withTimeout(5.seconds) { ai(metered = true).download(model.variant, mobileData = true).first() }

        assertFalse(first.waitingForNetwork)
        assertFalse(first.wifiOnly)
        assertEquals(listOf(model to false), started.toList())
    }

    /** Povolení dat u stahování, které čeká na Wi-Fi: úloha dostane novou podmínku, nezruší se. */
    @Test
    fun povoleniDatZmeniPodminkuCekajicihoStahovani() = runBlocking {
        val ai = ai(metered = true)
        withTimeout(5.seconds) { ai.download(model.variant, mobileData = false).first() }

        ai.setDownloadNetwork(model.variant, mobileData = true)
        val now = withTimeout(5.seconds) { ai.download(model.variant).first() }

        assertEquals(listOf(model to false), changed.toList())
        assertFalse(now.wifiOnly)
        // Podruhé se nic neplánuje: připojení k běžícímu stahování ho nespouští znovu.
        assertEquals(listOf(model to true), started.toList())
    }
}
