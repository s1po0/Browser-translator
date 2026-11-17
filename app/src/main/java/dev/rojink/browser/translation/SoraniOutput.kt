package dev.rojink.browser.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/** Write names left in Latin script in Sorani. Never sends a second translation request. */
internal class SoraniOutput(private val delegate: TranslationProvider) : TranslationProvider {
    override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
        val result = delegate.translate(texts, source, target)
        if (!target.equals("ckb", true)) return result
        return withContext(Dispatchers.Default) { result.map(::polishSorani) }
    }
}

private val names = mapOf(
    "sung jin-woo" to "سونگ جین وو", "sung jinwoo" to "سونگ جین وو", "sung jin woo" to "سونگ جین وو", "jin-woo" to "جین وو", "jinwoo" to "جین وو",
    "cha hae in" to "چا هە ئین", "kim dokja" to "کیم دۆکجا", "yoo joonghyuk" to "یو جونگهیوک", "ken kaneki" to "کێن کانێکی",
    "killua" to "کیلوئا", "gon" to "گۆن", "saitama" to "سایتاما", "anya" to "ئانیا", "loid" to "لۆید",
    "cha hae-in" to "چا هە ئین", "naruto" to "ناروتۆ", "uzumaki" to "ئوزوماکی", "sasuke" to "ساسۆکە", "sakura" to "ساکورا",
    "kakashi" to "کاکاشی", "itachi" to "ئیتاچی", "luffy" to "لوفی", "zoro" to "زۆرۆ", "nami" to "نامی", "sanji" to "سانجی",
    "gojo" to "گۆجۆ", "yuji" to "یوجی", "sukuna" to "سوکونا", "ichigo" to "ئیچیگۆ", "tanjiro" to "تانجیڕۆ",
    "nezuko" to "نێزوکۆ", "levi" to "لیڤای", "eren" to "ئێرێن", "mikasa" to "میکاسا", "goku" to "گۆکو", "vegeta" to "ڤێجیتا",
    "harry" to "هاری", "potter" to "پۆتەر", "hermione" to "هێرمایۆنی", "alice" to "ئەلیس", "alex" to "ئەلیکس",
    "james" to "جەیمز", "john" to "جۆن", "sarah" to "سارا", "emily" to "ئێمیلی", "rose" to "ڕۆز", "lee" to "لی",
    "kim" to "کیم", "jin" to "جین", "woong" to "وونگ", "erbil" to "هەولێر", "sipan" to "سیپان",
)
private val readingTerms = mapOf(
    "chapter" to "بەش", "chapters" to "بەشەکان", "episode" to "ئەڵقە", "episodes" to "ئەڵقەکان", "volume" to "بەرگ",
    "page" to "پەڕە", "pages" to "پەڕەکان", "character" to "کارەکتەر", "manga" to "مانگا", "manhwa" to "مانهوا",
    "webtoon" to "وێبتوون", "comic" to "کۆمیک", "spoiler" to "ئاشکراکردنی ڕووداو", "menu" to "مێنیو",
    "bookmark" to "نیشانە", "download" to "داگرتن", "next" to "دواتر", "previous" to "پێشوو", "continue" to "بەردەوام بە",
)
private const val latin = "A-Za-zÀ-ÖØ-öø-ÿĀ-ž"
private val latinWord = Regex("(?<![\\p{L}\\p{Nd}_])[$latin]+(?:[’'–-][$latin]+)*(?![\\p{L}\\p{Nd}_])")
private val phrase = Regex("(?<![\\p{L}\\p{Nd}])(?:${names.keys.filter { ' ' in it }.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }})(?![\\p{L}\\p{Nd}])", RegexOption.IGNORE_CASE)
private const val currency = "USD|EUR|IQD|JPY|KRW|CNY|SAR|GBP|AED|CAD|AUD|CHF|NZD|TRY|INR|SEK|NOK|DKK|KWD|QAR|BHD|OMR|IRR"
private val keepOriginal = Regex("""
    `[^`\r\n]*`|(?:[A-Za-z][A-Za-z0-9+.-]*://|www\.|mailto:|tel:)[^\s<>]+|(?<![\p{L}\d])(?:(?:$currency)\s*\p{Nd}[\p{Nd}.,٬٫]*|\p{Nd}[\p{Nd}.,٬٫]*\s*(?:$currency))(?![\p{L}\d])|[\p{L}\d._%+-]+@[\p{L}\d.-]+\.[\p{L}]{2,}|(?<![\p{L}\d])(?:[\p{L}\d-]+\.)+[A-Za-z]{2,63}(?:[/#?][^\s<>]*)?|(?<![\p{L}\d])[@#][\p{L}\d_.-]+|(?<![\p{L}\d])(?:[$latin\d]+_[$latin\d_]+|(?=[$latin\d_.:-]*\d)[$latin\d]+(?:[_.:-][$latin\d]+)+)(?=[\s,;!?)]|$)
""".trimIndent())
private val acronyms = setOf("api", "apk", "html", "https", "http", "url", "ocr", "pdf", "css", "json", "xml", "zip", "usb", "isbn",
    "usd", "iqd", "eur", "jpy", "krw", "cny", "sar", "gbp", "kg", "cm", "mm", "mb", "gb", "ui", "ux", "app", "rtl", "gpu", "cpu")
