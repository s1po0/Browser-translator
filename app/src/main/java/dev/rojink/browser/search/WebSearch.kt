package dev.rojink.browser.search

import dev.rojink.browser.web.webUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

/** The default on upgrade is a choice, not permission to send a local query to a website. */
enum class AddressSearchMode { ASK, DEVICE, WEB }

internal fun AddressSearchMode.forTab(privateTab: Boolean) = if (privateTab) AddressSearchMode.DEVICE else this

enum class WebSafeSearch(internal val cookieValue: String) { MODERATE("moderate"), STRICT("strict"), OFF("off") }

data class WebSearchFilters(
    val country: String? = null,
    val safeSearch: WebSafeSearch = WebSafeSearch.MODERATE,
    val countryDomainsOnly: Boolean = false,
) {
    internal val domainFilter: String? get() = searchCountryCode(country)
        ?.takeIf { countryDomainsOnly || !hasBraveRegion(it) }?.let(::countryDomain)
}

// Don't offer a domain filter for ISO territories with no matching delegation in the DNS root zone.
internal val searchCountryCodes: Set<String> = Locale.getISOCountries().toSet() - setOf("BL", "BQ", "EH", "MF", "UM")

private val isoCode = Regex("[a-zA-Z]{2}")
internal fun searchCountryCode(code: String?): String? = code?.trim()?.takeIf(isoCode::matches)
    ?.uppercase(Locale.ROOT)?.takeIf { it in searchCountryCodes }

// Listed in the public Brave Search Settings region picker. An ISO code alone does not imply a preset.
private val regions = setOf("AR", "AU", "AT", "BE", "BR", "CA", "CL", "CN", "DK", "FI", "FR", "DE", "GR", "HK",
    "IN", "ID", "IT", "JP", "KR", "MY", "MX", "NL", "NZ", "NO", "PH", "PL", "PT", "RU", "SA", "SG", "ZA", "ES",
    "SE", "CH", "TW", "TR", "GB", "US")

internal fun hasBraveRegion(country: String): Boolean = country in regions
internal fun countryDomain(country: String): String = if (country == "GB") "uk" else country.lowercase(Locale.ROOT)

internal const val SEARCH_QUERY_LIMIT = 300
private const val SCOPED_QUERY_LIMIT = SEARCH_QUERY_LIMIT + 8 // Space + site: + two-letter domain.

internal fun searchQuery(input: String): String = bounded(input, SEARCH_QUERY_LIMIT)

private fun bounded(input: String, limit: Int): String = input.trim().take(limit).let {
    if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1).trimEnd() else it
}

internal const val BRAVE_SEARCH_ORIGIN = "https://search.brave.com/"
private val braveSearch = BRAVE_SEARCH_ORIGIN.toHttpUrl()
private val resultsPaths = setOf("/search", "/images", "/news", "/videos")

/** Open Brave's public results page, not the paid API. Region and Safe Search are site preferences (cookies). */
internal fun braveSearchUrl(input: String, filters: WebSearchFilters = WebSearchFilters(), path: String = "/search"): String? {
    val term = searchQuery(input).takeIf(String::isNotBlank) ?: return null
    val query = filters.domainFilter?.let { domain -> "$term site:$domain" } ?: term
    return braveSearch.newBuilder().encodedPath(path.takeIf { it in resultsPaths } ?: "/search")
        .addQueryParameter("q", query).build().toString()
}

/** Fixed cookie names and validated values only; no search terms, location coordinates, or identifiers. */
internal fun braveSearchCookies(filters: WebSearchFilters): List<String> {
    val region = searchCountryCode(filters.country)?.takeIf(::hasBraveRegion)?.lowercase(Locale.ROOT) ?: "all"
    return listOf("country=$region", "safesearch=${filters.safeSearch.cookieValue}", "useLocation=0")
        .map { "$it; Path=/; Secure; SameSite=Lax" }
}

private fun braveResultsUrl(address: String?) = webUrl(address)
    ?.takeIf { it.isHttps && it.host == braveSearch.host && it.port == braveSearch.port && it.encodedPath in resultsPaths }

internal fun braveResultsQuery(address: String?): String? = braveResultsUrl(address)
    ?.queryParameter("q")?.let { bounded(it, SCOPED_QUERY_LIMIT) }?.takeIf(String::isNotBlank)

internal fun braveResultsPath(address: String?): String = braveResultsUrl(address)?.encodedPath ?: "/search"
