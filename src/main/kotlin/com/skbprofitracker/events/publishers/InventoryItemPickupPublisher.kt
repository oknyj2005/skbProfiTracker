package com.skbprofitracker.events.publishers

import com.google.gson.JsonParser
import com.skbprofitracker.SkbMod
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.events.models.GuiClosedEvent
import com.skbprofitracker.events.models.WorldChangedEvent
import com.skbprofitracker.items.ItemColors
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.CostLedger
import com.skbprofitracker.tracker.PurchaseGuard
import com.skbprofitracker.tracker.SupercraftGuard
import com.skbprofitracker.utils.ChatUtils.getFormattedString
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.GuiUtils
import com.skbprofitracker.utils.ItemUtils
import com.skbprofitracker.utils.WorldUtils
import com.skbprofitracker.utils.getScreenCompat
import com.skbprofitracker.utils.data.PersistentDataManager
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen
import net.minecraft.client.gui.screens.inventory.CraftingScreen
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen
import net.minecraft.client.gui.screens.inventory.FurnaceScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack

/**
 * Turns inventory snapshots into bucket ledger entries.
 *
 * The scan only ever compares totals: every [TICKS_INVENTORY_SCAN] ticks the inventory and
 * the currently open container are counted per price key, and increases are attributed to
 * whichever bucket is open at that moment. Items that moved from the container into the
 * inventory are netted out, otherwise every chest click would look like a pickup.
 *
 * Decreases are not ignored: they feed a short lived removal credit so that dropping an
 * item and picking it back up (or feeding the same item into a compactor) does not count
 * twice.
 */
object InventoryItemPickupPublisher {
    private const val TICKS_INVENTORY_SCAN = 5
    private const val INVENTORY_STABLE_MS = 3_500L
    private const val REMOVAL_CREDIT_MS = 10_000L

    private const val BAZAAR_FILTER_MS = 2_000L
    private const val AUCTION_FILTER_MS = 5_000L
    private const val NPC_FILTER_MS = 2_000L
    private const val TRADE_FILTER_MS = 2_000L
    private const val SUPERCRAFT_FILTER_MS = 2_000L

    private var tickCounter = 0
    private var previousInventory: MutableMap<String, Int>? = null

    /**
     * Opening or closing a screen reshuffles stacks between the slots, the cursor and the open
     * container, and the cursor stack in particular can move twice inside one scan. Re-baseline
     * on the transition instead of booking whatever the diff happens to look like: picking a
     * stack up onto the cursor and closing the screen used to count it a second time.
     */
    private var lastScreenOpen = false

    /**
     * Crafting, smelting and the other "turn A into B" screens do not produce loot: the inputs
     * come out of the inventory and the result comes back into it, so the diff would read as a
     * brand new item. Hypixel's recipe book also delivers the result straight into the
     * inventory without ever touching a container slot, which the container offset cannot see.
     */
    /**
     * Buying an auction hands the item straight to the inventory in the Hub, which the scan
     * cannot tell apart from loot. Both halves of the trade are printed, so they are enough to
     * fence the item off for a few seconds.
     */
    private val AUCTION_PURCHASED = Regex("^You purchased (.+) for ([\\d,]+) coins!$")
    private val AUCTION_CLAIMED = Regex("^You claimed (.+) from .+'s auction!$")

    private val CONVERSION_SCREEN_NAMES = listOf(
        "crafting", "anvil", "enchant", "brewing", "forge",
        "工作台", "合成", "铁砧", "附魔", "酿造", "锻造"
    )
    private var previousContainer: MutableMap<String, Int>? = null
    private var isInventoryLoaded = false
    private var lastFingerprint: String? = null
    private var fingerprintStableSince: Long? = null

    private val pendingRemovals = mutableMapOf<String, PendingRemoval>()

    private var lastBazaarGuiClosedMs = 0L
    private var lastBazaarMessageMs = 0L
    private var lastAuctionGuiClosedMs = 0L
    private var lastAuctionMessageMs = 0L
    private var lastNpcMessageMs = 0L
    private var lastTradeMs = 0L
    private var lastSupercraftMs = 0L

    private data class PendingRemoval(var count: Int, var atMs: Long)

