package dev.rojink.browser.translation

import dev.rojink.browser.data.BrowserDao
import dev.rojink.browser.data.CachedText
import dev.rojink.browser.web.readLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

interface TranslationProvider {
    suspend fun translate(texts: List<String>, source: String, target: String): List<String>
}

internal class HttpFailure(val status: Int, val retryAfter: Long?) : IOException("Provider HTTP $status")
internal class ResponseFailure : IOException("Unexpected response from translation provider")

internal val privateClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build()
private val googleCalls = Semaphore(4)
private val cooldownUntil = AtomicLong(0)
private val codePattern = Regex("[a-zA-Z0-9-]{2,24}")
private val freeEndpoint = "https://translate.googleapis.com/translate_a/single".toHttpUrl()
private val cloudEndpoint = "https://translation.googleapis.com/language/translate/v2".toHttpUrl()
internal val jsonMediaType = "application/json; charset=utf-8".toMediaType()

/** Free gtx is intentionally replaceable; neither transport logs text or credentials. */
class GoogleTranslateProvider internal constructor(
    private val key: String?,
    private val client: OkHttpClient,
    private val gtx: HttpUrl,
    private val cloud: HttpUrl,
) : TranslationProvider {
    constructor(key: String?) : this(key, privateClient, freeEndpoint, cloudEndpoint)

    override suspend fun translate(texts: List<String>, source: String, target: String): List<String> = withContext(Dispatchers.IO) {
        require(codePattern.matches(source) && codePattern.matches(target) && target != "auto")
        if (source.equals(target, true)) return@withContext texts
        val distinct = texts.filter(String::isNotBlank).distinct().associateWith { splitLong(it, 3400) }
        val translated = HashMap<String, String>()
        for (chunk in batches(distinct.values.flatten().distinct(), 3800, if (key == null) 14 else 50)) {
            val output = if (key == null) freeBatch(chunk, source, target) else official(chunk, source, target)
            if (output.size != chunk.size) throw ResponseFailure()
            chunk.zip(output).forEach { (a, b) -> translated[a] = b }
        }
        texts.map { text -> distinct[text]?.joinToString(" ") { part -> translated[part] ?: throw ResponseFailure() } ?: text }
    }

    private suspend fun official(texts: List<String>, source: String, target: String): List<String> {
        val payload = JSONObject().put("q", JSONArray(texts)).put("target", target).put("format", "text")
        if (source != "auto") payload.put("source", source)
        val request = Request.Builder().url(cloud).header("X-Goog-Api-Key", requireNotNull(key))
            .post(payload.toString().toRequestBody(jsonMediaType)).build()
        val response = send(request)
        return parseCloud(response, texts.size)
    }

    private suspend fun freeBatch(texts: List<String>, source: String, target: String): List<String> {
        if (texts.size == 1) return listOf(freeOne(texts.single(), source, target))
        val salt = Random.nextInt(106000, 989999).toString()
        val merged = texts.mapIndexed { index, text -> "[[$salt${index.toString().padStart(2, '0')}]]\n$text" }.joinToString("\n")
        try {
            val response = freeOne(merged, source, target)
            splitMarked(response, salt, texts.size)?.let { return it }
        } catch (e: HttpFailure) {
            if (e.status !in listOf(400, 405, 413, 414)) throw e
        }
        // If a model alters a boundary, don't associate the wrong translation with a bubble.
        return coroutineScope {
            texts.map { text -> async { freeOne(text, source, target) } }.map { it.await() }
        }
    }

    private suspend fun freeOne(text: String, source: String, target: String): String {
        val base = gtx.newBuilder().addQueryParameter("client", "gtx").addQueryParameter("sl", source)
            .addQueryParameter("tl", target).addQueryParameter("dt", "t").build()
        val getUrl = base.newBuilder().addQueryParameter("q", text).build()
        val request = Request.Builder().apply {
            if (getUrl.toString().length < 1900) url(getUrl)
            else url(base).post(FormBody.Builder().add("q", text).build())
        }.build()
        return try { parseGtx(send(request)) } catch (e: HttpFailure) {
            if (request.method != "POST" || e.status !in listOf(400, 405, 413, 414) || text.length <= 140) throw e
            // Some gtx deployments reject POST. Use short UTF-8-safe GETs in that case.
            splitLong(text, 140).map { part -> freeOne(part, source, target) }.joinToString(" ")
        }
    }

    private suspend fun send(request: Request): String {
        var failure: IOException? = null
        repeat(3) { attempt ->
            try {
                return googleCalls.withPermit {
                    val wait = (cooldownUntil.get() - System.currentTimeMillis()).coerceIn(0, 3500)
                    if (wait > 0) delay(wait)
                    client.read(request, 1_000_000)
                }
            } catch (e: IOException) {
                failure = e
                if (e is HttpFailure && e.status != 429 && e.status !in 500..599) throw e
                if (e is HttpFailure && e.status == 429) {
                    val wait = e.retryAfter?.coerceIn(1L, 4L)?.times(1000) ?: 1700
                    cooldownUntil.set(System.currentTimeMillis() + wait)
                }
                if (attempt < 2) delay(400L * (1 shl attempt) + Random.nextLong(160))
            }
        }
        throw failure ?: ResponseFailure()
    }
}

