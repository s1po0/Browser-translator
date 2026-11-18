package dev.rojink.browser.translation

import java.util.Locale
import kotlin.math.abs

internal const val ENGLISH_COMIC_SCRIPT = "en-comic"
private val asciiWord = Regex("[a-zA-Z]+(?:[’'][a-zA-Z]+)?")
private val englishWords = setOf("the", "and", "you", "your", "yours", "you're", "you’re", "i", "i'm", "i’m", "we", "we're", "my", "our", "they",
    "is", "was", "were", "been", "have", "had", "don't", "don’t", "can't", "can’t", "what", "how", "where", "when", "who", "why", "will",
    "would", "could", "should", "this", "that", "with", "after", "before", "from", "please", "thanks", "yes", "hello")
private val plainShort = setOf("you", "go", "no", "hi", "ok", "yes", "hello", "wait", "stop", "sorry", "thanks")

internal fun englishProse(text: String): Boolean {
    if (!asciiLettering(text)) return false
    val tokens = words(text)
    return tokens.size >= 2 && tokens.any(englishWords::contains) || tokens.size == 1 && tokens.single() in plainShort
}

internal fun asciiLettering(text: String): Boolean {
    val ascii = text.count { it in 'A'..'Z' || it in 'a'..'z' }
    return ascii >= 2 && ascii * 10 >= text.count(Char::isLetter) * 9
}

private fun words(value: String) = asciiWord.findAll(value).map { it.value.lowercase(Locale.ROOT) }.toList()
private fun alphaText(value: String) = value.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)

internal fun useComicOcr(base: OcrPage, hint: OcrHint): Boolean {
    if (hint.explicit == ENGLISH_COMIC_SCRIPT) return true
    if (!hint.enhanced) return false
    if (hint.explicit !in setOf("auto", "latin") || base.script?.let { it != Script.LATIN } == true || base.rightFirst) return false
    val lang = hint.documentLanguage.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
    val letterLines = base.lines.filter { line -> line.confidence >= .52f && line.text.any(Char::isLetter) }
    if (letterLines.count { !asciiLettering(it.text) && it.text.count(Char::isLetter) >= 3 } * 4 > letterLines.size) return false
    return letterLines.any { englishProse(it.text) } || letterLines.isEmpty() && lang in setOf("en", "", "und")
}

/** Prefer the original word geometry; recover absent text and fix demonstrable spacing without double-painting. */
internal fun reconcileComicOcr(
    original: List<OcrLine>, alternative: List<OcrLine>, explicit: Boolean, lightBorder: (Box) -> Boolean,
): List<OcrLine> {
    // Never overwrite a price, literal or independently detected script with a second model's guess.
    val blocked = groupForImage(OcrPage(original, false)).protected
    val result = original.toMutableList()
    for (line in alternative.sortedByDescending(OcrLine::confidence)) {
        val text = line.text
        if (line.vertical || line.confidence < .78f || line.box.width < 8 || line.box.height < 6 || blocked.any { it.overlaps(line.box) }) continue
        val overlap = result.indices.filter { index ->
            val other = result[index].box
            abs(other.y - line.box.y) < maxOf(other.height, line.box.height) * .65f && areaOverlap(other, line.box) > .46
        }
        if (isProtectedImageLiteral(text)) {
            if (overlap.isEmpty() && line.confidence >= .89f && result.size < 500) result += line
            continue
        }
        if (!asciiLettering(text) || text.any(Char::isDigit) || imageLiteralSpans(text).isNotEmpty()) continue
        if (overlap.isEmpty()) {
            val passesSurface = explicit || lightBorder(line.box)
            // Thin overlapping guesses still conflict with the original layout; skip rather than risk two overlays.
            if (passesSurface && result.size < 500 && result.none { areaOverlap(it.box, line.box) > .39 }) result += line
            continue
        }
        if (overlap.size != 1) continue
        val index = overlap.single()
        val old = result[index]
        if (old.vertical || !asciiLettering(old.text) || old.text.any(Char::isDigit) || imageLiteralSpans(old.text).isNotEmpty()) continue
        val oldLetters = alphaText(old.text)
        val newLetters = alphaText(text)
        if (oldLetters == newLetters && (words(text).size > words(old.text).size ||
                line.confidence >= .88f && text.count { it in ".,!?:;…" } > old.text.count { it in ".,!?:;…" }) &&
            (explicit || englishProse(text))) {
            result[index] = old.copy(text = text) // Keep ML Kit word boxes and tight geometry.
        } else if (old.confidence < .67f && line.confidence >= .88f && newLetters.length >= oldLetters.length && englishProse(text) &&
            areaOverlap(old.box, line.box) > .70) {
            result[index] = old.copy(text = text, confidence = line.confidence)
        }
    }
    return result
}

internal fun comicNeighbors(a: OcrLine, b: OcrLine): Boolean {
    if (a.vertical || b.vertical || !asciiLettering(a.text) || !asciiLettering(b.text)) return false
    if (a.text.any(Char::isDigit) || b.text.any(Char::isDigit) || imageLiteralSpans(a.text).isNotEmpty() || imageLiteralSpans(b.text).isNotEmpty()) return false
    val height = minOf(a.box.height, b.box.height).coerceAtLeast(1)
    val width = minOf(a.box.width, b.box.width).coerceAtLeast(1)
    val gap = b.box.top - a.box.bottom
    val overlap = minOf(a.box.right, b.box.right) - maxOf(a.box.left, b.box.left)
    return b.box.y > a.box.y + height * .50f && gap > -height * .38f && gap < height * .88f &&
        maxOf(a.box.height, b.box.height) <= height * 1.65f && overlap > width * .62f &&
        abs(a.box.x - b.box.x) <= maxOf(height * .72f, width * .17f)
}

/** A leftover English pronoun/phrase is not a name to transliterate. Keep the test conservative. */
internal fun incompleteSorani(source: String, output: String, knownEnglish: Boolean): Boolean {
    if (output.isBlank()) return true
    val sequence = alphaText(source)
    if (sequence.length >= 2 && sequence == alphaText(output) && asciiLettering(source)) return true
    if (!knownEnglish && !englishProse(source)) return false
    val withoutLiterals = imageLiteralSpans(output).fold(output) { text, span -> text.replace(span, " ") }
    if (withoutLiterals.none { it in '\u0600'..'\u08ff' && it.isLetter() }) return true
    val from = words(source).toSet()
    return words(withoutLiterals).any { it in from && (it in englishWords && it.length > 1 || it == "i" || it in plainShort) }
}
