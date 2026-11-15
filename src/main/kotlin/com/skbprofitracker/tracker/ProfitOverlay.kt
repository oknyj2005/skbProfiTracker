package com.skbprofitracker.tracker

import com.skbprofitracker.SkbMod
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.features.commands.SkbProfitCommand
import com.skbprofitracker.features.overlays.base.TrackerViewMode
import com.skbprofitracker.items.ItemColors
import com.skbprofitracker.price.PriceMode
import com.skbprofitracker.utils.ChatUtils
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.data.PersistentDataManager
import com.skbprofitracker.utils.gui.GuiButton
import com.skbprofitracker.utils.gui.LineAction
import com.skbprofitracker.utils.gui.LineInfo
import com.skbprofitracker.utils.gui.SkbGui
import com.skbprofitracker.utils.gui.Table
import java.text.DecimalFormat

/**
 * The overlay, laid out the way Feesh lays out its profit tracker.
 *
 * Everything except the activity name and the absolute numbers is a direct copy of Feesh's
 * formatting: grey dashes, a rarity coloured name, a gold value column, aqua labels and
 * bold totals. Colours come from [ItemColors] because our item table is generated from NEU
 * names and carries no rarity prefix.
 */
object ProfitOverlay {
    private const val MAX_VISIBLE_ITEMS = 15

    /** Rows cheaper than this are not worth a line of their own; they fold into "Other items". */
    private const val HIDE_CHEAPER_THAN = 1_000_000.0
    private const val REBUILD_INTERVAL_MS = 500L
    private const val RESET_CONFIRM_MS = 5_000L

    private const val GRAY = "§7"
    private const val WHITE = "§f"
    private const val GOLD = "§6"
    private const val AQUA = "§b"
    private const val GREEN = "§a"
    private const val RED = "§c"
    private const val BOLD = "§l"
    private const val RESET = "§r"

    private val gui = SkbGui()

    private var lastBuildMs = 0L
    private var resetArmedAtMs = 0L

    /** Whether the slice picker is expanded inside the panel. */
    private var selectorOpen = false

    fun init() {
        gui.setCoordsDataKey("skbprofit_profit")
            .setClickable(true)
            // There is no settings toggle for this overlay: it is always "enabled".
            // MoveGuis filters out GUIs without a settings key, which is why /skbprofit gui was empty.
            .setSettingsKey { true }
            .setSampleLines(
                listOf(
                    "${WHITE}Mining profit tracker ${GRAY}[Session]",
                    "${GRAY}- ${WHITE}12${GRAY}x ${AQUA}Flawed Sapphire Gemstone  ${GOLD}3.7k",
                    "${GRAY}- ${WHITE}4 601${GRAY}x ${WHITE}Rough Sapphire Gemstone  ${GOLD}2.3k",
                    "${AQUA}Profit: ${GOLD}18.2M ${GRAY}(${GOLD}24.1M${GRAY}/h)",
                    "${AQUA}Elapsed time: ${WHITE}2m 28s",
                )
            )
            .setCondition { ActivityBucket.current != null }

        EventBus.subscribe(ClientTickEvent::class) { onClientTick() }
    }

    private fun onClientTick() {
        val now = System.currentTimeMillis()
        if (now - lastBuildMs < REBUILD_INTERVAL_MS) return
        lastBuildMs = now

        val view = BucketLedger.currentView()
        if (view == null) {
            gui.clearLines()
            return
        }

        gui.setButtons(
            buildList {
                add(GuiButton(0, "${GRAY}[Click to show ${WHITE}[${nextViewModeText(view.mode)}]${GRAY}]") {
                    BucketLedger.viewMode = nextViewMode(view.mode)
                })
                add(GuiButton(1, pauseButtonText()) {
                    ActivityBucket.isPaused = !ActivityBucket.isPaused
                    ChatUtils.sendLocalChat(
                        if (ActivityBucket.isPaused) {
                            "${RED}Paused §7- mining and slayer are no longer tracked"
                        } else {
                            "${GREEN}Resumed §7- tracking is running again"
                        },
                    )
                })
                add(GuiButton(2, resetButtonText()) { onResetClicked(view.activity) })
                if (view.activity == Activity.SLAYER || view.activity == Activity.MINING) {
                    add(GuiButton(3, filterButtonText(view.activity, view.filter)) {
                        selectorOpen = !selectorOpen
                    })
                }
                add(GuiButton(4, priceButtonText()) {
                    BucketLedger.priceMode = when (BucketLedger.priceMode) {
                        PriceMode.SELL_OFFER -> PriceMode.INSTA_SELL
                        PriceMode.INSTA_SELL -> PriceMode.SELL_OFFER
                    }
                })
            }
        )
        val lines = buildLines(view)
        // The move screen renders placeholders instead of the live overlay, so keep them equal to
        // what is on screen right now; a fixed sample made slayer numbers show up in a mining hole.
        gui.setSampleLines(buildSample(lines))
        gui.setLines(lines)
    }

