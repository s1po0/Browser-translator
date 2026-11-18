package dev.rojink.browser.translation

import org.junit.Assert.*
import org.junit.Test

class LongImageLayoutTest {
    @Test fun `long strip keeps source text pixels and fits the final raster in memory`() {
        val original = RasterSize(1440, 12288)
        assertEquals(4, imageSample(original))
        val plan = requireNotNull(planLongImage(original))
        assertEquals(original, plan.scan)
        assertEquals(1, plan.sample)
        assertTrue(plan.output.width > original.width / 4)
        assertTrue(plan.output.edge <= IMAGE_MAX_EDGE && plan.output.area <= IMAGE_MAX_PIXELS)
        assertEquals(0, plan.windows.first().coreTop)
        assertEquals(original.height, plan.windows.last().coreBottom)
        for ((a, b) in plan.windows.zipWithNext()) {
            assertEquals(a.coreBottom, b.coreTop)
            assertTrue(a.bottom - b.top >= 400)
        }
        assertTrue(plan.windows.all { it.height * plan.scan.width <= LONG_MAX_WINDOW_PIXELS && it.height <= 2080 })
    }

    @Test fun `normal photos retain their existing path and excessive strips have a second work budget`() {
        assertNull(planLongImage(RasterSize(1440, 2016)))
        assertNull(planLongImage(RasterSize(3000, 3300)))
        val huge = requireNotNull(planLongImage(RasterSize(2880, 72_100)))
        assertTrue(huge.sample > 1 && huge.scan.area <= 34_000_000)
        assertTrue(huge.windows.size <= 32 && huge.output.area <= IMAGE_MAX_PIXELS)
        assertEquals(huge.scan.height, huge.windows.last().coreBottom)
    }

    @Test fun `keeps content islands whole when wide enough gutters bracket them`() {
        val plan = requireNotNull(planLongImage(RasterSize(1440, 12288)))
        val starts = listOf(0, 3504, 7704)
        val ends = starts.map { it + 2016 }
        val gutters = ends.zip(starts.drop(1) + plan.scan.height).map { (from, to) -> from until to }
        val windows = plan.windowsAtGutters(gutters)
        for ((top, bottom) in starts.zip(ends)) {
            assertEquals(1, windows.count { it.top <= top && it.bottom >= bottom })
        }
        assertEquals(0, windows.first().coreTop)
        assertEquals(plan.scan.height, windows.last().coreBottom)
        assertTrue(windows.zipWithNext().all { (a, b) -> a.coreBottom == b.coreTop && a.bottom >= b.top })
        assertTrue(windows.all { it.height * plan.scan.width <= LONG_MAX_WINDOW_PIXELS && it.height <= 2080 })
        assertEquals(plan.windows, plan.windowsAtGutters(emptyList()))
    }

    @Test fun `overlap chooses the complete bubble and not duplicates of separate neighboring dialogue`() {
        val upper = Box(31, 1434, 115, 1454)
        val lower = Box(22, 1462, 126, 1483)
        val whole = TextUnit("I WILL WAIT HERE.", upper.union(lower), listOf(upper, lower), 20, false)
        val partial = TextUnit("WAIT HERE.", lower.expand(2, 1), listOf(lower.expand(2, 1)), 22, false)
        val separate = partial.movedY(46)
        val first = StripReading(whole, 0, 55, false, false)
        val duplicate = StripReading(partial, 1, 7, true, false)
        val neighbor = StripReading(separate, 1, 64, true, false)
        assertEquals(listOf(first, neighbor), resolveStripReadings(listOf(duplicate, first, neighbor)))
    }

    @Test fun `preserves right to left rows without dropping identical dialogue in another panel`() {
        val box = Box(150, 31, 200, 49)
        val line = TextUnit("WAIT.", box, listOf(box), 18, false)
        val left = line.copy(box = box.moved(-90, 0), lines = listOf(box.moved(-90, 0)))
        val right = StripReading(line, 0, 50, true, true)
        val other = StripReading(left, 0, 50, true, true)
        val later = StripReading(line.movedY(1600), 1, 50, true, true)
        assertEquals(listOf(right, other, later), resolveStripReadings(listOf(later, other, right)))
    }
}
