package dev.rojink.browser.web

import org.junit.Assert.*
import org.junit.Test

class AddressesTest {
    @Test fun `supports links and encodes search without loading non-web schemes`() {
        assertEquals("https://example.org/chapter-1", resolveAddress(" example.org/chapter-1 ", "google"))
        assertEquals("https://www.bing.com/search?q=read%20manga", resolveAddress("read manga", "bing"))
        assertNull(resolveAddress("file:///etc/passwd", "google"))
        assertNull(webUrl("https://user:password@example.org"))
    }

    @Test fun `paste opens web links without sending clipboard text to a search engine`() {
        assertEquals("https://example.org/chapter-2", resolveLink("  example.org/chapter-2  "))
        assertEquals("http://example.org/page?q=read", resolveLink("http://example.org/page?q=read"))
        listOf("", "unrelated clipboard text", "file:///sdcard/book", "javascript:alert(1)",
            "https://one.example/\nhttps://two.example/", "https://name:pw@example.org").forEach { assertNull(resolveLink(it)) }
    }

    @Test fun `site boundary does not follow a misleading suffix or scheme downgrade`() {
        assertTrue(sameSite(webUrl("https://manga.example.com")!!, webUrl("https://cdn.example.com")!!))
        assertFalse(sameSite(webUrl("https://example.com")!!, webUrl("https://example.com.evil.example")!!))
        assertFalse(sameSite(webUrl("http://example.com")!!, webUrl("https://example.com")!!))
        assertTrue(isTracker("img.doubleclick.net"))
        assertFalse(isTracker("nodoubleclick.net"))
    }
}
