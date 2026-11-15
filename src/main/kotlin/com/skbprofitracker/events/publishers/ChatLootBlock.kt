package com.skbprofitracker.events.publishers

import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.Activity
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.LootLog
import com.skbprofitracker.utils.ChatUtils
import com.skbprofitracker.utils.Debug

/**
 * Reads one chat loot block (header -> indented reward lines -> `▬▬▬` footer) and books every
 * known item into a fixed mining slice.
 */
class ChatLootBlock(
    private val label: String,
    private val slice: String,
    private val startPattern: Regex,
    /** Called with the header line when a block starts, before any reward line is read. */
    private val onStart: (String) -> Unit = {},
    /**
     * Called once the block is finished. Key costs are booked here and not at [onStart],
     * because the "your key was saved" line can also arrive after the header.
     */
    private val onClose: () -> Unit = {},
    private val skipName: (String) -> Boolean = { false },
    private val mapName: (String) -> String = { it },
) {
    private var active = false
    private var lastLineMs = 0L
    private var items = 0
    private val warned = mutableMapOf<String, Long>()

    fun onChat(text: String) {
        if (ActivityBucket.current != Activity.MINING) return

        val now = System.currentTimeMillis()
        if (active && now - lastLineMs > STALE_MS) close()

        if (!active) {
            if (startPattern.matches(text)) {
                active = true
                lastLineMs = now
                items = 0
                Debug.log("[loot] $label block started")
                onStart(text)
            }
            return
        }

        lastLineMs = now

        if (ChatLootParser.isBlockEnd(text)) {
            close()
            return
        }
        if (!ChatLootParser.isRewardLine(text)) return

        val (rawName, amount) = ChatLootParser.parseItemLine(text) ?: return
        if (skipName(rawName)) return
        val name = mapName(rawName)

        val entry = ItemTable.get(name)
        if (entry == null) {
            Debug.log("[loot] $label unknown item: $name x$amount")
            LootLog.add(slice, label, name, null, amount)
            warnUnknown(name, amount)
            return
        }

        BucketLedger.addToMiningSlice(slice, entry.key, amount)
        LootLog.add(slice, label, name, entry.key, amount)
        items += 1
        Debug.log("[loot] $label +$amount ${entry.key}")
    }

    /**
     * An item missing from the table is simply not counted, so a silent drop could hide a very
     * expensive item. Say it in chat once per name instead of letting it vanish.
     */
    private fun warnUnknown(name: String, amount: Long) {
        val now = System.currentTimeMillis()
        if (now - (warned[name] ?: 0L) < WARN_COOLDOWN_MS) return
        warned[name] = now
        ChatUtils.sendLocalChat(
            "§c[skbprofit] unknown loot: §f$name §7x$amount §8(not in the item table, not counted)",
            true,
        )
    }

    private fun close() {
        if (active) Debug.log("[loot] $label block closed with $items item lines")
        val wasActive = active
        active = false
        items = 0
        if (wasActive) onClose()
    }

    private companion object {
        /** A block that stops half way (world change, disconnect) must not swallow later chat. */
        const val STALE_MS = 10_000L

        /** Do not repeat the same missing name every block. */
        const val WARN_COOLDOWN_MS = 300_000L
    }
}
