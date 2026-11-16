import com.skbprofitracker.capture.ActivityKind
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

    println()
    println(if (failures == 0) "ALL $checks CHECKS PASSED" else "$failures of $checks CHECKS FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
