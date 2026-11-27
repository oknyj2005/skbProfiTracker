package com.skbprofitracker.events.publishers

import com.skbprofitracker.capture.SackDeposits
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.Activity
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.SupercraftGuard
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import com.skbprofitracker.utils.ChatUtils.removeFormatting
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.GuiUtils
import com.skbprofitracker.utils.ItemUtils
import com.skbprofitracker.utils.WorldUtils
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
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

    data class SackPickup(val itemName: String, val amount: Int, val sackName: String)

    fun init() {
        EventBus.subscribe(ChatEvent::class, ::onChat)
        EventBus.subscribe(ClientTickEvent::class) { flushDeferredAdds() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            deferredAdds.clear()
            pendingDeposits.clear()
            lastStashToSacksAtMs = 0L
        }
    }

    private data class DeferredAdd(
        val items: List<SackPickup>,
        val withdrawals: List<SackPickup>,
        val windowMs: Long,
        val receivedAtMs: Long,
        val wasTracking: Boolean,
    )

    /** Sack lines can reach the client before the scanner saw the matching inventory change. */
    private val deferredAdds = mutableListOf<DeferredAdd>()
    private const val SACK_DEFER_MS = 600L
    private val pendingDeposits = SackDeposits(SACK_DEFER_MS)

    private fun flushDeferredAdds() {
        if (deferredAdds.isEmpty()) return
        val now = System.currentTimeMillis()
        val due = deferredAdds.filter { now - it.receivedAtMs >= SACK_DEFER_MS }
        if (due.isEmpty()) return
        deferredAdds.removeAll(due)
        due.forEach(::onSackItems)
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
                    val items = parseItems(event.message)
                    val withdrawals = parseWithdrawals(event.message)

                    if (items.isNotEmpty() || withdrawals.isNotEmpty()) {
                        // Give the 250ms inventory scan a moment to record the matching
                        // decrease as a deposit, otherwise a quick "put it back" is credited
                        // as fresh loot before the deposit exists. Both halves travel together
                        // so a compaction line can cancel its own raw materials.
                        val windowMs = LAST_WINDOW.find(text)
                            ?.groupValues?.get(1)?.toLongOrNull()?.times(1_000L) ?: 0L
                        deferredAdds.add(
                            DeferredAdd(items, withdrawals, windowMs, System.currentTimeMillis(), ActivityBucket.isPickupAllowed())
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
        // Once a return is observed as a manual deposit, the deposit itself accounts for those units.
        InventoryItemPickupPublisher.takeReturn(key, amount)
        pendingDeposits.record(key, amount, System.currentTimeMillis())
    }

    private fun onSackItems(batch: DeferredAdd) {
        val now = System.currentTimeMillis()
        InventoryItemPickupPublisher.flushInventoryGains(now)
        // Even with the sack screen open this line still carries real loot mined earlier, so it
        // is not dropped wholesale: the observed moves are netted against the batch.
        if (now - lastStashToSacksAtMs < MAX_SACKS_MESSAGE_DELAY_MS) return

        // The message's window is relative to receipt, even if processing waits for an inventory scan.
        val target = ActivityBucket.pickupTargetAt(batch.receivedAtMs - batch.windowMs)
        val nonLootDeliveries = InventoryItemPickupPublisher.nonLootDeliveries

        val removed = batch.withdrawals
            .mapNotNull { pickup ->
                val entry = ItemTable.get(ItemUtils.getCleanItemName(pickup.itemName)) ?: return@mapNotNull null
                entry.key to pickup.amount
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }
            .toMutableMap()

        val gains = batch.items.mapNotNull { pickup ->
            if (pickup.amount <= 0 || SupercraftGuard.guardsSack(pickup.itemName)) return@mapNotNull null
            val entry = ItemTable.get(ItemUtils.getCleanItemName(pickup.itemName)) ?: return@mapNotNull null
            entry to unexplainedGain(entry.key, pickup.amount, batch)
        }

        val bucket = target?.takeIf { batch.wasTracking && ActivityBucket.isPickupAllowed() }
            ?.let { BucketLedger.storage(it.activity, it.slice) }
        val poolPrefix = if (target != null && bucket != null) {
            "${target.activity.bucketKey}/${target.slice.orEmpty()}/${bucket.sessionRevision}/"
        } else null
        var compactionHappened = false
        fun creditGain(entry: ItemTable.Entry, amount: Int) {
            if (amount <= 0) return
            var fresh = amount
            val parent = entry.compactsFrom?.let { ItemTable.get(ItemUtils.getCleanItemName(it)) }
            if (parent != null) {
                var rawRemoved = removed[parent.key] ?: 0
                val excluded = nonLootDeliveries.excludedCompaction(parent.key, entry.key, rawRemoved, fresh, entry.compactRatio)
                if (excluded.inputs > 0) {
                    rawRemoved -= excluded.inputs
                    removed[parent.key] = rawRemoved
                    fresh -= excluded.products
                    compactionHappened = true
                    Debug.decision("[non-loot] compaction ${entry.key} -${excluded.products} (excluded input)")
                }

                if (poolPrefix != null) {
                    val conversion = InventoryItemPickupPublisher.reconcileSackCompaction(
                        poolPrefix + parent.key, fresh, entry.compactRatio, rawRemoved,
                    )
                    if (conversion.directRemoval > 0) {
                        removed[parent.key] = rawRemoved - conversion.directRemoval
                        nonLootDeliveries.removeSack(parent.key, conversion.directRemoval)
                        compactionHappened = true
                    }
                    if (conversion.remainingProducts != fresh) {
                        Debug.decision(
                            "[sacks] compaction ${fresh - conversion.remainingProducts} ${entry.key} " +
                                "from -${conversion.directRemoval} ${parent.key}, ${conversion.pooledRemoval} pooled, " +
                                "+${conversion.newlyAddedInputs} new input",
                        )
                    }
                    fresh = conversion.remainingProducts
                    if (conversion.newlyAddedInputs > 0) {
                        creditGain(parent, unexplainedGain(parent.key, conversion.newlyAddedInputs, batch))
                    }
                }
            }

            if (fresh <= 0 || target == null || bucket == null) return

            // Go through the ledger so the item lands in the running slice and so corpse or
            // nucleus loot that went straight into a sack is not counted a second time.
            val credit = BucketLedger.add(target.activity, entry.key, fresh.toLong(), target.slice, batch.receivedAtMs)
            if (credit == null) return
            Debug.trace("[sacks] credit +${credit.amount} ${entry.key} -> ${target.slice ?: target.activity.name} (delta $amount, window ${batch.windowMs}ms)")
            ActivityBucket.onWeakSignal(target.activity, batch.receivedAtMs - batch.windowMs)
        }
        for ((entry, amount) in gains) creditGain(entry, amount)

        for ((key, amount) in removed) {
            if (amount <= 0) continue
            nonLootDeliveries.removeSack(key, amount)
            if (bucket == null || poolPrefix == null) continue
            // A leftover removal is either a stack the player pulled out, or the raw half of a
            // compaction whose product has not been reported yet. Neither may be counted again.
            if (compactionHappened && ItemTable.isCompactionParent(key)) {
                InventoryItemPickupPublisher.noteCompactionInput(poolPrefix + key, amount)
            } else {
                InventoryItemPickupPublisher.noteWithdrawal(key, amount, bucket)
            }
        }
    }

    private fun unexplainedGain(key: String, amount: Int, batch: DeferredAdd): Int {
        val deposited = pendingDeposits.take(key, amount, batch.receivedAtMs, batch.windowMs)
        val returned = InventoryItemPickupPublisher.takeReturn(key, amount - deposited)
        if (returned > 0) Debug.trace("[sacks] return -$returned $key")
        val unexplained = amount - deposited - returned
        val excluded = InventoryItemPickupPublisher.nonLootDeliveries.takeSack(
            key, unexplained, batch.receivedAtMs, batch.windowMs,
        )
        if (excluded > 0) Debug.decision("[non-loot] sack $key -$excluded")
        return unexplained - excluded
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
