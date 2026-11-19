package dev.rojink.browser.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rojink.browser.MainActivity
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.DocumentFeatures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SourceReaderSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun separateReaderBlocksAdRequestsAndSlotsAndRestoresTheBrowserTab() {
        val activity = compose.activity
        val browser = activity.browser
        val savedFeatures = browser.features
        val preferences = (activity.application as RojInkApp).settings
        val before = runBlocking { preferences.values.first() }
        val chapter = "https://source.fixture/chapter-1"
        val image = "https://source.fixture/panel.png"
        val ad = "https://cdn.popads.net/test-ad.js"
        val served = CopyOnWriteArrayList<String>()
        val page = """<html><body><div class="ad-banner" id="banner">Test ad</div>
            <article id="chapter"><img src="$image"><a href="/chapter-2">Next chapter</a></article>
            <script src="$ad"></script></body></html>"""
        runBlocking { preferences.update { it.copy(uiLanguage = "en", trackerBlocking = false) } }
        compose.runOnIdle {
            browser.features = object : DocumentFeatures {
                override fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url.toString()
                    served += url
                    return when (url) {
                        chapter -> WebResourceResponse("text/html", "utf-8", page.byteInputStream())
                        image -> WebResourceResponse("image/png", null, activity.assets.open("demo/chapter.png"))
                        ad -> WebResourceResponse("text/javascript", "utf-8", "window.fixtureAdRan = true;".byteInputStream())
                        else -> WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                    }
                }
            }
        }
        try {
            val originalTab = compose.runOnIdle { browser.selected }
            compose.waitUntil(8_000) {
                !browser.settings.trackerBlocking && compose.onAllNodesWithContentDescription("Browser menu").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Browser menu").performClick()
            compose.onNodeWithText("Source reader").performClick()
            compose.onNodeWithTag("source-reader").assertIsDisplayed()
            val linkField = compose.onNodeWithTag("source-reader-link")
            linkField.performTextReplacement(chapter)
            linkField.performImeAction()
            compose.waitUntil(15_000) { browser.selected.address == chapter && browser.selected.progress == 100 && image in served }
            compose.onNodeWithTag("source-reader-translate").assertIsEnabled()
            assertEquals("true", evaluate("""
                document.querySelector('#chapter img').getAttribute('src') === '$image' &&
                document.querySelector('#chapter a').getAttribute('href') === '/chapter-2' &&
                getComputedStyle(document.querySelector('#banner')).display === 'none' && window.fixtureAdRan !== true
            """.trimIndent()))
            assertFalse(ad in served)
            assertTrue(compose.runOnIdle { browser.selected.view!!.settings.supportMultipleWindows() })

            compose.onNodeWithContentDescription("Close source reader").performClick()
            compose.onNodeWithTag("source-reader").assertDoesNotExist()
            assertSame(originalTab, compose.runOnIdle { browser.selected })
            assertTrue(compose.runOnIdle { browser.selected.address.isEmpty() })

            compose.runOnIdle { assertTrue(browser.navigate(chapter)) }
            compose.waitUntil(15_000) { browser.selected.address == chapter && browser.selected.progress == 100 && ad in served }
            assertEquals("true", evaluate("getComputedStyle(document.querySelector('#banner')).display !== 'none' && window.fixtureAdRan === true"))
        } finally {
            compose.runOnIdle { browser.features = savedFeatures }
            runBlocking { preferences.update { before } }
        }
    }

    private fun evaluate(script: String): String {
        val result = AtomicReference<String>()
        compose.runOnUiThread { compose.activity.browser.selected.view!!.evaluateJavascript(script, result::set) }
        compose.waitUntil(8_000) { result.get() != null }
        return checkNotNull(result.get())
    }
}
