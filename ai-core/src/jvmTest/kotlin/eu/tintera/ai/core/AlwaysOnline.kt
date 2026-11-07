package eu.tintera.ai.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Síť pro testy [LiteRtOnDeviceAi]: pořád připojeno, jako desktop. */
object AlwaysOnline : Connectivity {
    override val online: Flow<Boolean> = flowOf(true)
}
