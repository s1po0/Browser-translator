package dev.rojink.browser.translation

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GoogleTranslateTest {
    @Test fun `parses multiple segments and rejects unexpected payloads`() {
        assertEquals("سڵاو. چۆنی؟", parseGtx("""[[["سڵاو. ","Hello. "],["چۆنی؟","How are you?"]],null,"en"]"""))
        assertThrows(ResponseFailure::class.java) { parseGtx("{\"oops\": 3}") }
        assertThrows(ResponseFailure::class.java) { parseGtx("[[[{\"nested\":true}]]]") }
        assertThrows(ResponseFailure::class.java) { parseCloud("""{"data":{"translations":[{"translatedText":"x"}]}}""", 2) }
    }

    @Test fun `marker parsing recognizes Arabic digits and refuses reordered markers`() {
        assertEquals(listOf("سڵاو", "بەیانی باش"), splitMarked("[[١٢٣٤٥٦٠٠]] سڵاو\n[[12345601]] بەیانی باش", "123456", 2))
        assertNull(splitMarked("[[12345601]] second [[12345600]] first", "123456", 2))
        assertNull(splitMarked("no markers", "123456", 2))
    }

    @Test fun `chunks do not split surrogate pairs`() {
        val input = "xx😺yy zz".repeat(40)
        val result = splitLong(input, 3)
        assertEquals(input, result.joinToString(""))
        assertFalse(result.any { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }

    @Test fun `cloud sends semantic batch once and keeps duplicate order`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"translations":[{"translatedText":"سڵاو &amp; ڕۆژباش"},{"translatedText":"سوپاس"}]}}"""))
            val provider = GoogleTranslateProvider("sample-test-key", OkHttpClient(), server.url("/gtx"), server.url("/cloud"))
            assertEquals(listOf("سڵاو & ڕۆژباش", "سوپاس", "سڵاو & ڕۆژباش"), provider.translate(listOf("Hello", "Thanks", "Hello"), "auto", "ckb"))
            val request = server.takeRequest()
            assertEquals("/cloud", request.path)
            assertEquals("sample-test-key", request.getHeader("X-Goog-Api-Key"))
            assertTrue(request.body.readUtf8().contains("\"target\":\"ckb\""))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `a long free-provider request keeps text out of the query string`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[[["سڵاو","source"]],null,"en"]"""))
            val provider = GoogleTranslateProvider(null, OkHttpClient(), server.url("/gtx"), server.url("/cloud"))
            val source = "Hello from Android. ".repeat(112)
            assertEquals(listOf("سڵاو"), provider.translate(listOf(source), "auto", "ckb"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertNull(request.requestUrl!!.queryParameter("q"))
            assertTrue(request.body.readUtf8().startsWith("q=Hello"))
        }
    }

    @Test fun `unofficial endpoint requests Sorani without a key`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[[["سڵاو","Hello"]],null,"en"]"""))
            val provider = GoogleTranslateProvider(null, OkHttpClient(), server.url("/gtx"), server.url("/cloud"))
            assertEquals(listOf("سڵاو"), provider.translate(listOf("Hello"), "auto", "ckb"))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("gtx", request.requestUrl!!.queryParameter("client"))
            assertEquals("auto", request.requestUrl!!.queryParameter("sl"))
            assertEquals("ckb", request.requestUrl!!.queryParameter("tl"))
            assertNull(request.getHeader("X-Goog-Api-Key"))
        }
    }
}
