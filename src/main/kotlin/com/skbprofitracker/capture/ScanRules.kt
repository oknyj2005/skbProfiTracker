package com.skbprofitracker.capture

/**
 * Pure decision rules for the inventory scanner.
 *
 * Everything here is deliberately free of Minecraft types so the behaviour can be replayed with
 * fake data: the publisher only gathers state, asks for a verdict, and applies the side effects.
 */
enum class ActivityKind { MINING, SLAYER, OTHER }

/** What the player currently has open. */
enum class ScreenKind { NONE, SACK, CONTAINER, CUSTOM_MENU, CONVERSION }

/** How a decrease of the inventory total should be treated. */
enum class RemovalVerdict {
    /** Nothing is open: the item was really spent, so charge it and credit the removal. */
    COST_AND_CREDIT,

    /** Items moved into a sack and will be reported on a `[Sacks] +` line. */
    DEPOSIT,

    /** Titled menu (drill anvil, forge, pets) or Hypixel's own menu: the item is gone for good. */
    IGNORE,
}

/** How an increase of the inventory total should be treated. */
enum class AddVerdict {
    /** Book it as loot. */
    CREDIT,

    /** Taken out of a sack while the sack screen is open; the `[Sacks] -` line explains it. */
    WITHDRAWAL,

    /** Slots are still being rewritten right after a menu closed. */
    JUST_CLOSED,

    /** Right after leaving a sack, the client hands stacks back. */
    POST_SACK,

    /** Mining cannot produce anything with a menu open. */
    MENU_IGNORE,

    /** Slayer loot can arrive with a menu open; hold it briefly and ask again later. */
    HOLD,
}

object ScanRules {
    internal const val POST_SACK_DEPOSIT_MS = 2_000L

    fun removalVerdict(
        screen: ScreenKind,
        msSinceSackClosed: Long = Long.MAX_VALUE,
        postSackDepositMs: Long = POST_SACK_DEPOSIT_MS,
        handSackUsedRecently: Boolean = false,
        leavingSackGui: Boolean = false,
    ): RemovalVerdict = when {
        leavingSackGui || screen == ScreenKind.SACK -> RemovalVerdict.DEPOSIT
        screen != ScreenKind.NONE -> RemovalVerdict.IGNORE
        msSinceSackClosed < postSackDepositMs || handSackUsedRecently -> RemovalVerdict.DEPOSIT
        else -> RemovalVerdict.COST_AND_CREDIT
    }

    /**
     * The part of an increase that a shrinking container already explains, capped at the increase
     * itself. Moving a stack from a chest into the inventory therefore nets to zero.
     */
    fun containerExplained(increase: Int, containerDecrease: Int): Int =
        minOf(increase, maxOf(containerDecrease, 0))

    fun addVerdict(
        activity: ActivityKind,
        screenOpen: Boolean,
        sackScreenOpen: Boolean,
        msSinceScreenClosed: Long,
        msSinceSackClosed: Long,
        screenCloseGraceMs: Long,
        sackCooldownMs: Long,
    ): AddVerdict {
        if (screenOpen && sackScreenOpen) return AddVerdict.WITHDRAWAL
        if (!screenOpen && msSinceScreenClosed < screenCloseGraceMs) return AddVerdict.JUST_CLOSED
        if (msSinceSackClosed < sackCooldownMs) return AddVerdict.POST_SACK
        if (screenOpen) {
            return if (activity == ActivityKind.MINING) AddVerdict.MENU_IGNORE else AddVerdict.HOLD
        }
        return AddVerdict.CREDIT
    }
}
