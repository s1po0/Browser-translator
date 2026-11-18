package dev.rojink.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rojink.browser.MainActivity
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.search.AddressSearchMode
import dev.rojink.browser.search.WebSafeSearch
import dev.rojink.browser.search.WebSearchFilters
import dev.rojink.browser.search.WebSearchRequest
import dev.rojink.browser.search.braveSearchUrl
import dev.rojink.browser.translation.ENGLISH_IMAGE_LANGUAGE
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.DocumentFeatures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

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
        filters.assertIsDisplayed().activate()
        compose.onNodeWithTag("sipan-country-picker").activate()
        compose.onNodeWithTag("sipan-country-query").performTextInput("عێراق")
        compose.onNodeWithTag("sipan-country-IQ").assertIsDisplayed().activate()
        compose.onNodeWithTag("sipan-country-picker").assert(hasText("Country: Iraq"))
        compose.onNodeWithTag("sipan-safety-off").performScrollTo().activate().assertIsSelected()
        compose.onNodeWithTag("sipan-apply-filters").activate()

        val store = (compose.activity.application as RojInkApp).settings
        val expected = WebSearchFilters(country = "IQ", safeSearch = WebSafeSearch.OFF)
        awaitWebFilters(expected)
        compose.onNodeWithTag("sipan-web-context").assertDoesNotExist()
        filters.assert(hasText("Iraq (.iq) · Safe Search: Off"))
        compose.onNodeWithTag("sipan-web-search-once").performClick()
        awaitAddress(braveSearchUrl(WebSearchRequest("Erbil"), expected)!!)

        // Reapplying in the results header removes the previous domain operator rather than stacking it.
        filters.activate()
        compose.onNodeWithTag("sipan-country-picker").activate()
        compose.onNodeWithTag("sipan-country-world").activate()
        compose.onNodeWithTag("sipan-country-picker").assert(hasText("Country: Worldwide"))
        compose.onNodeWithTag("sipan-safety-strict").performScrollTo().activate().assertIsSelected()
        compose.onNodeWithTag("sipan-apply-filters").activate()
        val worldwide = expected.copy(country = null, safeSearch = WebSafeSearch.STRICT)
        awaitWebFilters(worldwide)
        awaitAddress(braveSearchUrl(WebSearchRequest("Erbil"), worldwide)!!, exact = true)
        compose.onNodeWithTag("sipan-device-search").performClick()
        compose.onNodeWithTag("sipan-query").assertTextContains("Erbil")
        assertTrue(runBlocking { store.values.first() }.addressSearch == AddressSearchMode.DEVICE)
    }

    @Test fun nativeSearchCategoriesAndLocalMatchesDoNotSendUnsubmittedText() = withAddressSearch(AddressSearchMode.DEVICE) { requests ->
        compose.onNodeWithTag("sipan-open-search").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sipan-scope-web").assertIsSelected()
        val query = compose.onNodeWithTag("sipan-query")
        query.performTextInput("Erbil citadel")
        compose.onNodeWithTag("sipan-scope-images").performClick().assertIsSelected()
        compose.onNodeWithTag("sipan-web-context").assertDoesNotExist()
        assertTrue("Typing and choosing a category do not send a query", requests.isEmpty())

        compose.onNodeWithTag("sipan-web-search-once").performClick()
        awaitAddress("https://search.brave.com/images?q=Erbil%20citadel", exact = true)
        compose.onNodeWithTag("sipan-result-images").assertIsSelected()
        compose.onNodeWithTag("sipan-result-news").performClick()
        awaitAddress("https://search.brave.com/news?q=Erbil%20citadel", exact = true)
        compose.onNodeWithTag("sipan-result-videos").performClick()
        val videos = "https://search.brave.com/videos?q=Erbil%20citadel"
        awaitAddress(videos, exact = true)
        compose.waitUntil(8_000) { videos in requests }
        val submitted = requests.toList()

        compose.onNodeWithTag("sipan-edit-search").performClick()
        compose.onNodeWithTag("sipan-scope-videos").assertIsSelected()
        query.assertTextContains("Erbil citadel")
        compose.onNodeWithTag("sipan-scope-device").performScrollTo().performClick().assertIsSelected()
        query.performClick().performTextReplacement("PaddleOCR")
        // Matched from the local index while typing, without an IME action or a network request.
        compose.waitUntil(8_000) { compose.onAllNodes(hasText("PaddleOCR · GitHub")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(requests.toList() == submitted)
    }

    @Test fun webSearchToolsSurviveEditingAndDoNotLeakIntoImages() = withAddressSearch(AddressSearchMode.DEVICE) { requests ->
        compose.onNodeWithTag("sipan-open-search").performClick()
        val query = compose.onNodeWithTag("sipan-query")
        query.performTextInput("Erbil Citadel")
        val tools = compose.onNodeWithTag("sipan-search-tools")
        tools.performScrollTo().performClick()
        compose.onNodeWithTag("sipan-date-week").performClick()
        compose.onNodeWithTag("sipan-exact-phrase").performScrollTo().performClick()
        compose.onNodeWithTag("sipan-pdf").performScrollTo().performClick()
        compose.onNodeWithTag("sipan-apply-search-tools").performClick()
        assertTrue("Applying tools to a draft does not submit the search", requests.isEmpty())
        compose.onNodeWithTag("sipan-web-search-once").performScrollTo().performClick()
        awaitAddress("https://search.brave.com/search?q=%22Erbil%20Citadel%22%20filetype%3Apdf&tf=pw&spellcheck=0", exact = true)
        compose.onNodeWithTag("sipan-edit-search").performClick()
        query.assertTextContains("Erbil Citadel")
        tools.assert(hasText("Search tools · Past week · Exact phrase · Find PDFs"))
        compose.onNodeWithTag("sipan-scope-images").performClick()
        tools.assertDoesNotExist()
        compose.onNodeWithTag("sipan-web-search-once").performClick()
        awaitAddress("https://search.brave.com/images?q=Erbil%20Citadel", exact = true)
    }

    // These tests exercise the filter controls and persistence, not IME/window transition timing.
    private fun SemanticsNodeInteraction.activate(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }

    private fun awaitWebFilters(expected: WebSearchFilters) {
        val store = (compose.activity.application as RojInkApp).settings
        val actual = runBlocking {
            withTimeoutOrNull(8_000) { store.values.first { it.webSearchFilters == expected } }
                ?: store.values.first()
        }
        assertEquals("Persisted web filters", expected, actual.webSearchFilters)
    }

    private fun awaitAddress(url: String, exact: Boolean = false) {
        val match = hasTestTag("reader-address") and hasText(url, substring = !exact)
        compose.waitUntil(12_000) { compose.onAllNodes(match).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun withAddressSearch(mode: AddressSearchMode, block: (List<String>) -> Unit) {
        val store = (compose.activity.application as RojInkApp).settings
        val prefs = runBlocking { store.values.first() }
        val requests = CopyOnWriteArrayList<String>()
        // Keep the navigation tests independent of the public site's redirects, connection and content.
        compose.runOnIdle {
            val browser = compose.activity.browser
            val original = browser.features
            browser.features = object : DocumentFeatures by original {
                override fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.scheme == "https" && request.url.host == "search.brave.com") {
                        if (request.isForMainFrame) requests.add(request.url.toString())
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
            block(requests)
        } finally { runBlocking { store.update { prefs } } }
    }

    @Test fun settingsOfferPlainEnglishAndRememberItSeparatelyFromEnglishComics() {
        val store = (compose.activity.application as RojInkApp).settings
        val prefs = runBlocking { store.values.first() }
        runBlocking { store.update { it.copy(uiLanguage = "en", ocrHint = "auto") } }
        val settings = hasText("Settings") and hasClickAction()
        val imageLanguage = hasText("Image text language or script") and hasClickAction()
        fun option(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
        try {
            compose.waitUntil(8_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(settings).activate()
            compose.onNodeWithTag("reader-settings-list").performScrollToNode(imageLanguage)
            compose.onNode(imageLanguage).activate()
            option("Auto-detect").assertIsSelected()
            option("English comics").assertIsNotSelected()
            option("Devanagari").performScrollTo().assertIsDisplayed()
            option("English").performScrollTo().assertIsDisplayed().assertIsNotSelected().activate()

            runBlocking { withTimeout(8_000) { store.values.first { it.ocrHint == ENGLISH_IMAGE_LANGUAGE } } }
            compose.waitUntil(8_000) { compose.onAllNodes(imageLanguage and hasText("English")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(imageLanguage).activate()
            option("English").assertIsSelected()
            option("English comics").assertIsNotSelected()
        } finally { runBlocking { store.update { prefs } } }
    }

    private fun awaitClipboardFocus() {
        fun focused() = compose.runOnIdle { compose.activity.hasWindowFocus() }
        if (focused()) return
        // OCR and WebView tests can precede this Activity on a headless emulator. Restore its display
        // instead of asserting that Compose being idle implies Android has granted a focused window.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        compose.runOnIdle { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("cmd statusbar collapse")
        try {
            compose.waitUntil(12_000) { focused() }
        } catch (failure: ComposeTimeoutException) {
            val windows = shell("dumpsys window displays").lineSequence()
                .filter { "mCurrentFocus=" in it || "mFocusedApp=" in it || "mAwake=" in it || "mScreenOn" in it }
                .joinToString("; ") { it.trim() }.take(490)
            throw AssertionError("Clipboard test needs the app's focused window: $windows", failure)
        }
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
            awaitClipboardFocus()
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
