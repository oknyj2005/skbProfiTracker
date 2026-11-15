package com.skbprofitracker.events.publishers

import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.CostLedger

/**
 * `  LAPIS CORPSE LOOT!` followed by indented reward lines and a `▬▬▬` footer.
 *
 * The header carries the corpse type, which is also the only way to tell a free lapis corpse
 * from a keyed one, so the whole slice is booked from chat instead of the inventory. The key
 * cost is booked when the block closes: the Resourceful perk prints
 * `LUCKY! ... saved your key from being consumed` right around the header, and its exact
 * position relative to the header is not worth relying on.
 */
object CorpseLootPublisher {
    private val CORPSE_TYPE = Regex("(LAPIS|TUNGSTEN|UMBER|VANGUARD) CORPSE LOOT!")

    /** The Resourceful perk can save the key; the chat says so right around the loot block. */
    private val KEY_SAVED = Regex("saved your key from being consumed", RegexOption.IGNORE_CASE)

    private var lastKeySavedMs = 0L

    /** Type of the corpse currently being read, charged when its block closes. */
    private var pendingType: String? = null
    private var pendingStartMs = 0L

    private val block = ChatLootBlock(
        label = "corpse",
        slice = ActivityBucket.MINING_CORPSE,
        startPattern = Regex("\\s+(LAPIS|TUNGSTEN|UMBER|VANGUARD) CORPSE LOOT!.*"),
        onStart = { header ->
            pendingType = CORPSE_TYPE.find(header)?.groupValues?.get(1).orEmpty()
            pendingStartMs = System.currentTimeMillis()
            BucketLedger.addCorpseOpened()
        },
        onClose = {
            val type = pendingType
            pendingType = null
            if (!type.isNullOrEmpty()) {
                val keyConsumed = lastKeySavedMs < pendingStartMs - KEY_SAVED_WINDOW_MS
                CostLedger.onCorpseOpened(type, keyConsumed)
            }
        },
        // Ice Cold I is the only book a corpse drops, and the server sometimes prints it without
        // the enchantment name, so the bare "Enchanted Book" line fills in the only candidate.
        mapName = { if (it == "Enchanted Book") "Enchanted Book (Ice Cold I)" else it },
        // Glacite Powder is a currency, not an item: no bazaar listing, no NPC price, so it can
        // never be sold and is skipped without the "unknown loot" warning.
        skipName = { it.contains(" Powder") },
    )

    fun init() {
        EventBus.subscribe(ChatEvent::class) { event ->
            val text = event.unformattedText
            if (KEY_SAVED.containsMatchIn(text)) lastKeySavedMs = System.currentTimeMillis()
            block.onChat(text)
        }
    }

    /** How far before the block start a "key saved" line still applies. */
    private const val KEY_SAVED_WINDOW_MS = 5_000L
}
