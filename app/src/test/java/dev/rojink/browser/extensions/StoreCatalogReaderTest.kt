package dev.rojink.browser.extensions

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream

class StoreCatalogReaderTest {
    private val key = "ab".repeat(32)
    private val address = "https://extensions.example/catalog/index.pb"
    private val requests = CopyOnWriteArrayList<String>()

    @Test fun `Keiyoushi gzip protobuf follows the raw redirect and uses release APKs`() = runBlocking {
        val raw = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
        val catalog = reader(KEIYOUSHI_INDEX to Redirect(raw), raw to keiFixture()).load(KEIYOUSHI_INDEX)
        assertEquals(SavedExtensionStore(KEIYOUSHI_INDEX, "Keiyoushi", KEIYOUSHI_SIGNER), catalog.store)
        assertEquals(2, catalog.entries.size)
        val manga = catalog.entries.search("manga pt-br", false).single()
        assertEquals("MANGA Plus by SHUEISHA", manga.name)
        assertEquals(106069L, manga.versionCode)
        assertEquals("1.6.69", manga.version)
        assertEquals(CatalogWarning.SAFE, manga.warning)
        assertEquals("https://mangaplus.shueisha.co.jp/", manga.sources.first().website.toString())
        assertEquals("https://github.com/keiyoushi/extensions/releases/download/8947c37/tachiyomi-all.mangaplus-v1.6.69.apk", manga.apk.toString())
        assertEquals(listOf(KEIYOUSHI_INDEX, raw), requests)
    }

    @Test fun `old Keiyoushi address discovers index_v2 instead of treating migration placeholders as entries`() = runBlocking {
        val old = KEIYOUSHI_INDEX.replace("index.pb", "index.min.json")
        val repo = KEIYOUSHI_INDEX.replace("index.pb", "repo.json")
        val catalog = reader(repo to """{"index_v2":"$KEIYOUSHI_INDEX","meta":{"name":"Keiyoushi"}}""",
            KEIYOUSHI_INDEX to keiFixture()).load("tachiyomi://add-repo?url=$old")
        assertEquals(KEIYOUSHI_INDEX, catalog.store.indexUrl)
        assertEquals(2, catalog.entries.size)
        assertEquals(listOf(repo, KEIYOUSHI_INDEX), requests)
    }

    @Test fun `split JSON uses relative lists and only lists safe entries by default`() = runBlocking {
        val list = "https://extensions.example/catalog/entries.json"
        val safe = """{"name":"Test extension","packageName":"ext.demo.safe","versionCode":"7000000000","versionName":"1.6.2",
            "resources":{"apkUrl":"https://releases.example/safe.apk","jarUrl":"https://releases.example/safe.jar"},
            "contentWarning":"CONTENT_WARNING_SAFE","sources":[{"id":"22645934","name":"Comics","language":"en","homeUrl":"https://reader.example"}]}"""
        val mixed = """{"name":"Example mixed","packageName":"ext.demo.mixed","versionCode":"1","contentWarning":"CONTENT_WARNING_MIXED"}"""
        val unknown = """{"name":"Example future","packageName":"ext.demo.future","versionCode":2,"contentWarning":"FUTURE_RATING"}"""
        val index = """{"name":"Demo","contact":{"website":"https://example.com"},"signingKey":"${key.uppercase()}","extensionListUrl":"entries.json"}"""
        val catalog = reader(address to index, list to "\uFEFF  \n{\"extensions\":[$safe,$mixed,$unknown]}").load(address)
        val entry = catalog.entries.search("comics en", false).single()
        assertEquals("Test extension", entry.name)
        assertEquals(7_000_000_000L, entry.versionCode)
        assertEquals("https://releases.example/safe.apk", entry.apk.toString())
        assertEquals(1, catalog.entries.search("", false).size)
        assertEquals(2, catalog.entries.search("example", true).size)
        assertEquals(key, catalog.store.signer)
    }

