import com.skbprofitracker.capture.ActivityKind
import com.skbprofitracker.capture.CaptureBuffer
import com.skbprofitracker.capture.AddVerdict
import com.skbprofitracker.capture.EssenceDeduper
import com.skbprofitracker.capture.ExcavationRewardBlock
import com.skbprofitracker.capture.MiningConsumableUses
import com.skbprofitracker.capture.NonLootDeliveries
import com.skbprofitracker.capture.PurchaseDelivery
import com.skbprofitracker.capture.RemovalVerdict
import com.skbprofitracker.capture.SackDeposits
import com.skbprofitracker.capture.SlayerCoinFlow
import com.skbprofitracker.capture.ScanRules
import com.skbprofitracker.capture.ScreenKind
import com.skbprofitracker.tracker.ActivitySignals
import com.skbprofitracker.tracker.CrystalHollowsGate
import com.skbprofitracker.tracker.GlaciteTravel
import com.skbprofitracker.tracker.ResetConfirmation
import com.skbprofitracker.tracker.ResetConfirmation.Method
import com.skbprofitracker.utils.enums.Alignment
import com.skbprofitracker.utils.gui.OverlayHitbox
import com.skbprofitracker.utils.gui.RowViewport
import com.skbprofitracker.utils.gui.alignedRowLeft

/**
 * Replays the scanner's pure rules with fake data, so the riskiest decisions can be checked
 * without launching the game. Run with tools/sim/run.sh.
 */
private var failures = 0
private var checks = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual == expected) {
        println("  ok   $name")
    } else {
        failures++
        println("  FAIL $name  expected=$expected actual=$actual")
    }
}

private fun verdict(
    activity: ActivityKind,
    screenOpen: Boolean = false,
    sackOpen: Boolean = false,
    msSinceScreenClosed: Long = 99_999,
    msSinceSackClosed: Long = 99_999,
) = ScanRules.addVerdict(
    activity = activity,
    screenOpen = screenOpen,
    sackScreenOpen = sackOpen,
    msSinceScreenClosed = msSinceScreenClosed,
    msSinceSackClosed = msSinceSackClosed,
    screenCloseGraceMs = 1_500L,
    sackCooldownMs = 5_000L,
)

