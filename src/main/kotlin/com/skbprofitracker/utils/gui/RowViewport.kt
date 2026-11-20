package com.skbprofitracker.utils.gui

/** Tracks a window of whole rows, including fractional scroll input from touchpads. */
internal class RowViewport {
    var offset = 0
        private set
    var visibleCount = 0
        private set
    private var maxOffset = 0
    private var remainder = 0.0

    val canScroll: Boolean
        get() = maxOffset > 0

    fun resize(rowCount: Int, limit: Int) {
        visibleCount = minOf(rowCount, limit)
        maxOffset = rowCount - visibleCount
        val clamped = offset.coerceAtMost(maxOffset)
        if (clamped != offset || !canScroll) remainder = 0.0
        offset = clamped
    }

    fun reset() {
        offset = 0
        remainder = 0.0
    }

    fun scroll(amount: Double): Boolean {
        if (!amount.isFinite()) return false
        val movement = (remainder - amount).coerceIn(-offset.toDouble(), (maxOffset - offset).toDouble())
        val steps = movement.toInt()
        offset += steps
        remainder = movement - steps
        return steps != 0
    }
}
