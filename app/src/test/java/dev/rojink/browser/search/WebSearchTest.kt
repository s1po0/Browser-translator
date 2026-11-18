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
