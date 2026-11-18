package dev.rojink.browser.translation

import android.os.SystemClock
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rojink.browser.MainActivity
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.web.Browser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun consentTextSoraniAndOriginalAreRestoredInARealWebView() = withReader { reader ->
        val before = reader.eval("document.querySelector('h1').outerHTML")
        assertTrue(before.contains("Beyond the hill"))
        SystemClock.sleep(550)
        assertTrue("No provider requests before Start", reader.provider.inputs.isEmpty())
        reader.main { reader.translation.start(reader.browser.selected, ReaderOptions("ckb", true, false, "auto", false)) }
        waitUntil("translated heading and auto direction") {
            reader.eval("document.querySelector('h1').dir + ':' + document.querySelector('h1').textContent") == "auto:لە پشت گردەکە"
        }
        reader.main { reader.translation.toggle(reader.browser.selected) }
        waitUntil("exact heading restoration") { reader.eval("document.querySelector('h1').outerHTML") == before }
        reader.main { reader.translation.toggle(reader.browser.selected) }
        waitUntil("translation toggle") { reader.eval("document.querySelector('h1').textContent") == "لە پشت گردەکە" }
        reader.main { reader.translation.stop(reader.browser.selected) }
        waitUntil("full restore on stop") { reader.eval("document.querySelector('h1').outerHTML") == before }
        assertTrue(reader.provider.targets.all { it == "ckb" })
    }

    @Test fun paintedPanelLoadsSupportsHoldAndTranslatesLazyMenu() = withReader { reader ->
        assertTrue(reader.provider.inputs.isEmpty())
        reader.main { reader.translation.start(reader.browser.selected, ReaderOptions("ckb", false, true, "latin", false)) }
        val chapter = "document.querySelector('figure img')"
        waitUntil("painted manga PNG loads", 45_000) {
            reader.eval("$chapter.src.includes('/.__rojink/') && $chapter.complete && $chapter.naturalWidth > 0") == "true"
        }
        assertTrue(reader.provider.inputs.any { it.contains("light", true) || it.contains("home", true) })
        reader.eval("$chapter.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:1,clientX:3,clientY:3}))")
        waitUntil("press and hold reveals original") { reader.eval("$chapter.getAttribute('src')") == "chapter.png" }
        reader.eval("$chapter.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:1}))")
        waitUntil("release returns translation") { reader.eval("$chapter.getAttribute('src')").contains("/.__rojink/") }
        reader.eval("document.querySelector('#later').scrollIntoView()")
        waitUntil("lazy menu also loads translated", 30_000) {
            reader.eval("document.querySelector('#later img').src.includes('/.__rojink/') && document.querySelector('#later img').naturalWidth > 0") == "true"
        }
        assertTrue(reader.provider.inputs.any { it.contains("mint tea", true) })
        assertFalse(reader.provider.inputs.any { it.contains("$3.50") })
    }

    @Test fun longInlineComicUsesOriginalPixelsAndKeepsTheWebViewBox() = withReader { reader ->
        val panel = instrumentation.context.assets.open("en-comic-coverage.png").use { BitmapFactory.decodeStream(it) }
        val strip = try { comicStrip(panel, 6000, intArrayOf(0)) } finally { panel.recycle() }
        val source = "data:image/png;base64," + Base64.encodeToString(strip, Base64.NO_WRAP)
        reader.eval("""(() => {
          document.querySelector('style').remove();
          const image = document.createElement('img');
          image.id = 'strip'; image.style.maxWidth = '1120px';
          image.src = ${JSONObject.quote(source)};
          document.body.replaceChildren(image);
        })()""")
        val image = "document.getElementById('strip')"
        waitUntil("inline strip loaded") { reader.eval("$image.complete && $image.naturalWidth === 1440 && $image.naturalHeight === 6000") == "true" }
        val height = reader.eval("$image.clientHeight").toInt()
        assertEquals("1120", reader.eval("$image.clientWidth"))
        assertTrue(reader.provider.inputs.isEmpty())
        reader.main { reader.translation.start(reader.browser.selected, ReaderOptions("ckb", false, true, ENGLISH_COMIC_SCRIPT, false)) }
        waitUntil("rendered long strip loads in the original box", 75_000) {
            reader.eval("$image.src.includes('/.__rojink/') && $image.complete && $image.naturalHeight > 1700") == "true"
        }
        assertEquals("Read the faint 9px dialogue from the original WebView image once: ${reader.provider.inputs}", 1,
            reader.provider.inputs.count { it.uppercase().filter(Char::isLetterOrDigit) == "IWASWRONG" })
        assertTrue(reader.provider.inputs.none { "example.com" in it || "$7.25" in it })
        assertEquals("1120", reader.eval("$image.clientWidth"))
        assertEquals(height.toDouble(), reader.eval("$image.clientHeight").toDouble(), 2.0)
        Log.i("SipanComicSmoke", "WebView inline 1440x6000: 9px dialogue read once, translated PNG loaded; layout held at 1120x$height")
        reader.main { reader.translation.stop(reader.browser.selected) }
        waitUntil("original inline image and attributes restored") {
            reader.eval("$image.src.startsWith('data:image/png;') && !$image.hasAttribute('width') && $image.naturalHeight === 6000") == "true"
        }
    }

    @Test fun JapaneseKoreanAndEnglishUseRealScriptModelsAndPreserveMenuPricePixels() = runBlocking {
        withTimeout(100_000) {
            ImagePipeline(instrumentation.targetContext).use { image ->
                val provider = SampleProvider()
                val providers = ReaderProviders(provider, "emulator-fixture-v1", null)
                val samples = listOf(Triple("jp-comic.png", "ja", "こんにちは"), Triple("kr-comic.png", "ko", "안녕하세요"))
                for ((asset, script, expected) in samples) {
                    val original = instrumentation.context.assets.open(asset).use { it.readBytes() }
                    val output = image.gallery(original, ReaderOptions("ckb", false, true, script, false), providers, true).bitmap
                    assertNotNull("Rendered $script PNG", output)
                    assertTrue("Found $expected via real OCR: ${provider.inputs}", provider.inputs.any { expected in it })
                    output!!.recycle()
                }
                val menu = instrumentation.targetContext.assets.open("demo/menu.png").use { it.readBytes() }
                val before = image.preview(menu)
                try {
                    val recognized = MlKitOcrProvider().use {
                        it.recognize(before, OcrHint(ENGLISH_IMAGE_LANGUAGE, "ja", enhanced = false))
                    }
                    assertEquals("Explicit English wins over the hosting page's language", Script.LATIN, recognized.script)
                    assertTrue(recognized.lines.any { it.text.contains("mint tea", ignoreCase = true) })
                    provider.inputs.clear()
                    provider.sources.clear()
                    val after = image.gallery(menu, ReaderOptions("ckb", false, true, ENGLISH_IMAGE_LANGUAGE, false), providers, true).bitmap
                    try {
                        assertNotNull("Rendered English menu", after)
                        assertTrue("English menu requests: ${provider.sources}", provider.sources.isNotEmpty() && provider.sources.all { it == "en" })
                        assertTrue(provider.inputs.any { it.contains("mint tea", ignoreCase = true) })
                        assertTrue(provider.inputs.none { "$3.50" in it })
                        for (y in 194..432 step 2) for (x in 750..875 step 2) {
                            assertEquals("Menu prices must stay at ($x,$y)", before.getPixel(x, y), after!!.getPixel(x, y))
                        }
                        Log.i("SipanComicSmoke", "English image mode: Latin OCR overrides page hint; menu uses MT source en and preserves price pixels")
                    } finally { after?.recycle() }
                } finally { before.recycle() }
            }
        }
    }

    @Test fun enhancedPassReadsFadedJapaneseAndKoreanLettering() = runBlocking {
        withTimeout(100_000) {
            MlKitOcrProvider().use { ocr ->
                val samples = listOf(Triple("jp-small-faded.png", "ja", "こんにちは"), Triple("kr-small-faded.png", "ko", "안녕하세요"))
                for ((asset, script, expected) in samples) {
                    val image = instrumentation.context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
                    try {
                        val baseline = ocr.recognize(image, OcrHint(script, "en", enhanced = false))
                        val enhanced = ocr.recognize(image, OcrHint(script, "en", enhanced = true))
                        fun hits(result: OcrPage) = result.lines.count { expected in it.text.filterNot(Char::isWhitespace) }
                        println("$asset exact line matches: ${hits(baseline)} baseline, ${hits(enhanced)} enhanced (two reference lines)")
                        assertEquals(0, baseline.enhancedTiles)
                        assertTrue("Expected a detail retry for $script", enhanced.enhancedTiles > 0)
                        assertTrue("Enhanced $script: ${enhanced.lines.map { it.text }} vs baseline ${baseline.lines.map { it.text }}",
                            hits(enhanced) >= hits(baseline) && hits(enhanced) >= 1)
                    } finally { image.recycle() }
                }
            }
        }
    }

    @Test fun explicitlyIndexingAPageAddsItsBodyWithoutStartingTranslation() = withReader { reader ->
        val dao = reader.browser.dao
        runBlocking { dao.clearSearchPages() }
        try {
            assertTrue(runBlocking { dao.searchPages().first() }.isEmpty())
            reader.main { reader.browser.indexCurrentPage() }
            val page = runBlocking { withTimeout(9_000) { dao.searchPages().first { it.isNotEmpty() }.single() } }
            assertTrue(page.title.contains("Sipan"))
            assertTrue(page.body.contains("Beyond the hill"))
            assertTrue(reader.provider.inputs.isEmpty())
        } finally { runBlocking { dao.clearSearchPages() } }
    }

    private fun withReader(block: (Harness) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ref = AtomicReference<Harness>()
            scenario.onActivity { activity ->
                val app = activity.application as RojInkApp
                val browser = Browser(activity, app.dao, app.library)
                val images = ImagePipeline(activity.applicationContext)
                val provider = SampleProvider()
                val reader = PageTranslator(activity, browser, { _, _ -> ReaderProviders(provider, "emulator-fixture-v1", null) }, images)
                browser.features = reader
                browser.setVisible(true)
                assertTrue(browser.navigate("https://rojink.invalid/demo/"))
                val web = browser.selected.view!!
                activity.addContentView(web, FrameLayout.LayoutParams(-1, -1))
                ref.set(Harness(browser, reader, images, provider, web))
            }
            val reader = ref.get()
            try {
                waitUntil("sample loaded") { reader.eval("document.readyState + ':' + document.querySelector('h1')?.textContent") == "complete:Beyond the hill" }
                block(reader)
            } finally {
                reader.main { reader.browser.destroy(); reader.images.close() }
            }
        }
    }

    private class Harness(val browser: Browser, val translation: PageTranslator, val images: ImagePipeline, val provider: SampleProvider, val web: WebView) {
        fun main(action: () -> Unit) { InstrumentationRegistry.getInstrumentation().runOnMainSync { action() } }
        fun eval(script: String): String {
            val result = AtomicReference<String>()
            val ready = CountDownLatch(1)
            main { web.evaluateJavascript(script) { value -> result.set(value); ready.countDown() } }
            check(ready.await(7, TimeUnit.SECONDS)) { "WebView eval timed out" }
            return (runCatching { JSONTokener(result.get()).nextValue() }.getOrNull() ?: "").toString()
        }
    }

    private class SampleProvider : TranslationProvider {
        val inputs = CopyOnWriteArrayList<String>()
        val targets = CopyOnWriteArrayList<String>()
        val sources = CopyOnWriteArrayList<String>()
        override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
            inputs.addAll(texts); targets += target; sources += source
            return texts.map { if (it == "Beyond the hill") "لە پشت گردەکە" else "سڵاو، هاوڕێ!" }
        }
    }

    private fun waitUntil(label: String, ms: Long = 18_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(130)
        }
        fail("Timed out waiting for $label")
    }
}
