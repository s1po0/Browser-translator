package dev.rojink.browser.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val scheme = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

/** Only URLs; never submit clipboard contents as a search. */
fun resolveLink(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any(Char::isWhitespace)) return null
    if (scheme.containsMatchIn(text)) return webUrl(text)?.toString()
    val host = text.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    if ('.' !in host && !host.equals("localhost", ignoreCase = true) && !text.startsWith('[')) return null
    return webUrl("https://$text")?.toString()
}

fun resolveAddress(input: String, engine: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    if (scheme.containsMatchIn(text)) return webUrl(text)?.toString()
    resolveLink(text)?.let { return it }
    val base = when (engine) {
        "duckduckgo" -> "https://duckduckgo.com/"
        "bing" -> "https://www.bing.com/search"
        else -> "https://www.google.com/search"
    }
    return base.toHttpUrl().newBuilder().addQueryParameter("q", text).build().toString()
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
