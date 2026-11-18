package dev.rojink.browser.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
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
import kotlin.math.ceil

private const val ASSETS = "ocr/ppocrv6-tiny"
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

    suspend fun recognize(image: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
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
                for ((tileIndex, tile) in ocrTiles(image.width, image.height).withIndex()) {
                    ensureActive()
                    if (result.size >= 320) break
                    val crop = Bitmap.createBitmap(image, tile.left, tile.top, tile.width, tile.height)
                    try {
                        val boxes = active.detect(crop)
                        for ((index, box) in boxes.withIndex()) {
                            ensureActive()
                            val text = active.read(crop, box)
                            if (text.confidence >= .62f && text.value.length in 1..280 && text.value.any(Char::isLetterOrDigit)) {
                                val bounds = box.moved(tile.left, tile.top)
                                mergeOcrLines(result, listOf(OcrLine(text.value, bounds, emptyList(),
                                    1_000_000 + tileIndex * 1000 + index, confidence = text.confidence)))
                            }
                        }
                    } finally { if (crop !== image) crop.recycle() }
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

    override fun close() {
        val retired = synchronized(life) {
            closed = true
            if (!busy) model.also { model = null } else null
        }
        retired?.close()
    }

    private class Model(context: Context) : AutoCloseable {
        private val alphabet = context.assets.open("$ASSETS/alphabet.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() } + " "
        private val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        private val det = InferenceSession(env, context, "$ASSETS/det.onnx")
        private val rec = try { InferenceSession(env, context, "$ASSETS/rec.onnx") }
        catch (failure: Throwable) { det.close(); throw failure }
        private var storage: FloatBuffer? = null

        private fun inputBuffer(width: Int, height: Int): FloatBuffer {
            val size = width * height * 3
            val input = storage?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocateDirect(size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().also { storage = it }
            val buffer: Buffer = input
            buffer.clear()
            buffer.limit(size)
            return input
        }

        fun detect(image: Bitmap): List<Box> {
            val (w, h) = paddleDimensions(image.width, image.height)
            val input = inputBuffer(w, h)
            fillTensor(image, null, w, h, w, detection = true, values = input)
            return evaluate(det, input, w, h) { map, shape ->
                check(shape.size in 3..4 && shape[shape.lastIndex - 1] == h.toLong() && shape.last() == w.toLong())
                paddleLineBoxes(map, w, h, image.width, image.height)
            }
        }

        fun read(image: Bitmap, box: Box): PaddleText {
            val content = ceil(REC_HEIGHT * box.width.toDouble() / box.height).toInt().coerceIn(1, 1024)
            val width = maxOf(320, content)
            val input = inputBuffer(width, REC_HEIGHT)
            fillTensor(image, box, content, REC_HEIGHT, width, detection = false, values = input)
            return evaluate(rec, input, width, REC_HEIGHT) { out, shape ->
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

        override fun close() { try { det.close() } finally { rec.close(); storage = null } }
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
