package com.example.pddpricemonitor.capture

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

class FrameDiffer(
    private val sampleStep: Int = 24,
    private val colorThreshold: Int = 32,
    private val minChangedSamples: Int = 8
) {
    private var previous: Bitmap? = null

    fun changedRegion(current: Bitmap): Rect? {
        val old = previous
        previous = current.copy(Bitmap.Config.ARGB_8888, false)
        if (old == null || old.width != current.width || old.height != current.height) {
            return Rect(0, 0, current.width, current.height)
        }

        var left = current.width
        var top = current.height
        var right = 0
        var bottom = 0
        var changed = 0

        var y = 0
        while (y < current.height) {
            var x = 0
            while (x < current.width) {
                if (isDifferent(old.getPixel(x, y), current.getPixel(x, y))) {
                    changed++
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
                x += sampleStep
            }
            y += sampleStep
        }

        old.recycle()

        if (changed < minChangedSamples) return null

        val padding = sampleStep * 2
        return Rect(
            (left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0),
            (right + padding).coerceAtMost(current.width),
            (bottom + padding).coerceAtMost(current.height)
        )
    }

    private fun isDifferent(left: Int, right: Int): Boolean {
        val red = abs((left shr 16 and 0xff) - (right shr 16 and 0xff))
        val green = abs((left shr 8 and 0xff) - (right shr 8 and 0xff))
        val blue = abs((left and 0xff) - (right and 0xff))
        return red + green + blue > colorThreshold
    }
}
