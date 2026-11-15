package com.skbprofitracker.utils

import com.skbprofitracker.utils.ChatUtils.removeFormatting
import com.skbprofitracker.SkbMod
import com.skbprofitracker.utils.getScreenCompat
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.GuiClosedEvent
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import java.util.Date
import java.util.Timer
import kotlin.concurrent.timerTask

data class LastGuisClosed(
    var lastSacksGuiClosedAt: Date? = null,
    var lastOdgerGuiClosedAt: Date? = null,
    var lastTrophyFrogsGuiClosedAt: Date? = null,
    var lastAuctionGuiClosedAt: Date? = null,
    var lastSupercraftGuiClosedAt: Date? = null,
    var lastCraftGuiClosedAt: Date? = null,
    var lastStorageGuiClosedAt: Date? = null,
    var lastBazaarGuiClosedAt: Date? = null,
    var lastPetItemSwapGuiClosedAt: Date? = null,
    var lastKatGuiClosedAt: Date? = null,
    var lastGeorgeGuiClosedAt: Date? = null,
    var lastHotmGuiClosedAt: Date? = null
)

object GuiUtils {
    private var cachedIsInInventoryOrChat: Boolean = false
    private var timer: Timer? = null

    val lastGuisClosed = LastGuisClosed()

    fun init() {
        startTimer()
        EventBus.subscribe(GuiClosedEvent::class, ::onGuiClosed)
    }

    private fun onGuiClosed(event: GuiClosedEvent) {
        val chestName = event.guiName.removeFormatting()
        if (chestName.isNullOrBlank()) return

        val now = Date()
        when {
            chestName.contains("Sack") -> lastGuisClosed.lastSacksGuiClosedAt = now
            chestName.contains("Trophy Fish") -> lastGuisClosed.lastOdgerGuiClosedAt = now
            chestName.contains("Trophy Frogs") -> lastGuisClosed.lastTrophyFrogsGuiClosedAt = now
            chestName.contains("Manage Auctions") || chestName.contains("Confirm Purchase") || chestName.contains("BIN Auction View") || chestName.contains("Your Bids") ->
                lastGuisClosed.lastAuctionGuiClosedAt = now
            chestName.endsWith("Recipe") -> lastGuisClosed.lastSupercraftGuiClosedAt = now
            chestName.contains("Craft Item") -> lastGuisClosed.lastCraftGuiClosedAt = now
            chestName.contains("Backpack") || chestName.contains("Chest") || chestName.contains("Ender Chest") ->
                lastGuisClosed.lastStorageGuiClosedAt = now
            isBazaarChestName(chestName) -> lastGuisClosed.lastBazaarGuiClosedAt = now
            chestName == "Swap Pet Item" || chestName == "Remove Pet Item" -> 
                lastGuisClosed.lastPetItemSwapGuiClosedAt = now
            chestName.contains("Offer Pets") -> lastGuisClosed.lastGeorgeGuiClosedAt = now
            chestName.contains("Pet Sitter") -> lastGuisClosed.lastKatGuiClosedAt = now
            chestName.contains("Heart of the Mountain") -> lastGuisClosed.lastHotmGuiClosedAt = now
        }
    }

    private fun startTimer() {
        timer?.cancel()
        timer = Timer("Skb-GuiUtils", true)

        val task = timerTask {
            CommonUtils.runWithCatching("Failed to update Gui utils cache") {
                updateCache()
            }
        }
        timer?.scheduleAtFixedRate(task, 0, 200)
    }

    private fun updateCache() {
        cachedIsInInventoryOrChat = readIsInInventoryOrChat()
    }

    private fun readIsInInventoryOrChat(): Boolean {
        if (!WorldUtils.isInSkyblock()) return false

        val screen = SkbMod.mc.getScreenCompat() ?: return false
        return screen is InventoryScreen || screen is ChatScreen
    }

    /*
     * Check if the player is in an inventory or chat screen.
     * This is assumed to be called very often so it is periodically cached.
     * @returns {Boolean}
     */
    fun isInInventoryOrChat(): Boolean {
        // Read live: a cached value made the overlay buttons linger for a second after closing
        // the inventory, and clicks in that window were dropped.
        return readIsInInventoryOrChat()
    }

    fun isInChest(): Boolean {
        val screen = SkbMod.mc.getScreenCompat() ?: return false
        return (screen is AbstractContainerScreen<*> && screen !is InventoryScreen)
    }

    fun getCurrentChestName(): String? {
        val screen = SkbMod.mc.getScreenCompat() ?: return null
        if (screen !is AbstractContainerScreen<*>) return null
        return screen.title.getUnformattedString()
    }

    fun isInBazaarGui(): Boolean {
        val title = getCurrentChestName() ?: return false
        return isBazaarChestName(title)
    }

    private fun isBazaarChestName(chestName: String): Boolean {
        return chestName.contains("Bazaar") ||
            chestName.contains("Order options") ||
            chestName.contains("Instant Buy") ||
            chestName.contains("Bazaar Orders") ||
            chestName.contains("➜") // Viewing specific item
    }

    fun isInSacksGui(): Boolean {
        val title = getCurrentChestName() ?: return false
        return (title == "Sacks" || title.endsWith("Sack"))
    }

    fun isInSupercraftGui(): Boolean {
        val title = getCurrentChestName() ?: return false
        return (title.endsWith("Recipe"))
    }
}
