package com.skbprofitracker.events.publishers

import com.skbprofitracker.SkbMod
import com.skbprofitracker.capture.SlayerCoinFlow
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.events.models.GuiClosedEvent
import com.skbprofitracker.events.models.WorldChangedEvent
import com.skbprofitracker.tracker.Activity
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.utils.ChatUtils.removeFormatting
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.GuiUtils
import com.skbprofitracker.utils.WorldUtils
import com.skbprofitracker.utils.getScreenCompat
import java.math.BigDecimal

/** Tracks the net coin cost of Slayer, and wallet gains from combat in other tracked activities. */
object PurseWatcher {
    private const val QUEST_STARTED = "SLAYER QUEST STARTED!"
    private const val QUEST_COMPLETE = "SLAYER QUEST COMPLETE!"
    private const val QUEST_FAILED = "SLAYER QUEST FAILED!"
    private val questCancelled = Regex("^(?:SLAYER QUEST CANCEL(?:L)?ED!|You (?:have )?cancel(?:l)?ed your Slayer Quest[.!])$", RegexOption.IGNORE_CASE)
    private const val RIFT_SLAYER = "Riftstalker Bloodfiend"
    private val MAX_SINGLE_GAIN = BigDecimal("1000000")
    private val commerceTitles = listOf("Bazaar", "Auction", "Bank", "Trade", "Order options", "Instant Buy")

    private val slayer = SlayerCoinFlow()
    private var lastPurse: BigDecimal? = null
    private var lastExternalMovement = 0L
    private var lastSlayerScreenAt = 0L

    private val externalCoinMessage = Regex(
        "^(?:\\[Bazaar]|\\[Auction]|\\[Bank]|(?:You (?:have )?)?(?:deposited|withdrew|purchased|sold)\\b|You claimed\\b.*\\b(?:coins|auction)\\b|You (?:died|were killed)\\b|You lost [\\d,.]+ coins\\b)",
        RegexOption.IGNORE_CASE,
    )

    fun init() {
        EventBus.subscribe(ClientTickEvent::class, ::onClientTick)
        EventBus.subscribe(WorldChangedEvent::class) { clear() }
        EventBus.subscribe(GuiClosedEvent::class) {
            if (isCommerceScreen(it.guiName)) markExternalMovement(System.currentTimeMillis())
        }
        EventBus.subscribe(ChatEvent::class) {
            if (it.unformattedText.contains("You uncovered a treasure chest!")) ActivityBucket.onTreasureChestOpened()
        }
    }

    /** Receives server chat directly so local copies of a bank notice cannot count as a payment. */
    fun onServerChat(message: String) {
        if (!WorldUtils.isInSkyblock() || ActivityBucket.isPaused || ActivityBucket.current == Activity.RIFT) return
        val text = message.removeFormatting().trim()
        val now = System.currentTimeMillis()
        val bank = SlayerCoinFlow.readBankCharge(text)
        when {
            bank != null -> slayer.bankCharge(bank, now)
            text == QUEST_STARTED -> slayer.start(now)
            text == QUEST_COMPLETE -> {
                if (slayer.questEnded()) ActivityBucket.onBossSlain()
            }
            text == QUEST_FAILED || questCancelled.matches(text) -> slayer.questEnded()
            externalCoinMessage.containsMatchIn(text) -> markExternalMovement(now)
        }
    }

    private fun onClientTick(event: ClientTickEvent) {
        if (!WorldUtils.isInSkyblock()) {
            clear()
            return
        }
        val now = System.currentTimeMillis()
        if (GuiUtils.getCurrentChestName()?.let(::isSlayerScreen) == true) lastSlayerScreenAt = now
        observeWallet(event.scoreboardLines, now)
        if (ActivityBucket.isPaused || ActivityBucket.current == Activity.RIFT) {
            slayer.clear()
            return
        }
        ActivityBucket.slayerTypeName?.takeUnless { it == RIFT_SLAYER }?.let(slayer::observeSlice)
        slayer.settle(now) { settlement ->
            val charge = settlement.charge
            BucketLedger.recordSlayerCoinCost(settlement.slice, charge.coins.toDouble(), charge.estimated, settlement.startedAt)
        }
    }

    private fun observeWallet(lines: List<String>, now: Long) {
        val previous = lastPurse
        val purse = lines.firstNotNullOfOrNull(SlayerCoinFlow::readPurse)
        lastPurse = purse
        if (purse == null || previous == null || ActivityBucket.isPaused) return
        val delta = purse - previous
        if (delta.signum() == 0) return

        val title = GuiUtils.getCurrentChestName()
        if (FossilExcavation.isOpen(title)) return
        if (title != null && isCommerceScreen(title)) {
            markExternalMovement(now)
            return
        }
        if (now - lastExternalMovement <= SlayerCoinFlow.WINDOW_MS) return

        if (delta.signum() < 0) {
            // Manual starts may debit the wallet while the Slayer menu is still open.
            val fromSlayerMenu = title != null && (isSlayerScreen(title) ||
                (title.equals("Confirm", ignoreCase = true) && now - lastSlayerScreenAt <= SlayerCoinFlow.WINDOW_MS))
            if (ActivityBucket.current != Activity.RIFT &&
                (SkbMod.mc.getScreenCompat() == null || fromSlayerMenu)
            ) {
                slayer.walletDebit(delta.negate(), now)
            }
            return
        }

        val activity = ActivityBucket.bucketForPickup() ?: return
        if (SkbMod.mc.getScreenCompat() != null || delta > MAX_SINGLE_GAIN || !ActivityBucket.attackedRecently()) return
        if (activity == Activity.SLAYER) {
            val slice = ActivityBucket.slayerPickupSlice ?: return
            BucketLedger.recordSlayerCoinCost(slice, -delta.toDouble(), estimated = false, receivedAtMs = now)
        } else {
            BucketLedger.add(activity, BucketLedger.COINS_KEY, delta.toLong())
        }
        if (Debug.verbose) Debug.trace("[purse] combat gain $delta -> ${activity.bucketKey}")
    }

    private fun isSlayerScreen(title: String): Boolean =
        title.contains("Slayer", ignoreCase = true) || title.contains("Maddox", ignoreCase = true) ||
            ActivityBucket.slayerTypeNames.any { title.contains(it, ignoreCase = true) }

    private fun isCommerceScreen(title: String): Boolean =
        commerceTitles.any { title.contains(it, ignoreCase = true) }

    private fun markExternalMovement(now: Long) {
        lastExternalMovement = now
        slayer.discardRecentWalletDebits(now)
    }

    private fun clear() {
        lastPurse = null
        lastExternalMovement = 0L
        lastSlayerScreenAt = 0L
        slayer.clear()
    }
}
