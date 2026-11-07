package eu.tintera.ai.core

import com.google.android.play.core.splitinstall.SplitInstallSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Souhlas, který Google Play chce před instalací součásti (`REQUIRES_USER_CONFIRMATION`), mezi
 * [PlayLiteRtRuntime] a aktivitou. Runtime běží mimo obrazovku (instalaci může spustit Nastavení
 * i doinstalace u už staženého modelu), dialog Play ale potřebuje aktivitu — ukáže ho aplikace
 * v kořeni aktivity (v Plume `SplitInstallConfirmationHost`): [pending] sleduje, před ukázáním
 * zavolá [markShown] a výsledek dialogu předá žádosti z [takeShown].
 *
 * Play o souhlas žádá podle dokumentace u aplikace, kterou nenainstaloval on, a u větší součásti
 * na mobilních datech; dřív to aplikace brala jako chybu a hned po „Stáhnout model" ukázala
 * „Model se nepodařilo stáhnout".
 */
class SplitInstallConfirmations {

    /**
     * Žádost o souhlas. [declined] ji ukončí jako zastavené stahování (uživatel řekl ne),
     * [failed] jako chybu (dialog nešel ukázat).
     */
    class Request(val state: SplitInstallSessionState, val declined: () -> Unit, val failed: () -> Unit)

    private val _pending = MutableStateFlow<Request?>(null)

    /** Žádost, která čeká na ukázání dialogu. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** Žádost, jejíž dialog je právě na obrazovce — na ni se vztahuje výsledek. */
    @Volatile
    private var shown: Request? = null

    fun request(request: Request) {
        _pending.value = request
    }

    /** Instalace skončila jinak (zastavená, zrušená): neukázaný dialog už nemá smysl. */
    fun withdraw(sessionId: Int) {
        _pending.update { current -> current?.takeUnless { it.state.sessionId() == sessionId } }
    }

    /** Dialog pro [request] jde na obrazovku: žádost přestane čekat a výsledek patří jí. */
    fun markShown(request: Request) {
        shown = request
        _pending.update { current -> current?.takeUnless { it === request } }
    }

    /** Žádost, jejíž dialog byl na obrazovce (a už není); `null`, když žádná. */
    fun takeShown(): Request? = shown.also { shown = null }
}
