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

    @Test fun `recovered lettering replaces its partial word boxes without leaving overlapping overlays`() {
        val partial = listOf(
            OcrLine("WAS", Box(33, 20, 53, 28), emptyList(), 0),
            OcrLine("ONG.", Box(71, 20, 94, 28), emptyList(), 1),
        )
        val recovered = OcrLine("I WAS WRONG.", Box(19, 18, 95, 30), emptyList(), 2, confidence = .91f)
        val result = reconcileComicOcr(partial, listOf(recovered), explicit = true) { true }
        assertEquals(listOf(recovered.copy(block = 0)), result)
    }

    @Test fun `aligned menu rows with separate price labels do not merge in comic mode`() {
        val lines = listOf(line("TEA OF THE DAY", 15, 20), line("SOUP OF THE DAY", 15, 54), line("$2.50", 180, 20), line("$3.50", 180, 54))
        val layout = groupForImage(OcrPage(lines, false, comic = true)) { _, _ -> true }
        assertEquals(listOf("TEA OF THE DAY", "SOUP OF THE DAY"), layout.units.map { it.text })
        assertEquals(lines.takeLast(2).map { it.box }, layout.protected)
    }

    @Test fun `Sorani fallback retries the whole unit and leaves bad results in the original pixels`() = runBlocking {
        val dialogue = "MY AIM WAS OFF, AND I LOST FOCUS AFTER I TOOK A LONG BREAK."
        val inputs = listOf(dialogue, "YOU", "Open https://example.com now")
        val mock = RecordingTranslator { texts -> texts.map { if (it == dialogue) "MY کە تێکچوو" else it } }
        val retry = RecordingTranslator { texts -> texts.map { if (it == dialogue) "ئامانجم تێکچوو و سەرنجم لەدەست دا." else if (it == "YOU") "تۆ" else "لینکەکە بکەرەوە" } }
        val translated = translateImageWords(inputs.map(::unit), "ckb", true, mock, retry)
        assertEquals(listOf(Call(inputs, "en", "ckb")), mock.calls)
        assertEquals(inputs.filterNot { it == "YOU" }.map { Call(listOf(it), "en", "ckb") }, retry.calls)
        assertEquals(listOf("ئامانجم تێکچوو و سەرنجم لەدەست دا.", "تۆ", inputs.last()), translated)
    }

    @Test fun `automatic mode stays auto for mixed languages and retry work is bounded`() = runBlocking {
        val inputs = (1..12).map { "You can try $it" } + listOf("こんにちは", "안녕하세요")
        val base = RecordingTranslator { it }
        val retry = RecordingTranslator { it }
        assertEquals(inputs, translateImageWords(inputs.map(::unit), "ckb", false, base, retry))
        assertEquals(listOf(Call(inputs, "auto", "ckb")), base.calls)
        assertEquals(inputs.take(8).map { Call(listOf(it), "auto", "ckb") }, retry.calls)
    }

    @Test fun `a standalone English YOU can be translated locally if Google leaves it unchanged`() = runBlocking {
        val base = RecordingTranslator { it }
        val retry = RecordingTranslator { it }
        assertEquals(listOf("تۆ؟", "نەخێر!"), translateImageWords(listOf(unit("YOU?"), unit("NO!")), "ckb", true, base, retry))
        assertEquals(listOf("NO!"), translateImageWords(listOf(unit("NO!")), "ckb", false, base, retry))
        val context = "I THINK WE CAN STILL WIN."
        assertEquals(listOf(context, "تۆ؟"), translateImageWords(listOf(unit(context), unit("YOU?")), "ckb", false, base, retry))
        assertFalse("Known short replies don't require a second network call", retry.calls.any { it.input == listOf("YOU?") })
    }

    @Test fun `review fixes grounded fused words but does not guess text on an unrelated background`() {
        val fast = PaddleText("IAM STILL HERE.", .97f)
        val stronger = PaddleText("I AM STILL HERE.", .97f)
        assertTrue(needsLatinReview(fast, null, enclosed = true, recovered = false))
        assertTrue(preferLatinReview(fast, stronger, null))
        assertFalse(needsLatinReview(PaddleText("z1", .6f), null, enclosed = false, recovered = false))
        assertFalse(preferLatinReview(PaddleText("YOU", .99f), PaddleText("TRY", .99f), null))
    }

    @Test fun `an enclosure permits wider leading and blocks merging a neighboring balloon`() {
        val first = line("I…", 15, 10, 1)
        val second = line("I MISSED YOU.", 15, 66, 2)
        val third = line("I AM HERE.", 15, 96, 2)
        val firstBubble = ComicBubble(Box(3, 5, 153, 93), listOf(first.box, second.box), false)
        val other = ComicBubble(Box(4, 95, 146, 130), listOf(third.box), false)
        val layout = groupForImage(OcrPage(listOf(first, second, third), false, comic = true, bubbles = listOf(firstBubble, other)))
        assertEquals(listOf("I… I MISSED YOU.", "I AM HERE."), layout.units.map { it.text })
    }

    @Test fun `proposes an enclosed faint line even when averaged pixels hide it`() {
        val width = 52
        val height = 85
        val background = IntArray(width * height) { 139 }
        for (y in 15..29) for (x in 9..30) background[y * width + x] = 238
        val low = background.copyOf()
        for (y in 21..23) for (x in 14..25 step 2) low[y * width + x] = 160
        val bubble = findComicBubbles(background, width, height, width * 4, height * 4, low).single()
        assertEquals(Box(36, 60, 124, 120), bubble.bounds)
        assertEquals(1, missedBubbleBands(bubble, emptyList()).size)
        val heard = OcrLine("I WAS WRONG.", bubble.bands.single(), emptyList(), 1, confidence = .95f)
        assertTrue(missedBubbleBands(bubble, listOf(heard)).isEmpty())
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
