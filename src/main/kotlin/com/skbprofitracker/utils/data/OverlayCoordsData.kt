package com.skbprofitracker.utils.data

import com.skbprofitracker.utils.enums.Alignment

data class OverlayCoordsData(
    val x: Int = 10,
    val y: Int = 10,
    val scale: Float = 1.0f,
    val alignment: Alignment = Alignment.LEFT
)