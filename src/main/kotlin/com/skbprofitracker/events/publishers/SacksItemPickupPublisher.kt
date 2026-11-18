package com.skbprofitracker.events.publishers

import com.skbprofitracker.capture.ScanRules
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.Activity
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.SackShadow
import com.skbprofitracker.tracker.SupercraftGuard
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import com.skbprofitracker.utils.ChatUtils.removeFormatting
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.GuiUtils
import com.skbprofitracker.utils.ItemUtils
import com.skbprofitracker.utils.WorldUtils
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * Credits items that went straight into a sack.
 *
 * Hypixel batches sack changes into a single `[Sacks] +N ...` line whose hover lists the
 * per item deltas, and that line may arrive up to ~35 seconds after the drop. Anything
 * taken out of a sack is reported the same way but as a negative delta, and those are
 * ignored: the sack is a storage container, not a source of loot.
 *
 * The tricky part is the player moving existing stacks into a sack by hand while a drop is
 * still in flight: the server reports `+1 mined + 50 moved` as a single `+51`, and crediting
 * that would invent 50 items. The inventory scanner therefore reports those manual moves
 * here as [noteManualDeposit], and they are subtracted from the chat delta before it counts.
 */
object SacksItemPickupPublisher {
    private val SACKS_TRIGGER = Regex("^\\[Sacks\\] \\+.*")
    private val SACKS_WITHDRAW_TRIGGER = Regex("^\\[Sacks\\] -.*")
    private val ITEM_LINE_REGEX = Regex("(\\+[\\d,]+) (.+) \\((.+)\\)")
    private val REMOVED_LINE_REGEX = Regex("(-[\\d,]+) (.+) \\((.+)\\)")

    /** `(Last 18s.)` tells us which stretch of time the batch covers. */
    private val LAST_WINDOW = Regex("\\(Last (\\d+)s\\.\\)")

    /** Hypixel may delay the chat line by up to 30s, so anything recent is suspect. */
    private const val MAX_SACKS_MESSAGE_DELAY_MS = 35_000L

    private val SUPERCRAFTED_PATTERN = Regex("^You Supercrafted .*")
    private const val STASH_TO_SACKS_MESSAGE =
        "You have successfully transferred your items from this stash to your sacks!"

    private var lastStashToSacksAtMs = 0L

    /** Inventory to sack moves that were observed directly, waiting to be netted off a chat line. */
    private data class Deposit(val amount: Int, val atMs: Long)

    private val pendingDeposits = mutableMapOf<String, Deposit>()
    private const val DEPOSIT_TTL_MS = 120_000L

    data class SackPickup(val itemName: String, val amount: Int, val sackName: String)

    fun init() {
        EventBus.subscribe(ChatEvent::class, ::onChat)
        EventBus.subscribe(ClientTickEvent::class) { flushDeferredAdds() }
    }

    private data class DeferredAdd(
        val items: List<SackPickup>,
        val withdrawals: List<SackPickup>,
        val windowMs: Long,
        val dueAtMs: Long,
    )

    /** Sack lines can reach the client before the scanner saw the matching inventory change. */
    private val deferredAdds = mutableListOf<DeferredAdd>()
    private const val SACK_DEFER_MS = 600L

    private fun flushDeferredAdds() {
        if (deferredAdds.isEmpty()) return
        val now = System.currentTimeMillis()
        val due = deferredAdds.filter { it.dueAtMs <= now }
        if (due.isEmpty()) return
        deferredAdds.removeAll(due)
        due.forEach { onSackItems(it.items, it.withdrawals, it.windowMs) }
    }

