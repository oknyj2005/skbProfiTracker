package com.skbprofitracker.tracker

import com.skbprofitracker.SkbMod
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.events.models.WorldChangedEvent
import com.skbprofitracker.events.publishers.FossilExcavation
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.price.PriceCache
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.TabListAndScoreboardUtils
import com.skbprofitracker.utils.WorldUtils
import com.skbprofitracker.utils.getScreenCompat
import com.skbprofitracker.utils.data.BucketData
import com.skbprofitracker.utils.data.PersistentDataManager
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

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
    private const val MINING_GRACE_MS = 60_000L
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

    private var pendingGlaciteTransfer: GlaciteTravel.Transfer? = null

    private val gate = CrystalHollowsGate(
        autoResumeSignals = AUTO_RESUME_SIGNALS,
        autoResumeWindowMs = AUTO_RESUME_WINDOW_MS,
    )

    /** Manual pause stops the timer and the ledger, as opposed to the automatic grace. */
    var isPaused: Boolean
        get() = gate.isPaused
        set(value) {
            if (value) pendingGlaciteTransfer = null
            gate.setGlobalPaused(value)
            if (value) lastAttackMs = 0L
            recordBucket(System.currentTimeMillis())
        }

    /**
     * Session-level toggle for plain mining inside Crystal Hollows.
     * Always starts false on launch, world change, disconnect, or global pause.
     */
    var hollowsMiningActive: Boolean
        get() = gate.hollowsMiningActive
        set(value) {
            gate.setHollowsMiningActive(value)
            if (!value) lastAttackMs = 0L
            recordBucket(System.currentTimeMillis())
        }

    private const val TELEPORT_SQR = 64.0 * 64.0
    private var lastPosX = 0.0
    private var lastPosY = 0.0
    private var lastPosZ = 0.0
    private var hasPos = false

    private const val AUTO_RESUME_SIGNALS = 10
    private const val AUTO_RESUME_WINDOW_MS = 10_000L

    var current: Activity? = null
        private set

    var world: String = ""
        private set

    var zone: String = ""
        private set

    /** Last time the player actually swung at or damaged a mob; purse gains are gated on it. */
    private var lastAttackMs = 0L
    private val lastSignalMs: Long get() = gate.lastSignalMs

    internal data class PickupTarget(val activity: Activity, val slice: String?)

    private data class BucketMark(val at: Long, val target: PickupTarget?)

    /** Hypixel batches sack changes for up to a minute, so credits are attributed to the
     * bucket that was running when the covered window started. */
    private val bucketHistory = ArrayDeque<BucketMark>()
    private const val HISTORY_MS = 120_000L
    private var closedAtMs = 0L
    private var lastCountMs = 0L
    private var lastStateSignature = ""
    /** Which action last started the clock; printed on every state change so a surprise
     *  start can be traced back without enabling verbose logging first. */
    private val lastSignalSource: String get() = gate.lastSignalSource
    private val hasStrongSignal: Boolean get() = gate.hasStrongSignal
    private var slayerQuest: SlayerType? = null

    /** The type fought most recently, kept after the quest line disappears so the completion
     * message that ends a boss can still be attributed to the right bucket. */
    private var lastSlayerTypeName: String? = null

    /** The slayer type being fought right now ("Inferno Demonlord", "Revenant Horror", ...), or null. */
    val slayerTypeName: String?
        get() = slayerQuest?.questName

    internal val slayerPickupSlice: String?
        get() = lastSlayerTypeName.takeIf { current == Activity.SLAYER }

    /** Every slayer type the mod knows about, so the chat menu can list them before any data exists. */
    val slayerTypeNames: List<String>
        get() = SlayerType.entries.map { it.questName }

    private var bossFight: Boolean = false

    /** Every mining slice the mod knows about, so the chat menu can list them before any data exists. */
    val miningTypeNames: List<String>
        get() = MINING_TYPES

    fun init() {
        EventBus.subscribe(ClientTickEvent::class, ::onTick)
        EventBus.subscribe(WorldChangedEvent::class) { onWorldChanged() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            pendingGlaciteTransfer = null
            bucketHistory.clear()
            resetWorldState()
        }
    }

    /** One boss died. Printed as `SLAYER QUEST COMPLETE!` by the server. */
    fun onBossSlain() {
        if (isPaused || current != Activity.SLAYER) return
        BucketLedger.addBossKill(lastSlayerTypeName)
    }

    fun onMiningContact(position: Long) {
        if (current != Activity.MINING || SkbMod.mc.getScreenCompat() != null) return
        val inHollows = isCrystalHollowsArea()
        if (inHollows && !hollowsMiningActive) return
        val now = System.currentTimeMillis()
        val wasPaused = isPaused
        val previousSignalMs = lastSignalMs
        val outcome = gate.onMiningContact(position, now, graceMs(Activity.MINING), inHollows)
        logStrongSignalOutcome(outcome, wasPaused, previousSignalMs, "mining", now)
    }

    fun onAttack() {
        if (SkbMod.mc.getScreenCompat() != null) return
        val activity = current ?: return
        val inHollows = isCrystalHollowsArea()
        if (activity == Activity.MINING && inHollows && !hollowsMiningActive) return
        val now = System.currentTimeMillis()
        lastAttackMs = now
        if (activity != Activity.MINING) {
            val wasPaused = isPaused
            val previousSignalMs = lastSignalMs
            val outcome = gate.onStrongSignal(
                now = now,
                graceMs = graceMs(activity),
                source = "attack",
                isMining = false,
                inCrystalHollows = inHollows,
                slayerStarted = activity == Activity.SLAYER && slayerQuest != null,
            )
            logStrongSignalOutcome(outcome, wasPaused, previousSignalMs, "attack", now)
        }
    }

    private fun logStrongSignalOutcome(
        outcome: CrystalHollowsGate.SignalOutcome,
        wasPaused: Boolean,
        previousSignalMs: Long,
        source: String,
        now: Long,
    ) {
        if (outcome == CrystalHollowsGate.SignalOutcome.IGNORED || outcome == CrystalHollowsGate.SignalOutcome.PAUSED) return
        if (wasPaused && !isPaused) {
            if (Debug.enabled) Debug.log("[pause] auto-resumed after ${gate.recentStrongSignalCount} signals")
            recordBucket(now)
        }
        if (Debug.enabled && (previousSignalMs == 0L || now - previousSignalMs > 10_000L)) {
            Debug.log("[signal] strong after ${if (previousSignalMs == 0L) "never" else "${(now - previousSignalMs) / 1000}s"} from $source")
        }
        if (outcome == CrystalHollowsGate.SignalOutcome.ARMED) {
            Debug.log("[signal] armed by $source (one more to start)")
        }
    }

    /** A coordinate jump usually ends the active window. Base Camp travel keeps its remainder. */
    private fun checkTeleport(now: Long) {
        val player = SkbMod.mc.player ?: return
        val pos = player.position()
        if (!hasPos) {
            hasPos = true
            lastPosX = pos.x
            lastPosY = pos.y
            lastPosZ = pos.z
            return
        }
        val dx = pos.x - lastPosX
        val dy = pos.y - lastPosY
        val dz = pos.z - lastPosZ
        if (dx * dx + dy * dy + dz * dz > TELEPORT_SQR) {
            val keepMiningClock = isGlaciteMiningActive(now) &&
                GlaciteTravel.includes(world, zone) &&
                GlaciteTravel.includes(WorldUtils.getWorldName().orEmpty(), WorldUtils.getZoneName().orEmpty())
            if (hasStrongSignal && !keepMiningClock) Debug.log("[signal] reset (teleport)")
            clearSignals(keepMiningClock)
        }
        lastPosX = pos.x
        lastPosY = pos.y
        lastPosZ = pos.z
    }

    /** Pickups and taking damage: can extend a window but never open one. */
    fun onWeakSignal(activity: Activity, observedAt: Long = System.currentTimeMillis()) {
        val now = System.currentTimeMillis()
        if (current != activity) return
        gate.onWeakSignal(
            now = now,
            observedAt = observedAt,
            graceMs = graceMs(activity),
            isMining = activity == Activity.MINING,
            inCrystalHollows = isCrystalHollowsArea(),
        )
    }

    /** The bucket that was active at [whenMs], used to place delayed sack credits. */
    internal fun pickupTargetAt(whenMs: Long): PickupTarget? {
        var matched = false
        var found: PickupTarget? = null
        for (mark in bucketHistory) {
            if (mark.at <= whenMs) {
                matched = true
                found = mark.target
            } else {
                break
            }
        }
        return if (matched) found else currentPickupTarget()
    }

    private fun currentPickupTarget(): PickupTarget? {
        if (!isPickupAllowed()) return null
        val activity = current ?: return null
        val slice = if (activity == Activity.SLAYER) slayerPickupSlice else BucketLedger.liveType(activity)
        return PickupTarget(activity, slice)
    }

    private fun recordBucket(now: Long) {
        val target = currentPickupTarget()
        val last = bucketHistory.lastOrNull()
        if (last != null && last.target == target && now - last.at < 1_000L) return
        bucketHistory.addLast(BucketMark(now, target))
        while (bucketHistory.size > 1 && now - bucketHistory.first().at > HISTORY_MS) {
            bucketHistory.removeFirst()
        }
    }

    /** Whether ordinary loot/pickup/timing is allowed right now. */
    fun isPickupAllowed(): Boolean =
        gate.isPickupAllowed(current == Activity.MINING, isCrystalHollowsArea())

    /** Whether mining chat loot blocks (Nucleus / Corpse) and their key costs may be booked. */
    fun canBookMiningSliceLoot(): Boolean =
        gate.canBookMiningSliceLoot(current == Activity.MINING)

    /** Whether the HUD and status line should treat the active tracker as paused. */
    fun isMiningPausedInUi(): Boolean =
        gate.isPausedInUi(current == Activity.MINING, isCrystalHollowsArea())

    /** Toggles Crystal Hollows mining when in CH, or global pause everywhere else. */
    fun togglePause(): Boolean {
        val inHollows = isCrystalHollowsArea()
        val started = gate.togglePause(inHollows)
        if (!started) {
            if (!inHollows) pendingGlaciteTransfer = null
            lastAttackMs = 0L
        }
        recordBucket(System.currentTimeMillis())
        return started
    }

    /** Idempotently starts/resumes tracking for `/skbprofit start` and `/skbprofit resume`. */
    fun startOrResume() {
        gate.startOrResume(isCrystalHollowsArea())
        recordBucket(System.currentTimeMillis())
    }

    /** The bucket a drop belongs to at this very moment, or null when tracking is paused or gated off. */
    fun bucketForPickup(): Activity? =
        if (!isPickupAllowed()) null else current

    fun isCounting(now: Long = System.currentTimeMillis()): Boolean {
        val activity = current ?: return false
        return gate.isCounting(
            now = now,
            graceMs = graceMs(activity),
            isMining = activity == Activity.MINING,
            inCrystalHollows = isCrystalHollowsArea(),
            excavatorOpen = activity == Activity.MINING && FossilExcavation.isOpen(),
        )
    }

    private fun hasActiveSignal(activity: Activity, now: Long): Boolean =
        gate.hasActiveSignal(now, graceMs(activity))

    fun isCountingFor(activity: Activity, filter: String): Boolean {
        if (current != activity || !isCounting()) return false
        return filter == BucketLedger.ALL || filter == timingSlice(activity)
    }

    private fun onTick(event: ClientTickEvent) {
        val now = System.currentTimeMillis()

        checkTeleport(now)
        refreshRegion(event.scoreboardLines)
        updateCurrent(now)
        resumeGlaciteTransfer(now)
        recordBucket(now)

        // A boss fight is one continuous stretch of work even when no sword swing lands for a
        // while: dodging a phase, waiting out a damage window, or chasing the boss across the
        // arena can leave gaps well past a minute. The sidebar only shows "Slay the boss!"
        // while a boss is actually alive, so holding the window open here cannot keep a dead
        // session alive - and a boss that is ignored despawns or kills the player, which ends
        // the line on its own.
        if (bossFight && !isPaused && current == Activity.SLAYER) {
            gate.extendActiveSignal(now)
        }

        val signature = "${current?.name}|${isCounting(now)}|$isPaused|$hollowsMiningActive"
        if (signature != lastStateSignature) {
            lastStateSignature = signature
            Debug.decision(
                "[state] $signature world=$world zone=$zone signal=$lastSignalSource " +
                    "elapsed=${current?.let { timingStorage(it).sessionElapsedSeconds } ?: 0}",
            )
        }

        if (now - lastCountMs >= SECOND_MS) {
            lastCountMs = now
            countSecond(now)
        }
    }

    private fun onWorldChanged() {
        val now = System.currentTimeMillis()
        val origin = GlaciteTravel.region(world)
        if (origin != null && isGlaciteMiningActive(now)) {
            pendingGlaciteTransfer = GlaciteTravel.Transfer(
                origin,
                now,
                maxOf(lastCountMs, now - SECOND_MS),
                lastSignalMs,
                lastSignalSource,
                MINING_GRACE_MS,
            )
        }
        resetWorldState()
    }

    private fun isGlaciteMiningActive(now: Long): Boolean =
        current == Activity.MINING && isPickupAllowed() && hasActiveSignal(Activity.MINING, now) && !FossilExcavation.isOpen()

    private fun resumeGlaciteTransfer(now: Long) {
        val transfer = pendingGlaciteTransfer ?: return
        if (isGlaciteMiningActive(now)) {
            pendingGlaciteTransfer = null
            return
        }
        when (transfer.arrival(world, now)) {
            GlaciteTravel.Arrival.WAITING -> return
            GlaciteTravel.Arrival.CANCELLED -> {
                pendingGlaciteTransfer = null
                return
            }
            GlaciteTravel.Arrival.CONFIRMED -> if (current != Activity.MINING) return
        }

        pendingGlaciteTransfer = null
        val seconds = transfer.secondsToCredit(now)
        BucketLedger.addElapsed(BucketLedger.sliceStorage(Activity.MINING, MINING_NORMAL), seconds)
        if (now - transfer.signalAtMs <= MINING_GRACE_MS) {
            gate.restoreMiningSignal(transfer.signalAtMs, transfer.signalSource)
            lastCountMs = transfer.countedThroughMs + seconds * SECOND_MS
        }
        Debug.log("[signal] Glacite transfer to $world (credited ${seconds}s)")
    }

    private fun resetWorldState() {
        current = null
        world = ""
        zone = ""
        closedAtMs = 0L
        slayerQuest = null
        lastSlayerTypeName = null
        bossFight = false
        hasPos = false
        lastAttackMs = 0L
        gate.onWorldReset()
    }

    private fun clearSignals(keepMiningClock: Boolean = false) {
        lastAttackMs = 0L
        gate.clearSignals(keepMiningClock)
    }

    private fun refreshRegion(lines: List<String>) {
        world = WorldUtils.getWorldName().orEmpty()
        zone = WorldUtils.getZoneName().orEmpty()
        slayerQuest = readSlayerQuest(lines)
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
        // A cast bobber means the player is fishing, and fishing has its own tracker (Feesh).
        // Its zones overlap ours (the Stronghold is both an Inferno area and a fishing spot), so
        // standing there with a rod out would otherwise run two overlays over the same loot and
        // book every fish as slayer profit.
        if (isFishing()) return null
        if (isMiningArea(world) || isMiningArea(zone)) return Activity.MINING
        if (slayerQuest != null && zone in slayerQuest!!.zones) return Activity.SLAYER
        return null
    }

    /** Last time the player uncovered a treasure chest; chest loot lands right after. */
    private var lastTreasureChestMs = 0L
    private const val TREASURE_CHEST_WINDOW_MS = 8_000L

    /** Called from the chat hook when the hollows announce a treasure chest. */
    fun onTreasureChestOpened() {
        lastTreasureChestMs = System.currentTimeMillis()
    }

    /** True if a treasure chest was uncovered moments ago (its loot is still arriving). */
    fun treasureChestRecently(): Boolean =
        System.currentTimeMillis() - lastTreasureChestMs <= TREASURE_CHEST_WINDOW_MS

    /** True inside the crystal hollows, including the nucleus and its mineshafts. */
    fun isCrystalHollowsArea(): Boolean {
        val world = WorldUtils.getWorldName()?.lowercase() ?: ""
        val zone = WorldUtils.getZoneName()?.lowercase() ?: ""
        return listOf(world, zone).any { it.contains("crystal hollows") || it.contains("crystal nucleus") }
    }

    /** True while the local player has a bobber in the water. */
    /**
     * Fishing suppresses every bucket because Feesh already tracks it. Holding a rod is not enough:
     * mining and slayer players keep one in hand to swap pets with a quick cast, so only a bobber
     * that actually sits in the water for a while counts. Once real fishing is detected the
     * suppression sticks for a few seconds so the panel does not blink between casts.
     */
    private var bobberSinceMs = 0L
    private var lastFishingMs = 0L
    private const val FISHING_SETTLE_MS = 2_000L
    private const val FISHING_STICKY_MS = 8_000L

    private fun isFishing(now: Long = System.currentTimeMillis()): Boolean {
        val bobber = SkbMod.mc.player?.fishing
        if (bobber == null) {
            bobberSinceMs = 0L
        } else {
            if (bobberSinceMs == 0L) bobberSinceMs = now
            if (now - bobberSinceMs >= FISHING_SETTLE_MS) lastFishingMs = now
        }
        return now - lastFishingMs <= FISHING_STICKY_MS
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
            if (current != resolved) clearSignals()
            current = resolved
            if (resolved == Activity.SLAYER) lastSlayerTypeName = slayerTypeName
            closedAtMs = now
            return
        }

        if (current == null) return
        if (now - closedAtMs > TAIL_MS) {
            current = null
            clearSignals()
        }
        // Else: inside the tail window, keep attributing to the closed bucket.
    }

    private fun graceMs(activity: Activity): Long = when (activity) {
        Activity.MINING -> MINING_GRACE_MS
        Activity.SLAYER -> SLAYER_GRACE_MS
        Activity.RIFT -> RIFT_GRACE_MS
    }

    /** Mob-kill coins only arrive right after a swing, so a purse jump without one is a sale. */
    fun attackedRecently(windowMs: Long = 10_000L): Boolean =
        lastAttackMs != 0L && System.currentTimeMillis() - lastAttackMs <= windowMs

    private fun countSecond(now: Long) {
        val activity = current ?: return
        if (!isCounting(now)) return

        BucketLedger.addElapsed(timingStorage(activity), 1L)
    }

    private fun timingSlice(activity: Activity): String? =
        BucketLedger.liveType(activity)

    private fun timingStorage(activity: Activity): BucketData =
        timingSlice(activity)?.let { BucketLedger.sliceStorage(activity, it) }
            ?: PersistentDataManager.skbprofitrackerData.bucket(activity.bucketKey)

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
            isMiningPausedInUi() -> "paused"
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

}
