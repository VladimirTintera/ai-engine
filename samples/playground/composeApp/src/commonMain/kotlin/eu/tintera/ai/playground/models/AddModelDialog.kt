package eu.tintera.ai.playground.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.tintera.ai.playground.CustomModel
import eu.tintera.ai.playground.Playground
import eu.tintera.ai.playground.catalog.HuggingFace
import eu.tintera.ai.playground.catalog.runtimeForFile
import eu.tintera.ai.playground.catalog.suggestedMinMemoryGb
import eu.tintera.ai.playground.formatBytes
import eu.tintera.ai.playground.label
import kotlinx.coroutines.launch

/**
 * Přidání libovolného modelu z Hugging Face: repozitář (nebo odkaz), volitelně revize a soubor.
 * „Look up" zjistí commit a soubory modelů s velikostí, „Add" ověří, že soubor jde stáhnout bez
 * přihlášení, a model uloží (přežije restart).
 */
@Composable
fun AddModelDialog(playground: Playground, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf<HuggingFace.Repository?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun lookUp() {
        val parsed = HuggingFace.parse(input)
        if (parsed == null) {
            error = "Enter a repository as owner/name, or paste a link from huggingface.co."
            return
        }
        val (repo, linkRevision, linkFile) = parsed
        if (linkRevision != null && revision.isBlank()) revision = linkRevision
        busy = true
        error = null
        scope.launch {
            try {
                val found = playground.huggingFace.repository(repo, revision)
                repository = found
                selected = linkFile?.takeIf { file -> found.files.any { it.path == file } } ?: found.files.singleOrNull()?.path
                if (found.files.isEmpty()) error = "No .gguf or .litertlm files in $repo."
            } catch (e: HuggingFace.LookupException) {
                repository = null
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun add() {
        val found = repository ?: return
        val file = found.files.firstOrNull { it.path == selected } ?: return
        val runtime = runtimeForFile(file.path) ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val size = playground.huggingFace.checkDownload(found.repo, found.commit, file.path)
                val sizeBytes = file.sizeBytes.takeIf { it > 0 } ?: size ?: 0L
                playground.models.addCustom(
                    CustomModel(
                        repo = found.repo,
                        commit = found.commit,
                        file = file.path,
                        sizeBytes = sizeBytes,
                        minMemoryGb = suggestedMinMemoryGb(sizeBytes),
                        runtime = runtime,
                        license = found.license,
                    ),
                )
                onDismiss()
            } catch (e: HuggingFace.LookupException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model from Hugging Face") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; repository = null; error = null },
                    label = { Text("Repository or link") },
                    placeholder = { Text("unsloth/Qwen3-0.6B-GGUF") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = revision,
                    onValueChange = { revision = it; repository = null; error = null },
                    label = { Text("Revision (optional)") },
                    placeholder = { Text("main, a branch or a commit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                repository?.let { found ->
                    Text(
                        "Commit ${found.commit.take(7)}" + (found.license?.let { " · license: $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        for (file in found.files) {
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = file.path }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == file.path, onClick = { selected = file.path })
                                Column {
                                    Text(file.path, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${formatBytes(file.sizeBytes)} · ${runtimeForFile(file.path)?.label}" +
                                            " · min. ${suggestedMinMemoryGb(file.sizeBytes)} GB RAM",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (busy) LoadingIndicator(Modifier.size(40.dp).align(Alignment.CenterHorizontally))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (repository == null) {
                TextButton(onClick = ::lookUp, enabled = !busy && input.isNotBlank()) { Text("Look up") }
            } else {
                TextButton(onClick = ::add, enabled = !busy && selected != null) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
