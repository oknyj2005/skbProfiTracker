package com.skbprofitracker.events.publishers

import com.skbprofitracker.capture.EssenceDeduper
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ActionBarEvent
import com.skbprofitracker.events.models.WorldChangedEvent
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.utils.Debug
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState

/**
 * Essences are a currency pool, not an item: they never enter the inventory and never appear in a
 * sack message, so neither the inventory diff nor the sack parser can see them. Hypixel does
 * announce every gain in the action bar ("+100 Ice Essence"), and that bar is the only readable
 * channel - which is exactly what Feesh hooks for Ice Essence as well.
 *
 * The bar keeps re-sending the same text for a while after one gain, so identical repeats inside
 * [EssenceDeduper.REPEAT_WINDOW_MS] are dropped without clearing on transient ability bars.
 * A repeated text that comes back after a real gap is a new gain.
 */
object EssenceWatcher {
    /** Matches "+100 Ice Essence", "+3 Fossil Essence". */
    private val GAIN = Regex("\\+(?<amount>[\\d,]+)\\s+(?<name>[A-Za-z' ]+?Essence)\\b")

    private val deduper = EssenceDeduper()
    private data class FossilTarget(val block: String, val atMs: Long)
    private val fossilTargets = mutableMapOf<Long, FossilTarget>()

    fun init() {
        EventBus.subscribe(ActionBarEvent::class, ::onActionBar)
        EventBus.subscribe(WorldChangedEvent::class) {
            deduper.clear()
            fossilTargets.clear()
        }
    }

    fun observeFossilTarget(pos: BlockPos, state: BlockState) {
        if (!Debug.verbose) return
        if (!ActivityBucket.world.contains("mineshaft", ignoreCase = true) &&
            !ActivityBucket.zone.contains("mineshaft", ignoreCase = true)
        ) return
        val block = BuiltInRegistries.BLOCK.getKey(state.block).path
        if (block != "bone_block" && !block.contains("quartz")) return

        val now = System.currentTimeMillis()
        fossilTargets.entries.removeIf { now - it.value.atMs > 5_000L }
        if (fossilTargets.putIfAbsent(pos.asLong(), FossilTarget(block, now)) == null) {
            Debug.trace("[fossil] target $pos $block at $now")
        }
    }

    fun observeFossilBlockUpdate(pos: BlockPos, state: BlockState) {
        if (!Debug.verbose) return
        val target = fossilTargets[pos.asLong()] ?: return
        val now = System.currentTimeMillis()
        val block = BuiltInRegistries.BLOCK.getKey(state.block).path
        if (now - target.atMs > 5_000L || block == target.block) return

        fossilTargets.remove(pos.asLong())
        Debug.trace("[fossil] server changed $pos ${target.block} -> $block at $now")
    }

    private fun onActionBar(event: ActionBarEvent) {
        val now = System.currentTimeMillis()
        val matches = GAIN.findAll(event.unformattedText).toList()

        if (matches.isEmpty()) {
            val hadFossil = deduper.hasSeen("Fossil Essence")
            if (deduper.onEmptyBar(now) && Debug.verbose && hadFossil) {
                Debug.trace("[fossil] action bar cleared at $now")
            }
            return
        }

        for (match in matches) {
            val name = match.groups["name"]?.value?.trim().orEmpty()
            val amount = match.groups["amount"]?.value?.replace(",", "")?.toIntOrNull() ?: continue
            if (name.isEmpty() || amount <= 0) continue

            val fresh = deduper.accept(name, amount, now)
            if (Debug.verbose && name == "Fossil Essence") {
                Debug.trace("[fossil] action bar +$amount at $now (repeat=${!fresh})")
            }
            if (!fresh || (name == "Fossil Essence" && FossilExcavation.hasEssenceReward(now))) continue

            val entry = ItemTable.get(name)
            if (entry == null) {
                Debug.decision("[essence] unknown '$name' x$amount")
                continue
            }

            val activity = ActivityBucket.bucketForPickup() ?: continue
            Debug.trace("[essence] +$amount $name (${entry.key})")
            BucketLedger.add(activity, entry.key, amount.toLong())
        }
    }
}
