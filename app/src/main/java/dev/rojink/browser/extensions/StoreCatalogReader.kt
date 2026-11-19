package dev.rojink.browser.extensions

import dev.rojink.browser.web.exchange
import dev.rojink.browser.web.readLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

private const val INDEX_LIMIT = 8 * 1024 * 1024
private const val APK_LIMIT = 32L * 1024 * 1024
private val redirectCodes = setOf(301, 302, 303, 307, 308)

/** A dedicated HTTPS client: no browser cookies, headers, JavaScript or cross-scheme redirects. */
internal class StoreHttp(client: OkHttpClient = OkHttpClient()) {
    private val network = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun index(url: HttpUrl): Pair<HttpUrl, ByteArray> = get(url, 35_000) { location, body ->
        if (body.contentLength() > INDEX_LIMIT) throw StoreException(StoreProblem.FORMAT)
        val source = BufferedInputStream(body.byteStream())
        source.mark(2)
        val gzip = source.read() == 0x1f && source.read() == 0x8b
        source.reset()
        val data = (if (gzip) GZIPInputStream(source) else source).use { readLimited(it, INDEX_LIMIT) }
        location to data
    }

    suspend fun apk(url: HttpUrl, target: File) = withContext(Dispatchers.IO) {
        // Own the file across the suspend point so a cancelled HTTP callback cannot recreate it.
        target.outputStream().buffered().use { output ->
            get(url, 60_000) { _, body ->
                if (body.contentLength() > APK_LIMIT) throw StoreException(StoreProblem.APK)
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > APK_LIMIT) throw StoreException(StoreProblem.APK)
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) throw StoreException(StoreProblem.APK)
                }
            }
        }
    }

    private suspend fun <T : Any> get(url: HttpUrl, timeout: Long, read: (HttpUrl, ResponseBody) -> T): T =
        withTimeoutOrNull(timeout) { follow(url, read) } ?: throw IOException("Extension request timed out")

    private suspend fun <T : Any> follow(start: HttpUrl, read: (HttpUrl, ResponseBody) -> T): T {
        var url = secureStoreUrl(start.toString()) ?: throw StoreException(StoreProblem.ADDRESS)
        val visited = mutableSetOf<HttpUrl>()
        repeat(6) {
            val location = url
            if (!visited.add(location)) throw IOException("Extension request redirected in a loop")
            val result = network.exchange(Request.Builder().url(location).build()) { response ->
                if (response.code in redirectCodes) {
                    val target = response.header("Location")?.let { resolveSecure(location, it) }
                        ?: throw IOException("Invalid extension redirect")
                    Hop.Redirect(target)
                } else {
                    if (!response.isSuccessful) throw IOException("Extension request failed: HTTP ${response.code}")
                    Hop.Content(read(location, response.body ?: throw IOException("Empty extension response")))
                }
            }
            when (result) {
                is Hop.Redirect -> url = result.url
                is Hop.Content -> return result.content
            }
        }
        throw IOException("Too many extension redirects")
    }

    private sealed interface Hop<out T> {
        data class Redirect(val url: HttpUrl) : Hop<Nothing>
        data class Content<T>(val content: T) : Hop<T>
    }
}

private fun resolveSecure(base: HttpUrl, link: String): HttpUrl? = link.trim()
    .takeIf { it.length in 1..MAX_STORE_URL && it.none { ch -> ch.isISOControl() || ch == '\\' } }
    ?.let(base::resolve)?.toString()?.let(::secureStoreUrl)

internal class StoreCatalogReader(private val http: StoreHttp = StoreHttp()) {
    suspend fun load(address: String, saved: SavedExtensionStore? = null): ExtensionCatalog = withContext(Dispatchers.Default) {
        var url = parseStoreInput(address) ?: throw StoreException(StoreProblem.ADDRESS)
        var official = url.isKeiyoushiIndex()
        if (url.pathSegments.last() == "index.min.json") url = url.sibling("repo.json")
        val visited = mutableSetOf<HttpUrl>()
        repeat(5) {
            if (!visited.add(url)) throw StoreException(StoreProblem.FORMAT)
            val (location, bytes) = http.index(url)
            official = official || url.isKeiyoushiIndex() || location.isKeiyoushiIndex()
            fun identity(name: String, key: String): SavedExtensionStore {
                val signer = fingerprint(key) ?: throw StoreException(StoreProblem.FORMAT)
                if ((official && signer != KEIYOUSHI_SIGNER) || (saved != null && signer != saved.signer)) {
                    throw StoreException(StoreProblem.SIGNER)
                }
                val title = catalogLabel(name).ifBlank { throw StoreException(StoreProblem.FORMAT) }
                // HTTP redirects may lead to temporary CDN URLs. Save the stable index, not the response location.
                val indexUrl = saved?.indexUrl ?: if (official) KEIYOUSHI_INDEX else url.toString()
                return SavedExtensionStore(indexUrl, title, signer)
            }
            when (val document = readIndex(bytes)) {
                is IndexDocument.Modern -> {
                    val index = document.index
                    val store = identity(index.name, index.signingKey)
                    val inline = index.extensionList
                    val pointer = index.extensionListUrl
                    val list = when {
                        inline != null && pointer == null -> inline
                        inline == null && pointer != null -> {
                            val listUrl = resolveSecure(location, pointer) ?: throw StoreException(StoreProblem.FORMAT)
                            readSplitList(http.index(listUrl).second)
                        }
                        else -> throw StoreException(StoreProblem.FORMAT)
                    }
                    return@withContext ExtensionCatalog(store, list.entries())
                }
                is IndexDocument.Legacy -> {
                    val repo = document.repo
                    if (repo.indexV2 != null) {
                        url = resolveSecure(location, repo.indexV2) ?: throw StoreException(StoreProblem.FORMAT)
                        if (url.pathSegments.last() == "index.min.json") url = url.sibling("repo.json")
                    } else {
                        val meta = repo.meta ?: throw StoreException(StoreProblem.FORMAT)
                        val store = identity(meta.name, meta.signingKeyFingerprint)
                        if (official || location.pathSegments.last() != "repo.json") throw StoreException(StoreProblem.FORMAT)
                        val list = http.index(location.sibling("index.min.json")).second
                        return@withContext ExtensionCatalog(store, legacyEntries(list, location))
                    }
                }
            }
        }
        throw StoreException(StoreProblem.FORMAT)
    }
}
