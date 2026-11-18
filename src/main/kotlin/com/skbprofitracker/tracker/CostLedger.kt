package com.skbprofitracker.tracker

import com.skbprofitracker.features.overlays.base.TrackerViewMode
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.price.PriceCache
import com.skbprofitracker.price.PriceMode
import com.skbprofitracker.utils.Debug
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
    /** When each key was last charged, used to swallow flickering inventory diffs. */
    private val lastCostAt = mutableMapOf<String, Long>()

    /**
     * Potion-like consumables are player heads, so right clicking one makes the client predict a
     * block placement first: the stack goes 1 -> 0, the server rejects it, the stack comes back
     * 0 -> 1 and the real consumption then goes 1 -> 0 again. Two removals, one potion.
     */
    private const val CONSUMABLE_DEDUPE_MS = 5_000L

    /** Materials are handed over in a menu, where a double click is a real second hand-in. */
    private const val MATERIAL_DEDUPE_MS = 1_000L

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

    /**
     * Consumables bought from Brynmore's shop and eaten while mining, priced at the shop cost.
     *
     * The auction market for these is thin (a Powder Pie has a 95k BIN against a 48.5k shop
     * price), so the shop price is what the player actually spends.
     */
    private val MINING_CONSUMABLES = mapOf(
        "FIESTA_FLASK" to 2_500_000.0,
        "CHILLED_PRISTINE_POTATO" to 242_500.0,
        "FILET_O_FORTUNE" to 97_000.0,
        "MINING_PUMPKIN" to 48_500.0,
    )

    private val COST_NAMES = mapOf(
        "FIESTA_FLASK" to "Fiesta Flask",
        "CHILLED_PRISTINE_POTATO" to "Chilled Pristine Potato",
        "FILET_O_FORTUNE" to "Filet O' Fortune",
        "MINING_PUMPKIN" to "Powder Pie",
    )

    /** Any goblin egg variant can be handed to King Yolkar; all of them are nucleus materials. */
    private val NUCLEUS_MATERIAL_KEYS = NUCLEUS_COST_KEYS + setOf(
        "GOBLIN_EGG_GREEN", "GOBLIN_EGG_BLUE", "GOBLIN_EGG_RED", "GOBLIN_EGG_YELLOW",
    )

    private val COST_KEYS =
        CORPSE_COST_KEYS + NUCLEUS_COST_KEYS + MINING_CONSUMABLES.keys + "REHEATED_GUMMY_POLAR_BEAR"

    /**
     * Items that only ever leave the inventory as a payment. They are counted as costs, never as
     * loot: keys come from corpse loot blocks, not from mining, so a generic credit for one means
     * the player shuffled a stack into a sack and the sack message looked like a drop.
     */
    fun isCostOnly(key: String): Boolean = key in COST_KEYS

    /**
     * Nucleus materials are handed to the altar well before the bundle opens, and the player may
     * also spend them on other things. Nothing is booked when they leave the inventory: the
     * amounts are parked here and charged as one line when the bundle is actually claimed.
     */
    private data class ParkedMaterial(val amount: Long, val atMs: Long)

    private val pendingNucleus = mutableMapOf<String, ParkedMaterial>()
    private const val NUCLEUS_PARK_MS = 10 * 60_000L

    fun pendingNucleusCosts(): Map<String, Long> = pendingNucleus.mapValues { it.value.amount }

    fun onNucleusOpened() {
        if (pendingNucleus.isEmpty()) {
            if (Debug.enabled) Debug.log("[cost] nucleus bundle opened with no pending materials")
            return
        }
        val activity = ActivityBucket.bucketForPickup() ?: return
        val bucket = BucketLedger.sliceStorage(activity, ActivityBucket.MINING_NUCLEUS)
        val now = System.currentTimeMillis()
        for ((key, parked) in pendingNucleus) {
            if (now - parked.atMs > NUCLEUS_PARK_MS) {
                if (Debug.enabled) Debug.log("[cost] nucleus dropped stale $key x${parked.amount}")
                continue
            }
            bucket.sessionCosts.merge(key, parked.amount, Long::plus)
            bucket.totalCosts.merge(key, parked.amount, Long::plus)
            if (Debug.enabled) Debug.log("[cost] nucleus charged $key x${parked.amount}")
        }
        pendingNucleus.clear()
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    fun onItemRemoved(key: String, amount: Int) {
        if (amount <= 0) return
        if (key !in COST_KEYS && key !in NUCLEUS_MATERIAL_KEYS) return
        // Corpse keys are charged in [onCorpseOpened] instead: the key vanishes in the same
        // breath as the loot arrives and the corpse UI can swallow the inventory diff, which
        // is how three opened corpses once produced only two key costs.
        if (key in CORPSE_COST_KEYS) return

        // A consumed item can flicker out of the inventory and come back while the server
        // confirms the use, and the scan then books the same removal twice (one Fiesta Flask
        // was once charged 5M instead of 2.5M). Nothing can be consumed twice within two
        // seconds, so a repeat of the same key inside that window is noise.
        val now = System.currentTimeMillis()
        val window = if (key in MINING_CONSUMABLES || key == "REHEATED_GUMMY_POLAR_BEAR") {
            CONSUMABLE_DEDUPE_MS
        } else {
            MATERIAL_DEDUPE_MS
        }
        val lastBooked = lastCostAt[key]
        if (lastBooked != null && now - lastBooked < window) {
            if (Debug.enabled) Debug.log("[cost] ignored repeated $key (+$amount)")
            return
        }
        lastCostAt[key] = now

        // Nucleus materials are not charged when they disappear: they are charged in one go
        // when the loot bundle opens, see [onNucleusOpened].
        if (key in NUCLEUS_MATERIAL_KEYS) {
            val now = System.currentTimeMillis()
            val previous = pendingNucleus[key]
            val carried = if (previous != null && now - previous.atMs <= NUCLEUS_PARK_MS) previous.amount else 0L
            pendingNucleus[key] = ParkedMaterial(carried + amount, now)
            if (Debug.enabled) Debug.log("[cost] parked $key x$amount for nucleus")
            return
        }

        // A potion is usually drunk in the Hub right after buying it, before the player warps
        // into the mines, so there is no active bucket yet. Mining consumables are mining-only,
        // so they are booked to the mining slice instead of being dropped.
        val activity = ActivityBucket.bucketForPickup()
            ?: if (key in MINING_CONSUMABLES) Activity.MINING else return
        val bucket = BucketLedger.storage(activity)
        bucket.sessionCosts.merge(key, amount.toLong(), Long::plus)
        bucket.totalCosts.merge(key, amount.toLong(), Long::plus)
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /**
     * Charges the key of a corpse that was just opened.
     *
     * The corpse header already names the type, and a lapis corpse is free, so the cost can be
     * booked from chat even when the inventory scan never saw the key leave. [keyConsumed] is
     * false when the Resourceful perk saved the key.
     */
    fun onCorpseOpened(corpseType: String, keyConsumed: Boolean) {
        val key = when (corpseType.uppercase()) {
            "UMBER" -> "UMBER_KEY"
            "TUNGSTEN" -> "TUNGSTEN_KEY"
            "VANGUARD" -> "SKELETON_KEY"
            else -> return
        }
        if (!keyConsumed) return

        val activity = ActivityBucket.bucketForPickup() ?: return
        val bucket = BucketLedger.sliceStorage(activity, ActivityBucket.MINING_CORPSE)
        bucket.sessionCosts.merge(key, 1L, Long::plus)
        bucket.totalCosts.merge(key, 1L, Long::plus)
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
            val price = MINING_CONSUMABLES[key] ?: PriceCache.price(key, priceMode)
            val name = COST_NAMES[key] ?: ItemTable.getByKey(key)?.name ?: key
            CostLine(name, amount, price, (price ?: 0.0) * amount)
        }.sortedByDescending { it.value }

        val total = lines.sumOf { it.value } + coins
        val hours = seconds / 3600.0

        return CostView(total, if (hours > 0.0) total / hours else 0.0, lines)
    }

}
