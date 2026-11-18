package com.skbprofitracker.features.commands

import com.skbprofitracker.features.overlays.base.TrackerViewMode
import com.skbprofitracker.price.PriceMode
import com.skbprofitracker.tracker.Activity
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.ProfitOverlay
import com.skbprofitracker.tracker.CostLedger
import com.skbprofitracker.tracker.LootLog
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.utils.ChatUtils
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.data.PersistentDataManager
import com.skbprofitracker.utils.RegisterUtils
import com.skbprofitracker.utils.gui.MoveGuis
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SkbProfitCommand {
    private const val COMMAND_NAME = "skbprofit"

    /** How long a reset stays armed after the first call. */
    private const val RESET_CONFIRM_MS = 15_000L

    private var resetArmedUntil = 0L
    private var resetArmedScope: String? = null

    fun init() {
        RegisterUtils.command(COMMAND_NAME) { args ->
            when (args.firstOrNull()?.lowercase()) {
                "gui" -> MoveGuis.moveAllGuis()
                "pause" -> {
                    ActivityBucket.isPaused = !ActivityBucket.isPaused
                    ChatUtils.sendLocalChat(statusLine(), true)
                }
                "reset" -> reset(args.getOrNull(1))
                "types" -> printTypes()
                "slayer" -> setFilter(Activity.SLAYER, args.drop(1).joinToString(" "))
                "mining" -> setFilter(Activity.MINING, args.drop(1).joinToString(" "))
                "mode" -> switchMode(args.getOrNull(1))
                "price" -> switchPrice(args.getOrNull(1))
                "nucleustime" -> switchNucleusTime(args.getOrNull(1))
                "costs" -> printCosts()
                "loot" -> printLoot(args.getOrNull(1))
                "debug" -> {
                    Debug.enabled = !Debug.enabled
                    Debug.verbose = Debug.enabled
                    ChatUtils.sendLocalChat(
                        "§7Diagnostics ${if (Debug.enabled) "§aon" else "§coff"}§7 (verbose ${if (Debug.verbose) "§aon" else "§coff"}§7).",
                        true,
                    )
                    ActivityBucket.debugLines().forEach { ChatUtils.sendLocalChat(it, true) }
                    ChatUtils.sendLocalChat("§8loaded build: §7${buildStamp()}", true)
                    ChatUtils.sendLocalChat("§8icon candidates: §7[⛁ Skb] §8| §7[⛃ Skb] §8| §7[✪ Skb] §8| §7[◉ Skb]", true)
                }
                else -> ChatUtils.sendLocalChat(statusLine(), true)
            }
        }
    }

    /**
     * Resetting wipes data that is not recoverable, so the command asks once before doing it:
     * the first call prints a clickable [Confirm], running it again within the window executes.
     * Clicking the overlay button already needs two clicks, this is the chat side of the same rule.
     */
    private fun reset(scope: String?) {
        val activity = ActivityBucket.current
        if (activity == null) {
            ChatUtils.sendLocalChat("§cNo activity is running right now.", true)
            return
        }

        val total = scope.equals("total", ignoreCase = true)
        val now = System.currentTimeMillis()

        if (now > resetArmedUntil || resetArmedScope != scope) {
            resetArmedUntil = now + RESET_CONFIRM_MS
            resetArmedScope = scope
            ChatUtils.sendLocalChat(
                "§eAbout to clear the §f${activity.bucketKey} ${if (total) "total" else "session"}§e. " +
                    "§7This cannot be undone.",
                true,
            )
            ChatUtils.sendLocalChatWithCommand(
                "§a[Confirm] §7click here, or run §f/skbprofit reset${if (total) " total" else ""}§7 again within 15s.",
                "skbprofit reset" + if (total) " total" else "",
            )
            return
        }

        resetArmedUntil = 0L
        resetArmedScope = null

        if (total) {
            ActivityBucket.resetTotal(activity)
            ChatUtils.sendLocalChat("§aReset the ${activity.bucketKey} total.", true)
        } else {
            ActivityBucket.resetSession(activity)
            ChatUtils.sendLocalChat("§aReset the ${activity.bucketKey} session.", true)
        }
    }

    /**
     * Chat-based loot (corpse, nucleus) is easy to miss while playing, so the last lines are kept
     * in memory and can be dumped here. `unknown` means the item table has no entry for the name.
     */
    private fun printLoot(countArg: String?) {
        val count = countArg?.toIntOrNull()?.coerceIn(1, 50) ?: 20
        val entries = LootLog.recent(count)
        if (entries.isEmpty()) {
            ChatUtils.sendLocalChat("§7No chat loot recorded yet.", true)
            return
        }

        ChatUtils.sendLocalChat("§8Last §7${entries.size}§8 loot lines", true)
        for (entry in entries) {
            val key = entry.key ?: "§cunknown"
            ChatUtils.sendLocalChat(
                "§8${entry.at} §7${entry.slice} §f+${entry.amount} §f${entry.name} §8($key§8)",
                true,
            )
        }
    }

    private fun switchMode(mode: String?) {
        BucketLedger.viewMode = when (mode?.lowercase()) {
            "total" -> TrackerViewMode.TOTAL
            "session" -> TrackerViewMode.SESSION
            else -> if (BucketLedger.viewMode == TrackerViewMode.SESSION) {
                TrackerViewMode.TOTAL
            } else {
                TrackerViewMode.SESSION
            }
        }
        ChatUtils.sendLocalChat("§7Viewing §6${BucketLedger.viewMode.name.lowercase()}§7 numbers.", true)
    }

    /**
     * Crystal Hollows time is not counted by default, so hunting for a crystal does not drag the
     * mining coins/hour down. Turn it on to include the time when you actually run the altar.
     */
    private fun switchNucleusTime(raw: String?) {
        val enabled = when (raw?.lowercase()) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            null, "toggle" -> !ActivityBucket.nucleusTimingEnabled()
            else -> {
                ChatUtils.sendLocalChat("§cUsage: /skbprofit nucleustime <on|off>")
                return
            }
        }
        ActivityBucket.setNucleusTiming(enabled)
        ChatUtils.sendLocalChat(
            if (enabled) "§aCrystal Hollows time is now counted" else "§7Crystal Hollows time is no longer counted",
        )
    }

    private fun switchPrice(mode: String?) {
        BucketLedger.priceMode = when (mode?.lowercase()) {
            "insta", "instant" -> PriceMode.INSTA_SELL
            "offer" -> PriceMode.SELL_OFFER
            else -> if (BucketLedger.priceMode == PriceMode.SELL_OFFER) {
                PriceMode.INSTA_SELL
            } else {
                PriceMode.SELL_OFFER
            }
        }
        ChatUtils.sendLocalChat("§7Pricing with §6${BucketLedger.priceMode.name.lowercase()}§7.", true)
    }

    private fun printCosts() {
        val activity = ActivityBucket.current
        if (activity == null) {
            ChatUtils.sendLocalChat("§cNo activity is running right now.", true)
            return
        }

        val bucket = PersistentDataManager.skbprofitrackerData.bucket(activity.bucketKey)
        val coins = if (BucketLedger.viewMode == TrackerViewMode.SESSION) {
            bucket.sessionCoinsSpent
        } else {
            bucket.totalCoinsSpent
        }
        val view = CostLedger.view(activity, BucketLedger.viewMode, BucketLedger.priceMode)

        ChatUtils.sendLocalChat(
            "§7Costs for §6${activity.bucketKey} §7(${BucketLedger.viewMode.name.lowercase()})§7:",
            true,
        )
        ChatUtils.sendLocalChat("  §ccoins §7-${coins(coins)} §8(purse, quest start)", true)
        for (line in view.lines) {
            val display = ItemTable.getByKey(line.name)?.name ?: line.name
            val unit = line.unitPrice?.let { coins(it) } ?: "unpriced"
            ChatUtils.sendLocalChat(
                "  §c$display §7x${line.amount} §8@ §7$unit §8= §c-${coins(line.value)}",
                true,
            )
        }
    }

    private fun coins(value: Double): String = CommonUtils.toShortNumber(value) ?: "0"

    /** Chat menu listing every slice of the running activity, each line clickable to filter. */
    fun printTypes() {
        val activity = ActivityBucket.current
        if (activity == null) {
            ChatUtils.sendLocalChat("§cNo activity is running right now.", true)
            return
        }
        when (activity) {
            // Riftstalker is fought inside the Rift, which has its own bucket, so it is not a
            // slayer filter here. Order follows the in-game slayer list.
            Activity.SLAYER -> printTypes(
                activity,
                "Slayer",
                ActivityBucket.slayerTypeNames.filterNot { it == "Riftstalker Bloodfiend" },
            )
            Activity.MINING -> printTypes(activity, "Mining", ActivityBucket.miningTypeNames)
            Activity.RIFT -> ChatUtils.sendLocalChat("§7The Rift is a single bucket - nothing to filter.", true)
        }
    }

    private fun printTypes(activity: Activity, title: String, known: List<String>) {
        val stored = BucketLedger.storedTypes(activity)
        val types = known + stored.filterNot { it in known }.sorted()
        val filter = BucketLedger.filterFor(activity)
        val sub = subcommand(activity)
        ChatUtils.sendLocalChat("§7$title types §8— click one to filter", true)

        val rows = types.map { type ->
            val (value, seconds, unpriced) = BucketLedger.typeSummary(activity, type)
            // Nucleus and corpse runs are one-off loot bursts, so a per-hour figure would be
            // noise. Only plain mining (and every slayer type) gets one.
            TypeRow(type, seconds, value, unpriced, perHourAllowed = activity != Activity.MINING || type == ActivityBucket.MINING_NORMAL)
        }

        // The chat font is proportional, so this is a best effort: pad every column to the
        // widest cell and pad the numbers from the left, which is what makes the values line up.
        val nameWidth = rows.maxOfOrNull { it.name.length } ?: 0
        val hourWidth = rows.maxOfOrNull { it.hours().length } ?: 0
        val valueWidth = rows.maxOfOrNull { coins(it.value).length } ?: 0
        val perHourWidth = rows.filter { it.perHourAllowed }.maxOfOrNull { it.perHour().length } ?: 0

        val allMark = if (filter == BucketLedger.ALL) "§a▶ " else "§8- "
        ChatUtils.sendLocalChatWithCommand("$allMark§f[All]", "$COMMAND_NAME $sub all")

        for (row in rows) {
            val mark = if (filter == row.name) "§a▶ " else "§8- "
            val name = "§f${row.name.padEnd(nameWidth)}"
            val line = if (row.isEmpty()) {
                // Keep the placeholder in the same columns as the numbers, otherwise the
                // empty slice visibly breaks the alignment of everything around it.
                "$mark$name §8${"—".padStart(hourWidth)} ${"—".padStart(valueWidth)}"
            } else {
                val suffix = if (row.unpriced > 0) " §8(${row.unpriced} unpriced)" else ""
                val perHour = if (row.perHourAllowed) " §7${row.perHour().padStart(perHourWidth)}" else ""
                "$mark$name §8${row.hours().padStart(hourWidth)} " +
                    "§6${coins(row.value).padStart(valueWidth)}$perHour$suffix"
            }
            ChatUtils.sendLocalChatWithCommand(line, "$COMMAND_NAME $sub ${row.name}")
        }
    }

    private data class TypeRow(
        val name: String,
        val seconds: Long,
        val value: Double,
        val unpriced: Int,
        val perHourAllowed: Boolean,
    ) {
        fun isEmpty(): Boolean = seconds == 0L && value == 0.0

        fun hours(): String = "${seconds / 3600}h"

        fun perHour(): String {
            val hours = seconds / 3600.0
            return "(${coins(if (hours > 0.0) value / hours else 0.0)}/h)"
        }
    }

    private fun subcommand(activity: Activity): String = when (activity) {
        Activity.SLAYER -> "slayer"
        Activity.MINING -> "mining"
        Activity.RIFT -> "rift"
    }

    private fun setFilter(activity: Activity, raw: String?) {
        if (raw.isNullOrBlank()) {
            printTypes()
            return
        }
        val known = when (activity) {
            Activity.SLAYER -> ActivityBucket.slayerTypeNames
            Activity.MINING -> ActivityBucket.miningTypeNames
            Activity.RIFT -> emptyList()
        }
        val types = known + BucketLedger.storedTypes(activity).filterNot { it in known }
        val filter = when {
            raw.equals("all", true) -> BucketLedger.ALL
            else -> types.firstOrNull { it.equals(raw, true) }
                ?: types.firstOrNull { it.contains(raw, true) }
                ?: run {
                    ChatUtils.sendLocalChat("§cUnknown type: §f$raw", true)
                    return
                }
        }
        BucketLedger.setFilter(activity, filter)
        ChatUtils.sendLocalChat(
            "§7Filter: §f${if (filter == BucketLedger.ALL) "All" else filter}",
            true,
        )
    }

    /**
     * The jar's timestamp, so it is obvious whether the running client picked up the latest build.
     * Replacing the file without restarting leaves Fabric on the old copy.
     */
    private fun buildStamp(): String = runCatching {
        val origin = FabricLoader.getInstance().getModContainer("skbprofitracker").orElse(null)?.origin
        val path = origin?.paths?.firstOrNull() ?: return@runCatching "unknown"
        val millis = Files.getLastModifiedTime(path).toMillis()
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }.getOrDefault("unknown")

    private fun statusLine(): String {
        val where = "§8world=§7${ActivityBucket.world} §8zone=§7${ActivityBucket.zone}"
        val activity = ActivityBucket.current
        if (activity == null) {
            return "§7Idle. §8Mining, Slayer and Rift are the tracked activities. $where"
        }

        val view = BucketLedger.view(activity)
        val state = if (ActivityBucket.isPaused) "§cpaused" else "§arunning"
        return "§7${activity.bucketKey}: $state §8| §6${view.items.size} types §8| §7${view.elapsedSeconds}s | $where"
    }
}
