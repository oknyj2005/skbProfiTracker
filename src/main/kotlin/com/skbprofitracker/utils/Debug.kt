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

    /**
     * Writes to the log file even when diagnostics are off.
     *
     * Used for the handful of lines that decide where an item came from: they are rare enough
     * to keep on, and they are the only way to tell afterwards which path credited an item.
     */
    fun trace(message: String) {
        SkbMod.LOGGER.info("[skbprofit] $message")
    }
}
