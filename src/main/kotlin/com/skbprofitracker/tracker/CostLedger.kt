package com.skbprofitracker.tracker

import com.skbprofitracker.features.overlays.base.TrackerViewMode
import com.skbprofitracker.price.PriceCache
import com.skbprofitracker.price.PriceMode
import com.skbprofitracker.utils.data.PersistentDataManager

data class CostLine(
    val name: String,
    val amount: Long,
    val unitPrice: Double?,
    val value: Double,
)

data class CostView(
    val totalValue: Double,
    val perHour: Double,
    val lines: List<CostLine>,
)

/**
 * The spending side of a bucket.
 *
 * Everything the player pays for while an activity is open is booked here: consumables are
 * detected as inventory decreases of a whitelisted id (a gummy bear disappears from the
 * hotbar when it is eaten), while coin costs come from the purse watcher, because the real
 * deduction already includes shard, mayor and slayer tier modifiers and a hardcoded table
 * would be wrong the moment one of them changes.
 */
object CostLedger {
    /**
     * Items whose disappearance is a cost rather than a loss.
     *
     * Only things the tracked activities actually consume: the Slayer buff consumable and
     * the keys/materials that open corpses and the Crystal Nucleus.
     */
    /** Keys that open a Frozen Corpse. */
    private val CORPSE_COST_KEYS = setOf(
        "UMBER_KEY",
        "TUNGSTEN_KEY",
        "SKELETON_KEY",
    )

    /** Materials handed to the Crystal Nucleus. */
    private val NUCLEUS_COST_KEYS = setOf(
        "JUNGLE_KEY",
        "GOBLIN_EGG",
        "PRECURSOR_APPARATUS",
        "ROBOTRON_REFLECTOR",
        "SUPERLITE_MOTOR",
        "CONTROL_SWITCH",
        "ELECTRON_TRANSMITTER",
        "FTX_3070",
        "SYNTHETIC_HEART",
    )

    private val COST_KEYS = CORPSE_COST_KEYS + NUCLEUS_COST_KEYS + "REHEATED_GUMMY_POLAR_BEAR"

    fun onItemRemoved(key: String, amount: Int) {
        if (amount <= 0 || key !in COST_KEYS) return

        val activity = ActivityBucket.bucketForPickup() ?: return
        // A corpse key belongs to the corpse slice, not to the plain mining one: charging it to
        // "Mining" turned a mining session deeply negative as soon as a few corpses were opened.
        val bucket = when {
            key in CORPSE_COST_KEYS -> BucketLedger.sliceStorage(activity, ActivityBucket.MINING_CORPSE)
            key in NUCLEUS_COST_KEYS -> BucketLedger.sliceStorage(activity, ActivityBucket.MINING_NUCLEUS)
            else -> BucketLedger.storage(activity)
        }
        bucket.sessionCosts.merge(key, amount.toLong(), Long::plus)
        bucket.totalCosts.merge(key, amount.toLong(), Long::plus)
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /**
     * Moves corpse/nucleus key costs that older builds booked into the plain mining slice.
     * Idempotent: once a key is gone from the mining slice there is nothing left to move.
     */
    fun migrateMiningCosts() {
        val data = PersistentDataManager.skbprofitrackerData
        val mining = data.miningTypes[ActivityBucket.MINING_NORMAL] ?: return

        var moved = false
        for ((keys, slice) in listOf(
            CORPSE_COST_KEYS to ActivityBucket.MINING_CORPSE,
            NUCLEUS_COST_KEYS to ActivityBucket.MINING_NUCLEUS,
        )) {
            val target = data.miningTypeBucket(slice)
            for (key in keys) {
                mining.sessionCosts.remove(key)?.let {
                    target.sessionCosts.merge(key, it, Long::plus)
                    moved = true
                }
                mining.totalCosts.remove(key)?.let {
                    target.totalCosts.merge(key, it, Long::plus)
                    moved = true
                }
            }
        }

        if (moved) PersistentDataManager.saveSkbDataToFileAsync()
    }

    fun onCoinsSpent(coins: Double) {
        if (coins <= 0.0) return

        val activity = ActivityBucket.bucketForPickup() ?: return
        val bucket = BucketLedger.storage(activity)
        bucket.sessionCoinsSpent += coins
        bucket.totalCoinsSpent += coins
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /**
     * Costs of the slice the overlay is showing.
     *
     * The filter has to be honoured here as well: reading the bucket of the *currently
     * fought* slayer type made an empty type (Sven, nothing recorded yet) show the coin
     * cost of every other type while its income stayed at zero.
     */
    fun view(
        activity: Activity,
        mode: TrackerViewMode,
        priceMode: PriceMode,
        filter: String = BucketLedger.filterFor(activity),
    ): CostView {
        val buckets = BucketLedger.costBuckets(activity, filter)

        val entries = mutableMapOf<String, Long>()
        var coins = 0.0
        var seconds = 0L
        for (bucket in buckets) {
            val costs = when (mode) {
                TrackerViewMode.SESSION -> bucket.sessionCosts
                TrackerViewMode.TOTAL -> bucket.totalCosts
            }
            costs.forEach { (key, amount) -> entries.merge(key, amount, Long::plus) }
            coins += when (mode) {
                TrackerViewMode.SESSION -> bucket.sessionCoinsSpent
                TrackerViewMode.TOTAL -> bucket.totalCoinsSpent
            }
            seconds += when (mode) {
                TrackerViewMode.SESSION -> bucket.sessionElapsedSeconds
                TrackerViewMode.TOTAL -> bucket.totalElapsedSeconds
            }
        }

        val lines = entries.map { (key, amount) ->
            val price = PriceCache.price(key, priceMode)
            CostLine(key, amount, price, (price ?: 0.0) * amount)
        }.sortedByDescending { it.value }

        val total = lines.sumOf { it.value } + coins
        val hours = seconds / 3600.0

        return CostView(total, if (hours > 0.0) total / hours else 0.0, lines)
    }

}
