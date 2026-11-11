package eu.tintera.ai.playground

import eu.tintera.ai.core.DownloadedModel
import eu.tintera.ai.core.ModelVariant
import eu.tintera.ai.core.OnDeviceAi
import eu.tintera.ai.playground.catalog.HuggingFace
import eu.tintera.ai.playground.chat.ChatSession
import eu.tintera.ai.playground.models.ModelManager
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Co dodá vstupní bod platformy (Android `Application`, desktop `main`, iOS
 * `MainViewController`). Tady je vidět, co aplikace knihovně dává: hotové [ai] zapojené pro
 * platformu (README, „Wire the providers") a pár věcí o zařízení pro obrazovku.
 */
class PlaygroundEnvironment(
    /** `RoutingOnDeviceAi` se stahovanými modely a systémovým modelem platformy. */
    val ai: OnDeviceAi,
    /** Adresář dat aplikace (nastavení, vlastní modely), absolutní cesta. */
    val dataDir: String,
    /** „Android", „iOS", „Desktop" — do hlášek. */
    val platformName: String,
    /**
     * Pozná platforma placenou síť (`MeteredNetwork`)? Jinak se volba „jen přes Wi-Fi" nenabízí,
     * slibovala by něco, co se nestane (desktop).
     */
    val wifiOnlySupported: Boolean,
    /** Systémové modely platformy (Gemini Nano, Apple Intelligence), které se mají ukázat. */
    val systemModels: List<SystemModel>,
    /** Proč [DownloadedModel] na tomhle zařízení nejde, když `status` vrátil `Unsupported`. */
    val unsupportedReason: (DownloadedModel) -> String,
)

/** Systémový model (stahuje a spravuje ho systém, aplikace ho jen používá). */
data class SystemModel(val variant: ModelVariant, val name: String, val description: String)

/**
 * Stav aplikace, jeden na proces: správa modelů a rozběhnutá konverzace přežijí otočení
 * obrazovky (Android) i odchod z obrazovky. Vytváří ho vstupní bod platformy.
 */
class Playground(val environment: PlaygroundEnvironment, http: HttpClient = HttpClient()) {

    /** Hlavní vlákno: stav pro Compose se mění jen odtud. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val storage = Storage(environment.dataDir)

    val huggingFace = HuggingFace(http)

    val models = ModelManager(environment, storage, scope)

    private val _chat = MutableStateFlow<ChatSession?>(null)

    /** Otevřená konverzace; `null` = obrazovka modelů. */
    val chat: StateFlow<ChatSession?> = _chat.asStateFlow()

    fun openChat(variant: ModelVariant, title: String, subtitle: String) {
        _chat.value?.stop()
        _chat.value = ChatSession(environment.ai, variant, title, subtitle, scope)
    }

    fun closeChat() {
        _chat.value?.stop()
        _chat.value = null
    }
}
