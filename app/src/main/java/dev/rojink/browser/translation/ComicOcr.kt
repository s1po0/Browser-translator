package dev.rojink.browser.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

internal class OnDeviceImageOcr(context: Context) : OcrProvider, AutoCloseable {
    private val mlKit = MlKitOcrProvider()
    private val paddle = PaddleComicOcr(context)

    override suspend fun recognize(image: Bitmap, hint: OcrHint): OcrPage {
        val base = try { mlKit.recognize(image, if (hint.explicit == ENGLISH_COMIC_SCRIPT) hint.copy(enhanced = false) else hint) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (hint.explicit != ENGLISH_COMIC_SCRIPT) throw e
            OcrPage(emptyList(), false, script = Script.LATIN)
        }
        val explicit = hint.explicit == ENGLISH_COMIC_SCRIPT
        if (!useComicOcr(base, hint)) return recoverInkLines(image, base, hint, explicit)
        return withContext(Dispatchers.Default) {
            val bubbles = comicBubbles(image)
            val found = try {
                paddle.recognize(image, base.lines, if (hint.enhanced) bubbles else emptyList(),
                    refine = hint.enhanced && (explicit || base.lines.any { reliableEnglish(it.text) || hasEnglishRank(it.text) }))
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
            catch (_: LinkageError) { emptyList() }
            ensureActive()
            // Balloons the page-scale readers left in English get one clean crop each, read by the same ML Kit
            // engine the desktop Kurd-man app uses. The mask keeps hands and clothes out of the crop.
            val cropped = try { bubbleCropLines(image, bubbles, base.lines + found, hint, mlKit) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
            catch (_: LinkageError) { emptyList() }
            val surface = ComicSurface(image, bubbles)
            base.copy(lines = reconcileComicOcr(base.lines, found + cropped, explicit, surface::letteringSurface),
                comic = true, bubbles = bubbles)
        }
    }

    /** ML Kit alone, no comic passes: the plain block reader the Kurd-man desktop app proved on English manhwa. */
    suspend fun plainLines(image: Bitmap, hint: OcrHint): List<OcrLine> =
        mlKit.recognize(image, hint.copy(enhanced = false)).lines

    /** Only lettering the first reader left behind is retried, so ordinary pages cost one flat pass. */
    private suspend fun recoverInkLines(image: Bitmap, base: OcrPage, hint: OcrHint, explicit: Boolean): OcrPage {
        if (!hint.enhanced && !explicit) return base
        return try {
            val found = paddle.inkLines(image, base.lines, strict = !explicit)
            if (found.isEmpty()) base else base.copy(
                lines = reconcileComicOcr(base.lines, found, explicit, ComicSurface(image)::letteringSurface),
            )
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { base }
        catch (_: LinkageError) { base }
    }

    override fun close() { paddle.close(); mlKit.close() }
}

/** Background evidence for grouping and extra typesetting room; no assertion that every white area is a bubble. */
internal class ComicSurface(private val image: Bitmap, private val bubbles: List<ComicBubble> = emptyList()) {
    private fun pixel(x: Int, y: Int): Int = image.getPixel(x.coerceIn(0, image.width - 1), y.coerceIn(0, image.height - 1))
    private fun bright(c: Int) = Color.alpha(c) >= 245 && minOf(Color.red(c), Color.green(c), Color.blue(c)) >= 172
    private fun similar(a: Int, b: Int) = Color.alpha(a) >= 245 && maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)),
        abs(Color.blue(a) - Color.blue(b))) <= 27

    fun letteringSurface(box: Box): Boolean =
        comicBubbleFor(box, bubbles) != null || borderReference(box) != null || flatPaper(box)

    /** Ink on plain paper: a burst outline can crowd the border samples, but the box's own gaps stay empty. */
    private fun flatPaper(box: Box): Boolean {
        val area = box.clip(image.width, image.height)
        if (area.width < 5 || area.height < 5 || area.width * area.height > 120_000) return false
        val luma = IntArray(area.width * area.height)
        val colors = IntArray(luma.size)
        image.getPixels(colors, 0, area.width, area.left, area.top, area.width, area.height)
        for (i in colors.indices) luma[i] = (Color.luminance(colors[i]) * 255f).toInt()
        luma.sort()
        return luma[(luma.size * .65f).toInt()] >= 172 && luma.first() < 96
    }

