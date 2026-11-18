package dev.rojink.browser.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal data class StripWindowStyle(val top: Int, val bottom: Int, val comic: Boolean, val bubbles: List<ComicBubble>)
internal data class StripScan(
    val readings: List<StripReading>, val protected: List<Box>, val styles: List<StripWindowStyle>,
    val suspectedMiss: Boolean, val truncated: Boolean,
)

/** Decode only a bounded region at a time. Never construct the original full-size strip bitmap. */
@Suppress("DEPRECATION")
internal class LongImageOcr private constructor(private val decoder: BitmapRegionDecoder, val plan: LongImagePlan) : AutoCloseable {
    private fun decode(rect: Rect, sample: Int): Bitmap? = try {
        decoder.decodeRegion(rect, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } catch (_: IllegalArgumentException) { null }

    private fun read(top: Int, bottom: Int): Bitmap? {
        val end = minOf(plan.source.height.toLong(), bottom.toLong() * plan.sample).toInt()
        val crop = decode(Rect(0, top * plan.sample, plan.source.width, end), plan.sample) ?: return null
        if (crop.width != plan.scan.width || crop.height != bottom - top) {
            crop.recycle()
            return null
        }
        return crop
    }

    /** A small guide for choosing cuts, never an input to OCR or the finished artwork. */
    private suspend fun quietRows(): List<IntRange> {
        val step = 8
        val thumb = decode(Rect(0, 0, plan.source.width, plan.source.height), plan.sample * step) ?: return emptyList()
        try {
            val row = IntArray(thumb.width)
            var start = 0
            var tone: Int? = null
            val runs = mutableListOf<IntRange>()
            fun close(at: Int) {
                val end = minOf(at * step, plan.scan.height)
                // The plan places the actual join inside the run, not on the estimated boundary.
                if (tone != null && end - start * step >= LONG_GUTTER_MIN) runs += start * step until end
            }
            for (y in 0 until thumb.height) {
                if (y % 128 == 0) coroutineContext.ensureActive()
                thumb.getPixels(row, 0, row.size, 0, y, row.size, 1)
                val color = row[0].takeIf { pixel -> row.all { it == pixel } }
                if (tone != color) { close(y); start = y; tone = color }
            }
            close(thumb.height)
            return runs
        } finally { thumb.recycle() }
    }

    suspend fun scan(output: Bitmap, ocr: OcrProvider, hint: OcrHint): StripScan? {
        val candidates = mutableListOf<StripReading>()
        val protected = mutableListOf<Box>()
        val styles = mutableListOf<StripWindowStyle>()
        val bands = mutableListOf<Box>()
        val canvas = Canvas(output)
        val sx = output.width.toFloat() / plan.scan.width
        val sy = output.height.toFloat() / plan.scan.height
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        val gutters = quietRows()
        val windows = plan.windowsAtGutters(gutters)
        for ((index, window) in windows.withIndex()) {
            coroutineContext.ensureActive()
            var crop = read(window.top, window.bottom) ?: return null
            try {
                // The source is drawn exactly once per output row, using overlapping pixels for filtering at seams.
                canvas.save()
                canvas.clipRect(0, (window.coreTop * sy).roundToInt(), output.width, (window.coreBottom * sy).roundToInt())
                canvas.scale(sx, sy)
                canvas.drawBitmap(crop, null, Rect(0, window.top, plan.scan.width, window.bottom), filter)
                canvas.restore()
                val leading = flatRows(crop, fromTop = true)
                if (leading == crop.height) {
                    styles += StripWindowStyle(window.top, window.bottom, false, emptyList())
                    continue
                }
                var top = window.top
                var bottom = window.bottom
                // Remove verified empty gutters from the OCR input only, not from the artwork. Keep tiny text-only crops padded.
                val trimTop = if (gutters.any { window.top in it }) leading else 0
                val trimBottom = if (gutters.any { window.bottom - 1 in it }) flatRows(crop, fromTop = false) else 0
                if (trimTop + trimBottom > 0 && crop.height - trimTop - trimBottom >= 320) {
                    top += trimTop
                    bottom -= trimBottom
                    crop.recycle()
                    crop = read(top, bottom) ?: return null
                }
                val page = ocr.recognize(crop, hint)
                val comic = page.comic || hint.explicit == ENGLISH_COMIC_SCRIPT
                val surface = if (comic) ComicSurface(crop, page.bubbles) else null
                val layout = groupForImage(page.copy(comic = comic), surface?.let { it::sameInterior })
                for (unit in layout.units) {
                    val global = unit.movedY(top)
                    candidates += StripReading(global, index, window.clearance(global.box, plan.scan.height), window.owns(global.box.y), page.rightFirst)
                }
                protected += layout.protected.map { it.moved(0, top) }.filter { window.clearance(it, plan.scan.height) >= 8 }
                styles += StripWindowStyle(top, bottom, comic, page.bubbles)
                // Proposals aren't proof of missing text. Use unrepresented bands to avoid saving a suspect render as complete.
                if (hint.enhanced) for (bubble in page.bubbles) {
                    val box = bubble.bounds.moved(0, top)
                    if (window.owns(box.y) && window.clearance(box, plan.scan.height) >= 10) {
                        bands += bubble.bands.map { it.moved(0, top) }
                    }
                }
            } finally { if (!crop.isRecycled) crop.recycle() }
        }
        val resolved = resolveStripReadings(candidates)
        val represented = resolved.flatMap { it.unit.lines } + protected
        val suspected = bands.any { band -> represented.none { box ->
            minOf(box.right, band.right) - maxOf(box.left, band.left) >= band.width * .52f &&
                box.top < band.bottom && band.top < box.bottom
        } }
        return StripScan(resolved.take(LONG_MAX_UNITS), protected, styles, suspected, resolved.size > LONG_MAX_UNITS)
    }

    suspend fun paint(
        output: Bitmap, scan: StripScan, translations: List<String>, target: String, renderer: ImageRenderer,
        observe: ((Box, PaintDecision) -> Unit)?,
    ): Int {
        val canvas = Canvas(output)
        val sx = output.width.toFloat() / plan.scan.width
        val sy = output.height.toFloat() / plan.scan.height
        val occupied = mutableListOf<Box>()
        val byWindow = scan.readings.indices.groupBy { scan.readings[it].window }.toSortedMap()
        var drawn = 0
        for ((index, units) in byWindow) {
            coroutineContext.ensureActive()
            val style = scan.styles[index]
            val top = style.top
            val crop = read(top, style.bottom) ?: continue
            try {
                val area = Box(0, top, plan.scan.width, style.bottom)
                val literals = scan.protected.filter { it.overlaps(area) }.map { it.moved(0, -top) }
                val neighbors = scan.readings.asSequence().filter { it.window != index && it.unit.box.overlaps(area) }.map { it.unit.box }
                val avoid = (neighbors + occupied.asSequence().filter { it.overlaps(area) }).map { it.moved(0, -top) }.toList()
                canvas.save()
                try {
                    // Layout/fill/erase coordinates remain in the original reading scale. Only the final drawing is reduced.
                    canvas.scale(sx, sy)
                    canvas.translate(0f, top.toFloat())
                    drawn += renderer.paint(crop, units.map { scan.readings[it].unit.movedY(-top) }, units.map(translations::get),
                        literals, target, style.comic, style.bubbles,
                        observe?.let { report -> { box, decision -> report(box.moved(0, top), decision) } },
                        PaintDestination(canvas, avoid, minOf(sx, sy)) { occupied += it.moved(0, top) })
                } finally { canvas.restore() }
            } finally { crop.recycle() }
        }
        return drawn
    }

    private suspend fun flatRows(image: Bitmap, fromTop: Boolean): Int {
        val first = image.getPixel(0, if (fromTop) 0 else image.height - 1)
        val row = IntArray(image.width)
        for (offset in 0 until image.height) {
            if (offset % 128 == 0) coroutineContext.ensureActive()
            val y = if (fromTop) offset else image.height - 1 - offset
            image.getPixels(row, 0, row.size, 0, y, row.size, 1)
            if (row.any { it != first }) return offset
        }
        return image.height
    }

    override fun close() { decoder.recycle() }

    companion object {
        fun open(bytes: ByteArray, plan: LongImagePlan): LongImageOcr? {
            val decoder = try { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) }
            catch (_: IOException) { null }
            catch (_: IllegalArgumentException) { null } ?: return null
            if (decoder.width != plan.source.width || decoder.height != plan.source.height) {
                decoder.recycle()
                return null
            }
            return LongImageOcr(decoder, plan)
        }
    }
}
