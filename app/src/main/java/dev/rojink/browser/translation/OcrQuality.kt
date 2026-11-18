package dev.rojink.browser.translation

import kotlin.math.max
import kotlin.math.min

internal const val ENGLISH_IMAGE_LANGUAGE = "en"
internal enum class Script { LATIN, JAPANESE, KOREAN, CHINESE, DEVANAGARI }

private fun Char.matches(script: Script): Boolean {
    val han = this in '\u3400'..'\u9fff' || this in '\uf900'..'\ufaff'
    return when (script) {
        Script.JAPANESE -> han || this in '\u3041'..'\u30ff' || this in '\uff66'..'\uff9f'
        Script.CHINESE -> han
        Script.KOREAN -> this in '\uac00'..'\ud7af' || this in '\u1100'..'\u11ff' || this in '\u3130'..'\u318f'
        Script.DEVANAGARI -> this in '\u0900'..'\u097f'
        Script.LATIN -> this in 'a'..'z' || this in 'A'..'Z' || this in '\u00c0'..'\u024f'
    } && isLetter()
}

internal fun scriptLetters(lines: List<OcrLine>, script: Script): Int = lines.sumOf { line -> line.text.count { it.matches(script) } }

internal fun ocrEvidence(lines: List<OcrLine>, script: Script): Double = lines.sumOf { line ->
    val all = line.text.count(Char::isLetter)
    if (all == 0 || line.box.width < 3 || line.box.height < 4) return@sumOf 0.0
    val native = line.text.count { it.matches(script) }
    val confidence = line.confidence.toDouble().coerceIn(0.0, 1.0)
    // CJK glyphs carry more information per character; repeated low-confidence Latin noise should not win.
    val scriptWeight = if (script == Script.LATIN) 1.0 else 2.2
    scriptWeight * native.coerceAtMost(44) * (0.10 + .90 * confidence * confidence) * (.30 + .70 * native / all)
}

internal fun needsOcrDetail(lines: List<OcrLine>, script: Script): Boolean {
    val candidate = lines.filter { it.text.any { glyph -> glyph.matches(script) } }
    if (candidate.isEmpty() || ocrEvidence(candidate, script) < 8) return true
    return candidate.any { it.confidence < .56f || (if (it.vertical) it.box.width else it.box.height) < 17 }
}

internal fun ocrTiles(width: Int, height: Int): List<Box> {
    if (height <= 2080 && width <= 2080) return listOf(Box(0, 0, width, height))
    val edge = 1640
    val overlap = 190
    fun offsets(length: Int): List<Int> {
        if (length <= edge) return listOf(0)
        val end = length - edge
        return buildList {
            var start = 0
            while (start < end) { add(start); start = min(start + edge - overlap, end) }
            add(end)
        }
    }
    return offsets(height).flatMap { y -> offsets(width).map { x -> Box(x, y, min(x + edge, width), min(y + edge, height)) } }
}

internal fun areaOverlap(a: Box, b: Box): Double = a.intersection(b).toDouble() / max(1, min(a.width * a.height, b.width * b.height))

internal fun mergeOcrLines(target: MutableList<OcrLine>, incoming: List<OcrLine>, minConfidence: Float = 0f) {
    for (line in incoming) {
        if (line.confidence < minConfidence || line.text.isBlank() || line.box.width < 3 || line.box.height < 4) continue
        val overlap = target.indexOfFirst { prev ->
            if (prev.vertical != line.vertical) return@indexOfFirst false
            val area = areaOverlap(prev.box, line.box)
            area > .84 || (area > .56 && (prev.text.contains(line.text, true) || line.text.contains(prev.text, true)))
        }
        if (overlap < 0) {
            if (target.size < 500) target += line
        } else {
            val previous = target[overlap]
            val fuller = line.text.length > previous.text.length && line.text.contains(previous.text, true)
            if ((fuller && line.confidence >= previous.confidence - .14f) ||
                (line.confidence > previous.confidence + .13f && line.text.length >= previous.text.length * .8)) target[overlap] = line
        }
    }
}
