package com.skbprofitracker.tracker

import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.events.models.WorldChangedEvent
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.price.PriceCache
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.TabListAndScoreboardUtils
import com.skbprofitracker.utils.WorldUtils
import com.skbprofitracker.utils.data.PersistentDataManager
import com.skbprofitracker.utils.data.SkbData

enum class Activity {
    MINING,
    SLAYER,
    RIFT,
    ;

    val bucketKey: String get() = name.lowercase()
}

/**
 * Decides which activity is currently running and for how long it has been running.
 *
 * Only one bucket can be open at a time, and the region gate is the primary switch: a
 * Slayer quest started in a mining area stays in the mining bucket, which keeps the two
 * buckets from ever double counting the same drop.
 *
 * The region names come from the zone line of the scoreboard (`⏣ Graveyard`) rather than
 * the area line of the tab list, because a Slayer boss is tied to a zone, not a whole
 * island. The zone list is the one SkyHanni uses for its Slayer tracker, so an area that
 * works there works here too.
 *
 * Active seconds are driven by signals rather than wall clock, so standing in a lobby does
 * not inflate the denominator:
 * - a strong signal (block break, entity attack) opens the activity window,
 * - any signal past that point extends it,
 * - the window closes [SLAYER_GRACE_MS] / [MINING_GRACE_MS] / [RIFT_GRACE_MS] after the
 *   last signal, whichever activity is running.
 *
 * When a bucket closes, attribution keeps running for [TAIL_MS] so a drop that lands right
 * after the last block still belongs to the activity that produced it.
 */
object ActivityBucket {
    /**
     * How long a bucket keeps counting after the last signal, per activity.
     *
     * Feesh uses a flat 180s because fishing is mostly waiting: between two catches there can
     * be a minute of nothing, and cutting that out would make the hourly number jump around.
     * Slayer and Rift have no such dead time (you are attacking or being attacked the whole
     * time), so they get a much shorter window; mining sits in between because walking to the
     * next vein is normal.
     */
    private const val SLAYER_GRACE_MS = 60_000L
    private const val MINING_GRACE_MS = 90_000L
    private const val RIFT_GRACE_MS = 60_000L
    private const val TAIL_MS = 15_000L
    private const val SECOND_MS = 1_000L

    /** Mining sub-types, mirroring the slayer split: plain mining, nucleus runs, corpse runs. */
    const val MINING_NORMAL = "Mining"
    const val MINING_NUCLEUS = "Nucleus"
    const val MINING_CORPSE = "Corpse"
    private val MINING_TYPES = listOf(MINING_NORMAL, MINING_NUCLEUS, MINING_CORPSE)

    /** Corpse loot arrives right after `XXX CORPSE LOOT!`, a short window is enough. */
    private const val CORPSE_LOOT_WINDOW_MS = 15_000L

    /**
     * A nucleus run spans several structures: keys and goblin eggs are consumed to obtain the
     * crystals, then the crystals are placed one by one. The reward only comes at the end, so
     * the window has to be generous.
     */
    private const val NUCLEUS_WINDOW_MS = 60_000L

    /**
     * The SkyBlock sidebar renders the quest as a title line and puts the boss on the line
     * below, so the separator is usually a line break rather than a colon. Matching the bare
     * prefix covers both layouts.
     */
    private const val SLAYER_QUEST_PREFIX = "Slayer Quest"

    /** SkyHanni uses this exact sidebar line to detect that the slayer boss has spawned. */
    private const val BOSS_FIGHT_LINE = "Slay the boss!"

    /**
     * Hypixel keeps inventing new mineshaft names ("Glacite Mineshafts", "Lapis Mineshaft", ...),
     * so mining is matched on keywords instead of an exact area list. Both the tab-list `Area:`
     * line and the sidebar zone line are checked, because a mineshaft can replace either one.
     */
    private val MINING_AREA_HINTS = listOf(
        "dwarven mines",
        "crystal hollows",
        "glacite",
        "mineshaft",
    )

    private fun isMiningArea(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.lowercase()
        return MINING_AREA_HINTS.any { normalized.contains(it) }
    }

    private val RIFT_WORLDS = setOf(
        "The Rift",
        "Rift Dimension",
    )

    private val RIFT_ZONES = setOf(
        "Stillgore Chateau",
        "Stillgore Château",
    )

    /**
     * The zones a boss can actually be fought in, per slayer. SkyHanni's
     * `checkTypeForCurrentArea` table.
     *
     * Matching a flat set of every slayer zone would keep a Revenant quest open while
     * standing in the Crimson Isle wasteland, so the quest's boss decides which zones count.
     */
    private enum class SlayerType(val questName: String, val zones: Set<String>) {
        REVENANT("Revenant Horror", setOf("Graveyard", "Revenant Cave", "Crypts")),
        TARANTULA(
            "Tarantula Broodfather",
            setOf("Spider Mound", "Arachne's Burrow", "Arachne's Sanctuary", "Burning Desert"),
        ),
        SVEN("Sven Packmaster", setOf("Ruins", "Howling Cave", "Soul Cave", "Spirit Cave")),
        VOIDGLOOM("Voidgloom Seraph", setOf("Void Sepulture", "Zealot Bruiser Hideout", "Dragon's Nest")),
        INFERNO("Inferno Demonlord", setOf("Stronghold", "The Wasteland", "Smoldering Tomb")),
        RIFTSTALKER("Riftstalker Bloodfiend", setOf("Stillgore Château", "Stillgore Chateau")),
    }

