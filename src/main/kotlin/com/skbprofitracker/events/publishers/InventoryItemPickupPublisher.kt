package com.skbprofitracker.events.publishers

import com.google.gson.JsonParser
import com.skbprofitracker.SkbMod
import com.skbprofitracker.capture.ActivityKind
import com.skbprofitracker.capture.CaptureBuffer
import com.skbprofitracker.capture.AddVerdict
import com.skbprofitracker.capture.RemovalVerdict
import com.skbprofitracker.capture.ScanRules
import com.skbprofitracker.capture.ScreenKind
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.events.models.GuiClosedEvent
import com.skbprofitracker.events.models.WorldChangedEvent
import com.skbprofitracker.items.ItemColors
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.tracker.Activity
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.CostLedger
import com.skbprofitracker.tracker.PurchaseGuard
import com.skbprofitracker.tracker.SackShadow
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
    private const val TICKS_INVENTORY_SCAN = 4
    private const val INVENTORY_STABLE_MS = 3_500L
    private const val REMOVAL_CREDIT_MS = 10_000L
    private const val PENDING_INCREASE_MS = 1_000L

    // Every uncertain move (held pickups, removal credits, ignored withdrawals) lives here so it
    // expires in one place and can be replayed with fake time in tools/sim.
    private val buffer = CaptureBuffer(PENDING_INCREASE_MS, REMOVAL_CREDIT_MS)

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
    /** Item names the table did not know about, so a miss is logged once, not 5x a second. */
    private val unseenNames = mutableSetOf<String>()

    /** Gear churn can produce hundreds of one-off names; a sample is enough to notice gaps. */
    private var unseenLogged = 0
    private const val UNSEEN_LOG_LIMIT = 40
    private var lastScreenOpen = false
    private var sackScreenOpen = false
    private var lastSackScreenClosedMs = 0L

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
    private val AUCTION_PURCHASED = Regex("^You purchased (.+) for ([\\d,]+) coins!$")
    private val AUCTION_CLAIMED = Regex("^You claimed (.+) from .+'s auction!$")
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


    private var lastBazaarGuiClosedMs = 0L
    private var lastBazaarMessageMs = 0L
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

    private fun detectInventoryChanges() {
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

        val previousInv = previousInventory
        if (previousInv == null) {
            previousInventory = currentInventory
            previousContainer = currentContainer
            return
        }

        val screenOpen = SkbMod.mc.getScreenCompat() != null
        if (screenOpen != lastScreenOpen) {
            val closingSackScreen = !screenOpen && sackScreenOpen
            lastScreenOpen = screenOpen
            if (!screenOpen) {
                lastScreenClosedMs = now
                if (closingSackScreen) lastSackScreenClosedMs = now
            }
            sackScreenOpen = screenOpen && GuiUtils.isInSacksGui()

            // The last click of a deposit often lands between two scans, right before the sack
            // screen closes. Book those moves before rebuilding the baseline, otherwise the
            // matching `[Sacks] +N` line has nothing to net against and counts as fresh loot.
            if (closingSackScreen) {
                for ((key, previousTotal) in previousInv) {
                    val currentTotal = currentInventory[key] ?: 0
                    if (currentTotal < previousTotal) {
                        val moved = previousTotal - currentTotal
                        SacksItemPickupPublisher.noteManualDeposit(key, moved)
                        Debug.trace("[dep] +$moved $key (screen closing)")
                    }
                }
            }

            previousInventory = currentInventory
            previousContainer = currentContainer
            return
        }

        if (isConversionScreen() || isMenuScreen()) {
            previousInventory = currentInventory
            previousContainer = currentContainer
            return
        }

        val screenTitle = GuiUtils.getCurrentChestName()

        val previousCont = previousContainer ?: emptyMap()
        val containerOpen = screenTitle != null

        val anyScreenOpen = SkbMod.mc.getScreenCompat() != null

        try {
            val screenKind = when {
                GuiUtils.isInSacksGui() -> ScreenKind.SACK
                containerOpen -> ScreenKind.CONTAINER
                anyScreenOpen -> ScreenKind.CUSTOM_MENU
                else -> ScreenKind.NONE
            }
            val removalVerdict = ScanRules.removalVerdict(screenKind)
            for ((key, previousTotal) in previousInv) {
                val currentTotal = currentInventory[key] ?: 0
                if (currentTotal >= previousTotal) continue
                val removed = previousTotal - currentTotal
                when (removalVerdict) {
                    RemovalVerdict.COST_AND_CREDIT -> {
                        addPendingRemoval(key, removed)
                        CostLedger.onItemRemoved(key, removed)
                    }
                    RemovalVerdict.DEPOSIT -> {
                        SacksItemPickupPublisher.noteManualDeposit(key, removed)
                        if (Debug.enabled) Debug.log("[menu] $key -$removed title=${screenTitle ?: "-"}")
                    }
                    RemovalVerdict.IGNORE -> if (Debug.enabled) {
                        Debug.log("[menu] $key -$removed title=${screenTitle ?: "(none)"} consumed")
                    }
                }
            }

            // A container that lost this key explains part of the increase, possibly one scan late.
            for ((key, previousCount) in previousCont) {
                val lost = previousCount - (currentContainer[key] ?: 0)
                if (lost <= 0) continue
                consumePendingIncrease(key, lost, now)
            }

            for ((key, currentTotal) in currentInventory) {
                val previousTotal = previousInv[key] ?: 0
                if (currentTotal <= previousTotal) continue

                val increase = currentTotal - previousTotal
                val containerDecrease = (previousCont[key] ?: 0) - (currentContainer[key] ?: 0)
                val movedBetweenInventoryAndContainer = ScanRules.containerExplained(increase, containerDecrease)
                var pickupAmount = increase - movedBetweenInventoryAndContainer
                if (pickupAmount <= 0) continue

                val verdict = ScanRules.addVerdict(
                    activity = if (ActivityBucket.current == Activity.MINING) ActivityKind.MINING else ActivityKind.SLAYER,
                    screenOpen = screenOpen,
                    sackScreenOpen = GuiUtils.isInSacksGui(),
                    msSinceScreenClosed = now - lastScreenClosedMs,
                    msSinceSackClosed = now - lastSackScreenClosedMs,
                    screenCloseGraceMs = SCREEN_CLOSE_GRACE_MS,
                    sackCooldownMs = SACK_SCREEN_COOLDOWN_MS,
                )
                if (verdict == AddVerdict.WITHDRAWAL) buffer.noteSkipped(key, pickupAmount)
                if (verdict != AddVerdict.CREDIT) {
                    if (Debug.enabled) Debug.log("[scan] $verdict +$pickupAmount $key title=${screenTitle ?: "-"}")
                    if (verdict == AddVerdict.HOLD) addPendingIncrease(key, pickupAmount, now)
                    continue
                }

                pickupAmount = applyRemovalCredit(key, pickupAmount)
                if (pickupAmount <= 0) continue

                onItemPickedUp(key, pickupAmount)
            }

            flushPendingIncreases(now)
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

    fun takeCompactionInput(key: String, amount: Int): Int =
        buffer.takeCompactionInput(key, amount, System.currentTimeMillis())

    /**
     * A stack that left the sack may already have been booked as loot a moment earlier: taking it
     * out puts it in the inventory, the scan sees `+N`, and the `[Sacks] -N` line arrives after.
     * Undoing that credit keeps "took it out and put it in a chest" from inflating the session.
     */
    private data class RecentCredit(val amount: Long, val atMs: Long, val activity: Activity)

    private val recentCredits = HashMap<String, RecentCredit>()
    private val UNDO_WINDOW_MS = 15_000L

    private fun undoRecentCredit(key: String, amount: Long): Long {
        val credit = recentCredits[key] ?: return 0L
        if (System.currentTimeMillis() - credit.atMs > UNDO_WINDOW_MS) {
            recentCredits.remove(key)
            return 0L
        }
        val take = minOf(credit.amount, amount)
        if (take <= 0L) return 0L

        BucketLedger.subtract(credit.activity, key, take)
        if (credit.amount - take <= 0L) {
            recentCredits.remove(key)
        } else {
            recentCredits[key] = credit.copy(amount = credit.amount - take)
        }
        Debug.decision("[sacks] undo +$take $key (withdrawn right after it was credited)")
        return take
    }

    fun noteWithdrawal(key: String, amount: Int) {
        if (key.isBlank() || amount <= 0) return

        val undone = undoRecentCredit(key, amount.toLong())
        val afterUndo = amount - undone.toInt()
        if (afterUndo <= 0) return

        val now = System.currentTimeMillis()
        // Whatever left the sack may be put back later; remember it so the matching `[Sacks] +N`
        // line nets to zero instead of counting as freshly mined loot.
        buffer.noteReturn(key, afterUndo, now)

        val explained = buffer.takeSkipped(key, afterUndo)
        if (explained > 0 && Debug.enabled) {
            Debug.log("[withdraw] $key -$explained was already ignored while the sacks screen was open")
        }

        var remaining = amount - explained
        if (remaining <= 0) return

        val cancelled = buffer.cancelHold(key, remaining)
        if (cancelled > 0) {
            if (Debug.enabled) Debug.log("[withdraw] $key -$cancelled cancelled a held pickup")
            remaining -= cancelled
        }
        if (remaining > 0 && Debug.enabled) {
            Debug.log("[withdraw] $key -$remaining had no matching pickup, ignored")
        }
    }

    private fun addPendingIncrease(key: String, amount: Int, now: Long) {
        buffer.hold(key, amount, now)
        if (Debug.enabled) Debug.log("[hold] $key +$amount, waiting for the container to catch up")
    }

    private fun consumePendingIncrease(key: String, amount: Int, now: Long) {
        buffer.explain(key, amount)
    }

    private fun flushPendingIncreases(now: Long) {
        for ((key, amount) in buffer.dueHolds(now)) {
            val credited = applyRemovalCredit(key, amount)
            if (credited <= 0) continue
            if (Debug.enabled) Debug.log("[hold] flush $key +$credited after ${PENDING_INCREASE_MS}ms, no container decrease")
            onItemPickedUp(key, credited)
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
        buffer.takeCredit(key, amount, System.currentTimeMillis()) { compacted ->
            val entry = ItemTable.getByKey(compacted) ?: return@takeCredit null
            if (entry.compactRatio <= 0) return@takeCredit null
            val parentName = entry.compactsFrom ?: return@takeCredit null
            val parentKey = ItemTable.get(parentName)?.key ?: return@takeCredit null
            parentKey to entry.compactRatio
        }

    private fun onItemPickedUp(key: String, amount: Int) {
        Debug.trace("[pick] +$amount $key (scan)")
        if (shouldSkipPickup()) return

        // A supercrafted stack coming back out of the sacks is stock the player already owned.
        val displayName = ItemTable.getByKey(key)?.name
        if (displayName != null && SupercraftGuard.guardsInventory(displayName)) return

        if (PurchaseGuard.shouldSkip(key)) return

        // The sack line already credited this key a moment ago.
        if (SackShadow.isRecent(key)) {
            Debug.decision("[scan] skip $key +$amount (already credited by the sack line)")
            return
        }

        val activity = ActivityBucket.bucketForPickup() ?: return
        ActivityBucket.onWeakSignal()

        BucketLedger.add(activity, key, amount.toLong())
        recentCredits[key] = RecentCredit(amount.toLong(), System.currentTimeMillis(), activity)
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

        val byName = ItemTable.get(name)
        if (byName != null) {
            ItemColors.remember(byName.key, ItemUtils.getRarityPrefix(stack))
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
            ItemColors.remember(byId.key, ItemUtils.getRarityPrefix(stack))
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
        buffer.clear()
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
