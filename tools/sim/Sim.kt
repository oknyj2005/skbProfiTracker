import com.skbprofitracker.capture.ActivityKind
import com.skbprofitracker.capture.CaptureBuffer
import com.skbprofitracker.capture.AddVerdict
import com.skbprofitracker.capture.MiningConsumableUses
import com.skbprofitracker.capture.RemovalVerdict
import com.skbprofitracker.capture.ScanRules
import com.skbprofitracker.capture.ScreenKind
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
        check("an expired attempt does not charge", uses.serverUpdate(2, null, 0, 5_600L), null)
        uses.attempt(2, flask, 1, 5_700L)
        uses.clear()
        check("reset discards unconfirmed attempts", uses.serverUpdate(2, null, 0, 5_800L), null)
    }

    println("container explanation")
    check("chest moved 10 -> explained 10", ScanRules.containerExplained(10, 10), 10)
    check("chest moved 25 -> capped at 10", ScanRules.containerExplained(10, 25), 10)
    check("chest grew -> nothing", ScanRules.containerExplained(10, -3), 0)
    check("no container -> nothing", ScanRules.containerExplained(10, 0), 0)

    println("sack deposit netting")
    check("+1408 with 1407 deposited -> 1 fresh", ScanRules.netAgainstDeposit(1408, 1407), 1 to 0)
    check("+63 with 1407 deposited -> 0 fresh, 1344 left", ScanRules.netAgainstDeposit(63, 1407), 0 to 1344)
    check("+1408 with 1344 left -> 64 fresh", ScanRules.netAgainstDeposit(1408, 1344), 64 to 0)
    check("withdraw only, nothing mined -> 0 fresh", ScanRules.netAgainstDeposit(1407, 1407), 0 to 0)

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
        buffer.explain("OIL_BARREL", 5)
        check("container decrease explains the held pickup", buffer.dueHolds(9_999L), emptyList<Pair<String, Int>>())
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

    println()
    println(if (failures == 0) "ALL $checks CHECKS PASSED" else "$failures of $checks CHECKS FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
