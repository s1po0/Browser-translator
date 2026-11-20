package dev.rojink.browser.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

private const val ASSETS = "ocr/ppocrv6-tiny"
private const val LATIN_ASSETS = "ocr/ppocrv6-small-latin"
private const val MAX_LINES = 320
private const val MAX_REFINEMENTS = 8
private const val MAX_INK_CHECKS = 4
private const val MAX_RESCANS = 5
private const val MAX_INK_LINES = 20
private const val REC_HEIGHT = 48

/** Local PP-OCRv6 tiny; no model downloads, OCR service, or disk copies of image/text. */
internal class PaddleComicOcr(private val context: Context) : AutoCloseable {
    private val gate = Mutex()
    private val life = Any()
    private var closed = false
    private var busy = false
    private var unavailable = false
    // Session ownership is serialized by gate; close defers destruction until the current call releases it.
    private var model: Model? = null
    private val comicDetector by lazy { ComicTextDetector(context) }

    private suspend fun <T> withModel(fallback: T, block: suspend CoroutineScope.(Model) -> T): T = withContext(Dispatchers.Default) {
        gate.withLock {
            synchronized(life) {
                if (closed || unavailable) return@withLock fallback
                busy = true
            }
            try {
                val active = model ?: try { Model(context).also { model = it } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { unavailable = true; return@withLock fallback }
                catch (_: LinkageError) { unavailable = true; return@withLock fallback }
                block(active)
            } finally {
                val retired = synchronized(life) {
                    busy = false
                    if (closed) model.also { model = null } else null
                }
                retired?.close()
            }
        }
    }

    /** Cheap second opinion for lettering on flat paper that the line models skipped entirely. */
    suspend fun inkLines(image: Bitmap, baseline: List<OcrLine>, strict: Boolean): List<OcrLine> = withModel(emptyList()) { active ->
        val boxes = comicTextLines(image).filter { box -> baseline.none { areaOverlap(it.box, box) >= .70 } }
        // A document already read line by line gains nothing here; a comic window whose dialogue was skipped gains everything.
        if (boxes.isEmpty() || strict && boxes.size < maxOf(2, baseline.size)) return@withModel emptyList()
        val result = mutableListOf<OcrLine>()
        var id = 2_000_000
        fun settle(outer: Box): OcrLine? {
            val first = active.read(image, outer)
            val text = if (first.confidence >= .62f && englishLettering(first.value)) first else
                active.refine(image, outer)?.takeIf { it.confidence >= .62f && englishLettering(it.value) } ?: return null
            if (text.value.length !in 1..280 || text.value.none(Char::isLetterOrDigit)) return null
            return OcrLine(text.value, outer, emptyList(), id++, confidence = text.confidence)
        }
        for (group in inkGroups(image, boxes.take(MAX_INK_LINES))) {
            ensureActive()
            if (result.size >= 8) break
            val kept = mutableListOf<OcrLine>()
            for (box in group) {
                val line = settle(box.expand(3, 3).clip(image.width, image.height)) ?: continue
                if (kept.none { areaOverlap(it.box, line.box) >= .60 }) kept += line
            }
            if (kept.size >= 2) {
                val block = kept.map { it.box }.reduce(Box::union)
                result += OcrLine(kept.sortedBy { it.box.top }.joinToString(" ") { it.text.trim() }, block, emptyList(), id++,
                    confidence = kept.minOf { it.confidence })
            } else result += kept
        }
        result
    }

    suspend fun recognize(
        image: Bitmap, baseline: List<OcrLine> = emptyList(), bubbles: List<ComicBubble> = emptyList(), refine: Boolean = false,
    ): List<OcrLine> = withModel(emptyList()) { active ->
        val result = mutableListOf<OcrLine>()
        var budget = if (refine) MAX_REFINEMENTS else 0
        var inkBudget = MAX_INK_CHECKS
        val surface by lazy { ComicSurface(image, bubbles) }
        var id = 1_000_000
        fun read(box: Box, region: ComicBubble?, recovered: Boolean, isolatedBand: Boolean = false, masked: Bitmap? = null, retry: Boolean = false): OcrLine? {
            if (result.size >= MAX_LINES || box.width < 6 || box.height < 5) return null
            ensureActive()
            val raster = masked ?: image
            val local = if (masked != null) checkNotNull(region).bounds.let { box.moved(-it.left, -it.top) } else box
            val first = active.read(raster, local)
            // Stylized lettering often lands under the tiny model's floor; the latin reader is the second opinion.
            val tiny = if (retry && first.confidence < .62f) {
                active.refine(raster, local)?.takeIf { it.confidence >= .62f && englishLettering(it.value) } ?: first
            } else first
            if (isolatedBand && (tiny.confidence < .93f || !reliableEnglish(tiny.value))) return null
            val base = baseline.firstOrNull { abs(it.box.y - box.y) < box.height * .45f && areaOverlap(it.box, box) > .69 }
            val needsReview = !isolatedBand && budget > 0 && needsLatinReview(tiny, base, region != null, recovered)
            var inkVerified = false
            val text = if (needsReview) {
                budget--
                val small = active.refine(raster, local)
                if (small != null) inkVerified = verifyLatinInk(tiny, small, base) {
                    if (inkBudget <= 0) null else {
                        inkBudget--
                        val tight = paddedInkCrop(box, surface.inkBounds(box))
                        val sample = tight?.moved(local.left - box.left, local.top - box.top) ?: local
                        active.refine(raster, sample, narrow = tight == null)
                    }
                }
                if (small != null && (inkVerified || preferLatinReview(tiny, small, base))) small else tiny
            } else tiny
            if (text.confidence >= if (recovered) .76f else .62f) {
                if (text.value.length in 1..280 && text.value.any(Char::isLetterOrDigit)) {
                    val grounded = region?.takeIf { recovered && it.contour?.fits(box) == true }
                    val reading = OcrLine(text.value, box, emptyList(), id++, confidence = text.confidence,
                        bubbleRead = grounded, inkVerifiedLatin = inkVerified)
                    val spilled = if (grounded != null) result.indexOfFirst { replacesSpilledLine(reading, it) } else -1
                    if (spilled >= 0) result[spilled] = reading
                    else if (result.none { it.bubbleRead != null && replacesSpilledLine(it, reading) }) mergeOcrLines(result, listOf(reading))
                    return reading
                }
            }
            return null
        }
        for (tile in ocrTiles(image.width, image.height)) {
            ensureActive()
            if (result.size >= MAX_LINES) break
            for (box in detectRegion(active, image, tile, false)) {
                if (result.size >= MAX_LINES) break
                read(box, comicBubbleFor(box, bubbles), false)
            }
        }
        // Handwritten lettering in burst or open bubbles: the ink proposes each line, the readers confirm them.
        val leftovers = comicTextLines(image).filter { box -> baseline.none { areaOverlap(it.box, box) >= .70 } }
        for (group in inkGroups(image, leftovers)) {
            ensureActive()
            if (result.size >= MAX_LINES) break
            val readings = mutableListOf<OcrLine>()
            for (box in group) {
                // A word cluster already inside a confirmed reading is the same ink, not a second sentence.
                if (result.any { areaOverlap(it.box, box) >= .60 }) continue
                val outer = box.expand(3, 3).clip(image.width, image.height)
                read(outer, comicBubbleFor(box, bubbles), false, retry = true)?.let { readings += it }
            }
            if (readings.size < 2) continue
            // One balloon, one sentence: a single block is translated once and wiped as one, instead of leaving fragments.
            val kept = mutableListOf<OcrLine>()
            for (line in readings.sortedByDescending { it.confidence * it.text.length }) {
                if (kept.none { areaOverlap(it.box, line.box) >= .60 }) kept += line
            }
            if (kept.size < 2) continue
            result.removeAll(kept.toSet())
            val block = kept.map { it.box }.reduce(Box::union)
            val joined = kept.sortedBy { it.box.top }.joinToString(" ") { it.text.trim() }
            mergeOcrLines(result, listOf(OcrLine(joined, block, emptyList(), id++, confidence = kept.minOf { it.confidence })))
        }
        val seen = baseline + result
        val recoveries = bubbles.mapNotNull { bubble ->
            missedBubbleBands(bubble, seen).takeIf { it.isNotEmpty() }?.let { bubble to it }
        }.sortedByDescending { it.second.size }.take(MAX_RESCANS)
        for ((bubble, missed) in recoveries) {
            ensureActive()
            // Thin ink bands sometimes already isolate one complete line, so try CTC before rerunning DB.
            missed.asSequence().filter { it.height <= 28 && it.width >= it.height * 2 }.take(3)
                .forEach { read(it, bubble, true, isolatedBand = true) }
            val remaining = missedBubbleBands(bubble, baseline + result)
            if (remaining.isEmpty()) continue
            val area = bubble.bounds
            val contour = bubble.contour
            val masked = contour?.maskedCrop(image, area)
            try {
                val detected = if (masked != null) active.detect(masked, true).map { it.moved(area.left, area.top) }
                    else detectRegion(active, image, area, true, invert = bubble.dark)
                val proposals = detected.filter { box ->
                    (masked == null || contour?.containsInk(box) == true) && remaining.any { band ->
                        abs(box.y - band.y) <= maxOf(box.height, band.height) * .68f && areaOverlap(box, band) > .30
                    }
                }.take(12)
                for (box in proposals) read(box, bubble, true, masked = masked)
            } finally { masked?.recycle() }
            // Some tiny lines never cross the DB threshold, even at crop scale. Try only the isolated ink bands.
            missedBubbleBands(bubble, baseline + result).take(6).forEach { box -> read(box, bubble, true) }
        }
        result
    }

    /** Tightly led lettering inside one flat interior reads as one block, so it is replaced as one block. */
    private fun inkGroups(image: Bitmap, boxes: List<Box>): List<List<Box>> {
        val groups = mutableListOf<MutableList<Box>>()
        for (box in boxes) {
            val group = groups.lastOrNull()
            val last = group?.lastOrNull()
            val joined = group != null && last != null && box.top - last.bottom in 0..(last.height * .40f).toInt() &&
                minOf(box.right, last.right) - maxOf(box.left, last.left) >= minOf(box.width, last.width) * .25f &&
                paperBetween(image, last, box)
            if (joined) group!! += box else groups += mutableListOf(box)
        }
        return groups
    }

    /** A bubble outline or panel rule between two lines is a reason not to join them. */
    private fun paperBetween(image: Bitmap, first: Box, second: Box): Boolean {
        val top = first.bottom.coerceAtMost(second.bottom)
        val bottom = second.top.coerceAtLeast(first.top)
        if (bottom - top < 1) return true
        val left = maxOf(first.left, second.left)
        val right = minOf(first.right, second.right)
        if (right - left < 4) return false
        val pixels = IntArray((right - left) * (bottom - top))
        image.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
        return pixels.count { minOf(Color.red(it), Color.green(it), Color.blue(it)) >= 172 } * 100 >= pixels.size * 78
    }

    private fun detectRegion(active: Model, image: Bitmap, area: Box, recover: Boolean, invert: Boolean = false): List<Box> {
        val raw = Bitmap.createBitmap(image, area.left, area.top, area.width, area.height)
        val crop = if (invert) try { raw.invertedCopy() } finally { raw.recycle() } else raw
        return try { active.detect(crop, recover).map { it.moved(area.left, area.top) } }
        finally { if (crop !== image) crop.recycle() }
    }

    override fun close() {
        val retired = synchronized(life) {
            closed = true
            if (!busy) model.also { model = null } else null
        }
        retired?.close()
        comicDetector.close()
    }

    private class Model(private val context: Context) : AutoCloseable {
        private fun alphabet(dir: String) = context.assets.open("$dir/alphabet.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() } + " "
        private val alphabet = alphabet(ASSETS)
        private val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        private val det = InferenceSession(env, context, "$ASSETS/det.onnx")
        private val rec = try { InferenceSession(env, context, "$ASSETS/rec.onnx") }
        catch (failure: Throwable) { det.close(); throw failure }
        private var storage: FloatBuffer? = null
        private var small: InferenceSession? = null
        private var smallAlphabet = emptyList<String>()
        private var smallDisabled = context.getSystemService(ActivityManager::class.java)?.let { it.isLowRamDevice || it.memoryClass < 96 } == true

        private fun inputBuffer(width: Int, height: Int): FloatBuffer {
            val size = width * height * 3
            val input = storage?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocateDirect(size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().also { storage = it }
            val buffer: Buffer = input
            buffer.clear()
            buffer.limit(size)
            return input
        }

        fun detect(image: Bitmap, recover: Boolean): List<Box> {
            val (w, h) = if (recover) paddleDimensions(image.width, image.height, 640, 480) else paddleDimensions(image.width, image.height)
            val input = inputBuffer(w, h)
            fillTensor(image, null, w, h, w, detection = true, values = input)
            return evaluate(det, input, w, h) { map, shape ->
                check(shape.size in 3..4 && shape[shape.lastIndex - 1] == h.toLong() && shape.last() == w.toLong())
                paddleLineBoxes(map, w, h, image.width, image.height, recover)
            }
        }

        fun read(image: Bitmap, box: Box): PaddleText = read(image, box, rec, alphabet)

        fun refine(image: Bitmap, box: Box, narrow: Boolean = false): PaddleText? {
            if (smallDisabled) return null
            return try {
                val session = small ?: run {
                    smallAlphabet = alphabet(LATIN_ASSETS)
                    InferenceSession(env, context, "$LATIN_ASSETS/rec.onnx").also { small = it }
                }
                read(image, box, session, smallAlphabet, narrow)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { smallDisabled = true; null }
            catch (_: LinkageError) { smallDisabled = true; null }
        }

        private fun read(image: Bitmap, box: Box, recognizer: InferenceSession, alphabet: List<String>, narrow: Boolean = false): PaddleText {
            val content = paddleContentWidth(box, REC_HEIGHT, narrow)
            val width = (((content + 31) / 32 + 1) * 32).coerceIn(96, 1024)
            val input = inputBuffer(width, REC_HEIGHT)
            fillTensor(image, box, content, REC_HEIGHT, width, detection = false, values = input)
            return evaluate(recognizer, input, width, REC_HEIGHT) { out, shape ->
                check(shape.size == 3 && shape[0] == 1L && shape[2] == alphabet.size + 1L)
                paddleRead(out, shape[1].toInt(), alphabet)
            }
        }

        private inline fun <T> evaluate(
            session: InferenceSession, data: FloatBuffer, width: Int, height: Int, consume: (FloatBuffer, LongArray) -> T,
        ): T = OnnxTensor.createTensor(env, data, longArrayOf(1, 3, height.toLong(), width.toLong())).use { input ->
            session.session.run(mapOf("x" to input)).use { result ->
                val output = result[0] as OnnxTensor
                consume(checkNotNull(output.floatBuffer), output.info.shape)
            }
        }

        override fun close() {
            try { det.close() } finally {
                try { rec.close() } finally { small?.close(); storage = null }
            }
        }
    }
}

private class InferenceSession(env: OrtEnvironment, context: Context, path: String) : AutoCloseable {
    private val settings = OrtSession.SessionOptions()
    val session: OrtSession = try {
        settings.setIntraOpNumThreads(2)
        settings.setInterOpNumThreads(1)
        settings.setCPUArenaAllocator(false) // Do not retain the largest panel's activation buffers between images.
        settings.setMemoryPatternOptimization(false)
        settings.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
        env.createSession(context.assets.open(path).use { it.readBytes() }, settings)
    } catch (failure: Throwable) { settings.close(); throw failure }

    override fun close() { try { session.close() } finally { settings.close() } }
}

/** Scale the source box, not the partly downsampled detection map. Horizontal CTC is padded with zeros. */
private fun fillTensor(image: Bitmap, box: Box?, contentWidth: Int, height: Int, width: Int, detection: Boolean, values: FloatBuffer) {
    val crop = if (box == null) image else Bitmap.createBitmap(image, box.left, box.top, box.width, box.height)
    val resized = Bitmap.createScaledBitmap(crop, contentWidth, height, true)
    val pixels = IntArray(contentWidth * height)
    try { resized.getPixels(pixels, 0, contentWidth, 0, 0, contentWidth, height) }
    finally {
        if (resized !== crop && resized !== image) resized.recycle()
        if (crop !== image) crop.recycle()
    }
    val plane = width * height
    fun color(pixel: Int, shift: Int, alpha: Int): Float {
        val channel = (pixel ushr shift) and 0xff
        return if (alpha == 255) channel / 255f else (channel * alpha + 255 * (255 - alpha)) / 65025f
    }
    for (y in 0 until height) for (x in 0 until width) {
        val start = y * width + x
        if (x >= contentWidth) {
            values.put(start, 0f); values.put(start + plane, 0f); values.put(start + plane * 2, 0f)
            continue
        }
        val pixel = pixels[y * contentWidth + x]
        val alpha = pixel ushr 24
        val b = color(pixel, 0, alpha)
        val g = color(pixel, 8, alpha)
        val r = color(pixel, 16, alpha)
        values.put(start, if (detection) (b - .485f) / .229f else b * 2f - 1f)
        values.put(start + plane, if (detection) (g - .456f) / .224f else g * 2f - 1f)
        values.put(start + plane * 2, if (detection) (r - .406f) / .225f else r * 2f - 1f)
    }
}

private fun Bitmap.invertedCopy(): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
    Canvas(out).drawBitmap(this, 0f, 0f, paint)
    return out
}
