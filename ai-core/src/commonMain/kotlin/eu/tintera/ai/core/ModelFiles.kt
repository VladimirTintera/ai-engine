package eu.tintera.ai.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Soubory stažených modelů ve společném kódu ([LiteRtOnDeviceAi]) přes kotlinx-io. Chovají se jako
 * `java.io.File`, který tu dřív byl: chybějící soubor má velikost 0, mazání chybějícího nic nedělá.
 */
internal fun Path.exists(): Boolean = SystemFileSystem.exists(this)

/** Velikost souboru v bajtech; 0, když neexistuje. */
internal fun Path.size(): Long = SystemFileSystem.metadataOrNull(this)?.size ?: 0L

internal fun Path.delete() {
    SystemFileSystem.delete(this, mustExist = false)
}

internal fun Path.mkdirs() {
    SystemFileSystem.createDirectories(this)
}

/** Jména souborů v adresáři; prázdné, když adresář není. */
internal fun Path.listNames(): List<String> =
    if (exists()) SystemFileSystem.list(this).map { it.name } else emptyList()

/** Přejmenuje soubor na [target] (přepíše ho). Selhání vyhodí [IOException]. */
internal fun Path.moveTo(target: Path) {
    SystemFileSystem.atomicMove(this, target)
}

/** Čas poslední změny souboru v milisekundách od 1970; 0, když neexistuje. kotlinx-io ho neumí. */
internal expect fun Path.lastModified(): Long

/** Průběžný výpočet SHA-256 knihovnou platformy (`MessageDigest`, CommonCrypto). */
internal interface Sha256 {
    fun update(bytes: ByteArray, count: Int)

    /** Hash malými hexa číslicemi; pak už se nepoužívá. */
    fun hex(): String
}

internal expect fun sha256Digest(): Sha256

/** SHA-256 souboru malými hexa číslicemi. Gigabajty trvají sekundy, proto jde zrušit. */
internal suspend fun Path.sha256(): String {
    val context = currentCoroutineContext()
    val digest = sha256Digest()
    SystemFileSystem.source(this).buffered().use { input ->
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            context.ensureActive()
            val read = input.readAtMostTo(buffer)
            if (read < 0) break
            digest.update(buffer, read)
        }
    }
    return digest.hex()
}

private const val HASH_BUFFER_BYTES = 256 * 1024

/**
 * Kolik bajtů smí aplikace v adresáři [dir] ještě zapsat. Na iOS to, co systém uvolní pro
 * „důležité" stahování, o které uživatel požádal (uklidí kvůli němu i keše jiných aplikací).
 */
internal expect fun usableSpace(dir: Path): Long

/**
 * Byla [error] chyba načtení nativní knihovny (`LinkageError` na JVM)? Je to `Error`, ne
 * výjimka, a aplikace kvůli volitelné funkci spadnout nesmí — [LiteRtOnDeviceAi] ho převede na
 * [IOException].
 */
internal expect fun isLinkageError(error: Throwable): Boolean
