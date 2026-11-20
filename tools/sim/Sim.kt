import com.skbprofitracker.capture.ActivityKind
import com.skbprofitracker.capture.CaptureBuffer
import com.skbprofitracker.capture.AddVerdict
import com.skbprofitracker.capture.RemovalVerdict
import com.skbprofitracker.capture.ScanRules
import com.skbprofitracker.capture.ScreenKind

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