    fun init() {
        EventBus.subscribe(ClientTickEvent::class) { onClientTick() }
        EventBus.subscribe(WorldChangedEvent::class) { resetInventoryState() }
        EventBus.subscribe(GuiClosedEvent::class) { onGuiClosed(it.guiName) }
        EventBus.subscribe(ChatEvent::class) { onChat(it.unformattedText) }
    }

    private fun onClientTick() {
        tickCounter++
        if (tickCounter < TICKS_INVENTORY_SCAN) return
        tickCounter = 0

        CommonUtils.runWithCatching("Failed to detect inventory changes") { detectInventoryChanges() }
    }

    private fun onGuiClosed(guiName: String) {
        val now = System.currentTimeMillis()
        if (isBazaarChestName(guiName)) lastBazaarGuiClosedMs = now
        if (isAuctionChestName(guiName)) lastAuctionGuiClosedMs = now

        // Actualize the baseline right after closing a container, so items taken out of a
        // chest are not counted on the next tick.
        CommonUtils.runWithCatching("Failed to detect inventory changes") { detectInventoryChanges() }
    }

    private fun onChat(message: String) {
        val now = System.currentTimeMillis()
        SupercraftGuard.onChat(message)
        when {
            message.startsWith("[Bazaar]") || message.contains("Bazaar!") -> lastBazaarMessageMs = now
            message.startsWith("You bought") || message.startsWith("You sold") -> lastNpcMessageMs = now
            message.startsWith("You traded with") || message.contains("Trade completed") -> lastTradeMs = now
            message.startsWith("You Supercrafted") -> lastSupercraftMs = now
            message.contains("Auction") -> lastAuctionMessageMs = now
        }

        AUCTION_PURCHASED.find(message)?.let { match ->
            if (Debug.enabled) Debug.log("[purchase] $message")
            PurchaseGuard.guard(match.groupValues[1])
        }
        AUCTION_CLAIMED.find(message)?.let { match ->
            if (Debug.enabled) Debug.log("[purchase] $message")
            PurchaseGuard.guard(match.groupValues[1])
        }
    }

    private fun detectInventoryChanges() {
        if (!WorldUtils.isInSkyblock()) {
            resetInventoryState()
            return
        }

        if (!isInventoryLoaded) {
            updateInventoryLoadedState()
            return
        }

        val currentInventory = collectInventory()
        val currentContainer = collectContainer()

        val previousInv = previousInventory
        if (previousInv == null) {
            previousInventory = currentInventory
            previousContainer = currentContainer
            return
        }

        val screenOpen = SkbMod.mc.getScreenCompat() != null
        if (screenOpen != lastScreenOpen) {
            lastScreenOpen = screenOpen
            previousInventory = currentInventory
            previousContainer = currentContainer
            return
        }

        if (isConversionScreen()) {
            previousInventory = currentInventory
            previousContainer = currentContainer
            return
        }

        val screenTitle = GuiUtils.getCurrentChestName()

        val previousCont = previousContainer ?: emptyMap()
        val containerOpen = screenTitle != null

        try {
            if (!containerOpen) {
                for ((key, previousTotal) in previousInv) {
                    val currentTotal = currentInventory[key] ?: 0
                    if (currentTotal < previousTotal) {
                        val removed = previousTotal - currentTotal
                        addPendingRemoval(key, removed)
                        CostLedger.onItemRemoved(key, removed)
                    }
                }
            } else if (GuiUtils.isInSacksGui()) {
                // Moving a stack from the inventory into a sack is not a cost, but the sack chat
                // line that follows will report it as if it were loot, so remember the amount.
                for ((key, previousTotal) in previousInv) {
                    val currentTotal = currentInventory[key] ?: 0
                    if (currentTotal < previousTotal) {
                        SacksItemPickupPublisher.noteManualDeposit(key, previousTotal - currentTotal)
                    }
                }
            }

            for ((key, currentTotal) in currentInventory) {
                val previousTotal = previousInv[key] ?: 0
                if (currentTotal <= previousTotal) continue

                val increase = currentTotal - previousTotal
                val containerDecrease = (previousCont[key] ?: 0) - (currentContainer[key] ?: 0)
                val movedBetweenInventoryAndContainer = minOf(increase, maxOf(containerDecrease, 0))
                var pickupAmount = increase - movedBetweenInventoryAndContainer
                if (pickupAmount <= 0) continue

                pickupAmount = applyRemovalCredit(key, pickupAmount)
                if (pickupAmount <= 0) continue

                onItemPickedUp(key, pickupAmount)
            }
        } finally {
            // Always advance the baseline: if anything throws half way through, keeping the
            // old snapshot would make the next scan book the same removal a second time.
            previousInventory = currentInventory
            previousContainer = currentContainer
        }
    }

