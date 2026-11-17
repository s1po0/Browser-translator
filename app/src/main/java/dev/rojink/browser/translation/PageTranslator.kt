package dev.rojink.browser.translation

import android.content.Context
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rojink.browser.R
import dev.rojink.browser.data.ApiKey
import dev.rojink.browser.data.BrowserDao
import dev.rojink.browser.data.KeyVault
import dev.rojink.browser.web.Browser
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.DocumentFeatures
import dev.rojink.browser.web.origin
import dev.rojink.browser.web.webUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class ReaderOptions(val target: String, val text: Boolean, val images: Boolean, val script: String, val useVision: Boolean)
internal data class ReaderProviders(val translator: TranslationProvider, val variant: String, val visionKey: String?)
internal data class WebImage(val id: Int, val url: String, val kind: String, val priority: Int, val lang: String)
internal data class ImageWork(val candidate: WebImage, val referer: HttpUrl, val userAgent: String, val cookies: android.webkit.CookieManager?,
    val fallback: suspend () -> ByteArray?)

internal sealed interface RenderedImage {
    fun stream(): InputStream
    data class Stored(val file: File) : RenderedImage { override fun stream(): InputStream = file.inputStream() }
    data class Memory(val bytes: ByteArray) : RenderedImage { override fun stream(): InputStream = ByteArrayInputStream(bytes) }
}

internal fun interface PageImageProcessor {
    suspend fun process(info: ImageWork, options: ReaderOptions, providers: ReaderProviders, privateTab: Boolean): RenderedImage?
}

class PageStatus internal constructor(val options: ReaderOptions, internal val revision: Long, internal val key: String, internal val origin: String) {
    var original by mutableStateOf(false)
        internal set
    var discovered by mutableIntStateOf(0)
        internal set
    var completed by mutableIntStateOf(0)
        internal set
    var busyText by mutableStateOf(false)
        internal set
    var error by mutableIntStateOf(0)
        internal set
    var imageErrors by mutableIntStateOf(0)
        internal set
    internal var job: Job? = null
    internal var active = true
}

internal class ProviderFactory(private val dao: BrowserDao, private val keys: KeyVault) {
    suspend fun get(privateSession: Boolean, wantVision: Boolean): ReaderProviders {
        val cloud = keys.read(ApiKey.TRANSLATE)
        val vision = if (wantVision) keys.read(ApiKey.VISION) else null
        val engine = if (cloud != null) "google-cloud-v2" else "google-gtx-v1"
        val provider = GoogleTranslateProvider(cloud)
        return ReaderProviders(if (privateSession) provider else TextCache(dao, provider, engine), engine, vision)
    }
}