    private fun buildSample(lines: List<LineInfo>): List<String> =
        lines.asSequence()
            .mapNotNull { line ->
                val cells = line.cells
                val text = if (cells.isNullOrEmpty()) line.text else cells.joinToString("  ") { it.text }
                text.takeIf { it.isNotBlank() }
            }
            .take(8)
            .toList()

    private fun buildLines(view: ProfitView): List<LineInfo> {
        val lines = mutableListOf<LineInfo>()

        val split = view.activity == Activity.SLAYER || view.activity == Activity.MINING
        val activityName = if (split && view.filter != BucketLedger.ALL) {
            view.filter
        } else {
            view.activity.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        val modeName = view.mode.name.lowercase().replaceFirstChar { it.uppercase() }
        val filterName = if (split && view.filter == BucketLedger.ALL) {
            " ${GRAY}[All]"
        } else {
            ""
        }
        lines.add(LineInfo("${WHITE}$activityName profit tracker ${GRAY}[$modeName]$filterName"))

        // The picker sits right under the title so the item rows below are pushed down while it
        // is open, instead of the panel growing somewhere off the bottom of the list.
        if (selectorOpen) appendSelector(lines, view)

        // Feesh folds everything below a value threshold into one "Other items" row instead of
        // printing a dozen rows worth a few coins each, and drops whatever overflows the list.
        val priced = view.items.filter { it.unitPrice != null }
        val visible = priced.filter { it.value >= HIDE_CHEAPER_THAN }.take(MAX_VISIBLE_ITEMS)
        val shownKeys = visible.map { it.key }.toSet()
        val others = priced.filterNot { it.key in shownKeys }

        if (visible.isNotEmpty()) {
            val rows = visible.map { item -> row(item) }
            val layout = Table.layout(SkbMod.mc.font, rows, " ")
            layout.rows.forEachIndexed { index, cells ->
                lines.add(
                    LineInfo.withCells(
                        cells,
                        layout.tableWidth,
                        actions = buildActions(view.activity, visible[index]),
                    )
                )
            }
        }

        if (others.isNotEmpty()) {
            val otherValue = others.sumOf { it.value }
            lines.add(
                LineInfo(
                    "${GRAY}- ${WHITE}Other items ${GRAY}(${count(others.sumOf { it.amount })}x, " +
                        "${WHITE}${others.size} ${GRAY}types)  ${GOLD}${coins(otherValue)}"
                )
            )
        }

        if (view.unpricedTypes > 0) {
            lines.add(LineInfo("${GRAY}- ${view.unpricedTypes} unpriced types"))
        }

        lines.add(LineInfo())

        val cost = CostLedger.view(view.activity, view.mode, BucketLedger.priceMode, view.filter)
        val hasCosts = view.activity != Activity.RIFT && cost.totalValue > 0.0

        // A slayer type without history would otherwise show the cost line of the combined
        // bucket next to a zero income: the numbers would contradict each other.
        if (view.items.isEmpty() && view.elapsedSeconds == 0L) {
            lines.add(LineInfo("${GRAY}No data recorded for this type yet."))
            return lines
        }

        // Feesh prints a single bottom line: "Costs" + "Net profit" when something was paid,
        // and just "Profit" when nothing was. Printing both Profit and Net profit makes the
        // same number appear twice, so we follow Feesh and keep only one.
        if (hasCosts) {
            lines.add(LineInfo("${AQUA}Costs: ${RED}-${coins(cost.totalValue)}"))

            val net = view.totalValue - cost.totalValue
            val netPerHour = view.perHour - cost.perHour
            val color = if (net < 0.0) RED else GOLD
            val perHour = if (view.showPerHour) {
                "${GRAY}(${color}${coins(netPerHour)}${GRAY}/h) "
            } else {
                ""
            }
            lines.add(
                LineInfo(
                    "${AQUA}Net profit: ${color}${BOLD}${coins(net)} ${RESET}" +
                        "$perHour${GRAY}${priceModeBadge()}"
                )
            )
        } else {
            val perHour = if (view.showPerHour) {
                "${GRAY}(${GOLD}${coins(view.perHour)}${GRAY}/h) "
            } else {
                ""
            }
            lines.add(
                LineInfo(
                    "${AQUA}Profit: ${GOLD}${BOLD}${coins(view.totalValue)} ${RESET}" +
                        "$perHour${GRAY}${priceModeBadge()}"
                )
            )
        }

        // SkyHanni prints the boss count right above the profit block; keeping it at the
        // bottom next to the timer makes the "per boss" mental math one glance.
        if (view.activity == Activity.SLAYER && view.bosses > 0) {
            val perHour = if (view.elapsedSeconds > 0) view.bosses * 3600.0 / view.elapsedSeconds else 0.0
            lines.add(
                LineInfo(
                    "${AQUA}Bosses killed: ${WHITE}${comma(view.bosses)} " +
                        "${GRAY}(${WHITE}${DecimalFormat("#,##0.0").format(perHour)}${GRAY}/h)"
                )
            )
        }

        // A corpse run is read per key: three corpses and three keys is the number the player
        // actually wants to check, so it sits where the slayer boss count sits.
        if (view.activity == Activity.MINING && view.filter == ActivityBucket.MINING_CORPSE && view.corpses > 0) {
            lines.add(LineInfo("${AQUA}Corpses opened: ${WHITE}${comma(view.corpses)}"))
        }

        // Three states are worth telling apart: paused by hand, frozen because nothing has
        // happened for the grace period, and actually counting. Without the middle one the
        // panel looks alive while the clock stands still.
        val paused = when {
            ActivityBucket.isPaused -> " ${GRAY}[Paused]"
            !ActivityBucket.isCounting() -> " ${GRAY}[Idle]"
            else -> ""
        }
        // Only the plain "Mining" slice measures a rate: it is the one that accumulates time and
        // shows /h. All / Corpse / Nucleus are profit-only views, so a time line would be noise.
        val hasTimedSlice = view.activity != Activity.MINING || view.filter == ActivityBucket.MINING_NORMAL
        if (hasTimedSlice) {
            lines.add(
                LineInfo(
                    "${AQUA}Elapsed time: ${WHITE}${
                        CommonUtils.formatTimeElapsed(view.elapsedSeconds.toInt())
                    }$paused"
                )
            )
        }

        return lines
    }

