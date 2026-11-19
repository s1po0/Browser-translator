package dev.rojink.browser.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val number = "\\p{Nd}+(?:[.,٬٫]\\p{Nd}+)*"
private val price = Regex("(?<![\\p{L}\\p{Nd}])(?:[\\p{Sc}]\\s*$number|$number\\s*[\\p{Sc}%٪])(?![\\p{L}\\p{Nd}])")
private val serial = Regex("(?<![\\p{L}\\p{Nd}_])[A-Z]{2,10}[0-9]{2,12}(?![\\p{L}\\p{Nd}_])")
// OCR can drop one slash or add a space to an otherwise clear printed URL. Never erase or repair its pixels.
private val printedUrl = Regex("""(?<![\p{L}\p{Nd}])https?\s*:\s*/{1,2}\s*(?:[\p{L}\p{Nd}-]+\.)+[\p{L}]{2,63}(?:[/#?][^\s<>]*)?""",
    RegexOption.IGNORE_CASE)

/** Do not substitute/erase literal labels. For mixed strings, reject translations that change any literal. */
internal fun isProtectedImageLiteral(text: String): Boolean = text.trim().let {
    keepOriginal.matches(it) || printedUrl.matches(it) || price.matches(it) || serial.matches(it)
}

internal fun imageLiteralSpans(text: String): List<String> {
    val candidates = (keepOriginal.findAll(text) + printedUrl.findAll(text) + price.findAll(text) + serial.findAll(text)).sortedWith(
        compareBy<MatchResult> { it.range.first }.thenByDescending { it.value.length },
    )
    var last = -1
    return buildList {
        for (match in candidates) {
            if (match.range.first <= last) continue
            add(match.value)
            last = match.range.last
        }
    }
}

private val shortSorani = mapOf(
    "you" to "تۆ", "what" to "چی", "why" to "بۆچی", "who" to "کێ", "where" to "لە کوێ",
    "yes" to "بەڵێ", "no" to "نەخێر", "wait" to "چاوەڕوان بە", "stop" to "بوەستە", "hello" to "سڵاو",
    "sorry" to "ببورە", "i'm sorry" to "ببورە", "thank you" to "سوپاس", "thanks" to "سوپاس",
    "not yet" to "هێشتا نا", "come here" to "وەرە ئێرە", "go away" to "بڕۆ لێرە", "hurry up" to "پەلە بکە",
)

private fun shortSoraniLine(text: String): String? {
    val normalized = text.trim().lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("\\s+"), " ")
    val value = shortSorani[normalized.trimEnd('.', '?', '!', '…')] ?: return null
    return value + when {
        normalized.endsWith('?') -> "؟"
        normalized.endsWith('!') -> "!"
        normalized.endsWith('…') -> "…"
        normalized.endsWith('.') -> "."
        else -> ""
    }
}

private val letterRank = Regex("""(?<![\p{L}\p{Nd}_-])([A-FS])[-\u2010\u2011\u2013](?i:rank)(?![\p{L}\p{Nd}_-])""")
private val gradeCode = Regex("""(?<![\p{L}\p{Nd}_])[A-FS](?![\p{L}\p{Nd}_])""")
private val rankWord = Regex("\\brank\\b", RegexOption.IGNORE_CASE)
private val apostrophe = Regex("""(?<=\p{L})[‘’](?=\p{L})""")
private val retryWord = Regex("""\p{L}+(?:'\p{L}+)*""")

/** Let MT see ordinary English rather than comic lettering. Leave spelling, boundaries and literals intact. */
private fun soraniRetryInput(text: String): String {
    if (!asciiLettering(text) || imageLiteralSpans(text).isNotEmpty()) return text
    val plain = apostrophe.replace(text, "'")
    val recased = if (plain.none(Char::isLowerCase) && retryWord.findAll(plain).take(2).count() == 2) {
        val ranks = letterRank.findAll(plain).map { it.range.first }.toSet()
        var previousEnd = 0
        retryWord.replace(plain) { match ->
            val word = match.value
            val lower = word.lowercase(Locale.ROOT)
            val startsSentence = previousEnd == 0 || (previousEnd until match.range.first).any { plain[it] in ".!?" }
            previousEnd = match.range.last + 1
            when {
                match.range.first in ranks || word.length == 1 && word != "A" || isKnownAcronym(lower) -> word
                startsSentence || lower.startsWith("i'") -> lower.replaceFirstChar(Char::uppercaseChar)
                else -> lower
            }
        }
    } else plain
    // The hyphenated modifier can be copied verbatim or misread as “electronic rank”. Keep the actual grade.
    return letterRank.replace(recased) { "${it.groupValues[1]} rank" }
}

