package dev.rojink.browser.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** Optional ONNX comic-text-detector for stylized/irregular speech bubbles missed by DBNet. */
internal class ComicTextDetector(context: Context) : AutoCloseable {
    private val env: OrtEnvironment? = runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
    private val session: OrtSession? = runCatching {
        val bytes = context.assets.open("comictextdetector.onnx").use { it.readBytes() }
        env?.createSession(bytes)
    }.getOrNull()

    val available: Boolean get() = session != null

    fun detect(bitmap: Bitmap): List<Box> {
        val active = session ?: return emptyList()
        val ort = env ?: return emptyList()
        val size = 1024
        val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val padded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply {
            drawColor(Color.BLACK)
            drawBitmap(resized, 0f, 0f, null)
        }
        resized.recycle()
        val pixels = IntArray(size * size)
        padded.getPixels(pixels, 0, size, 0, 0, size, size)
        padded.recycle()

        val plane = size * size
        val buf = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            buf.put(i, ((c ushr 16) and 0xFF) / 255f)
            buf.put(plane + i, ((c ushr 8) and 0xFF) / 255f)
            buf.put(2 * plane + i, (c and 0xFF) / 255f)
        }
        buf.rewind()

        val inputName = active.inputNames.firstOrNull() ?: return emptyList()
        val tensor = OnnxTensor.createTensor(ort, buf, longArrayOf(1, 3, size.toLong(), size.toLong()))
        return tensor.use {
            active.run(mapOf(inputName to it)).use { outputs ->
                val raw = outputs[0].value as? Array<*> ?: return@use emptyList()
                val rows = raw.firstOrNull() as? Array<*> ?: return@use emptyList()
                val boxes = mutableListOf<Box>()
                for (rowObj in rows) {
                    val row = rowObj as? FloatArray ?: continue
                    if (row.size < 5) continue
                    val conf = row[4]
                    if (conf < 0.35f) continue
                    val cx = row[0] / scale
                    val cy = row[1] / scale
                    val w = row[2] / scale
                    val h = row[3] / scale
                    val left = max(0, (cx - w / 2f).toInt())
                    val top = max(0, (cy - h / 2f).toInt())
                    val right = min(bitmap.width, (cx + w / 2f).toInt())
                    val bottom = min(bitmap.height, (cy + h / 2f).toInt())
                    if (right - left >= 8 && bottom - top >= 8) {
                        boxes.add(Box(left, top, right - left, bottom - top))
                    }
                }
                boxes
            }
        }
    }

    override fun close() {
        runCatching { session?.close() }
    }
}
