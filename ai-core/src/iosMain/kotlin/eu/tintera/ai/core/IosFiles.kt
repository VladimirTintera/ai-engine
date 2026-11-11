@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package eu.tintera.ai.core

import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.NSUserDomainMask

/**
 * Adresář stažených modelů: `Application Support/ai-models`, vyřazený ze zálohy do iCloudu.
 * Sdílí ho platforma ([IosLiteRtPlatform]) i stahování na pozadí ([IosModelDownloads]), které
 * hotový soubor ukládá i tehdy, když aplikaci spustil jen systém kvůli událostem sezení.
 */
internal val iosModelsDir: String by lazy { iosDirectory(NSApplicationSupportDirectory, "ai-models", backup = false) }

/** Adresář [name] v [base], založený; s [backup] = `false` vyřazený ze zálohy. */
internal fun iosDirectory(base: NSSearchPathDirectory, name: String, backup: Boolean): String {
    val root = NSFileManager.defaultManager.URLForDirectory(base, NSUserDomainMask, appropriateForURL = null, create = true, error = null)
        ?: error("No directory $base")
    val dir = root.URLByAppendingPathComponent(name, isDirectory = true) ?: error("No directory $name")
    NSFileManager.defaultManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
    if (!backup && !dir.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)) {
        Logger.withTag("OnDeviceAi").w { "Adresář $name se nepodařilo vyřadit ze zálohy" }
    }
    return dir.path ?: error("No path for $dir")
}

/** Stahování na pozadí má jen platforma z knihovny; vlastní `LiteRtPlatform` stahuje v popředí. */
internal actual fun modelFileDownloader(platform: LiteRtPlatform): ModelFileDownloader? =
    (platform as? IosLiteRtPlatform)?.fileDownloader

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
