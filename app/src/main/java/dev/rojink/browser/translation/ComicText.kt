package dev.rojink.browser.translation

import java.util.Locale
import kotlin.math.abs

internal const val ENGLISH_COMIC_SCRIPT = "en-comic"
private val asciiWord = Regex("[a-zA-Z]+(?:[’'][a-zA-Z]+)?")
private val englishWords = setOf("the", "and", "you", "your", "yours", "you're", "you’re", "i", "i'm", "i’m", "we", "we're", "my", "our", "they",
    "is", "was", "were", "been", "have", "had", "don't", "don’t", "can't", "can’t", "what", "how", "where", "when", "who", "why", "will",
    "would", "could", "should", "this", "that", "with", "after", "before", "from", "please", "thanks", "yes", "hello")
private val joinedDialogueWords = setOf("iam", "itook", "iwent", "ihave", "ican", "youcan", "youare", "itis", "itwas", "shouldwe", "areyou", "rightnow")
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
private fun shortLetter(value: String) = alphaText(value) in setOf("a", "i") && value.firstOrNull() in listOf('A', 'I')
internal fun englishLettering(value: String) = asciiLettering(value) || shortLetter(value)
internal fun reliableEnglish(value: String): Boolean {
    if (!asciiLettering(value)) return false
    val tokens = words(value)
    return tokens.size >= 3 && tokens.distinct().count { it in englishWords } >= 2
}

internal fun needsLatinReview(tiny: PaddleText, base: OcrLine?, enclosed: Boolean, recovered: Boolean): Boolean {
    val text = tiny.value
    val grounded = enclosed || base?.let { !it.vertical && englishLettering(it.text) } == true || englishProse(text) ||
        words(text).any(joinedDialogueWords::contains)
    if (!grounded || tiny.confidence < .28f && !recovered || isProtectedImageLiteral(text)) return false
    return recovered && tiny.confidence < .92f || tiny.confidence < .88f || tiny.uncertainU ||
        (enclosed && hasSplitUShape(text) && tiny.confidence < .985f) ||
        words(text).any { it.length >= 13 || it in joinedDialogueWords } ||
        base?.let { !it.vertical && englishLettering(it.text) && it.text.trim() != text.trim() &&
            (alphaText(it.text) != alphaText(text) || words(it.text).size > words(text).size ||
                latinInkChange(it.text, text) == LatinInkChange.APOSTROPHE || latinInkChange(text, it.text) == LatinInkChange.APOSTROPHE) } == true
}

internal fun preferLatinReview(tiny: PaddleText, small: PaddleText, base: OcrLine?): Boolean {
    val after = small.value
    if (!englishLettering(after) && !isProtectedImageLiteral(after)) return false
    if (small.confidence < if (shortLetter(after)) .76f else .86f) return false
    val before = tiny.value
    if (before == after) return false
    if (base?.text?.equals(after, ignoreCase = true) == true) return true
    if (alphaText(before) == alphaText(after) && words(after).size > words(before).size) return true
    return (tiny.confidence < .83f || !englishLettering(before)) &&
        (englishProse(after) || shortLetter(after) || after.length >= 4 && small.confidence >= .93f) &&
        (shortLetter(after) || alphaText(after).length >= alphaText(before).length * .70f)
}

internal fun useComicOcr(base: OcrPage, hint: OcrHint): Boolean {
    if (hint.explicit == ENGLISH_COMIC_SCRIPT) return true
    if (!hint.enhanced) return false
    if (hint.explicit !in setOf("auto", "latin") || base.script?.let { it != Script.LATIN } == true || base.rightFirst) return false
    val lang = hint.documentLanguage.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
    val letterLines = base.lines.filter { line -> line.confidence >= .52f && line.text.any(Char::isLetter) }
    if (letterLines.count { !asciiLettering(it.text) && it.text.count(Char::isLetter) >= 3 } * 4 > letterLines.size) return false
    return letterLines.any { englishProse(it.text) || hasEnglishRank(it.text) } || letterLines.isEmpty() && lang in setOf("en", "", "und") ||
        lang == "en" && letterLines.size <= 2 && letterLines.all { englishLettering(it.text) && it.confidence < .69f }
}