/** Owns consent for a single top-level document. Navigation invalidates all async results. */
class PageTranslator internal constructor(
    private val context: Context,
    private val browser: Browser,
    private val providersFor: suspend (Boolean, Boolean) -> ReaderProviders,
    private val processImage: PageImageProcessor? = null,
) : DocumentFeatures {
    private val states = mutableStateMapOf<String, PageStatus>()
    private val script by lazy { context.assets.open("reader.js").bufferedReader().use { it.readText() } }
    private data class Route(val tabId: String, val origin: String, val image: RenderedImage)
    private val routes = ConcurrentHashMap<String, Route>() // read on WebView's IO thread
    private val privateOrder = mutableMapOf<String, LinkedHashMap<String, Int>>()

    fun status(tab: BrowserTab): PageStatus? = states[tab.id]

    fun start(tab: BrowserTab, options: ReaderOptions) {
        if (!options.text && !options.images) return
        val web = tab.view ?: return
        val address = webUrl(tab.address) ?: return
        if (tab.progress != 100 || tab.failed || tab.destroyed) return
        stop(tab)
        val key = "__rojink_" + UUID.randomUUID().toString().replace("-", "")
        val state = PageStatus(options, tab.revision, key, address.origin())
        state.active = browser.canScan(tab)
        states[tab.id] = state
        state.busyText = options.text
        state.job = browser.scope.launch {
            try {
                val providers = providersFor(tab.incognito, options.images && options.useVision)
                val flags = JSONObject().put("text", options.text).put("images", options.images && processImage != null)
                val injection = "$script(${JSONObject.quote(key)},$flags)"
                if (eval(tab, state, web, injection) != "true") throw ResponseFailure()
                scanLoop(tab, web, address, state, providers)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (current(tab, state)) { state.error = R.string.translate_error; state.busyText = false } }
        }
    }

    fun toggle(tab: BrowserTab) {
        val state = states[tab.id] ?: return
        state.original = !state.original
        tab.view?.evaluateJavascript(call(state, "show", state.original.toString()), null)
    }

    override fun allowImageMenu(tab: BrowserTab) = states[tab.id]?.original != false

    override fun foreground(tab: BrowserTab, visible: Boolean) {
        states[tab.id]?.let { state ->
            state.active = visible
            tab.view?.evaluateJavascript(call(state, "active", visible.toString()), null)
        }
    }

    fun stopAll() { browser.tabs.toList().forEach(::stop) }
    fun stop(tab: BrowserTab) = remove(tab, restore = true)
    override fun invalidate(tab: BrowserTab) = remove(tab, restore = true)
    override fun closed(tab: BrowserTab) = remove(tab, restore = false)

    private fun remove(tab: BrowserTab, restore: Boolean) {
        val state = states.remove(tab.id) ?: return
        state.job?.cancel()
        if (restore) tab.view?.evaluateJavascript(call(state, "stop", ""), null)
        routes.entries.removeIf { it.value.tabId == tab.id }
        privateOrder.remove(tab.id)
    }

    override fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET" || request.isForMainFrame && request.url.host != "rojink.invalid") return null
        val url = webUrl(request.url.toString()) ?: return null
        val path = url.encodedPath
        if (path.startsWith("/.__rojink/")) {
            val route = routes[path]?.takeIf { it.tabId == tab.id && it.origin == url.origin() }
            if (route == null) return emptyLocal()
            val content = runCatching { route.image.stream() }.getOrNull() ?: return emptyLocal()
            return WebResourceResponse("image/png", null, 200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), content)
        }
        if (url.host == "rojink.invalid" && url.scheme == "https") {
            val (asset, mime) = when (path) {
                "/demo/" -> "demo/index.html" to "text/html"
                "/demo/chapter.png" -> "demo/chapter.png" to "image/png"
                "/demo/menu.png" -> "demo/menu.png" to "image/png"
                else -> return emptyLocal()
            }
            return try { WebResourceResponse(mime, if (mime == "text/html") "utf-8" else null, 200, "OK",
                mapOf("Cache-Control" to "no-store"), context.assets.open(asset)) }
            catch (_: Exception) { emptyLocal() }
        }
        return null
    }

    private suspend fun scanLoop(tab: BrowserTab, web: WebView, initial: HttpUrl, state: PageStatus, providers: ReaderProviders) = coroutineScope {
        var lastText: Job? = null
        val imageJobs = linkedSetOf<Job>()
        val pending = java.util.PriorityQueue<WebImage>(compareBy<WebImage> { it.priority }.thenBy { it.id })
        val seen = mutableSetOf<Int>()
        val imageResults = mutableMapOf<String, RenderedImage?>()
        var idle = 0
        while (isActive && current(tab, state)) {
            if (!state.active || state.original) { delay(450); continue }
            imageJobs.removeAll { it.isCompleted }
            val wantImages = processImage != null && state.options.images && pending.size < 4
            // One outstanding text batch. Do not pull more until the bridge can consume it.
            val canTakeText = lastText?.isActive != true && state.options.text && state.error == 0
            val raw = eval(tab, state, web, call(state, "take", "$wantImages,$canTakeText"))
            val snapshot = raw?.takeIf { it.length < 680000 && it.startsWith('{') }?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (!current(tab, state)) break
            val items = snapshot?.optJSONArray("texts")
            if (canTakeText && items != null && items.length() > 0) {
                val queue = List(items.length().coerceAtMost(24)) { i -> items.optJSONObject(i) }.mapNotNull { entry ->
                    val id = entry?.optInt("id", -1) ?: -1
                    val text = entry?.opt("text") as? String
                    if (id <= 0 || text.isNullOrBlank() || text.length > 10_000) null else id to text
                }
                if (queue.isNotEmpty()) {
                    state.busyText = true
                    lastText = launch {
                        try {
                            val output = providers.translator.translate(queue.map { it.second }, "auto", state.options.target)
                            if (output.size != queue.size) throw ResponseFailure()
                            val translated = JSONArray()
                            queue.zip(output).forEach { (input, after) -> translated.put(JSONObject().put("id", input.first).put("text", after.take(22_000))) }
                            eval(tab, state, web, call(state, "text", translated.toString()))
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { if (current(tab, state)) state.error = R.string.translate_error }
                        finally { if (current(tab, state)) state.busyText = false }
                    }
                }
            } else if (lastText?.isActive != true) state.busyText = false
            if (wantImages && snapshot != null) {
                val candidates = snapshot.optJSONArray("images") ?: JSONArray()
                val lang = snapshot.optString("lang").take(30)
                for (i in 0 until candidates.length().coerceAtMost(16)) {
                    val entry = candidates.optJSONObject(i) ?: continue
                    val id = entry.optInt("id", -1)
                    val kind = entry.optString("kind")
                    val url = entry.optString("url")
                    if (id <= 0 || kind !in listOf("img", "bg", "canvas") || url.length > 12000 || !seen.add(id)) continue
                    pending.add(WebImage(id, url, kind, entry.optInt("priority", 0), entry.optString("lang").ifBlank { lang }.take(30)))
                    state.discovered++
                }
            }
            state.imageErrors += (snapshot?.optInt("errors") ?: 0).coerceIn(0, 50)
            while (processImage != null && imageJobs.size < 3 && pending.isNotEmpty() && state.active && !state.original) {
                val candidate = pending.remove()
                val cookies = tab.cookies
                val page = webUrl(tab.address)?.takeIf { it.origin() == state.origin } ?: initial
                val agent = web.settings.userAgentString
                // Note: no files, no credentials, no cross-frame data exposed to JavaScript.
                imageJobs += launch {
                    try {
                        if (!current(tab, state) || !state.active) return@launch
                        val previous = candidate.url.takeIf { it.isNotEmpty() && it in imageResults }
                        val image = if (previous != null) imageResults[previous] else processImage.process(
                            ImageWork(candidate, page, agent, cookies) { snapshotBitmap(tab, state, web, candidate.id) },
                            state.options, providers, tab.incognito,
                        )
                        if (candidate.url.isNotEmpty() && image !is RenderedImage.Memory && imageResults.size < 850) imageResults[candidate.url] = image
                        if (image != null && current(tab, state)) {
                            val path = "/.__rojink/${state.key.substringAfterLast('_')}/${candidate.id}.png"
                            val sourceOrigin = webUrl(candidate.url)?.origin()
                            val localOrigin = if (sourceOrigin == null || sourceOrigin == state.origin) state.origin else "https://rojink.invalid"
                            putRoute(tab, path, Route(tab.id, localOrigin, image))
                            if (eval(tab, state, web, call(state, "picture", "${candidate.id},${JSONObject.quote(localOrigin + path)}")) != "true") {
                                routes.remove(path)
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: HttpFailure) {
                        if (current(tab, state)) { state.imageErrors++; if (e.status == 403 || e.status == 429) state.error = R.string.translate_error }
                    } catch (_: Exception) { if (current(tab, state)) state.imageErrors++ }
                    finally { if (current(tab, state)) state.completed++ }
                }
            }
            val more = (items?.length() ?: 0) > 0 || imageJobs.isNotEmpty() || pending.isNotEmpty()
            idle = if (more) 0 else (idle + 1).coerceAtMost(5)
            delay(if (idle > 2) 450 else 190)
        }
    }

    private fun putRoute(tab: BrowserTab, path: String, route: Route) {
        val mem = route.image as? RenderedImage.Memory
        if (mem != null) {
            val order = privateOrder.getOrPut(tab.id) { LinkedHashMap() }
            order[path] = mem.bytes.size
            var size = order.values.sum()
            val iter = order.iterator()
            while (size > 10_000_000 && order.size > 1 && iter.hasNext()) {
                val old = iter.next()
                size -= old.value
                routes.remove(old.key)
                iter.remove()
            }
        }
        routes[path] = route
    }

    private suspend fun snapshotBitmap(tab: BrowserTab, state: PageStatus, web: WebView, id: Int): ByteArray? {
        val response = eval(tab, state, web, call(state, "snap", id.toString())) ?: return null
        if (response.length > 500_000) return null
        val data = runCatching { JSONTokener(response).nextValue() as? String }.getOrNull() ?: return null
        if (!data.startsWith("data:image/jpeg;base64,")) return null
        return runCatching { Base64.decode(data.substringAfter(','), Base64.DEFAULT) }.getOrNull()
    }

    private fun current(tab: BrowserTab, state: PageStatus): Boolean = states[tab.id] === state && tab.revision == state.revision &&
        !tab.destroyed && webUrl(tab.address)?.origin() == state.origin

    private suspend fun eval(tab: BrowserTab, state: PageStatus, web: WebView, code: String): String? {
        if (!current(tab, state) || tab.view !== web) return null
        val value = withTimeoutOrNull(4500) {
            suspendCancellableCoroutine { continuation -> web.evaluateJavascript(code) { value -> if (continuation.isActive) continuation.resume(value) } }
        }
        return value?.takeIf { current(tab, state) }
    }

    private fun call(state: PageStatus, method: String, arguments: String) = "window[${JSONObject.quote(state.key)}]?.$method($arguments)"
    private fun emptyLocal() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(byteArrayOf()))
}
