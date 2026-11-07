package eu.tintera.ai.core

import android.content.Context
import co.touchlab.kermit.Logger
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallHelper
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import eu.tintera.ai.engine.LlmEngineFactory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android: runtime modelu v zařízení je součást z Google Play stahovaná až na požádání (Play
 * Feature Delivery): LiteRT-LM v Plume :client:litertFeature, llama.cpp v Plume :client:llamacppFeature.
 * Do základu aplikace by přidaly desítky MB nativních knihoven na ABI, a model v zařízení si
 * zapne málokdo. Jedna instance na runtime ([module], [nativeLibrary], [factoryClass]).
 *
 * - **Instalaci dělá Play**, aplikace jen poslouchá. Když obrazovka odejde, instalace běží dál;
 *   další [install] se na ni napojí (`SplitInstallManager.getSessionStates`) místo druhé, kterou
 *   by Play odmítl.
 * - **Doinstalovaná součást je vidět až po [SplitCompat.install]** — bez něj by ji classloader
 *   neviděl do restartu procesu. Po restartu ji systém načte sám jako běžný split APK.
 * - **Nativní knihovnu načte [SplitInstallHelper.loadLibrary] dřív než runtime**: ten si ji
 *   bere `System.loadLibrary`, který v čerstvě doinstalovaném splitu cestu najít nemusí; už
 *   načtenou knihovnu pak linker najde podle jména.
 * - **Souhlas, který si Play občas řekne** (`REQUIRES_USER_CONFIRMATION`), ukáže dialog Play
 *   v aktivitě přes [SplitInstallConfirmations]; dokud uživatel neodpoví, instalace stojí.
 * - **Mimo Google Play to nejde** (APK z `assembleDebug`, jiný obchod) — instalace selže
 *   (`APP_NOT_OWNED`, -15) a model se nestáhne. Pro vývoj se součást instaluje spolu s aplikací, viz
 *   docs/cs/ai-v-zarizeni.md, „Runtime jako součást z Google Play".
 */
class PlayLiteRtRuntime(
    context: Context,
    /** Jméno součásti = jméno modulu Gradle (`dist:module` ho bere z projektu). */
    private val module: String,
    /** Hlavní nativní knihovna runtime bez `lib` a `.so`. */
    private val nativeLibrary: String,
    /** Implementace [LlmEngineFactory] v součásti, viz konstanty v :ai-engine. */
    private val factoryClass: String,
    /** Kudy jde žádost Play o souhlas k aktivitě. */
    private val confirmations: SplitInstallConfirmations,
) : LiteRtRuntime {

    private val context = context.applicationContext
    private val manager by lazy { SplitInstallManagerFactory.create(this.context) }
    private val logger = Logger.withTag("OnDeviceAi")

    @Volatile
    private var engines: LlmEngineFactory? = null

    override fun installed(): Boolean = module in manager.installedModules

    override fun install(): Flow<ModelDownload> = callbackFlow {
        if (installed()) {
            close()
            awaitClose()
            return@callbackFlow
        }
        var sessionId = 0
        var done = false

        /** Dialog souhlasu Play; odpověď rozhodne, jestli instalace pokračuje. */
        fun confirm(state: SplitInstallSessionState) {
            sessionId = state.sessionId()
            logger.i { "Play chce před instalací $module souhlas" }
            confirmations.request(
                SplitInstallConfirmations.Request(
                    state,
                    declined = {
                        done = true
                        close(DownloadStoppedException())
                    },
                    failed = {
                        done = true
                        close(RuntimeInstallException(cause = IllegalStateException("Play confirmation dialog failed")))
                    },
                ),
            )
        }

        val listener = SplitInstallStateUpdatedListener { state ->
            if (module !in state.moduleNames()) return@SplitInstallStateUpdatedListener
            when (state.status()) {
                SplitInstallSessionStatus.DOWNLOADING ->
                    trySend(ModelDownload(state.bytesDownloaded(), state.totalBytesToDownload()))
                SplitInstallSessionStatus.INSTALLED -> {
                    done = true
                    SplitCompat.install(context)
                    logger.i { "Runtime $module doinstalovaný" }
                    close()
                }
                SplitInstallSessionStatus.FAILED -> {
                    done = true
                    close(RuntimeInstallException(state.errorCode()))
                }
                SplitInstallSessionStatus.CANCELED -> {
                    done = true
                    close(DownloadStoppedException())
                }
                // Souhlas ukáže dialog Play v aktivitě (SplitInstallConfirmationHost); po něm
                // instalace pokračuje dalšími stavy, odmítnutí stahování zastaví.
                SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> confirm(state)
                else -> Unit
            }
        }
        manager.registerListener(listener)

        fun start() {
            manager.startInstall(SplitInstallRequest.newBuilder().addModule(module).build())
                .addOnSuccessListener { id ->
                    sessionId = id
                    // Nula = nic se stahovat nemusí, součást už mezitím je.
                    if (id == 0) {
                        done = true
                        close()
                    }
                }
                .addOnFailureListener { e ->
                    done = true
                    close(RuntimeInstallException(code = (e as? SplitInstallException)?.errorCode, cause = e))
                }
        }
        manager.sessionStates
            .addOnSuccessListener { states ->
                val running = states.firstOrNull { module in it.moduleNames() && it.isActive() }
                when {
                    // Dialog z minulého ťuknutí zavřený bez odpovědi: sezení na souhlas pořád čeká
                    // a nová instalace by narazila na limit sezení.
                    running?.status() == SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> confirm(running)
                    running != null -> sessionId = running.sessionId()
                    else -> start()
                }
            }
            .addOnFailureListener { start() }

        awaitClose {
            manager.unregisterListener(listener)
            if (sessionId != 0) confirmations.withdraw(sessionId)
            // Zastavil uživatel (nebo odešel celý runtime) — Play by jinak stahoval dál.
            if (!done && sessionId != 0) manager.cancelInstall(sessionId)
        }
    }

    override fun engines(): LlmEngineFactory = engines ?: synchronized(this) {
        engines ?: run {
            SplitCompat.install(context)
            SplitInstallHelper.loadLibrary(context, nativeLibrary)
            loadEngineFactory(context.classLoader, factoryClass).also { engines = it }
        }
    }

    private fun SplitInstallSessionState.isActive() = status() in ACTIVE

    private companion object {
        val ACTIVE = setOf(
            SplitInstallSessionStatus.PENDING,
            SplitInstallSessionStatus.DOWNLOADING,
            SplitInstallSessionStatus.DOWNLOADED,
            SplitInstallSessionStatus.INSTALLING,
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION,
        )
    }
}
