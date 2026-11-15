package com.skbprofitracker.events.publishers

import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
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
 */
object SacksItemPickupPublisher {
    private val SACKS_TRIGGER = Regex("^\\[Sacks\\] \\+.*")
    private val ITEM_LINE_REGEX = Regex("(\\+[\\d,]+) (.+) \\((.+)\\)")

    /** Hypixel may delay the chat line by up to 30s, so anything recent is suspect. */
    private const val MAX_SACKS_MESSAGE_DELAY_MS = 35_000L

    private val SUPERCRAFTED_PATTERN = Regex("^You Supercrafted .*")
    private const val STASH_TO_SACKS_MESSAGE =
        "You have successfully transferred your items from this stash to your sacks!"

    private var lastSupercraftedMessage: String? = null
    private var lastSupercraftedAtMs = 0L
    private var lastStashToSacksAtMs = 0L

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

                SUPERCRAFTED_PATTERN.matches(text) -> {
                    lastSupercraftedMessage = text
                    lastSupercraftedAtMs = System.currentTimeMillis()
                }

                text == STASH_TO_SACKS_MESSAGE -> lastStashToSacksAtMs = System.currentTimeMillis()
            }
        }
    }

    private fun onSackItems(items: List<SackPickup>) {
        val now = System.currentTimeMillis()
        val lastSacksGuiClosedAt = GuiUtils.lastGuisClosed.lastSacksGuiClosedAt

        // Withdrawing from the sack GUI produces the same message; that is not loot.
        if (GuiUtils.isInSacksGui()) return
        if (lastSacksGuiClosedAt != null && now - lastSacksGuiClosedAt.time < MAX_SACKS_MESSAGE_DELAY_MS) return
        if (now - lastStashToSacksAtMs < MAX_SACKS_MESSAGE_DELAY_MS) return

        val activity = ActivityBucket.bucketForPickup() ?: return

        for (pickup in items) {
            if (pickup.amount <= 0) continue

            val entry = ItemTable.get(ItemUtils.getCleanItemName(pickup.itemName)) ?: continue
            if (wasSupercrafted(pickup.itemName, now)) continue

            // Go through the ledger so the item lands in the running slice and so corpse or
            // nucleus loot that went straight into a sack is not counted a second time.
            BucketLedger.add(activity, entry.key, pickup.amount.toLong())
            ActivityBucket.onWeakSignal()
        }
    }

    // Supercrafted items end up in the sack too, and their ingredients were already
    // credited when they were collected.
    private fun wasSupercrafted(itemName: String, now: Long): Boolean {
        val message = lastSupercraftedMessage ?: return false
        if (now - lastSupercraftedAtMs >= MAX_SACKS_MESSAGE_DELAY_MS) return false
        return message.contains(itemName, ignoreCase = true)
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
