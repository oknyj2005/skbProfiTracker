package com.skbprofitracker.utils.gui

/**
 * Overlay chrome defaults.
 *
 * These used to be user settings; until the config screen exists they are
 * compile-time constants, which also keeps the overlay renderer free of any
 * config lookups on the render path.
 */
object OverlayStyle {
    const val BUTTONS_REQUIRE_CTRL_CLICK = false

    const val BACKGROUND = false
    const val BACKGROUND_COLOR_TOP = 0x46000000
    const val BACKGROUND_COLOR_BOTTOM = 0x78000000

    const val BORDER = false
    const val BORDER_COLOR = 0xFFFFFFFF.toInt()
    const val BORDER_WIDTH = 1
}
