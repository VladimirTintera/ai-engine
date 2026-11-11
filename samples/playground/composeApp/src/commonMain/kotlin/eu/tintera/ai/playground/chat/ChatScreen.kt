package eu.tintera.ai.playground.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.playground.formatSeconds
import eu.tintera.ai.playground.oneDecimal
import eu.tintera.ai.playground.ui.Icons

/** Konverzační test nad jedním modelem: streamovaná odpověď s měřením, tlačítko Zastavit. */
@Composable
fun ChatScreen(session: ChatSession, onBack: () -> Unit) {
    val messages by session.messages.collectAsState()
    val generating by session.generating.collectAsState()
    var input by rememberSaveable(session) { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Na konec při nové zprávě i při každém kousku odpovědi.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session.title) },
                subtitle = { Text(session.subtitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = session::clear, enabled = messages.isNotEmpty()) {
                        Icon(Icons.Refresh, contentDescription = "New conversation")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f).testTag("chat-input"),
                    maxLines = 5,
                )
                if (generating) {
                    FilledIconButton(
                        onClick = session::stop,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.Stop, contentDescription = "Stop") }
                } else {
                    FilledIconButton(
                        onClick = { session.send(input); input = "" },
                        enabled = input.isNotBlank(),
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.Send, contentDescription = "Send") }
                }
            }
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Ask anything. The first answer also loads the model, which can take a few seconds.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message -> MessageBubble(message) }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.fromUser
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            if (message.droppedTurns > 0) {
                Text(
                    "${message.droppedTurns} oldest ${if (message.droppedTurns == 1) "turn" else "turns"} left out to fit the context",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // Zastavené dřív, než přišel první text: bez prázdné bubliny, jen poznámka pod ní.
            if (message.text.isNotEmpty() || message.streaming || message.error != null) Surface(
                shape = MaterialTheme.shapes.large,
                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    message.thinking?.let { thinking ->
                        Text("Thinking", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            thinking,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = if (message.answer.isNotEmpty()) 8.dp else 0.dp),
                        )
                    }
                    when {
                        message.answer.isNotEmpty() -> SelectionContainer { Text(message.answer, style = MaterialTheme.typography.bodyLarge) }
                        message.streaming && message.thinking == null -> LoadingIndicator(Modifier.size(36.dp))
                    }
                    message.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            val notes = buildList {
                if (message.truncated) add("cut off at the output limit")
                if (message.stopped) add("stopped")
                message.metrics?.let { addAll(it.describe()) }
            }
            if (notes.isNotEmpty()) {
                Text(
                    notes.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

/** „model loaded in 2.1 s (GPU) · first token 0.3 s · ≈ 24.5 tokens/s · 98 chars/s · prompt 120/6656 tokens". */
private fun AnswerMetrics.describe(): List<String> = buildList {
    loadMs?.let { load ->
        add("model loaded in ${formatSeconds(load)}" + (backend?.let { " (${if (it == LlmBackend.Gpu) "GPU" else "CPU"})" } ?: ""))
    }
    firstTokenMs?.let { add("first token ${formatSeconds(it)}") }
    tokensPerSecond?.let { add("≈ ${oneDecimal(it)} tokens/s") }
    charsPerSecond?.let { add("${oneDecimal(it)} chars/s") }
    add("prompt ≈ $promptTokens/$tokenLimit tokens")
}
