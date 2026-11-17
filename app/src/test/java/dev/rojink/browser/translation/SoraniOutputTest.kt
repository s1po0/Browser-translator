package dev.rojink.browser.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SoraniOutputTest {
    @Test fun `glossary writes names and reading terms in Sorani without changing names into meanings`() {
        assertEquals("ناروتۆ ئوزوماکی", polishSorani("Naruto Uzumaki"))
        assertEquals("سونگ جین وو هات.", polishSorani("Sung Jin-Woo هات."))
        assertEquals("پۆتەر و شەنۆن", polishSorani("Potter و Shannon"))
        assertEquals("بەش ٢", polishSorani("Chapter ٢"))
        assertEquals("بەش", polishSorani("chapter"))
    }

    @Test fun `retains links prices identifiers and English sentences not translated by the provider`() {
        val text = "لوفی · https://example.org/Naruto?id=1 · name@example.net · A1-B2 · USD 8.50 · 1,500 CAD · `some_function`"
        assertEquals(text, polishSorani(text.replace("لوفی", "Luffy")))
        assertEquals("We should go home now.", polishSorani("We should go home now."))
        assertEquals("پۆتەر و leftover text", polishSorani("Potter و leftover text"))
        assertEquals("A1-B2", polishSorani("A1-B2"))
        assertEquals("هەولێر", polishSorani("هەولێر"))
    }

    @Test fun `postprocessing is local and only for the Sorani target`() = runBlocking {
        var calls = 0
        val delegate = object : TranslationProvider {
            override suspend fun translate(texts: List<String>, source: String, target: String): List<String> = texts.map { calls++; "Naruto" }
        }
        val output = SoraniOutput(delegate)
        assertEquals(listOf("Naruto"), output.translate(listOf("name"), "en", "de"))
        assertEquals(listOf("ناروتۆ"), output.translate(listOf("name"), "en", "ckb"))
        assertEquals(2, calls)
    }
}
