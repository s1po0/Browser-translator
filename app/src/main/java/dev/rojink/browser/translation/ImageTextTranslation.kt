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

internal fun validImageTranslation(before: String, after: String, source: String, target: String): Boolean {
    if (after.isBlank() || before.trim().equals(after.trim(), ignoreCase = true)) return false
    val literals = imageLiteralSpans(before).groupingBy { it }.eachCount()
    if (literals.isNotEmpty()) {
        val preserved = imageLiteralSpans(after).groupingBy { it }.eachCount()
        if (literals.any { (span, count) -> (preserved[span] ?: 0) < count }) return false
    }
    return target != "ckb" || source != "en" && !englishProse(before) || !incompleteSorani(before, after, source == "en")
}

/** Keep whole dialogue together, then bypass batch markers and stale cache for suspect bubbles. */
internal suspend fun translateImageWords(
    units: List<TextUnit>, target: String, explicitEnglish: Boolean,
    provider: TranslationProvider, retryProvider: TranslationProvider,
): List<String> {
    val input = units.map { it.text }
    val englishContext = input.any(::reliableEnglish) && input.count(::englishLettering) * 4 >= input.size * 3
    val result = input.toMutableList()
    val retry = linkedMapOf<Pair<String, String>, MutableList<Int>>()
    fun polish(text: String) = if (target == "ckb") polishSorani(text) else text
    for ((source, indices) in input.indices.groupBy { idx ->
        if (explicitEnglish && !units[idx].vertical && englishLettering(input[idx])) "en" else "auto"
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
            } else if (target == "ckb" && (knownEnglish || englishProse(before))) {
                val key = checkSource to before
                retry.getOrPut(key) { mutableListOf() }.add(index)
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
                    val value = try { retryProvider.translate(listOf(before), source, target).singleOrNull() }
                    catch (e: CancellationException) { throw e }
                    catch (_: IOException) { null }
                    if (value != null && validImageTranslation(before, value, source, target)) fixes[key] = polish(value)
                }
            } }
        }
    }
    for ((key, indices) in retry) {
        val value = fixes[key] ?: if (key.first == "en") shortSoraniLine(key.second) else null
        if (value != null) indices.forEach { result[it] = value }
    }
    return result
}
