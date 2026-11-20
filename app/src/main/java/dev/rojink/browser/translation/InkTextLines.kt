package dev.rojink.browser.translation

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.sqrt

private const val INK_TONE = 120
private const val PAPER_TONE = 198
private const val MAX_LINES = 24

/**
 * Dark lettering on a flat light interior. DB/ML Kit line models can skip burst or open bubbles
 * whose interior is not an enclosed contour, so the ink itself proposes the line boxes.
 * Pure pixel work: callers pass sampled tone rasters.
 */
internal fun inkLineBoxes(mean: IntArray, low: IntArray, w: Int, h: Int): List<Box> {
    require(mean.size == w * h && low.size == w * h)
    val labels = IntArray(w * h)
    val stack = IntArray(w * h)
    val parts = ArrayList<Box>(256)
    var tag = 0
    for (seed in 0 until w * h) {
        if (labels[seed] != 0 || low[seed] >= INK_TONE) continue
        tag++
        labels[seed] = tag
        stack[0] = seed
        var head = 0
        var end = 1
        var left = w
        var top = h
        var right = 0
        var bottom = 0
        while (head < end) {
            val index = stack[head++]
            val x = index % w
            val y = index / w
            if (x < left) left = x
            if (y < top) top = y
            if (x + 1 > right) right = x + 1
            if (y + 1 > bottom) bottom = y + 1
            if (x > 0) scanCell(labels, low, index - 1, tag, stack, end).let { end = it }
            if (x < w - 1) scanCell(labels, low, index + 1, tag, stack, end).let { end = it }
            if (y > 0) scanCell(labels, low, index - w, tag, stack, end).let { end = it }
            if (y < h - 1) scanCell(labels, low, index + w, tag, stack, end).let { end = it }
        }
        // A glyph fragment is small. Large dark masses are artwork, panels or borders.
        if (end >= 2 && end <= 2_400) parts += Box(left, top, right, bottom)
    }
    if (parts.isEmpty()) return emptyList()
    val lines = ArrayList<Box>(32)
    for (part in parts) {
        val height = part.height
        var target = -1
        for (i in lines.indices) {
            val line = lines[i]
            val overlap = minOf(line.bottom, part.bottom) - maxOf(line.top, part.top)
            val reach = maxOf(minOf(line.height, height), 2)
            val gap = maxOf(line.left - part.right, part.left - line.right)
            if (overlap * 2 >= minOf(line.height, height) && gap <= reach * 2) { target = i; break }
        }
        if (target < 0) lines += part else lines[target] = lines[target].union(part)
    }
    val result = ArrayList<Box>(MAX_LINES)
    for (line in lines) {
        if (result.size >= MAX_LINES) break
        val height = line.height
        val width = line.width
        if (height < 6 || height > 96 || width < 20 || width < height * 1.15f) continue
        var ink = 0
        var paper = 0
        for (y in line.top until line.bottom) {
            val row = y * w
            for (x in line.left until line.right) {
                if (low[row + x] < INK_TONE) ink++ else if (mean[row + x] >= PAPER_TONE) paper++
            }
        }
        val area = width * height
        // Lettering covers a small, regular share of its own box and sits on empty paper.
        if (ink * 100 < area * 4 || ink * 100 > area * 42 || paper * 100 < area * 38) continue
        val padY = (height / 2).coerceAtLeast(2)
        val padX = (height / 3).coerceAtLeast(2)
        val ring = line.expand(padX, padY).clip(w, h)
        var lit = 0
        var total = 0
        for (y in ring.top until ring.bottom) for (x in ring.left until ring.right) {
            if (x >= line.left && x < line.right && y >= line.top && y < line.bottom) continue
            total++
            if (mean[y * w + x] >= PAPER_TONE) lit++
        }
        if (total == 0 || lit * 100 < total * 45) continue
        result += line
    }
    return result.sortedWith(compareBy<Box> { it.top }.thenBy { it.left })
}

private fun scanCell(labels: IntArray, low: IntArray, index: Int, tag: Int, stack: IntArray, end: Int): Int {
    if (labels[index] != 0 || low[index] >= INK_TONE) return end
    labels[index] = tag
    stack[end] = index
    return end + 1
}

/** Sampled tone raster keeps the scan cheap on tall strips; boxes map back to source pixels. */
internal fun comicTextLines(bitmap: Bitmap): List<Box> {
    if (bitmap.width < 40 || bitmap.height < 40) return emptyList()
    val step = ceil(sqrt(bitmap.width.toDouble() * bitmap.height / 700_000)).toInt().coerceAtLeast(1)
    val w = (bitmap.width + step - 1) / step
    val h = (bitmap.height + step - 1) / step
    if (w < 8 || h < 8) return emptyList()
    val mean = IntArray(w * h) { -1 }
    val low = IntArray(w * h) { 255 }
    val rows = IntArray(bitmap.width * step)
    for (y in 0 until h) {
        val py = y * step
        val count = minOf(step, bitmap.height - py)
        bitmap.getPixels(rows, 0, bitmap.width, 0, py, bitmap.width, count)
        for (x in 0 until w) {
            val start = x * step
            val end = minOf(start + step, bitmap.width)
            var n = 0
            var sum = 0
            var lo = 255
            for (i in 0 until count) {
                val row = i * bitmap.width
                for (px in start until end) {
                    val p = rows[row + px]
                    if (p ushr 24 < 240) continue
                    val shade = (54 * ((p shr 16) and 255) + 183 * ((p shr 8) and 255) + 19 * (p and 255)) shr 8
                    n++
                    sum += shade
                    if (shade < lo) lo = shade
                }
            }
            if (n > 0) { mean[y * w + x] = sum / n; low[y * w + x] = lo }
        }
    }
    return inkLineBoxes(mean, low, w, h).map { cell ->
        Box(cell.left * step, cell.top * step, cell.right * step, cell.bottom * step)
            .expand(step - 1, step - 1).clip(bitmap.width, bitmap.height)
    }
}
