package dev.rojink.browser.translation

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Approximate enclosed flat backgrounds. They're proposals for OCR, never permission to erase the bubble. */
internal data class ComicBubble(val bounds: Box, val bands: List<Box>, val dark: Boolean) {
    fun contains(box: Box): Boolean = box.x in bounds.left until bounds.right && box.y in bounds.top until bounds.bottom &&
        box.intersection(bounds) >= box.width * box.height * .76f
}

internal fun comicBubbles(bitmap: Bitmap): List<ComicBubble> {
    val step = ceil(sqrt(bitmap.width.toDouble() * bitmap.height / 200_000)).toInt().coerceAtLeast(1)
    val w = (bitmap.width + step - 1) / step
    val h = (bitmap.height + step - 1) / step
    val luma = IntArray(w * h)
    val low = IntArray(luma.size)
    val high = IntArray(luma.size)
    val width = bitmap.width
    val rows = IntArray(width * step)
    // Average tones locate the enclosure. Extrema retain faint lettering that averaging a 4+ px grid would erase.
    for (y in 0 until h) {
        val py = y * step
        val count = minOf(step, bitmap.height - py)
        bitmap.getPixels(rows, 0, width, 0, py, width, count)
        for (x in 0 until w) {
            val start = x * step
            val end = minOf(start + step, width)
            var n = 0; var sum = 0; var lo = 255; var hi = 0
            for (i in 0 until count) for (px in start until end) {
                val p = rows[i * width + px]
                if (p ushr 24 < 240) continue
                val shade = (54 * ((p shr 16) and 255) + 183 * ((p shr 8) and 255) + 19 * (p and 255)) shr 8
                n++; sum += shade; lo = minOf(lo, shade); hi = maxOf(hi, shade)
            }
            val index = y * w + x
            luma[index] = if (n == 0 || n * 4 < count * (end - start) * 3) -1 else sum / n
            low[index] = lo; high[index] = hi
        }
    }
    return findComicBubbles(luma, w, h, bitmap.width, bitmap.height, low, high)
}

/** Four-connected interiors with enclosed glyph-like horizontal bands, in bounded scaled coordinates. */
internal fun findComicBubbles(
    luma: IntArray, w: Int, h: Int, imageWidth: Int, imageHeight: Int,
    low: IntArray = luma, high: IntArray = luma,
): List<ComicBubble> {
    require(luma.size == w * h && luma.size <= 230_000)
    val labels = IntArray(luma.size)
    val queue = IntArray(luma.size)
    val result = mutableListOf<ComicBubble>()
    var region = 0
    fun toImage(box: Box) = Box(
        floor(box.left.toDouble() * imageWidth / w).toInt(), floor(box.top.toDouble() * imageHeight / h).toInt(),
        ceil(box.right.toDouble() * imageWidth / w).toInt(), ceil(box.bottom.toDouble() * imageHeight / h).toInt(),
    )
    for (seed in luma.indices) {
        val tone = luma[seed]
        if (labels[seed] != 0 || tone < 0 || tone in 80..199) continue
        val dark = tone < 80
        region++
        labels[seed] = region
        queue[0] = seed
        var head = 0; var end = 1
        var left = w; var top = h; var right = 0; var bottom = 0
        fun visit(next: Int) {
            if (labels[next] != 0 || luma[next] < 0 || abs(luma[next] - tone) > 21) return
            labels[next] = region
            queue[end++] = next
        }
        while (head < end) {
            val index = queue[head++]
            val x = index % w; val y = index / w
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            if (x > 0) visit(index - 1)
            if (x < w - 1) visit(index + 1)
            if (y > 0) visit(index - w)
            if (y < h - 1) visit(index + w)
        }
        val box = Box(left, top, right, bottom)
        val area = box.width * box.height
        if (left == 0 || right == w || top == 0 || bottom == h || box.width < 12 || box.height < 8 ||
            end < maxOf(80, area / 2) || area > luma.size / 6 || box.width > 9 * box.height || box.height > 4 * box.width) continue
        val bands = mutableListOf<Box>()
        var band: Box? = null
        var inkCount = 0
        fun finish() {
            band?.let { span ->
                if (span.width >= 4 && inkCount >= 5 && span.height <= box.height * .46f) bands += toImage(span.expand(1, 1).clip(w, h))
            }
            band = null; inkCount = 0
        }
        for (y in top + 2 until bottom - 2) {
            val row = y * w
            var a = left; var b = right - 1
            while (a < b && labels[row + a] != region) a++
            while (b > a && labels[row + b] != region) b--
            if (b - a < box.width * .33f) continue
            var first = b; var last = a; var count = 0
            for (x in a + 2 until b - 1) {
                val index = row + x
                val shade = if (dark) high[index] else low[index]
                if (luma[index] < 0 || (if (dark) shade - tone else tone - shade) < 34) continue
                first = minOf(first, x); last = maxOf(last, x); count++
            }
            if (count == 0 || count * 5 > (b - a) * 4) continue
            if (band?.let { y - it.bottom > 1 } == true) finish()
            val span = Box(first, y, last + 1, y + 1)
            band = band?.union(span) ?: span
            inkCount += count
        }
        finish()
        if (bands.isNotEmpty() && bands.size <= 9) result += ComicBubble(toImage(box), bands, dark)
        if (result.size >= 96) break
    }
    return result
}

internal fun comicBubbleFor(box: Box, bubbles: List<ComicBubble>): ComicBubble? = bubbles.asSequence().filter { it.contains(box) }
    .minByOrNull { it.bounds.width * it.bounds.height }

/** Don't spend a second detection pass on a bubble whose ink bands are already represented. */
internal fun missedBubbleBands(bubble: ComicBubble, lines: List<OcrLine>): List<Box> = bubble.bands.filter { band ->
    lines.none { line -> line.confidence >= .70f && line.text.any(Char::isLetterOrDigit) &&
        line.box.intersection(band) >= band.width * band.height * .62f }
}