/** Only replace a geometrically dirty reading when the inner crop corroborates its actual dialogue. */
internal fun replacesSpilledLine(clean: OcrLine, dirty: OcrLine): Boolean {
    val contour = clean.bubbleRead?.contour ?: return false
    if (clean.confidence < .89f || clean.vertical || dirty.vertical || !contour.fits(clean.box) || contour.containsInk(dirty.box) ||
        areaOverlap(dirty.box, clean.box) < .75 || abs(clean.box.y - dirty.box.y) > clean.box.height * .60f ||
        imageLiteralSpans(clean.text).isNotEmpty() || imageLiteralSpans(dirty.text).isNotEmpty()) return false
    val reading = words(clean.text)
    val old = words(dirty.text)
    if (reading.size < 2 || reading.sumOf(String::length) < 6 || old.size - reading.size !in 1..2) return false
    // Do not trade a valid sentence for a shorter guess, or swallow an independent neighboring label.
    val start = (0..old.size - reading.size).firstOrNull { old.subList(it, it + reading.size) == reading } ?: return false
    val extras = old.take(start) + old.drop(start + reading.size)
    return extras.all { it.length <= 3 } && (dirty.words.isEmpty() || dirty.words.count { !contour.containsInk(it.box) } >= extras.size)
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
        if (line.vertical || line.confidence < (if (shortLetter(text)) .76f else .78f) || line.box.width < 6 || line.box.height < 5 ||
            blocked.any { it.overlaps(line.box) }) continue
        val overlap = result.indices.filter { index ->
            val other = result[index].box
            abs(other.y - line.box.y) < maxOf(other.height, line.box.height) * .65f && areaOverlap(other, line.box) > .46
        }
        if (isProtectedImageLiteral(text)) {
            if (overlap.isEmpty() && line.confidence >= .89f && result.size < 500) result += line
            continue
        }
        if (!englishLettering(text) || imageLiteralSpans(text).isNotEmpty()) continue
        if (overlap.isEmpty()) {
            val passesSurface = lightBorder(line.box) || explicit && !shortLetter(text)
            // Thin overlapping guesses still conflict with the original layout; skip rather than risk two overlays.
            if (passesSurface && result.size < 500 && result.none { areaOverlap(it.box, line.box) > .39 }) result += line
            continue
        }
        val newLetters = alphaText(text)
        val partials = overlap.map(result::get)
        if (line.confidence >= .87f && englishProse(text) && lightBorder(line.box) && partials.all { old ->
                val letters = alphaText(old.text)
                !old.vertical && englishLettering(old.text) && imageLiteralSpans(old.text).isEmpty() && letters.length >= 2 &&
                    newLetters.length >= letters.length + 3 && newLetters.contains(letters) && areaOverlap(old.box, line.box) > .76
            } && result.indices.none { it !in overlap && areaOverlap(result[it].box, line.box) > .43 }) {
            // A small/faint line can be split into several words or reported as just its darkest segment.
            val index = overlap.first()
            val block = result[index].block
            overlap.asReversed().forEach { result.removeAt(it) }
            result.add(index, line.copy(block = block))
            continue
        }
        if (overlap.size != 1) continue
        val index = overlap.single()
        val old = result[index]
        if (replacesSpilledLine(line, old) && result.indices.none { it != index && areaOverlap(result[it].box, line.box) > .43 }) {
            result[index] = line.copy(block = old.block)
            continue
        }
        if (old.vertical || imageLiteralSpans(old.text).isNotEmpty()) continue
        val oldLetters = alphaText(old.text)
        if (shortLetter(text) && oldLetters == "l" && line.confidence >= .77f && areaOverlap(old.box, line.box) > .80 && lightBorder(line.box)) {
            result[index] = old.copy(text = text, confidence = line.confidence)
            continue
        }
        if (!englishLettering(old.text)) continue
        if (oldLetters == newLetters && (words(text).size > words(old.text).size ||
                line.confidence >= .88f && text.count { it in ".,!?:;…" } > old.text.count { it in ".,!?:;…" }) &&
            (explicit || englishProse(text))) {
            result[index] = old.copy(text = text) // Keep ML Kit word boxes and tight geometry.
        } else if (line.inkVerifiedLatin && line.confidence >= .91f && areaOverlap(old.box, line.box) >= .78 &&
            abs(old.box.y - line.box.y) <= minOf(old.box.height, line.box.height) * .46f &&
            latinInkChange(old.text, text) != null && lightBorder(line.box)) {
            result[index] = old.copy(text = text, confidence = line.confidence, inkVerifiedLatin = true)
        } else if (old.confidence < .67f && line.confidence >= .88f && newLetters.length >= oldLetters.length && englishProse(text) &&
            areaOverlap(old.box, line.box) > .70) {
            result[index] = old.copy(text = text, confidence = line.confidence)
        }
    }
    return result
}

