package com.skbprofitracker.events.publishers

import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.tracker.ActivityBucket

/**
 * `  LAPIS CORPSE LOOT!` followed by indented reward lines and a `▬▬▬` footer.
 *
 * The header carries the corpse type, which is also the only way to tell a free lapis corpse
 * from a keyed one, so the whole slice is booked from chat instead of the inventory.
 */
object CorpseLootPublisher {
    private val block = ChatLootBlock(
        label = "corpse",
        slice = ActivityBucket.MINING_CORPSE,
        startPattern = Regex("\\s+(LAPIS|TUNGSTEN|UMBER|VANGUARD) CORPSE LOOT!.*"),
        // Ice Cold I is the only book a corpse drops, and the server sometimes prints it without
        // the enchantment name, so the bare "Enchanted Book" line fills in the only candidate.
        mapName = { if (it == "Enchanted Book") "Enchanted Book (Ice Cold I)" else it },
    )

    fun init() {
        EventBus.subscribe(ChatEvent::class) { block.onChat(it.unformattedText) }
    }
}