    private fun onChat(event: ChatEvent) {
        if (!WorldUtils.isInSkyblock()) return

        CommonUtils.runWithCatching("Failed to handle chat message in sacks item pickup publisher") {
            val text = event.unformattedText
            when {
                SACKS_TRIGGER.matches(text) || SACKS_WITHDRAW_TRIGGER.matches(text) -> {
                    // A single line can carry both halves: the hover lists `Added items:` and
                    // `Removed items:` separately. Withdrawals are handled first so they cancel
                    // whatever the inventory scan already booked for the same stack.
                    val items = if (SACKS_TRIGGER.matches(text)) parseItems(event.message) else emptyList()
                    val withdrawals = parseWithdrawals(event.message)

                    if (items.isNotEmpty() || withdrawals.isNotEmpty()) {
                        // Give the 250ms inventory scan a moment to record the matching
                        // decrease as a deposit, otherwise a quick "put it back" is credited
                        // as fresh loot before the deposit exists. Both halves travel together
                        // so a compaction line can cancel its own raw materials.
                        val windowMs = LAST_WINDOW.find(text)
                            ?.groupValues?.get(1)?.toLongOrNull()?.times(1_000L) ?: 0L
                        deferredAdds.add(
                            DeferredAdd(items, withdrawals, windowMs, System.currentTimeMillis() + SACK_DEFER_MS)
                        )
                    }
                }

                SUPERCRAFTED_PATTERN.matches(text) -> SupercraftGuard.onChat(text)

                text == STASH_TO_SACKS_MESSAGE -> lastStashToSacksAtMs = System.currentTimeMillis()
            }
        }
    }

    /** Records a stack the player moved into a sack by hand, so it is not read as loot later. */
    fun noteManualDeposit(key: String, amount: Int) {
        if (key.isBlank() || amount <= 0) return

        val now = System.currentTimeMillis()
        val previous = pendingDeposits[key]
        val carried = if (previous != null && now - previous.atMs <= DEPOSIT_TTL_MS) previous.amount else 0
        pendingDeposits[key] = Deposit(carried + amount, now)
    }

    /**
     * Consumes up to [requested] of the amount the player already moved in by hand.
     *
     * Only the part the current line actually needs is spent. Taking the whole record would
     * throw away the rest whenever a small line (a few mined items) lands between a big
     * deposit and the line that reports it, and the big line would then be credited in full.
     */
    private fun takeManualDeposit(key: String, requested: Int): Int {
        val deposit = pendingDeposits[key] ?: return 0
        val now = System.currentTimeMillis()
        if (now - deposit.atMs > DEPOSIT_TTL_MS) {
            pendingDeposits.remove(key)
            return 0
        }

        val (_, left) = ScanRules.netAgainstDeposit(requested, deposit.amount)
        val used = requested - left
        if (left <= 0) pendingDeposits.remove(key) else pendingDeposits[key] = Deposit(left, now)
        return used
    }

    private fun onSackItems(items: List<SackPickup>, withdrawals: List<SackPickup>, windowMs: Long) {
        val now = System.currentTimeMillis()
        // Even with the sack screen open this line still carries real loot mined earlier, so it
        // is not dropped wholesale: the moves made on screen are netted by [takeManualDeposit].
        if (now - lastStashToSacksAtMs < MAX_SACKS_MESSAGE_DELAY_MS) return

        // Attribute the batch to the bucket that was running when its window opened: a line
        // that arrives after flying to another island still belongs to the mining session.
        val activity = ActivityBucket.bucketAt(now - windowMs) ?: ActivityBucket.bucketForPickup() ?: return

        val removed = withdrawals
            .mapNotNull { pickup ->
                val entry = ItemTable.get(ItemUtils.getCleanItemName(pickup.itemName)) ?: return@mapNotNull null
                entry.key to pickup.amount
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }
            .toMutableMap()

        for (pickup in items) {
            if (pickup.amount <= 0) continue

            val entry = ItemTable.get(ItemUtils.getCleanItemName(pickup.itemName)) ?: continue
            if (SupercraftGuard.guardsSack(pickup.itemName)) continue

            // Stacks that were pulled out earlier and put back now are not loot at all.
            val returned = InventoryItemPickupPublisher.takeReturn(entry.key, pickup.amount)
            if (returned > 0) Debug.trace("[sacks] return -$returned ${entry.key}")
            val net = pickup.amount - returned
            if (net <= 0) continue

            // The delta also covers everything that reached the sack since the last line,
            // including stacks the player carried in themselves; only the remainder is fresh loot.
            val fresh = net - takeManualDeposit(entry.key, net)
            if (fresh <= 0) continue

            // Go through the ledger so the item lands in the running slice and so corpse or
            // nucleus loot that went straight into a sack is not counted a second time.
            Debug.trace("[sacks] credit +$fresh ${entry.key} -> ${activity.name} (delta ${pickup.amount}, window ${windowMs}ms)")
            BucketLedger.add(activity, entry.key, fresh.toLong())
            SackShadow.mark(entry.key, fresh)
            ActivityBucket.onWeakSignal()

            consumeCompaction(activity, entry, fresh.toLong(), removed)
        }

        for ((key, amount) in removed) {
            if (amount > 0) InventoryItemPickupPublisher.noteWithdrawal(key, amount)
        }
    }

