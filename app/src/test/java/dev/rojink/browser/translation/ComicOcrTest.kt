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

    @Test fun `a high mean line score can hide a competing U on the same ink`() {
        val alphabet = listOf("H", "L", "U", "N", "T", "E", "R", "I")
        val rows = listOf("H", "L", "I", "N", "T", "E", "R")
        val scores = FloatArray(rows.size * (alphabet.size + 1))
        for ((row, letter) in rows.withIndex()) {
            val at = row * (alphabet.size + 1)
            scores[at + alphabet.indexOf(letter) + 1] = if (letter == "I") .73f else .99f
            if (letter == "I") scores[at + alphabet.indexOf("U") + 1] = .13f
        }
        val tiny = paddleRead(FloatBuffer.wrap(scores), rows.size, alphabet)
        assertEquals("HLINTER", tiny.value)
        assertTrue(tiny.confidence > .95f)
        assertTrue(tiny.uncertainU)
        assertTrue(needsLatinReview(tiny, null, enclosed = true, recovered = false))
        assertFalse(needsLatinReview(tiny, null, enclosed = false, recovered = false))
    }

    @Test fun `optical agreement can replace confident fractured letters but not a label or a guess`() {
        val broken = line("THE HLINTER", 24, 34).copy(confidence = .98f)
        val confirmed = broken.copy(text = "THE HUNTER", box = broken.box.expand(2, 1), inkVerifiedLatin = true)
        fun output(base: OcrLine, alternate: OcrLine, surface: Boolean = true) =
            reconcileComicOcr(listOf(base), listOf(alternate), explicit = true) { surface }.single()
        assertEquals(confirmed.text, output(broken, confirmed).text)
        assertEquals(broken.box, output(broken, confirmed).box)
        assertEquals(broken.text, output(broken, confirmed.copy(inkVerifiedLatin = false)).text)
        assertEquals(broken.text, output(broken, confirmed, surface = false).text)
        assertEquals(broken.text, output(broken, confirmed.copy(text = "OUR HUNTER")).text)
        assertEquals(broken.text, output(broken, confirmed.copy(text = "THE MUNTER")).text)
        val literal = broken.copy(text = "THE HLINTER ID42")
        assertEquals(literal.text, output(literal, confirmed.copy(text = "THE HUNTER ID42")).text)
        assertEquals(LatinInkChange.FRACTURED_U, latinInkChange("HINTER.", "HUNTER,"))
        assertEquals(LatinInkChange.FRACTURED_U, latinInkChange("GIILD'S", "GUILD’S"))
        assertEquals(LatinInkChange.APOSTROPHE, latinInkChange("GUILD S", "GUILD’S"))
        assertEquals(LatinInkChange.APOSTROPHE, latinInkChange("GUILDS", "GUILD'S"))
        assertTrue(needsLatinReview(PaddleText("GUILD’S", .993f), broken.copy(text = "GUILDS"), enclosed = true, recovered = false))
        val tiny = PaddleText("HINTER.", .957f, uncertainU = true)
        val small = PaddleText("HUNTER.", .987f)
        assertTrue(verifyLatinInk(tiny, small, broken.copy(text = "HLINTER.")) { PaddleText("HUNTER,", .989f) })
        assertFalse(verifyLatinInk(tiny, small, null) { PaddleText("HINTER.", .989f) })
        assertFalse(verifyLatinInk(PaddleText("GUILDS", .98f), PaddleText("GUILD'S", .99f), null) { error("Apostrophe needs independent agreement") })
        assertTrue(verifyLatinInk(PaddleText("GUILD’S", .98f), PaddleText("GUILD'S", .99f), broken.copy(text = "GUILD S")) { null })
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
        assertFalse(useComicOcr(page("YOU CAN TRY", Script.LATIN), OcrHint(ENGLISH_IMAGE_LANGUAGE, "en-US")))
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

    @Test fun `protects a printed URL even if OCR drops a slash`() {
        for (url in listOf("https:/example.com", "HTTPS: /EXAMPLE.COM/help")) {
            assertTrue(isProtectedImageLiteral(url))
            assertEquals(listOf(url), imageLiteralSpans("Open $url now"))
            assertFalse(validImageTranslation("Open $url now", "لینکەکە بکەرەوە", "en", "ckb"))
        }
    }

    @Test fun `plain English uses en even for accented labels and Latin still uses auto`() = runBlocking {
        val texts = listOf("TEA OF THE DAY", "Crème brûlée")
        val reply = listOf("چای ئەمڕۆ", "شیرینی")
        for ((mode, expectedSource) in listOf(ENGLISH_IMAGE_LANGUAGE to "en", "latin" to "auto")) {
            val provider = RecordingTranslator { reply }
            val retry = RecordingTranslator { error("Valid menu translations do not require retries") }
            assertEquals(reply, translateImageWords(texts.map(::unit), "ckb", mode, provider, retry))
            assertEquals(listOf(Call(texts, expectedSource, "ckb")), provider.calls)
        }
    }

    @Test fun `Sorani fallback retries the whole unit and leaves bad results in the original pixels`() = runBlocking {
        val dialogue = "MY AIM WAS OFF, AND I LOST FOCUS AFTER I TOOK A LONG BREAK."
        val inputs = listOf(dialogue, "YOU", "Open https://example.com now")
        val recased = "My aim was off, and I lost focus after I took a long break."
        val mock = RecordingTranslator { texts -> texts.map { if (it == dialogue) "MY کە تێکچوو" else it } }
        val retry = RecordingTranslator { texts -> texts.map { if (it == recased) "ئامانجم تێکچوو و سەرنجم لەدەست دا." else "لینکەکە بکەرەوە" } }
        val translated = translateImageWords(inputs.map(::unit), "ckb", ENGLISH_COMIC_SCRIPT, mock, retry)
        assertEquals(listOf(Call(inputs, "en", "ckb")), mock.calls)
        assertEquals(listOf(recased, inputs.last()).map { Call(listOf(it), "en", "ckb") }, retry.calls)
        assertEquals(listOf("ئامانجم تێکچوو و سەرنجم لەدەست دا.", "تۆ", inputs.last()), translated)
    }

    @Test fun `retries copied comic fragments using live-observed alternatives without mixing balloons`() = runBlocking {
        val rank = "E-RANK HUNTER,"
        val guild = "THE HUNTER GUILD’S"
        val inputs = listOf(rank, "KEEP MOVING.", guild, rank)
        val moving = "بەردەوام بە لە ڕۆیشتن."
        // Recorded GTX behavior on the two screenshot fragments, not a live network test.
        val recovery = mapOf("E rank hunter," to "ڕاوچی پلەی E،", "The hunter guild's" to "سەندیکای ڕاوچییەکان")
        for ((mode, source) in listOf(ENGLISH_COMIC_SCRIPT to "en", ENGLISH_IMAGE_LANGUAGE to "en", "auto" to "auto")) {
            val provider = RecordingTranslator { listOf("E-RANK HUNTER،", moving, guild, "E-RANK HUNTER،") }
            val retry = RecordingTranslator { texts -> texts.map { recovery.getValue(it) } }
            val output = translateImageWords(inputs.map(::unit), "ckb", mode, provider, retry)
            assertEquals(listOf(Call(listOf("E rank hunter,", "KEEP MOVING.", "The hunter guild's", "E rank hunter,"), source, "ckb")), provider.calls)
            assertEquals(recovery.keys.map { Call(listOf(it), source, "ckb") }, retry.calls)
            assertEquals(listOf(recovery.values.first(), moving, recovery.values.last(), recovery.values.first()), output)
        }
    }

    @Test fun `rank stays the same and mixed English rank text cannot be painted`() {
        val rank = "E-RANK HUNTER,"
        for (source in listOf("auto", "en")) {
            assertFalse(validImageTranslation(rank, "E-RANK HUNTER،", source, "ckb"))
            assertFalse(validImageTranslation(rank, "ڕاوچییەکی E-rank،", source, "ckb"))
            assertFalse(validImageTranslation(rank, "ڕاوچی پلەی ئەلیکترۆنی،", source, "ckb"))
            assertFalse(validImageTranslation(rank, "ڕاوچی پلەی S،", source, "ckb"))
            assertTrue(validImageTranslation(rank, "ڕاوچی پلەی E،", source, "ckb"))
        }
        assertFalse(validImageTranslation("HUNTER,", "HUNTER،", "auto", "ckb"))
        assertTrue(validImageTranslation(rank, "Chasseur de rang E,", "en", "fr"))
    }

    @Test fun `rejects the field reply that preserves broken Latin alongside Sorani`() = runBlocking {
        val rank = "E-RANK HLINTER."
        val guild = "THE HUNTER GUILD ’ s"
        val fragments = listOf(rank, guild)
        val provider = RecordingTranslator { it }
        val retry = RecordingTranslator { texts -> texts.map { when (it) {
            "E rank hlinter." -> "E ڕیزبەندی hlinter." // Exact GTX reply from the failing OCR spelling.
            "The hunter guild's" -> "سەندیکای ڕاوچییەکان"
            else -> error("Unexpected input: $it")
        } } }
        assertEquals(listOf(rank, "سەندیکای ڕاوچییەکان"),
            translateImageWords(fragments.map(::unit), "ckb", ENGLISH_COMIC_SCRIPT, provider, retry))
        assertFalse(validImageTranslation(rank, "E ڕیزبەندی Hlinter.", "en", "ckb"))
        assertFalse(validImageTranslation("THE HUNTER GIILD'S", "ڕاوچی giild's", "auto", "ckb"))
        assertFalse(validImageTranslation("THE HUNTER GUILD S", "سەندیکای ڕاوچییەکان s", "en", "ckb"))
        assertTrue(validImageTranslation("SUNG JIN-WOO IS HERE.", "Sung Jin-Woo لێرەیە.", "en", "ckb"))
        assertTrue(validImageTranslation("Mira is here.", "Mira لێرەیە.", "en", "ckb"))
        assertTrue(validImageTranslation("E-RANK HUNTER.", "ڕاوچی پلەی E.", "en", "ckb"))
    }

    @Test fun `retry keeps protected labels and good neighbors while a bad result stays original`() = runBlocking {
        val rank = "S‑RANK MONSTER!"
        val acronyms = "I FOUND THE API."
        val literal = "OPEN HTTPS:/EXAMPLE.COM AND SAVE ID42 FOR $7.25."
        val labels = listOf("$7.25", "https:/example.com", "ID42")
        val inputs = listOf(rank, acronyms, literal) + labels
        val provider = RecordingTranslator { it }
        val retry = RecordingTranslator { texts -> texts.map {
            when (it) {
                "S rank monster!" -> "ئەهریمەنێکی پلەی S!"
                "I found the API." -> "API ـەکەم دۆزییەوە."
                literal -> "ID43 ـەکە پاشەکەوت بکە."
                else -> error("Unexpected request: $it")
            }
        } }
        assertEquals(listOf("ئەهریمەنێکی پلەی S!", "API ـەکەم دۆزییەوە.", literal) + labels,
            translateImageWords(inputs.map(::unit), "ckb", ENGLISH_COMIC_SCRIPT, provider, retry))
        assertEquals(listOf(Call(listOf("S rank monster!", acronyms, literal), "en", "ckb")), provider.calls)
        assertEquals(listOf("S rank monster!", "I found the API.", literal).map { Call(listOf(it), "en", "ckb") }, retry.calls)
    }

    @Test fun `automatic mode stays auto for mixed languages and retry work is bounded`() = runBlocking {
        val inputs = (1..12).map { "You can try $it" } + listOf("こんにちは", "안녕하세요")
        val base = RecordingTranslator { it }
        val retry = RecordingTranslator { it }
        assertEquals(inputs, translateImageWords(inputs.map(::unit), "ckb", "auto", base, retry))
        assertEquals(listOf(Call(inputs, "auto", "ckb")), base.calls)
        assertEquals(inputs.take(8).map { Call(listOf(it), "auto", "ckb") }, retry.calls)
    }

    @Test fun `a standalone English YOU can be translated locally if Google leaves it unchanged`() = runBlocking {
        val base = RecordingTranslator { it }
        val retry = RecordingTranslator { it }
        assertEquals(listOf("تۆ؟", "نەخێر!"), translateImageWords(listOf(unit("YOU?"), unit("NO!")), "ckb", ENGLISH_COMIC_SCRIPT, base, retry))
        assertEquals(listOf("NO!"), translateImageWords(listOf(unit("NO!")), "ckb", "auto", base, retry))
        val context = "I THINK WE CAN STILL WIN."
        assertEquals(listOf(context, "تۆ؟"), translateImageWords(listOf(unit(context), unit("YOU?")), "ckb", "auto", base, retry))
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

    @Test fun `a verified continuous contour joins distant dialogue but a tail or a literal does not`() {
        val first = line("IF YOU CAN,", 58, 30, 1).copy(confidence = .95f)
        val second = line("PLEASE STAY.", 60, 105, 2).copy(confidence = .96f)
        val bounds = Box(20, 10, 212, 148)
        fun shape(narrow: Boolean) = ComicContour(bounds.top, 1, IntArray(bounds.height * 2) { i ->
            if (narrow && i / 2 in 62..66) if (i % 2 == 0) 111 else 122
            else if (i % 2 == 0) bounds.left else bounds.right
        }, 240)
        val bubble = ComicBubble(bounds, listOf(first.box, second.box), false, shape(false))
        val page = OcrPage(listOf(first, second), false, comic = true, bubbles = listOf(bubble))
        fun text(input: OcrPage, clean: Boolean = true) = groupForImage(input) { _, _ -> clean }.units.map { it.text }
        val separate = listOf(first.text, second.text)
        assertEquals(listOf("IF YOU CAN, PLEASE STAY."), text(page))
        assertEquals(separate, text(page.copy(bubbles = listOf(bubble.copy(contour = null)))))
        assertEquals(separate, text(page.copy(bubbles = listOf(bubble.copy(contour = shape(true))))))
        assertEquals(separate, text(page, clean = false))
        val label = line("ID42", 60, 76, 3)
        val protected = groupForImage(page.copy(lines = page.lines + label)) { _, _ -> true }
        assertEquals(separate, protected.units.map { it.text })
        assertEquals(listOf(label.box), protected.protected)
    }

    @Test fun `a spilled reading does not cover inner ink and can only be replaced by a corroborating crop`() {
        val band = Box(54, 43, 165, 63)
        val bounds = Box(20, 20, 200, 100)
        val contour = ComicContour(bounds.top, 1, IntArray(bounds.height * 2) { if (it % 2 == 0) bounds.left else bounds.right }, 246)
        val bubble = ComicBubble(bounds, listOf(band), false, contour)
        val dirty = OcrLine("WHO ARE YOU?! PY", Box(54, 43, 244, 63), emptyList(), 0, confidence = .98f)
        val clean = OcrLine("WHO ARE YOU?!", band, emptyList(), 1, confidence = .94f, bubbleRead = bubble)
        assertEquals(listOf(band), missedBubbleBands(bubble, listOf(dirty)))
        assertTrue(missedBubbleBands(bubble, listOf(clean)).isEmpty())
        fun reconcile(before: OcrLine, after: OcrLine) = reconcileComicOcr(listOf(before), listOf(after), explicit = true) { true }
        assertEquals(listOf(clean.copy(block = dirty.block)), reconcile(dirty, clean))
        assertEquals(listOf(dirty), reconcile(dirty, clean.copy(bubbleRead = null)))
        assertEquals(listOf(dirty), reconcile(dirty, clean.copy(text = "WHO ARE THEY?!")))
        val protected = dirty.copy(text = "WHO ARE YOU?! ID42")
        assertEquals(listOf(protected), reconcile(protected, clean))
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
