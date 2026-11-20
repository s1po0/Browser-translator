package dev.rojink.browser.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTextLinesTest {
    private class Raster(val w: Int, val h: Int) {
        val mean = IntArray(w * h) { 255 }
        val low = IntArray(w * h) { 255 }

        fun paint(left: Int, top: Int, right: Int, bottom: Int, tone: Int) {
            for (y in top until bottom) for (x in left until right) {
                mean[y * w + x] = tone
                low[y * w + x] = tone
            }
        }

        fun glyphs(firstTop: Int) {
            for (g in 0 until 8) {
                val x = 30 + g * 8
                paint(x, firstTop, x + 2, firstTop + 7, 30)
            }
        }
    }

    @Test fun `dark lettering on a flat light interior proposes one box per line`() {
        val raster = Raster(220, 300)
        for (line in 0 until 4) raster.glyphs(70 + line * 12)
        val boxes = inkLineBoxes(raster.mean, raster.low, raster.w, raster.h)
        assertEquals(boxes.toString(), 4, boxes.size)
        assertEquals(listOf(70, 82, 94, 106), boxes.map { it.top })
        assertTrue(boxes.all { it.height == 7 && it.left == 30 && it.right == 88 })
    }

    @Test fun `artwork masses and light lettering on a dark panel are not line proposals`() {
        val raster = Raster(220, 300)
        raster.paint(10, 20, 90, 60, 25) // A solid art mass, far above glyph size.
        raster.paint(10, 200, 210, 260, 18) // A black panel.
        for (g in 0 until 8) { // White lettering inside the panel.
            val x = 24 + g * 20
            raster.paint(x, 220, x + 9, 236, 255)
        }
        val boxes = inkLineBoxes(raster.mean, raster.low, raster.w, raster.h)
        assertTrue(boxes.toString(), boxes.isEmpty())
    }

    @Test fun `lettering scattered inside a tall light area still groups by row`() {
        val raster = Raster(220, 300)
        // Ragged line ends, like handwritten dialogue, plus a faint stray dot that is not a line.
        for (g in 0 until 5) raster.paint(40 + g * 9, 40, 42 + g * 9, 47, 45)
        for (g in 0 until 3) raster.paint(48 + g * 9, 52, 50 + g * 9, 59, 45)
        raster.paint(180, 250, 182, 252, 45)
        val boxes = inkLineBoxes(raster.mean, raster.low, raster.w, raster.h)
        assertEquals(boxes.toString(), 2, boxes.size)
        assertEquals(listOf(40, 52), boxes.map { it.top })
    }
}
