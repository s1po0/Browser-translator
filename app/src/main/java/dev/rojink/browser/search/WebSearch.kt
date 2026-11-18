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

internal enum class WebSearchCategory(val path: String) {
    WEB("/search"), IMAGES("/images"), NEWS("/news"), VIDEOS("/videos");

    val supportsRefinements: Boolean get() = this == WEB
}

internal enum class SearchFreshness(val braveValue: String?) {
    ANY(null), DAY("pd"), WEEK("pw"), MONTH("pm"), YEAR("py")
}

/** Per-query tools; unlike country and Safe Search, these do not become defaults. */
internal data class WebRefinements(
    val freshness: SearchFreshness = SearchFreshness.ANY,
    val exactPhrase: Boolean = false,
    val pdfs: Boolean = false,
)

internal data class WebSearchRequest(
    val query: String,
    val category: WebSearchCategory = WebSearchCategory.WEB,
    val refinements: WebRefinements = WebRefinements(),
) {
    fun inCategory(target: WebSearchCategory) = copy(
        category = target, refinements = if (target.supportsRefinements) refinements else WebRefinements(),
    )
}

internal const val SEARCH_QUERY_LIMIT = 300
private const val BRAVE_QUERY_LIMIT = SEARCH_QUERY_LIMIT + 23 // Quotes, " filetype:pdf" and " site:xx".

// Keep a trailing space while editing; trimming here would make it impossible to type a second word.
internal fun searchDraft(input: String): String = input.take(SEARCH_QUERY_LIMIT).withoutTrailingHighSurrogate()
internal fun searchQuery(input: String): String = searchDraft(input.trim()).trimEnd()

private fun String.withoutTrailingHighSurrogate(): String =
    if (lastOrNull()?.isHighSurrogate() == true) dropLast(1) else this

internal const val BRAVE_SEARCH_ORIGIN = "https://search.brave.com/"
private val braveSearch = BRAVE_SEARCH_ORIGIN.toHttpUrl()

/** Brave's public results page, not the API. Region and Safe Search remain site preferences (cookies). */
internal fun braveSearchUrl(request: WebSearchRequest, filters: WebSearchFilters = WebSearchFilters()): String? {
    val term = searchQuery(request.query).takeIf(String::isNotBlank) ?: return null
    val tools = if (request.category.supportsRefinements) request.refinements else WebRefinements()
    // Inner double quotes would end the exact phrase early. Leave ordinary typed queries untouched.
    val phrase = if (tools.exactPhrase) term.replace('"', ' ').trim().takeIf(String::isNotEmpty) else null
    val query = buildString {
        append(if (phrase != null) "\"$phrase\"" else term)
        if (tools.pdfs) append(" filetype:pdf")
        filters.domainFilter?.let { append(" site:").append(it) }
    }
    return braveSearch.newBuilder().encodedPath(request.category.path).addQueryParameter("q", query).apply {
        tools.freshness.braveValue?.let { addQueryParameter("tf", it) }
        if (phrase != null) addQueryParameter("spellcheck", "0")
    }.build().toString()
}

/** Fixed cookie names and validated values only; no search terms, location coordinates, or identifiers. */
internal fun braveSearchCookies(filters: WebSearchFilters): List<String> {
    val region = searchCountryCode(filters.country)?.takeIf(::hasBraveRegion)?.lowercase(Locale.ROOT) ?: "all"
    return listOf("country=$region", "safesearch=${filters.safeSearch.cookieValue}", "useLocation=0")
        .map { "$it; Path=/; Secure; SameSite=Lax" }
}

internal fun braveResults(address: String?): WebSearchRequest? {
    val url = webUrl(address)?.takeIf { it.isHttps && it.host == braveSearch.host && it.port == braveSearch.port } ?: return null
    val category = WebSearchCategory.entries.firstOrNull { it.path == url.encodedPath } ?: return null
    val query = url.queryParameter("q")?.trim()?.take(BRAVE_QUERY_LIMIT)?.withoutTrailingHighSurrogate()
        ?.takeIf(String::isNotBlank) ?: return null
    val date = if (category.supportsRefinements) url.queryParameter("tf") else null
    val freshness = SearchFreshness.entries.firstOrNull { it.braveValue == date } ?: SearchFreshness.ANY
    return WebSearchRequest(query, category, WebRefinements(freshness))
}

/** Tab-lifetime, bounded record of app-added query operators. Never strips operators a user typed. */
internal class WebSearchTrail {
    private data class Original(val input: String, val tools: WebRefinements)

    private val decorated = LinkedHashMap<String, Original>()

    fun record(address: String, request: WebSearchRequest) {
        val sent = braveResults(address) ?: return
        val input = searchQuery(request.query)
        decorated.remove(sent.query)
        if (sent.query != input) {
            decorated[sent.query] = Original(input, request.inCategory(sent.category).refinements)
            if (decorated.size > 16) decorated.remove(decorated.keys.first())
        }
    }

    fun read(address: String?): WebSearchRequest? {
        val page = braveResults(address) ?: return null
        val original = decorated[page.query]
        val refinements = if (original != null && page.category.supportsRefinements) {
            original.tools.copy(freshness = page.refinements.freshness)
        } else page.refinements
        return page.copy(query = original?.input ?: searchQuery(page.query), refinements = refinements)
    }

    fun clear() = decorated.clear()
}
