package dev.rojink.browser.translation

import android.os.SystemClock
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rojink.browser.MainActivity
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.web.Browser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun JapaneseAndKoreanUseRealScriptModelsAndPreserveMenuPricePixels() = runBlocking {
        withTimeout(100_000) {
            ImagePipeline(instrumentation.targetContext).use { image ->
                val provider = SampleProvider()
                val providers = ReaderProviders(provider, "emulator-fixture-v1", null)
                val samples = listOf(Triple("jp-comic.png", "ja", "こんにちは"), Triple("kr-comic.png", "ko", "안녕하세요"))
                for ((asset, script, expected) in samples) {
                    val original = instrumentation.context.assets.open(asset).use { it.readBytes() }
                    val output = image.gallery(original, ReaderOptions("ckb", false, true, script, false), providers, true)
                    assertNotNull("Rendered $script PNG", output)
                    assertTrue("Found $expected via real OCR: ${provider.inputs}", provider.inputs.any { expected in it })
                    output!!.recycle()
                }
                val menu = instrumentation.targetContext.assets.open("demo/menu.png").use { it.readBytes() }
                val before = image.preview(menu)
                val after = image.gallery(menu, ReaderOptions("ckb", false, true, "latin", false), providers, true)
                assertNotNull(after)
                for (y in 194..432 step 2) for (x in 750..875 step 2) {
                    assertEquals("Menu prices must stay at ($x,$y)", before.getPixel(x, y), after!!.getPixel(x, y))
                }
                before.recycle()
                after!!.recycle()
            }
        }
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
        override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
            inputs.addAll(texts); targets += target
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
