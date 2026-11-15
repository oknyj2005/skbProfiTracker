package com.skbprofitracker.tracker

import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.utils.Debug

/**
 * Fences off items the player just bought for a few seconds.
 *
 * In the Hub a bought auction item lands in the inventory without ever sitting in a container
 * slot, so the diff cannot tell it apart from loot. Hypixel announces both halves of the trade,
 * and [ItemTable.getChatKeys] bridges the chat name to the inventory name (a pet is `Ghoul` in
 * chat and `[Lvl 1] Ghoul (LEGENDARY)` in the inventory), so the match is name based only.
 */
object PurchaseGuard {
    private const val WINDOW_MS = 10_000L

    private data class Guard(val keys: Set<String>, val atMs: Long)

    private val guards = ArrayDeque<Guard>()

    fun guard(rawName: String) {
        val keys = ItemTable.getChatKeys(rawName)
        if (keys.isEmpty()) {
            if (Debug.enabled) Debug.log("[purchase] '$rawName' is not in the item table, nothing to fence")
            return
        }

        guards.addLast(Guard(keys, System.currentTimeMillis()))
        trim()
        if (Debug.enabled) Debug.log("[purchase] fencing $keys")
    }

    /** True when [key] was bought within the window; the guard is consumed by the first hit. */
    fun shouldSkip(key: String): Boolean {
        trim()

        val match = guards.firstOrNull { key in it.keys } ?: return false
        guards.remove(match)
        if (Debug.enabled) Debug.log("[purchase] ignored $key")
        return true
    }

    private fun trim(now: Long = System.currentTimeMillis()) {
        while (guards.isNotEmpty() && now - guards.first().atMs > WINDOW_MS) guards.removeFirst()
    }
}
