package eu.tintera.ai.core

import kotlinx.io.IOException
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
