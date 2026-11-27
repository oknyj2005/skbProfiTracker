package com.skbprofitracker.capture

internal const val SACK_DELIVERY_SLOP_MS = 15_000L

/** Inventory decreases observed while manually depositing into a sack. */
internal class SackDeposits(private val scanDelayMs: Long) {
    private data class Deposit(var amount: Int, val atMs: Long)

    private val byItem = mutableMapOf<String, ArrayDeque<Deposit>>()

    fun clear() = byItem.clear()

    fun record(key: String, amount: Int, now: Long) {
        if (amount <= 0) return
        byItem.values.removeIf { deposits ->
            while (deposits.firstOrNull()?.let { now - it.atMs > TTL_MS } == true) deposits.removeFirst()
            deposits.isEmpty()
        }
        byItem.getOrPut(key) { ArrayDeque() }.addLast(Deposit(amount, now))
    }

    fun take(key: String, amount: Int, receivedAt: Long, windowMs: Long): Int {
        val deposits = byItem[key] ?: return 0
        val lookback = if (windowMs > 0) minOf(windowMs + SACK_DELIVERY_SLOP_MS, TTL_MS) else TTL_MS
        val end = receivedAt + scanDelayMs
        while (deposits.firstOrNull()?.let { it.atMs < receivedAt - lookback } == true) deposits.removeFirst()

        var remaining = amount
        while (remaining > 0) {
            val deposit = deposits.firstOrNull()?.takeIf { it.atMs <= end } ?: break
            val used = minOf(deposit.amount, remaining)
            remaining -= used
            deposit.amount -= used
            if (deposit.amount == 0) deposits.removeFirst()
        }
        if (deposits.isEmpty()) byItem.remove(key)
        return amount - remaining
    }

    private companion object {
        const val TTL_MS = 120_000L
    }
}
