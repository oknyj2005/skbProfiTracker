package com.skbprofitracker.capture

/**
 * In-flight bookkeeping for the inventory scanner.
 *
 * Two kinds of uncertainty live here:
 *  - **holds**: an increase seen while a menu is open may be a menu handing a stack back, so it is
 *    parked until either a shrinking container explains it or [pendingIncreaseMs] passes.
 *  - **removals**: a decrease may cancel a later increase of the same item (drop and pick up, or
 *    compactor turning 160 ore into one block), so it is remembered for [removalCreditMs].
 *
 * The clock is passed in on every call so the expiry rules can be replayed with fake time.
 */
class CaptureBuffer(
    private val pendingIncreaseMs: Long = 1_000L,
    private val removalCreditMs: Long = 10_000L,
    private val returnTtlMs: Long = 600_000L,
    private val compactionPoolTtlMs: Long = 600_000L,
) {
    private class Bucket(var amount: Int, var atMs: Long)

    private val holds = HashMap<String, Bucket>()
    private val removals = HashMap<String, Bucket>()
    private val skipped = HashMap<String, Int>()

    /** Stacks the player pulled out of a sack. They usually go back in a minute later, and that
     *  `[Sacks] +N` delta must not count as fresh loot. */
    private val returns = HashMap<String, Bucket>()

    /** Raw units the compactor ate in an earlier `[Sacks]` line. The product may only be
     *  reported in a later line, and by then the material was already credited. */
    private val compactionInputs = HashMap<String, Bucket>()

    val holdCount: Int get() = holds.size
    val removalCount: Int get() = removals.size
    val compactionInputCount: Int get() = compactionInputs.size

    fun clear() {
        holds.clear()
        removals.clear()
        skipped.clear()
        returns.clear()
        compactionInputs.clear()
    }

    // ---- compaction pool -------------------------------------------------------------

    /** Remembers raw units that a compaction removed but whose product was not seen yet. */
    fun noteCompactionInput(key: String, amount: Int, now: Long) {
        if (amount <= 0) return
        purgeCompactionInputs(now)
        val existing = compactionInputs[key]
        if (existing == null) compactionInputs[key] = Bucket(amount, now) else existing.amount += amount
    }

    /** Keeps unmatched remainders in the pool until they can cover a whole product. */
    fun takeCompactionInput(
        key: String,
        requested: Int,
        now: Long,
        unitsPerProduct: Int = 1,
        alreadyMatched: Int = 0,
    ): Int {
        purgeCompactionInputs(now)
        val existing = compactionInputs[key] ?: return 0
        val offered = minOf(existing.amount, requested)
        val remainder = (offered.toLong() + alreadyMatched) % unitsPerProduct
        val used = (offered - remainder).coerceAtLeast(0L).toInt()
        existing.amount -= used
        if (existing.amount <= 0) compactionInputs.remove(key)
        return used
    }

    private fun purgeCompactionInputs(now: Long) {
        if (compactionInputs.isEmpty()) return
        compactionInputs.entries.removeIf { now - it.value.atMs > compactionPoolTtlMs }
    }

    // ---- holds ---------------------------------------------------------------------------

    fun hold(key: String, amount: Int, now: Long) {
        val existing = holds[key]
        if (existing == null) {
            holds[key] = Bucket(amount, now)
        } else {
            existing.amount += amount
            existing.atMs = now
        }
    }

    /** A container that lost [amount] of [key] explains that much of the held increase. */
    fun explain(key: String, amount: Int) {
        val existing = holds[key] ?: return
        existing.amount -= amount
        if (existing.amount <= 0) holds.remove(key)
    }

    /** A withdrawal cancels a held pickup for the same key; returns how much was cancelled. */
    fun cancelHold(key: String, amount: Int): Int {
        val existing = holds[key] ?: return 0
        val cancelled = minOf(existing.amount, amount)
        existing.amount -= cancelled
        if (existing.amount <= 0) holds.remove(key)
        return cancelled
    }

    /** Removes and returns every hold that outlived [pendingIncreaseMs]. */
    fun dueHolds(now: Long): List<Pair<String, Int>> {
        if (holds.isEmpty()) return emptyList()
        val due = holds.entries.filter { now - it.value.atMs >= pendingIncreaseMs }
        return due.map { entry ->
            holds.remove(entry.key)
            entry.key to entry.value.amount
        }
    }

    // ---- removals ------------------------------------------------------------------------

    fun addRemoval(key: String, amount: Int, now: Long) {
        val existing = removals[key]
        if (existing == null || now - existing.atMs > removalCreditMs) {
            removals[key] = Bucket(amount, now)
        } else {
            existing.amount += amount
            existing.atMs = now
        }
    }

    private fun takeRemoval(key: String, upTo: Int, now: Long): Int {
        val existing = removals[key] ?: return 0
        if (now - existing.atMs > removalCreditMs) {
            removals.remove(key)
            return 0
        }
        val consumed = minOf(existing.amount, upTo)
        existing.amount -= consumed
        if (existing.amount <= 0) removals.remove(key)
        return consumed
    }

    private fun takeRemovalIfEnough(key: String, needed: Long, now: Long): Boolean {
        val existing = removals[key] ?: return false
        if (now - existing.atMs > removalCreditMs) {
            removals.remove(key)
            return false
        }
        if (existing.amount < needed) return false
        existing.amount -= needed.toInt()
        if (existing.amount <= 0) removals.remove(key)
        return true
    }

    /**
     * How much of [amount] is explained by earlier removals. [compaction] maps a compacted item to
     * its parent and ratio (`Enchanted Mithril -> Mithril to 160`) so a whole block can be
     * explained by the stack that was consumed to make it.
     */
    fun takeCredit(
        key: String,
        amount: Int,
        now: Long,
        compaction: (String) -> Pair<String, Int>? = { null },
    ): Int {
        val remaining = amount - takeRemoval(key, amount, now)
        if (remaining <= 0) return 0

        val (parentKey, ratio) = compaction(key) ?: return remaining
        if (ratio <= 0) return remaining
        val needed = ratio.toLong() * remaining
        return if (takeRemovalIfEnough(parentKey, needed, now)) 0 else remaining
    }

    // ---- withdrawals ignored while the sacks screen was open -------------------------------

    fun noteSkipped(key: String, amount: Int) {
        skipped[key] = (skipped[key] ?: 0) + amount
    }

    /** Remembers that [amount] of [key] left a sack and may come back. */
    fun noteReturn(key: String, amount: Int, now: Long) {
        val existing = returns[key]
        if (existing == null || now - existing.atMs > returnTtlMs) {
            returns[key] = Bucket(amount, now)
        } else {
            existing.amount += amount
            existing.atMs = now
        }
    }

    /** How much of [requested] was already taken out of a sack earlier. */
    fun takeReturn(key: String, requested: Int, now: Long): Int {
        val existing = returns[key] ?: return 0
        if (now - existing.atMs > returnTtlMs) {
            returns.remove(key)
            return 0
        }
        val used = minOf(existing.amount, requested)
        if (existing.amount == used) returns.remove(key) else existing.amount -= used
        return used
    }

    /** Consumes the earlier `[Sacks] -` bookkeeping; returns how much of [amount] it explains. */
    fun takeSkipped(key: String, amount: Int): Int {
        val existing = skipped[key] ?: return 0
        val consumed = minOf(existing, amount)
        if (existing == consumed) skipped.remove(key) else skipped[key] = existing - consumed
        return consumed
    }
}