    private fun borderReference(box: Box): Int? {
        val dark = comicBubbleFor(box, bubbles)?.dark == true
        fun plausible(c: Int) = if (dark) Color.alpha(c) >= 245 && Color.luminance(c) <= .15f else bright(c)
        val points = buildList {
            for (i in 1..3) {
                val y = box.top + box.height * i / 4
                add(pixel(box.left - 3, y)); add(pixel(box.right + 2, y))
                val x = box.left + box.width * i / 4
                add(pixel(x, box.top - 2)); add(pixel(x, box.bottom + 1))
            }
        }
        return points.firstOrNull { c -> plausible(c) && points.count { similar(it, c) } >= 8 } ?: run {
            // A tall CTC box can include the next line's ascenders along its top/bottom border.
            val sides = points.filterIndexed { i, _ -> i % 4 < 2 }
            sides.firstOrNull { c -> plausible(c) && sides.all { similar(it, c) } }
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

    /** Remove blank DB padding before testing overlaps, not ink or detected literal labels. */
    fun inkBounds(box: Box): Box {
        val area = box.clip(image.width, image.height)
        if (area.width * area.height !in 35..100_000) return box
        val reference = borderReference(area) ?: return box
        val colors = IntArray(area.width * area.height)
        image.getPixels(colors, 0, area.width, area.left, area.top, area.width, area.height)
        var left = area.right; var top = area.bottom; var right = area.left; var bottom = area.top
        for (i in colors.indices) if (!similar(colors[i], reference)) {
            val x = area.left + i % area.width; val y = area.top + i / area.width
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
        }
        if (right - left < 4 || bottom - top < 4) return box
        return Box(maxOf(area.left, left - 1), maxOf(area.top, top - 1), minOf(area.right, right + 1), minOf(area.bottom, bottom + 1))
    }

    /** Lend only uniform blank space, including wide or tall space in a confirmed enclosure. */
    fun fitRoom(original: Box, erase: List<Box>, size: Int, avoid: List<Box>): Box? {
        val reference = borderReference(original) ?: erase.asSequence().mapNotNull(::borderReference).firstOrNull() ?: return null
        val bubble = comicBubbleFor(original, bubbles)
        val dx = (size * if (bubble != null) 2.6f else 1.35f).toInt().coerceIn(2, 116)
        val dy = (size * if (bubble != null) 1.9f else .78f).toInt().coerceIn(2, 84)
        val candidates = buildList {
            for (gain in listOf(1f, .68f, .40f)) {
                val x = (dx * gain).toInt().coerceAtLeast(1); val y = (dy * gain).toInt().coerceAtLeast(1)
                for ((w, h) in listOf(x to y, x to minOf(y, 3), minOf(x, 3) to y)) {
                    val room = original.expand(w, h).clip(image.width, image.height)
                    if (room != original && room.width * room.height <= 220_000 && avoid.none { it.overlaps(room) } &&
                        (bubble == null || bubble.bounds.intersection(room) == room.width * room.height)) add(room)
                }
            }
        }.distinct().sortedByDescending { it.width * it.height }
        if (candidates.isEmpty()) return null
        fun beingErased(x: Int, y: Int) = erase.any { x in it.left until it.right && y in it.top until it.bottom }
        // Do not cover unknown ink in a rectangular union (for example, a price tucked between two lines).
        for (y in original.top until original.bottom step 3) for (x in original.left until original.right step 3) {
            if (!similar(pixel(x, y), reference) && !beingErased(x, y)) return null
        }
        val extent = candidates.reduce(Box::union)
        if (extent.width * extent.height > 240_000) return null
        val colors = IntArray(extent.width * extent.height)
        image.getPixels(colors, 0, extent.width, extent.left, extent.top, extent.width, extent.height)
        fun clear(room: Box): Boolean {
            for (y in room.top until room.bottom) for (x in room.left until room.right) {
                if (x in original.left until original.right && y in original.top until original.bottom) continue
                // A clipped descender/period inside the same checked erasure margin is not surrounding art.
                if (!similar(colors[(y - extent.top) * extent.width + x - extent.left], reference) && !beingErased(x, y)) return false
            }
            return true
        }
        return candidates.firstOrNull(::clear)
    }
}

/** How many balloon crops one page may cost; each is one ML Kit call on a small bitmap. */
private const val MAX_BUBBLE_CROPS = 6

/**
 * A tight, masked, enlarged crop of one balloon body is the most reliable reading available on device:
 * the engine sees nothing but its own lettering. Bubbles that already carry a reading are skipped.
 */
private suspend fun bubbleCropLines(
    image: Bitmap, bubbles: List<ComicBubble>, known: List<OcrLine>, hint: OcrHint, reader: MlKitOcrProvider,
): List<OcrLine> {
    if (bubbles.isEmpty()) return emptyList()
    val found = mutableListOf<OcrLine>()
    var budget = MAX_BUBBLE_CROPS
    for (bubble in bubbles.sortedByDescending { it.bounds.width * it.bounds.height }) {
        if (budget <= 0) break
        val area = bubble.bounds
        if (area.width < 40 || area.height < 18 || area.width > 900 || area.height > 900) continue
        if (missedBubbleBands(bubble, known).isEmpty()) continue
        if (known.any { areaOverlap(it.box, area) >= .34 }) continue
        val contour = bubble.contour ?: continue
        val crop = contour.maskedCrop(image, area) ?: continue
        budget--
        val factor = if (area.width < 300) 2.6f else 2f
        val big = runCatching {
            Bitmap.createScaledBitmap(crop, (crop.width * factor).toInt().coerceAtLeast(1),
                (crop.height * factor).toInt().coerceAtLeast(1), true)
        }.getOrNull()
        if (big == null) { crop.recycle(); continue }
        if (big !== crop) crop.recycle()
        try {
            val page = runCatching { reader.recognize(big, hint.copy(explicit = ENGLISH_COMIC_SCRIPT, enhanced = false)) }.getOrNull()
                ?: continue
            for (line in page.lines) {
                if (!englishLettering(line.text) || line.box.width < 6 || line.box.height < 5) continue
                val full = Box(
                    area.left + (line.box.left / factor).roundToInt(), area.top + (line.box.top / factor).roundToInt(),
                    area.left + (line.box.right / factor).roundToInt(), area.top + (line.box.bottom / factor).roundToInt(),
                ).clip(image.width, image.height)
                if (full.width < 5 || full.height < 4) continue
                if (!contour.containsInk(full)) continue
                found += OcrLine(line.text, full, emptyList(), 2_000_000 + found.size,
                    confidence = line.confidence.coerceIn(.80f, 1f), bubbleRead = bubble, inkVerifiedLatin = true)
            }
        } finally { big.recycle() }
    }
    return found
}
