package eu.tintera.ai.playground.models

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.tintera.ai.core.ModelVariant
import eu.tintera.ai.core.OnDeviceAiStatus
import eu.tintera.ai.playground.CustomModel
import eu.tintera.ai.playground.Playground
import eu.tintera.ai.playground.SystemModel
import eu.tintera.ai.playground.catalog.Catalog
import eu.tintera.ai.playground.catalog.CatalogModel
import eu.tintera.ai.playground.catalog.CatalogVariant
import eu.tintera.ai.playground.formatBytes
import eu.tintera.ai.playground.label
import eu.tintera.ai.playground.ui.Icons

const val ALL = "all"
private const val CUSTOM = "custom"

/** Obrazovka modelů: systémové modely, katalog po rodinách a vlastní modely z Hugging Face. */
@Composable
fun ModelsScreen(
    playground: Playground,
    /** Stav seznamu a vybraná rodina žijí v [eu.tintera.ai.playground.App], ať přežijí odchod do konverzace. */
    listState: LazyListState = rememberLazyListState(),
    filterState: MutableState<String> = rememberSaveable { mutableStateOf(ALL) },
) {
    val manager = playground.models
    val environment = playground.environment
    val states by manager.states.collectAsState()
    val wifiOnly by manager.wifiOnly.collectAsState()
    val custom by manager.customModels.collectAsState()
    var filter by filterState
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun openChat(variant: CatalogVariant) = playground.openChat(
        variant.model.variant,
        title = variant.model.displayName,
        subtitle = "${variant.model.runtime.label} · ${formatBytes(variant.model.sizeBytes)}",
    )

    val actions = VariantActions(
        download = manager::download,
        pause = manager::pause,
        delete = { variant, name -> confirmDelete = "Delete $name?" to { manager.delete(variant) } },
        dismissError = manager::dismissError,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model playground") },
                subtitle = { Text("On-device models · ${environment.platformName}") },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Hugging Face model") },
                icon = { Icon(Icons.Add, contentDescription = null) },
                onClick = { adding = true },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("models"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (environment.wifiOnlySupported) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Download over Wi-Fi only", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "On mobile data or a hotspot downloads wait for Wi-Fi.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = wifiOnly, onCheckedChange = manager::setWifiOnly)
                        }
                    }
                }
            }

            item {
                FamilyFilter(
                    selected = filter,
                    options = listOf(ALL to "All") + Catalog.map { it.id to it.name } + (CUSTOM to "Custom"),
                    onSelect = { filter = it },
                )
            }

            if (filter == ALL && environment.systemModels.isNotEmpty()) {
                item { SectionHeader("System models", "Managed by the operating system; nothing to download here.") }
                items(environment.systemModels, key = { "system-${it.name}" }) { model ->
                    SystemModelCard(
                        model,
                        states[model.variant],
                        onDownload = { manager.download(model.variant) },
                        onChat = { playground.openChat(model.variant, title = model.name, subtitle = "System model") },
                    )
                }
            }

            for (family in Catalog) {
                if (filter != ALL && filter != family.id) continue
                item(key = "family-${family.id}") { SectionHeader(family.name, family.description) }
                items(family.models, key = { "model-${it.name}" }) { model ->
                    ModelCard(model, states, actions, environment.unsupportedReason, ::openChat)
                }
            }

            if (filter == ALL || filter == CUSTOM) {
                item(key = "custom") {
                    SectionHeader(
                        "Custom",
                        if (custom.isEmpty()) "Add any GGUF or .litertlm file from Hugging Face with the button below."
                        else "Models you added from Hugging Face.",
                    )
                }
                items(custom, key = { it.id }) { model ->
                    CustomModelCard(
                        model,
                        states[model.variant.model.variant],
                        actions,
                        environment.unsupportedReason,
                        onChat = { openChat(model.variant) },
                        onRemove = {
                            confirmDelete = "Remove ${model.file} and delete its file?" to { manager.removeCustom(model) }
                        },
                    )
                }
            }
        }
    }

    if (adding) {
        AddModelDialog(playground, onDismiss = { adding = false })
    }
    confirmDelete?.let { (question, action) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(question) },
            text = { Text("The downloaded file is removed from this device.") },
            confirmButton = {
                TextButton(onClick = { action(); confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Co jde s variantou udělat; volá [ModelManager]. */
class VariantActions(
    val download: (ModelVariant) -> Unit,
    val pause: (ModelVariant) -> Unit,
    val delete: (ModelVariant, String) -> Unit,
    val dismissError: (ModelVariant) -> Unit,
)

/** Výběr rodiny: spojená skupina přepínacích tlačítek (Material 3 Expressive). */
@Composable
private fun FamilyFilter(selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (id, label) ->
            ToggleButton(
                checked = selected == id,
                onCheckedChange = { onSelect(id) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(label) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, description: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelCard(
    model: CatalogModel,
    states: Map<ModelVariant, ModelState>,
    actions: VariantActions,
    unsupportedReason: (eu.tintera.ai.core.DownloadedModel) -> String,
    onChat: (CatalogVariant) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                LicenseChip(model.license.name, model.license.nonCommercial)
            }
            model.variants.forEachIndexed { index, variant ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                else Spacer(Modifier.height(8.dp))
                VariantRow(variant, states[variant.model.variant], actions, unsupportedReason, onChat = { onChat(variant) })
            }
        }
    }
}

@Composable
private fun CustomModelCard(
    model: CustomModel,
    state: ModelState?,
    actions: VariantActions,
    unsupportedReason: (eu.tintera.ai.core.DownloadedModel) -> String,
    onChat: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.repo, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                model.license?.let { LicenseChip(it, nonCommercial = "nc" in it.lowercase()) }
                IconButton(onClick = onRemove) { Icon(Icons.Close, contentDescription = "Remove") }
            }
            VariantRow(model.variant, state, actions, unsupportedReason, onChat, showDelete = false)
        }
    }
}