internal fun parseGtx(json: String): String = parseSafely {
    val entries = JSONArray(json).optJSONArray(0) ?: throw ResponseFailure()
    buildString {
        for (i in 0 until entries.length()) (entries.optJSONArray(i)?.opt(0) as? String)?.let { append(it) }
    }.takeIf { it.isNotBlank() } ?: throw ResponseFailure()
}

internal fun parseCloud(json: String, expected: Int): List<String> = parseSafely {
    val result = JSONObject(json).optJSONObject("data")?.optJSONArray("translations") ?: throw ResponseFailure()
    if (result.length() != expected) throw ResponseFailure()
    List(expected) { index ->
        val value = result.optJSONObject(index)?.opt("translatedText") as? String ?: throw ResponseFailure()
        Parser.unescapeEntities(value, false).takeIf { it.isNotBlank() } ?: throw ResponseFailure()
    }
}

private inline fun <T> parseSafely(block: () -> T): T = try { block() } catch (_: org.json.JSONException) { throw ResponseFailure() }

private val marker = Regex("\\[\\s*\\[\\s*(\\p{Nd}{8})\\s*]\\s*]")
private val formattingMarks = Regex("[\\u200E\\u200F\\u061C\\u2066-\\u2069]")
internal fun splitMarked(rendered: String, salt: String, size: Int): List<String>? {
    val clean = rendered.replace(formattingMarks, "")
    val matches = marker.findAll(clean).toList()
    if (matches.isEmpty() || matches.size != size || clean.substring(0, matches.first().range.first).isNotBlank()) return null
    return matches.mapIndexed { index, match ->
        val digits = match.groupValues[1].map { it.digitToIntOrNull() ?: return null }.joinToString("")
        if (digits != "$salt${index.toString().padStart(2, '0')}") return null
        clean.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: clean.length).trim().ifBlank { return null }
    }
}

internal fun splitLong(value: String, max: Int): List<String> {
    if (value.length <= max) return listOf(value)
    val parts = mutableListOf<String>()
    var start = 0
    while (start < value.length) {
        var end = (start + max).coerceAtMost(value.length)
        if (end < value.length) {
            val space = value.lastIndexOf(' ', end - 1)
            if (space > start + max / 2) end = space + 1
            if (Character.isHighSurrogate(value[end - 1])) end--
        }
        parts += value.substring(start, end)
        start = end
    }
    return parts
}

private fun batches(values: List<String>, maxSize: Int, maxCount: Int): List<List<String>> = buildList {
    var current = mutableListOf<String>()
    var size = 0
    values.forEach { text ->
        if (current.isNotEmpty() && (current.size == maxCount || size + text.length + 16 > maxSize)) {
            add(current); current = mutableListOf(); size = 0
        }
        current.add(text); size += text.length + 16
    }
    if (current.isNotEmpty()) add(current)
}

class TextCache(
    private val dao: BrowserDao, private val delegate: TranslationProvider, private val namespace: String,
    private val cacheable: (before: String, after: String, source: String, target: String) -> Boolean = { _, _, _, _ -> true },
) : TranslationProvider {
    override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
        val keys = texts.filter(String::isNotBlank).distinct().associateWith { sha256("$namespace\u0000$source\u0000$target\u0000$it".toByteArray()) }
        if (keys.isEmpty()) return texts
        val saved = keys.values.chunked(350).flatMap { dao.cached(it) }.associate { it.key to it.value }.toMutableMap()
        val missing = keys.filterValues { it !in saved }
        if (missing.isNotEmpty()) {
            val entries = missing.entries.toList()
            val translated = delegate.translate(entries.map { it.key }, source, target)
            if (translated.size != entries.size) throw ResponseFailure()
            val additions = entries.zip(translated).mapNotNull { (entry, output) ->
                saved[entry.value] = output
                CachedText(entry.value, output).takeIf { cacheable(entry.key, output, source, target) }
            }
            if (additions.isNotEmpty()) { dao.putCached(additions); dao.trimCached() }
        }
        return texts.map { saved[keys[it]] ?: it }
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** Read on OkHttp's dispatcher, with cancellation closing the socket even during body reads. */
internal suspend fun <T> OkHttpClient.exchange(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try { continuation.resume(response.use(read)) } catch (e: Exception) { continuation.resumeWithException(e) }
        }
    })
}

internal suspend fun OkHttpClient.read(request: Request, limit: Int): String = exchange(request) { response ->
    if (!response.isSuccessful) throw HttpFailure(response.code, response.header("Retry-After")?.toLongOrNull())
    val body = response.body ?: throw IOException("Missing provider response")
    readLimited(body.byteStream(), limit).toString(Charsets.UTF_8)
}