    /**
     * Slice picker rendered inside the panel.
     *
     * It replaces the old chat menu: opening the inventory once and clicking the row is one
     * step, while the chat menu needed a second round trip through the chat screen.
     */
    private fun appendSelector(lines: MutableList<LineInfo>, view: ProfitView) {
        val options = selectorOptions(view.activity)
        if (options.isEmpty()) return

        lines.add(LineInfo())
        lines.add(LineInfo("${GRAY}Show ${if (view.activity == Activity.SLAYER) "slayer type" else "mining type"}:"))

        val rows = options.map { option ->
            val (value, _, _) = BucketLedger.typeSummary(view.activity, option)
            val marker = if (option == view.filter) "${GREEN}▶ " else "${GRAY}  "
            val label = if (option == BucketLedger.ALL) "All" else option
            listOf(
                "$marker${if (option == view.filter) "${WHITE}$label" else "${GRAY}$label"}",
                "${GOLD}${coins(value)}",
            )
        }
        val layout = Table.layout(SkbMod.mc.font, rows, "  ")
        options.forEachIndexed { index, option ->
            lines.add(
                LineInfo.withCells(
                    layout.rows[index],
                    layout.tableWidth,
                    onClick = {
                        Debug.log("[pick] ${view.activity} -> $option")
                        BucketLedger.setFilter(view.activity, option)
                        selectorOpen = false
                    },
                )
            )
        }
    }

