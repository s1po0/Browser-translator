package dev.rojink.browser.translation

import org.junit.Assert.*
import org.junit.Test

class OcrQualityTest {
    @Test fun `native high-confidence Japanese beats fake Latin lettering`() {
        val box = Box(3, 4, 140, 25)
        val noise = OcrLine("II vv iaoh lll rr me lll", box, emptyList(), 0, confidence = .24f)
        val japanese = OcrLine("こんにちは世界", box, emptyList(), 0, confidence = .89f)
        assertTrue(ocrEvidence(listOf(japanese), Script.JAPANESE) > ocrEvidence(listOf(noise), Script.LATIN))
        assertEquals(0, scriptLetters(listOf(japanese), Script.KOREAN))
        assertFalse(needsOcrDetail(listOf(japanese), Script.JAPANESE))
        assertTrue(needsOcrDetail(listOf(noise), Script.LATIN))
        assertTrue(needsOcrDetail(listOf(japanese.copy(box = Box(0, 0, 140, 13))), Script.JAPANESE))
    }

    @Test fun `overlapping tall tiles cover the last row and duplicate text only once`() {
        val boxes = ocrTiles(510, 8130)
        assertEquals(0, boxes.first().top)
        assertEquals(8130, boxes.last().bottom)
        assertTrue(boxes.zipWithNext().all { (a, b) -> a.bottom > b.top })
        val small = OcrLine("안녕하세요", Box(20, 16, 103, 33), emptyList(), 0, confidence = .67f)
        val full = small.copy(text = "안녕하세요!", box = Box(19, 15, 106, 35), confidence = .81f)
        val result = mutableListOf(small)
        mergeOcrLines(result, listOf(full))
        assertEquals(listOf(full), result)
    }
}
