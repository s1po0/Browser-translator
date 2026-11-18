package dev.rojink.browser.search

import dev.rojink.browser.web.webUrl
import org.junit.Assert.*
import org.junit.Test

class WebSearchTest {
    @Test fun `the screenshot query opens the independent public results endpoint`() {
        assertEquals("https://search.brave.com/search?q=hi", braveSearchUrl(" hi "))
        assertNull(braveSearchUrl(" \t\n"))
    }

    @Test fun `the whole Sorani query is passed as one encoded parameter`() {
        val term = "هەواڵی هەولێر & images #1 + مانگا"
        val address = webUrl(braveSearchUrl(term))!!
        assertTrue(address.isHttps)
        assertEquals("search.brave.com", address.host)
        assertEquals(setOf("q"), address.queryParameterNames)
        assertEquals(term, address.queryParameter("q"))
        assertNull(address.fragment)
        assertEquals(term, braveResultsQuery(address.toString()))
        assertEquals("x".repeat(299), braveResultsQuery(braveSearchUrl("x".repeat(299) + "💬")))
    }

    @Test fun `a supported country prioritizes regional results without changing the query`() {
        val filters = WebSearchFilters(country = " jp ", safeSearch = WebSafeSearch.STRICT)
        assertNull(filters.domainFilter)
        assertEquals("hi", braveResultsQuery(braveSearchUrl("hi", filters)))
        assertEquals(listOf("country=jp", "safesearch=strict", "useLocation=0"), cookiePairs(filters))
    }

    @Test fun `Iraq uses its country domain and does not pretend to have a Brave preset`() {
        val filters = WebSearchFilters(country = "IQ", safeSearch = WebSafeSearch.OFF)
        val term = "هەواڵی هەولێر"
        val address = braveSearchUrl(term, filters)!!
        assertEquals("iq", filters.domainFilter)
        assertEquals("$term site:iq", braveResultsQuery(address))
        assertEquals(setOf("q"), webUrl(address)!!.queryParameterNames)
        assertEquals(listOf("country=all", "safesearch=off", "useLocation=0"), cookiePairs(filters))
        assertTrue(braveSearchCookies(filters).all { it.endsWith("; Path=/; Secure; SameSite=Lax") })
    }

    @Test fun `country domain scope is optional for the UK and keeps the full bounded user term`() {
        val filters = WebSearchFilters(country = "GB", countryDomainsOnly = true)
        val term = "a".repeat(SEARCH_QUERY_LIMIT)
        val address = braveSearchUrl(term + " longer", filters, path = "/news")!!
        assertEquals("/news", braveResultsPath(address))
        assertEquals("$term site:uk", braveResultsQuery(address))
        assertEquals(listOf("country=gb", "safesearch=moderate", "useLocation=0"), cookiePairs(filters))
        assertEquals("/search", braveResultsPath(braveSearchUrl("hi", filters, path = "//elsewhere.test")))
    }

    @Test fun `worldwide removes domain scope and invalid regions cannot enter URLs or cookies`() {
        for (invalid in listOf(null, "", "ZZ", "BQ", ".iq", "ıQ", "JP; safesearch=off")) {
            val filters = WebSearchFilters(country = invalid, countryDomainsOnly = true)
            assertNull(searchCountryCode(invalid))
            assertEquals("hi", braveResultsQuery(braveSearchUrl("hi", filters)))
            assertEquals(listOf("country=all", "safesearch=moderate", "useLocation=0"), cookiePairs(filters))
        }
    }

    private fun cookiePairs(filters: WebSearchFilters) = braveSearchCookies(filters).map { it.substringBefore(';') }

    @Test fun `private tabs never inherit an online default and result detection is scoped to Brave`() {
        for (mode in AddressSearchMode.entries) {
            assertEquals(AddressSearchMode.DEVICE, mode.forTab(privateTab = true))
            assertEquals(mode, mode.forTab(privateTab = false))
        }
        assertEquals("hi", braveResultsQuery("https://search.brave.com/images?q=hi"))
        assertNull(braveResultsQuery("https://search.brave.com.example/search?q=hi"))
        assertNull(braveResultsQuery("https://some.example/search?q=hi"))
        assertNull(braveResultsQuery("https://search.brave.com/settings?q=hi"))
        assertNull(braveResultsQuery("https://search.brave.com:8443/search?q=hi"))
    }
}
