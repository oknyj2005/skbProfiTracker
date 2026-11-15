package com.skbprofitracker.utils.data

/**
 * Everything that survives a game restart.
 *
 * Only counts and durations are stored: prices are always resolved live from
 * the bazaar/auction cache, so a stale price can never be persisted.
 */
data class SkbData(
    var isWelcomeMessageShown: Boolean = false,
    var priceMode: String = "SELL_OFFER",
    var viewMode: String = "SESSION",
    var slayerFilter: String = "ALL",
    var miningFilter: String = "ALL",
    val mining: BucketData = BucketData(),
    val slayer: BucketData = BucketData(),
    val rift: BucketData = BucketData(),
    /** Per slayer type (INFERNO, REVENANT, ...), so each one keeps its own time and loot. */
    val slayerTypes: MutableMap<String, BucketData> = mutableMapOf(),
    /** Per mining type (Mining, Nucleus, Corpse), same reason as the slayer split. */
    val miningTypes: MutableMap<String, BucketData> = mutableMapOf(),
    /** Rarity colour prefix per price key, learned from the hover lore on first pickup. */
    val itemColors: MutableMap<String, String> = mutableMapOf(),
) {
    fun bucket(name: String): BucketData = when (name) {
        "mining" -> mining
        "slayer" -> slayer
        "rift" -> rift
        else -> error("unknown bucket: $name")
    }

    fun slayerTypeBucket(type: String): BucketData =
        slayerTypes.getOrPut(type) { BucketData() }

    fun miningTypeBucket(type: String): BucketData =
        miningTypes.getOrPut(type) { BucketData() }
}

/** Per-activity counters, split into session and lifetime totals. */
data class BucketData(
    var sessionElapsedSeconds: Long = 0,
    var totalElapsedSeconds: Long = 0,
    val sessionItems: MutableMap<String, Long> = mutableMapOf(),
    val totalItems: MutableMap<String, Long> = mutableMapOf(),
    /** Consumables keyed by bazaar id, priced live like the income side. */
    val sessionCosts: MutableMap<String, Long> = mutableMapOf(),
    val totalCosts: MutableMap<String, Long> = mutableMapOf(),
    /** Coin costs (slayer quest start, auto-slayer) that are not tied to an item. */
    var sessionCoinsSpent: Double = 0.0,
    var totalCoinsSpent: Double = 0.0,
    /** Completed bosses, so the panel can show a per-kill average next to the per-hour one. */
    var sessionBosses: Long = 0,
    var totalBosses: Long = 0,
    /** Opened Frozen Corpses, so a corpse run can be read per key instead of only per coins. */
    var sessionCorpses: Long = 0,
    var totalCorpses: Long = 0,
)
