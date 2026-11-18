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

internal data class StripWindowStyle(val comic: Boolean, val bubbles: List<ComicBubble>)
internal data class StripScan(
    val readings: List<StripReading>, val protected: List<Box>, val styles: List<StripWindowStyle>,
    val suspectedMiss: Boolean, val truncated: Boolean,
)

/** Decode only a bounded region at a time. Never construct the original full-size strip bitmap. */
@Suppress("DEPRECATION")
internal class LongImageOcr private constructor(private val decoder: BitmapRegionDecoder, val plan: LongImagePlan) : AutoCloseable {
    private fun read(window: StripWindow): Bitmap? {
        val rect = Rect(0, window.top * plan.sample, plan.source.width, minOf(plan.source.height.toLong(), window.bottom.toLong() * plan.sample).toInt())
        val crop = try {
            decoder.decodeRegion(rect, BitmapFactory.Options().apply {
                inSampleSize = plan.sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } catch (_: IllegalArgumentException) { null } ?: return null
        if (crop.width != plan.scan.width || crop.height != window.height) {
            crop.recycle()
            return null
        }
        return crop
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
        for ((index, window) in plan.windows.withIndex()) {
            coroutineContext.ensureActive()
            val crop = read(window) ?: return null
            try {
                // The source is drawn exactly once per output row, using overlapping pixels for filtering at seams.
                canvas.save()
                canvas.clipRect(0, (window.coreTop * sy).roundToInt(), output.width, (window.coreBottom * sy).roundToInt())
                canvas.scale(sx, sy)
                canvas.drawBitmap(crop, null, Rect(0, window.top, plan.scan.width, window.bottom), filter)
                canvas.restore()
                val page = if (uniform(crop)) OcrPage(emptyList(), false) else ocr.recognize(crop, hint)
                val comic = page.comic || hint.explicit == ENGLISH_COMIC_SCRIPT
                val surface = if (comic) ComicSurface(crop, page.bubbles) else null
                val layout = groupForImage(page.copy(comic = comic), surface?.let { it::sameInterior })
                for (unit in layout.units) {
                    val global = unit.movedY(window.top)
                    candidates += StripReading(global, index, window.clearance(global.box, plan.scan.height), window.owns(global.box.y), page.rightFirst)
                }
                protected += layout.protected.map { it.moved(0, window.top) }
                styles += StripWindowStyle(comic, page.bubbles)
                // Proposals aren't proof of missing text. Use unrepresented bands to avoid saving a suspect render as complete.
                if (hint.enhanced) for (bubble in page.bubbles) {
                    val box = bubble.bounds.moved(0, window.top)
                    if (window.owns(box.y) && window.clearance(box, plan.scan.height) >= 10) {
                        bands += bubble.bands.map { it.moved(0, window.top) }
                    }
                }
            } finally { crop.recycle() }
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
            val window = plan.windows[index]
            val crop = read(window) ?: continue
            try {
                val area = Box(0, window.top, plan.scan.width, window.bottom)
                val literals = scan.protected.filter { it.overlaps(area) }.map { it.moved(0, -window.top) }
                val neighbors = scan.readings.asSequence().filter { it.window != index && it.unit.box.overlaps(area) }.map { it.unit.box }
                val avoid = (neighbors + occupied.asSequence().filter { it.overlaps(area) }).map { it.moved(0, -window.top) }.toList()
                val style = scan.styles[index]
                canvas.save()
                try {
                    // Layout/fill/erase coordinates remain in the original reading scale. Only the final drawing is reduced.
                    canvas.scale(sx, sy)
                    canvas.translate(0f, window.top.toFloat())
                    drawn += renderer.paint(crop, units.map { scan.readings[it].unit.movedY(-window.top) }, units.map(translations::get),
                        literals, target, style.comic, style.bubbles,
                        observe?.let { report -> { box, decision -> report(box.moved(0, window.top), decision) } },
                        PaintDestination(canvas, avoid, minOf(sx, sy)) { occupied += it.moved(0, window.top) })
                } finally { canvas.restore() }
            } finally { crop.recycle() }
        }
        return drawn
    }

    private suspend fun uniform(image: Bitmap): Boolean {
        val first = image.getPixel(0, 0)
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            if (y % 128 == 0) coroutineContext.ensureActive()
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            if (row.any { it != first }) return false
        }
        return true
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
