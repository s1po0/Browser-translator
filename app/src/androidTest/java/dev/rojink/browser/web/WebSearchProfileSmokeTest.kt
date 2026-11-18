package dev.rojink.browser.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.search.BRAVE_SEARCH_ORIGIN
import dev.rojink.browser.search.WebSafeSearch
import dev.rojink.browser.search.WebSearchCategory
import dev.rojink.browser.search.WebSearchFilters
import dev.rojink.browser.search.WebSearchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebSearchProfileSmokeTest {
    @Test fun webSearchPreferencesStayInTheSelectedWebViewProfile() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RojInkApp
        withContext(Dispatchers.Main) {
            val browser = Browser(app, app.dao, app.library)
            try {
                assumeTrue("This WebView must support isolated profiles", browser.supportsPrivate)
                // Real WebView and cookie stores; a local response keeps this independent of Brave's availability.
                browser.features = object : DocumentFeatures {
                    override fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/html", "utf-8", "<!doctype html><title>Search test</title>".byteInputStream())
                }
                val normal = browser.selected
                assertTrue(browser.searchWeb(WebSearchRequest("train tickets", WebSearchCategory.NEWS), WebSearchFilters("JP", WebSafeSearch.STRICT)))
                assertEquals("https://search.brave.com/news?q=train%20tickets", normal.address)
                assertTrue(normal.searchCookies().containsAll(listOf("country=jp", "safesearch=strict", "useLocation=0")))

                assertTrue(browser.newTab(privateTab = true))
                val privateTab = browser.selected
                assertEquals(WebSearchFilters(), privateTab.privateSearchFilters)
                privateTab.privateSearchFilters = WebSearchFilters("IQ", WebSafeSearch.OFF)
                assertTrue(browser.searchWeb(WebSearchRequest("Erbil"), privateTab.privateSearchFilters))
                assertEquals("https://search.brave.com/search?q=Erbil%20site%3Aiq", privateTab.address)
                assertEquals("Erbil", privateTab.displayedWebSearch()?.query)
                assertTrue(privateTab.searchCookies().containsAll(listOf("country=all", "safesearch=off", "useLocation=0")))
                assertTrue(normal.searchCookies().containsAll(listOf("country=jp", "safesearch=strict")))
                browser.close(privateTab)
                assertTrue(browser.newTab(privateTab = true))
                assertEquals(WebSearchFilters(), browser.selected.privateSearchFilters)
            } finally {
                browser.destroy()
            }
        }
    }

    private fun BrowserTab.searchCookies(): Set<String> = checkNotNull(cookies).getCookie(BRAVE_SEARCH_ORIGIN)
        .orEmpty().split(';').map(String::trim).toSet()
}