@Composable
private fun LicenseChip(name: String, nonCommercial: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (nonCommercial) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            if (nonCommercial) "$name · non-commercial" else name,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Jedna varianta: parametry, stav a akce podle stavu. */
@Composable
private fun VariantRow(
    variant: CatalogVariant,
    state: ModelState?,
    actions: VariantActions,
    unsupportedReason: (eu.tintera.ai.core.DownloadedModel) -> String,
    onChat: () -> Unit,
    showDelete: Boolean = true,
) {
    val model = variant.model
    val target = model.variant
    val status = state?.status
    val unsupported = status == OnDeviceAiStatus.Unsupported
    Column(Modifier.fillMaxWidth().alpha(if (unsupported) 0.6f else 1f).testTag("variant-${model.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(variant.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatBytes(model.sizeBytes)} · ${model.runtime.label} · min. ${model.minMemoryGb} GB RAM",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(variant.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (status) {
                null -> LoadingIndicator(Modifier.size(32.dp))
                OnDeviceAiStatus.Unsupported -> Unit
                OnDeviceAiStatus.Downloadable ->
                    FilledTonalButton(onClick = { actions.download(target) }) {
                        Icon(Icons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download")
                    }
                OnDeviceAiStatus.Downloading ->
                    IconButton(onClick = { actions.pause(target) }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Pause, contentDescription = "Pause")
                    }
                OnDeviceAiStatus.Paused -> Row {
                    IconButton(onClick = { actions.download(target) }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Resume, contentDescription = "Resume")
                    }
                    if (showDelete) {
                        IconButton(onClick = { actions.delete(target, model.displayName) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Delete, contentDescription = "Delete")
                        }
                    }
                }
                OnDeviceAiStatus.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showDelete) {
                        IconButton(onClick = { actions.delete(target, model.displayName) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Delete, contentDescription = "Delete")
                        }
                    }
                    Button(onClick = onChat) {
                        Icon(Icons.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Chat")
                    }
                }
            }
        }
        when (status) {
            OnDeviceAiStatus.Unsupported -> Text(
                "Not available here: ${unsupportedReason(model)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OnDeviceAiStatus.Downloading -> DownloadProgress(state.progress, model.sizeBytes)
            OnDeviceAiStatus.Paused -> Text(
                "Paused" + (state.progress?.let { " at ${formatBytes(it.downloadedBytes)}" } ?: "") + " — resume continues where it stopped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Unit
        }
        state?.error?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.dismissError(target) }) { Text("OK") }
            }
        }
    }
}

@Composable
private fun DownloadProgress(progress: eu.tintera.ai.core.ModelDownload?, sizeBytes: Long) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val fraction = progress?.fraction
        if (fraction != null && progress.waitingForNetwork.not()) {
            LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val text = when {
            progress == null -> "Starting…"
            progress.waitingForNetwork && progress.wifiOnly -> "Waiting for Wi-Fi · ${formatBytes(progress.downloadedBytes)} downloaded"
            progress.waitingForNetwork -> "Waiting for network · ${formatBytes(progress.downloadedBytes)} downloaded"
            else -> "${formatBytes(progress.downloadedBytes)} of ${formatBytes(progress.totalBytes ?: sizeBytes)}" +
                (fraction?.let { " · ${(it * 100).toInt()} %" } ?: "")
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SystemModelCard(model: SystemModel, state: ModelState?, onDownload: () -> Unit, onChat: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (state?.status) {
                    OnDeviceAiStatus.Unsupported -> Text(
                        "Not available on this device (or not enabled in system settings).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OnDeviceAiStatus.Downloading, OnDeviceAiStatus.Paused -> Text(
                        "The system is downloading the model.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Unit
                }
                state?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            when (state?.status) {
                null -> LoadingIndicator(Modifier.size(32.dp))
                // Gemini Nano: stahuje AICore, aplikace ho jen vyžádá (`download`).
                OnDeviceAiStatus.Downloadable -> FilledTonalButton(onClick = onDownload) { Text("Download") }
                OnDeviceAiStatus.Ready -> Button(onClick = onChat) { Text("Chat") }
                else -> Unit
            }
        }
    }
}