    /** Manual pause stops the timer and the ledger, as opposed to the automatic grace. */
    var isPaused: Boolean = false
        set(value) {
            field = value
            if (value) {
                hasStrongSignal = false
                recentStrongSignals.clear()
            }
        }

    /**
     * Timestamps of recent strong signals, used to tell "I am actually mining" from "I hit a
     * block on the way past". A manual pause survives the latter but not ten blocks in ten
     * seconds, which is the cheapest unambiguous proof of sustained work.
     */
    private val recentStrongSignals = ArrayDeque<Long>()

    private const val AUTO_RESUME_SIGNALS = 10
    private const val AUTO_RESUME_WINDOW_MS = 10_000L

    var current: Activity? = null
        private set

    var world: String = ""
        private set

    var zone: String = ""
        private set

    private var lastStrongMs = 0L
    private var lastSignalMs = 0L
    private var closedAtMs = 0L
    private var lastCountMs = 0L
    private var hasStrongSignal = false
    private var slayerQuest: SlayerType? = null

    /** The type fought most recently, kept after the quest line disappears so the completion
     * message that ends a boss can still be attributed to the right bucket. */
    var lastSlayerTypeName: String? = null
        private set

    /** The slayer type being fought right now ("Inferno Demonlord", "Revenant Horror", ...), or null. */
    val slayerTypeName: String?
        get() = slayerQuest?.questName

    /** Every slayer type the mod knows about, so the chat menu can list them before any data exists. */
    val slayerTypeNames: List<String>
        get() = SlayerType.entries.map { it.questName }

    private var bossFight: Boolean = false

    /** Every mining slice the mod knows about, so the chat menu can list them before any data exists. */
    val miningTypeNames: List<String>
        get() = MINING_TYPES

    fun init() {
        EventBus.subscribe(ClientTickEvent::class) { onTick() }
        EventBus.subscribe(WorldChangedEvent::class) { onWorldChanged() }
    }

    /** One boss died. Printed as `SLAYER QUEST COMPLETE!` by the server. */
    fun onBossSlain() {
        if (current != Activity.SLAYER) return
        BucketLedger.addBossKill(lastSlayerTypeName)
    }

    fun onStrongSignal() {
        val now = System.currentTimeMillis()

        // Keep collecting even while paused: the burst check below needs them.
        recentStrongSignals.addLast(now)
        while (recentStrongSignals.isNotEmpty() && now - recentStrongSignals.first() > AUTO_RESUME_WINDOW_MS) {
            recentStrongSignals.removeFirst()
        }

        if (isPaused) {
            // Two things un-pause: starting to slay inside a running quest, or a sustained
            // burst of work. A single block or a mob on the way past must not undo a pause.
            val slayerStarted = current == Activity.SLAYER && slayerQuest != null
            val sustained = recentStrongSignals.size >= AUTO_RESUME_SIGNALS
            if (!slayerStarted && !sustained) return

            isPaused = false
            if (Debug.enabled) Debug.log("[pause] auto-resumed after ${recentStrongSignals.size} signals")
        }
        if (Debug.enabled && (lastSignalMs == 0L || now - lastSignalMs > 10_000L)) {
            Debug.log("[signal] strong after ${if (lastSignalMs == 0L) "never" else "${(now - lastSignalMs) / 1000}s"}")
        }
        lastStrongMs = now
        lastSignalMs = now
        hasStrongSignal = true
    }

    /** Pickups and taking damage: can extend a window but never open one. */
    fun onWeakSignal() {
        if (isPaused || !hasStrongSignal) return
        lastSignalMs = System.currentTimeMillis()
    }

    /** The bucket a drop belongs to at this very moment, or null when nothing is running. */
    fun bucketForPickup(): Activity? {
        if (isPaused) return null
        return current
    }

    fun isCounting(): Boolean {
        if (isPaused) return false
        val activity = current ?: return false
        return hasStrongSignal && System.currentTimeMillis() - lastSignalMs <= graceMs(activity)
    }

    fun elapsedSeconds(activity: Activity): Long = data().bucket(activity.bucketKey).sessionElapsedSeconds

    fun resetSession(activity: Activity) {
        BucketLedger.reset(activity, total = false)
    }

    fun resetTotal(activity: Activity) {
        BucketLedger.reset(activity, total = true)
    }

    private fun onTick() {
        val now = System.currentTimeMillis()

        refreshRegion()
        updateCurrent(now)

        // A boss fight is one continuous stretch of work even when no sword swing lands for a
        // while: dodging a phase, waiting out a damage window, or chasing the boss across the
        // arena can leave gaps well past a minute. The sidebar only shows "Slay the boss!"
        // while a boss is actually alive, so holding the window open here cannot keep a dead
        // session alive - and a boss that is ignored despawns or kills the player, which ends
        // the line on its own.
        if (bossFight && !isPaused && current == Activity.SLAYER) {
            lastSignalMs = now
        }

        if (now - lastCountMs >= SECOND_MS) {
            lastCountMs = now
            countSecond()
        }
    }