    /**
     * Screens where the player turns items into other items. Matched by screen class first so the
     * client language does not matter, then by title for Hypixel's own menus.
     */
    private fun isConversionScreen(): Boolean {
        val screen = SkbMod.mc.getScreenCompat() ?: return false
        if (screen is CraftingScreen || screen is AnvilScreen || screen is EnchantmentScreen ||
            screen is BrewingStandScreen || screen is FurnaceScreen
        ) {
            return true
        }

        val title = GuiUtils.getCurrentChestName() ?: return false
        return CONVERSION_SCREEN_NAMES.any { title.contains(it, ignoreCase = true) }
    }

    /**
     * Consumes pending removals that explain part (or all) of [amount].
     *
     * Two cases are covered: the exact same item leaving and coming back, and a compactor
     * turning [compactsFrom] of the parent into one of this item.
     */
    private fun applyRemovalCredit(key: String, amount: Int): Int {
        val now = System.currentTimeMillis()

        val remaining = amount - consumePendingRemoval(key, amount, now)
        if (remaining <= 0) return 0

        val entry = ItemTable.getByKey(key) ?: return remaining
        val parentName = entry.compactsFrom ?: return remaining
        if (entry.compactRatio <= 0) return remaining

        val parentKey = ItemTable.get(parentName)?.key ?: return remaining
        val needed = entry.compactRatio.toLong() * remaining
        if (consumePendingRemovalIfEnough(parentKey, needed, now)) return 0

        return remaining
    }

    private fun addPendingRemoval(key: String, amount: Int) {
        val now = System.currentTimeMillis()
        val pending = pendingRemovals[key]
        if (pending == null || now - pending.atMs > REMOVAL_CREDIT_MS) {
            pendingRemovals[key] = PendingRemoval(amount, now)
        } else {
            pending.count += amount
            pending.atMs = now
        }
    }

    private fun consumePendingRemoval(key: String, upTo: Int, now: Long): Int {
        val pending = pendingRemovals[key] ?: return 0
        if (now - pending.atMs > REMOVAL_CREDIT_MS) {
            pendingRemovals.remove(key)
            return 0
        }

        val consumed = minOf(pending.count, upTo)
        pending.count -= consumed
        if (pending.count <= 0) pendingRemovals.remove(key)
        return consumed
    }

    private fun consumePendingRemovalIfEnough(key: String, needed: Long, now: Long): Boolean {
        val pending = pendingRemovals[key] ?: return false
        if (now - pending.atMs > REMOVAL_CREDIT_MS) {
            pendingRemovals.remove(key)
            return false
        }
        if (pending.count < needed) return false

        pending.count -= needed.toInt()
        if (pending.count <= 0) pendingRemovals.remove(key)
        return true
    }

    private fun onItemPickedUp(key: String, amount: Int) {
        if (shouldSkipPickup()) return

        // A supercrafted stack coming back out of the sacks is stock the player already owned.
        val displayName = ItemTable.getByKey(key)?.name
        if (displayName != null && SupercraftGuard.guardsInventory(displayName)) return

        if (PurchaseGuard.shouldSkip(key)) return

        val activity = ActivityBucket.bucketForPickup() ?: return
        ActivityBucket.onWeakSignal()

        BucketLedger.add(activity, key, amount.toLong())
    }

    private fun shouldSkipPickup(): Boolean {
        val now = System.currentTimeMillis()
        val chestName = GuiUtils.getCurrentChestName()

        if (chestName != null) {
            if (isBazaarChestName(chestName)) return true
            if (isAuctionChestName(chestName)) return true
            if (GuiUtils.isInSacksGui()) return true
            if (chestName == "Pets" || chestName.contains("Hunting Box")) return true
        }

        if (now - lastBazaarGuiClosedMs < BAZAAR_FILTER_MS) return true
        if (now - lastBazaarMessageMs < BAZAAR_FILTER_MS) return true
        if (now - lastAuctionGuiClosedMs < AUCTION_FILTER_MS) return true
        if (now - lastAuctionMessageMs < AUCTION_FILTER_MS) return true
        if (now - lastNpcMessageMs < NPC_FILTER_MS) return true
        if (now - lastTradeMs < TRADE_FILTER_MS) return true
        if (now - lastSupercraftMs < SUPERCRAFT_FILTER_MS) return true

        return false
    }

