package com.skbprofitracker.tracker

import com.skbprofitracker.features.overlays.base.TrackerViewMode
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.price.PriceCache
import com.skbprofitracker.price.PriceMode
import com.skbprofitracker.utils.data.BucketData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skbprofitracker.utils.data.PersistentDataManager
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

data class ItemLine(
    val name: String,
    val key: String,
    val amount: Long,
    val unitPrice: Double?,
    val value: Double,
)

data class ProfitView(
    val activity: Activity,
    val mode: TrackerViewMode,
    val items: List<ItemLine>,
    val totalValue: Double,
    val perHour: Double,
    val elapsedSeconds: Long,
    val unpricedTypes: Int,
    /** Completed bosses in the shown slice, zero for non-slayer activities. */
    val bosses: Long = 0,
    /** Opened corpses in the shown slice, zero outside the corpse slice. */
    val corpses: Long = 0,
    /** Which slayer type is shown, or [BucketLedger.ALL] for the combined number. */
    val filter: String = BucketLedger.ALL,
    /**
     * Whether a per-hour figure is meaningful for this slice.
     *
     * Nucleus and corpse runs are bursty one-offs: the loot lands in a single moment, so
     * dividing it by the few minutes the run took produces a number nobody should plan
     * around. They show a plain total instead, and so does the combined mining view.
     */
    val showPerHour: Boolean = true,
)

/**
 * Turns the stored quantities into a priced view.
 *
 * Only quantities are persisted; every number here is recomputed against the current
 * price snapshot, so switching the price mode or waiting for the next bazaar refresh
 * changes the displayed value without touching the save file.
 *
 * Slayer data is kept per type (Inferno / Tarantula / ...) because the per-hour number of
 * the combined bucket is an average over very different activities, and averages are what
 * made the total look wrong next to SkyHanni's per-category trackers. The old merged bucket
 * is still read so history imported before the split keeps showing up under "All".
 *
 * A maxed pet is stored as `<petId>+100` (the key the auction API itself uses) and is
 * priced as the difference between the maxed and the level 1 pet, which is the value the
 * player actually gained by levelling it.
 */
object BucketLedger {
    /**
     * Coins picked up from mobs are stored as an item row so they go through the same
     * session/total bookkeeping as everything else. Their unit price is 1, not a bazaar price.
     */
    const val COINS_KEY = "SKYBLOCK_COIN"

    /** Pseudo type that merges every slayer bucket, including the pre-split merged one. */
    const val ALL = "ALL"

    var priceMode: PriceMode = PriceMode.INSTA_SELL
        set(value) {
            field = value
            data().priceMode = value.name
            PersistentDataManager.saveSkbDataToFileAsync()
        }

    var viewMode: TrackerViewMode = TrackerViewMode.SESSION
        set(value) {
            field = value
            data().viewMode = value.name
            PersistentDataManager.saveSkbDataToFileAsync()
        }

    /** Which slayer type the overlay shows; [ALL] shows the sum of every type. */
    var slayerFilter: String = ALL
        set(value) {
            field = value
            data().slayerFilter = value
        }

    /** Which mining slice the overlay shows; [ALL] shows the sum of every slice. */
    var miningFilter: String = ALL
        set(value) {
            field = value
            data().miningFilter = value
        }

