package eu.tintera.ai.core

import android.content.Context
import android.net.ConnectivityManager

/**
 * Placená síť podle systému (`isActiveNetworkMetered`): mobilní data i Wi-Fi z hotspotu, kterou
 * systém jako placenou vede. Totéž rozhoduje podmínka `UNMETERED` úlohy stahování.
 *
 * **Bez sítě `false`**, ne `true` jako systém: bez připojení se nemá na co ptát — dotaz „stáhnout
 * přes data?" by v letovém režimu nedával smysl a stahování stejně počká na síť.
 */
class AndroidMeteredNetwork(context: Context) : MeteredNetwork {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override fun isMetered(): Boolean = manager.activeNetwork != null && manager.isActiveNetworkMetered
}
