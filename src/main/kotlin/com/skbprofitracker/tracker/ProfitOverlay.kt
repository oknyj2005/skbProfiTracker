package com.skbprofitracker.tracker

import com.skbprofitracker.SkbMod
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.features.commands.SkbProfitCommand
import com.skbprofitracker.features.overlays.base.TrackerViewMode
import com.skbprofitracker.items.ItemColors
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.price.PriceCache
import com.skbprofitracker.price.PriceMode
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.gui.GuiButton
import com.skbprofitracker.utils.gui.LineAction
import com.skbprofitracker.utils.gui.LineInfo
import com.skbprofitracker.utils.gui.SkbGui
import com.skbprofitracker.utils.gui.Table
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.chat.Component
import java.text.DecimalFormat
import kotlin.math.abs

/**
 * The overlay, laid out the way Feesh lays out its profit tracker.
 *
 * Everything except the activity name and the absolute numbers is a direct copy of Feesh's
 * formatting: grey dashes, a rarity coloured name, a gold value column, aqua labels and
 * bold totals. Colours come from [ItemColors] because our item table is generated from NEU
 * names and carries no rarity prefix.
 */
object ProfitOverlay {
    private const val MAX_VISIBLE_ITEMS = 10

    /** Rows cheaper than this are not worth a line of their own; they fold into "Other items". */
    private const val HIDE_CHEAPER_THAN = 1_000_000.0

    private const val GRAY = "§7"
    private const val WHITE = "§f"
    private const val GOLD = "§6"
    private const val AQUA = "§b"
    private const val GREEN = "§a"
    private const val RED = "§c"
    private const val BOLD = "§l"
    private const val RESET = "§r"

    private val gui = SkbGui()

    private var displayedTarget: TrackerViewTarget? = null
    private var displayedState: PanelState? = null
    private var valuation: Valuation? = null
    private var header = emptyList<LineInfo>()

    private data class ValuationKey(
        val target: TrackerViewTarget,
        val content: Long,
        val prices: Long,
        val colors: Long,
        val priceMode: PriceMode,
    )

    private data class PanelState(
        val valuationKey: ValuationKey,
        val time: Long,
        val paused: Boolean,
        val counting: Boolean,
        val inCrystalHollows: Boolean,
        val resetArmed: Boolean,
        val selectorOpen: Boolean,
    )

