package dev.rojink.browser.translation

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Approximate enclosed flat backgrounds. They're proposals for OCR, never permission to erase the bubble. */
internal data class ComicBubble(val bounds: Box, val bands: List<Box>, val dark: Boolean, val contour: ComicContour? = null) {
    fun contains(box: Box): Boolean = box.x in bounds.left until bounds.right && box.y in bounds.top until bounds.bottom &&
        box.intersection(bounds) >= box.width * box.height * .76f
}

/** The filled cross-sections of a dense connected interior, at the proposal scan scale. */
internal class ComicContour(private val top: Int, private val step: Int, private val spans: IntArray, private val tone: Int) {
    private val bottom get() = top + spans.size / 2 * step

    fun fits(box: Box): Boolean {
        if (box.width <= 0 || box.height <= 0 || box.top < top || box.bottom > bottom) return false
        for (i in (box.top - top) / step..(box.bottom - 1 - top) / step) {
            if (box.left < spans[i * 2] || box.right > spans[i * 2 + 1]) return false
        }
        return true
    }

    // Ignore normal detector padding along the edges, not a box that actually straddles the outline.
    fun containsInk(box: Box): Boolean = fits(box.expand(-box.width / 9, -box.height / 5))

    /** Pixels outside the contour are neutralized only in a temporary OCR crop. */
    fun maskedCrop(image: Bitmap, area: Box): Bitmap? {
        val size = area.width.toLong() * area.height
        if (size !in 1L..720_000L || area.clip(image.width, image.height) != area) return null
        val blank = 0xff000000.toInt() or (tone * 0x010101)
        val pixels = IntArray(size.toInt()) { blank }
        for (y in maxOf(top, area.top) until minOf(bottom, area.bottom)) {
            val index = (y - top) / step * 2
            val from = maxOf(spans[index], area.left)
            val to = minOf(spans[index + 1], area.right)
            if (from < to) image.getPixels(pixels, (y - area.top) * area.width + from - area.left, area.width, from, y, to - from, 1)
        }
        return Bitmap.createBitmap(pixels, area.width, area.height, Bitmap.Config.ARGB_8888)
    }
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
    return findComicBubbles(luma, w, h, bitmap.width, bitmap.height, low, high, step)
}

/** Four-connected interiors with enclosed glyph-like horizontal bands, in bounded scaled coordinates. */
internal fun findComicBubbles(
    luma: IntArray, w: Int, h: Int, imageWidth: Int, imageHeight: Int,
    low: IntArray = luma, high: IntArray = luma, step: Int = (imageWidth + w - 1) / w,
): List<ComicBubble> {
    require(luma.size == w * h && luma.size <= 230_000)
    val labels = IntArray(luma.size)
    val queue = IntArray(luma.size)
    val result = mutableListOf<ComicBubble>()
    var region = 0
    fun toImage(box: Box) = Box(box.left * step, box.top * step, box.right * step, box.bottom * step).clip(imageWidth, imageHeight)
    for (seed in luma.indices) {
        val tone = luma[seed]
        if (labels[seed] != 0 || tone < 0 || tone in 80..199) continue
        val dark = tone < 80
        // At a different strip offset, a mixed dark rule cell can seed a flood into a lighter bubble.
        // Keep darker fills local in both directions, including the case where the rule is the seed.
        val tolerance = if (dark) 10 else 21
        if (dark && low[seed] < tone - 17) continue
        region++
        labels[seed] = region
        queue[0] = seed
        var head = 0; var end = 1
        var left = w; var top = h; var right = 0; var bottom = 0
        fun visit(next: Int) {
            if (labels[next] != 0 || luma[next] < 0 || abs(luma[next] - tone) > tolerance || dark && low[next] < tone - 17) return
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
        val spans = IntArray(box.height * 2)
        var filled = 0
        for (y in top until bottom) {
            val row = y * w
            var a = left; var b = right - 1
            while (a < b && labels[row + a] != region) a++
            while (b > a && labels[row + b] != region) b--
            val i = (y - top) * 2
            spans[i] = a * step
            spans[i + 1] = minOf((b + 1) * step, imageWidth)
            filled += b - a + 1
            if (y < top + 2 || y >= bottom - 2 || b - a < box.width * .33f) continue
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
        if (bands.isNotEmpty() && bands.size <= 9) {
            // Fill holes from letter strokes. A concave or fragmented shape with large holes
            // is still a recovery proposal, but not evidence for masking or joining distant lines.
            val contour = ComicContour(top * step, step, spans, tone).takeIf { end * 5 >= filled * 4 }
            result += ComicBubble(toImage(box), bands, dark, contour)
        }
        if (result.size >= 96) break
    }
    return result
}

internal fun comicBubbleFor(box: Box, bubbles: List<ComicBubble>): ComicBubble? = bubbles.asSequence().filter { it.contains(box) }
    .minByOrNull { it.bounds.width * it.bounds.height }

/** Don't spend a second detection pass on a bubble whose ink bands are already represented. */
internal fun missedBubbleBands(bubble: ComicBubble, lines: List<OcrLine>): List<Box> = bubble.bands.filter { band ->
    val slack = (band.height / 5).coerceIn(1, 3)
    // Ink bands are padded and may span closely spaced lines. Test vertical union of broad readings,
    // not a sum of overlapping OCR boxes or a high-confidence stub such as just "ONG.".
    val spans = lines.asSequence().filter { line ->
        line.confidence >= .70f && !line.vertical && (bubble.contour?.containsInk(line.box) != false) &&
            minOf(line.box.right, band.right) - maxOf(line.box.left, band.left) >= band.width * .66f &&
            line.box.top < band.bottom && line.box.bottom > band.top && line.text.any(Char::isLetterOrDigit)
    }.map { maxOf(band.top, it.box.top - slack) to minOf(band.bottom, it.box.bottom + slack) }.sortedBy { it.first }
    var edge = band.top
    var covered = 0
    for ((top, bottom) in spans) {
        covered += (bottom - maxOf(edge, top)).coerceAtLeast(0)
        edge = maxOf(edge, bottom)
    }
    covered < band.height * .62f
}
