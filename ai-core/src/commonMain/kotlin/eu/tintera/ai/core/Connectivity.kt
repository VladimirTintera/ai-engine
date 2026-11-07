package eu.tintera.ai.core

import kotlinx.coroutines.flow.Flow

/**
 * Je zařízení připojené k síti? Stahování modelu podle toho ukazuje „čeká na síť" a po výpadku
 * naváže, až se připojení vrátí (`LiteRtOnDeviceAi`). Dodává aplikace — má vlastní sledování sítě
 * (v Plume `NetworkMonitor`) a druhé by jen hlásilo totéž jindy.
 */
interface Connectivity {

    /** `true` = nějaká použitelná síť je; při odběru se ozve hned aktuální hodnotou, pak na změnu. */
    val online: Flow<Boolean>
}
