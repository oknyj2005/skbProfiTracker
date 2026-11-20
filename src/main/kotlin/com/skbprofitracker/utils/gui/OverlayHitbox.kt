package com.skbprofitracker.utils.gui

import com.skbprofitracker.utils.enums.Alignment

internal fun alignedRowLeft(alignment: Alignment, left: Int, width: Int, rowWidth: Int): Int =
    left + when (alignment) {
        Alignment.LEFT -> 0
        Alignment.CENTER -> (width - rowWidth) / 2
        Alignment.RIGHT -> width - rowWidth
    }

internal class OverlayHitbox(
    val left: Int,
    val top: Int,
    val width: Int,
    private val textHeight: Int,
    private val scale: Float,
    val alignment: Alignment,
) {
    val rowHeight = textHeight + 2

    fun rowAt(mouseX: Double, mouseY: Double, rows: Int): Int? {
        val y = mouseY / scale - top
        if (!containsX(mouseX, left, width) || !y.isFinite() || y < 0.0) return null
        return (y / rowHeight).toInt().takeIf { it < rows }
    }

    fun isOverText(mouseY: Double, row: Int): Boolean {
        val y = mouseY / scale - top - row * rowHeight
        return y >= 0.0 && y < textHeight
    }

    fun containsX(mouseX: Double, start: Int, contentWidth: Int): Boolean {
        val x = mouseX / scale
        return x >= start && x < start + contentWidth
    }
}
