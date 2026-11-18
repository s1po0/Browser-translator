package dev.rojink.browser.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnglishComicSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixture = listOf("MY AIM WAS OFF,", "AND I LOST FOCUS", "AFTER I TOOK", "A LONG BREAK.", "YOU CAN TRY", "AGAIN TOMORROW.")

    @Test fun bundledRuntimeRecognizesEachLineIncludingSpaces() = runBlocking {
        withTimeout(85_000) {
            val data = bytes
            val image = BitmapFactory.decodeByteArray(data, 0, data.size)
            try {
                val baseline = MlKitOcrProvider().use { it.recognize(image, OcrHint("latin", "", enhanced = false)).lines }
                val tiny = PaddleComicOcr(context).use { it.recognize(image) }
                fun hits(lines: List<OcrLine>) = fixture.count { expected -> lines.any { it.text == expected } }
                println("English comic exact fixture lines: ML Kit ${hits(baseline)}/6, Tiny ${hits(tiny)}/6")
                assertEquals("Tiny recognized ${tiny.map { it.text }}", fixture.size, hits(tiny))
                assertTrue(tiny.any { it.text == "$7.25" })
                val layout = groupForImage(OcrPage(tiny, false, comic = true), ComicSurface(image)::sameInterior)
                assertEquals(listOf(fixture.take(4).joinToString(" "), fixture.drop(4).joinToString(" ")), layout.units.map { it.text })
            } finally { image.recycle() }
        }
    }

    @Test fun gallerySendsSeparateWholeBubblesAndPaintsSoraniWithoutTouchingPricesOrArt() = runBlocking {
        withTimeout(85_000) {
            ImagePipeline(context).use { pipeline ->
                val data = bytes
                val expected = listOf(fixture.take(4).joinToString(" "), fixture.drop(4).joinToString(" "))
                val seen = mutableListOf<List<String>>()
                val provider = object : TranslationProvider {
                    override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
                        seen += texts
                        assertEquals("en", source)
                        assertEquals("ckb", target)
                        return texts.map { if (it.startsWith("MY")) "لە دوای پشوویەکی درێژ، ئامانجم تێکچوو و سەرنجم لەدەست دا." else "دەتوانیت سبەی دووبارە هەوڵ بدەیتەوە." }
                    }
                }
                val options = ReaderOptions("ckb", false, true, ENGLISH_COMIC_SCRIPT, false)
                val providers = ReaderProviders(provider, "english-comic-fixture-v1", null)
                val before = pipeline.preview(data)
                val after = pipeline.gallery(data, options, providers, incognito = true).bitmap
                assertNotNull("Expected a painted Sorani image; OCR requests: $seen", after)
                try {
                    assertEquals(listOf(expected), seen)
                    assertEquals(before.width, after!!.width)
                    assertEquals(before.height, after.height)
                    for (box in listOf(Box(110, 110, 460, 308), Box(540, 329, 858, 432))) {
                        var diff = 0
                        var ink = 0
                        for (y in box.top until box.bottom) for (x in box.left until box.right) {
                            val old = before.getPixel(x, y)
                            val new = after.getPixel(x, y)
                            if (old != new) { diff++; if (Color.luminance(new) < .25f) ink++ }
                        }
                        assertTrue("Translated ink must be visible in $box ($diff changed, $ink ink)", diff > 280 && ink > 100)
                    }
                    for (y in 530..690) for (x in 40..400) {
                        assertEquals("Price and surroundings at ($x,$y)", before.getPixel(x, y), after.getPixel(x, y))
                    }
                    for (y in 72..360 step 4) {
                        assertEquals("Bubble border at x=49", before.getPixel(49, y), after.getPixel(49, y))
                    }
                    val original = pipeline.preview(data)
                    try { assertTrue("Gallery preview is still the original", before.sameAs(original)) }
                    finally { original.recycle() }
                } finally { before.recycle(); after?.recycle() }
            }
        }
    }

    @Test fun rescansTinyLetteringAndRepairsSpacingOnTheSameDifficultPanel() = runBlocking {
        withTimeout(110_000) {
            val data = coverageBytes
            val image = BitmapFactory.decodeByteArray(data, 0, data.size)
            try {
                PaddleComicOcr(context).use { ocr ->
                    ocr.recognize(image) // warm model initialization and runtime caches
                    val fast = measured("full-panel Tiny") { ocr.recognize(image) }
                    val bubbles = measured("balloon proposals") { comicBubbles(image) }
                    assertTrue("Faint bubble proposal: $bubbles", bubbles.any { it.bounds.x in 470..500 && it.bounds.y in 680..715 })
                    assertTrue("Dark fill stays separate from the panel rule", bubbles.any { it.dark && it.bounds.y in 884..924 })
                    val recovered = measured("Tiny with crop recovery and Small review") { ocr.recognize(image, bubbles = bubbles, refine = true) }
                    val tinyBox = Box(396, 655, 575, 737)
                    fun readings(lines: List<OcrLine>, box: Box) = lines.filter { it.box.overlaps(box) }.map { it.text }
                    assertTrue("After crop recovery: ${readings(recovered, tinyBox)}; full panel: ${readings(fast, tinyBox)}",
                        "I WAS WRONG." in readings(recovered, tinyBox))
                    assertFalse("Full-panel scan only sees part of this lettering", "I WAS WRONG." in readings(fast, tinyBox))
                    assertTrue("Dark bubble: ${readings(recovered, Box(40, 835, 340, 968))}",
                        recovered.any { it.box.y in 884..924 && it.text == "I AM STILL HERE." })
                }
            } finally { image.recycle() }
        }
    }

    @Test fun recoveredAndUnchangedYouDialogueActuallyPaintWithoutMovingLiterals() = runBlocking {
        withTimeout(110_000) {
            val data = coverageBytes
            val seen = mutableListOf<String>()
            val provider = object : TranslationProvider {
                override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
                    seen += texts
                    return texts.map { item -> when {
                        item.trim() == "YOU?" -> item // Use the exact local fallback instead of spending another request.
                        "I WAS WRONG." in item -> "من هەڵەم کرد."
                        else -> "دەگەڕێمەوە."
                    } }
                }
            }
            val stages = linkedMapOf<String, Long>()
            val decisions = mutableListOf<Pair<Box, PaintDecision>>()
            ImagePipeline(context, { stage, milliseconds -> stages[stage] = milliseconds }, { box, decision -> decisions += box to decision }).use { pipeline ->
                val before = pipeline.preview(data)
                val result = pipeline.gallery(data, ReaderOptions("ckb", false, true, ENGLISH_COMIC_SCRIPT, false),
                    ReaderProviders(provider, "english-coverage-fixture", null), true)
                val after = result.bitmap
                Log.i("SipanComicSmoke", "coverage gallery, mocked MT: $stages; drawn=${decisions.count { it.second == PaintDecision.DRAWN }}/${decisions.size}")
                assertNotNull("Rendered difficult English panel: $seen; paint=$decisions", after)
                try {
                    assertTrue("Inputs: $seen", seen.any { "I WAS WRONG." in it })
                    assertEquals("Standalone YOU? uses the local fallback. Inputs: $seen", 1, seen.count { it == "YOU?" })
                    assertTrue("Literals are not sent as dialogue: $seen", seen.none { "$7.25" in it || "example.com" in it })
                    for (box in listOf(Box(398, 46, 592, 119), Box(405, 662, 565, 730))) {
                        var changed = 0
                        for (y in box.top until box.bottom) for (x in box.left until box.right) {
                            if (before.getPixel(x, y) != after!!.getPixel(x, y)) changed++
                        }
                        val nearby = decisions.filter { it.first.overlaps(box) }
                        assertTrue("Ink changed in $box ($changed pixels); paint=$nearby; inputs=$seen", changed > 25)
                    }
                    for (box in listOf(Box(560, 1810, 797, 1963), Box(890, 1870, 1221, 1961))) {
                        for (y in box.top until box.bottom step 2) for (x in box.left until box.right step 2) {
                            assertEquals("Literal pixel at ($x, $y)", before.getPixel(x, y), after!!.getPixel(x, y))
                        }
                    }
                } finally { before.recycle(); after?.recycle() }
            }
        }
    }

    @Test fun aDarkOutlineKeepsStackedBubblesSeparate() {
        val bitmap = Bitmap.createBitmap(180, 150, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawRect(10f, 66f, 170f, 70f, Paint().apply { color = Color.BLACK })
            val first = Box(29, 40, 150, 62)
            val next = Box(37, 75, 142, 98)
            val surface = ComicSurface(bitmap)
            val lines = listOf(OcrLine("YOU CAN HELP", first, emptyList(), 1), OcrLine("I WILL WAIT", next, emptyList(), 1))
            assertEquals(2, groupForImage(OcrPage(lines, false, comic = true), surface::sameInterior).units.size)
        } finally { bitmap.recycle() }
    }

    private inline fun <T> measured(name: String, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { Log.i("SipanComicSmoke", "$name: ${(System.nanoTime() - start) / 1_000_000} ms") }
    }

    private val coverageBytes get() = instrumentation.context.assets.open("en-comic-coverage.png").use { it.readBytes() }
    private val bytes get() = instrumentation.context.assets.open("en-comic.png").use { it.readBytes() }
}
