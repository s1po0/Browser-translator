package dev.rojink.browser.translation

import kotlinx.coroutines.CancellationException
import java.io.IOException

private const val number = "\\p{Nd}+(?:[.,٬٫]\\p{Nd}+)*"
private val price = Regex("(?<![\\p{L}\\p{Nd}])(?:[\\p{Sc}]\\s*$number|$number\\s*[\\p{Sc}%٪])(?![\\p{L}\\p{Nd}])")
private val serial = Regex("(?<![\\p{L}\\p{Nd}_])[A-Z]{2,10}[0-9]{2,12}(?![\\p{L}\\p{Nd}_])")

/** Do not substitute/erase literal labels. For mixed strings, reject translations that change any literal. */
internal fun isProtectedImageLiteral(text: String): Boolean = text.trim().let {
    keepOriginal.matches(it) || price.matches(it) || serial.matches(it)
}

internal fun imageLiteralSpans(text: String): List<String> {
    val candidates = (keepOriginal.findAll(text) + price.findAll(text) + serial.findAll(text)).sortedWith(
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

/** A full bubble stays one entry. Retry only a few suspect Sorani results as single requests, on the same provider. */
internal suspend fun translateImageWords(
    units: List<TextUnit>, target: String, explicitEnglish: Boolean,
    provider: TranslationProvider, retryProvider: TranslationProvider,
): List<String> {
    val input = units.map { it.text }
    val sources = units.map { unit ->
        if (explicitEnglish && !unit.vertical && asciiLettering(unit.text)) "en" else "auto"
    }
    val result = input.toMutableList()
    var retryBudget = 3
    val retried = mutableMapOf<Pair<String, String>, String?>()
    for ((source, indices) in input.indices.groupBy { sources[it] }) {
        if (source.equals(target, ignoreCase = true)) continue
        val batch = provider.translate(indices.map(input::get), source, target)
        if (batch.size != indices.size) throw ResponseFailure()
        for ((index, after) in indices.zip(batch)) {
            val before = input[index]
            val literals = imageLiteralSpans(before).groupingBy { it }.eachCount()
            fun safe(text: String): Boolean {
                if (text.isBlank()) return false
                val preserved = imageLiteralSpans(text).groupingBy { it }.eachCount()
                return literals.all { (span, count) -> (preserved[span] ?: 0) >= count }
            }
            val english = source == "en" || englishProse(before)
            fun complete(text: String) = safe(text) && !(target == "ckb" && english && incompleteSorani(before, text, source == "en"))
            fun polish(text: String) = if (target == "ckb") polishSorani(text) else text
            if (complete(after)) { result[index] = polish(after); continue }
            if (target == "ckb" && english) {
                val key = source to before
                if (key in retried || retryBudget > 0) {
                    val translated = if (key in retried) retried[key] else {
                        retryBudget--
                        val one = try { retryProvider.translate(listOf(before), source, target).singleOrNull() }
                        catch (e: CancellationException) { throw e }
                        catch (_: IOException) { null }
                        retried[key] = one
                        one
                    }
                    if (translated != null && complete(translated)) result[index] = polish(translated)
                }
            }
            // Keep the original art/text, rather than painting a mixed, missing or damaged translation.
        }
    }
    return result
}
