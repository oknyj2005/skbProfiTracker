package com.skbprofitracker.capture

/**
 * Deduplicates repeated action-bar essence announcements ("+100 Ice Essence").
 *
 * Hypixel keeps an essence gain on the action bar for ~3 seconds, and an unrelated ability
 * (such as AOTV Instant Transmission) can briefly overwrite the bar before the original essence
 * text comes back. Clearing on an unrelated bar would double-count the restored line, so entries
 * expire strictly by elapsed time instead.
 */
class EssenceDeduper(
    private val repeatWindowMs: Long = REPEAT_WINDOW_MS,
) {
    private data class Seen(val amount: Int, val atMs: Long)

    private val seen = mutableMapOf<String, Seen>()
    private var sawEssenceLastBar = false

    /**
     * Notes an action bar with no essence match without clearing deduplication state.
     * Returns true once on the transition away from an essence bar so callers can log it.
     */
    fun onEmptyBar(now: Long): Boolean {
        pruneExpired(now)
        val transitioned = sawEssenceLastBar
        sawEssenceLastBar = false
        return transitioned
    }

    /**
     * Returns true if [name] + [amount] should be credited as a new gain, refreshing the
     * deduplication timestamp in all cases.
     */
    fun accept(name: String, amount: Int, now: Long): Boolean {
        pruneExpired(now)
        sawEssenceLastBar = true
        val previous = seen[name]
        val repeated = previous != null &&
            previous.amount == amount &&
            now - previous.atMs <= repeatWindowMs
        seen[name] = Seen(amount, now)
        return !repeated
    }

    fun hasSeen(name: String): Boolean = name in seen

    fun clear() {
        seen.clear()
        sawEssenceLastBar = false
    }

    private fun pruneExpired(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.atMs > repeatWindowMs) it.remove()
        }
    }

    companion object {
        const val REPEAT_WINDOW_MS = 2_000L
    }
}