    fun init() {
        priceMode = runCatching { PriceMode.valueOf(data().priceMode) }.getOrDefault(PriceMode.INSTA_SELL)
        slayerFilter = data().slayerFilter
        miningFilter = data().miningFilter

        // A session lasts one game launch: clear it on start so a restart never keeps
        // yesterday's numbers. Only the total is meant to accumulate across launches.
        viewMode = TrackerViewMode.SESSION
        data().viewMode = TrackerViewMode.SESSION.name
        for (activity in Activity.entries) {
            clearSession(data().bucket(activity.bucketKey))
        }
        data().slayerTypes.values.forEach { clearSession(it) }
        data().miningTypes.values.forEach { clearSession(it) }
        migrateBaseMiningBucket()
        CostLedger.migrateMiningCosts()
        importBossHistoryOnce()
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /**
     * Sack pickups used to be written into the base mining bucket, which no slice ever reads, so
     * ore that went straight into a sack was invisible. Fold whatever is left there into the
     * plain mining slice; the ALL view sums both, so no number changes.
     */
    private fun migrateBaseMiningBucket() {
        val base = data().mining
        val empty = base.sessionItems.isEmpty() && base.totalItems.isEmpty() &&
            base.sessionElapsedSeconds == 0L && base.totalElapsedSeconds == 0L &&
            base.sessionCosts.isEmpty() && base.totalCosts.isEmpty() &&
            base.sessionCoinsSpent == 0.0 && base.totalCoinsSpent == 0.0 &&
            base.sessionBosses == 0L && base.totalBosses == 0L
        if (empty) return

        val target = data().miningTypeBucket(ActivityBucket.MINING_NORMAL)
        base.sessionItems.forEach { (key, amount) -> target.sessionItems.merge(key, amount, Long::plus) }
        base.totalItems.forEach { (key, amount) -> target.totalItems.merge(key, amount, Long::plus) }
        target.sessionElapsedSeconds += base.sessionElapsedSeconds
        target.totalElapsedSeconds += base.totalElapsedSeconds
        target.sessionCoinsSpent += base.sessionCoinsSpent
        target.totalCoinsSpent += base.totalCoinsSpent
        base.sessionCosts.forEach { (key, amount) -> target.sessionCosts.merge(key, amount, Long::plus) }
        base.totalCosts.forEach { (key, amount) -> target.totalCosts.merge(key, amount, Long::plus) }

        base.sessionItems.clear()
        base.totalItems.clear()
        base.sessionElapsedSeconds = 0
        base.totalElapsedSeconds = 0
        base.sessionCoinsSpent = 0.0
        base.totalCoinsSpent = 0.0
        base.sessionCosts.clear()
        base.totalCosts.clear()
    }

    /**
     * One-time migration of the boss counts that were tracked before this mod had a counter.
     * The file is written by hand (see `tools/`), applied on the next launch and then removed,
     * so no personal history ever ships inside the jar.
     */
    private fun importBossHistoryOnce() {
        val path = FabricLoader.getInstance().configDir.resolve("skbprofitracker/boss_history.json")
        if (!Files.exists(path)) return
        val counts = runCatching {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                val type = object : TypeToken<Map<String, Long>>() {}.type
                Gson().fromJson<Map<String, Long>>(reader, type)
            }
        }.getOrNull()
        if (counts != null) {
            counts.forEach { (type, bosses) ->
                if (bosses > 0) data().slayerTypeBucket(type).totalBosses = bosses
            }
        }
        runCatching { Files.delete(path) }
    }

    private fun clearSession(bucket: BucketData) {
        bucket.sessionElapsedSeconds = 0
        bucket.sessionItems.clear()
        bucket.sessionCosts.clear()
        bucket.sessionCoinsSpent = 0.0
        bucket.sessionBosses = 0
        bucket.sessionCorpses = 0
    }

    /**
     * The bucket new data is written into. Slayer drops go to the bucket of the type that is
     * being fought, mining drops to the slice that is running (plain / nucleus / corpse), so
     * the per-type view and the combined view stay consistent.
     */
    fun storage(activity: Activity): BucketData {
        val type = liveType(activity) ?: return data().bucket(bucketName(activity))
        val types = subTypes(activity) ?: return data().bucket(bucketName(activity))
        return types.getOrPut(type) { BucketData() }
    }

    /** Writes into one specific sub-type bucket, creating it on demand. */
    fun sliceStorage(activity: Activity, slice: String): BucketData {
        val types = subTypes(activity) ?: return data().bucket(bucketName(activity))
        return types.getOrPut(slice) { BucketData() }
    }

    /** The sub-type being recorded right now ("Inferno Demonlord", "Corpse", ...), or null. */
    fun liveType(activity: Activity): String? = when (activity) {
        Activity.SLAYER -> ActivityBucket.slayerTypeName
        Activity.MINING -> ActivityBucket.MINING_NORMAL
        Activity.RIFT -> null
    }

    /** Sub-types that already have a bucket on disk, so the menu can list them before they run again. */
    fun storedTypes(activity: Activity): Set<String> = subTypes(activity)?.keys?.toSet() ?: emptySet()

