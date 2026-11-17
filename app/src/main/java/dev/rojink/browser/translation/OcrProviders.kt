package dev.rojink.browser.translation

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal data class OcrHint(val explicit: String, val documentLanguage: String)
internal interface OcrProvider { suspend fun recognize(image: Bitmap, hint: OcrHint): OcrPage }
private enum class Script { LATIN, JAPANESE, KOREAN, CHINESE, DEVANAGARI }

/** All five models are packaged in the APK; no downloaded recognition model is required. */
internal class MlKitOcrProvider : OcrProvider, AutoCloseable {
    private val clients = mutableMapOf<Script, TextRecognizer>()
    private val lock = Any()
    @Volatile private var closed = false
    private fun client(script: Script): TextRecognizer = synchronized(lock) {
        check(!closed)
        clients.getOrPut(script) {
            when (script) {
                Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    override suspend fun recognize(image: Bitmap, hint: OcrHint): OcrPage = withContext(Dispatchers.Default) {
        val explicit = scriptOf(hint.explicit)
        val suggested = scriptOf(hint.documentLanguage)
        var selected = explicit ?: suggested
        val output = mutableListOf<OcrLine>()
        val slices = tiles(image.width, image.height)
        for ((index, box) in slices.withIndex()) {
            ensureActive()
            val tile = Bitmap.createBitmap(image, box.left, box.top, box.width, box.height)
            try {
                var model = selected ?: Script.LATIN
                var lines = recognizeTile(tile, model, index * 2000)
                if (explicit == null && (selected == null || index == 0) && quality(lines, model) < 13) {
                    var best = quality(lines, model)
                    val choices = listOf(Script.JAPANESE, Script.KOREAN, Script.CHINESE, Script.LATIN).filter { it != model }
                    for (other in choices) {
                        ensureActive()
                        if (best >= 40) break
                        val alternate = recognizeTile(tile, other, index * 2000)
                        val score = quality(alternate, other)
                        if (score > best) { lines = alternate; model = other; best = score }
                    }
                }
                if (explicit != null || quality(lines, model) >= 6) selected = model
                for (line in lines) {
                    val mapped = line.copy(box = line.box.moved(box.left, box.top), words = line.words.map { it.copy(box = it.box.moved(box.left, box.top)) })
                    val existing = output.indexOfFirst { overlapRatio(it.box, mapped.box) > .59 && (it.text.contains(mapped.text) || mapped.text.contains(it.text)) }
                    if (existing < 0) output += mapped else if (mapped.text.length > output[existing].text.length) output[existing] = mapped
                }
            } finally { if (tile !== image) tile.recycle() }
        }
        OcrPage(output, selected == Script.JAPANESE || selected == Script.CHINESE)
    }

    private suspend fun recognizeTile(tile: Bitmap, script: Script, base: Int): List<OcrLine> {
        val recognizer = client(script)
        // ML Kit's Task cannot be cancelled; keep the backing bitmap alive until it finishes.
        val result = withContext(NonCancellable) { recognizer.process(InputImage.fromBitmap(tile, 0)).await() }
        coroutineContext.ensureActive()
        return result.textBlocks.flatMapIndexed { block, group ->
            group.lines.mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                val box = Box(bounds.left, bounds.top, bounds.right, bounds.bottom)
                val words = line.elements.mapNotNull { el -> el.boundingBox?.let { OcrWord(el.text, Box(it.left, it.top, it.right, it.bottom)) } }
                val p = line.cornerPoints
                val angled = p != null && p.size >= 2 && abs(p[1].y - p[0].y) > abs(p[1].x - p[0].x) * 1.1
                val asian = script == Script.JAPANESE || script == Script.CHINESE
                OcrLine(line.text, box, words, base + block, angled || (asian && box.height > box.width * 1.65 && line.text.length > 1))
            }
        }
    }

    override fun close() = synchronized(lock) { closed = true; clients.values.forEach { it.close() }; clients.clear() }
}

private fun scriptOf(code: String): Script? = when (code.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
    "ja" -> Script.JAPANESE
    "ko" -> Script.KOREAN
    "zh", "yue" -> Script.CHINESE
    "hi", "mr", "ne", "sa", "doi", "mai", "devanagari" -> Script.DEVANAGARI
    "latin" -> Script.LATIN
    else -> null
}

private fun quality(lines: List<OcrLine>, script: Script): Int {
    val value = lines.joinToString(" ") { it.text }
    return when (script) {
        Script.KOREAN -> value.count { it in '\uac00'..'\ud7af' } * 5
        Script.JAPANESE -> value.count { it in '\u3041'..'\u30ff' } * 5 + value.count { it in '\u4e00'..'\u9fff' } * 3
        Script.CHINESE -> value.count { it in '\u4e00'..'\u9fff' } * 4
        Script.DEVANAGARI -> value.count { it in '\u0900'..'\u097f' } * 4
        Script.LATIN -> value.count { it.isLetter() } + value.split(Regex("[^A-Za-z]+")).count { it.length >= 3 } * 3
    }
}

private fun overlapRatio(a: Box, b: Box): Double = a.intersection(b).toDouble() / minOf(a.width * a.height, b.width * b.height).coerceAtLeast(1)

private fun tiles(width: Int, height: Int): List<Box> {
    if (height <= 2400 && width <= 2400) return listOf(Box(0, 0, width, height))
    val normal = 1800
    val overlap = 150
    fun offsets(span: Int): List<Int> = buildList {
        var offset = 0
        add(0)
        while (offset + normal < span) { offset += normal - overlap; add(offset) }
    }
    return offsets(height).flatMap { y -> offsets(width).map { x -> Box(x, y, (x + normal).coerceAtMost(width), (y + normal).coerceAtMost(height)) } }
}

/** Opt-in alternative for unsupported scripts, complex vertical lettering and sparse bubbles. */
internal class CloudVisionOcrProvider(private val key: String) : OcrProvider {
    override suspend fun recognize(image: Bitmap, hint: OcrHint): OcrPage = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream().use { stream ->
            if (!image.compress(Bitmap.CompressFormat.JPEG, 84, stream)) throw IOException("Image could not be encoded")
            stream.toByteArray()
        }
        if (bytes.size > 5_000_000) throw IOException("Image exceeds Cloud Vision limit")
        val feature = JSONObject().put("type", "DOCUMENT_TEXT_DETECTION")
        val input = JSONObject().put("image", JSONObject().put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            .put("features", JSONArray().put(feature))
        val hints = when (hint.explicit) { "ja", "ko", "zh" -> hint.explicit; "devanagari" -> "hi"; else -> null }
        if (hints != null) input.put("imageContext", JSONObject().put("languageHints", JSONArray().put(hints)))
        val request = Request.Builder().url("https://vision.googleapis.com/v1/images:annotate").header("X-Goog-Api-Key", key)
            .post(JSONObject().put("requests", JSONArray().put(input)).toString().toRequestBody(jsonMediaType)).build()
        parseVision(privateClient.read(request, 6_000_000), hint)
    }

    private fun parseVision(json: String, hint: OcrHint): OcrPage {
        val response = JSONObject(json).optJSONArray("responses")?.optJSONObject(0) ?: throw ResponseFailure()
        if (response.has("error")) throw ResponseFailure()
        val lines = mutableListOf<OcrLine>()
        var groupId = 0
        var right = hint.explicit in setOf("ja", "zh") || hint.documentLanguage.substringBefore('-') in setOf("ja", "zh", "ar", "fa", "ckb")
        val pages = response.optJSONObject("fullTextAnnotation")?.optJSONArray("pages")
        for (page in objects(pages)) for (block in objects(page.optJSONArray("blocks"))) for (paragraph in objects(block.optJSONArray("paragraphs"))) {
            groupId++
            val props = objects(paragraph.optJSONObject("property")?.optJSONArray("detectedLanguages"))
            if (props.any { it.optString("languageCode").substringBefore('-') in setOf("ja", "zh", "ar", "fa", "ckb") }) right = true
            val words = mutableListOf<OcrWord>()
            val text = StringBuilder()
            fun flush() {
                if (words.isNotEmpty() && text.isNotBlank()) {
                    val box = words.map { it.box }.reduce(Box::union)
                    lines += OcrLine(text.toString().trim(), box, words.toList(), groupId, box.height > box.width * 1.6 && right)
                }
                words.clear(); text.clear()
            }
            for (word in objects(paragraph.optJSONArray("words"))) {
                val bounds = objects(word.optJSONObject("boundingBox")?.optJSONArray("vertices"))
                if (bounds.size < 3) continue
                val box = Box(bounds.minOf { it.optInt("x") }, bounds.minOf { it.optInt("y") }, bounds.maxOf { it.optInt("x") }, bounds.maxOf { it.optInt("y") })
                val symbols = objects(word.optJSONArray("symbols"))
                val value = symbols.joinToString("") { it.optString("text") }
                if (value.isBlank()) continue
                words += OcrWord(value, box); text.append(value)
                when (symbols.lastOrNull()?.optJSONObject("property")?.optJSONObject("detectedBreak")?.optString("type")) {
                    "LINE_BREAK", "EOL_SURE_SPACE" -> flush()
                    "HYPHEN" -> { text.append('-'); flush() }
                    "SPACE", "SURE_SPACE" -> text.append(' ')
                }
            }
            flush()
        }
        return OcrPage(lines.take(500), right)
    }
    private fun objects(array: JSONArray?) = if (array == null) emptyList() else
        (0 until array.length().coerceAtMost(1500)).mapNotNull(array::optJSONObject)
}
