package dev.rojink.browser.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rojink.browser.MainActivity
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.DocumentFeatures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenSearchImageSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun directOpeningDoesNotDependOnTheSitePreviewOrDisablingTrackers() = checkImageOpening(privateTab = false)

    @Test fun directOpeningStaysInThePrivateTabAndOutOfHistory() = checkImageOpening(privateTab = true)

    private fun checkImageOpening(privateTab: Boolean) {
        val results = "https://search.brave.com/images?q=Erbil"
        val kind = if (privateTab) "private" else "normal"
        val image = "https://imgs.search.brave.com/sipan-smoke-$kind.png"
        val pixels = compose.activity.assets.open("demo/menu.png").use { it.readBytes() }
        val html = """
            <!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin: 0 }
              button { display: block; position: relative; border: 0; padding: 0 }
              img { display: block; width: 100vw; height: 100vh; object-fit: contain }
              #card { position: absolute; inset: 0 }
            </style>
            <button type="button" aria-label="Image result with no working preview">
              <img src="$image" alt="Sample result" onload="document.title='Image ready'">
              <span id="card" aria-hidden="true"></span>
            </button>
        """.trimIndent()
        val browser = compose.activity.browser
        if (privateTab) assumeTrue("Isolated profiles are unavailable", compose.runOnIdle { browser.supportsPrivate })
        val tab = compose.runOnIdle {
            if (privateTab) assertTrue(browser.newTab(privateTab = true))
            browser.apply(browser.settings.copy(trackerBlocking = true))
            browser.features = object : DocumentFeatures by browser.features {
                override fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse = when (request.url.toString()) {
                    results -> WebResourceResponse("text/html", "utf-8", html.byteInputStream())
                    image -> WebResourceResponse("image/png", null, 200, "OK", mapOf("Cache-Control" to "no-store"), pixels.inputStream())
                    else -> WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                }
            }
            assertTrue(browser.navigate(results))
            browser.selected
        }
        val view = compose.runOnIdle { checkNotNull(tab.view) }
        compose.waitUntil(12_000) {
            compose.runOnIdle { tab.title == "Image ready" && tab.progress == 100 && view.isAttachedToWindow && view.height > 0 }
        }

        // Fire the result card's DOM click inside the real WebView, independent of the emulator window focus.
        val cardClick = """
            (() => {
              const x = Math.max(1, innerWidth / 2), y = Math.max(1, innerHeight / 2);
              return document.getElementById('card').dispatchEvent(new MouseEvent('click', {
                bubbles: true, cancelable: true, detail: 1, clientX: x, clientY: y
              }));
            })()
        """.trimIndent()
        assertEquals("true", evaluate(view, cardClick))
        fun toggle() {
            compose.onNodeWithTag("sipan-direct-images").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { click ->
                assertTrue(click())
            }
        }
        toggle()
        compose.waitUntil(8_000) { compose.runOnIdle { tab.directImagePage == results } }
        if (!privateTab) {
            toggle()
            compose.waitUntil(8_000) { compose.runOnIdle { tab.directImagePage == null } }
            assertEquals("Cancelled mode leaves Brave's click alone", "true", evaluate(view, cardClick))
            toggle()
            compose.waitUntil(8_000) { compose.runOnIdle { tab.directImagePage == results } }
        }
        val handled = evaluate(view, cardClick)
        if (handled != "false") {
            val state = evaluate(view, """
                (() => {
                  const pic = document.querySelector('img');
                  const card = document.getElementById('card');
                  return { href: location.href, armed: typeof window.__sipanClearDirectImage, viewport: [innerWidth, innerHeight],
                    src: pic?.currentSrc, width: pic?.naturalWidth, card: card?.parentElement?.localName,
                    hits: document.elementsFromPoint(innerWidth / 2, innerHeight / 2).map(el => el.localName) };
                })()
            """.trimIndent())
            throw AssertionError("The direct mode did not handle the picture click: $handled; $state")
        }
        // Chromium may reuse the decoded thumbnail: verify the actual document, not a second network request.
        try {
            compose.waitUntil(12_000) { compose.runOnIdle { tab.address == image && view.url == image && tab.progress == 100 } }
        } catch (failure: ComposeTimeoutException) {
            val state = compose.runOnIdle { "address=${tab.address} web=${view.url} progress=${tab.progress} failed=${tab.failed}" }
            throw AssertionError("The standalone image did not load: $state", failure)
        }
        assertEquals("A standalone image should decode in the WebView", "true", evaluate(view,
            "document.contentType === 'image/png' && document.images.length === 1 && document.images[0].naturalWidth > 0"))

        compose.runOnIdle {
            assertSame(tab, browser.selected)
            assertSame(view, tab.view)
            assertTrue(browser.settings.trackerBlocking)
            assertTrue(browser.back())
        }
        compose.waitUntil(12_000) {
            compose.runOnIdle { tab.address == results && tab.title == "Image ready" && tab.progress == 100 && tab.directImagePage == null }
        }
        assertEquals("Going back restores the normal preview behavior", "true", evaluate(view, cardClick))
        if (privateTab) {
            val dao = (compose.activity.application as RojInkApp).dao
            assertTrue(runBlocking { dao.visits().first() }.none { it.url == image })
        }
    }

    private fun evaluate(view: WebView, script: String): String? {
        val result = CompletableDeferred<String?>()
        compose.runOnIdle { view.evaluateJavascript(script) { result.complete(it) } }
        return runBlocking { withTimeout(5_000) { result.await() } }
    }
}
