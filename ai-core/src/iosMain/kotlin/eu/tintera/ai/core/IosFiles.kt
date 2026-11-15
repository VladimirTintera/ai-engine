@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package eu.tintera.ai.core

import co.touchlab.kermit.Logger
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

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

internal actual fun Path.lastModified(): Long {
    val date = NSFileManager.defaultManager.attributesOfItemAtPath(toString(), error = null)?.get(NSFileModificationDate) as? NSDate
    return date?.let { (it.timeIntervalSince1970 * 1000).toLong() } ?: 0L
}

/** CommonCrypto; kontext žije na nativní haldě, dokud [Sha256.hex] neskončí. */
internal actual fun sha256Digest(): Sha256 = object : Sha256 {
    private val context = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }

    override fun update(bytes: ByteArray, count: Int) {
        if (count == 0) return
        bytes.usePinned { CC_SHA256_Update(context.ptr, it.addressOf(0), count.convert()) }
    }

    override fun hex(): String {
        val hash = UByteArray(CC_SHA256_DIGEST_LENGTH)
        hash.usePinned { CC_SHA256_Final(it.addressOf(0), context.ptr) }
        nativeHeap.free(context)
        return hash.joinToString("") { it.toString(16).padStart(2, '0') }
    }
}

/** Kotlin/Native nemá `LinkageError`; chybějící knihovna se na iOS pozná už při linkování. */
internal actual fun isLinkageError(error: Throwable): Boolean = false

private fun Any?.asLong(): Long? = when (this) {
    is NSNumber -> longLongValue
    is Number -> toLong()
    else -> null
}
