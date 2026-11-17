package com.skbprofitracker.events.publishers

import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ActionBarEvent
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.utils.Debug

/**
 * Essences are a currency pool, not an item: they never enter the inventory and never appear in a
 * sack message, so neither the inventory diff nor the sack parser can see them. Hypixel does
 * announce every gain in the action bar ("+100 Ice Essence"), and that bar is the only readable
 * channel - which is exactly what Feesh hooks for Ice Essence as well.
 *
 * The bar keeps re-sending the same text for a while after one gain, so identical repeats inside
 * [REPEAT_WINDOW_MS] are dropped. A repeated text that comes back after a real gap is a new gain.
 */
object EssenceWatcher {
    /** Matches "+100 Ice Essence", "+3 Fossil Essence". */
    private val GAIN = Regex("\\+(?<amount>[\\d,]+)\\s+(?<name>[A-Za-z' ]+?Essence)\\b")

    private const val REPEAT_WINDOW_MS = 1_500L

    private data class Seen(val text: String, val atMs: Long)

    private val seen = mutableMapOf<String, Seen>()

    fun init() {
        EventBus.subscribe(ActionBarEvent::class, ::onActionBar)
    }

    private fun onActionBar(event: ActionBarEvent) {
        val now = System.currentTimeMillis()
        val matches = GAIN.findAll(event.unformattedText).toList()

        if (matches.isEmpty()) {
            seen.clear()
            return
        }

        for (match in matches) {
            val name = match.groups["name"]?.value?.trim().orEmpty()
            val amount = match.groups["amount"]?.value?.replace(",", "")?.toIntOrNull() ?: continue
            if (name.isEmpty() || amount <= 0) continue

            val text = "$name+$amount"
            val previous = seen[name]
            val repeated = previous != null &&
                previous.text == text &&
                now - previous.atMs <= REPEAT_WINDOW_MS
            seen[name] = Seen(text, now)
            if (repeated) continue

            val entry = ItemTable.get(name)
            if (entry == null) {
                Debug.decision("[essence] unknown '$name' x$amount")
                continue
            }

            val activity = ActivityBucket.current ?: continue
            Debug.trace("[essence] +$amount $name (${entry.key})")
            BucketLedger.add(activity, entry.key, amount.toLong())
        }
    }
}
