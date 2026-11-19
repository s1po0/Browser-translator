package dev.rojink.browser.translation

import java.util.Locale

private val apostropheInk = Regex("""(?<=[A-Za-z])[ \t]*['‘’`´ʼ][ \t]*(?=[A-Za-z])""")
internal fun normalizeLatinApostrophes(text: String): String = apostropheInk.replace(text, "'")

private val spaces = Regex("\\s+")
private fun inkKey(value: String) = normalizeLatinApostrophes(value).lowercase(Locale.ROOT)
    .trim().trimEnd('.', ',').replace(spaces, " ")

internal fun sameLatinInk(a: String, b: String): Boolean = inkKey(a) == inkKey(b)

private val splitU = Regex("(?i)[a-z][il]{2}[a-z]{2}")
internal fun hasSplitUShape(text: String): Boolean = splitU.containsMatchIn(text)

internal enum class LatinInkChange { FRACTURED_U, APOSTROPHE }

/** Compare readings of the same ink, not a spelling guess. Only these local glyph differences may override a strong line. */
internal fun latinInkChange(before: String, after: String): LatinInkChange? {
    if (before.length > 280 || after.length > 280 || !asciiLettering(before) || !asciiLettering(after)) return null
    val old = inkKey(before)
    val new = inkKey(after)
    if (old == new) return null
    val start = (0 until minOf(old.length, new.length)).firstOrNull { old[it] != new[it] } ?: return null
    if (start < 1 || new[start - 1] !in 'a'..'z') return null
    if (new[start] == '\'' && new.getOrNull(start + 1) == 's' && new.getOrNull(start + 2) !in 'a'..'z' &&
        (new.removeRange(start, start + 1) == old || new.replaceRange(start, start + 1, " ") == old)) return LatinInkChange.APOSTROPHE
    if (new[start] != 'u' || new.getOrNull(start + 1) !in 'a'..'z') return null
    val end = old.length - (new.length - start - 1)
    return if (end - start in 1..2 && old.substring(start, end).all { it == 'i' || it == 'l' } &&
        old.regionMatches(end, new, start + 1, old.length - end)) LatinInkChange.FRACTURED_U else null
}

/** Require corroboration before a glyph-only alternative can replace a confident reading. */
internal fun verifyLatinInk(tiny: PaddleText, small: PaddleText, base: OcrLine?, retake: () -> PaddleText?): Boolean {
    if (small.confidence < .91f || imageLiteralSpans(small.value).isNotEmpty()) return false
    val nearby = base?.takeUnless(OcrLine::vertical)
    val tinyChange = latinInkChange(tiny.value, small.value)
    if (tinyChange == null && nearby?.let { latinInkChange(it.text, small.value) } == null) return false
    if (tiny.confidence >= .87f && sameLatinInk(tiny.value, small.value) ||
        nearby?.let { it.confidence >= .76f && sameLatinInk(it.text, small.value) } == true) return true
    // An alternative apostrophe needs agreement from both readers, not only two crops run through Small.
    if (tinyChange != LatinInkChange.FRACTURED_U || !(tiny.uncertainU || hasSplitUShape(tiny.value) || tiny.confidence < .88f)) return false
    val tight = retake()
    return tight != null && tight.confidence >= .91f && sameLatinInk(tight.value, small.value)
}

/** Fit a second OCR sample around the same letter pixels; never update the renderer's geometry. */
internal fun paddedInkCrop(outer: Box, ink: Box): Box? {
    val pad = (ink.height * .12f + .5f).toInt().coerceAtLeast(1)
    val box = Box(maxOf(outer.left, ink.left - pad), maxOf(outer.top, ink.top - pad),
        minOf(outer.right, ink.right + pad), minOf(outer.bottom, ink.bottom + pad))
    return box.takeIf { it.width >= 6 && it.height >= 5 && it.width * it.height <= outer.width * outer.height * .91f }
}
