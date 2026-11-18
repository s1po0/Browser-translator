package dev.rojink.browser.translation

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal const val IMAGE_MAX_PIXELS = 4_100_000L
internal const val IMAGE_MAX_EDGE = 8200
private const val LONG_MAX_SCAN_PIXELS = 34_000_000L
private const val LONG_MAX_SCAN_HEIGHT = 30_000
private const val LONG_MAX_SCAN_WIDTH = 2048
internal const val LONG_MAX_WINDOW_PIXELS = 3_200_000
private const val WINDOW_EDGE = 2080
private const val WINDOW_CONTEXT = 224
internal const val LONG_MAX_UNITS = 320

internal data class RasterSize(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
    val edge: Int get() = maxOf(width, height)
    fun atSample(sample: Int) = RasterSize(ceilDivide(width, sample), ceilDivide(height, sample))
}

private fun ceilDivide(value: Int, divisor: Int) = ((value.toLong() + divisor - 1) / divisor).toInt()

internal fun imageSample(size: RasterSize): Int {
    require(size.width > 0 && size.height > 0)
    var sample = 1
    while (size.atSample(sample).let { it.area > IMAGE_MAX_PIXELS || it.edge > IMAGE_MAX_EDGE }) sample *= 2
    return sample
}

internal data class StripWindow(val coreTop: Int, val coreBottom: Int, val top: Int, val bottom: Int) {
    val height get() = bottom - top
    fun owns(y: Int) = y in coreTop until coreBottom
    fun clearance(box: Box, imageHeight: Int): Int = minOf(
        if (top == 0) Int.MAX_VALUE else box.top - top,
        if (bottom == imageHeight) Int.MAX_VALUE else bottom - box.bottom,
    )
}

internal data class LongImagePlan(
    val source: RasterSize, val sample: Int, val scan: RasterSize, val output: RasterSize, val windows: List<StripWindow>,
)

/** Keep the artwork canvas bounded but use a higher-resolution coordinate space for finding and fitting dialogue. */
internal fun planLongImage(size: RasterSize): LongImagePlan? {
    if (size.width < 80 || size.height.toLong() * 5 < size.width.toLong() * 8) return null
    val previewSample = imageSample(size)
    if (previewSample == 1) return null
    var sample = 1
    var scan = size
    while (scan.width > LONG_MAX_SCAN_WIDTH || scan.height > LONG_MAX_SCAN_HEIGHT || scan.area > LONG_MAX_SCAN_PIXELS) {
        sample *= 2
        if (sample >= previewSample) return null
        scan = size.atSample(sample)
    }
    val ratio = minOf(sqrt(IMAGE_MAX_PIXELS.toDouble() / size.area), IMAGE_MAX_EDGE.toDouble() / size.edge)
    val width = floor(size.width * ratio).toInt()
    if (width < 80) return null
    // Derive height from the rounded width so narrow strips don't stretch or collapse in the WebView.
    val output = RasterSize(width, (size.height.toLong() * width / size.width).toInt())
    val windowHeight = minOf(WINDOW_EDGE, LONG_MAX_WINDOW_PIXELS / scan.width)
    val count = ceilDivide(scan.height, windowHeight - 2 * WINDOW_CONTEXT)
    val windows = List(count) { n ->
        val from = scan.height * n / count
        val to = scan.height * (n + 1) / count
        StripWindow(from, to, maxOf(0, from - WINDOW_CONTEXT), minOf(scan.height, to + WINDOW_CONTEXT))
    }
    return LongImagePlan(size, sample, scan, output, windows)
}

internal fun TextUnit.movedY(dy: Int) = copy(box = box.moved(0, dy), lines = lines.map { it.moved(0, dy) })

internal data class StripReading(
    val unit: TextUnit, val window: Int, val clearance: Int, val inCore: Boolean, val rightFirst: Boolean,
)

/** Adjacent reading windows see some of the same ink. Keep the complete unit from the better window. */
internal fun resolveStripReadings(readings: List<StripReading>): List<StripReading> {
    fun sameLine(a: Box, b: Box, vertical: Boolean): Boolean {
        val thickness = if (vertical) minOf(a.width, b.width) else minOf(a.height, b.height)
        val delta = if (vertical) abs(a.x - b.x) else abs(a.y - b.y)
        return delta <= maxOf(3f, thickness * .65f) && areaOverlap(a, b) >= .64
    }
    val preferred = readings.sortedWith(compareByDescending<StripReading> { it.clearance >= 5 }
        .thenByDescending { it.unit.lines.size }
        .thenByDescending { it.unit.text.count(Char::isLetterOrDigit) }
        .thenByDescending { it.inCore }
        .thenByDescending { it.clearance })
    val accepted = mutableListOf<StripReading>()
    for (next in preferred) {
        if (accepted.none { old -> old.window != next.window && old.unit.vertical == next.unit.vertical &&
                old.unit.box.overlaps(next.unit.box) && old.unit.lines.any { a -> next.unit.lines.any { b -> sameLine(a, b, old.unit.vertical) } }
            }) accepted += next
    }
    val row = (accepted.map { it.unit.sizeHint }.sorted().let { it.getOrNull(it.size / 2) ?: 16 } * 2).coerceIn(24, 80)
    return accepted.sortedWith(compareBy<StripReading> { it.unit.box.top / row }
        .thenBy { if (it.rightFirst) -it.unit.box.right else it.unit.box.left }.thenBy { it.unit.box.top })
}
