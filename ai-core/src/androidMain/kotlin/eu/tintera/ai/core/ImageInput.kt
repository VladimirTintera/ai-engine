package eu.tintera.ai.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Obrázek pro Gemmu: PNG zmenšený tak, aby delší strana nepřesáhla zadanou mez — tak ho modelu
 * dává Google AI Edge Gallery (`Bitmap` → PNG do `Content.ImageBytes`).
 *
 * - **Dekóduje systém** (`BitmapFactory`), takže projde cokoli, co umí galerie: JPEG z Telegramu,
 *   PNG, WebP. Formát, který by si přečetla nativní strana LiteRT-LM, se tím neřeší.
 * - **Zmenšuje se už při dekódování** (`inSampleSize`) a pak přesně: originál fotky má i 2560 px
 *   a plný `Bitmap` by zbytečně držel desítky MB, kodér modelu si ho stejně zmenší.
 */
internal object ImageInput {

    /** PNG s delší stranou nejvýš [maxSide]; `null`, když soubor obrázek není. */
    fun png(path: String, maxSide: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null

        val longer = max(decoded.width, decoded.height)
        val bitmap = if (longer > maxSide) {
            val scale = maxSide.toFloat() / longer
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
