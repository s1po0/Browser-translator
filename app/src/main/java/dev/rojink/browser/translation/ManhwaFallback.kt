package dev.rojink.browser.translation

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlin.math.max

/**
 * The desktop Kurd-man app translates English manhwa with nothing but ML Kit: the page is cut into overlapping
 * tiles, every usable line it returns is kept, duplicates from the overlap are dropped, and stacked lines are
 * joined into one utterance. That path is plain and hard to break, so it is the safety net for every balloon
 * this app's tuned pipeline leaves in English.
 */
internal suspend fun plainManhwaUnits(ocr: OnDeviceImageOcr, bitmap: Bitmap, hint: OcrHint): List<TextUnit> {
    if (bitmap.width < 40 || bitmap.height < 40) return emptyList()
    val tileHeight = if (bitmap.height <= 1600) bitmap.height else 1400
    val overlap = if (bitmap.height <= tileHeight) 0 else 220
    val step = (tileHeight - overlap).coerceAtLeast(1)
    val lines = mutableListOf<OcrLine>()
    var y = 0
    while (y < bitmap.height) {
        val end = minOf(bitmap.height, y + tileHeight)
        val slice = try { Bitmap.createBitmap(bitmap, 0, y, bitmap.width, end - y) }
        catch (_: IllegalArgumentException) { null } ?: break
        val page = try { ocr.plainLines(slice, hint) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
        catch (_: LinkageError) { emptyList() }
        finally { slice.recycle() }
        val coreTop = if (y == 0) 0 else y + overlap / 2
        val coreBottom = if (end == bitmap.height) bitmap.height else end - overlap / 2
        for (line in page) {
            val text = line.text.replace('\u00A0', ' ').replace(whitespaceRun, " ").trim()
            if (!usablePlainText(text)) continue
            val box = line.box.moved(0, y).clip(bitmap.width, bitmap.height)
            if (box.width < 6 || box.height < 5) continue
            val centre = (box.top + box.bottom) / 2
            if (centre < coreTop || centre >= coreBottom) continue
            lines += line.copy(text = text, box = box)
        }
        if (end >= bitmap.height) break
        y += step
    }
    if (lines.isEmpty()) return emptyList()
    // The same balloon read twice through the tile overlap is one balloon, not two utterances.
    val kept = mutableListOf<OcrLine>()
    for (line in lines) if (kept.none { areaOverlap(it.box, line.box) >= .55 }) kept += line
    return plainBlocks(kept)
}

private val whitespaceRun = Regex("[\\t ]+")

private fun usablePlainText(text: String): Boolean {
    if (text.length !in 2..300) return false
    val letters = text.count(Char::isLetter)
    if (letters < 2) return false
    val other = text.count { !it.isLetterOrDigit() && !it.isWhitespace() }
    val digits = text.count(Char::isDigit)
    return letters * 10 >= (letters + digits + other) * 4
}

/** Stacked lines that share horizontal space are one utterance; anything else stands alone. */
private fun plainBlocks(lines: List<OcrLine>): List<TextUnit> {
    val ordered = lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    val blocks = mutableListOf<MutableList<OcrLine>>()
    for (line in ordered) {
        val block = blocks.lastOrNull()
        val last = block?.maxByOrNull { it.box.bottom }
        val joins = block != null && last != null &&
            line.box.top <= last.box.bottom + max(4, last.box.height) &&
            line.box.left < last.box.right && last.box.left < line.box.right
        if (joins) block!! += line else blocks += mutableListOf(line)
    }
    return blocks.mapNotNull { block ->
        val boxes = block.map { it.box }
        val text = block.sortedWith(compareBy({ it.box.top }, { it.box.left })).joinToString(" ") { it.text.trim() }
        if (text.isBlank()) return@mapNotNull null
        val heights = boxes.map { it.height }.sorted()
        TextUnit(text, boxes.reduce(Box::union), boxes, heights[heights.size / 2], vertical = false)
    }
}
