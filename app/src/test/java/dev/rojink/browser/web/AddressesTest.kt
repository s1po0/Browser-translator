package dev.rojink.browser.web

import org.junit.Assert.*
import org.junit.Test

class AddressesTest {
    @Test fun `web links are navigable and everything else is local or rejected`() {
        assertEquals(AddressInput.Link("https://example.org/chapter-1"), addressInput(" example.org/chapter-1 "))
        assertEquals(AddressInput.Query("read manga"), addressInput("read manga"))
        assertEquals(AddressInput.Query("١.٢"), addressInput("١.٢"))
        assertNull(addressInput("file:///etc/passwd"))
        assertNull(addressInput("https://user:password@example.org"))
        assertNull(addressInput("javascript:alert(1)"))
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