internal fun validImageTranslation(before: String, after: String, source: String, target: String): Boolean {
    if (after.isBlank() || before.trim().equals(after.trim(), ignoreCase = true) || isProtectedImageLiteral(before)) return false
    val literals = imageLiteralSpans(before).groupingBy { it }.eachCount()
    if (literals.isNotEmpty()) {
        val preserved = imageLiteralSpans(after).groupingBy { it }.eachCount()
        if (literals.any { (span, count) -> (preserved[span] ?: 0) < count }) return false
    }
    if (target != "ckb") return true
    val grades = if (literals.isEmpty() && asciiLettering(before)) letterRank.findAll(before).map { it.groupValues[1] }.toSet() else emptySet()
    if (grades.isNotEmpty()) {
        val visible = imageLiteralSpans(after).fold(after) { text, literal -> text.replace(literal, " ") }
        val kept = gradeCode.findAll(visible).map { it.value }.toSet()
        if (!kept.containsAll(grades) || rankWord.containsMatchIn(visible)) return false
    }
    // Run even in Auto: a Latin copy with only changed punctuation is not a translation.
    return !incompleteSorani(before, after, source == "en" || grades.isNotEmpty())
}

/** Keep whole dialogue together, then retry rejected bubbles without batch markers or stale cache. */
internal suspend fun translateImageWords(
    units: List<TextUnit>, target: String, script: String,
    provider: TranslationProvider, retryProvider: TranslationProvider,
): List<String> {
    val input = units.map { it.text }
    val englishContext = input.any(::reliableEnglish) && input.count(::englishLettering) * 4 >= input.size * 3
    val result = input.toMutableList()
    val retry = linkedMapOf<Pair<String, String>, MutableList<Int>>()
    fun polish(text: String) = if (target == "ckb") polishSorani(text) else text
    for ((source, indices) in input.indices.filterNot { isProtectedImageLiteral(input[it]) }.groupBy { idx ->
        when {
            script == ENGLISH_IMAGE_LANGUAGE -> ENGLISH_IMAGE_LANGUAGE
            script == ENGLISH_COMIC_SCRIPT && !units[idx].vertical && englishLettering(input[idx]) -> ENGLISH_IMAGE_LANGUAGE
            else -> "auto"
        }
    }) {
        if (source.equals(target, ignoreCase = true)) continue
        val batch = try { provider.translate(indices.map(input::get), source, target) }
        catch (_: ResponseFailure) { List(indices.size) { "" } }
        if (batch.size != indices.size) throw ResponseFailure()
        for ((index, after) in indices.zip(batch)) {
            val before = input[index]
            val knownEnglish = source == "en" || englishContext &&
                (englishProse(before) || shortSoraniLine(before) != null || reliableEnglish(before))
            val checkSource = if (knownEnglish) "en" else source
            if (validImageTranslation(before, after, checkSource, target)) {
                result[index] = polish(after)
            } else if (target == "ckb" && (knownEnglish || englishProse(before) ||
                    !units[index].vertical && asciiLettering(before) && letterRank.containsMatchIn(before))) {
                val local = if (knownEnglish) shortSoraniLine(before) else null
                if (local != null) result[index] = local else {
                    val key = checkSource to before
                    retry.getOrPut(key) { mutableListOf() }.add(index)
                }
            }
        }
    }
    if (retry.isEmpty()) return result
    // Time-budget the entire corrective pass; preserve completed fixes even if another call stalls.
    val fixes = ConcurrentHashMap<Pair<String, String>, String>()
    val parallel = Semaphore(3)
    withTimeoutOrNull(5_500L) {
        coroutineScope {
            retry.keys.take(8).forEach { key -> launch {
                parallel.withPermit {
                    val (source, before) = key
                    val query = soraniRetryInput(before)
                    val value = try { retryProvider.translate(listOf(query), source, target).singleOrNull() }
                    catch (e: CancellationException) { throw e }
                    catch (_: IOException) { null }
                    // Check against the unchanged OCR text, never the reformatted input sent to MT.
                    if (value != null && validImageTranslation(before, value, source, target)) fixes[key] = polish(value)
                }
            } }
        }
    }
    for ((key, indices) in retry) {
        fixes[key]?.let { value -> indices.forEach { result[it] = value } }
    }
    return result
}
