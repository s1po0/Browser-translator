package dev.rojink.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rojink.browser.MainActivity
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.search.AddressSearchMode
import dev.rojink.browser.search.WebSafeSearch
import dev.rojink.browser.search.WebSearchFilters
import dev.rojink.browser.search.braveSearchUrl
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.DocumentFeatures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderChromeSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sipanSearchFindsTheBundledIndexFromTheAddressBar() = withAddressSearch(AddressSearchMode.DEVICE) {
        val field = compose.onNodeWithTag("reader-address")
        field.performTextInput("paddleocr")
        field.performImeAction()
        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag("sipan-results")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("PaddleOCR · GitHub").assertIsDisplayed()
        compose.onNodeWithTag("sipan-web-search-once").assertIsDisplayed()
        compose.onNodeWithTag("reader-translate").assertDoesNotExist()
    }

    @Test fun shortQueryCanEnableWebSearchAndReturnToTheSameLocalTerm() = withAddressSearch(AddressSearchMode.ASK) {
        val field = compose.onNodeWithTag("reader-address")
        field.performTextInput("hi")
        compose.onNodeWithTag("sipan-web-context").assertDoesNotExist()
        field.performImeAction()
        compose.onNodeWithTag("sipan-enable-web").assertIsDisplayed()
        compose.onNodeWithTag("sipan-web-context").assertDoesNotExist()
        compose.onNodeWithTag("sipan-enable-web").performClick()
        // Check the app's real navigation and UI, independent of the external site's availability/content.
        awaitAddress("https://search.brave.com/search?q=hi")
        compose.onNodeWithTag("sipan-web-context").assertIsDisplayed()
        val store = (compose.activity.application as RojInkApp).settings
        runBlocking { withTimeout(8_000) { store.values.first { it.addressSearch == AddressSearchMode.WEB } } }

        compose.onNodeWithTag("sipan-device-search").performClick()
        compose.onNodeWithTag("sipan-query").assertTextContains("hi")
        compose.waitUntil(8_000) { compose.onAllNodes(hasText("No on-device matches")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sipan-web-search-once").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        field.performClick()
        field.performTextReplacement("weather in Erbil")
        field.performImeAction()
        compose.onNodeWithTag("sipan-enable-web").assertDoesNotExist()
        awaitAddress("https://search.brave.com/search?q=weather%20in%20Erbil")
    }

    @Test fun countryAndSafeSearchApplyToWebResultsWithoutOptingInToAddressSearch() = withAddressSearch(AddressSearchMode.DEVICE) {
        val address = compose.onNodeWithTag("reader-address")
        address.performTextInput("Erbil")
        address.performImeAction()
        val filters = compose.onNodeWithTag("sipan-search-filters")
        filters.assertIsDisplayed().performClick()
        compose.onNodeWithTag("sipan-country-picker").performClick()
        compose.onNodeWithTag("sipan-country-query").performTextInput("عێراق")
        compose.onNodeWithTag("sipan-country-IQ").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sipan-safety-off").performScrollTo().performClick()
        compose.onNodeWithTag("sipan-apply-filters").performClick()

        val store = (compose.activity.application as RojInkApp).settings
        val expected = WebSearchFilters(country = "IQ", safeSearch = WebSafeSearch.OFF)
        runBlocking { withTimeout(8_000) { store.values.first { it.webSearchFilters == expected } } }
        compose.onNodeWithTag("sipan-web-context").assertDoesNotExist()
        filters.assert(hasText("Iraq (.iq) · Safe Search: Off"))
        compose.onNodeWithTag("sipan-web-search-once").performClick()
        awaitAddress(braveSearchUrl("Erbil", expected)!!)

        // Reapplying in the results header removes the previous domain operator rather than stacking it.
        filters.performClick()
        compose.onNodeWithTag("sipan-country-picker").performClick()
        compose.onNodeWithTag("sipan-country-world").performClick()
        compose.onNodeWithTag("sipan-safety-strict").performScrollTo().performClick()
        compose.onNodeWithTag("sipan-apply-filters").performClick()
        val worldwide = expected.copy(country = null, safeSearch = WebSafeSearch.STRICT)
        runBlocking { withTimeout(8_000) { store.values.first { it.webSearchFilters == worldwide } } }
        awaitAddress(braveSearchUrl("Erbil", worldwide)!!, exact = true)
        compose.onNodeWithTag("sipan-device-search").performClick()
        compose.onNodeWithTag("sipan-query").assertTextContains("Erbil")
        assertTrue(runBlocking { store.values.first() }.addressSearch == AddressSearchMode.DEVICE)
    }

    private fun awaitAddress(url: String, exact: Boolean = false) {
        val match = hasTestTag("reader-address") and hasText(url, substring = !exact)
        compose.waitUntil(12_000) { compose.onAllNodes(match).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun withAddressSearch(mode: AddressSearchMode, block: () -> Unit) {
        val store = (compose.activity.application as RojInkApp).settings
        val prefs = runBlocking { store.values.first() }
        // Keep the navigation tests independent of the public site's redirects, connection and content.
        compose.runOnIdle {
            val browser = compose.activity.browser
            val original = browser.features
            browser.features = object : DocumentFeatures by original {
                override fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.scheme == "https" && request.url.host == "search.brave.com") {
                        return WebResourceResponse("text/html", "utf-8", "<!doctype html><title>Search test</title>".byteInputStream())
                    }
                    return original.intercept(tab, request)
                }
            }
        }
        runBlocking { store.update { it.copy(uiLanguage = "en", addressSearch = mode, webSearchFilters = WebSearchFilters()) } }
        try {
            val hint = if (mode == AddressSearchMode.DEVICE) "Enter a link or search this device" else "Enter a link or search"
            compose.waitUntil(8_000) { compose.onAllNodes(hasText(hint)).fetchSemanticsNodes().isNotEmpty() }
            block()
        } finally { runBlocking { store.update { prefs } } }
    }

    @Test fun soraniMirrorsChromeAndPasteOpensConsentSheet() {
        val store = (compose.activity.application as RojInkApp).settings
        val prefs = runBlocking { store.values.first() }
        runBlocking { store.update { it.copy(uiLanguage = "en", target = "ckb", translateText = true, translateImages = true) } }
        val language = compose.onNodeWithTag("reader-ui-language")
        try {
            compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag("reader-ui-language") and hasText("کوردی")).fetchSemanticsNodes().isNotEmpty() }
            val englishPosition = language.fetchSemanticsNode().boundsInRoot.center.x
            language.performClick()
            compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag("reader-ui-language") and hasText("English")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("The language button moves to the logical end in RTL", language.fetchSemanticsNode().boundsInRoot.center.x < englishPosition)

            // Android restricts clipboard reads to the focused app. Set the clip and tap Paste
            // together on the UI thread, before a focus/clipboard-overlay transition can intervene.
            val demo = "https://rojink.invalid/demo/"
            compose.waitUntil(8_000) { compose.runOnIdle { compose.activity.hasWindowFocus() } }
            compose.onNodeWithTag("reader-paste").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { click ->
                val activity = compose.activity
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("demo", demo))
                assertTrue(click())
            }
            awaitAddress(demo, exact = true)
            // The sample is served from bundled assets. Never start live translation in this test.
            try {
                compose.waitUntil(18_000) { compose.onAllNodes(hasTestTag("reader-translate") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: ComposeTimeoutException) {
                val state = compose.runOnIdle {
                    val tab = compose.activity.browser.selected
                    "address=${tab.address} progress=${tab.progress} failed=${tab.failed} WebView=${tab.view?.url}"
                }
                throw AssertionError("Pasted sample didn't expose Translate: $state", failure)
            }
            compose.onNodeWithTag("reader-translation-sheet").assertDoesNotExist()
            compose.onNodeWithTag("reader-translate").performClick()
            compose.onNodeWithTag("reader-target-language").assert(hasText("کوردیی سۆرانی"))
            val text = compose.onNodeWithTag("reader-translate-text")
            val images = compose.onNodeWithTag("reader-translate-images")
            text.assertIsOn().performClick()
            images.assertIsOn().performClick()
            compose.onNodeWithTag("reader-start-translation").assertIsNotEnabled()
            images.performClick()
            compose.onNodeWithTag("reader-start-translation").assertIsEnabled()
        } finally {
            runBlocking { store.update { prefs } }
        }
    }
}