    private fun isBazaarChestName(name: String): Boolean =
        name.contains("Bazaar") || name.contains("Order options") || name.contains("Instant Buy")

    private fun isAuctionChestName(name: String): Boolean =
        name.contains("Auction") || name.contains("Create Auction")

    private fun collectInventory(): MutableMap<String, Int> {
        val result = mutableMapOf<String, Int>()
        val player = SkbMod.mc.player ?: return result

        for (i in 0..35) {
            addStack(result, player.inventory.getItem(i))
        }

        addStack(result, player.containerMenu.carried)

        return result
    }

    private fun collectContainer(): MutableMap<String, Int> {
        val result = mutableMapOf<String, Int>()
        val player = SkbMod.mc.player ?: return result
        val playerInventory = player.inventory

        for (slot in player.containerMenu.slots) {
            if (slot.container === playerInventory) continue
            addStack(result, slot.item)
        }

        // Equipping or unequipping armor from the inventory screen must not look like loot.
        if (SkbMod.mc.getScreenCompat() is InventoryScreen) {
            addStack(result, player.getItemBySlot(EquipmentSlot.HEAD))
            addStack(result, player.getItemBySlot(EquipmentSlot.CHEST))
            addStack(result, player.getItemBySlot(EquipmentSlot.LEGS))
            addStack(result, player.getItemBySlot(EquipmentSlot.FEET))
            addStack(result, player.getItemBySlot(EquipmentSlot.OFFHAND))
        }

        return result
    }

    private fun addStack(result: MutableMap<String, Int>, stack: ItemStack) {
        val entry = getEntryFromStack(stack) ?: return
        result[entry.key] = (result[entry.key] ?: 0) + stack.count
    }

    private fun getEntryFromStack(stack: ItemStack): ItemTable.Entry? {
        if (stack.isEmpty) return null

        var name = ItemUtils.getCleanItemName(stack.hoverName.getFormattedString())
        if (name.isBlank()) return null

        if (name == "Enchanted Book") {
            val bookName = ItemUtils.getEnchantedBookName(stack)
            if (!bookName.isNullOrBlank()) name += " ($bookName)"
        } else if (name.startsWith("[Lvl ")) {
            val tier = getPetTier(stack)
            if (tier != null) name += " (${tier.uppercase()})"
        }

        val entry = ItemTable.get(name) ?: return null
        ItemColors.remember(entry.key, ItemUtils.getRarityPrefix(stack))
        return entry
    }

    private fun getPetTier(stack: ItemStack): String? {
        val customData = ItemUtils.getCustomData(stack) ?: return null
        if (ItemUtils.getCustomDataId(customData) != "PET") return null

        return try {
            val petInfo = ItemUtils.getCustomDataPetInfo(customData) ?: return null
            JsonParser.parseString(petInfo).asJsonObject.get("tier")?.takeIf { it.isJsonPrimitive }?.asString
        } catch (e: Exception) {
            null
        }
    }

    private fun resetInventoryState() {
        previousInventory = null
        previousContainer = null
        isInventoryLoaded = false
        lastFingerprint = null
        fingerprintStableSince = null
        pendingRemovals.clear()
    }

    /**
     * After a world change the inventory is rewritten slot by slot, which looks exactly
     * like a hundred pickups. Wait until the fingerprint has been identical for
     * [INVENTORY_STABLE_MS] before trusting any diff.
     */
    private fun updateInventoryLoadedState() {
        val fingerprint = inventoryFingerprint()
        val now = System.currentTimeMillis()

        if (fingerprint != lastFingerprint) {
            lastFingerprint = fingerprint
            fingerprintStableSince = now
            return
        }

        val stableSince = fingerprintStableSince ?: now.also { fingerprintStableSince = it }
        if (now - stableSince < INVENTORY_STABLE_MS) return

        isInventoryLoaded = true
    }

    private fun inventoryFingerprint(): String {
        val player = SkbMod.mc.player ?: return ""
        return (0..35).joinToString(";") { i ->
            val stack = player.inventory.getItem(i)
            if (stack.isEmpty) "" else "${stack.hoverName.getUnformattedString()}|${stack.count}"
        }
    }
}
