package dev.localphone.core

/** Content direction; the finger moves the other way, inside the observed visible container. */
object ScrollGesture {
    data class Path(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    fun path(bounds: Bounds, width: Int, height: Int, dir: ScrollDir): Path? {
        val left = bounds.left.coerceAtLeast(0).toFloat()
        val top = bounds.top.coerceAtLeast(0).toFloat()
        val right = bounds.right.coerceAtMost(width).toFloat()
        val bottom = bounds.bottom.coerceAtMost(height).toFloat()
        if (right - left < 20 || bottom - top < 20) return null
        val x = (left + right) / 2; val y = (top + bottom) / 2
        val lowX = left + (right - left) * .25f; val highX = left + (right - left) * .75f
        val lowY = top + (bottom - top) * .25f; val highY = top + (bottom - top) * .75f
        return when (dir) {
            ScrollDir.DOWN -> Path(x, highY, x, lowY)
            ScrollDir.UP -> Path(x, lowY, x, highY)
            ScrollDir.RIGHT -> Path(highX, y, lowX, y)
            ScrollDir.LEFT -> Path(lowX, y, highX, y)
        }
    }
}
