package dev.rojink.browser.translation

import java.nio.FloatBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ComicOcrTest {
    @Test fun `detector map ignores low scores and maps expanded bounds to source pixels`() {
        val map = FloatArray(32 * 32) { .1f }
        for (y in 8..12) for (x in 6..20) map[y * 32 + x] = .89f
        for (y in 20..24) for (x in 5..20) map[y * 32 + x] = .40f
        assertEquals(listOf(Box(6, 10, 48, 32)), paddleLineBoxes(FloatBuffer.wrap(map), 32, 32, 64, 64))
        val (w, h) = paddleDimensions(800, 1640)
        assertTrue(w % 32 == 0 && h % 32 == 0 && w < h && h <= 960)
    }

    @Test fun `recognizer preserves CTC spaces and repeat separated by blank`() {
        val classes = listOf("A", "B", " ")
        val best = listOf(1, 1, 0, 1, 3, 3, 2)
        val scores = FloatBuffer.wrap(FloatArray(best.size * 4) { if (it % 4 == best[it / 4]) .96f else .01f })
        val result = paddleRead(scores, best.size, classes)
        assertEquals("AA B", result.value)
        assertEquals(.96f, result.confidence, .001f)
    }

    @Test fun `English host does not override recognized script or force comic OCR for non-English text`() {
        fun page(value: String, script: Script) = OcrPage(listOf(line(value, 15, 25)), false, script = script)
        val auto = OcrHint("auto", "en-US")
        assertFalse(useComicOcr(page("こんにちは", Script.JAPANESE), auto))
        assertFalse(useComicOcr(page("안녕하세요", Script.KOREAN), auto))
        assertFalse(useComicOcr(page("Je ne comprends pas.", Script.LATIN), auto))
        assertTrue(useComicOcr(page("YOU CAN TRY", Script.LATIN), auto))
        assertFalse(useComicOcr(page("YOU CAN TRY", Script.LATIN), auto.copy(enhanced = false)))
        assertTrue(useComicOcr(page("YOU", Script.LATIN), OcrHint(ENGLISH_COMIC_SCRIPT, "", enhanced = false)))
    }

    @Test fun `uses the extra reading to fix spacing and recover missing words without replacing a price`() {
        val base = line("AFTER ITOOK", 15, 20).copy(words = listOf(OcrWord("AFTER", Box(22, 20, 70, 42))))
        val price = line("$7.25", 15, 110)
        val alternate = listOf(base.copy(text = "AFTER I TOOK", box = base.box.expand(2, 2), confidence = .95f),
            line("YOU", 190, 20).copy(confidence = .92f), price.copy(text = "S725", confidence = .99f))
        val result = reconcileComicOcr(listOf(base, price), alternate, false) { true }
        assertEquals(listOf("AFTER I TOOK", "$7.25", "YOU"), result.map { it.text })
        assertEquals(base.box, result.first().box)
        assertEquals(base.words, result.first().words)
        assertEquals(listOf("AFTER I TOOK", "$7.25"), reconcileComicOcr(listOf(base, price), alternate, false) { false }.map { it.text })
    }

    @Test fun `light interior links separate OCR blocks but a separator vetoes even the same OCR block`() {
        val page = OcrPage(listOf(line("MY AIM WAS OFF.", 15, 15, 1), line("CAN YOU HELP?", 18, 49, 2),
            line("GO NOW!", 18, 81, 2), line("I WILL WAIT.", 145, 49, 3), line("ID42", 15, 125, 7)), false, comic = true)
        val layout = groupForImage(page) { a, b -> a.y < 40 && b.y < 80 }
        assertEquals(setOf("MY AIM WAS OFF. CAN YOU HELP?", "GO NOW!", "I WILL WAIT."), layout.units.map { it.text }.toSet())
        assertEquals(listOf(Box(15, 125, 128, 147)), layout.protected)
    }

    @Test fun `Sorani fallback retries the whole unit and leaves bad results in the original pixels`() = runBlocking {
        val dialogue = "MY AIM WAS OFF, AND I LOST FOCUS AFTER I TOOK A LONG BREAK."
        val inputs = listOf(dialogue, "YOU", "Open https://example.com now")
        val mock = RecordingTranslator { texts -> texts.map { if (it == dialogue) "YOU کە تێکچوو" else it } }
        val retry = RecordingTranslator { texts -> texts.map { if (it == dialogue) "ئامانجم تێکچوو و سەرنجم لەدەست دا." else if (it == "YOU") "تۆ" else "لینکەکە بکەرەوە" } }
        val translated = translateImageWords(inputs.map(::unit), "ckb", true, mock, retry)
        assertEquals(listOf(Call(inputs, "en", "ckb")), mock.calls)
        assertEquals(inputs.map { Call(listOf(it), "en", "ckb") }, retry.calls)
        assertEquals(listOf("ئامانجم تێکچوو و سەرنجم لەدەست دا.", "تۆ", inputs.last()), translated)
    }

    @Test fun `automatic mode never forces English and retry work is bounded`() = runBlocking {
        val inputs = listOf("You can wait", "You can go", "You can come", "You can try", "こんにちは")
        val base = RecordingTranslator { it }
        val retry = RecordingTranslator { it }
        assertEquals(inputs, translateImageWords(inputs.map(::unit), "ckb", false, base, retry))
        assertEquals(listOf(Call(inputs, "auto", "ckb")), base.calls)
        assertEquals(inputs.take(3).map { Call(listOf(it), "auto", "ckb") }, retry.calls)
    }

    private fun line(text: String, left: Int, top: Int, block: Int = 0) = OcrLine(text, Box(left, top, left + 113, top + 22), emptyList(), block)
    private fun unit(text: String) = TextUnit(text, Box(0, 0, 110, 56), emptyList(), 22, false)
    private data class Call(val input: List<String>, val source: String, val target: String)
    private class RecordingTranslator(val reply: (List<String>) -> List<String>) : TranslationProvider {
        val calls = mutableListOf<Call>()
        override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
            calls += Call(texts.toList(), source, target)
            return reply(texts)
        }
    }
}