    private data class Valuation(val key: ValuationKey, val view: ProfitView, val costs: CostView, val rows: ItemRows)

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
                    "${GRAY}- ${WHITE}12${GRAY}x ${AQUA}Flawed Sapphire  ${GOLD}3.7k",
                    "${GRAY}- ${WHITE}4 601${GRAY}x ${WHITE}Rough Sapphire  ${GOLD}2.3k",
                    "${AQUA}Profit: ${GOLD}18.2M ${GRAY}(${GOLD}24.1M${GRAY}/h)",
                    "${AQUA}Elapsed time: ${WHITE}2m 28s",
                )
            )
            .setCondition { ActivityBucket.current != null }

        EventBus.subscribe(ClientTickEvent::class) { onClientTick() }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is InventoryScreen || screen is ChatScreen) {
                ScreenMouseEvents.allowMouseClick(screen).register { current, click ->
                    !gui.click(current, click)
                }
                ScreenMouseEvents.allowMouseScroll(screen).register { current, mouseX, mouseY, _, vertical ->
                    displayedTarget != BucketLedger.currentTarget() || !gui.scrollRows(current, mouseX, mouseY, vertical)
                }
            }
        }
    }

    fun invalidate() {
        displayedState = null
    }

    private fun onClientTick() {
        val now = System.currentTimeMillis()
        val target = BucketLedger.currentTarget()
        if (target?.activity != displayedTarget?.activity) selectorOpen = false
        if (target == null) {
            if (displayedTarget == null) return
            gui.clearLines()
            displayedTarget = null
            displayedState = null
            valuation = null
            header = emptyList()
            return
        }

        val key = ValuationKey(target, BucketLedger.contentRevision, PriceCache.revision, ItemColors.revision, BucketLedger.priceMode)
        val inCrystalHollows = target.activity == Activity.MINING && ActivityBucket.isCrystalHollowsArea()
        val state = PanelState(
            key, BucketLedger.timeRevision, ActivityBucket.isMiningPausedInUi(),
            ActivityBucket.isCountingFor(target.activity, target.filter),
            inCrystalHollows,
            target.mode == TrackerViewMode.SESSION && SkbProfitCommand.isOverlaySessionResetArmed(target, now),
            selectorOpen,
        )
        val previous = displayedState
        if (state == previous) return

        val values = valuation?.takeIf { it.key == key } ?: run {
            val view = BucketLedger.view(target.activity, target.filter)
            val costs = CostLedger.view(target.activity, target.mode, key.priceMode, target.filter)
            Valuation(key, view, costs, buildItemRows(view, target))
        }
        val elapsed = BucketLedger.elapsedSeconds(target)
        val view = values.view.copy(elapsedSeconds = elapsed, perHour = hourly(values.view.totalValue, elapsed))
        val newHeader = if (previous?.valuationKey != key || previous.selectorOpen != state.selectorOpen) {
            buildHeader(view, target)
        } else {
            header
        }
        // A market refresh may occur while the client is pricing the list. Retry with the new snapshot next tick.
        if (PriceCache.revision != key.prices) return
        valuation = values
        header = newHeader

        val nextMode = target.mode.next()
        val nextModeLabel = nextMode.name.lowercase().replaceFirstChar { it.uppercase() }
        val paused = state.paused
        gui.setButtons(
            buildList {
                add(GuiButton(0, "${GRAY}[Click to show ${WHITE}[$nextModeLabel]${GRAY}]") {
                    useControl(target) { SkbProfitCommand.setViewMode(nextMode) }
                })
                add(GuiButton(1, pauseButtonText(paused, state.inCrystalHollows)) {
                    useControl(target) { if (ActivityBucket.isMiningPausedInUi() == paused) SkbProfitCommand.togglePause() }
                })
                add(GuiButton(2, resetButtonText(target, state.resetArmed)) {
                    useControl(target) { SkbProfitCommand.requestOverlayReset(target) }
                })
                if (view.activity == Activity.SLAYER || view.activity == Activity.MINING) {
                    add(GuiButton(3, filterButtonText(view.activity, view.filter)) {
                        useControl(target) { selectorOpen = !selectorOpen }
                    })
                }
            }
        )
        val items = values.rows
        gui.setScrollableLines(
            header, items.details, buildFooter(view, target, items.summary, values.costs, state),
            MAX_VISIBLE_ITEMS, resetScroll = displayedTarget != target,
        )
        displayedTarget = target
        displayedState = state
        // The move screen renders placeholders instead of the live overlay, so keep them equal to
        // what is on screen right now; a fixed sample made slayer numbers show up in a mining hole.
        gui.setSampleLines(buildSample(gui.getLines()))
    }

    private fun useControl(target: TrackerViewTarget, action: () -> Unit) {
        if (BucketLedger.currentTarget() == target) action()
        invalidate()
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

    private fun buildHeader(view: ProfitView, target: TrackerViewTarget): List<LineInfo> {
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
        if (selectorOpen) appendSelector(lines, target)
        return lines
    }

    private data class ItemRows(
        val details: List<LineInfo>,
        val summary: LineInfo?,
    )

    private fun buildItemRows(view: ProfitView, target: TrackerViewTarget): ItemRows {
        val detailedItems = mutableListOf<ItemLine>()
        var otherTypes = 0
        var otherAmount = 0L
        var otherValue = 0.0
        for (item in view.items) {
            if (item.unitPrice == null) continue
            if (item.value >= HIDE_CHEAPER_THAN) {
                detailedItems.add(item)
            } else {
                otherTypes++
                otherAmount += item.amount
                otherValue += item.value
            }
        }

        // Measure the columns once for the entire list so they stay in place while scrolling.
        val layout = Table.layout(SkbMod.mc.font, detailedItems.map(::row), " ")
        val details = layout.rows.mapIndexed { index, cells ->
            val actions = if (target.canEditItems) buildActions(target, detailedItems[index]) else emptyList()
            LineInfo.withCells(cells, layout.tableWidth, actions = actions)
        }
        val summary = if (otherTypes > 0) {
            LineInfo(
                "${GRAY}- ${WHITE}Other items ${GRAY}(${count(otherAmount)}x, " +
                    "${WHITE}$otherTypes ${GRAY}types)  ${GOLD}${coins(otherValue)}"
            )
        } else null
        return ItemRows(details, summary)
    }

    private fun buildFooter(
        view: ProfitView,
        target: TrackerViewTarget,
        otherItems: LineInfo?,
        cost: CostView,
        state: PanelState,
    ): List<LineInfo> {
        val lines = mutableListOf<LineInfo>()
        if (otherItems != null) lines.add(otherItems)
        val unpricedCosts = cost.lines.filter { it.unitPrice == null }
        val partial = view.unpricedTypes > 0 || unpricedCosts.isNotEmpty()
        if (partial) {
            val missing = buildList {
                for (item in view.items) {
                    if (item.unitPrice == null) add(Component.literal("Loot: ${item.amount}x ${item.name}"))
                }
                for (item in unpricedCosts) {
                    add(Component.literal("Cost: ${item.amount}x ${item.name}"))
                }
            }
            lines.add(LineInfo("${GRAY}- ${missing.size} unpriced types", tooltip = missing))
        }

        lines.add(LineInfo())

        val priceMode = state.valuationKey.priceMode
        val badge = "[${priceMode.shortName}]"
        val changePrice: () -> Unit = {
            useControl(target) {
                if (BucketLedger.priceMode == priceMode) SkbProfitCommand.setPriceMode(priceMode.next())
            }
        }
        val hasCosts = view.activity != Activity.RIFT &&
            (cost.lines.isNotEmpty() || cost.coinsSpent != 0.0 || cost.estimatedSlayerFees > 0)

        // A slayer type without history would otherwise show the cost line of the combined
        // bucket next to a zero income: the numbers would contradict each other.
        if (view.items.isEmpty() && view.elapsedSeconds == 0L && !hasCosts) {
            lines.add(LineInfo("${GRAY}No data recorded for this type yet."))
            return lines
        }

        if (hasCosts) {
            val scope = if (view.mode == TrackerViewMode.SESSION) "Session" else "Total"
            val label = when {
                unpricedCosts.isNotEmpty() -> "$scope priced costs"
                cost.totalValue < 0.0 -> "$scope cost credit"
                else -> "$scope costs"
            }
            val breakdown = buildList {
                for (line in cost.lines) {
                    val value = if (line.unitPrice != null) coins(line.value) else "unpriced"
                    add(Component.literal("${line.amount}x ${line.name}  $value"))
                }
                if (cost.coinsSpent != 0.0 || cost.estimatedSlayerFees > 0) {
                    val coinLabel = if (view.activity == Activity.SLAYER) "Slayer coin flow" else "Coins spent"
                    val coinSign = if (cost.coinsSpent < 0.0) "+" else if (cost.coinsSpent > 0.0) "-" else ""
                    add(Component.literal("$coinLabel  $coinSign${coins(abs(cost.coinsSpent))}"))
                }
                if (cost.estimatedSlayerFees > 0) {
                    add(Component.literal("Includes ${cost.estimatedSlayerFees} rounded bank fee(s)"))
                }
            }
            val costColor = if (cost.totalValue > 0.0) RED else GREEN
            val costSign = if (cost.totalValue > 0.0) "-" else if (cost.totalValue < 0.0) "+" else ""
            lines.add(LineInfo("$AQUA$label: $costColor$costSign${coins(abs(cost.totalValue))}", tooltip = breakdown))

            val net = view.totalValue - cost.totalValue
            val profitLabel = if (partial) "Partial net profit" else "Net profit"
            val netPerHour = hourly(net, view.elapsedSeconds)
            val color = if (net < 0.0) RED else GOLD
            val perHour = if (view.showPerHour) {
                "${GRAY}(${color}${coins(netPerHour)}${GRAY}/h) "
            } else {
                ""
            }
            lines.add(
                LineInfo(
                    "${AQUA}$profitLabel: ${color}${BOLD}${coins(net)} ${RESET}" +
                        "$perHour$GRAY$badge",
                    clickSuffix = badge,
                    onClick = changePrice,
                )
            )
        } else {
            val profitLabel = if (partial) "Partial profit" else "Profit"
            val perHour = if (view.showPerHour) {
                "${GRAY}(${GOLD}${coins(view.perHour)}${GRAY}/h) "
            } else {
                ""
            }
            lines.add(
                LineInfo(
                    "${AQUA}$profitLabel: ${GOLD}${BOLD}${coins(view.totalValue)} ${RESET}" +
                        "$perHour$GRAY$badge",
                    clickSuffix = badge,
                    onClick = changePrice,
                )
            )
        }

        // SkyHanni prints the boss count right above the profit block; keeping it at the
        // bottom next to the timer makes the "per boss" mental math one glance.
        if (view.activity == Activity.SLAYER && view.bosses > 0) {
            val perHour = hourly(view.bosses.toDouble(), view.elapsedSeconds)
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
            val breakdown = view.corpseTypes
                .filter { it.second > 0 }
                .joinToString("${GRAY}, ") { (type, count) ->
                    "${WHITE}${comma(count)}${GRAY}${type.take(1).uppercase()}"
                }
            val suffix = if (breakdown.isEmpty()) "" else " ${GRAY}($breakdown${GRAY})"
            lines.add(LineInfo("${AQUA}Corpses opened: ${WHITE}${comma(view.corpses)}$suffix"))
        }

        val hasTimedSlice = view.activity != Activity.MINING ||
            view.filter == ActivityBucket.MINING_NORMAL
        if (hasTimedSlice) {
            val status = when {
                state.paused -> " ${GRAY}[Paused]"
                !state.counting -> " ${GRAY}[Idle]"
                else -> ""
            }
            lines.add(
                LineInfo(
                    "${AQUA}Elapsed time: ${WHITE}${
                        CommonUtils.formatTimeElapsed(view.elapsedSeconds.toInt())
                    }$status"
                )
            )
        }

        return lines
    }

    private fun appendSelector(lines: MutableList<LineInfo>, target: TrackerViewTarget) {
        val options = BucketLedger.availableFilters(target.activity)
        if (options.isEmpty()) return

        lines.add(LineInfo())
        lines.add(LineInfo("${GRAY}Show ${if (target.activity == Activity.SLAYER) "slayer type" else "mining type"}:"))

        val rows = options.map { option ->
            val (value, _, _) = BucketLedger.typeSummary(target.activity, option)
            val marker = if (option == target.filter) "${GREEN}▶ " else "${GRAY}  "
            val label = if (option == BucketLedger.ALL) "All" else option
            listOf(
                "$marker${if (option == target.filter) "${WHITE}$label" else "${GRAY}$label"}",
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
                        useControl(target) {
                            Debug.log("[pick] ${target.activity} -> $option")
                            BucketLedger.setFilter(target.activity, option)
                            selectorOpen = false
                        }
                    },
                )
            )
        }
    }

    private fun comma(value: Long): String = DecimalFormat("#,##0").format(value)

    private fun row(item: ItemLine): List<String> {
        val name = ItemTable.getPetDisplayName(item.key)
            ?: ItemColors.get(item.key) + item.name.removeSuffix(" Gemstone")
        return listOf(
            "${GRAY}- ${WHITE}${count(item.amount)}${GRAY}x $name",
            "${GOLD}${coins(item.value)}",
        )
    }

    /**
     * Editing is slice-local by design: `All` is a sum over slices, so a correction made there has
     * no bucket to land in. Pick the slice first, and `All` follows automatically.
     */
    private fun buildActions(target: TrackerViewTarget, item: ItemLine): List<LineAction> {
        return listOf(
            LineAction("${GRAY}[${GREEN}+${GRAY}]") { editItem(target, item.key, 1L) },
            LineAction("${GRAY}[${RED}-${GRAY}]") { editItem(target, item.key, -1L) },
            LineAction("${GRAY}[${RED}x${GRAY}]") { editItem(target, item.key, -item.amount) },
        )
    }

    private fun editItem(target: TrackerViewTarget, key: String, delta: Long) {
        BucketLedger.editItem(target, key, delta)
        invalidate()
    }

    private fun filterButtonText(activity: Activity, filter: String): String {
        val title = if (activity == Activity.SLAYER) "Slayer" else "Mining"
        val label = if (filter == BucketLedger.ALL) "All" else filter
        return "${GRAY}[$title: ${WHITE}$label${GRAY} ${GRAY}▾${GRAY}]"
    }

    private fun pauseButtonText(paused: Boolean, inCrystalHollows: Boolean): String = when {
        paused && inCrystalHollows -> "${GRAY}[${GREEN}Start mining${GRAY}]"
        paused -> "${GRAY}[${GREEN}Click to resume${GRAY}]"
        else -> "${GRAY}[${GOLD}Click to pause${GRAY}]"
    }

    private fun resetButtonText(target: TrackerViewTarget, armed: Boolean): String {
        if (target.mode == TrackerViewMode.TOTAL) {
            return "${GRAY}[Click to reset ${WHITE}Total${GRAY}]"
        }
        return if (armed) {
            "${GRAY}[${RED}Click again to clear ${WHITE}Session${GRAY}]"
        } else {
            "${GRAY}[Click to reset ${WHITE}Session${GRAY}]"
        }
    }

    /** Feesh writes big counts as `1 909 194`, which makes the row scannable. */
    private fun count(amount: Long): String =
        amount.toString().reversed().chunked(3).joinToString(" ").reversed()

    private fun coins(value: Double): String = CommonUtils.toShortNumber(value) ?: "0"

    private fun hourly(value: Double, seconds: Long): Double =
        if (seconds > 0) value * 3_600.0 / seconds else 0.0
}
