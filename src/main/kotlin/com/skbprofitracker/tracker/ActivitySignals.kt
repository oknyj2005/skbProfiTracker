package com.skbprofitracker.tracker

internal class ActivitySignals {
    private var firstSignalMs: Long? = null
    private var miningPosition: Long? = null
    private var miningSignalMs = 0L

    fun acceptsMiningContact(position: Long, now: Long): Boolean {
        if (miningPosition == position && now - miningSignalMs < 250L) return false
        miningPosition = position
        miningSignalMs = now
        return true
    }

    fun canStart(now: Long): Boolean {
        val first = firstSignalMs
        if (first != null && now - first <= 3_000L) {
            firstSignalMs = null
            return true
        }
        firstSignalMs = now
        return false
    }

    fun clear() {
        firstSignalMs = null
        miningPosition = null
    }
}