    @Test fun `legacy stores need a signed descriptor and APK paths are filenames`() = runBlocking {
        val repo = "https://legacy.example/store/repo.json"
        val list = "https://legacy.example/store/index.min.json"
        val catalog = reader(repo to """{"meta":{"name":"Legacy","signingKeyFingerprint":"$key"}}""", list to """[
            {"name":"Tachiyomi: Old comics","pkg":"ext.old.comics","apk":"comics.apk","lang":"fr","code":7,"version":"1.4.7","nsfw":0},
            {"name":"Bad path","pkg":"ext.old.bad","apk":"../not-a-file.apk","lang":"en","code":8,"nsfw":0}
        ]""").load(list)
        assertEquals(repo, catalog.store.indexUrl)
        val entry = catalog.entries.search("fr", false).single()
        assertEquals("Old comics", entry.name)
        assertEquals("https://legacy.example/store/apk/comics.apk", entry.apk.toString())
        assertNull(catalog.entries.first().apk)
        assertEquals(listOf(repo, list), requests)
    }

    @Test fun `saved signer changes and incorrect official pin fail before a linked list is fetched`() = runBlocking {
        val index = """{"name":"New store","signingKey":"$key","extensionListUrl":"https://another.example/list.pb"}"""
        val saved = SavedExtensionStore(address, "Saved", "12".repeat(32))
        expectProblem(StoreProblem.SIGNER) { reader(address to index).load(address, saved) }
        expectProblem(StoreProblem.SIGNER) { reader(KEIYOUSHI_INDEX to index).load(KEIYOUSHI_INDEX) }
        assertEquals(listOf(address, KEIYOUSHI_INDEX), requests)
    }

    @Test fun `bad payloads malformed keys and circular descriptors do not add a store`() = runBlocking {
        expectProblem(StoreProblem.FORMAT) { reader(address to "<html>Not a catalog</html>").load(address) }
        expectProblem(StoreProblem.FORMAT) { reader(address to """{"name":"Demo","signingKey":"untrusted","extensionList":{"extensions":[]}}""").load(address) }
        expectProblem(StoreProblem.FORMAT) { reader(address to """{"index_v2":"$address"}""").load(address) }
        assertEquals(listOf(address, address, address), requests)
    }

    @Test fun `oversized gzip and cleartext redirects fail without a second request`() = runBlocking {
        val huge = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(ByteArray(8 * 1024 * 1024 + 1)) } }.toByteArray()
        for (body in listOf(huge, Redirect("http://extensions.example/catalog/index.pb"))) {
            try { reader(address to body).load(address); fail("Request must fail") } catch (_: IOException) { }
        }
        assertEquals(listOf(address, address), requests)
    }

    @Test fun `store links unwrap exactly once and reject untrusted URL forms`() {
        assertEquals(KEIYOUSHI_INDEX, parseStoreInput("mihon://extension-store?url=https%3A%2F%2Fgithub.com%2Fkeiyoushi%2Fextensions%2Fraw%2Frepo%2Findex.pb").toString())
        assertEquals(address, parseStoreInput("tachiyomi://add-repo?url=$address").toString())
        listOf("http://example.com/index.pb", "file:///tmp/index.pb", "javascript:alert(1)", "https://a:b@example.com/index.pb",
            "https://example.com/index.pb#fragment", "https:///example.com/index.pb", "tachiyomi://add-repo?url=$address&url=$address",
            "unknown://add-repo?url=$address", "mihon://extension-store/elsewhere?url=$address").forEach { assertNull(it, parseStoreInput(it)) }
    }

    private data class Redirect(val to: String)
    private fun reader(vararg routes: Pair<String, Any>): StoreCatalogReader {
        val data = routes.toMap()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            requests += url
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            when (val item = data[url]) {
                is Redirect -> response.code(302).header("Location", item.to).body("".toResponseBody()).build()
                is ByteArray -> response.code(200).body(item.toResponseBody()).build()
                is String -> response.code(200).body(item.toResponseBody()).build()
                else -> throw AssertionError("Unexpected request: $url")
            }
        }.build()
        return StoreCatalogReader(StoreHttp(client))
    }

    private fun keiFixture() = requireNotNull(javaClass.getResourceAsStream("/extension-stores/keiyoushi-sample.pb.gz")).use { it.readBytes() }
    private suspend fun expectProblem(problem: StoreProblem, block: suspend () -> Unit) {
        try { block(); fail("Expected $problem") } catch (e: StoreException) { assertEquals(problem, e.problem) }
    }
}
