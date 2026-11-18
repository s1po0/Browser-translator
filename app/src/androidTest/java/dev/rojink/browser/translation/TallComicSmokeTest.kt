package dev.rojink.browser.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

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
                    suspend fun inspect(input: ByteArray, width: Int, height: Int, copies: Int): Pair<Int, Int> {
                        seen.clear(); outcomes.clear(); timings.clear()
                        val original = pipeline.preview(input)
                        val result = pipeline.gallery(input, options, ReaderProviders(provider, "tall-ocr-fixture", null), incognito = true)
                        val keys = seen.map(::letters)
                        val exact = keys.count { it in lines }
                        val tiny = keys.count { it == letters("I WAS WRONG.") }
                        val drawn = outcomes.count { it.second == PaintDecision.DRAWN }
                        val skips = outcomes.map { it.second }.filter { it != PaintDecision.DRAWN }.groupingBy { it }.eachCount()
                        Log.i("SipanComicSmoke", "strip ${width}x$height -> ${original.width}x${original.height}: " +
                            "whole bubbles before MT $exact/${lines.size * copies}, 9px line $tiny/$copies, painted $drawn/${outcomes.size}, " +
                            "skips=$skips; ${timings}")
                        try {
                            assertEquals("The decoded aspect ratio is stable", width.toDouble() / height, original.width.toDouble() / original.height, .001)
                            result.bitmap?.let { assertEquals(original.width, it.width); assertEquals(original.height, it.height) }
                            assertTrue("No additional paint attempts without a recognized unit", outcomes.size <= seen.size)
                            return exact to drawn
                        } finally { original.recycle(); result.bitmap?.recycle() }
                    }
                    val (reading, painted) = inspect(short, panel.width, panel.height, 1)
                    assertEquals("Standalone reference fixture", lines.size, reading)
                    assertEquals(lines.size, painted)
                    // Exactly the same panels and placements; only extra blank rows change the second strip.
                    for (h in listOf(9920, 12288)) inspect(strip(panel, h), panel.width, h, tops.size)
                }
            } finally { panel.recycle() }
        }
    }

    private fun strip(panel: Bitmap, height: Int): ByteArray {
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

    private fun letters(text: String) = text.uppercase().filter(Char::isLetterOrDigit)
}
