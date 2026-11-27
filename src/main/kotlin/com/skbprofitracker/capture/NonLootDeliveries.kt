package com.skbprofitracker.capture

/** A completed delivery, as announced by the server. Order setup and fill notices are not deliveries. */
internal data class PurchaseDelivery(val name: String, val amount: Int, val canEnterSack: Boolean) {
    companion object {
        private val bazaar = Regex("""^\[Bazaar] (?:Bought ([\d,]+)x (.+?) for [\d,.]+ coins!|Claimed ([\d,]+)x (.+?) worth [\d,.]+ coins bought for [\d,.]+ each!)$""")
        private val auction = Regex("""^You (?:purchased (.+?) for [\d,.]+ coins!|claimed (.+?) from .+'s auction!)$""")
        private val stack = Regex("""^([\d,]+)x (.+)$""")
        private val npc = Regex("""^You bought (?:([\d,]+)x )?(.+?) for [\d,.]+ coins!$""")

        fun parse(text: String): PurchaseDelivery? {
            val message = text.trim()
            bazaar.matchEntire(message)?.let { match ->
                val (bought, boughtName, claimed, claimedName) = match.destructured
                return delivery(bought.ifEmpty { claimed }, boughtName.ifEmpty { claimedName }, true)
            }
            auction.matchEntire(message)?.let { match ->
                val (purchased, claimed) = match.destructured
                val name = purchased.ifEmpty { claimed }
                val sized = stack.matchEntire(name) ?: return PurchaseDelivery(name, 1, false)
                return delivery(sized.groupValues[1], sized.groupValues[2], false)
            }
            npc.matchEntire(message)?.let { match ->
                return delivery(match.groupValues[1].ifEmpty { "1" }, match.groupValues[2], false)
            }
            return null
        }

        private fun delivery(quantity: String, name: String, canEnterSack: Boolean): PurchaseDelivery? {
            val count = quantity.replace(",", "").toIntOrNull()?.takeIf { it > 0 } ?: return null
            return PurchaseDelivery(name.trim(), count, canEnterSack)
        }
    }
}

/** Shared quantities for rewards and purchases that don't belong to the active tracker. */
internal class NonLootDeliveries {
    private data class Receipt(
        val keys: Set<String>,
        var amount: Int,
        val at: Long,
        val canEnterSack: Boolean,
        val sackOnly: Boolean = false,
    )
    private val receipts = ArrayDeque<Receipt>()
    private val inventoryStock = mutableMapOf<String, Int>()
    private val sackStock = mutableMapOf<String, Int>()

    fun record(keys: Set<String>, amount: Int, at: Long, canEnterSack: Boolean) {
        if (keys.isEmpty() || amount <= 0) return
        prune(at)
        receipts.addLast(Receipt(keys, amount, at, canEnterSack))
    }

    /** Match a possible transfer to the sack before adding the units to its tracked stock. */
    fun recordSackTransfer(key: String, amount: Int, at: Long) {
        if (amount <= 0) return
        prune(at)
        receipts.addLast(Receipt(setOf(key), amount, at, canEnterSack = true, sackOnly = true))
    }

    fun takeInventory(key: String, amount: Int, observedAt: Long): Int {
        val excluded = take(key, amount, observedAt - INVENTORY_LAG_MS, observedAt + INVENTORY_WAIT_MS, sack = false)
        if (excluded > 0) inventoryStock.merge(key, excluded, Int::plus)
        return excluded
    }

    fun takeSack(key: String, amount: Int, observedAt: Long, batchWindowMs: Long): Int {
        // The advertised window can be shorter than the notification delay. A 5s batch
        // containing 834 purchased items reached the client 7s after the claim.
        val lookback = (batchWindowMs + SACK_DELIVERY_SLOP_MS).coerceIn(SACK_DELIVERY_SLOP_MS, SACK_DELAY_MS)
        val excluded = take(key, amount, observedAt - lookback, observedAt + INVENTORY_WAIT_MS, sack = true)
        if (excluded > 0) sackStock.merge(key, excluded, Int::plus)
        return excluded
    }

    private fun take(key: String, amount: Int, oldest: Long, newest: Long, sack: Boolean): Int {
        if (amount <= 0 || receipts.isEmpty()) return 0
        var outstanding = amount
        val iterator = receipts.iterator()
        while (iterator.hasNext() && outstanding > 0) {
            val receipt = iterator.next()
            if (receipt.at !in oldest..newest || key !in receipt.keys) continue
            if (if (sack) !receipt.canEnterSack else receipt.sackOnly) continue
            val used = minOf(outstanding, receipt.amount)
            outstanding -= used
            receipt.amount -= used
            if (receipt.amount == 0) iterator.remove()
        }
        return amount - outstanding
    }

    fun prune(now: Long) {
        while (receipts.firstOrNull()?.let { now - it.at > SACK_DELAY_MS + INVENTORY_WAIT_MS * 2 } == true) {
            receipts.removeFirst()
        }
    }

    fun removeInventory(key: String, quantity: Int, transferredToSack: Boolean = false): Int {
        val removed = removeStock(inventoryStock, key, quantity)
        if (transferredToSack && removed > 0) sackStock.merge(key, removed, Int::plus)
        return removed
    }

    fun removeSack(key: String, quantity: Int) {
        removeStock(sackStock, key, quantity)
    }

    internal data class ExcludedCompaction(val products: Int, val inputs: Int)

    /**
     * An observed removal paired with its output can explain excluded input. If the last output
     * combines excluded and mined units, leave that product out and preserve the mined raw
     * credit. There is no fractional item row or reliable ordering inside a signed sack batch.
     */
    fun excludedCompaction(key: String, product: String, removed: Int, products: Int, ratio: Int): ExcludedCompaction {
        if (ratio <= 0 || products <= 0) return ExcludedCompaction(0, 0)
        val pairedInputs = minOf(removed / ratio, products) * ratio
        val excluded = removeStock(sackStock, key, pairedInputs)
        if (excluded <= 0) return ExcludedCompaction(0, 0)
        val suppressed = 1 + (excluded - 1) / ratio
        sackStock.merge(product, suppressed, Int::plus)
        return ExcludedCompaction(suppressed, suppressed * ratio)
    }

    private fun removeStock(stock: MutableMap<String, Int>, key: String, quantity: Int): Int {
        val existing = stock[key] ?: return 0
        val removed = minOf(existing, quantity.coerceAtLeast(0))
        val left = existing - removed
        if (left == 0) stock.remove(key) else stock[key] = left
        return removed
    }

    fun clear() {
        receipts.clear()
        inventoryStock.clear()
        sackStock.clear()
    }

    companion object {
        const val INVENTORY_WAIT_MS = 600L
        private const val INVENTORY_LAG_MS = 2_000L
        private const val SACK_DELAY_MS = 35_000L
    }
}
