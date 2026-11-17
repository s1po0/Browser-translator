package dev.rojink.browser.translation

import org.junit.Assert.*
import org.junit.Test

class OcrLayoutTest {
    private fun line(text: String, box: Box, block: Int = 0, vertical: Boolean = false) = OcrLine(text, box, emptyList(), block, vertical)

    @Test fun `keeps item translations separate from price columns`() {
        val tea = OcrWord("Mint", Box(22, 52, 56, 74))
        val noun = OcrWord("tea", Box(60, 52, 90, 74))
        val price = OcrWord("$3.50", Box(330, 52, 395, 74))
        val result = groupForImage(OcrPage(listOf(OcrLine("Mint tea $3.50", Box(22, 52, 395, 74), listOf(tea, noun, price), 0),
            line("١٢٫٥٠", Box(330, 92, 395, 114), 1)), false))
        assertEquals(listOf("Mint tea"), result.units.map { it.text })
        assertEquals(listOf(price.box, Box(330, 92, 395, 114)), result.protected)
        assertFalse(result.units.single().box.overlaps(price.box))
    }

    @Test fun `groups nearby aligned lines but not a distant bubble`() {
        val original = listOf(line("Meet me", Box(10, 12, 100, 35)), line("at sundown.", Box(12, 40, 106, 64)), line("Okay!", Box(205, 70, 270, 93), 1))
        val result = groupForImage(OcrPage(original, false)).units
        assertEquals(listOf("Meet me at sundown.", "Okay!"), result.map { it.text })
        assertEquals(2, result.first().lines.size)
    }

    @Test fun `vertical columns and bubbles are read right first`() {
        val right = line("こんにちは", Box(210, 20, 233, 120), vertical = true)
        val left = line("世界", Box(180, 23, 201, 110), vertical = true)
        val other = line("またね", Box(35, 20, 54, 120), 2, true)
        val result = groupForImage(OcrPage(listOf(left, other, right), true)).units
        assertEquals(listOf("こんにちは世界", "またね"), result.map { it.text })
    }
}
