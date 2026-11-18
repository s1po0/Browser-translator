package dev.rojink.browser.search

import dev.rojink.browser.web.webUrl
import org.junit.Assert.*
import org.junit.Test

class WebSearchTest {
    @Test fun `the screenshot query opens the independent public results endpoint`() {
        assertEquals("https://search.brave.com/search?q=hi", url(" hi "))
        assertNull(url(" \t\n"))
    }

    @Test fun `the whole Sorani query is passed as one encoded parameter`() {
        val term = "هەواڵی هەولێر & images #1 + مانگا"
        val address = webUrl(url(term))!!
        assertTrue(address.isHttps)
        assertEquals("search.brave.com", address.host)
        assertEquals(setOf("q"), address.queryParameterNames)
        assertEquals(term, address.queryParameter("q"))
        assertNull(address.fragment)
        assertEquals(term, braveResults(address.toString())?.query)
        assertEquals("x".repeat(299), braveResults(url("x".repeat(299) + "💬"))?.query)
        assertEquals("هەواڵی ", searchDraft("هەواڵی "))
    }

    @Test fun `each search category opens the real public result page`() {
        val paths = mapOf(WebSearchCategory.WEB to "/search", WebSearchCategory.IMAGES to "/images",
            WebSearchCategory.NEWS to "/news", WebSearchCategory.VIDEOS to "/videos")
        for ((category, path) in paths) {
            val request = WebSearchRequest("Erbil citadel", category)
            val address = "https://search.brave.com$path?q=Erbil%20citadel"
            assertEquals(address, braveSearchUrl(request))
            assertEquals(request, braveResults(address))
        }
    }

    @Test fun `date phrase and PDF tools use public web request syntax without affecting media searches`() {
        val tools = WebRefinements(SearchFreshness.WEEK, exactPhrase = true, pdfs = true)
        val request = WebSearchRequest("Erbil Citadel", refinements = tools)
        val address = webUrl(braveSearchUrl(request))!!
        assertEquals("\"Erbil Citadel\" filetype:pdf", address.queryParameter("q"))
        assertEquals("pw", address.queryParameter("tf"))
        assertEquals("0", address.queryParameter("spellcheck"))
        assertEquals(SearchFreshness.WEEK, braveResults(address.toString())?.refinements?.freshness)
        for ((option, code) in mapOf(SearchFreshness.ANY to null, SearchFreshness.DAY to "pd", SearchFreshness.WEEK to "pw",
                SearchFreshness.MONTH to "pm", SearchFreshness.YEAR to "py")) {
            val dateUrl = braveSearchUrl(request.copy(refinements = WebRefinements(option)))!!
            assertEquals(code, webUrl(dateUrl)?.queryParameter("tf"))
            assertEquals(option, braveResults(dateUrl)?.refinements?.freshness)
        }
        for (category in WebSearchCategory.entries.filterNot { it.supportsRefinements }) {
            val media = webUrl(braveSearchUrl(request.copy(category = category)))!!
            assertEquals(category.path, media.encodedPath)
            assertEquals("Erbil Citadel", media.queryParameter("q"))
            assertEquals(setOf("q"), media.queryParameterNames)
            assertEquals(WebRefinements(), request.inCategory(category).refinements)
        }
    }

    @Test fun `only generated operators are removed when editing or reapplying a country`() {
        val trail = WebSearchTrail()
        val tools = WebRefinements(SearchFreshness.MONTH, exactPhrase = true, pdfs = true)
        val request = WebSearchRequest("a".repeat(SEARCH_QUERY_LIMIT), refinements = tools)
        val filters = WebSearchFilters(country = "IQ")
        val address = braveSearchUrl(request, filters)!!
        assertEquals("\"${request.query}\" filetype:pdf site:iq", braveResults(address)?.query)
        trail.record(address, request)
        assertEquals(request, trail.read(address))
        assertEquals("\"${request.query}\" filetype:pdf", braveResults(braveSearchUrl(trail.read(address)!!))?.query)
        assertEquals(request.copy(refinements = tools.copy(freshness = SearchFreshness.YEAR)), trail.read(address.replace("tf=pm", "tf=py")))

        val typed = WebSearchRequest("\"art history\" filetype:pdf")
        val typedAddress = braveSearchUrl(typed)!!
        trail.record(typedAddress, typed)
        assertEquals(typed, trail.read(typedAddress))
    }

