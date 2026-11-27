package com.skbprofitracker.tracker

/**
 * Pure gating and timer-signal rules for Crystal Hollows manual mining vs. automatic regions.
 *
 * Inside Crystal Hollows:
 * - Plain mining is off by default ([hollowsMiningActive] == false) and never auto-resumes on
 *   sustained block breaking, so digging tunnels during a Nucleus run cannot start the clock.
 * - Mining slice chat loot ([canBookMiningSliceLoot], i.e. Nucleus / Corpse bundles) still settles
 *   as long as global [isPaused] is false.
 * - Pausing inside Crystal Hollows only clears [hollowsMiningActive] and signals without setting
 *   global [isPaused], so Nucleus settlement keeps working and leaving for Dwarven Mines / Slayer
 *   does not carry a pause across worlds.
 */
internal class CrystalHollowsGate(
    private val autoResumeSignals: Int = 10,
    private val autoResumeWindowMs: Long = 10_000L,
) {
    enum class SignalOutcome {
        IGNORED,
        PAUSED,
        ARMED,
        STARTED,
        EXTENDED,
    }

    var isPaused: Boolean = false
        private set

    var hollowsMiningActive: Boolean = false
        private set

    private val signals = ActivitySignals()
    private val recentStrongSignals = ArrayDeque<Long>()

    var hasStrongSignal: Boolean = false
        private set

    var lastSignalMs: Long = 0L
        private set

    var lastSignalSource: String = "none"
        private set

    val recentStrongSignalCount: Int
        get() = recentStrongSignals.size

    fun setGlobalPaused(paused: Boolean) {
        isPaused = paused
        if (paused) {
            hollowsMiningActive = false
            clearSignals()
        }
    }

    fun setHollowsMiningActive(active: Boolean) {
        if (active) {
            isPaused = false
            hollowsMiningActive = true
        } else {
            hollowsMiningActive = false
            clearSignals()
        }
    }

    /**
     * Toggles tracking from the UI button or `/skbprofit pause`.
     * Returns true when tracking is now active/started, false when paused/stopped.
     */
    fun togglePause(inCrystalHollows: Boolean): Boolean {
        if (inCrystalHollows) {
            val shouldStart = isPaused || !hollowsMiningActive
            setHollowsMiningActive(shouldStart)
            return shouldStart
        }
        setGlobalPaused(!isPaused)
        return !isPaused
    }

    /** Idempotent start/resume for `/skbprofit start` and `/skbprofit resume`. */
    fun startOrResume(inCrystalHollows: Boolean) {
        if (inCrystalHollows) {
            setHollowsMiningActive(true)
        } else {
            setGlobalPaused(false)
        }
    }

    fun onWorldReset() {
        hollowsMiningActive = false
        clearSignals()
    }

    fun clearSignals(keepMiningClock: Boolean = false) {
        if (!keepMiningClock) {
            lastSignalMs = 0L
            lastSignalSource = "none"
            hasStrongSignal = false
        }
        signals.clear()
        recentStrongSignals.clear()
    }

    fun isPickupAllowed(isMining: Boolean, inCrystalHollows: Boolean): Boolean {
        if (isPaused) return false
        if (isMining && inCrystalHollows && !hollowsMiningActive) return false
        return true
    }

    fun canBookMiningSliceLoot(isMining: Boolean): Boolean =
        !isPaused && isMining

    fun isPausedInUi(isMining: Boolean, inCrystalHollows: Boolean): Boolean =
        if (isMining && inCrystalHollows) isPaused || !hollowsMiningActive else isPaused

    fun hasActiveSignal(now: Long, graceMs: Long): Boolean =
        hasStrongSignal && now - lastSignalMs <= graceMs

    fun isCounting(
        now: Long,
        graceMs: Long,
        isMining: Boolean,
        inCrystalHollows: Boolean,
        excavatorOpen: Boolean = false,
    ): Boolean {
        if (!isPickupAllowed(isMining, inCrystalHollows)) return false
        if (isMining && excavatorOpen) return false
        return hasActiveSignal(now, graceMs)
    }

    fun onMiningContact(
        position: Long,
        now: Long,
        graceMs: Long,
        inCrystalHollows: Boolean,
    ): SignalOutcome {
        if (inCrystalHollows && !hollowsMiningActive) return SignalOutcome.IGNORED
        if (!signals.acceptsMiningContact(position, now)) return SignalOutcome.IGNORED
        return onStrongSignal(
            now = now,
            graceMs = graceMs,
            source = "mining",
            isMining = true,
            inCrystalHollows = inCrystalHollows,
            slayerStarted = false,
        )
    }

    fun onStrongSignal(
        now: Long,
        graceMs: Long,
        source: String,
        isMining: Boolean,
        inCrystalHollows: Boolean,
        slayerStarted: Boolean,
    ): SignalOutcome {
        if (isMining && inCrystalHollows && !hollowsMiningActive) return SignalOutcome.IGNORED

        // Keep collecting even while paused: the burst check below needs them.
        recentStrongSignals.addLast(now)
        while (recentStrongSignals.isNotEmpty() && now - recentStrongSignals.first() > autoResumeWindowMs) {
            recentStrongSignals.removeFirst()
        }

        if (isPaused) {
            // Two things un-pause outside Crystal Hollows: starting to slay inside a running quest,
            // or a sustained burst of work. A single block or a mob on the way past must not undo a pause.
            val sustained = recentStrongSignals.size >= autoResumeSignals
            if (!slayerStarted && !sustained) return SignalOutcome.PAUSED
            isPaused = false
        }

        val wasActive = hasActiveSignal(now, graceMs)
        // One click can be a menu, an NPC or a stray swing. Real work repeats within a tick or
        // two, so the session only opens after a second signal inside [START_WINDOW_MS].
        if (!wasActive && !signals.canStart(now)) {
            return SignalOutcome.ARMED
        }

        lastSignalMs = now
        hasStrongSignal = true
        lastSignalSource = source
        return if (wasActive) SignalOutcome.EXTENDED else SignalOutcome.STARTED
    }

    fun onWeakSignal(
        now: Long,
        observedAt: Long,
        graceMs: Long,
        isMining: Boolean,
        inCrystalHollows: Boolean,
    ): Boolean {
        if (!isPickupAllowed(isMining, inCrystalHollows) || !hasActiveSignal(now, graceMs)) return false
        lastSignalMs = maxOf(lastSignalMs, observedAt.coerceAtMost(now))
        return true
    }

    fun extendActiveSignal(now: Long) {
        lastSignalMs = now
    }

    fun restoreMiningSignal(signalAtMs: Long, signalSource: String) {
        hasStrongSignal = true
        lastSignalMs = signalAtMs
        lastSignalSource = signalSource
    }
}
