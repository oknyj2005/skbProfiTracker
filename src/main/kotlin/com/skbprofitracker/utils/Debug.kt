package com.skbprofitracker.utils

import com.skbprofitracker.SkbMod

/**
 * Toggles extra in-game diagnostics. Enabled with `/skbprofit debug`.
 */
object Debug {
    var enabled: Boolean = false

    /** Diagnostics go to the log file, not to chat: a loot block can be dozens of lines. */
    fun log(message: String) {
        if (enabled) SkbMod.LOGGER.info("[skbprofit] $message")
    }
}
