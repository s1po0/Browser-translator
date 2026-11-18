package dev.rojink.browser.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import dev.rojink.browser.R
import kotlin.math.max
import kotlin.math.roundToInt

private val rtlTargets = setOf("ckb", "ar", "fa", "ps", "ur", "iw", "he", "yi", "sd", "ug", "dv", "ks", "bal", "fa-AF", "pa-Arab", "ms-Arab", "bm-Nkoo")
private val naskhTargets = setOf("ckb", "ar", "fa", "ps", "ur", "sd", "ug", "ks", "bal", "fa-AF", "pa-Arab", "ms-Arab")

internal enum class PaintDecision { UNCHANGED, PROTECTED, COLLISION, NO_ROOM, DRAWN }

/** Rewrite only OCR boxes, leaving numbers, surrounding illustration, and the page layout in place. */
internal class ImageRenderer(private val context: Context) {
    private val naskh by lazy { ResourcesCompat.getFont(context, R.font.noto_naskh_arabic) ?: Typeface.DEFAULT }

    fun paint(
        bitmap: Bitmap, input: List<TextUnit>, translated: List<String>, protected: List<Box>, target: String,
        comic: Boolean = false, bubbles: List<ComicBubble> = emptyList(), onDecision: ((Box, PaintDecision) -> Unit)? = null,
    ): Int {
        val canvas = Canvas(bitmap)
        val surface = if (comic) ComicSurface(bitmap, bubbles) else null
        val units = if (surface == null) input else input.map { unit ->
            if (unit.vertical) unit else {
                val ink = unit.lines.map(surface::inkBounds)
                unit.copy(lines = ink, box = ink.reduce(Box::union))
            }
        }
        val drawnAreas = mutableListOf<Box>()
        var count = 0
        units.zip(translated).forEachIndexed { index, (unit, value) ->
            fun report(decision: PaintDecision) { onDecision?.invoke(unit.box, decision) }
            if (value.isBlank() || value.trim() == unit.text.trim()) { report(PaintDecision.UNCHANGED); return@forEachIndexed }
            val original = unit.box.clip(bitmap.width, bitmap.height)
            if (original.width < 5 || original.height < 5) { report(PaintDecision.NO_ROOM); return@forEachIndexed }
            if (protected.any { it.overlaps(original) }) { report(PaintDecision.PROTECTED); return@forEachIndexed }
            val avoid = protected + drawnAreas + units.mapIndexedNotNull { i, other -> other.box.takeIf { i != index } }
            if (unit.lines.any { line -> avoid.any { it.overlaps(line) } }) { report(PaintDecision.COLLISION); return@forEachIndexed }
            val erase = unit.lines.map { line ->
                val padded = line.expand(2, 2).clip(bitmap.width, bitmap.height)
                if (avoid.any { padded.overlaps(it) }) line.clip(bitmap.width, bitmap.height) else padded
            }
            val margin = (unit.sizeHint * .29f).roundToInt().coerceIn(2, 18)
            val roomy = original.expand(if (unit.vertical) margin * 2 else margin, margin).clip(bitmap.width, bitmap.height)
            val drawBox = if (surface != null && !unit.vertical) surface.fitRoom(original, erase, unit.sizeHint, avoid) ?: original
                else if (avoid.any { roomy.overlaps(it) && !original.overlaps(it) }) original else roomy
            val rtl = target in rtlTargets
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = if (target in naskhTargets) naskh else Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val layout = fitted(value.trim(), paint, drawBox, unit.sizeHint, rtl)
            if (layout == null) { report(PaintDecision.NO_ROOM); return@forEachIndexed }
            val tones = erase.map { it to borderTone(bitmap, it) }
            val (background, noisy) = borderTone(bitmap, original)
            for ((box, tone) in tones) {
                canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), Paint().apply { color = tone.first })
            }
            canvas.save()
            canvas.clipRect(drawBox.left, drawBox.top, drawBox.right, drawBox.bottom)
            canvas.translate(drawBox.left.toFloat(), drawBox.top + (drawBox.height - layout.height) / 2f)
            val isBright = Color.luminance(background) > .35f
            val ink = if (isBright) 0xFF181A19.toInt() else 0xFFFFFCF5.toInt()
            if (noisy) {
                paint.color = if (isBright) Color.WHITE else Color.BLACK
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = (paint.textSize * .095f).coerceAtLeast(1f)
                layout.draw(canvas)
            }
            paint.color = ink; paint.style = Paint.Style.FILL
            layout.draw(canvas)
            canvas.restore()
            drawnAreas += drawBox
            count++
            report(PaintDecision.DRAWN)
        }
        return count
    }

    private fun fitted(text: String, paint: TextPaint, area: Box, sizeHint: Int, rtl: Boolean): StaticLayout? {
        var size = (sizeHint * if (rtl) 1.17f else 1.04f).coerceIn(8f, 116f)
        repeat(20) {
            paint.textSize = size
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, area.width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setTextDirection(if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            if (layout.height <= area.height && (0 until layout.lineCount).all { layout.getLineWidth(it) <= area.width + .9f }) return layout
            if (size <= 7.5f) return null
            size = max(7.5f, size * .89f)
        }
        return null
    }

    private fun borderTone(bitmap: Bitmap, box: Box): Pair<Int, Boolean> {
        val pixels = ArrayList<Int>(92)
        fun add(x: Int, y: Int) { pixels += bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)) }
        val xStep = (box.width / 23).coerceAtLeast(1)
        val yStep = (box.height / 14).coerceAtLeast(1)
        for (x in box.left until box.right step xStep) { add(x, box.top - 3); add(x, box.bottom + 2) }
        for (y in box.top until box.bottom step yStep) { add(box.left - 3, y); add(box.right + 2, y) }
        if (pixels.isEmpty()) return Color.WHITE to false
        fun median(channel: (Int) -> Int): Int = pixels.map(channel).sorted()[pixels.size / 2]
        val color = Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
        val diff = pixels.count { absChannel(it, color) > 96 }
        return color to (diff > pixels.size / 5)
    }

    private fun absChannel(a: Int, b: Int) = kotlin.math.abs(Color.red(a) - Color.red(b)) +
        kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
}
