package dev.rojink.browser.translation

import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** DB probability-map postprocessing for roughly horizontal lettering. Bounded and does not need OpenCV. */
internal fun paddleLineBoxes(map: FloatBuffer, width: Int, height: Int, imageWidth: Int, imageHeight: Int): List<Box> {
    val count = width * height
    require(width in 1..1024 && height in 1..1024 && map.remaining() == count)
    val scores = FloatArray(count).also { map.get(it) }
    val seen = BooleanArray(count)
    val queue = IntArray(count)
    val candidates = mutableListOf<Pair<Box, Float>>()
    for (start in scores.indices) {
        if (seen[start] || scores[start] <= .30f || !scores[start].isFinite()) continue
        var head = 0
        var end = 1
        queue[0] = start
        seen[start] = true
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        var sum = 0f
        while (head < end) {
            val pixel = queue[head++]
            val x = pixel % width
            val y = pixel / width
            left = minOf(left, x); right = maxOf(right, x + 1)
            top = minOf(top, y); bottom = maxOf(bottom, y + 1)
            sum += scores[pixel]
            // Eight-connected instead of four-connected: italic text can touch only at a corner.
            for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                    val next = ny * width + nx
                    if (!seen[next] && scores[next] > .30f && scores[next].isFinite()) {
                        seen[next] = true
                        queue[end++] = next
                    }
                }
            }
        }
        val w = right - left
        val h = bottom - top
        val score = sum / end
        if (w < 5 || h < 3 || w < h * .90f || h > height / 3 || end < maxOf(9, w * h / 5) || score < .53f) continue
        // Axis-aligned equivalent of DB unclip (area * ratio / perimeter).
        val pad = (1.5f * w * h / (2 * (w + h))).coerceIn(1f, h * 1.2f)
        val box = Box(
            floor((left - pad) * imageWidth / width).toInt(), floor((top - pad) * imageHeight / height).toInt(),
            ceil((right + pad) * imageWidth / width).toInt(), ceil((bottom + pad) * imageHeight / height).toInt(),
        ).clip(imageWidth, imageHeight)
        if (box.width >= 8 && box.height >= 6) candidates += box to score
    }
    val chosen = mutableListOf<Box>()
    for ((box, _) in candidates.sortedByDescending { it.second }) {
        if (chosen.size >= 130) break
        if (chosen.none { areaOverlap(it, box) > .78 }) chosen += box
    }
    return chosen.sortedWith(compareBy<Box> { it.top }.thenBy { it.left })
}

internal data class PaddleText(val value: String, val confidence: Float)

/** Greedy CTC: zero is blank, repeated indexes collapse, and the final class is a real space. */
internal fun paddleRead(scores: FloatBuffer, steps: Int, alphabet: List<String>): PaddleText {
    val classes = alphabet.size + 1
    require(steps in 1..512 && scores.remaining() == steps * classes)
    val start = scores.position()
    var prev = 0
    var sum = 0f
    var picked = 0
    val value = buildString {
        for (step in 0 until steps) {
            val row = start + step * classes
            var best = 0
            var confidence = scores.get(row)
            for (index in 1 until classes) {
                val probability = scores.get(row + index)
                if (probability > confidence) { best = index; confidence = probability }
            }
            if (best != 0 && best != prev && confidence.isFinite()) {
                append(alphabet[best - 1])
                sum += confidence.coerceIn(0f, 1f)
                picked++
            }
            prev = best
        }
    }
    return PaddleText(value.trim(), if (picked == 0) 0f else sum / picked)
}

/** Round the resized map dimensions to the detector's stride without growing huge/tall inputs. */
internal fun paddleDimensions(width: Int, height: Int): Pair<Int, Int> {
    val scale = min(960f / maxOf(width, height), maxOf(1f, 640f / minOf(width, height)))
    fun align(size: Int) = (((size * scale / 32).roundToInt()) * 32).coerceIn(32, 960)
    return align(width) to align(height)
}
