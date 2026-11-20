package com.skbprofitracker.features.commands

import com.skbprofitracker.features.overlays.base.TrackerViewMode
import com.skbprofitracker.price.PriceMode
import com.skbprofitracker.tracker.Activity
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.ProfitOverlay
import com.skbprofitracker.tracker.CostLedger
import com.skbprofitracker.tracker.LootLog
import com.skbprofitracker.tracker.ResetConfirmation
import com.skbprofitracker.tracker.ResetConfirmation.Method
import com.skbprofitracker.tracker.TrackerViewTarget
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.utils.ChatUtils
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.RegisterUtils
import com.skbprofitracker.utils.gui.MoveGuis
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SkbProfitCommand {
    private const val COMMAND_NAME = "skbprofit"

    private val resetConfirmation = ResetConfirmation<TrackerViewTarget>()

    fun init() {
        RegisterUtils.command(COMMAND_NAME) { args ->
            when (args.firstOrNull()?.lowercase()) {
                "gui" -> MoveGuis.moveAllGuis()
                "pause" -> togglePause()
                "reset" -> reset(args.getOrNull(1), args.getOrNull(2))
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

    private fun reset(scope: String?, confirmationId: String?) {
        if (scope.equals("confirm", ignoreCase = true)) {
            val target = resetConfirmation.confirm(confirmationId?.toLongOrNull(), System.currentTimeMillis())
            if (target == null) {
                ChatUtils.sendLocalChat("§eThis reset confirmation is no longer active.", true)
            } else {
                confirmReset(target)
            }
            return
        }

        val mode = when (scope?.lowercase()) {
            null, "session" -> TrackerViewMode.SESSION
            "total" -> TrackerViewMode.TOTAL
            else -> {
                ChatUtils.sendLocalChat("§eUse /skbprofit reset [session|total].", true)
                return
            }
        }
        val activity = ActivityBucket.current
        if (activity == null) {
            ChatUtils.sendLocalChat("§cNo activity is running right now.", true)
            return
        }
        requestReset(TrackerViewTarget(activity, BucketLedger.filterFor(activity), mode), Method.COMMAND)
    }

    fun isOverlaySessionResetArmed(target: TrackerViewTarget, now: Long): Boolean =
        resetConfirmation.isArmed(target, Method.OVERLAY_SESSION, now)

    fun requestOverlayReset(target: TrackerViewTarget) {
        val method = if (target.mode == TrackerViewMode.SESSION) Method.OVERLAY_SESSION else Method.OVERLAY_TOTAL
        requestReset(target, method)
    }

    private fun requestReset(target: TrackerViewTarget, method: Method) {
        val id = resetConfirmation.request(target, method, System.currentTimeMillis())
        if (id == null) {
            confirmReset(target)
            return
        }

        val seconds = method.windowMs / 1_000L
        if (!method.usesChat) {
            ChatUtils.sendLocalChat("§7About to clear §f${target.label}§7. Click again within ${seconds}s.", true)
            return
        }
        val sessionWarning = if (target.mode == TrackerViewMode.TOTAL) " The session will also be cleared." else ""
        ChatUtils.sendLocalChat("§eAbout to clear §f${target.label}§e. §7This cannot be undone.$sessionWarning", true)
        ChatUtils.sendLocalChatWithCommand(
            "§a[Confirm] §7Clear §f${target.label} §7within ${seconds}s.",
            "skbprofit reset confirm $id",
        )
    }

    private fun confirmReset(target: TrackerViewTarget) {
        BucketLedger.reset(target)
        ProfitOverlay.invalidate()
        ChatUtils.sendLocalChat("§aCleared ${target.label}.", true)
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
        val viewMode = when (mode?.lowercase()) {
            "total" -> TrackerViewMode.TOTAL
            "session" -> TrackerViewMode.SESSION
            null, "toggle" -> BucketLedger.viewMode.next()
            else -> {
                ChatUtils.sendLocalChat("§cUsage: /skbprofit mode <session|total|toggle>", true)
                return
            }
        }
        setViewMode(viewMode, announce = true)
    }

    fun setViewMode(mode: TrackerViewMode, announce: Boolean = false) {
        BucketLedger.viewMode = mode
        ProfitOverlay.invalidate()
        if (announce) ChatUtils.sendLocalChat("§7Viewing §6${mode.name.lowercase()}§7 numbers.", true)
    }

    fun togglePause() {
        ActivityBucket.isPaused = !ActivityBucket.isPaused
        ProfitOverlay.invalidate()
        ChatUtils.sendLocalChat(statusLine(), true)
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
        setNucleusTime(enabled)
        ChatUtils.sendLocalChat(
            if (enabled) "§aCrystal Hollows time is now counted" else "§7Crystal Hollows time is no longer counted",
        )
    }

    fun setNucleusTime(enabled: Boolean) {
        ActivityBucket.setNucleusTiming(enabled)
        if (enabled && ActivityBucket.current == Activity.MINING && ActivityBucket.isCrystalHollowsArea()) {
            BucketLedger.setFilter(Activity.MINING, ActivityBucket.MINING_NUCLEUS)
        }
        ProfitOverlay.invalidate()
    }

    private fun switchPrice(mode: String?) {
        val priceMode = when (mode?.lowercase()) {
            "insta", "instant" -> PriceMode.INSTA_SELL
            "offer" -> PriceMode.SELL_OFFER
            null, "toggle" -> BucketLedger.priceMode.next()
            else -> {
                ChatUtils.sendLocalChat("§cUsage: /skbprofit price <insta|offer|toggle>", true)
                return
            }
        }
        setPriceMode(priceMode, announce = true)
    }

    fun setPriceMode(mode: PriceMode, announce: Boolean = false) {
        BucketLedger.priceMode = mode
        ProfitOverlay.invalidate()
        if (announce) ChatUtils.sendLocalChat("§7Pricing with §6${mode.name.lowercase()}§7.", true)
    }

    private fun printCosts() {
        val activity = ActivityBucket.current
        if (activity == null) {
            ChatUtils.sendLocalChat("§cNo activity is running right now.", true)
            return
        }

        val filter = BucketLedger.filterFor(activity)
        val view = CostLedger.view(activity, BucketLedger.viewMode, BucketLedger.priceMode, filter)

        ChatUtils.sendLocalChat(
            "§7Costs for §6${activity.bucketKey} / $filter §7(${BucketLedger.viewMode.name.lowercase()})§7: §c-${coins(view.totalValue)}",
            true,
        )
        if (view.coinsSpent > 0.0) {
            ChatUtils.sendLocalChat("  §ccoins §7-${coins(view.coinsSpent)} §8(purse, quest start)", true)
        }
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
        printTypes(activity)
    }

    private fun printTypes(activity: Activity) {
        val options = BucketLedger.availableFilters(activity)
        if (options.isEmpty()) {
            ChatUtils.sendLocalChat("§7The Rift has a single bucket.", true)
            return
        }
        val types = options.filterNot { it == BucketLedger.ALL }
        val title = activity.bucketKey.replaceFirstChar { it.uppercase() }
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
        val requested = raw?.trim()
        if (requested.isNullOrEmpty()) {
            printTypes(activity)
            return
        }
        val options = BucketLedger.availableFilters(activity)
        val exact = options.firstOrNull { it.equals(requested, true) }
        val matches = options.filter { it.contains(requested, true) }
        val filter = exact ?: matches.singleOrNull()
        if (filter == null) {
            val message = if (matches.isEmpty()) "Unknown type: $requested" else "Choose one of: ${matches.joinToString()}"
            ChatUtils.sendLocalChat("§c$message", true)
            return
        }
        BucketLedger.setFilter(activity, filter)
        ProfitOverlay.invalidate()
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
        val state = when {
            ActivityBucket.isPaused -> "§cpaused"
            ActivityBucket.isCountingFor(activity, view.filter) -> "§arunning"
            else -> "§7idle"
        }
        return "§7${activity.bucketKey}: $state §8| §6${view.items.size} types §8| §7${view.elapsedSeconds}s | $where"
    }
}
