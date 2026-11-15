package com.skbprofitracker.events.publishers

import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.SupercraftGuard
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import com.skbprofitracker.utils.ChatUtils.removeFormatting
import com.skbprofitracker.utils.CommonUtils
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
    private val ITEM_LINE_REGEX = Regex("(\\+[\\d,]+) (.+) \\((.+)\\)")

    /** Hypixel may delay the chat line by up to 30s, so anything recent is suspect. */
    private const val MAX_SACKS_MESSAGE_DELAY_MS = 35_000L

    private val SUPERCRAFTED_PATTERN = Regex("^You Supercrafted .*")
    private const val STASH_TO_SACKS_MESSAGE =
        "You have successfully transferred your items from this stash to your sacks!"

    private var lastStashToSacksAtMs = 0L

    /** Inventory to sack moves that were observed directly, waiting to be netted off a chat line. */
    private data class Deposit(val amount: Int, val atMs: Long)

    private val pendingDeposits = mutableMapOf<String, Deposit>()
    private const val DEPOSIT_TTL_MS = 60_000L

    data class SackPickup(val itemName: String, val amount: Int, val sackName: String)

    fun init() {
        EventBus.subscribe(ChatEvent::class, ::onChat)
    }

    private fun onChat(event: ChatEvent) {
        if (!WorldUtils.isInSkyblock()) return

        CommonUtils.runWithCatching("Failed to handle chat message in sacks item pickup publisher") {
            val text = event.unformattedText
            when {
                SACKS_TRIGGER.matches(text) -> {
                    val items = parseItems(event.message)
                    if (items.isNotEmpty()) onSackItems(items)
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

    /** Removes and returns how many of [key] the player had already moved in by hand. */
    private fun takeManualDeposit(key: String): Int {
        val deposit = pendingDeposits.remove(key) ?: return 0
        val now = System.currentTimeMillis()
        return if (now - deposit.atMs <= DEPOSIT_TTL_MS) deposit.amount else 0
    }

    private fun onSackItems(items: List<SackPickup>) {
        val now = System.currentTimeMillis()
        // While the sack screen is open the moves on screen are still being observed; the
        // deltas reported now cannot be told apart from what is about to be scanned.
        if (GuiUtils.isInSacksGui()) return
        if (now - lastStashToSacksAtMs < MAX_SACKS_MESSAGE_DELAY_MS) return

        val activity = ActivityBucket.bucketForPickup() ?: return

        for (pickup in items) {
            if (pickup.amount <= 0) continue

            val entry = ItemTable.get(ItemUtils.getCleanItemName(pickup.itemName)) ?: continue
            if (SupercraftGuard.guardsSack(pickup.itemName)) continue

            // The delta covers everything that reached the sack since the last line, including
            // stacks the player carried in themselves; only the remainder is fresh loot.
            val fresh = pickup.amount - takeManualDeposit(entry.key)
            if (fresh <= 0) continue

            // Go through the ledger so the item lands in the running slice and so corpse or
            // nucleus loot that went straight into a sack is not counted a second time.
            BucketLedger.add(activity, entry.key, fresh.toLong())
            ActivityBucket.onWeakSignal()
        }
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
}
