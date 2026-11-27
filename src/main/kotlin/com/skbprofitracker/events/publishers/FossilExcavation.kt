package com.skbprofitracker.events.publishers

import com.skbprofitracker.capture.ExcavationRewardBlock
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.events.models.GuiClosedEvent
import com.skbprofitracker.events.models.WorldChangedEvent
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.GuiUtils
import com.skbprofitracker.utils.WorldUtils

internal object FossilExcavation {
    private val rewardBlock = ExcavationRewardBlock()
    private var closedAt: Long? = null

    fun init() {
        EventBus.subscribe(ChatEvent::class) { event ->
            if (!WorldUtils.isInSkyblock()) return@subscribe
            val now = System.currentTimeMillis()
            val (name, amount) = rewardBlock.accept(event.unformattedText, now) ?: return@subscribe
            // Powder and essence are currencies, not items delivered to the inventory or sacks.
            if (name.endsWith(" Powder") || name.endsWith(" Essence") || amount > Int.MAX_VALUE) return@subscribe
            val keys = ItemTable.getChatKeys(name)
            if (keys.isEmpty()) return@subscribe
            InventoryItemPickupPublisher.nonLootDeliveries.record(keys, amount.toInt(), now, canEnterSack = true)
            Debug.decision("[excavation] exclude $name x$amount")
        }
        EventBus.subscribe(GuiClosedEvent::class) {
            if (isOpen(it.guiName)) closedAt = System.currentTimeMillis()
        }
        EventBus.subscribe(WorldChangedEvent::class) {
            closedAt = null
            rewardBlock.clear()
        }
    }

    fun isOpen(title: String? = GuiUtils.getCurrentChestName()): Boolean = ExcavationRewardBlock.isExcavator(title)

    fun justClosed(now: Long): Boolean = closedAt?.let { now - it in 0..ExcavationRewardBlock.RESULT_WINDOW_MS } == true

    fun hasEssenceReward(now: Long): Boolean = isOpen() || justClosed(now) || rewardBlock.recentResult(now)
}