internal fun comicNeighbors(a: OcrLine, b: OcrLine, enclosed: Boolean = false, wideLeading: Boolean = false): Boolean {
    if (a.vertical || b.vertical || !englishLettering(a.text) || !englishLettering(b.text)) return false
    if (imageLiteralSpans(a.text).isNotEmpty() || imageLiteralSpans(b.text).isNotEmpty()) return false
    val height = minOf(a.box.height, b.box.height).coerceAtLeast(1)
    val width = minOf(a.box.width, b.box.width).coerceAtLeast(1)
    val gap = b.box.top - a.box.bottom
    val overlap = minOf(a.box.right, b.box.right) - maxOf(a.box.left, b.box.left)
    val larger = maxOf(a.box.width, b.box.width)
    return b.box.y > a.box.y + height * .50f && gap > -height * .59f && (wideLeading || gap < height * (if (enclosed) 2.05f else .88f)) &&
        maxOf(a.box.height, b.box.height) <= height * (if (enclosed) 2.25f else 1.65f) && overlap > width * .56f &&
        abs(a.box.x - b.box.x) <= maxOf(height * .72f, if (enclosed) larger * .28f else width * .17f)
}

private fun nameCase(word: String) = word.first().isUpperCase() && word.drop(1).any(Char::isLowerCase)

/** A partial reply containing a copied OCR error is not a translation, even if other words are Sorani. */
internal fun incompleteSorani(source: String, output: String, knownEnglish: Boolean): Boolean {
    if (output.isBlank()) return true
    val sequence = alphaText(source)
    if (sequence == alphaText(output) && englishLettering(source)) return true
    if (!knownEnglish && !englishProse(source)) return false
    val withoutLiterals = imageLiteralSpans(output).fold(output) { text, span -> text.replace(span, " ") }
    if (withoutLiterals.none { it in '\u0600'..'\u08ff' && it.isLetter() }) return true
    val originals = asciiWord.findAll(normalizeLatinApostrophes(source)).map { it.value }.groupBy { it.lowercase(Locale.ROOT) }
    return asciiWord.findAll(normalizeLatinApostrophes(withoutLiterals)).map { it.value }.any { token ->
        val lower = token.lowercase(Locale.ROOT)
        if (token == "s" && ("s" in originals || originals.keys.any { it.endsWith("'s") })) return@any true
        val matches = originals[lower] ?: return@any false
        when {
            lower == "i" || lower in plainShort || lower.length > 1 && lower in englishWords -> true
            lower.length == 1 || knownSoraniResidue(lower) -> false // e.g. rank E, API, mapped names
            else -> !(nameCase(token) && matches.any(::nameCase))
        }
    }
}
