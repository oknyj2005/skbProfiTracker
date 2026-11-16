package com.skbprofitracker.events.publishers

import com.skbprofitracker.SkbMod
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.CostLedger
import com.skbprofitracker.utils.ChatUtils
import com.skbprofitracker.utils.ChatUtils.removeFormatting
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.TabListAndScoreboardUtils
import com.skbprofitracker.utils.WorldUtils
import com.skbprofitracker.utils.getScreenCompat

/**
 * Watches the purse line and reports the coin costs of the tracked activities.
 *
 * A slayer quest start is the only coin cost the mod cares about, and its price is not a
 * constant: shards, mayors and slayer levels all change it. Reading the purse before and
 * after the quest start message gives the exact amount the server took, so no table has to
 * be maintained and no discount has to be modelled.
 *
 * The purse stays untouched by the mod on purpose: it only ever reads it. In the Rift the
 * scoreboard shows `Motes:` instead, so the watcher simply never matches there and the Rift
 * bucket never gets a coin cost.
 */
object PurseWatcher {
    private val PURSE_LINE = Regex("^(?:Piggy|Purse): ([\\d,]+)$")
    private const val SLAYER_QUEST_START_MARKER = "SLAYER QUEST STARTED!"
    private const val SLAYER_QUEST_COMPLETE_MARKER = "SLAYER QUEST COMPLETE!"
    private const val SLAYER_QUEST_WINDOW_MS = 1_500L

    /**
     * A quest start costs at most a tier 5 spawn (100k) and at least a tier 1 (2k). Anything
     * outside that range is pocket change from something else happening in the same window
     * (a bazaar buy, an auction bid) and must not be booked as a slayer cost.
     */
    private const val MIN_SPAWN_COST = 1_000L
    private const val MAX_SPAWN_COST = 200_000L

    private var lastPurse: Long? = null
    private var questStartedMs = 0L
    private var lastBossSlainMs = 0L
    private const val BOSS_KILL_DEDUPE_MS = 3_000L

    fun init() {
        EventBus.subscribe(ClientTickEvent::class) { onClientTick() }
        EventBus.subscribe(ChatEvent::class) { onChat(it) }
    }

    private const val TREASURE_CHEST_MARKER = "You uncovered a treasure chest!"

    private fun onChat(event: ChatEvent) {
        val text = event.formattedText.removeFormatting()
        if (text.contains(SLAYER_QUEST_START_MARKER)) {
            questStartedMs = System.currentTimeMillis()
        }
        if (text.contains(TREASURE_CHEST_MARKER)) {
            ActivityBucket.onTreasureChestOpened()
        }
        if (text.contains(SLAYER_QUEST_COMPLETE_MARKER)) {
            val now = System.currentTimeMillis()
            if (now - lastBossSlainMs > BOSS_KILL_DEDUPE_MS) {
                lastBossSlainMs = now
                ActivityBucket.onBossSlain()
            }
        }
    }

    private fun onClientTick() {
        if (!WorldUtils.isInSkyblock()) {
            lastPurse = null
            return
        }

        val purse = readPurse() ?: return
        val previous = lastPurse
        lastPurse = purse

        if (previous == null || purse == previous) return

        if (purse > previous) {
            onCoinsGained(purse - previous)
            return
        }

        val spent = previous - purse
        if (spent <= 0) return

        val withinQuestWindow = System.currentTimeMillis() - questStartedMs <= SLAYER_QUEST_WINDOW_MS
        if (Debug.enabled && spent >= 10_000) {
            val window = if (withinQuestWindow) "yes" else "no"
            ChatUtils.sendLocalChat("§8[purse] -${spent} (quest window: $window)", true)
        }
        if (!withinQuestWindow) return

        if (spent !in MIN_SPAWN_COST..MAX_SPAWN_COST) {
            if (Debug.enabled) {
                ChatUtils.sendLocalChat("§8[purse] ignored $spent coins near a quest start", true)
            }
            questStartedMs = 0L
            return
        }

        CostLedger.onCoinsSpent(spent.toDouble())
        questStartedMs = 0L
    }

    /**
     * Coins dropped by the mobs themselves (slayer bosses, and the occasional coin drop while
     * mining) are income, and SkyHanni books them as a `SKYBLOCK_COIN` line of the item
     * tracker, so the mod does the same. Selling to a bazaar or an NPC also raises the purse,
     * but always with a screen open, hence the `currentScreen == null` guard. The numbers are
     * only trusted while a bucket is actually counting, otherwise idle purse drift would be
     * recorded as profit.
     */
    private fun onCoinsGained(gained: Long) {
        if (gained <= 0) return
        val activity = ActivityBucket.current ?: return
        if (ActivityBucket.isPaused) return
        if (SkbMod.mc.getScreenCompat() != null) return

        BucketLedger.add(activity, BucketLedger.COINS_KEY, gained)
        if (Debug.enabled && gained >= 10_000) {
            ChatUtils.sendLocalChat("§8[purse] +${gained} coins -> ${activity.bucketKey}", true)
        }
    }

    private fun readPurse(): Long? {
        val lines = TabListAndScoreboardUtils.getUnformattedScoreboardLines()
        for (line in lines) {
            val match = PURSE_LINE.matchEntire(line.trim()) ?: continue
            return match.groupValues[1].replace(",", "").toLongOrNull()
        }
        return null
    }
}
