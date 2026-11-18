package com.skbprofitracker.tracker

import com.skbprofitracker.utils.Debug

/**
 * Remembers what the sack line just credited.
 *
 * Since 0.27.3 mined loot goes straight into a sack, but a full sack overflows into the
 * inventory. Both channels can therefore describe the same stack within a second of each
 * other, and the 200ms inventory scan would book it a second time. The shadow marks the key
 * for a short window so the scan steps aside; overflow that arrives later is still counted.
 */
object SackShadow {
    private const val WINDOW_MS = 3_000L

    private val creditedAt = mutableMapOf<String, Long>()

    fun mark(key: String, amount: Int) {
        if (key.isBlank() || amount <= 0) return
        creditedAt[key] = System.currentTimeMillis()
    }

    fun isRecent(key: String): Boolean {
        val at = creditedAt[key] ?: return false
        if (System.currentTimeMillis() - at > WINDOW_MS) {
            creditedAt.remove(key)
            return false
        }
        return true
    }

    fun clear() {
        creditedAt.clear()
    }
}