    @Test fun `a supported country prioritizes regional results without changing the query`() {
        val filters = WebSearchFilters(country = " jp ", safeSearch = WebSafeSearch.STRICT)
        assertNull(filters.domainFilter)
        assertEquals("hi", braveResults(url("hi", filters))?.query)
        assertEquals(listOf("country=jp", "safesearch=strict", "useLocation=0"), cookiePairs(filters))
    }

    @Test fun `Iraq uses its country domain and does not pretend to have a Brave preset`() {
        val filters = WebSearchFilters(country = "IQ", safeSearch = WebSafeSearch.OFF)
        val term = "هەواڵی هەولێر"
        val address = braveSearchUrl(WebSearchRequest(term, WebSearchCategory.NEWS), filters)!!
        assertEquals("iq", filters.domainFilter)
        assertEquals("$term site:iq", braveResults(address)?.query)
        assertEquals(setOf("q"), webUrl(address)!!.queryParameterNames)
        assertEquals(listOf("country=all", "safesearch=off", "useLocation=0"), cookiePairs(filters))
        assertTrue(braveSearchCookies(filters).all { it.endsWith("; Path=/; Secure; SameSite=Lax") })
    }

    @Test fun `country domain scope is optional for the UK and keeps the full bounded user term`() {
        val filters = WebSearchFilters(country = "GB", countryDomainsOnly = true)
        val term = "a".repeat(SEARCH_QUERY_LIMIT)
        val address = braveSearchUrl(WebSearchRequest(term + " longer", WebSearchCategory.NEWS), filters)!!
        assertEquals(WebSearchCategory.NEWS, braveResults(address)?.category)
        assertEquals("$term site:uk", braveResults(address)?.query)
        assertEquals(listOf("country=gb", "safesearch=moderate", "useLocation=0"), cookiePairs(filters))
    }

    @Test fun `worldwide removes domain scope and invalid regions cannot enter URLs or cookies`() {
        for (invalid in listOf(null, "", "ZZ", "BQ", ".iq", "ıQ", "JP; safesearch=off")) {
            val filters = WebSearchFilters(country = invalid, countryDomainsOnly = true)
            assertNull(searchCountryCode(invalid))
            assertEquals("hi", braveResults(url("hi", filters))?.query)
            assertEquals(listOf("country=all", "safesearch=moderate", "useLocation=0"), cookiePairs(filters))
        }
    }

    @Test fun `private tabs never inherit an online default and result detection is scoped to Brave`() {
        for (mode in AddressSearchMode.entries) {
            assertEquals(AddressSearchMode.DEVICE, mode.forTab(privateTab = true))
            assertEquals(mode, mode.forTab(privateTab = false))
        }
        assertEquals("hi", braveResults("https://search.brave.com/images?q=hi")?.query)
        assertNull(braveResults("http://search.brave.com/search?q=hi"))
        assertNull(braveResults("https://search.brave.com.example/search?q=hi"))
        assertNull(braveResults("https://some.example/search?q=hi"))
        assertNull(braveResults("https://search.brave.com/settings?q=hi"))
        assertNull(braveResults("https://search.brave.com:8443/search?q=hi"))
        assertNull(braveResults("https://search.brave.com/search?q=+"))
    }

    private fun url(term: String, filters: WebSearchFilters = WebSearchFilters()) = braveSearchUrl(WebSearchRequest(term), filters)
    private fun cookiePairs(filters: WebSearchFilters) = braveSearchCookies(filters).map { it.substringBefore(';') }
}
