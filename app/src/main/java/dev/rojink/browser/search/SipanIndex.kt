package dev.rojink.browser.search

import dev.rojink.browser.web.webUrl
import java.text.Normalizer
import java.util.Locale

internal enum class SearchSource { INDEX, SAVED_PAGE, BOOKMARK, HISTORY }
internal data class SearchEntry(
    val url: String, val title: String, val summary: String = "", val terms: String = "",
    val source: SearchSource = SearchSource.INDEX,
)

/** The public catalog is authored by Sipan. No query is sent to a search or suggestion provider. */
internal fun readSipanIndex(lines: Sequence<String>): List<SearchEntry> = lines.filterNot { it.startsWith('#') }.mapNotNull { line ->
    val fields = line.split('\t', limit = 4)
    if (fields.size != 4 || fields[1].isBlank()) return@mapNotNull null
    val url = webUrl(fields[0])?.takeIf { it.isHttps } ?: return@mapNotNull null
    SearchEntry(url.toString(), fields[1].take(160), fields[2].take(300), fields[3].take(800))
}.take(1000).toList()

internal fun searchWords(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).mapNotNull { ch ->
        when {
            ch == 'ـ' || Character.getType(ch) in marks -> null
            ch == 'ك' -> 'ک'
            ch == 'ي' || ch == 'ى' -> 'ی'
            ch.isDigit() -> ch.digitToIntOrNull()?.digitToChar() ?: ch
            ch.isLetter() -> ch
            else -> ' '
        }
    }.joinToString("").split(' ').filter(String::isNotEmpty)

private val marks = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt())

internal fun findInSipan(query: String, index: List<SearchEntry>, personal: List<SearchEntry>, privateTab: Boolean): List<SearchEntry> {
    val tokens = searchWords(query.take(300)).distinct().take(12)
    if (tokens.isEmpty()) return emptyList()
    return (if (privateTab) index.asSequence() else personal.asSequence() + index)
        .mapNotNull { entry ->
            val address = webUrl(entry.url) ?: return@mapNotNull null
            val title = searchWords(entry.title)
            val site = searchWords(address.host + " " + address.pathSegments.joinToString(" "))
            val excerpt = searchWords(entry.summary)
            val terms = searchWords(entry.terms)
            var score = 0
            for (word in tokens) {
                val value = maxOf(match(word, title, 16), match(word, site, 10), match(word, excerpt, 6), match(word, terms, 4))
                if (value == 0) return@mapNotNull null
                score += value
            }
            if (title == tokens) score += 20
            else if (tokens.size > 1 && title.windowed(tokens.size).any { it == tokens }) score += 10
            score += when (entry.source) { SearchSource.SAVED_PAGE -> 3; SearchSource.BOOKMARK -> 2; else -> 0 }
            Triple(entry, score, address.newBuilder().fragment(null).build().toString())
        }.sortedWith(compareByDescending<Triple<SearchEntry, Int, String>> { it.second }.thenBy { it.first.title })
        .distinctBy { it.third }.take(60).map { it.first }.toList()
}

private fun match(token: String, field: List<String>, weight: Int): Int = when {
    token in field -> weight
    token.length >= 3 && field.any { it.startsWith(token) } -> (weight / 2).coerceAtLeast(1)
    else -> 0
}
