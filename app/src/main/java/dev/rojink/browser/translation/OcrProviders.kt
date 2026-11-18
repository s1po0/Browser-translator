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
import kotlinx.coroutines.CancellationException
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
import kotlin.math.ceil
import kotlin.math.floor

internal data class OcrHint(val explicit: String, val documentLanguage: String, val enhanced: Boolean = true)
internal interface OcrProvider { suspend fun recognize(image: Bitmap, hint: OcrHint): OcrPage }

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
        val explicit = when (hint.explicit) {
            ENGLISH_IMAGE_LANGUAGE, ENGLISH_COMIC_SCRIPT -> Script.LATIN
            else -> scriptOf(hint.explicit)
        }
        val suggested = scriptOf(hint.documentLanguage)
        var preferred = explicit ?: suggested ?: Script.LATIN
        val output = mutableListOf<OcrLine>()
        val votes = mutableMapOf<Script, Double>()
        var remainingExtra = if (hint.enhanced) 8 else 3
        var remainingDetail = if (hint.enhanced) 3 else 0
        var retries = 0
        val leading = listOfNotNull(suggested, Script.JAPANESE, Script.KOREAN, Script.CHINESE, Script.LATIN, Script.DEVANAGARI).distinct()
        for ((index, bounds) in ocrTiles(image.width, image.height).withIndex()) {
            ensureActive()
            if (output.size >= 500) break
            val tile = Bitmap.createBitmap(image, bounds.left, bounds.top, bounds.width, bounds.height)
            var extra: Bitmap? = null
            try {
                val base = index * 10_000
                var model = preferred
                var lines = recognizeTile(tile, model, base)
                var evidence = ocrEvidence(lines, model)
                if (remainingDetail > 0 && needsOcrDetail(lines, model)) {
                    remainingDetail--
                    extra = clearerOcrBitmap(tile)
                    if (extra != null) {
                        retries++
                        val recovered = recoverTile(extra, tile, model, base + 1000)
                        val recoveredEvidence = ocrEvidence(recovered, model)
                        lines = if (recoveredEvidence > evidence * 1.09 + .5) {
                            recovered.toMutableList().also { mergeOcrLines(it, lines, .55f) }
                        } else {
                            lines.toMutableList().also { mergeOcrLines(it, recovered, .60f) }
                        }
                        evidence = ocrEvidence(lines, model)
                    }
                }
                if (explicit == null && remainingExtra > 0 && (evidence < 10.5 || (index == 0 && suggested == null))) {
                    // Probe Japanese and Korean independently: an English site can still serve untranslated comics.
                    val start = model
                    val comparison = leading.filter { it != start }.take(if (evidence >= 10.5) 2 else 4)
                    for ((i, candidate) in comparison.withIndex()) {
                        if (remainingExtra <= 0) break
                        if (i >= 2 && evidence >= 16) break
                        remainingExtra--
                        val alternate = recoverTile(extra ?: tile, tile, candidate, base + (i + 2) * 1000)
                        val alternateEvidence = ocrEvidence(alternate, candidate)
                        if (alternateEvidence > evidence * 1.13 + 1.8 && scriptLetters(alternate, candidate) >= 3) {
                            // Keep independent confident lines from the first script, not conflicting guesses over the same box.
                            val previous = lines
                            lines = alternate.toMutableList().also { merged ->
                                mergeOcrLines(merged, previous.filterNot { prior -> alternate.any { areaOverlap(prior.box, it.box) > .57 } }, .69f)
                            }
                            model = candidate
                            evidence = ocrEvidence(lines, model)
                        }
                    }
                }
                if (evidence >= 6) {
                    preferred = model
                    votes[model] = (votes[model] ?: 0.0) + evidence
                }
                val mapped = lines.map { line ->
                    line.copy(box = line.box.moved(bounds.left, bounds.top), words = line.words.map { it.copy(box = it.box.moved(bounds.left, bounds.top)) })
                }
                mergeOcrLines(output, mapped)
            } finally {
                extra?.recycle()
                if (tile !== image) tile.recycle()
            }
        }
        val dominant = explicit ?: votes.maxByOrNull { it.value }?.key ?: suggested
        OcrPage(output, dominant == Script.JAPANESE || dominant == Script.CHINESE, retries, script = dominant)
    }

    private suspend fun recoverTile(input: Bitmap, normal: Bitmap, script: Script, block: Int): List<OcrLine> {
        val recognized = try { recognizeTile(input, script, block) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return emptyList() }
        if (input === normal) return recognized
        val x = normal.width.toDouble() / input.width
        val y = normal.height.toDouble() / input.height
        fun map(box: Box) = Box(floor(box.left * x).toInt(), floor(box.top * y).toInt(), ceil(box.right * x).toInt(),
            ceil(box.bottom * y).toInt()).clip(normal.width, normal.height)
        return recognized.map { line -> line.copy(box = map(line.box), words = line.words.map { it.copy(box = map(it.box)) }) }
    }

    private suspend fun recognizeTile(tile: Bitmap, script: Script, base: Int): List<OcrLine> {
        val recognizer = client(script)
        // ML Kit's Task cannot be cancelled; keep the backing bitmap alive until it finishes.
        val result = withContext(NonCancellable) { recognizer.process(InputImage.fromBitmap(tile, 0)).await() }
        coroutineContext.ensureActive()
        return result.textBlocks.flatMapIndexed { block, group ->
            group.lines.mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                val box = Box(bounds.left, bounds.top, bounds.right, bounds.bottom).clip(tile.width, tile.height)
                val words = line.elements.mapNotNull { el -> el.boundingBox?.let { OcrWord(el.text, Box(it.left, it.top, it.right, it.bottom).clip(tile.width, tile.height)) } }
                val p = line.cornerPoints
                val angled = p != null && p.size >= 2 && abs(p[1].y - p[0].y) > abs(p[1].x - p[0].x) * 1.1
                val asian = script == Script.JAPANESE || script == Script.CHINESE
                val confidence = line.confidence.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(1f) ?: .64f
                OcrLine(line.text, box, words, base + block, angled || (asian && box.height > box.width * 1.65 && line.text.length > 1), confidence)
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
        val hints = when (hint.explicit) {
            "ja", "ko", "zh", ENGLISH_IMAGE_LANGUAGE -> hint.explicit
            "devanagari" -> "hi"
            ENGLISH_COMIC_SCRIPT -> ENGLISH_IMAGE_LANGUAGE
            else -> null
        }
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
