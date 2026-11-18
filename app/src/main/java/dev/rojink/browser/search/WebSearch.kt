package dev.rojink.browser.search

import dev.rojink.browser.web.webUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The default on upgrade is a choice, not permission to send a local query to a website. */
enum class AddressSearchMode { ASK, DEVICE, WEB }

internal fun AddressSearchMode.forTab(privateTab: Boolean) = if (privateTab) AddressSearchMode.DEVICE else this

internal const val SEARCH_QUERY_LIMIT = 300

internal fun searchQuery(input: String): String = input.trim().take(SEARCH_QUERY_LIMIT).let {
    if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1).trimEnd() else it
}

private val braveSearch = "https://search.brave.com/search".toHttpUrl()
private val resultsPaths = setOf("/search", "/images", "/news", "/videos")

/** Open the provider's ordinary results page. No API credential, scraping, or network suggestions. */
internal fun braveSearchUrl(input: String): String? = searchQuery(input).takeIf(String::isNotBlank)?.let { query ->
    braveSearch.newBuilder().addQueryParameter("q", query).build().toString()
}

internal fun braveResultsQuery(address: String?): String? = webUrl(address)
    ?.takeIf { it.isHttps && it.host == braveSearch.host && it.port == braveSearch.port && it.encodedPath in resultsPaths }
    ?.queryParameter("q")?.let(::searchQuery)?.takeIf(String::isNotBlank)
