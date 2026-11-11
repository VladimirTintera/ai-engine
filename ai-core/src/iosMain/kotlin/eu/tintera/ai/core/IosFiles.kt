@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package eu.tintera.ai.core

import kotlinx.io.files.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey

/**
 * Místo pro „důležité" stahování, o které požádal uživatel: iOS kvůli němu uvolní i keše jiných
 * aplikací, takže je ho víc než volného místa. Když ho systém neřekne, volné místo svazku; a když
 * ani to, nic se neodmítá — plný disk pak skončí chybou zápisu.
 */
internal actual fun usableSpace(dir: Path): Long {
    val important = NSURL.fileURLWithPath(dir.toString())
        .resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), error = null)
        ?.get(NSURLVolumeAvailableCapacityForImportantUsageKey)
        .asLong()
    if (important != null && important > 0) return important
    return NSFileManager.defaultManager.attributesOfFileSystemForPath(dir.toString(), error = null)
        ?.get(NSFileSystemFreeSize)
        .asLong()
        ?: Long.MAX_VALUE
}

/** Kotlin/Native nemá `LinkageError`; chybějící knihovna se na iOS pozná už při linkování. */
internal actual fun isLinkageError(error: Throwable): Boolean = false

private fun Any?.asLong(): Long? = when (this) {
    is NSNumber -> longLongValue
    is Number -> toLong()
    else -> null
}
