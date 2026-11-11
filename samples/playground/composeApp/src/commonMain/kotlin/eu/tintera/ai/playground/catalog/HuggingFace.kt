package eu.tintera.ai.playground.catalog

import eu.tintera.ai.core.huggingFaceUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Vlastní model z Hugging Face: z repozitáře (a volitelně revize) zjistí commit, soubory modelů
 * a jejich velikost přes `https://huggingface.co/api/models/{repo}/revision/{rev}`, a ověří, že
 * soubor jde stáhnout bez přihlášení — modely za souhlasem s licencí knihovna neumí.
 */
class HuggingFace(private val http: HttpClient) {

    /** Soubor modelu v repozitáři. */
    data class ModelFile(val path: String, val sizeBytes: Long)

    /** Co o repozitáři víme na zadané revizi. [commit] je plný SHA, na který se adresa pinne. */
    data class Repository(val repo: String, val commit: String, val license: String?, val files: List<ModelFile>)

    /** Selhání se srozumitelnou hláškou pro obrazovku. */
    class LookupException(message: String) : Exception(message)

    /** Repozitář na [revision] (`null` = výchozí větev) se soubory, které knihovna umí. */
    suspend fun repository(repo: String, revision: String?): Repository {
        val rev = revision?.takeIf { it.isNotBlank() } ?: "main"
        val response = runCatching {
            http.get("$BASE/api/models/$repo/revision/${rev.encodeURLPathPart()}?blobs=true")
        }.getOrElse { throw LookupException("Hugging Face is not reachable: ${it.message}") }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            // Bez přihlášení vrací Hugging Face 401 i pro repozitář, který neexistuje.
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound ->
                throw LookupException("Repository \"$repo\" or revision \"$rev\" not found, or it is private.")
            else -> throw LookupException("Hugging Face answered ${response.status.value}.")
        }
        val info = json.decodeFromString<ApiModel>(response.bodyAsText())
        val gated = info.gated?.content
        if (gated != null && gated != "false") {
            throw LookupException(
                "\"$repo\" is gated: it needs signing in and accepting its license on Hugging Face, " +
                    "which the library cannot do. Try a re-upload without gating (e.g. unsloth, bartowski).",
            )
        }
        val files = info.siblings
            .filter { runtimeForFile(it.rfilename) != null && !it.rfilename.contains("mmproj", ignoreCase = true) }
            .map { ModelFile(it.rfilename, it.size ?: 0L) }
            .sortedBy { it.path }
        return Repository(repo = info.id ?: repo, commit = info.sha, license = info.cardData?.license, files = files)
    }

    /**
     * Ověří, že [file] z [repo] na [commit] jde stáhnout bez přihlášení, a vrátí jeho velikost,
     * když ji API nedalo.
     */
    suspend fun checkDownload(repo: String, commit: String, file: String): Long? {
        val url = huggingFaceUrl(repo, commit, file)
        val response = runCatching { http.head(url) }
            .getOrElse { throw LookupException("Hugging Face is not reachable: ${it.message}") }
        return when (response.status) {
            HttpStatusCode.OK -> response.headers["Content-Length"]?.toLongOrNull()
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                throw LookupException("\"$file\" needs signing in to Hugging Face (gated or private), which the library cannot do.")
            HttpStatusCode.NotFound -> throw LookupException("\"$file\" does not exist in $repo at ${commit.take(7)}.")
            else -> throw LookupException("Download check failed: HTTP ${response.status.value}.")
        }
    }

    @Serializable
    private class ApiModel(
        val id: String? = null,
        val sha: String,
        // `false`, nebo "auto" / "manual"; v JSON boolean i řetězec, proto obecně.
        val gated: JsonPrimitive? = null,
        val cardData: CardData? = null,
        val siblings: List<Sibling> = emptyList(),
    )

    @Serializable
    private class CardData(val license: String? = null)

    @Serializable
    private class Sibling(val rfilename: String, val size: Long? = null)

    companion object {
        const val BASE = "https://huggingface.co"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Rozebere, co uživatel vložil: `owner/name`, nebo odkaz z prohlížeče
         * (`https://huggingface.co/owner/name/blob/<rev>/<soubor>`, `…/resolve/…`, `…/tree/<rev>`).
         * Vrací repozitář, revizi a soubor, co z toho v odkazu je.
         */
        fun parse(input: String): Triple<String, String?, String?>? {
            val text = input.trim().removeSuffix("/")
            val path = text.substringAfter("huggingface.co/", missingDelimiterValue = text)
                .substringBefore('?')
                .split('/')
                .filter { it.isNotEmpty() }
            if (path.size < 2) return null
            val repo = "${path[0]}/${path[1]}"
            if (path.size >= 4 && path[2] in setOf("blob", "resolve", "tree")) {
                val revision = path[3]
                val file = path.drop(4).joinToString("/").takeIf { it.isNotEmpty() }
                return Triple(repo, revision, file)
            }
            return Triple(repo, null, null)
        }
    }
}
