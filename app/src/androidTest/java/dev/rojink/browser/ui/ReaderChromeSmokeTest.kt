package dev.rojink.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rojink.browser.MainActivity
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.search.AddressSearchMode
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
        compose.onNodeWithTag("sipan-web-context").assertIsDisplayed()
        field.assertTextContains("https://search.brave.com/search?q=hi", substring = true)
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
        field.assertTextContains("https://search.brave.com/search?q=weather%20in%20Erbil", substring = true)
    }

    private fun withAddressSearch(mode: AddressSearchMode, block: () -> Unit) {
        val store = (compose.activity.application as RojInkApp).settings
        val prefs = runBlocking { store.values.first() }
        runBlocking { store.update { it.copy(uiLanguage = "en", addressSearch = mode) } }
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

            // The sample is served by WebView from bundled assets. Never start live translation in this test.
            compose.runOnIdle {
                compose.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("demo", "https://rojink.invalid/demo/"))
            }
            compose.onNodeWithTag("reader-paste").assertIsDisplayed().performClick()
            compose.waitUntil(18_000) { compose.onAllNodes(hasTestTag("reader-translate") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
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
