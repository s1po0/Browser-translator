package dev.rojink.browser.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal class OnDeviceImageOcr(context: Context) : OcrProvider, AutoCloseable {
    private val mlKit = MlKitOcrProvider()
    private val paddle = PaddleComicOcr(context)

    override suspend fun recognize(image: Bitmap, hint: OcrHint): OcrPage {
        val base = try { mlKit.recognize(image, hint) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (hint.explicit != ENGLISH_COMIC_SCRIPT) throw e
            OcrPage(emptyList(), false, script = Script.LATIN)
        }
        if (!useComicOcr(base, hint)) return base
        return withContext(Dispatchers.Default) {
            val found = try { paddle.recognize(image) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
            catch (_: LinkageError) { emptyList() }
            ensureActive()
            val surface = ComicSurface(image)
            base.copy(lines = reconcileComicOcr(base.lines, found, hint.explicit == ENGLISH_COMIC_SCRIPT, surface::lightBorder), comic = true)
        }
    }

    override fun close() { paddle.close(); mlKit.close() }
}

/** Background evidence for grouping and extra typesetting room; no assertion that every white area is a bubble. */
internal class ComicSurface(private val image: Bitmap) {
    private fun pixel(x: Int, y: Int): Int = image.getPixel(x.coerceIn(0, image.width - 1), y.coerceIn(0, image.height - 1))
    private fun bright(c: Int) = Color.alpha(c) >= 245 && minOf(Color.red(c), Color.green(c), Color.blue(c)) >= 172
    private fun similar(a: Int, b: Int) = Color.alpha(a) >= 245 && maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)),
        abs(Color.blue(a) - Color.blue(b))) <= 27

    fun lightBorder(box: Box): Boolean = borderReference(box) != null

    private fun borderReference(box: Box): Int? {
        val points = buildList {
            for (i in 1..3) {
                val y = box.top + box.height * i / 4
                add(pixel(box.left - 3, y)); add(pixel(box.right + 2, y))
                val x = box.left + box.width * i / 4
                add(pixel(x, box.top - 2)); add(pixel(x, box.bottom + 1))
            }
        }
        return points.firstOrNull { c -> bright(c) && points.count { similar(it, c) } >= 8 } ?: run {
            // A tall CTC box can include the next line's ascenders along its top/bottom border.
            val sides = points.filterIndexed { i, _ -> i % 4 < 2 }
            sides.firstOrNull { c -> bright(c) && sides.all { similar(it, c) } }
        }
    }

    // Null means uncertain. A visible dark separator can veto even a same-block match from the OCR engine.
    fun sameInterior(a: Box, b: Box): Boolean? {
        val first = borderReference(a)
        val second = borderReference(b)
        val reference = first ?: second ?: return null
        if (first != null && second != null && !similar(second, first)) return false
        val left = maxOf(a.left, b.left)
        val right = minOf(a.right, b.right)
        if (left >= right) return false
        val middle = (a.bottom + b.top) / 2
        val rows = if (b.top - a.bottom >= 2) a.bottom until b.top else middle - 1..middle + 1
        var cleanRows = 0
        for (y in rows) {
            val matches = (1..7).count { i -> similar(pixel(left + (right - left) * i / 8, y), reference) }
            if (matches <= 3) return false
            if (matches >= 6) cleanRows++
        }
        return if (cleanRows * 3 >= rows.count() * 2) true else null
    }

    /** Only lend blank space in a light bubble. Each newly occupied pixel is checked before any paint. */
    fun fitRoom(original: Box, glyphs: List<Box>, size: Int, avoid: List<Box>): Box? {
        val reference = borderReference(original) ?: return null
        for (gain in listOf(1.0f, .67f, .38f)) {
            val x = (size * gain * 1.35).toInt().coerceIn(2, 70)
            val y = (size * gain * .78).toInt().coerceIn(2, 40)
            val room = original.expand(x, y).clip(image.width, image.height)
            if (room == original || avoid.any { it.overlaps(room) }) continue
            // A nearly uniform field is required: do not cross bubble outlines or overwrite neighboring art.
            var safe = true
            loop@ for (py in room.top until room.bottom) {
                for (px in room.left until room.right) {
                    if (px in original.left until original.right && py in original.top until original.bottom) continue
                    if (!similar(pixel(px, py), reference)) { safe = false; break@loop }
                }
            }
            if (!safe) continue
            // Expanded lines can be off the true glyph box by a few pixels. Do not cover unidentified ink inside the union either.
            loop@ for (py in original.top until original.bottom step 3) {
                for (px in original.left until original.right step 3) {
                    if (glyphs.any { px in it.left until it.right && py in it.top until it.bottom }) continue
                    if (!similar(pixel(px, py), reference)) { safe = false; break@loop }
                }
            }
            if (safe) return room
        }
        return null
    }
}