    /**
     * `+19 Fine Peridot` next to `-1,520 Flawed Peridot` is the compactor at work: the raw
     * units were already credited when they reached the sack, so the removal cancels them
     * instead of the product being counted on top of the materials it replaced.
     */
    private fun consumeCompaction(
        activity: Activity,
        entry: ItemTable.Entry,
        credited: Long,
        removed: MutableMap<String, Int>,
    ) {
        val parentName = entry.compactsFrom ?: return
        val ratio = entry.compactRatio
        if (ratio <= 0) return

        val parentKey = ItemTable.get(ItemUtils.getCleanItemName(parentName))?.key ?: return
        val available = removed[parentKey] ?: return
        val needed = minOf(available.toLong(), ratio * credited).toInt()
        if (needed <= 0) return

        removed[parentKey] = available - needed
        BucketLedger.subtract(activity, parentKey, needed.toLong())
        Debug.decision("[sacks] compaction -$needed $parentKey for +$credited ${entry.key}")
    }

    /**
     * Reads the hover of the `[Sacks]` line, which looks like:
     * `Added items:` followed by lines of `+1,344 Pufferfish (Fishing Sack)`.
     */
    private fun parseItems(message: Component): List<SackPickup> {
        val items = mutableListOf<SackPickup>()

        for (part in message.siblings) {
            if (!part.getUnformattedString().contains(" item")) continue

            val hover = part.style?.hoverEvent
            if (hover !is HoverEvent.ShowText) continue

            val text = hover.value.getUnformattedString()
            if (!text.contains("Added items:")) continue

            ITEM_LINE_REGEX.findAll(text).forEach { match ->
                val amount = match.groupValues[1].replace("+", "").replace(",", "").toIntOrNull() ?: 0
                val itemName = match.groupValues[2].trim().removeFormatting()
                val sackName = match.groupValues[3].removeFormatting()

                if (amount > 0 && itemName.isNotBlank()) {
                    items.add(SackPickup(itemName, amount, sackName))
                }
            }
        }

        return items
    }

    /** Same hover, but the `Removed items:` section, used for sack withdrawals. */
    private fun parseWithdrawals(message: Component): List<SackPickup> {
        val items = mutableListOf<SackPickup>()

        for (part in message.siblings) {
            if (!part.getUnformattedString().contains(" item")) continue

            val hover = part.style?.hoverEvent
            if (hover !is HoverEvent.ShowText) continue

            val text = hover.value.getUnformattedString()
            if (!text.contains("Removed items:")) continue

            REMOVED_LINE_REGEX.findAll(text).forEach { match ->
                val amount = match.groupValues[1].replace("-", "").replace(",", "").toIntOrNull() ?: 0
                val itemName = match.groupValues[2].trim().removeFormatting()
                val sackName = match.groupValues[3].removeFormatting()

                if (amount > 0 && itemName.isNotBlank()) {
                    items.add(SackPickup(itemName, amount, sackName))
                }
            }
        }

        return items
    }
}