private val notNames = setOf("the", "and", "are", "we", "you", "i", "of", "to", "in", "go", "no", "yes", "hello", "home", "here",
    "read", "my", "your", "be", "is", "a", "an", "stop", "please", "thank", "out", "look", "wait", "don't", "open", "now", "this", "that", "what", "good", "morning", "welcome", "chapter", "later", "turn", "fight", "run", "as", "it", "on", "for", "at", "from", "with", "too", "not", "can", "will", "should", "must", "there", "how", "really", "okay")

private val labelShape = Regex("[$latin]+[\\s\\p{Nd}#:.–/-]*")
private val nameShape = Regex("[$latin][ $latin’'–-]*[.!?]?")

private fun likelyName(word: String) = word.first().isUpperCase() && word.lowercase(Locale.ROOT) !in notNames

internal fun polishSorani(value: String): String {
    if (value.none { it in 'A'..'Z' || it in 'a'..'z' }) return value
    val mixed = value.any { it in '\u0600'..'\u08ff' && it.isLetter() }
    val parts = latinWord.findAll(value).map { it.value }.take(5).toList()
    val shortName = !mixed && parts.isNotEmpty() && parts.size <= 4 && value.length <= 68 &&
        nameShape.matches(value.trim()) &&
        parts.all(::likelyName)
    val lower = value.trim().trimEnd('.', '!', '?').lowercase(Locale.ROOT)
    val knownOnly = !mixed && (lower in names || lower in readingTerms ||
        parts.size == 1 && labelShape.matches(value.trim()) && parts.single().lowercase(Locale.ROOT) in readingTerms)
    // An English sentence left intact by a provider is not a Sorani translation. Keep it readable.
    if (!mixed && !shortName && !knownOnly) return value
    fun convert(section: String): String {
        val phrases = phrase.replace(section) { names[it.value.lowercase(Locale.ROOT)] ?: it.value }
        return latinWord.replace(phrases) { match ->
            val original = match.value
            val word = original.lowercase(Locale.ROOT).replace('’', '\'').replace('–', '-')
            when {
                word in acronyms || word.length <= 1 -> original
                word in names -> names.getValue(word)
                word in readingTerms -> readingTerms.getValue(word)
                likelyName(original) -> phoneticWord(word)
                else -> original
            }
        }
    }
    return buildString {
        var start = 0
        for (span in keepOriginal.findAll(value)) {
            append(convert(value.substring(start, span.range.first)))
            append(span.value)
            start = span.range.last + 1
        }
        append(convert(value.substring(start)))
    }
}

private val sounds = listOf("eigh" to "ەی", "tion" to "شەن", "tch" to "چ", "dge" to "ج", "sh" to "ش", "ch" to "چ", "zh" to "ژ",
    "ph" to "ف", "th" to "ت", "kh" to "خ", "gh" to "غ", "ng" to "نگ", "ck" to "ک", "qu" to "کو", "oo" to "وو",
    "ou" to "او", "ee" to "ی", "ai" to "ەی", "ay" to "ەی", "oa" to "ۆ", "ei" to "ەی", "ey" to "ەی")

private fun phoneticWord(word: String): String = word.split('-').joinToString("-") { part ->
    if (part.endsWith("'s") && part.length > 3) return@joinToString phoneticWord(part.dropLast(2)) + "ی"
    val text = Normalizer.normalize(part, Normalizer.Form.NFD).filter { it in 'a'..'z' }
    if (text.isEmpty()) return@joinToString part
    names[text]?.let { return@joinToString it }
    buildString {
        var i = 0
        while (i < text.length) {
            val pair = sounds.firstOrNull { text.startsWith(it.first, i) }
            if (pair != null) {
                if (i == 0 && text[i] in "aeiou") append('ئ')
                append(pair.second); i += pair.first.length; continue
            }
            val ch = text[i]
            val next = text.getOrNull(i + 1) ?: ' '
            if (i == 0 && ch in "aeiou") append('ئ')
            append(when (ch) {
                'a' -> if (i == 0 || i == text.lastIndex) "ا" else "ە"
                'e' -> if (i == text.lastIndex && text.length > 3) "" else "ێ"
                'i', 'y' -> "ی"
                'o' -> "ۆ"
                'u' -> "و"
                'b' -> "ب"; 'c' -> if (next in "iey") "س" else "ک"
                'd' -> "د"; 'f' -> "ف"; 'g' -> "گ"; 'h' -> "ه"; 'j' -> "ج"; 'k', 'q' -> "ک"
                'l' -> "ل"; 'm' -> "م"; 'n' -> "ن"; 'p' -> "پ"; 'r' -> "ر"; 's' -> "س"
                't' -> "ت"; 'v' -> "ڤ"; 'w' -> "و"; 'x' -> "کس"; 'z' -> "ز"; else -> ""
            })
            i += if (ch == next && ch !in "aeiouy") 2 else 1
        }
    }.ifBlank { part }
}
