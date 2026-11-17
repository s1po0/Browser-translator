package dev.rojink.browser.translation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
    fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    fun moved(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)
    fun expand(x: Int, y: Int) = Box(left - x, top - y, right + x, bottom + y)
    fun clip(w: Int, h: Int) = Box(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))
    fun intersection(other: Box) = (min(right, other.right) - max(left, other.left)).coerceAtLeast(0) *
        (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0)
    fun overlaps(other: Box) = intersection(other) > 0
}
internal data class OcrWord(val text: String, val box: Box)
internal data class OcrLine(val text: String, val box: Box, val words: List<OcrWord>, val block: Int, val vertical: Boolean = false, val confidence: Float = .70f)
internal data class OcrPage(val lines: List<OcrLine>, val rightFirst: Boolean, val enhancedTiles: Int = 0)
internal data class TextUnit(val text: String, val box: Box, val lines: List<Box>, val sizeHint: Int, val vertical: Boolean)
internal data class ImageLayout(val units: List<TextUnit>, val protected: List<Box>)

private val numeric = Regex("^(?:(?:USD|EUR|IQD|JPY|CNY|KRW|TRY|SAR|GBP)\\s*|[\\p{Sc}]\\s*)?[\\p{Nd}][\\p{Nd}.,٬٫: /\\-]*(?:\\s*(?:USD|EUR|IQD|JPY|CNY|KRW|TRY|SAR|GBP|[\\p{Sc}%٪]))?$", RegexOption.IGNORE_CASE)
private fun isNumeric(text: String) = numeric.matches(text.trim())

/** ML Kit blocks are hints, not a promise that two adjacent lines share one speech bubble. */
internal fun groupForImage(page: OcrPage): ImageLayout {
    val protected = mutableListOf<Box>()
    val split = page.lines.flatMap { line ->
        if (isNumeric(line.text)) { protected += line.box; emptyList() }
        else splitMenuLine(line, protected)
    }.filter { item -> item.box.width >= 4 && item.box.height >= 5 && item.text.any(Char::isLetter) }
    val groups = mutableListOf<MutableList<OcrLine>>()
    for (line in split.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { if (page.rightFirst) -it.box.right else it.box.left }).take(320)) {
        val matching = groups.asReversed().firstOrNull { g ->
            g.size < 8 && g.any { canGroup(it, line) } && !g.any { a ->
                val combined = a.box.union(line.box)
                protected.any { it.overlaps(combined) }
            }
        }
        if (matching == null) groups += mutableListOf(line) else matching += line
    }
    val units = groups.map { g ->
        val vertical = g.first().vertical
        val lines = if (vertical) g.sortedWith(compareBy<OcrLine> { -it.box.right }.thenBy { it.box.top }) else g.sortedBy { it.box.top }
        val ties = if (vertical) "" else " "
        TextUnit(lines.joinToString(ties) { it.text.trim() }, lines.map { it.box }.reduce(Box::union), lines.map { it.box },
            lines.map { if (vertical) it.box.width else it.box.height }.sorted()[lines.size / 2], vertical)
    }
    val rowHeight = units.map { it.sizeHint }.sorted().let { if (it.isEmpty()) 30 else (it[it.size / 2] * 2).coerceIn(24, 120) }
    return ImageLayout(units.sortedWith(compareBy<TextUnit> { it.box.top / rowHeight }.thenBy { if (page.rightFirst) -it.box.right else it.box.left }.thenBy { it.box.top }), protected)
}

private fun splitMenuLine(line: OcrLine, protected: MutableList<Box>): List<OcrLine> {
    val words = line.words
    if (line.vertical || words.size < 2) return listOf(line)
    var end = words.size
    for (count in minOf(3, words.size - 1) downTo 1) {
        val tail = words.takeLast(count)
        val price = tail.joinToString(" ") { it.text }
        val gap = tail.first().box.left - words[words.size - count - 1].box.right
        if (isNumeric(price) && (gap > line.box.height * .66f || price.any { Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() })) {
            protected += tail.map { it.box }.reduce(Box::union)
            end -= count
            break
        }
    }
    val result = mutableListOf<OcrLine>()
    val parts = mutableListOf<OcrWord>()
    fun collect() {
        if (parts.isEmpty()) return
        val candidate = parts.joinToString(" ") { it.text }
        val box = parts.map { it.box }.reduce(Box::union)
        if (isNumeric(candidate)) protected += box
        else result += line.copy(text = candidate, box = box, words = parts.toList())
        parts.clear()
    }
    for (i in 0 until end) {
        if (i > 0 && words[i].box.left - words[i - 1].box.right > line.box.height * 1.38f) collect()
        parts += words[i]
    }
    collect()
    if (result.size == 1 && end == words.size) return listOf(line)
    return result
}

private fun canGroup(a: OcrLine, b: OcrLine): Boolean {
    if (a.vertical != b.vertical) return false
    if (a.vertical) {
        val scale = min(a.box.width, b.box.width).coerceAtLeast(1)
        val xGap = max(a.box.left, b.box.left) - min(a.box.right, b.box.right)
        val yGap = max(a.box.top, b.box.top) - min(a.box.bottom, b.box.bottom)
        return (abs(a.box.x - b.box.x) < scale * .8 && yGap in -2..(scale / 2)) ||
            (a.block == b.block && xGap in -2..scale && yGap < -scale / 2)
    }
    if (a.block != b.block) return false
    val median = min(a.box.height, b.box.height).coerceAtLeast(1)
    val verticalGap = max(a.box.top, b.box.top) - min(a.box.bottom, b.box.bottom)
    val overlap = min(a.box.right, b.box.right) - max(a.box.left, b.box.left)
    val smaller = min(a.box.width, b.box.width).coerceAtLeast(1)
    return verticalGap > -median * .45f && verticalGap < median * .65f && overlap > smaller * .38f &&
        max(a.box.height, b.box.height) < median * 1.7f
}
