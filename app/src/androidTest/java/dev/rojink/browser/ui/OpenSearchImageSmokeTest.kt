package dev.rojink.browser.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rojink.browser.MainActivity
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.DocumentFeatures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class OpenSearchImageSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensThePictureNotTheSurroundingLinkWithTrackerBlockingOn() = checkImageMenu(privateTab = false)

    @Test fun opensInTheSamePrivateTabWithoutOfferingToSave() = checkImageMenu(privateTab = true)

    private fun checkImageMenu(privateTab: Boolean) {
        val results = "https://search.brave.com/images?q=Erbil"
        val image = "https://imgs.search.brave.com/sipan-smoke-test.png"
        val pixels = compose.activity.assets.open("demo/menu.png").use { it.readBytes() }
        val img = """<img src="$image" alt="Sample result" onload="document.title='Image ready'">"""
        val result = if (privateTab) img else """<a href="https://search.brave.com/source-page">$img</a>"""
        val html = """
            <!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body { margin: 0 } img { display: block; width: 100vw; height: 100vh; object-fit: contain }</style>
            $result
        """.trimIndent()
        val imageOpened = AtomicBoolean(false)
        val browser = compose.activity.browser
        if (privateTab) assumeTrue("Isolated profiles are unavailable", compose.runOnIdle { browser.supportsPrivate })
        val tab = compose.runOnIdle {
            if (privateTab) assertTrue(browser.newTab(privateTab = true))
            browser.apply(browser.settings.copy(trackerBlocking = true))
            browser.features = object : DocumentFeatures by browser.features {
                override fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse = when (request.url.toString()) {
                    results -> WebResourceResponse("text/html", "utf-8", html.byteInputStream())
                    image -> {
                        if (request.isForMainFrame) imageOpened.set(true)
                        WebResourceResponse("image/png", null, 200, "OK", mapOf("Cache-Control" to "no-store"), pixels.inputStream())
                    }
                    else -> WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                }
            }
            assertTrue(browser.navigate(results))
            browser.selected
        }
        val view = compose.runOnIdle { tab.view }
        compose.waitUntil(12_000) { compose.runOnIdle { tab.title == "Image ready" && tab.progress == 100 } }

        // A physical hold exercises Android WebView's real image/linked-image hit test.
        compose.onNodeWithTag("reader-web-page").assertIsDisplayed().performTouchInput { longClick() }
        val save = compose.onNodeWithTag("reader-save-image")
        if (privateTab) save.assertDoesNotExist() else save.assertIsDisplayed()
        compose.onNodeWithTag("reader-open-image").assertIsDisplayed().performClick()
        compose.waitUntil(12_000) { imageOpened.get() && compose.runOnIdle { tab.address == image && tab.canBack && tab.progress == 100 } }
        val rendered = CompletableDeferred<Boolean>()
        compose.runOnIdle {
            checkNotNull(view).evaluateJavascript("document.images.length === 1 && document.images[0].naturalWidth > 0") {
                rendered.complete(it == "true")
            }
        }
        assertTrue("A standalone image should be decoded in the WebView", runBlocking { withTimeout(5_000) { rendered.await() } })

        compose.runOnIdle {
            assertSame(tab, browser.selected)
            assertSame(view, tab.view)
            assertTrue(browser.settings.trackerBlocking)
            assertTrue(browser.back())
        }
        compose.waitUntil(12_000) { compose.runOnIdle { tab.address == results && tab.title == "Image ready" } }
    }
}
