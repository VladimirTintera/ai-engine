package eu.tintera.ai.playground

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.backhandler.BackHandler
import eu.tintera.ai.playground.chat.ChatScreen
import eu.tintera.ai.playground.models.ALL
import eu.tintera.ai.playground.models.ModelsScreen

/** Kořen UI: obrazovka modelů, nebo konverzace s vybraným modelem. */
@Composable
fun App(playground: Playground) {
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else expressiveLightColorScheme(),
        motionScheme = MotionScheme.expressive(),
    ) {
        val chat by playground.chat.collectAsState()
        val session = chat
        // Nad oběma obrazovkami: po návratu z konverzace zůstane vybraná rodina i pozice v seznamu.
        val modelsList = rememberLazyListState()
        val filter = rememberSaveable { mutableStateOf(ALL) }
        if (session == null) {
            ModelsScreen(playground, modelsList, filter)
        } else {
            // Systémové Zpět (Android, gesto na iOS) zavře konverzaci. BackHandler je v Compose 1.12
            // zastaralý ve prospěch NavigationEventHandler, pro jednu obrazovku ale stačí.
            @Suppress("DEPRECATION")
            BackHandler { playground.closeChat() }
            ChatScreen(session, onBack = playground::closeChat)
        }
    }
}
