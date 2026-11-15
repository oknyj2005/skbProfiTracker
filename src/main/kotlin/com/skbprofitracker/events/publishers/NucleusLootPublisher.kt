package com.skbprofitracker.events.publishers

import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.tracker.ActivityBucket

/**
 * `  CRYSTAL NUCLEUS LOOT BUNDLE` followed by indented reward lines and a `▬▬▬` footer.
 *
 * Powder lines are printed in the same block but are not items, so they are skipped here.
 */
object NucleusLootPublisher {
    private val block = ChatLootBlock(
        label = "nucleus",
        slice = ActivityBucket.MINING_NUCLEUS,
        startPattern = Regex("\\s+CRYSTAL NUCLEUS LOOT BUNDLE.*"),
        skipName = { it.contains(" Powder") },
    )

    fun init() {
        EventBus.subscribe(ChatEvent::class) { block.onChat(it.unformattedText) }
    }
}
