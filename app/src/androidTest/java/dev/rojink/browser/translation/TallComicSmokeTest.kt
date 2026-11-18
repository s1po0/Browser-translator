package dev.rojink.browser.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class TallComicSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val options = ReaderOptions("ckb", false, true, ENGLISH_COMIC_SCRIPT, false)
    private val lines = listOf(
        "I THINK WE CAN STILL WIN.", "YOU?", "THE LIGHT WENT OUT. CAN YOU SEE ME?", "WAIT! I HEARD SOMETHING.",
        "I WAS WRONG.", "COME BACK, I AM STILL HERE.", "NOT YET.", "I… I MISSED YOU.",
        "OPEN THE DOOR. HURRY UP!", "I WAS SCARED. NOW I CAN FINALLY TAKE A BREATH.", "DID YOU SEE IT?",
    ).map(::letters).toSet()
    private val tops = intArrayOf(0, 3584, 7800)

    @Test fun darkBalloonStaysSeparateFromPanelRuleAtEachGridOffset() {
        val panel = instrumentation.context.assets.open("en-comic-coverage.png").use { BitmapFactory.decodeStream(it) }
        try {
            for (offset in 0..3) {
                val crop = Bitmap.createBitmap(panel, 0, offset, panel.width, panel.height - offset)
                try {
                    assertTrue("Dark proposal at offset $offset", comicBubbles(crop).any { bubble ->
                        val box = bubble.bounds.moved(0, offset)
                        bubble.dark && box.left in 36..56 && box.right in 324..344 && box.top in 827..850 && box.bottom in 950..977
                    })
                } finally { if (crop !== panel) crop.recycle() }
            }
        } finally { panel.recycle() }
    }

    @Test fun compareIdenticalLetteringAloneAndInTallStrips() = runBlocking {
        withTimeout(135_000) {
            val short = instrumentation.context.assets.open("en-comic-coverage.png").use { it.readBytes() }
            val panel = BitmapFactory.decodeByteArray(short, 0, short.size)
            val seen = mutableListOf<String>()
            val outcomes = mutableListOf<Pair<Box, PaintDecision>>()
            val timings = linkedMapOf<String, Long>()
            val provider = object : TranslationProvider {
                override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
                    assertEquals("ckb", target)
                    seen += texts
                    return texts.map { "من دەگەڕێمەوە." }
                }
            }
            try {
                ImagePipeline(instrumentation.targetContext, { stage, ms -> timings[stage] = ms }, { box, decision ->
                    outcomes.add(box to decision)
                }).use { pipeline ->
                    suspend fun inspect(input: ByteArray, height: Int, positions: IntArray) {
                        seen.clear(); outcomes.clear(); timings.clear()
                        val width = panel.width
                        val result = pipeline.gallery(input, options, ReaderProviders(provider, "tall-ocr-fixture", null), incognito = true)
                        val keys = seen.map(::letters)
                        val counts = keys.groupingBy { it }.eachCount()
                        val copies = positions.size
                        val exact = lines.sumOf { minOf(copies, counts[it] ?: 0) }
                        val tiny = counts[letters("I WAS WRONG.")] ?: 0
                        val drawn = outcomes.count { it.second == PaintDecision.DRAWN }
                        val skips = outcomes.map { it.second }.filter { it != PaintDecision.DRAWN }.groupingBy { it }.eachCount()
                        val bitmap = result.bitmap
                        Log.i("SipanComicSmoke", "strip ${width}x$height -> ${bitmap?.width}x${bitmap?.height}: " +
                            "whole bubbles before MT $exact/${lines.size * copies}, 9px line $tiny/$copies, painted $drawn/${outcomes.size}, " +
                            "skips=$skips; kept=${result.keptText}; $timings")
                        if (skips.isNotEmpty()) Log.i("SipanComicSmoke", "strip $height skipped boxes: ${outcomes.filter { it.second != PaintDecision.DRAWN }}")
                        try {
                            val plan = planLongImage(RasterSize(width, height))
                            assertEquals("Whole dialogue identities (no duplicates or seam fragments): $seen",
                                lines.associateWith { copies }, counts)
                            assertEquals("Panel order (and reading order across joins): $keys", List(copies) { lines }, keys.chunked(lines.size).map { it.toSet() })
                            for (top in positions) {
                                assertEquals("Exactly one paint per dialogue on the panel at $top", lines.size,
                                    outcomes.count { it.second == PaintDecision.DRAWN && it.first.y in top until top + panel.height })
                            }
                            assertEquals("Unpainted: ${outcomes.filter { it.second != PaintDecision.DRAWN }}", lines.size * copies, drawn)
                            assertTrue("No prices or links sent to MT", seen.none { "$7.25" in it || "example.com" in it })
                            assertNotNull(bitmap)
                            val after = requireNotNull(bitmap)
                            assertEquals((plan?.output ?: RasterSize(width, height)), RasterSize(after.width, after.height))
                            assertEquals("The decoded aspect ratio is stable", width.toDouble() / height, after.width.toDouble() / after.height, .001)
                            if (plan != null) assertTrue("Source-resolution strips were actually used", timings.containsKey("strip OCR"))
                            checkPlacement(panel, after, height, positions)
                        } finally { bitmap?.recycle() }
                    }
                    inspect(short, panel.height, intArrayOf(0))
                    // Exactly the same panels and placements; only extra blank rows change the second strip.
                    for (h in listOf(9920, 12288)) inspect(comicStrip(panel, h, tops), h, tops)
                }
            } finally { panel.recycle() }
        }
    }

    private fun checkPlacement(panel: Bitmap, output: Bitmap, sourceHeight: Int, positions: IntArray) {
        val baseline = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
        try {
            val sx = output.width.toFloat() / panel.width
            val sy = output.height.toFloat() / sourceHeight
            val canvas = Canvas(baseline).apply { scale(sx, sy) }
            for (y in positions) canvas.drawBitmap(panel, null, Rect(0, y, panel.width, y + panel.height), Paint(Paint.FILTER_BITMAP_FLAG))
            fun changes(box: Box, dy: Int): Int {
                var count = 0
                for (y in ((box.top + dy) * sy).roundToInt() until ((box.bottom + dy) * sy).roundToInt()) {
                    for (x in (box.left * sx).roundToInt() until (box.right * sx).roundToInt()) {
                        if (baseline.getPixel(x, y) != output.getPixel(x, y)) count++
                    }
                }
                return count
            }
            for (top in positions) {
                assertTrue("Tiny bubble repainted at source row ${top + 700}", changes(Box(405, 662, 565, 730), top) > 12)
                for (literal in listOf(Box(560, 1810, 797, 1963), Box(890, 1870, 1221, 1961), Box(8, 215, 300, 268))) {
                    assertEquals("Price, URL or artwork moved at $top $literal", 0, changes(literal, top))
                }
            }
        } finally { baseline.recycle() }
    }

    private fun letters(text: String) = text.uppercase().filter(Char::isLetterOrDigit)
}

internal fun comicStrip(panel: Bitmap, height: Int, tops: IntArray): ByteArray {
    val bitmap = Bitmap.createBitmap(panel.width, height, Bitmap.Config.ARGB_8888)
    return try {
        bitmap.eraseColor(0xFF728091.toInt())
        val canvas = Canvas(bitmap)
        for (top in tops) canvas.drawBitmap(panel, null, Rect(0, top, panel.width, top + panel.height), null)
        ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
    } finally { bitmap.recycle() }
}
