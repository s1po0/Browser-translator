package dev.rojink.browser.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A bounded second look for small or faded letters. Its pixels are never used for the final artwork. */
internal fun clearerOcrBitmap(input: Bitmap): Bitmap? {
    val w = input.width
    val h = input.height
    val row = IntArray(w)
    val histogram = IntArray(256)
    var samples = 0
    for (y in 0 until h step max(1, h / 112)) {
        input.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w step max(1, w / 220)) {
            val p = row[x]
            val gray = ((p shr 16 and 255) * 54 + (p shr 8 and 255) * 183 + (p and 255) * 19) / 256
            val alpha = p ushr 24
            histogram[(gray * alpha + 255 * (255 - alpha)) / 255]++
            samples++
        }
    }
    fun quantile(fraction: Double): Int {
        val index = (samples * fraction).toInt().coerceIn(0, samples - 1)
        var count = 0
        for (i in histogram.indices) { count += histogram[i]; if (count > index) return i }
        return 255
    }
    var low = quantile(.005)
    var high = quantile(.995)
    if (high - low < 13) { low = quantile(0.0); high = quantile(1.0) }
    if (high - low < 5) return null
    val gain = (212f / (high - low)).coerceIn(1.0f, 5.0f)
    val invert = quantile(.5) <= 111
    val scale = minOf(2.16, 1880.0 / max(w, h), sqrt(2_350_000.0 / (w.toLong() * h)))
    val width = (w * scale).roundToInt().coerceAtLeast(1)
    val height = (h * scale).roundToInt().coerceAtLeast(1)
    val sign = if (invert) -gain else gain
    val shift = if (invert) 235f + gain * low else 18f - gain * low
    val r = .2126f * sign; val g = .7152f * sign; val b = .0722f * sign
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(floatArrayOf(r,g,b,0f,shift, r,g,b,0f,shift, r,g,b,0f,shift, 0f,0f,0f,1f,0f))
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(input, null, Rect(0, 0, width, height), paint) }
    }
}
