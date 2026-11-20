package com.skbprofitracker.tracker

internal class ResetConfirmation<T> {
    enum class Method(val windowMs: Long, val repeatsConfirm: Boolean, val usesChat: Boolean) {
        OVERLAY_SESSION(5_000L, true, false),
        OVERLAY_TOTAL(15_000L, false, true),
        COMMAND(15_000L, true, true),
    }

    private data class Pending<T>(val target: T, val id: Long, val expiresAt: Long, val method: Method)

    private var pending: Pending<T>? = null
    private var nextId = 0L

    fun isArmed(target: T, method: Method, now: Long): Boolean =
        pending?.let { it.target == target && it.method == method && now <= it.expiresAt } == true

    /** Returns the pending confirmation ID, or null when the repeated request confirms it. */
    fun request(target: T, method: Method, now: Long): Long? {
        if (isArmed(target, method, now)) {
            if (!method.repeatsConfirm) return pending?.id
            pending = null
            return null
        }
        val request = Pending(target, ++nextId, now + method.windowMs, method)
        pending = request
        return request.id
    }

    fun confirm(id: Long?, now: Long): T? {
        val request = pending ?: return null
        if (!request.method.usesChat || request.id != id || now > request.expiresAt) return null
        pending = null
        return request.target
    }
}