    /** The per-type map of an activity, or null when the activity is not split. */
    private fun subTypes(activity: Activity): MutableMap<String, BucketData>? = when (activity) {
        Activity.SLAYER -> data().slayerTypes
        Activity.MINING -> data().miningTypes
        Activity.RIFT -> null
    }

    /** The filter currently applied to an activity; Rift has no split. */
    fun filterFor(activity: Activity): String = when (activity) {
        Activity.SLAYER -> slayerFilter
        Activity.MINING -> miningFilter
        Activity.RIFT -> ALL
    }

    fun setFilter(activity: Activity, value: String) {
        when (activity) {
            Activity.SLAYER -> slayerFilter = value
            Activity.MINING -> miningFilter = value
            Activity.RIFT -> Unit
        }
    }

    fun add(activity: Activity, key: String, amount: Long) {
        if (amount <= 0) return

        var remaining = amount
        if (activity == Activity.MINING) {
            // Corpse and nucleus loot is booked from chat first; the same items then walk into the
            // inventory a tick later and must not be counted a second time as plain mining.
            remaining -= consumeCredit(key, amount)
            if (remaining <= 0) return
        }

        val bucket = storage(activity)
        bucket.sessionItems.merge(key, remaining, Long::plus)
        bucket.totalItems.merge(key, remaining, Long::plus)
        if (activity == Activity.MINING) rememberMiningAdd(key, remaining)
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /**
     * Books a haul the server already printed in chat (corpse and nucleus loot). Everything here
     * belongs to one mining slice and is never plain mining.
     */
    fun addToMiningSlice(slice: String, key: String, amount: Long) {
        if (amount <= 0) return
        val types = subTypes(Activity.MINING) ?: return
        val bucket = types.getOrPut(slice) { BucketData() }
        bucket.sessionItems.merge(key, amount, Long::plus)
        bucket.totalItems.merge(key, amount, Long::plus)
        creditMining(key, amount)
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    private data class Credit(var amount: Long, val atMs: Long)

    private const val CREDIT_TTL_MS = 60_000L
    private const val RECALL_MS = 5_000L

    private val credits = mutableMapOf<String, Credit>()
    private val recentMiningAdds = ArrayDeque<Triple<String, Long, Long>>()

    /** Removes up to [amount] of a key that was already booked from chat, returns how many it took. */
    private fun consumeCredit(key: String, amount: Long): Long {
        val credit = credits[key] ?: return 0
        if (System.currentTimeMillis() - credit.atMs > CREDIT_TTL_MS) {
            credits.remove(key)
            return 0
        }
        val taken = minOf(amount, credit.amount)
        credit.amount -= taken
        if (credit.amount <= 0) credits.remove(key)
        return taken
    }

    private fun creditMining(key: String, amount: Long) {
        var left = amount
        val now = System.currentTimeMillis()

        // The inventory scan may have already seen the items (chat and pickup race), so pull the
        // count back out of plain mining before falling back to a voucher for future pickups.
        while (left > 0) {
            val index = recentMiningAdds.indexOfLast {
                it.first == key && now - it.third <= RECALL_MS
            }
            if (index < 0) break
            val add = recentMiningAdds.removeAt(index)
            val taken = minOf(left, add.second)
            subtractFromPlainMining(key, taken)
            left -= taken
        }
        if (left > 0) credits[key] = Credit((credits[key]?.amount ?: 0) + left, now)
    }

    private fun rememberMiningAdd(key: String, amount: Long) {
        val now = System.currentTimeMillis()
        recentMiningAdds.addLast(Triple(key, amount, now))
        while (recentMiningAdds.isNotEmpty() && now - recentMiningAdds.first().third > RECALL_MS) {
            recentMiningAdds.removeFirst()
        }
    }

    private fun subtractFromPlainMining(key: String, amount: Long) {
        val bucket = subTypes(Activity.MINING)?.getOrPut(ActivityBucket.MINING_NORMAL) { BucketData() } ?: return
        subtract(bucket.sessionItems, key, amount)
        subtract(bucket.totalItems, key, amount)
    }

    private fun subtract(items: MutableMap<String, Long>, key: String, amount: Long) {
        val current = items[key] ?: return
        val left = current - amount
        if (left <= 0) items.remove(key) else items[key] = left
    }

    fun currentView(): ProfitView? {
        val activity = ActivityBucket.current ?: return null
        return view(activity)
    }

    fun view(activity: Activity, filter: String = filterFor(activity)): ProfitView {
        val (entries, elapsedSeconds, bosses) = entries(activity, filter)

        val items = entries.map { (key, amount) ->
            val price = unitPrice(key)
            ItemLine(
                name = displayName(key),
                key = key,
                amount = amount,
                unitPrice = price,
                value = (price ?: 0.0) * amount,
            )
        }.sortedWith(compareByDescending<ItemLine> { it.unitPrice != null }.thenByDescending { it.value })

        val totalValue = items.sumOf { it.value }
        val hours = elapsedSeconds / 3600.0
        val perHour = if (hours > 0.0) totalValue / hours else 0.0
        val corpses = costBuckets(activity, filter).sumOf { bucket ->
            when (viewMode) {
                TrackerViewMode.SESSION -> bucket.sessionCorpses
                TrackerViewMode.TOTAL -> bucket.totalCorpses
            }
        }

        return ProfitView(
            activity = activity,
            mode = viewMode,
            items = items,
            totalValue = totalValue,
            perHour = perHour,
            elapsedSeconds = elapsedSeconds,
            unpricedTypes = items.count { it.unitPrice == null },
            bosses = bosses,
            corpses = corpses,
            filter = filter,
            showPerHour = showPerHour(activity, filter),
        )
    }

    /**
     * Whether a per-hour figure is meaningful for this slice: slayer and the Rift always are,
     * mining only for plain mining, never for one-off nucleus or corpse runs.
     */
    private fun showPerHour(activity: Activity, filter: String): Boolean = when (activity) {
        Activity.SLAYER, Activity.RIFT -> true
        Activity.MINING -> filter == ActivityBucket.MINING_NORMAL
    }

    /**
     * Value, elapsed seconds and unpriced row count of one sub-type, always in Total mode so
     * the chat menu shows the lifetime picture regardless of what the overlay is set to.
     */
    fun typeSummary(activity: Activity, type: String): Triple<Double, Long, Int> {
        // "All" is not a bucket of its own: it is every slice added together.
        val buckets = if (type == ALL) {
            costBuckets(activity, ALL)
        } else {
            listOf(subTypes(activity)?.get(type) ?: BucketData())
        }

        var value = 0.0
        var unpriced = 0
        var seconds = 0L
        for (bucket in buckets) {
            for ((key, amount) in bucket.totalItems) {
                val price = unitPrice(key)
                if (price == null) unpriced++ else value += price * amount
            }
            seconds += bucket.totalElapsedSeconds
        }
        return Triple(value, seconds, unpriced)
    }

    /** Every bucket a cost has to be summed over for the given slice. */
    fun costBuckets(activity: Activity, filter: String): List<BucketData> {
        val types = subTypes(activity)
        if (types == null) return listOf(data().bucket(bucketName(activity)))
        if (filter != ALL) return listOf(types[filter] ?: BucketData())
        return buildList {
            add(data().bucket(bucketName(activity)))
            addAll(types.values)
        }
    }

    /**
     * One completed boss. The quest line is already gone by the time the completion message
     * arrives on some kills, so the caller passes the type it saw being fought.
     */
    fun addBossKill(type: String?) {
        val key = type ?: return
        val bucket = data().slayerTypeBucket(key)
        bucket.sessionBosses += 1
        bucket.totalBosses += 1
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /**
     * One opened Frozen Corpse, counted when its loot block completes.
     *
     * The inventory diff cannot be trusted for the count: the key disappears in the same
     * breath as the loot arrives, and a scan that lands while the corpse UI is open skips
     * the removal entirely, which is how three opened corpses once produced two key costs.
     */
    fun addCorpseOpened(slice: String = ActivityBucket.MINING_CORPSE) {
        val bucket = data().miningTypeBucket(slice)
        bucket.sessionCorpses += 1
        bucket.totalCorpses += 1
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /** Manual correction from the overlay's [+]/[-] buttons, applied to the visible mode. */
    fun adjust(activity: Activity, key: String, delta: Long) {
        val items = currentItems(activity)
        val next = (items[key] ?: 0L) + delta
        if (next <= 0L) items.remove(key) else items[key] = next
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /** Drops a whole row (the [x] button). */
    fun remove(activity: Activity, key: String) {
        currentItems(activity).remove(key)
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /** Clears the visible slice: one sub-type when filtered, everything when on [ALL]. */
    fun reset(activity: Activity, total: Boolean) {
        val buckets = costBuckets(activity, filterFor(activity))

        for (bucket in buckets) {
            if (total) {
                bucket.totalElapsedSeconds = 0
                bucket.totalItems.clear()
                bucket.totalCosts.clear()
                bucket.totalCoinsSpent = 0.0
                bucket.totalBosses = 0
                bucket.totalCorpses = 0
            }
            clearSession(bucket)
        }
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    private fun currentItems(activity: Activity): MutableMap<String, Long> {
        val bucket = storage(activity)
        return when (viewMode) {
            TrackerViewMode.SESSION -> bucket.sessionItems
            TrackerViewMode.TOTAL -> bucket.totalItems
        }
    }

    private fun bucketName(activity: Activity): String = activity.bucketKey

    private fun bucketEntries(bucket: BucketData): Triple<Map<String, Long>, Long, Long> = when (viewMode) {
        TrackerViewMode.SESSION -> Triple(bucket.sessionItems, bucket.sessionElapsedSeconds, bucket.sessionBosses)
        TrackerViewMode.TOTAL -> Triple(bucket.totalItems, bucket.totalElapsedSeconds, bucket.totalBosses)
    }

    /**
     * Item counts, seconds and boss kills of one slice. [ALL] merges the pre-split bucket with
     * every per-type bucket so history imported before the split still shows up.
     */
    private fun entries(activity: Activity, filter: String): Triple<Map<String, Long>, Long, Long> {
        val types = subTypes(activity)
        if (types == null) return bucketEntries(data().bucket(bucketName(activity)))
        if (filter != ALL) return bucketEntries(types[filter] ?: BucketData())

        val merged = mutableMapOf<String, Long>()
        var elapsed = 0L
        var bosses = 0L
        for (bucket in costBuckets(activity, ALL)) {
            val (items, seconds, kills) = bucketEntries(bucket)
            items.forEach { (key, amount) -> merged.merge(key, amount, Long::plus) }
            elapsed += seconds
            bosses += kills
        }
        return Triple(merged, elapsed, bosses)
    }

    private fun data() = PersistentDataManager.skbprofitrackerData

    /**
     * Market price first (bazaar, then lowest bin), NPC sell price as the last resort.
     * An item is never worth less than what the NPC pays for it, so a crashed or missing
     * auction price cannot drag a row to zero.
     */
    private fun unitPrice(key: String): Double? {
        if (key == COINS_KEY) return 1.0
        if (!key.endsWith("+100") && !key.endsWith("+200")) {
            return withNpcFloor(key, PriceCache.price(key, priceMode))
        }

        val level1Key = key.substringBefore("+")
        val maxedPrice = PriceCache.price(key, priceMode) ?: return null
        val level1Price = PriceCache.price(level1Key, priceMode) ?: return null
        return (maxedPrice - level1Price).coerceAtLeast(0.0)
    }

    private fun withNpcFloor(key: String, price: Double?): Double? {
        val npc = ItemTable.getByKey(key)?.npcPrice ?: return price
        if (price == null) return npc
        return maxOf(price, npc)
    }

    private fun displayName(key: String): String {
        if (key == COINS_KEY) return "Coins"
        ItemTable.getByKey(key)?.let { return it.name }

        if (!key.endsWith("+100") && !key.endsWith("+200")) return key

        val level = key.substringAfterLast("+")
        val level1Key = key.substringBefore("+")
        val baseName = ItemTable.getByKey(level1Key)?.name ?: return key
        return baseName.replaceFirst("[Lvl 1]", "[Lvl $level]")
    }
}
