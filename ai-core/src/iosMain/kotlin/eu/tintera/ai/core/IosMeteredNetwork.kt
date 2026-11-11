package eu.tintera.ai.core

import kotlin.concurrent.atomics.AtomicReference
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * Placená síť na iOS: „drahá" cesta podle systému (`NWPath.isExpensive` — mobilní data i Wi-Fi
 * z hotspotu telefonu). Totéž rozhoduje sezení „jen přes Wi-Fi" stahování na pozadí
 * (`allowsExpensiveNetworkAccess = false`, [IosModelDownloads]), takže dotaz a skutečné chování se
 * shodnou. Režim nízkých dat (`isConstrained`) se nepočítá, stejně jako na Androidu.
 *
 * **Bez sítě `false`**, jako `AndroidMeteredNetwork`: bez připojení se nemá na co ptát.
 *
 * Aplikace ho předá do `LiteRtOnDeviceAi(meteredNetwork = IosMeteredNetwork())`: bez něj obrazovka
 * neukáže „čeká na Wi-Fi" a volbu „jen přes Wi-Fi" nemá aplikace podle čeho nabízet.
 */
class IosMeteredNetwork : MeteredNetwork {
    override fun isMetered(): Boolean = IosNetworkPath.expensive()
}

/**
 * Jeden `NWPathMonitor` na proces; drží poslední stav cesty. Než systém ohlásí první stav (hned
 * po spuštění), platí „neplacená".
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosNetworkPath {

    private val expensive = AtomicReference(false)

    private val monitor by lazy {
        nw_path_monitor_create().also { monitor ->
            nw_path_monitor_set_update_handler(monitor) { path ->
                expensive.store(nw_path_get_status(path) == nw_path_status_satisfied && nw_path_is_expensive(path))
            }
            nw_path_monitor_set_queue(monitor, dispatch_queue_create("eu.tintera.ai.network-path", null))
            nw_path_monitor_start(monitor)
        }
    }

    /** Je teď použitelná cesta placená? */
    fun expensive(): Boolean {
        monitor
        return expensive.load()
    }
}