    private fun selectorOptions(activity: Activity): List<String> = when (activity) {
        // Riftstalker is a separate bucket (the Rift), so it is not offered as a slayer filter.
        Activity.SLAYER -> listOf(BucketLedger.ALL) +
            ActivityBucket.slayerTypeNames.filterNot { it == "Riftstalker Bloodfiend" }
        Activity.MINING -> listOf(BucketLedger.ALL) + ActivityBucket.miningTypeNames
        Activity.RIFT -> emptyList()
    }

    private fun comma(value: Long): String = DecimalFormat("#,##0").format(value)

    private fun row(item: ItemLine): List<String> = listOf(
        "${GRAY}- ${WHITE}${count(item.amount)}${GRAY}x ${ItemColors.get(item.key)}${item.name}",
        "${GOLD}${coins(item.value)}",
    )

    private fun buildActions(activity: Activity, item: ItemLine): List<LineAction> = listOf(
        LineAction("${GRAY}[${GREEN}+${GRAY}]") { BucketLedger.adjust(activity, item.key, 1L) },
        LineAction("${GRAY}[${RED}-${GRAY}]") { BucketLedger.adjust(activity, item.key, -1L) },
        LineAction("${GRAY}[${RED}x${GRAY}]") { BucketLedger.remove(activity, item.key) },
    )

    /** The button opens a chat menu (see [SkbProfitCommand.printTypes]); clicking a line picks that type. */
    private fun filterButtonText(activity: Activity, filter: String): String {
        val title = if (activity == Activity.SLAYER) "Slayer" else "Mining"
        val label = if (filter == BucketLedger.ALL) "All" else filter
        return "${GRAY}[$title: ${WHITE}$label${GRAY} ${GRAY}▾${GRAY}]"
    }

    private fun pauseButtonText(): String =
        if (ActivityBucket.isPaused) {
            "${GRAY}[${GREEN}Click to resume${GRAY}]"
        } else {
            "${GRAY}[${GOLD}Click to pause${GRAY}]"
        }

    /** One click flips the price source; a dropdown would be more work than it is worth. */
    private fun priceButtonText(): String {
        val label = when (BucketLedger.priceMode) {
            PriceMode.SELL_OFFER -> "offer"
            PriceMode.INSTA_SELL -> "insta"
        }
        return "${GRAY}[Price: ${WHITE}$label${GRAY}]"
    }

    private fun resetButtonText(): String {
        val armed = System.currentTimeMillis() - resetArmedAtMs < RESET_CONFIRM_MS
        return if (armed) {
            "${GRAY}[${RED}Click again to reset ${BucketLedger.viewMode.name.lowercase()}${GRAY}]"
        } else {
            "${GRAY}[${RED}Click to reset${GRAY}]"
        }
    }

    private fun onResetClicked(activity: Activity) {
        val now = System.currentTimeMillis()
        if (now - resetArmedAtMs < RESET_CONFIRM_MS) {
            when (BucketLedger.viewMode) {
                TrackerViewMode.SESSION -> ActivityBucket.resetSession(activity)
                TrackerViewMode.TOTAL -> ActivityBucket.resetTotal(activity)
            }
            resetArmedAtMs = 0L
        } else {
            resetArmedAtMs = now
        }
    }

    private fun nextViewMode(mode: TrackerViewMode): TrackerViewMode = when (mode) {
        TrackerViewMode.SESSION -> TrackerViewMode.TOTAL
        TrackerViewMode.TOTAL -> TrackerViewMode.SESSION
    }

    private fun nextViewModeText(mode: TrackerViewMode): String = when (mode) {
        TrackerViewMode.SESSION -> "Total"
        TrackerViewMode.TOTAL -> "Session"
    }

    private fun priceModeBadge(): String = when (BucketLedger.priceMode) {
        PriceMode.SELL_OFFER -> "[offer]"
        PriceMode.INSTA_SELL -> "[insta]"
    }

    /** Feesh writes big counts as `1 909 194`, which makes the row scannable. */
    private fun count(amount: Long): String =
        amount.toString().reversed().chunked(3).joinToString(" ").reversed()

    private fun coins(value: Double): String = CommonUtils.toShortNumber(value) ?: "0"
}