    private fun onWorldChanged() {
        current = null
        world = ""
        zone = ""
        lastStrongMs = 0L
        lastSignalMs = 0L
        closedAtMs = 0L
        hasStrongSignal = false
        slayerQuest = null
        bossFight = false
        recentStrongSignals.clear()
    }

    private fun refreshRegion() {
        world = WorldUtils.getWorldName().orEmpty()
        zone = WorldUtils.getZoneName().orEmpty()
        val lines = TabListAndScoreboardUtils.getUnformattedScoreboardLines()
        slayerQuest = readSlayerQuest(lines)
        if (slayerQuest != null) lastSlayerTypeName = slayerQuest!!.questName
        bossFight = lines.any { it.trim() == BOSS_FIGHT_LINE }
    }

    private fun readSlayerQuest(lines: List<String>): SlayerType? {
        if (lines.none { isQuestLine(it) }) return null

        return SlayerType.entries.firstOrNull { type ->
            lines.any { it.contains(type.questName, ignoreCase = true) }
        }
    }

    /** `SLAYER QUEST COMPLETE!` also starts with the prefix once the case is normalised. */
    private fun isQuestLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith(SLAYER_QUEST_PREFIX, ignoreCase = true)) return false
        return !trimmed.contains("COMPLETE", ignoreCase = true)
    }

    private fun resolveActivity(): Activity? {
        if (!WorldUtils.isInSkyblock()) return null
        if (world in RIFT_WORLDS || zone in RIFT_ZONES) return Activity.RIFT
        if (isMiningArea(world) || isMiningArea(zone)) return Activity.MINING
        if (slayerQuest != null && zone in slayerQuest!!.zones) return Activity.SLAYER
        return null
    }

    private fun updateCurrent(now: Long) {
        val resolved = resolveActivity()

        // SkyHanni treats the "Slay the boss!" sidebar line as the start of a boss fight.
        // A boss only spawns after the player actually ground out the quest, so that line is
        // a strong enough signal to undo a manual pause.
        if (isPaused && bossFight && resolved == Activity.SLAYER) {
            isPaused = false
        }

        if (resolved != null) {
            current = resolved
            closedAtMs = now
            return
        }

        val open = current ?: return
        if (now - closedAtMs > TAIL_MS) {
            current = null
            hasStrongSignal = false
        }
        // Else: inside the tail window, keep attributing to the closed bucket.
    }

    private fun graceMs(activity: Activity): Long = when (activity) {
        Activity.MINING -> MINING_GRACE_MS
        Activity.SLAYER -> SLAYER_GRACE_MS
        Activity.RIFT -> RIFT_GRACE_MS
    }

    private fun countSecond() {
        if (!isCounting()) return
        val activity = current ?: return
        val bucket = BucketLedger.storage(activity)
        bucket.sessionElapsedSeconds += 1
        bucket.totalElapsedSeconds += 1
        PersistentDataManager.saveSkbDataToFileAsync()
    }

    /**
     * Human readable state, used by `/skbprofit debug` to explain why a bucket did or did
     * not open without needing to read the client log.
     */
    fun debugLines(): List<String> {
        val signalAge = if (lastSignalMs == 0L) {
            "never"
        } else {
            "${(System.currentTimeMillis() - lastSignalMs) / 1000}s ago"
        }
        val state = when {
            isPaused -> "paused"
            isCounting() -> "counting"
            else -> "idle"
        }

        return buildList {
            add("§8world§7=${world.ifEmpty { "-" }} §8zone§7=${zone.ifEmpty { "-" }} §8quest§7=${slayerQuest?.name ?: "-"}")
            add("§8bucket§7=${current?.bucketKey ?: "-"} §8state§7=$state §8signal§7=$signalAge")
            add(
                "§8mining§7=inventory " +
                    "§8corpse§7=chat " +
                    "§8nucleus§7=chat"
            )
            add(
                "§8resolve§7=${resolveActivity()?.bucketKey ?: "-"} " +
                    "§8zoneHit§7=${slayerQuest?.let { zone in it.zones } ?: false} " +
                    "§8worldHit§7=${isMiningArea(world)} "
            )
            add("§8bazaar§7=${ageSeconds(PriceCache.lastBazaarUpdateMs)}s §8lbin§7=${ageSeconds(PriceCache.lastAuctionUpdateMs)}s §8items§7=${ItemTable.size}")
            TabListAndScoreboardUtils.getUnformattedScoreboardLines()
                .filter { it.contains("Slayer") || it.contains("⏣") }
                .forEach { add("§8side§7=§f${it.trim()}") }
        }
    }

    private fun ageSeconds(timestampMs: Long): Long =
        if (timestampMs == 0L) -1L else (System.currentTimeMillis() - timestampMs) / 1000

    private fun data(): SkbData = PersistentDataManager.skbprofitrackerData
}
