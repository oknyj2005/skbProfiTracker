package com.skbprofitracker.features.overlays.base

enum class TrackerViewMode {
    SESSION,
    TOTAL;

    fun next(): TrackerViewMode = if (this == SESSION) TOTAL else SESSION
}
