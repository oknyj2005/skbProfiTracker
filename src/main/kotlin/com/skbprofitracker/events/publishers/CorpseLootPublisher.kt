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
 * cost and the corpse counter are taken from the same header: relying on the inventory diff
 * missed a key every now and then, because the key disappears while the corpse UI is opening.
 */
object CorpseLootPublisher {
    private val CORPSE_TYPE = Regex("(LAPIS|TUNGSTEN|UMBER|VANGUARD) CORPSE LOOT!")

    /** The Resourceful perk can save the key; the chat says so right around the loot block. */
    private val KEY_SAVED = Regex("saved your key from being consumed", RegexOption.IGNORE_CASE)

    private var lastKeySavedMs = 0L

    private val block = ChatLootBlock(
        label = "corpse",
        slice = ActivityBucket.MINING_CORPSE,
        startPattern = Regex("\\s+(LAPIS|TUNGSTEN|UMBER|VANGUARD) CORPSE LOOT!.*"),
        onStart = { header ->
            val type = CORPSE_TYPE.find(header)?.groupValues?.get(1).orEmpty()
            val keyConsumed = System.currentTimeMillis() - lastKeySavedMs > KEY_SAVED_WINDOW_MS
            BucketLedger.addCorpseOpened()
            CostLedger.onCorpseOpened(type, keyConsumed)
        },
        // Ice Cold I is the only book a corpse drops, and the server sometimes prints it without
        // the enchantment name, so the bare "Enchanted Book" line fills in the only candidate.
        mapName = { if (it == "Enchanted Book") "Enchanted Book (Ice Cold I)" else it },
    )

    fun init() {
        EventBus.subscribe(ChatEvent::class) { event ->
            val text = event.unformattedText
            if (KEY_SAVED.containsMatchIn(text)) lastKeySavedMs = System.currentTimeMillis()
            block.onChat(text)
        }
    }

    /** How far around the block start a "key saved" line still applies. */
    private const val KEY_SAVED_WINDOW_MS = 5_000L
}
