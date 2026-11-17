package dev.rojink.browser.search

import org.junit.Assert.*
import org.junit.Test

class SipanIndexTest {
    @Test fun `normalizes Sorani variants and ranks title matches above aliases`() {
        val title = SearchEntry("https://books.example/", "کوردیی سۆرانی", "کتێب", "books Kurdish reading")
        val other = SearchEntry("https://docs.example/", "Archive", terms = "کوردیی سۆرانی Kurdish docs")
        val all = listOf(other, title)
        assertEquals(listOf(title, other), findInSipan("كورديي سۆراني", all, emptyList(), false))
        assertEquals(listOf(title), findInSipan("kurd read", all, emptyList(), false))
        assertTrue(findInSipan("nowhere", all, emptyList(), false).isEmpty())
    }

    @Test fun `private search never matches normal tab pages or history`() {
        val catalog = SearchEntry("https://directory.example/", "Public manga")
        val saved = SearchEntry("https://public.example/chapter", "Chapter", terms = "secret sample drawing", source = SearchSource.SAVED_PAGE)
        val history = SearchEntry("https://other.example/visit", "Secret visit", source = SearchSource.HISTORY)
        assertEquals(listOf(history, saved), findInSipan("secret", listOf(catalog), listOf(saved, history), false))
        assertTrue(findInSipan("secret", listOf(catalog), listOf(saved, history), true).isEmpty())
        assertEquals(listOf(catalog), findInSipan("manga", listOf(catalog), listOf(saved), true))
    }

    @Test fun `checks catalog URLs and removes duplicate destinations`() {
        val docs = readSipanIndex(sequenceOf(
            "# comment", "file:///stuff\tInvalid\tIgnored\tbook", "https://example.org/#1\tComic\tComic book\tmanga",
            "https://example.org/#2\tComic\tSecond record\tmanga", "https://name:pass@example.org/\tNo\to\tx"
        ))
        assertEquals(2, docs.size)
        assertEquals(1, findInSipan("manga", docs, emptyList(), false).size)
    }
}