fun main() {
    println("Slayer net coin costs")
    run {
        val flow = SlayerCoinFlow()
        val settled = mutableListOf<SlayerCoinFlow.Settlement>()
        val slice = "Revenant Horror"
        val bank = SlayerCoinFlow.readBankCharge("Took 45.6k coins from your bank for auto-slayer...")!!
        check("bank text preserves the rounded amount", bank, SlayerCoinFlow.Charge("45600".toBigDecimal(), true))
        check("unabbreviated bank fees are exact", SlayerCoinFlow.readBankCharge("Took 45,602 coins from your bank for auto-slayer..."), SlayerCoinFlow.Charge("45602".toBigDecimal()))
        check("a wallet line includes decimal balances and gain annotations", SlayerCoinFlow.readPurse("Purse: 54,600.25 (+56)"), "54600.25".toBigDecimal())
        check("wallet coalescing preserves the net cost", SlayerCoinFlow.readPurse("Piggy: 100,000")!! - SlayerCoinFlow.readPurse("Piggy: 54,600")!!, "45400".toBigDecimal())

        flow.walletDebit("45400".toBigDecimal(), 3_000L)
        flow.start(3_100L)
        flow.start(3_140L)
        flow.observeSlice(slice)
        flow.settle(5_650L, settled::add)
        check("a wallet debit before a start is recorded once", settled.map { it.charge }, listOf(SlayerCoinFlow.Charge("45400".toBigDecimal())))
        check("settlement carries the original slice and start time", settled.single().slice to settled.single().startedAt, slice to 3_100L)

        flow.questEnded()
        flow.bankCharge(bank, 6_000L)
        flow.bankCharge(bank, 6_010L)
        flow.start(6_100L)
        flow.observeSlice(slice)
        flow.walletDebit("20".toBigDecimal(), 6_120L)
        flow.settle(8_700L, settled::add)
        check("bank confirmation takes precedence over a coincident debit", settled.last().charge, bank)
        check("combat income reduces bank fees without changing the bank amount", settled.last().charge.coins - "200".toBigDecimal(), "45400".toBigDecimal())

        flow.questEnded()
        flow.walletDebit("600".toBigDecimal(), 9_000L)
        flow.discardRecentWalletDebits(9_100L)
        flow.start(9_200L)
        flow.observeSlice(slice)
        flow.settle(11_800L, settled::add)
        check("disqualified wallet movement is not a summons fee", settled.size, 2)
        flow.walletDebit("100".toBigDecimal(), 12_000L)
        flow.settle(15_000L, settled::add)
        check("wallet movement without a successful start is never a fee", settled.size, 2)

        flow.questEnded()
        flow.start(16_000L)
        flow.observeSlice(slice)
        flow.walletDebit("1000".toBigDecimal(), 16_050L)
        flow.questEnded()
        flow.bankCharge(bank, 18_450L)
        flow.settle(18_500L, settled::add)
        flow.start(18_510L)
        flow.observeSlice("Inferno Demonlord")
        flow.settle(21_020L, settled::add)
        check("a bank notice after completion belongs to the next quest", settled.takeLast(2).map { it.slice to it.charge.coins }, listOf(slice to "1000".toBigDecimal(), "Inferno Demonlord" to bank.coins))
    }
    run {
        val flow = SlayerCoinFlow()
        val settled = mutableListOf<SlayerCoinFlow.Settlement>()
        flow.start(10_000L)
        flow.observeSlice("Revenant Horror")
        flow.walletDebit("45400".toBigDecimal(), 10_100L)
        flow.questEnded()
        flow.walletDebit("44900".toBigDecimal(), 12_450L)
        flow.settle(12_500L, settled::add)
        flow.start(12_510L)
        flow.observeSlice("Revenant Horror")
        flow.settle(15_020L, settled::add)
        check("back-to-back wallet debits before the next start keep both fees", settled.map { it.charge.coins }, listOf("45400".toBigDecimal(), "44900".toBigDecimal()))
    }
    run {
        val flow = SlayerCoinFlow()
        val settled = mutableListOf<SlayerCoinFlow.Settlement>()
        val bank = SlayerCoinFlow.Charge("45600".toBigDecimal(), estimated = true)
        check("attaching during an already active quest accepts its completion", flow.questEnded(), true)
        flow.bankCharge(bank, 10_010L)
        check("duplicate completion does not count another boss", flow.questEnded(), false)
        flow.start(10_030L)
        flow.observeSlice("Revenant Horror")
        check("a distinct boss can complete inside three seconds", flow.questEnded(), true)
        flow.settle(12_530L, settled::add)
        check("a repeated completion cannot detach the next bank fee", settled.map { it.charge }, listOf(bank))
    }

    println("overlay controls")
    val hitbox = OverlayHitbox(20, 40, 100, 9, 0.5f, Alignment.RIGHT)
    val buttonLeft = alignedRowLeft(hitbox.alignment, hitbox.left, hitbox.width, 24)
    check("right-aligned button starts at its text", buttonLeft, 96)
    check("blank section beside short button misses", hitbox.containsX(47.9, buttonLeft, 24), false)
    check("first pixel of scaled button hits", hitbox.containsX(48.0, buttonLeft, 24), true)
    check("right edge of scaled button is excluded", hitbox.containsX(60.0, buttonLeft, 24), false)
    check("scaled hover resolves the second row", hitbox.rowAt(48.0, 25.5, 2), 1)
    check("scaled row padding cannot be clicked", hitbox.isOverText(24.5, 0), false)
    check("left-aligned row starts at the panel edge", alignedRowLeft(Alignment.LEFT, 20, 100, 24), 20)
    check("centered row has matching offset", alignedRowLeft(Alignment.CENTER, 20, 100, 24), 58)

    val resets = ResetConfirmation<String>()
    val miningReset = resets.request("Mining / Session", Method.OVERLAY_SESSION, 1_000L)
    check("session reset requires a second click", miningReset != null, true)
    check("a session button does not accept a chat token", resets.confirm(miningReset, 1_100L), null)
    check("switching slice needs a new confirmation", resets.request("Corpse / Session", Method.OVERLAY_SESSION, 1_200L) != miningReset, true)
    check("the same session target confirms on repeat", resets.request("Corpse / Session", Method.OVERLAY_SESSION, 1_300L), null)
    val corpseTotal = resets.request("Corpse / Total", Method.OVERLAY_TOTAL, 2_000L)
    check("repeating the Total button only shows its confirmation", resets.request("Corpse / Total", Method.OVERLAY_TOTAL, 2_100L), corpseTotal)
    val allTotal = resets.request("All / Total", Method.OVERLAY_TOTAL, 2_200L)
    check("old links cannot confirm a newer reset", resets.confirm(corpseTotal, 2_300L), null)
    check("chat confirmation returns its original scope", resets.confirm(allTotal, 2_400L), "All / Total")
    val expired = resets.request("Mining / Total", Method.OVERLAY_TOTAL, 0L)
    check("expired confirmation leaves the ledger alone", resets.confirm(expired, 15_001L), null)

    println("Glacite mining travel")
    val fromCamp = GlaciteTravel.Transfer(GlaciteTravel.Region.DWARVEN_MINES, 30_000L, 29_500L, 20_000L, "test", 60_000L)
    val fromShaft = GlaciteTravel.Transfer(GlaciteTravel.Region.MINESHAFT, 59_000L, 59_000L, 1_000L, "test", 60_000L)
    check("unknown destination waits", fromCamp.arrival("", 31_000L), GlaciteTravel.Arrival.WAITING)
    check("cached departure world waits", fromCamp.arrival("Dwarven Mines", 31_000L), GlaciteTravel.Arrival.WAITING)
    check("Camp to Mineshaft carries the window", fromCamp.arrival("Mineshaft", 32_000L), GlaciteTravel.Arrival.CONFIRMED)
    check("Mineshaft to Camp works before the Camp zone is known", fromShaft.arrival("Dwarven Mines", 61_000L), GlaciteTravel.Arrival.CONFIRMED)
    check("warp to Hub cancels even during grace", fromCamp.arrival("Hub", 32_000L), GlaciteTravel.Arrival.CANCELLED)
    check("slow loading can be confirmed within the grace window", fromCamp.arrival("Mineshaft", 50_000L), GlaciteTravel.Arrival.CONFIRMED)
    check("a gap longer than the entire grace cancels the transfer", fromCamp.arrival("Mineshaft", 90_001L), GlaciteTravel.Arrival.CANCELLED)
    check("confirmed load accounts for missed whole seconds", fromCamp.secondsToCredit(32_000L), 2L)
    check("time during a transfer ends at the original grace deadline", fromShaft.secondsToCredit(64_000L), 2L)
    check("large jumps within Base Camp are on the route", GlaciteTravel.includes("Dwarven Mines", "Dwarven Base Camp"), true)
    check("large jumps within Glacite Tunnels are on the route", GlaciteTravel.includes("Dwarven Mines", "Glacite Tunnels"), true)
    check("Crystal Hollows is outside the Camp route", GlaciteTravel.includes("Crystal Hollows", "Crystal Nucleus"), false)

    println("scrollable rows")
    val viewport = RowViewport()
    viewport.resize(25, 10)
    check("long lists show ten rows", viewport.visibleCount, 10)
    check("long lists can scroll", viewport.canScroll, true)
    check("fractional input leaves the current row visible", viewport.scroll(-0.5), false)
    check("fractional input accumulates into a row", viewport.scroll(-0.5), true)
    check("wheel down advances the window", viewport.offset, 1)
    viewport.scroll(-100.0)
    check("scrolling stops at the last full window", viewport.offset, 15)
    check("scrolling beyond the end leaves the window in place", viewport.scroll(-1.0), false)
    viewport.scroll(2.0)
    check("wheel up moves toward the first row", viewport.offset, 13)
    viewport.resize(12, 10)
    check("shrinking data clamps the window", viewport.offset, 2)
    viewport.reset()
    check("switching views resets the scroll position", viewport.offset, 0)
    viewport.resize(3, 10)
    check("short lists display every row", viewport.visibleCount, 3)
    check("short lists do not need scrolling", viewport.canScroll, false)

    println("add verdicts")
    check("mining, nothing open -> credit", verdict(ActivityKind.MINING), AddVerdict.CREDIT)
    check("mining, sack open -> withdrawal", verdict(ActivityKind.MINING, screenOpen = true, sackOpen = true), AddVerdict.WITHDRAWAL)
    check("mining, 500ms after closing -> just closed", verdict(ActivityKind.MINING, msSinceScreenClosed = 500), AddVerdict.JUST_CLOSED)
    check("mining, 2s after sack -> post sack", verdict(ActivityKind.MINING, msSinceSackClosed = 2_000), AddVerdict.POST_SACK)
    check("mining, chest open -> menu ignore", verdict(ActivityKind.MINING, screenOpen = true), AddVerdict.MENU_IGNORE)
    check("slayer, chest open -> hold", verdict(ActivityKind.SLAYER, screenOpen = true), AddVerdict.HOLD)
    check("slayer, nothing open -> credit", verdict(ActivityKind.SLAYER), AddVerdict.CREDIT)
    check("mining, 6s after sack -> credit", verdict(ActivityKind.MINING, msSinceSackClosed = 6_000), AddVerdict.CREDIT)

    println("removal verdicts")
    check("no screen -> cost and credit", ScanRules.removalVerdict(ScreenKind.NONE), RemovalVerdict.COST_AND_CREDIT)
    check("sack screen -> deposit", ScanRules.removalVerdict(ScreenKind.SACK), RemovalVerdict.DEPOSIT)
    check("chest -> ignore", ScanRules.removalVerdict(ScreenKind.CONTAINER), RemovalVerdict.IGNORE)
    check("drill anvil -> ignore", ScanRules.removalVerdict(ScreenKind.CUSTOM_MENU), RemovalVerdict.IGNORE)
    check("crafting -> ignore", ScanRules.removalVerdict(ScreenKind.CONVERSION), RemovalVerdict.IGNORE)
    check("inventory clear 200ms after a sack closed -> deposit",
        ScanRules.removalVerdict(ScreenKind.NONE, msSinceSackClosed = 200), RemovalVerdict.DEPOSIT)
    check("sack close grace ends at 2 seconds",
        ScanRules.removalVerdict(ScreenKind.NONE, msSinceSackClosed = 2_000), RemovalVerdict.COST_AND_CREDIT)
    check("using a hand-held sack without a screen -> deposit",
        ScanRules.removalVerdict(ScreenKind.NONE, handSackUsedRecently = true), RemovalVerdict.DEPOSIT)
    check("sack grace does not treat subsequent forge consumption as a deposit",
        ScanRules.removalVerdict(ScreenKind.CONVERSION, msSinceSackClosed = 200, handSackUsedRecently = true), RemovalVerdict.IGNORE)

    println("activity signals")
    run {
        val signals = ActivitySignals()
        check("a first accepted contact arms the clock", signals.acceptsMiningContact(1L, 1_000L) && signals.canStart(1_000L), false)
        check("overlapping hooks on the same block are coalesced", signals.acceptsMiningContact(1L, 1_010L), false)
        check("sustained mining supplies a second signal", signals.acceptsMiningContact(1L, 1_250L) && signals.canStart(1_250L), true)
        check("reopening after inactivity needs a first signal again", signals.canStart(70_000L), false)
        check("a first signal more than three seconds ago expires", signals.canStart(73_001L), false)
        check("another block can qualify without waiting 250ms", signals.acceptsMiningContact(2L, 73_050L) && signals.canStart(73_050L), true)
        signals.clear()
        check("reset forgets the previous block", signals.acceptsMiningContact(2L, 73_100L), true)
    }

    println("mining consumable confirmation")
    run {
        val uses = MiningConsumableUses()
        val potato = "CHILLED_PRISTINE_POTATO"
        val filet = "FILET_O_FORTUNE"
        val flask = "FIESTA_FLASK"
        check("an untouched flask slot cannot produce a cost", uses.serverUpdate(2, null, 0, 50L), null)
        uses.attempt(0, potato, 1, 100L)
        uses.attempt(1, filet, 5, 110L)
        check("a correction restoring the potato confirms nothing", uses.serverUpdate(0, potato, 1, 300L), null)
        check("a removal in another slot is ignored", uses.serverUpdate(2, null, 0, 310L), null)
        check("one true potato use is charged", uses.serverUpdate(0, null, 0, 350L), potato to 1)
        check("repeat confirmation cannot charge twice", uses.serverUpdate(0, null, 0, 360L), null)
        check("one filet from a stack of five is charged once", uses.serverUpdate(1, filet, 4, 370L), filet to 1)
        uses.attempt(1, filet, 4, 380L)
        uses.attempt(1, filet, 3, 390L)
        check("two quick uses can be confirmed separately", uses.serverUpdate(1, filet, 3, 400L), filet to 1)
        check("a repeat packet keeps the remaining confirmation pending", uses.serverUpdate(1, filet, 3, 410L), null)
        check("the second quick use is also charged", uses.serverUpdate(1, filet, 2, 420L), filet to 1)
        uses.attempt(2, flask, 5, 430L)
        check("losing a whole stack after one attempt is ignored", uses.serverUpdate(2, null, 0, 440L), null)
        uses.attempt(2, flask, 1, 450L)
        check("a replacement item does not confirm a use", uses.serverUpdate(2, filet, 1, 460L), null)
        uses.attempt(2, flask, 1, 470L)
        check("a delayed slot confirmation still charges once", uses.serverUpdate(2, null, 0, 6_470L), flask to 1)
        uses.attempt(2, flask, 1, 6_500L)
        check("an expired attempt does not charge", uses.serverUpdate(2, null, 0, 14_500L), null)
        uses.attempt(0, potato, 1, 15_000L)
        uses.attempt(2, flask, 3, 15_000L)
        uses.attempt(2, flask, 2, 15_200L)
        uses.cancel(0)
        check("a manually moved item is never charged", uses.serverUpdate(0, null, 0, 16_000L), null)
        check("a merged update confirms two uses on another slot", uses.serverUpdate(2, flask, 1, 21_000L), flask to 2)
        uses.attempt(2, flask, 1, 21_100L)
        uses.clear()
        check("world reset discards unconfirmed attempts", uses.serverUpdate(2, null, 0, 21_200L), null)
    }

    println("container explanation")
    check("chest moved 10 -> explained 10", ScanRules.containerExplained(10, 10), 10)
    check("chest moved 25 -> capped at 10", ScanRules.containerExplained(10, 25), 10)
    check("chest grew -> nothing", ScanRules.containerExplained(10, -3), 0)
    check("no container -> nothing", ScanRules.containerExplained(10, 0), 0)

    println("purchase delivery reconciliation")
    run {
        val umber = "ENCHANTED_UMBER"
        val tungsten = "ENCHANTED_TUNGSTEN"
        val receipts = NonLootDeliveries()

        fun delivery(message: String, item: String, at: Long) {
            val parsed = checkNotNull(PurchaseDelivery.parse(message))
            receipts.record(setOf(item), parsed.amount, at, parsed.canEnterSack)
        }

        val buy = "[Bazaar] Bought 128x Enchanted Red Sand for 207,104 coins!"
        check("actual instant-buy receipt", PurchaseDelivery.parse(buy), PurchaseDelivery("Enchanted Red Sand", 128, true))
        val setup = "[Bazaar] Buy Order Setup! 1,024x Enchanted Tungsten for 13,410,918 coins."
        check("placing an order is not a delivery", PurchaseDelivery.parse(setup), null)
        val filled = "[Bazaar] Your Buy Order for 1,024x Enchanted Tungsten was filled!"
        check("filling an order is not a delivery", PurchaseDelivery.parse(filled), null)
        val refund = "[Bazaar] Cancelled! Refunded 62,021,700 coins from cancelling Buy Order!"
        check("an order refund does not deliver items", PurchaseDelivery.parse(refund), null)
        val ah = "You claimed Wolf Talisman from someone's auction!"
        check("auction claim still fences one item", PurchaseDelivery.parse(ah), PurchaseDelivery("Wolf Talisman", 1, false))

        delivery("[Bazaar] Claimed 834x Enchanted Tungsten worth 10,922,564 coins bought for 13,097 each!", tungsten, 2_000L)
        delivery("[Bazaar] Claimed 99x Enchanted Umber worth 1,494,959 coins bought for 15,101 each!", umber, 7_000L)
        check("archive replay: +834 tungsten reported 7s after delivery with a 5s window",
            receipts.takeSack(tungsten, 834, 9_000L, 5_000L), 834)
        check("archive replay: +99 umber reported 8s after delivery with a 5s window",
            receipts.takeSack(umber, 99, 15_000L, 5_000L), 99)
        check("the next independent tungsten drop is not consumed",
            receipts.takeSack(tungsten, 3, 16_000L, 5_000L), 0)
        val umberClaim = "[Bazaar] Claimed 64x Enchanted Umber worth 966,451 coins bought for 15,101 each!"
        delivery(umberClaim, umber, 20_000L)
        delivery(umberClaim, umber, 21_000L)
        check("identical separate receipts each deliver their own units",
            receipts.takeSack(umber, 128, 27_000L, 5_000L), 128)

        receipts.record(setOf(umber), 100, 30_200L, canEnterSack = true)
        check("inventory slot packet can arrive before the receipt",
            receipts.takeInventory(umber, 40, 30_000L), 40)
        check("inventory and sack share the purchase quota and leave 3 real drops",
            63 - receipts.takeSack(umber, 63, 40_000L, 5_000L), 3)
        check("sack cannot spend the receipt a second time",
            receipts.takeSack(umber, 100, 41_000L, 5_000L), 0)
        receipts.record(setOf(tungsten), 30, 50_000L, canEnterSack = true)
        check("a later receipt does not take already-observed loot",
            receipts.takeSack(tungsten, 30, 47_000L, 5_000L), 0)
        receipts.prune(90_000L)
        check("an unobserved receipt eventually expires",
            receipts.takeSack(tungsten, 30, 90_000L, 35_000L), 0)
        receipts.record(setOf("WOLF_TALISMAN"), 1, 91_000L, canEnterSack = false)
        check("an auction receipt cannot take sack loot",
            receipts.takeSack("WOLF_TALISMAN", 1, 91_100L, 5_000L), 0)
        check("auction receipt is still available for inventory",
            receipts.takeInventory("WOLF_TALISMAN", 2, 91_200L), 1)

        val sand = "RED_SAND"
        val compacted = "ENCHANTED_RED_SAND"
        receipts.record(setOf(sand), 240, 100_000L, canEnterSack = true)
        check("raw red sand is recognized as bought", receipts.takeSack(sand, 240, 102_000L, 5_000L), 240)
        check("a bought raw stack cannot create profit by compaction",
            receipts.excludedCompaction(sand, compacted, 160, 1, 160), NonLootDeliveries.ExcludedCompaction(1, 160))
        check("mixed compaction excludes only the product with bought inputs",
            receipts.excludedCompaction(sand, compacted, 320, 2, 160), NonLootDeliveries.ExcludedCompaction(1, 160))
        check("ordinary raw loot still compacts after bought inputs are exhausted",
            receipts.excludedCompaction(sand, compacted, 160, 1, 160), NonLootDeliveries.ExcludedCompaction(0, 0))
        receipts.clear()
        check("disconnect clears the remaining receipt and sack stock",
            receipts.excludedCompaction(compacted, "ENCHANTED_RED_SAND_CUBE", 2, 1, 2),
            NonLootDeliveries.ExcludedCompaction(0, 0))

        delivery("[Bazaar] Claimed 960x Enchanted Umber worth 14,496,768 coins bought for 15,101 each!", umber, 200_000L)
        delivery(umberClaim, umber, 210_000L)
        delivery("[Bazaar] Claimed 896x Enchanted Tungsten worth 11,750,771 coins bought for 13,115 each!", tungsten, 211_000L)
        delivery("[Bazaar] Claimed 128x Enchanted Tungsten worth 1,678,682 coins bought for 13,115 each!", tungsten, 218_000L)
        val tradedUmber = receipts.takeSack(umber, 960, 211_000L, 5_000L) +
            receipts.takeSack(umber, 64, 221_000L, 5_000L)
        val tradedTungsten = receipts.takeSack(tungsten, 896, 221_000L, 5_000L) +
            receipts.takeSack(tungsten, 128, 228_000L, 5_000L)
        check("four archived partial deliveries exclude all 2,048 bought items",
            (1_024 - tradedUmber) + (1_024 - tradedTungsten), 0)
    }

    println("fossil excavation")
    run {
        val block = ExcavationRewardBlock()
        val deliveries = NonLootDeliveries()
        val firstResult = 100_000L
        val rewards = listOf(
            "  EXCAVATION COMPLETE",
            "",
            "  REWARDS",
            "    \uE054 Fine Citrine Gemstone",
            "    \uE054 Flawed Citrine Gemstone x40",
            "    \uE051 Flawed Peridot Gemstone x20",
            "    Fossil Essence x10",
            "    Glacite Powder x1,223",
            "▬".repeat(64),
        )
        val expected = listOf(
            "Fine Citrine Gemstone" to 1L,
            "Flawed Citrine Gemstone" to 40L,
            "Flawed Peridot Gemstone" to 20L,
            "Fossil Essence" to 10L,
            "Glacite Powder" to 1_223L,
        )
        val rows = rewards.mapNotNull { block.accept(it, firstResult) }
        check("archive replay: parses all Fossil Excavator reward rows", rows, expected)
        check("ordinary chat after the footer is not read as an excavation reward",
            block.accept("    Enchanted Tungsten x2", firstResult + 100L), null)
        check("the essence window includes the completion tick", block.recentResult(firstResult + 100L), true)
        check("the essence window still covers 3.5s trailing action bar", block.recentResult(firstResult + 3_500L), true)
        check("the essence window expires for later mining", block.recentResult(firstResult + 3_501L), false)
        check("only the Fossil Excavator title is excluded",
            listOf("Fossil Excavator", "Fossil Essence Shop", "Mineshaft", null).map(ExcavationRewardBlock::isExcavator),
            listOf(true, false, false, false))
        check("a mining reward without an excavation header is untouched",
            block.accept("    Flawed Peridot Gemstone x30", firstResult + 10_000L), null)

        for ((name, amount) in rows.take(3)) deliveries.record(setOf(name), amount.toInt(), firstResult, canEnterSack = true)
        val receiptAt = firstResult + 5_000L
        val excluded = deliveries.takeSack("Fine Citrine Gemstone", 1, receiptAt, 5_000L) +
            deliveries.takeSack("Flawed Citrine Gemstone", 40, receiptAt, 5_000L) +
            deliveries.takeSack("Flawed Peridot Gemstone", 23, receiptAt, 5_000L)
        check("archive replay: all 61 excavation items are excluded, preserving 3 mined items", 64 - excluded, 3)

        val nextResult = 110_000L
        block.accept("  EXCAVATION COMPLETE", nextResult)
        block.accept("  REWARDS", nextResult)
        val (name, qty) = checkNotNull(block.accept("    Enchanted Tungsten x2", nextResult))
        deliveries.record(setOf(name), qty.toInt(), nextResult, canEnterSack = true)
        check("a slot arriving before the excavation chat can consume its reward",
            deliveries.takeInventory(name, 1, nextResult - 300L), 1)
        check("the sack can only match the remaining excavation reward",
            deliveries.takeSack(name, 4, nextResult + 7_000L, 5_000L), 1)
        check("ordinary tungsten after the excavation still counts",
            deliveries.takeInventory(name, 4, nextResult + 8_000L), 0)
    }

    println("sack deposit netting")
    run {
        val deposits = SackDeposits(600L)
        deposits.record("OPAL", 1_407, 10_000L)
        check("a partial batch consumes only the observed transfer it needs", deposits.take("OPAL", 63, 11_000L, 2_000L), 63)
        check("the rest can be matched once on a later batch", deposits.take("OPAL", 1_408, 12_000L, 3_000L), 1_344)
        check("a same-name gain with no transfer evidence is independent", deposits.take("OPAL", 1_408, 13_000L, 1_000L), 0)
        deposits.record("OPAL", 4, 15_150L)
        deposits.record("OPAL", 5, 15_800L)
        check("a batch before the scan can match the observed deposit within its defer period", deposits.take("OPAL", 10, 15_000L, 500L), 4)
        check("a deposit observed after that period belongs to a later batch", deposits.take("OPAL", 10, 16_000L, 500L), 5)
        deposits.record("OPAL", 7, 17_000L)
        check("a deposit can match at the full window plus 15 seconds", deposits.take("OPAL", 2, 37_000L, 5_000L), 2)
        check("a 25-second-old deposit cannot offset new drops", deposits.take("OPAL", 10, 42_000L, 5_000L), 0)
    }

    println("late sacks messages after Bazaar claims")
    run {
        val key = "ENCHANTED_TUNGSTEN"
        val deliveries = NonLootDeliveries()
        val deposits = SackDeposits(600L)

        fun claim(amount: Int, inventoryGain: Int, now: Long) {
            val parsed = PurchaseDelivery.parse(
                "[Bazaar] Claimed ${amount.toString().reversed().chunked(3).joinToString(",").reversed()}x Enchanted Tungsten worth 15,000,000 coins bought for 13,097 each!"
            ) ?: error("Expected Bazaar delivery")
            deliveries.record(setOf(key), parsed.amount, now, parsed.canEnterSack)
            check("claim of $amount credits zero for the $inventoryGain units delivered to inventory",
                inventoryGain - deliveries.takeInventory(key, inventoryGain, now), 0)
        }

        fun scanDecrease(amount: Int, now: Long, verdict: RemovalVerdict) {
            val deposit = verdict == RemovalVerdict.DEPOSIT
            val bought = deliveries.removeInventory(key, amount, transferredToSack = deposit)
            if (deposit) deposits.record(key, amount, now)
            if (verdict == RemovalVerdict.COST_AND_CREDIT) deliveries.recordSackTransfer(key, bought, now)
        }

        fun sackGain(amount: Int, now: Long): Int {
            val remaining = amount - deposits.take(key, amount, now, 5_000L)
            return remaining - deliveries.takeSack(key, remaining, now, 5_000L)
        }

        claim(1_216, 1_212, 10_000L)
        val lateClear = ScanRules.removalVerdict(ScreenKind.NONE, msSinceSackClosed = 200)
        scanDecrease(1_212, 11_200L, lateClear)
        check("1,212 deposited plus four direct-to-sack units, 9s delay", sackGain(1_216, 20_200L), 0)

        claim(1_216, 1_216, 22_000L)
        claim(322, 322, 23_000L)
        val inSack = ScanRules.removalVerdict(ScreenKind.SACK)
        val afterEsc = ScanRules.removalVerdict(ScreenKind.NONE, leavingSackGui = true)
        val afterSwitchToForge = ScanRules.removalVerdict(ScreenKind.CONVERSION, leavingSackGui = true)
        check("Chat -> Sack of Sacks with a screen open throughout -> deposit", inSack, RemovalVerdict.DEPOSIT)
        check("Sack of Sacks -> ESC -> deposit before rebasing", afterEsc, RemovalVerdict.DEPOSIT)
        check("Sack of Sacks -> Forge -> deposit before rebasing", afterSwitchToForge, RemovalVerdict.DEPOSIT)
        scanDecrease(1_216, 24_000L, afterEsc)
        scanDecrease(322, 25_000L, afterSwitchToForge)
        check("second claim's transfer is excluded after 10s", sackGain(1_216, 34_000L), 0)
        check("third claim's transfer is excluded after 13s", sackGain(322, 38_000L), 0)
        check("the next five mined Tungsten are credited in full", sackGain(5, 38_100L), 5)

        claim(160, 160, 40_000L)
        scanDecrease(165, 41_000L, ScanRules.removalVerdict(ScreenKind.NONE))
        check("a fallback receipt cannot absorb fresh inventory loot",
            deliveries.takeInventory(key, 5, 41_100L), 0)
        check("unknown sack transfer offsets only purchased inventory units", sackGain(165, 55_000L), 5)
        check("a fallback receipt cannot be spent twice", sackGain(5, 55_100L), 5)

        claim(64, 64, 60_000L)
        scanDecrease(64, 61_000L, ScanRules.removalVerdict(ScreenKind.CONVERSION))
        check("purchased materials consumed inside Forge never create a sack receipt", sackGain(64, 62_000L), 64)
    }

    println("sack compaction and existing stock")
    run {
        val buffer = CaptureBuffer()
        val peridot = "MINING/normal/1/FLAWED_PERIDOT_GEM"
        val archivedBatches = listOf(16 to 1_280, 7 to 560, 4 to 320)
        val replay = archivedBatches.mapIndexed { index, (products, removed) ->
            buffer.reconcileSackCompaction(peridot, products, 80, removed, index * 30_000L)
        }
        check("archive replay: 27 fine from old stock add zero new product",
            replay.sumOf { it.remainingProducts }, 0)
        check("archive replay: all 2,160 consumed flawed are explained",
            replay.sumOf { it.directRemoval }, 2_160)
        check("archive replay: no new raw is inferred from a fully matched conversion",
            replay.sumOf { it.newlyAddedInputs }, 0)
        check("same-batch compaction adds only the fresh raw hidden by the net removal",
            buffer.reconcileSackCompaction(peridot, 27, 80, 2_100, 90_000L),
            CaptureBuffer.SackCompaction(0, 60, 2_100, 0))
        check("a directly acquired fine remains when signed inputs cover only two conversions",
            buffer.reconcileSackCompaction(peridot, 3, 80, 160, 120_000L),
            CaptureBuffer.SackCompaction(1, 0, 160, 0))
        check("a fine with no evidence of conversion is ordinary loot",
            buffer.reconcileSackCompaction(peridot, 1, 80, 0, 121_000L),
            CaptureBuffer.SackCompaction(1, 0, 0, 0))
        buffer.noteCompactionInput(peridot, 100, 130_000L)
        check("stock from the previous batch can finish the conversion",
            buffer.reconcileSackCompaction(peridot, 2, 80, 60, 160_000L),
            CaptureBuffer.SackCompaction(0, 0, 60, 100))
        check("old removal cannot explain a later fine twice",
            buffer.reconcileSackCompaction(peridot, 1, 80, 0, 161_000L),
            CaptureBuffer.SackCompaction(1, 0, 0, 0))
        buffer.noteCompactionInput(peridot, 80, 170_000L)
        check("reset revisions do not share a pool", buffer.reconcileSackCompaction(
            "MINING/normal/2/FLAWED_PERIDOT_GEM", 1, 80, 0, 171_000L),
            CaptureBuffer.SackCompaction(1, 0, 0, 0))
    }

    println("capture buffer")
    run {
        val buffer = CaptureBuffer(pendingIncreaseMs = 1_000L, removalCreditMs = 10_000L)
        buffer.hold("OIL_BARREL", 5, 0L)
        check("held pickup is not credited before the window", buffer.dueHolds(500L), emptyList<Pair<String, Int>>())
        check("held pickup flushes after the window", buffer.dueHolds(1_200L), listOf("OIL_BARREL" to 5))
        check("flushed pickup is gone", buffer.dueHolds(5_000L), emptyList<Pair<String, Int>>())
    }
    run {
        val buffer = CaptureBuffer()
        buffer.hold("OIL_BARREL", 5, 0L)
        val remaining = buffer.remainingContainerDecrease("OIL_BARREL", 5)
        check("a container loss used by a hold cannot explain a second pickup", ScanRules.containerExplained(5, remaining), 0)
        check("container decrease explains the held pickup", buffer.dueHolds(9_999L), emptyList<Pair<String, Int>>())
        buffer.hold("OIL_BARREL", 2, 10_000L)
        check("the unused part of a container loss can explain a new pickup", buffer.remainingContainerDecrease("OIL_BARREL", 5), 3)
    }
    run {
        val buffer = CaptureBuffer()
        buffer.addRemoval("OIL_BARREL", 100, 0L)
        check("removal cancels the next pickup", buffer.takeCredit("OIL_BARREL", 40, 100L), 0)
        check("leftover removal still covers the rest", buffer.takeCredit("OIL_BARREL", 60, 200L), 0)
        check("removal is used up", buffer.takeCredit("OIL_BARREL", 1, 300L), 1)
    }
    run {
        val buffer = CaptureBuffer()
        buffer.addRemoval("OIL_BARREL", 5, 0L)
        check("expired removal does not cancel loot", buffer.takeCredit("OIL_BARREL", 10, 20_000L), 10)
    }
    run {
        val buffer = CaptureBuffer()
        buffer.addRemoval("MITHRIL", 160, 0L)
        val compaction = { key: String -> if (key == "ENCHANTED_MITHRIL") "MITHRIL" to 160 else null }
        check("compacted block is covered by the raw stack", buffer.takeCredit("ENCHANTED_MITHRIL", 1, 100L, compaction), 0)
        check("a second block has nothing left to lean on", buffer.takeCredit("ENCHANTED_MITHRIL", 1, 200L, compaction), 1)
    }
    run {
        val buffer = CaptureBuffer()
        buffer.noteSkipped("OIL_BARREL", 1_407)
        check("skipped withdrawal nets the next line", buffer.takeSkipped("OIL_BARREL", 1_408), 1_407)
        check("skipped withdrawal is used up", buffer.takeSkipped("OIL_BARREL", 1_408), 0)
        buffer.notePendingWithdrawal("OPAL", 4, 1_000L)
        check("a late inventory increase only offsets the signed withdrawal", buffer.takePendingWithdrawal("OPAL", 6, 1_500L), 4)
        check("a subsequent same-name drop is independent", buffer.takePendingWithdrawal("OPAL", 6, 1_600L), 0)
        buffer.notePendingWithdrawal("OPAL", 2, 2_000L)
        check("old withdrawals cannot explain new pickups", buffer.takePendingWithdrawal("OPAL", 2, 4_000L), 0)
    }

    run {
        val buffer = CaptureBuffer()
        buffer.noteCompactionInput("ROUGH_AMETHYST_GEM", 80, 0L)
        check("pool pays for one product", buffer.takeCompactionInput("ROUGH_AMETHYST_GEM", 80, 100L), 80)
        check("pool is used up", buffer.takeCompactionInput("ROUGH_AMETHYST_GEM", 80, 200L), 0)
    }
    run {
        val buffer = CaptureBuffer()
        buffer.noteCompactionInput("ROUGH_AMETHYST_GEM", 40, 0L)
        buffer.noteCompactionInput("ROUGH_AMETHYST_GEM", 40, 10L)
        check("pool accumulates across lines", buffer.takeCompactionInput("ROUGH_AMETHYST_GEM", 80, 100L), 80)
    }
    run {
        val buffer = CaptureBuffer()
        buffer.noteCompactionInput("FLAWED_PERIDOT_GEM", 1_520, 0L)
        check("pool gives what it has when short", buffer.takeCompactionInput("FLAWED_PERIDOT_GEM", 9_999, 100L), 1_520)
    }
    run {
        val buffer = CaptureBuffer()
        buffer.noteCompactionInput("ROUGH_PERIDOT_GEM", 100, 0L)
        check("only one complete product uses the pool", buffer.takeCompactionInput("ROUGH_PERIDOT_GEM", 160, 100L, 80), 80)
        check("an incomplete product leaves the pool intact", buffer.takeCompactionInput("ROUGH_PERIDOT_GEM", 80, 200L, 80), 0)
        check("current and earlier inputs complete a product", buffer.takeCompactionInput("ROUGH_PERIDOT_GEM", 20, 300L, 80, 60), 20)
    }
    run {
        val buffer = CaptureBuffer()
        buffer.noteCompactionInput("TUNGSTEN", 6_880, 0L)
        check("pool entry expires after ten minutes", buffer.takeCompactionInput("TUNGSTEN", 6_880, 700_000L), 0)
    }
    run {
        val buffer = CaptureBuffer()
        buffer.noteCompactionInput("UMBER", 160, 0L)
        buffer.clear()
        check("clear wipes the pool", buffer.takeCompactionInput("UMBER", 160, 100L), 0)
    }

    println("essence action-bar dedup")
    run {
        val deduper = EssenceDeduper()
        check("initial essence gain is credited", deduper.accept("Ice Essence", 100, 0L), true)
        check("transient ability bar transitions state once", deduper.onEmptyBar(400L), true)
        check("consecutive non-essence bar does not re-trigger transition", deduper.onEmptyBar(500L), false)
        check("restored essence text after ability bar is deduplicated", deduper.accept("Ice Essence", 100, 1_400L), false)
        check("same essence after window expires is credited again", deduper.accept("Ice Essence", 100, 4_000L), true)
        check("changed amount within window is credited as a new gain", deduper.accept("Ice Essence", 200, 4_500L), true)
        deduper.clear()
        check("world change clears dedup state", deduper.accept("Ice Essence", 200, 4_600L), true)
    }

    println("crystal hollows manual mining gate")
    run {
        val gate = CrystalHollowsGate()
        val grace = 60_000L

        // 1. Entering Crystal Hollows: plain mining off by default, 20 blocks never auto-start, Nucleus still allowed.
        gate.onWorldReset()
        check("hollows defaults to plain mining disabled", gate.hollowsMiningActive, false)
        check("hollows default blocks plain mining pickups", gate.isPickupAllowed(isMining = true, inCrystalHollows = true), false)
        check("hollows default shows paused/start state in UI", gate.isPausedInUi(isMining = true, inCrystalHollows = true), true)
        check("hollows default still allows Nucleus slice settlement", gate.canBookMiningSliceLoot(isMining = true), true)
        for (i in 1..20) {
            gate.onMiningContact(i.toLong(), 1_000L + i * 300L, grace, inCrystalHollows = true)
        }
        check("20 blocks in hollows do not auto-start mining", gate.hollowsMiningActive, false)
        check("20 blocks in hollows do not start the clock", gate.isCounting(7_000L, grace, isMining = true, inCrystalHollows = true), false)
        check("20 blocks in hollows keep pickups blocked", gate.isPickupAllowed(isMining = true, inCrystalHollows = true), false)
        check("Nucleus settlement remains open after 20 ignored blocks", gate.canBookMiningSliceLoot(isMining = true), true)

        // 2. Manual start -> 2 blocks start clock -> >60s auto-Idle -> 2 blocks resume.
        check("clicking Start mining activates hollows mining", gate.togglePause(inCrystalHollows = true), true)
        check("manual start enables pickups", gate.isPickupAllowed(isMining = true, inCrystalHollows = true), true)
        check("manual start clears paused UI state", gate.isPausedInUi(isMining = true, inCrystalHollows = true), false)
        check("clock stays idle before mining blocks", gate.isCounting(10_000L, grace, isMining = true, inCrystalHollows = true), false)
        check("first block arms the hollows mining clock",
            gate.onMiningContact(101L, 10_000L, grace, inCrystalHollows = true), CrystalHollowsGate.SignalOutcome.ARMED)
        check("clock is not counting after a single block", gate.isCounting(10_000L, grace, isMining = true, inCrystalHollows = true), false)
        check("second block within 3s starts the clock",
            gate.onMiningContact(102L, 10_400L, grace, inCrystalHollows = true), CrystalHollowsGate.SignalOutcome.STARTED)
        check("clock counts right after second block", gate.isCounting(10_400L, grace, isMining = true, inCrystalHollows = true), true)
        check("clock still counts at the 60s grace edge", gate.isCounting(70_400L, grace, isMining = true, inCrystalHollows = true), true)
        check("clock automatically enters Idle after 60s without signals",
            gate.isCounting(70_401L, grace, isMining = true, inCrystalHollows = true), false)
        check("auto-Idle does not flip back to paused UI state",
            gate.isPausedInUi(isMining = true, inCrystalHollows = true), false)
        gate.onMiningContact(103L, 80_000L, grace, inCrystalHollows = true)
        gate.onMiningContact(104L, 80_400L, grace, inCrystalHollows = true)
        check("mining again after auto-Idle resumes the clock",
            gate.isCounting(80_400L, grace, isMining = true, inCrystalHollows = true), true)

        // 3. Manual pause in hollows -> stops clock & pickups without setting global pause; 20 blocks stay off; Nucleus stays open.
        check("clicking Pause in hollows stops mining", gate.togglePause(inCrystalHollows = true), false)
        check("pausing in hollows does not set global isPaused", gate.isPaused, false)
        check("pausing in hollows disables pickups immediately", gate.isPickupAllowed(isMining = true, inCrystalHollows = true), false)
        check("pausing in hollows stops the clock immediately", gate.isCounting(80_500L, grace, isMining = true, inCrystalHollows = true), false)
        check("pausing in hollows keeps Nucleus settlement allowed", gate.canBookMiningSliceLoot(isMining = true), true)
        for (i in 1..20) {
            gate.onMiningContact(200L + i, 81_000L + i * 300L, grace, inCrystalHollows = true)
        }
        check("20 blocks after pausing in hollows never auto-resume", gate.hollowsMiningActive, false)
        check("clock remains stopped after 20 blocks while paused in hollows",
            gate.isCounting(87_000L, grace, isMining = true, inCrystalHollows = true), false)
        check("manual click re-enables hollows mining", gate.togglePause(inCrystalHollows = true), true)
        gate.onMiningContact(301L, 90_000L, grace, inCrystalHollows = true)
        gate.onMiningContact(302L, 90_300L, grace, inCrystalHollows = true)
        check("mining after second manual start counts again",
            gate.isCounting(90_300L, grace, isMining = true, inCrystalHollows = true), true)

        // 4. Switching from Crystal Hollows (even after clicking Pause there) to Dwarven Mines restores normal mining.
        gate.togglePause(inCrystalHollows = true)
        gate.onWorldReset()
        check("warping to Dwarven Mines is not polluted by hollows pause", gate.isPaused, false)
        check("Dwarven Mines allows pickups automatically", gate.isPickupAllowed(isMining = true, inCrystalHollows = false), true)
        check("Dwarven Mines is not shown as paused", gate.isPausedInUi(isMining = true, inCrystalHollows = false), false)
        gate.onMiningContact(401L, 100_000L, grace, inCrystalHollows = false)
        gate.onMiningContact(402L, 100_300L, grace, inCrystalHollows = false)
        check("Dwarven Mines starts counting automatically after 2 blocks",
            gate.isCounting(100_300L, grace, isMining = true, inCrystalHollows = false), true)

        // 5. Dwarven Mines manual pause still auto-resumes after 10 blocks in 10s, and global pause blocks Nucleus if carried in.
        check("pausing outside hollows sets global isPaused", gate.togglePause(inCrystalHollows = false), false)
        check("global pause blocks Dwarven Mines pickups", gate.isPickupAllowed(isMining = true, inCrystalHollows = false), false)
        for (i in 1..9) {
            gate.onMiningContact(500L + i, 110_000L + i * 300L, grace, inCrystalHollows = false)
        }
        check("9 blocks outside hollows keep global pause", gate.isPaused, true)
        gate.onMiningContact(510L, 113_000L, grace, inCrystalHollows = false)
        check("10th block in 10s outside hollows auto-resumes global pause", gate.isPaused, false)
        gate.onMiningContact(511L, 113_300L, grace, inCrystalHollows = false)
        check("next block after auto-resume starts counting in Dwarven Mines",
            gate.isCounting(113_300L, grace, isMining = true, inCrystalHollows = false), true)

        gate.setGlobalPaused(true)
        gate.onWorldReset()
        check("global pause blocks Nucleus settlement until resumed", gate.canBookMiningSliceLoot(isMining = true), false)
        gate.startOrResume(inCrystalHollows = true)
        check("Start mining clears global pause and activates hollows mining",
            !gate.isPaused && gate.hollowsMiningActive && gate.canBookMiningSliceLoot(isMining = true), true)
    }

    println()
    println(if (failures == 0) "ALL $checks CHECKS PASSED" else "$failures of $checks CHECKS FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
