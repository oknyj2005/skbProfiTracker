package com.skbprofitracker.capture

/** Matches right clicks to server-confirmed stack decreases in the hand that used the item. */
class MiningConsumableUses(private val confirmationMs: Long = 5_000L) {
    private data class PendingUse(
        val key: String,
        var count: Int,
        val attempts: ArrayDeque<Long> = ArrayDeque(),
    )

    private val pending = mutableMapOf<Int, PendingUse>()

    fun attempt(slot: Int, key: String, count: Int, now: Long) {
        val use = activeUse(slot, now)?.takeIf { it.key == key }
            ?: PendingUse(key, count).also { pending[slot] = it }
        if (use.attempts.size < use.count) use.attempts.addLast(now)
    }

    fun serverUpdate(slot: Int, key: String?, count: Int, now: Long): Pair<String, Int>? {
        val use = activeUse(slot, now) ?: return null
        val used = use.count - count
        if ((count > 0 && key != use.key) || used < 0 || used > use.attempts.size) {
            pending.remove(slot)
            return null
        }
        if (used == 0) return null

        use.count = count
        repeat(used) { use.attempts.removeFirst() }
        if (use.attempts.isEmpty()) pending.remove(slot)
        return use.key to used
    }

    fun clear() = pending.clear()

    fun hasPendingUse(slot: Int): Boolean = slot in pending

    fun slots(): Set<Int> = pending.keys.toSet()

    private fun activeUse(slot: Int, now: Long): PendingUse? {
        val use = pending[slot] ?: return null
        while (use.attempts.firstOrNull()?.let { now - it >= confirmationMs } == true) {
            use.attempts.removeFirst()
        }
        if (use.attempts.isNotEmpty()) return use
        pending.remove(slot)
        return null
    }
}
