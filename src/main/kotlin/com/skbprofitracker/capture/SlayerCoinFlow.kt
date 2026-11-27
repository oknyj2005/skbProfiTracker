package com.skbprofitracker.capture

import java.math.BigDecimal

/** Assigns bank notices or clean wallet debits to observed Slayer starts, once per start. */
internal class SlayerCoinFlow {
    data class Charge(val coins: BigDecimal, val estimated: Boolean = false)
    data class Settlement(val slice: String, val charge: Charge, val startedAt: Long)

    private data class Start(val at: Long, val cycle: Long, var slice: String?)
    private data class Payment(val at: Long, val cycle: Long, val charge: Charge, val fromBank: Boolean = false)

    private val starts = ArrayDeque<Start>()
    private val payments = ArrayDeque<Payment>()
    private var previousStart: Long? = null
    private var cycle = 0L
    private var endSeen = false

    fun start(now: Long) {
        if (previousStart?.let { now - it < 250L } == true) return
        starts.addLast(Start(now, cycle, null))
        previousStart = now
        endSeen = false
    }

    fun questEnded(): Boolean {
        if (endSeen) return false
        endSeen = true
        previousStart = null
        cycle++
        return true
    }

    fun observeSlice(slice: String) {
        starts.lastOrNull()?.takeIf { it.cycle == cycle }?.slice = slice
    }

    fun walletDebit(coins: BigDecimal, now: Long) {
        payments.addLast(Payment(now, cycle, Charge(coins)))
    }

    fun bankCharge(charge: Charge, now: Long) {
        val previous = payments.lastOrNull { it.cycle == cycle && it.fromBank }
        if (previous != null && now - previous.at < 250L && previous.charge == charge) return
        payments.addLast(Payment(now, cycle, charge, fromBank = true))
    }

    fun discardRecentWalletDebits(now: Long) {
        payments.removeAll { !it.fromBank && now - it.at <= WINDOW_MS }
    }

    fun settle(now: Long, output: (Settlement) -> Unit) {
        while (starts.firstOrNull()?.let { now - it.at >= WINDOW_MS } == true) {
            val start = starts.removeFirst()
            val next = starts.firstOrNull()?.takeIf { it.cycle == start.cycle }
            val end = minOf(start.at + WINDOW_MS, next?.let { start.at + (it.at - start.at) / 2 } ?: Long.MAX_VALUE)
            val candidates = payments.filter { it.cycle == start.cycle && it.at in (start.at - WINDOW_MS)..end }
            val bank = candidates.filter { it.fromBank }
            val payment = if (bank.isNotEmpty()) bank.singleOrNull() else candidates.singleOrNull()
            val slice = start.slice
            if (slice != null && payment != null) output(Settlement(slice, payment.charge, start.at))
            payments.removeAll { it.cycle == start.cycle && it.at <= end }
        }
        val cutoff = (starts.firstOrNull()?.at ?: now) - WINDOW_MS
        payments.removeAll { it.at < cutoff }
    }

    fun clear() {
        starts.clear()
        payments.clear()
        previousStart = null
        cycle = 0L
        endSeen = false
    }

    companion object {
        const val WINDOW_MS = 2_500L
        private val PURSE = Regex("^(?:Piggy|Purse): ([0-9,]+(?:\\.[0-9]+)?)(?:\\s+\\([+-][0-9,.]+\\))?$")
        private val BANK = Regex("^Took ([0-9,]+(?:\\.[0-9]+)?)([kKmMbB]?) coins from your bank for auto-slayer\\.\\.\\.$")

        fun readPurse(line: String): BigDecimal? =
            PURSE.matchEntire(line.trim())?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()

        fun readBankCharge(line: String): Charge? {
            val match = BANK.matchEntire(line.trim()) ?: return null
            val coins = match.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return null
            if (coins.signum() <= 0) return null
            val scale = when (match.groupValues[2].lowercase()) {
                "k" -> 3
                "m" -> 6
                "b" -> 9
                else -> 0
            }
            return Charge(coins.movePointRight(scale), estimated = scale > 0)
        }
    }
}
