package dev.rojink.browser.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
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

    suspend fun recognize(
        image: Bitmap, baseline: List<OcrLine> = emptyList(), bubbles: List<ComicBubble> = emptyList(), refine: Boolean = false,
    ): List<OcrLine> = withContext(Dispatchers.Default) {
        gate.withLock {
            synchronized(life) {
                if (closed || unavailable) return@withLock emptyList()
                busy = true
            }
            try {
                val active = model ?: try { Model(context).also { model = it } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { unavailable = true; return@withLock emptyList() }
                catch (_: LinkageError) { unavailable = true; return@withLock emptyList() }
                val result = mutableListOf<OcrLine>()
                var budget = if (refine) MAX_REFINEMENTS else 0
                var inkBudget = MAX_INK_CHECKS
                val surface by lazy { ComicSurface(image, bubbles) }
                var id = 1_000_000
                fun read(box: Box, region: ComicBubble?, recovered: Boolean, isolatedBand: Boolean = false, masked: Bitmap? = null) {
                    if (result.size >= MAX_LINES || box.width < 6 || box.height < 5) return
                    ensureActive()
                    val raster = masked ?: image
                    val local = if (masked != null) checkNotNull(region).bounds.let { box.moved(-it.left, -it.top) } else box
                    val tiny = active.read(raster, local)
                    if (isolatedBand && (tiny.confidence < .93f || !reliableEnglish(tiny.value))) return
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
                        }
                    }
                }
                for (tile in ocrTiles(image.width, image.height)) {
                    ensureActive()
                    if (result.size >= MAX_LINES) break
                    for (box in detectRegion(active, image, tile, false)) {
                        if (result.size >= MAX_LINES) break
                        read(box, comicBubbleFor(box, bubbles), false)
                    }
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
                            else detectRegion(active, image, area, true)
                        val proposals = detected.filter { box ->
                            (masked == null || contour?.containsInk(box) == true) && remaining.any { band ->
                                abs(box.y - band.y) <= maxOf(box.height, band.height) * .68f && areaOverlap(box, band) > .30
                            }
                        }.take(12)
                        for (box in proposals) read(box, bubble, true, masked = masked)
                    } finally { masked?.recycle() }
                    // Some tiny lines never cross the DB threshold, even at crop scale. Try only the isolated ink bands.
                    missedBubbleBands(bubble, baseline + result).take(3).forEach { box -> read(box, bubble, true) }
                }
                result
            } finally {
                val retired = synchronized(life) {
                    busy = false
                    if (closed) model.also { model = null } else null
                }
                retired?.close()
            }
        }
    }

    private fun detectRegion(active: Model, image: Bitmap, area: Box, recover: Boolean): List<Box> {
        val crop = Bitmap.createBitmap(image, area.left, area.top, area.width, area.height)
        return try { active.detect(crop, recover).map { it.moved(area.left, area.top) } }
        finally { if (crop !== image) crop.recycle() }
    }

    override fun close() {
        val retired = synchronized(life) {
            closed = true
            if (!busy) model.also { model = null } else null
        }
        retired?.close()
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
