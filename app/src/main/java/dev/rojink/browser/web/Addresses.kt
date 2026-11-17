package dev.rojink.browser.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN

private val scheme = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

/** Only URLs; never submit clipboard contents as a search. */
fun resolveLink(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any(Char::isWhitespace)) return null
    if (scheme.containsMatchIn(text)) return webUrl(text)?.toString()
    val host = text.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    if ('.' !in host && !host.equals("localhost", ignoreCase = true) && !text.startsWith('[')) return null
    return webUrl("https://$text")?.takeIf { url ->
        val labels = url.host.trimEnd('.').split('.')
        val tld = IDN.toUnicode(labels.last())
        val ipv4 = labels.size == 4 && labels.all { part ->
            part.all { it in '0'..'9' } && part.toIntOrNull()?.let { it in 0..255 } == true
        }
        url.host == "localhost" || ':' in url.host || ipv4 ||
            (labels.size >= 2 && tld.length >= 2 && tld.all(Char::isLetter))
    }?.toString()
}

internal sealed interface AddressInput {
    data class Link(val url: String) : AddressInput
    data class Query(val text: String) : AddressInput
}

/** Queries never become web URLs. Only the search screen handles Query. */
internal fun addressInput(input: String): AddressInput? {
    val text = input.trim()
    if (text.isBlank()) return null
    resolveLink(text)?.let { return AddressInput.Link(it) }
    if (scheme.containsMatchIn(text) || text.startsWith("javascript:", true) || text.startsWith("data:", true) || text.startsWith("file:", true)) return null
    return AddressInput.Query(text.take(300))
}

fun webUrl(value: String?): HttpUrl? = value?.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }

fun HttpUrl.origin(): String = newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")

fun sameSite(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme &&
    (a.host == b.host || a.topPrivateDomain()?.let { it == b.topPrivateDomain() } == true)

private val trackers = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "adservice.google.com", "google-analytics.com", "googletagmanager.com", "scorecardresearch.com", "taboola.com",
    "outbrain.com", "adnxs.com", "criteo.com", "criteo.net", "adsrvr.org", "mgid.com", "pubmatic.com", "rubiconproject.com",
    "moatads.com", "hotjar.com", "quantserve.com", "branch.io")

fun isTracker(host: String): Boolean = trackers.any { host == it || host.endsWith(".$it") }
