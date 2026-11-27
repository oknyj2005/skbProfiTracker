package com.skbprofitracker.events.publishers

import com.google.gson.JsonParser
import com.skbprofitracker.SkbMod
import com.skbprofitracker.capture.ActivityKind
import com.skbprofitracker.capture.CaptureBuffer
import com.skbprofitracker.capture.AddVerdict
import com.skbprofitracker.capture.MiningConsumableUses
import com.skbprofitracker.capture.RemovalVerdict
import com.skbprofitracker.capture.ScanRules
import com.skbprofitracker.capture.ScreenKind
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.events.models.GuiClosedEvent
import com.skbprofitracker.events.models.PlayerInteractEvent
import com.skbprofitracker.events.models.WorldChangedEvent
import com.skbprofitracker.items.ItemColors
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.Activity
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.CostLedger
import com.skbprofitracker.capture.PurchaseDelivery
import com.skbprofitracker.capture.NonLootDeliveries
import com.skbprofitracker.tracker.SupercraftGuard
import com.skbprofitracker.utils.ChatUtils.getFormattedString
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.GuiUtils
import com.skbprofitracker.utils.ItemUtils
import com.skbprofitracker.utils.WorldUtils
import com.skbprofitracker.utils.data.BucketData
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import com.skbprofitracker.utils.getScreenCompat
import com.skbprofitracker.utils.data.PersistentDataManager
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen
import net.minecraft.client.gui.screens.inventory.CraftingScreen
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen
import net.minecraft.client.gui.screens.inventory.FurnaceScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
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
    private const val TICKS_INVENTORY_SCAN = 4
    private const val INVENTORY_STABLE_MS = 3_500L
    private const val REMOVAL_CREDIT_MS = 10_000L
    private const val PENDING_INCREASE_MS = 1_000L

    // Every uncertain move (held pickups, removal credits, ignored withdrawals) lives here so it
    // expires in one place and can be replayed with fake time in tools/sim.
    private val buffer = CaptureBuffer(PENDING_INCREASE_MS, REMOVAL_CREDIT_MS)
    private val miningConsumableUses = MiningConsumableUses()

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
    /** Item names the table did not know about, so a miss is logged once, not 5x a second. */
    private val unseenNames = mutableSetOf<String>()

    /** Gear churn can produce hundreds of one-off names; a sample is enough to notice gaps. */
    private var unseenLogged = 0
    private const val UNSEEN_LOG_LIMIT = 40
    private var lastScreenOpen = false
    private var wasInSackGui = false
    private var lastSackScreenClosedMs = 0L
    private var lastHandSackUseMs = 0L

    /** When any menu was last closed; used to drop the slot rewrite that follows it. */
    private var lastScreenClosedMs = 0L

    /**
     * Items that showed up in the inventory while a menu was open but no container slot lost them
     * in the same scan.
     *
     * Hypixel refreshes the menu a beat after the click, so the slot empties one scan later. Booking
     * straight away would count forge claims and sack withdrawals as loot; dropping them outright
     * would lose real drops that land while the player has a menu open (a boss dying to someone
     * else, for example). Holding them for [PENDING_INCREASE_MS] does both: a late container
     * decrease explains the increase, otherwise it is booked.
     */

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
    private val SACK_GRAB = Regex("grab x([\\d,]+) (.+?) from sacks!")
    private const val SACK_SCREEN_COOLDOWN_MS = 5_000L

    /** A mining pickup this soon after closing a menu is the menu handing items back. */
    private const val SCREEN_CLOSE_GRACE_MS = 1_500L

    private val CONVERSION_SCREEN_NAMES = listOf(
        "crafting", "anvil", "enchant", "brewing", "forge",
        "工作台", "合成", "铁砧", "附魔", "酿造", "锻造"
    )

    /**
     * Hypixel's own menu screens. Their slots are server side, so anything that appears to move
     * while one is open is a UI artefact rather than loot: rebuild the baseline and skip the scan.
     */
    private val MENU_SCREEN_NAMES = listOf(
        "skyblock menu", "skyblock 菜单", "skyblock选单",
        "fast travel", "skyblock leveling", "collections"
    )
    private var previousContainer: MutableMap<String, Int>? = null
    private var isInventoryLoaded = false
    private var lastFingerprint: String? = null
    private var fingerprintStableSince: Long? = null


    internal val nonLootDeliveries = NonLootDeliveries()

    private data class InventoryGain(
        val key: String,
        var amount: Int,
        val at: Long,
        val target: ActivityBucket.PickupTarget?,
    )

    private val pendingGains = ArrayDeque<InventoryGain>()
    private var lastMarketClosedMs = 0L
    private var lastAuctionGuiClosedMs = 0L
    private var lastAuctionMessageMs = 0L
    private var lastNpcMessageMs = 0L
    private var lastTradeMs = 0L
    private var lastSupercraftMs = 0L


    fun init() {
        EventBus.subscribe(ClientTickEvent::class) { onClientTick() }
        EventBus.subscribe(WorldChangedEvent::class) { resetInventoryState() }
        EventBus.subscribe(GuiClosedEvent::class) { onGuiClosed(it.guiName) }
        EventBus.subscribe(ChatEvent::class) { onChat(it.unformattedText) }
        EventBus.subscribe(PlayerInteractEvent::class) { onPlayerInteract(it) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            recentCredits.clear()
            pendingGains.clear()
            nonLootDeliveries.clear()
            lastMarketClosedMs = 0L
            resetInventoryState()
        }
    }

    private fun onPlayerInteract(event: PlayerInteractEvent) {
        if (SkbMod.mc.getScreenCompat() != null || !WorldUtils.isInSkyblock()) return
        val inventory = SkbMod.mc.player?.inventory ?: return
        val slot = if (event.isMainHand) inventory.selectedSlot else Inventory.SLOT_OFFHAND
        val stack = inventory.getItem(slot)
        val key = getEntryFromStack(stack)?.key ?: return
        if (!CostLedger.isMiningConsumable(key)) return
        miningConsumableUses.attempt(slot, key, stack.count, System.nanoTime() / 1_000_000L)
        if (Debug.verbose) Debug.trace("[mining use] attempted $key x${stack.count} in slot $slot")
    }

    fun onHandSackUse() {
        if (SkbMod.mc.getScreenCompat() != null || !WorldUtils.isInSkyblock()) return
        val stack = SkbMod.mc.player?.mainHandItem ?: return
        if (!stack.isEmpty && stack.hoverName.getUnformattedString().contains("sack", ignoreCase = true)) {
            lastHandSackUseMs = System.currentTimeMillis()
        }
    }

    private fun playerMenu(containerId: Int): AbstractContainerMenu? {
        val player = SkbMod.mc.player ?: return null
        if (containerId == 0) return player.inventoryMenu
        return player.containerMenu.takeIf { it.containerId == containerId }
    }

    fun hasPendingConsumableUses(): Boolean = miningConsumableUses.hasPendingUses()

    fun onPlayerMenuSlot(containerId: Int, slot: Int, stack: ItemStack) {
        if (!hasPendingConsumableUses()) return
        val inventory = SkbMod.mc.player?.inventory ?: return
        val menuSlot = playerMenu(containerId)?.slots?.getOrNull(slot) ?: return
        if (menuSlot.container === inventory) onPlayerInventorySlot(menuSlot.containerSlot, stack)
    }

    fun onPlayerMenuContents(containerId: Int, stacks: List<ItemStack>) {
        if (!hasPendingConsumableUses()) return
        val inventory = SkbMod.mc.player?.inventory ?: return
        val menu = playerMenu(containerId) ?: return
        for (slot in miningConsumableUses.slots()) {
            val menuSlot = menu.findSlot(inventory, slot).orElse(-1)
            stacks.getOrNull(menuSlot)?.let { onPlayerInventorySlot(slot, it) }
        }
    }

    fun onHandStackMoved(offhandSwap: Boolean) {
        if (!hasPendingConsumableUses()) return
        val inventory = SkbMod.mc.player?.inventory ?: return
        cancelPendingUse(inventory.selectedSlot)
        if (offhandSwap) cancelPendingUse(Inventory.SLOT_OFFHAND)
    }

    fun onContainerClick(containerId: Int, clickedSlot: Int, changedSlots: IntArray, swapSlot: Int) {
        val inventory = SkbMod.mc.player?.inventory ?: return
        val menu = playerMenu(containerId) ?: return
        fun cancelMenuSlot(index: Int) {
            val slot = menu.slots.getOrNull(index) ?: return
            if (slot.container === inventory) cancelPendingUse(slot.containerSlot)
        }
        cancelMenuSlot(clickedSlot)
        changedSlots.forEach(::cancelMenuSlot)
        if (swapSlot >= 0) cancelPendingUse(swapSlot)
    }

    private fun cancelPendingUse(slot: Int) {
        if (miningConsumableUses.cancel(slot) && Debug.verbose) {
            Debug.trace("[mining use] cancelled by a manual move in slot $slot")
        }
    }

    fun onPlayerInventorySlot(slot: Int, stack: ItemStack) {
        if (!miningConsumableUses.hasPendingUse(slot)) return
        CommonUtils.runWithCatching("Failed to confirm mining consumable use") {
            val key = getEntryFromStack(stack)?.key
            val count = if (stack.isEmpty) 0 else stack.count
            val used = miningConsumableUses.serverUpdate(slot, key, count, System.nanoTime() / 1_000_000L)
            if (Debug.verbose) Debug.trace("[mining use] server slot $slot: ${key ?: "empty"} x$count, confirmed=$used")
            if (used != null) CostLedger.onMiningConsumableUsed(used.first, used.second)
        }
    }

    private fun onClientTick() {
        tickCounter++
        if (tickCounter < TICKS_INVENTORY_SCAN) return
        tickCounter = 0

        CommonUtils.runWithCatching("Failed to detect inventory changes") {
            val now = System.currentTimeMillis()
            flushInventoryGains(now)
            detectInventoryChanges()
            nonLootDeliveries.prune(now)
        }
    }

    private fun onGuiClosed(guiName: String) {
        val now = System.currentTimeMillis()
        val marketClosing = isBazaarChestName(guiName) || isAuctionChestName(guiName)
        val sackClosing = guiName.contains("sack", ignoreCase = true)
        if (marketClosing) lastMarketClosedMs = now
        if (isAuctionChestName(guiName)) lastAuctionGuiClosedMs = now
        if (sackClosing) lastSackScreenClosedMs = now

        // Actualize the baseline right after closing a container, so items taken out of a
        // chest are not counted on the next tick.
        CommonUtils.runWithCatching("Failed to detect inventory changes") {
            flushInventoryGains(now)
            detectInventoryChanges(marketClosing = marketClosing, sackClosing = sackClosing)
        }
    }

    private fun onChat(message: String) {
        val now = System.currentTimeMillis()
        SupercraftGuard.onChat(message)
        when {
            message.startsWith("You bought") || message.startsWith("You sold") -> lastNpcMessageMs = now
            message.startsWith("You traded with") || message.contains("Trade completed") -> lastTradeMs = now
            message.startsWith("You Supercrafted") -> lastSupercraftMs = now
            message.contains("Auction") -> lastAuctionMessageMs = now
        }

        if (WorldUtils.isInSkyblock()) PurchaseDelivery.parse(message)?.let { delivery ->
            val keys = ItemTable.getChatKeys(delivery.name)
            nonLootDeliveries.record(keys, delivery.amount, now, delivery.canEnterSack)
            Debug.decision("[purchase] delivery ${delivery.amount}x ${delivery.name} -> $keys")
        }

        // SkyHanni (and the Sacks GUI) hand sack contents straight into the inventory, so the
        // scan sees a plain increase. Register a removal credit so the increase is cancelled.
        SACK_GRAB.find(message)?.let { match ->
            val amount = match.groupValues[1].replace(",", "").toIntOrNull() ?: 0
            val entry = ItemTable.get(match.groupValues[2].trim())
            if (entry != null && amount > 0) {
                addPendingRemoval(entry.key, amount)
                if (Debug.enabled) Debug.log("[sacks] grab ${entry.key} -$amount")
            }
        }
    }

    private fun detectInventoryChanges(marketClosing: Boolean = false, sackClosing: Boolean = false) {
        if (!WorldUtils.isInSkyblock()) {
            resetInventoryState()
            return
        }

        if (!isInventoryLoaded) {
            updateInventoryLoadedState()
            return
        }

        val now = System.currentTimeMillis()
        val currentInventory = collectInventory()
        val currentContainer = collectContainer()

        val screenOpen = SkbMod.mc.getScreenCompat() != null
        val inSackGui = screenOpen && GuiUtils.isInSacksGui()
        val leavingSackGui = (wasInSackGui || sackClosing) && !inSackGui
        val screenChanged = screenOpen != lastScreenOpen
        if (leavingSackGui) lastSackScreenClosedMs = now
        if (screenChanged && !screenOpen) lastScreenClosedMs = now
        wasInSackGui = inSackGui
        lastScreenOpen = screenOpen

        val previousInv = previousInventory
        if (previousInv == null) {
            previousInventory = currentInventory
            previousContainer = currentContainer
            return
        }

        val screenTitle = GuiUtils.getCurrentChestName()
        val excavatorOpen = FossilExcavation.isOpen(screenTitle)
        val excavatorJustClosed = FossilExcavation.justClosed(now)
        val bazaarOpen = screenTitle?.let(::isBazaarChestName) == true
        val marketOpen = bazaarOpen || screenTitle?.let(::isAuctionChestName) == true
        val marketJustClosed = marketClosing || now - lastMarketClosedMs < SCREEN_CLOSE_GRACE_MS
        val conversionOpen = isConversionScreen()
        val menuOpen = isMenuScreen()
        val screenKind = when {
            inSackGui -> ScreenKind.SACK
            conversionOpen || excavatorOpen -> ScreenKind.CONVERSION
            GuiUtils.isInChest() -> ScreenKind.CONTAINER
            screenOpen -> ScreenKind.CUSTOM_MENU
            else -> ScreenKind.NONE
        }
        val removalVerdict = ScanRules.removalVerdict(
            screenKind,
            msSinceSackClosed = now - lastSackScreenClosedMs,
            handSackUsedRecently = now - lastHandSackUseMs < ScanRules.POST_SACK_DEPOSIT_MS,
            leavingSackGui = leavingSackGui,
        )

        try {
            // Observe transfers before rebuilding the baseline, including direct menu switches.
            if (removalVerdict == RemovalVerdict.DEPOSIT || (!screenChanged && !menuOpen)) {
                recordInventoryRemovals(previousInv, currentInventory, removalVerdict, screenTitle, leavingSackGui, now)
            }
            if (screenChanged) {
                if (marketOpen || marketJustClosed || excavatorOpen || excavatorJustClosed) {
                    for ((key, currentTotal) in currentInventory) {
                        val increase = currentTotal - (previousInv[key] ?: 0)
                        if (increase > 0) pendingGains.addLast(InventoryGain(key, increase, now, null))
                    }
                }
                return
            }
            if (conversionOpen || menuOpen) return

            // Each lost unit can explain one increase, including a hold from the previous scan.
            val remainingContainerLosses = HashMap<String, Int>()
            for ((key, previousCount) in previousContainer.orEmpty()) {
                val lost = previousCount - (currentContainer[key] ?: 0)
                val remaining = buffer.remainingContainerDecrease(key, lost)
                if (remaining > 0) remainingContainerLosses[key] = remaining
            }

            for ((key, currentTotal) in currentInventory) {
                val previousTotal = previousInv[key] ?: 0
                if (currentTotal <= previousTotal) continue

                val increase = currentTotal - previousTotal
                val containerDecrease = remainingContainerLosses[key] ?: 0
                val movedBetweenInventoryAndContainer =
                    if (excavatorOpen) 0 else ScanRules.containerExplained(increase, containerDecrease)
                var pickupAmount = increase - movedBetweenInventoryAndContainer
                if (pickupAmount <= 0) continue

                if (marketOpen || marketJustClosed || excavatorOpen) {
                    // The excavator delivers rewards straight to inventory or sacks. A slot
                    // update can precede the reward chat; let it consume the eventual receipt.
                    val unexplained = if (excavatorOpen) pickupAmount else applyRemovalCredit(key, pickupAmount)
                    if (unexplained > 0) {
                        val canCredit = !excavatorOpen && (bazaarOpen || (!marketOpen && !shouldSkipPickup()))
                        val target = if (canCredit) pickupTarget(now) else null
                        pendingGains.addLast(InventoryGain(key, unexplained, now, target))
                    }
                    continue
                }

                val verdict = ScanRules.addVerdict(
                    activity = if (ActivityBucket.current == Activity.MINING) ActivityKind.MINING else ActivityKind.SLAYER,
                    screenOpen = screenOpen,
                    sackScreenOpen = inSackGui,
                    msSinceScreenClosed = now - lastScreenClosedMs,
                    msSinceSackClosed = now - lastSackScreenClosedMs,
                    screenCloseGraceMs = SCREEN_CLOSE_GRACE_MS,
                    sackCooldownMs = SACK_SCREEN_COOLDOWN_MS,
                )
                if (verdict == AddVerdict.WITHDRAWAL) buffer.noteSkipped(key, pickupAmount)
                if (verdict != AddVerdict.CREDIT) {
                    if (Debug.enabled) Debug.log("[scan] $verdict +$pickupAmount $key title=${screenTitle ?: "-"}")
                    if (verdict == AddVerdict.HOLD) addPendingIncrease(key, pickupAmount, now)
                    if (verdict == AddVerdict.JUST_CLOSED && excavatorJustClosed) {
                        pendingGains.addLast(InventoryGain(key, pickupAmount, now, null))
                    }
                    continue
                }

                pickupAmount = applyRemovalCredit(key, pickupAmount)
                if (pickupAmount <= 0) continue

                onItemPickedUp(key, pickupAmount, now)
            }

            flushPendingIncreases(now)
        } finally {
            // Always advance the baseline: if anything throws half way through, keeping the
            // old snapshot would make the next scan book the same removal a second time.
            previousInventory = currentInventory
            previousContainer = currentContainer
        }
    }

    private fun recordInventoryRemovals(
        previousInventory: Map<String, Int>,
        currentInventory: Map<String, Int>,
        verdict: RemovalVerdict,
        screenTitle: String?,
        leavingSackGui: Boolean,
        now: Long,
    ) {
        for ((key, previousTotal) in previousInventory) {
            val removed = previousTotal - (currentInventory[key] ?: 0)
            if (removed <= 0) continue
            val removedBought = nonLootDeliveries.removeInventory(key, removed, transferredToSack = verdict == RemovalVerdict.DEPOSIT)
            when (verdict) {
                RemovalVerdict.COST_AND_CREDIT -> {
                    if (removedBought > 0) {
                        val compacted = compactedInventoryInputs(key, previousInventory, currentInventory)
                        val possibleTransfer = removedBought - compacted
                        if (possibleTransfer > 0) {
                            nonLootDeliveries.recordSackTransfer(key, possibleTransfer.toInt(), now)
                            Debug.decision("[non-loot] possible sack transfer $key x$possibleTransfer")
                        }
                    }
                    addPendingRemoval(key, removed)
                    if (!CostLedger.isMiningConsumable(key) && CostLedger.isCostKey(key)) {
                        Debug.decision("[cost] saw $key -$removed -> checking cost")
                        CostLedger.onItemRemoved(key, removed)
                    }
                }
                RemovalVerdict.DEPOSIT -> {
                    SacksItemPickupPublisher.noteManualDeposit(key, removed)
                    val source = if (leavingSackGui) "leaving sack GUI" else screenTitle ?: "recent sack use or close"
                    Debug.decision("[dep] +$removed $key ($source)")
                }
                RemovalVerdict.IGNORE -> if (Debug.enabled) {
                    Debug.log("[menu] $key -$removed title=${screenTitle ?: "(none)"} consumed")
                }
            }
        }
    }

    private fun compactedInventoryInputs(
        key: String,
        previousInventory: Map<String, Int>,
        currentInventory: Map<String, Int>,
    ): Long {
        if (!ItemTable.isCompactionParent(key)) return 0L
        return currentInventory.entries.sumOf { (product, total) ->
            val gained = total - (previousInventory[product] ?: 0)
            val recipe = if (gained > 0) compactionRecipe(product) else null
            if (recipe?.first == key) gained.toLong() * recipe.second else 0L
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

    /** True for Hypixel's chat-style menus, whose contents never become inventory items. */
    private fun isMenuScreen(): Boolean {
        val title = GuiUtils.getCurrentChestName() ?: return false
        return MENU_SCREEN_NAMES.any { title.contains(it, ignoreCase = true) }
    }

    /**
     * The player took items out of a sack, which Hypixel reports as a negative delta. A sack is
     * storage, so this cancels the increase the scan just saw (or already booked).
     */
    /**
     * A withdrawal means the item left the sack and reappeared in the inventory. Its value was
     * already credited when it was first mined, so nothing may be added or removed here: we only
     * drop the in-flight pickup and the counts that the sacks screen already ignored.
     */
    /** How much of [amount] of [key] was taken out of a sack earlier and is only coming back. */
    fun takeReturn(key: String, amount: Int): Int =
        buffer.takeReturn(key, amount, System.currentTimeMillis())

    /** Raw units a compaction consumed without its product being reported yet. */
    fun noteCompactionInput(key: String, amount: Int) {
        buffer.noteCompactionInput(key, amount, System.currentTimeMillis())
    }

    fun reconcileSackCompaction(key: String, products: Int, ratio: Int, removed: Int): CaptureBuffer.SackCompaction =
        buffer.reconcileSackCompaction(key, products, ratio, removed, System.currentTimeMillis())

    /**
     * A stack that left the sack may already have been booked as loot a moment earlier: taking it
     * out puts it in the inventory, the scan sees `+N`, and the `[Sacks] -N` line arrives after.
     * Undoing that credit keeps "took it out and put it in a chest" from inflating the session.
     */
    private val recentCredits = ArrayDeque<BucketLedger.ItemCredit>()
    private const val UNDO_WINDOW_MS = 15_000L

    private fun undoRecentCredit(key: String, amount: Long, bucket: BucketData): Long {
        pruneRecentCredits(System.currentTimeMillis())
        var remaining = amount
        while (remaining > 0L) {
            val index = recentCredits.indexOfLast { it.bucket === bucket && it.key == key && it.remaining > 0L }
            if (index < 0) break
            val credit = recentCredits[index]
            remaining -= BucketLedger.reverseCredit(credit, remaining)
            if (credit.remaining == 0L) recentCredits.removeAt(index)
        }
        val undone = amount - remaining
        if (undone > 0L) Debug.decision("[sacks] undo +$undone $key (withdrawn right after it was credited)")
        return undone
    }

    private fun pruneRecentCredits(now: Long) {
        while (recentCredits.isNotEmpty() && now - recentCredits.first().atMs > UNDO_WINDOW_MS) {
            recentCredits.removeFirst()
        }
    }

    fun noteWithdrawal(key: String, amount: Int, bucket: BucketData) {
        if (key.isBlank() || amount <= 0) return

        val explained = buffer.takeSkipped(key, amount)
        if (explained > 0 && Debug.enabled) {
            Debug.log("[withdraw] $key -$explained was already ignored while the sacks screen was open")
        }

        val held = buffer.cancelHold(key, amount - explained)
        val cancelled = held + cancelPendingGain(key, amount - explained - held)
        if (cancelled > 0) {
            if (Debug.enabled) Debug.log("[withdraw] $key -$cancelled cancelled a held pickup")
        }
        val unexplained = amount - explained - cancelled
        val undone = undoRecentCredit(key, unexplained.toLong(), bucket).toInt()
        val remaining = unexplained - undone
        val now = System.currentTimeMillis()
        // Stacks that were never booked can come back in a later sack message.
        if (amount > undone) buffer.noteReturn(key, amount - undone, now)
        buffer.notePendingWithdrawal(key, remaining, now)
        if (remaining > 0 && Debug.enabled) {
            Debug.log("[withdraw] $key -$remaining awaiting a matching inventory increase")
        }
    }

    private fun cancelPendingGain(key: String, amount: Int): Int {
        if (amount <= 0) return 0
        var remaining = amount
        val gains = pendingGains.iterator()
        while (remaining > 0 && gains.hasNext()) {
            val gain = gains.next()
            if (gain.key != key) continue
            val used = minOf(remaining, gain.amount)
            remaining -= used
            gain.amount -= used
            if (gain.amount == 0) gains.remove()
        }
        return amount - remaining
    }

    private fun addPendingIncrease(key: String, amount: Int, now: Long) {
        buffer.hold(key, amount, now)
        if (Debug.enabled) Debug.log("[hold] $key +$amount, waiting for the container to catch up")
    }

    private fun flushPendingIncreases(now: Long) {
        for ((key, amount) in buffer.dueHolds(now)) {
            val credited = applyRemovalCredit(key, amount)
            if (credited <= 0) continue
            if (Debug.enabled) Debug.log("[hold] flush $key +$credited after ${PENDING_INCREASE_MS}ms, no container decrease")
            onItemPickedUp(key, credited, now)
        }
    }

    private fun addPendingRemoval(key: String, amount: Int) {
        buffer.addRemoval(key, amount, System.currentTimeMillis())
    }

    /**
     * Consumes pending removals that explain part (or all) of [amount]: the same item leaving and
     * coming back, or a compactor turning 160 ore into one block.
     */
    private fun applyRemovalCredit(key: String, amount: Int): Int =
        buffer.takeCredit(key, amount, System.currentTimeMillis(), ::compactionRecipe)

    private fun compactionRecipe(product: String): Pair<String, Int>? {
        val entry = ItemTable.getByKey(product) ?: return null
        if (entry.compactRatio <= 0) return null
        val parentName = entry.compactsFrom ?: return null
        val parentKey = ItemTable.get(parentName)?.key ?: return null
        return parentKey to entry.compactRatio
    }

    private fun pickupTarget(at: Long): ActivityBucket.PickupTarget? =
        if (ActivityBucket.bucketForPickup() == null) null else ActivityBucket.pickupTargetAt(at)

    private fun onItemPickedUp(key: String, amount: Int, observedAt: Long) {
        Debug.trace("[pick] +$amount $key (scan)")
        // A supercrafted stack coming back out of the sacks is stock the player already owned.
        val displayName = ItemTable.getByKey(key)?.name
        if (displayName != null && SupercraftGuard.guardsInventory(displayName)) return

        val target = if (shouldSkipPickup()) null else pickupTarget(observedAt)
        pendingGains.addLast(InventoryGain(key, amount, observedAt, target))
    }

    internal fun flushInventoryGains(now: Long) {
        while (true) {
            val gain = pendingGains.firstOrNull() ?: break
            if (now - gain.at < NonLootDeliveries.INVENTORY_WAIT_MS) break
            pendingGains.removeFirst()
            val withdrawn = buffer.takePendingWithdrawal(gain.key, gain.amount, now)
            val available = gain.amount - withdrawn
            val excluded = nonLootDeliveries.takeInventory(gain.key, available, gain.at)
            if (excluded > 0) Debug.decision("[non-loot] inventory ${gain.key} -$excluded")
            val target = gain.target ?: continue
            val remaining = available - excluded
            if (remaining <= 0 || !ActivityBucket.isPickupAllowed()) continue
            val credit = BucketLedger.add(target.activity, gain.key, remaining.toLong(), target.slice, gain.at) ?: continue
            ActivityBucket.onWeakSignal(target.activity, gain.at)
            pruneRecentCredits(credit.atMs)
            recentCredits.addLast(credit)
        }
    }

    private fun shouldSkipPickup(): Boolean {
        val now = System.currentTimeMillis()
        val chestName = GuiUtils.getCurrentChestName()

        if (chestName != null) {
            if (isAuctionChestName(chestName)) return true
            if (GuiUtils.isInSacksGui()) return true
            if (chestName == "Pets" || chestName.contains("Hunting Box")) return true
        }

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

        val formattedName = stack.hoverName.getFormattedString()
        var name = ItemUtils.getCleanItemName(formattedName)
        if (name.isBlank()) return null

        if (name == "Enchanted Book") {
            val bookName = ItemUtils.getEnchantedBookName(stack)
            if (!bookName.isNullOrBlank()) name += " ($bookName)"
        } else if (name.startsWith("[Lvl ")) {
            val tier = getPetTier(stack)
            if (tier != null) name += " (${tier.uppercase()})"
        }

        val byName = ItemTable.get(name)
        if (byName != null) {
            ItemColors.remember(byName.key, ItemUtils.getRarityPrefix(stack, formattedName))
            return byName
        }

        // Some items display under a name the table does not know (essences are the reason this
        // exists) while carrying a custom data id that IS the price key. Try that before giving up,
        // and remember what we saw so a miss is diagnosable instead of silent.
        val customId = ItemUtils.getCustomData(stack)
            ?.let { ItemUtils.getCustomDataId(it) }
            ?.takeIf { it.isNotBlank() }
        val byId = customId?.let { ItemTable.getByKey(it) }
        if (byId != null) {
            ItemColors.remember(byId.key, ItemUtils.getRarityPrefix(stack, formattedName))
            return byId
        }

        if (unseenNames.add(name) && unseenLogged < UNSEEN_LOG_LIMIT &&
            GuiUtils.getCurrentChestName() == null
        ) {
            unseenLogged++
            Debug.decision("[scan] unknown item '$name' id=${customId ?: "-"}")
        }
        return null
    }

    private fun getPetTier(stack: ItemStack): String? {
        val customData = ItemUtils.getCustomData(stack) ?: return null
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
        lastScreenOpen = false
        wasInSackGui = false
        lastSackScreenClosedMs = 0L
        lastHandSackUseMs = 0L
        isInventoryLoaded = false
        lastFingerprint = null
        fingerprintStableSince = null
        buffer.clear()
        if (Debug.verbose && hasPendingConsumableUses()) {
            Debug.trace("[mining use] pending slots cleared on inventory reset: ${miningConsumableUses.slots()}")
        }
        miningConsumableUses.clear()
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
