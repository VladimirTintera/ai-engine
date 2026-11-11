package eu.tintera.ai.playground

import eu.tintera.ai.core.DownloadedModel
import eu.tintera.ai.core.ModelRuntime
import eu.tintera.ai.core.huggingFaceUrl
import eu.tintera.ai.playground.catalog.CatalogVariant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Co playground ukládá: volbu „jen přes Wi-Fi" a vlastní modely z Hugging Face, ať přežijí
 * restart. Jeden malý JSON v adresáři dat aplikace; žádná databáze, víc toho není.
 */
class Storage(dataDir: String) {

    private val file = Path(dataDir, "playground.json")

    var state: Stored = load()
        private set

    fun update(change: (Stored) -> Stored) {
        state = change(state)
        runCatching {
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            val temp = Path("$file.tmp")
            SystemFileSystem.sink(temp).buffered().use { it.writeString(json.encodeToString(Stored.serializer(), state)) }
            SystemFileSystem.atomicMove(temp, file)
        }
    }

    private fun load(): Stored = runCatching {
        if (!SystemFileSystem.exists(file)) return Stored()
        json.decodeFromString(Stored.serializer(), SystemFileSystem.source(file).buffered().use { it.readString() })
    }.getOrDefault(Stored())

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}

@Serializable
data class Stored(
    val wifiOnly: Boolean = false,
    val customModels: List<CustomModel> = emptyList(),
)

/** Vlastní model z Hugging Face, pinnutý na commit jako modely z katalogu. */
@Serializable
data class CustomModel(
    val repo: String,
    val commit: String,
    val file: String,
    val sizeBytes: Long,
    val minMemoryGb: Int,
    val runtime: ModelRuntime,
    val license: String? = null,
) {
    /** Stálé id; v souboru je i repozitář, aby se dva stejně pojmenované soubory nepotkaly. */
    val id: String get() = "custom:$repo/$file@${commit.take(12)}"

    val variant: CatalogVariant
        get() = CatalogVariant(
            label = file.substringAfterLast('/'),
            source = "$repo @ ${commit.take(7)}",
            model = DownloadedModel(
                id = id,
                displayName = file.substringAfterLast('/').substringBeforeLast('.'),
                url = huggingFaceUrl(repo, commit, file),
                // Repozitář ve jménu souboru: stejně pojmenovaný soubor z jiného repa nebo commitu
                // je jiný model a nesmí přepsat ten první.
                fileName = "${repo.replace('/', '_')}_${commit.take(7)}_${file.replace('/', '_')}",
                sizeBytes = sizeBytes,
                minMemoryGb = minMemoryGb,
                runtime = runtime,
            ),
        )
}
