package com.skbprofitracker.utils.gui

import com.skbprofitracker.events.models.GameRenderEvent
import com.skbprofitracker.events.models.ScreenAfterBackgroundRenderEvent
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.utils.Debug
import com.skbprofitracker.utils.InputUtils
import com.skbprofitracker.utils.WorldUtils
import com.skbprofitracker.utils.getScreenCompat
import com.skbprofitracker.utils.enums.Alignment
import com.skbprofitracker.utils.enums.ColorCodes.*
import com.skbprofitracker.utils.data.PersistentDataManager
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.GuiGraphicsExtractor as GuiGraphics
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.input.MouseButtonEvent
import java.awt.Color
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A button within an overlay line. Contains the display text and click callback.
 * Buttons are rendered on top of all other content in the overlay.
 * @param lineIndex 0-based index of the line in the overlay. Used for click detection.
 * @param text The text with formatting to display on the button.
 * @param onClick The callback to execute when the button is clicked.
 */
data class GuiButton(
    val lineIndex: Int,
    val text: String,
    val onClick: () -> Unit
)

/**
 * An inline action button for a specific overlay line. Defined by the overlay.
 * @param text The formatted display text (e.g. "[+]", "[-]", "[x]" with color codes).
 * @param onClick The callback to execute when the button is clicked.
 */
data class LineAction(
    val text: String,
    val onClick: () -> Unit
)

/** 
 * A segment of a line used to display column-based text with table alignment.
 * @param text The text to display in the cell.
 * @param xOffset The x offset within the table - set by [Table.layout].
 * @param columnWidth The width of the column - set by [Table.layout].
 */
data class LineCell(
    val text: String,
    val xOffset: Int = 0,
    val columnWidth: Int = 0,
)

data class LineInfo(
    val text: String = "",
    val cells: List<LineCell>? = null, // Text is not needed if Cells defined
    internal val layoutTableWidth: Int? = null,
    val tooltip: List<Component>? = null,
    val actions: List<LineAction> = emptyList(),
    /** Whole-row click handler; used by pickers that should not need a hover pill. */
    val onClick: (() -> Unit)? = null,
    /** Limit a text row's click handler to its trailing badge. */
    val clickSuffix: String? = null,
) {
    val tableWidth: Int?
        get() {
            layoutTableWidth?.let { return it }
            val segs = cells ?: return null
            if (segs.isEmpty()) return null
            return segs.maxOf { cell ->
                cell.xOffset + (cell.columnWidth.takeIf { it > 0 } ?: 0)
            }
        }

    companion object {
        fun withCells(
            cells: List<LineCell>,
            tableWidth: Int,
            text: String = "",
            tooltip: List<Component>? = null,
            actions: List<LineAction> = emptyList(),
            onClick: (() -> Unit)? = null,
        ): LineInfo = LineInfo(
            text = text,
            cells = cells,
            layoutTableWidth = tableWidth,
            tooltip = tooltip,
            actions = actions,
            onClick = onClick,
        )
    }
}

object Table {
    data class LayoutResult(
        val tableWidth: Int,
        val rows: List<List<LineCell>>,
    )

    /**
     * Lays out columnar rows with shared column widths and x offsets.
     * @param rows Each row has the same number of cells; empty string skips cell content but keeps column slots.
     * @param separator Text drawn between enabled columns (column max width > 0).
     */
    fun layout(font: Font, rows: List<List<String>>, separator: String): LayoutResult {
        if (rows.isEmpty()) return LayoutResult(0, emptyList())

        val columnCount = rows.maxOf { it.size }
        val normalizedRows = rows.map { row ->
            if (row.size >= columnCount) row else row + List(columnCount - row.size) { "" }
        }

        val separatorWidth = font.width(Component.literal(separator))
        val columnWidths = (0 until columnCount).map { col ->
            normalizedRows.maxOf { font.width(Component.literal(it[col])) }
        }
        val enabled = columnWidths.map { it > 0 }

        val columnX = IntArray(columnCount) { -1 }
        val separatorX = mutableListOf<Int>()
        var x = 0
        var firstColumn = true
        for (col in 0 until columnCount) {
            if (!enabled[col]) continue
            if (!firstColumn) {
                separatorX.add(x)
                x += separatorWidth
            }
            columnX[col] = x
            x += columnWidths[col]
            firstColumn = false
        }

        val tableWidth = x
        val builtRows = normalizedRows.map { row ->
            val cells = mutableListOf<LineCell>()
            var separatorIndex = 0
            var firstColumnInRow = true
            for (col in 0 until columnCount) {
                if (!enabled[col]) continue
                if (!firstColumnInRow) {
                    val sepX = separatorX[separatorIndex++]
                    cells.add(LineCell(separator, sepX, separatorWidth))
                }
                if (row[col].isNotEmpty()) {
                    cells.add(LineCell(row[col], columnX[col], columnWidths[col]))
                }
                firstColumnInRow = false
            }
            cells
        }

        return LayoutResult(tableWidth, builtRows)
    }
}

/**
 * A class that represents a GUI for the Skb mod. GUI is shown when in Skyblock and the condition is met.
 * @property coordsDataKey The unique key for this GUI, used for saving/loading coords/scale configuration.
 * @property x The x position of the GUI.
 * @property y The y position of the GUI.
 * @property scale The scale of the GUI.
 * @property alignment The alignment of the GUI.
 * @property condition The condition that must be met for the GUI to be displayed.
 * @property settingsKey The settings key that must be enabled for the GUI to be displayed.
 * @property applyCustomStyleKey The settings key that must be enabled for the GUI to have custom style applied. When false, custom Overlays style (background, border, colors) is not applied to this GUI.
 * @property isClickable Whether the GUI is clickable, and will be rendered in front of background when Inventory GUI is opened.
 * @property lines The lines of the GUI (text, optional tooltips, optional inline actions).
 * @property sampleLines The sample lines of the GUI for MoveGuis screen.
 */
class SkbGui {
    companion object {
        private val registeredGuis = mutableListOf<SkbGui>()

        fun getAllRegisteredGuis(): List<SkbGui> {
            return registeredGuis.toList()
        }

        /** Applies saved scale/position/alignment to all registered GUIs, after they are loaded from file. */
        fun applyOverlayCoordsToAllGuis() {
            registeredGuis.forEach { it.applyCoordsFromStorage() }
        }
    }

    private var coordsDataKey: String = ""
    private var x: Int = 10
    private var y: Int = 10
    private var scale: Float = 1.0f
    private var alignment: Alignment = Alignment.LEFT
    private var condition: () -> Boolean = { true }
    private var settingsKey: (() -> Boolean)? = null
    private var applyCustomStyleKey: () -> Boolean = { true }
    private var isClickable: Boolean = false
    private var lines: List<LineInfo> = emptyList()
    private var sampleLines: List<String> = emptyList()
    private val viewport = RowViewport()
    private var scrollableLines: ScrollableLines? = null
    private var layoutFont: Font? = null
    private var contentLayout: OverlayLayout? = null
    private var interactiveLayout: OverlayLayout? = null
    private var renderedOverlay: RenderedOverlay? = null

    private var color: Int = Color(255, 255, 255, 255).rgb
    // Buttons are shown when in inventory/chat on top of the overlay (e.g. Reset, Pause, switch mode).
    private var buttons: List<GuiButton> = emptyList()

    constructor() {
        registeredGuis.add(this)
        EventBus.subscribe(GameRenderEvent::class, { event -> onDraw(event.drawContext, event.textRenderer, event.mcClient) })
        EventBus.subscribe(ScreenAfterBackgroundRenderEvent::class, ::onPostDrawAfterBackground)
    }

    fun getCoordsDataKey(): String = coordsDataKey
    fun getX(): Int = x
    fun getY(): Int = y
    fun getScale(): Float = scale
    fun getAlignment(): Alignment = alignment
    fun getLines(): List<LineInfo> = lines
    fun getSampleLines(): List<String> = sampleLines
    fun getSettingsKey(): (() -> Boolean)? = settingsKey
    fun getCondition(): () -> Boolean = condition
    fun getIsClickable(): Boolean = isClickable

    fun setCoordsDataKey(coordsDataKey: String): SkbGui {
        this.coordsDataKey = coordsDataKey
        applyCoordsFromStorage()
        return this
    }

    /** Applies x, y, scale, alignment from overlayCoordsData. No-op if coordsDataKey is empty. */
    fun applyCoordsFromStorage(): SkbGui {
        if (coordsDataKey.isEmpty()) return this
        val savedData = PersistentDataManager.getOverlayCoordsData(coordsDataKey)
        this.x = savedData.x
        this.y = savedData.y
        this.scale = savedData.scale
        this.alignment = savedData.alignment
        return this
    }

    fun setX(x: Int): SkbGui {
        this.x = x
        return this
    }

    fun setY(y: Int): SkbGui {
        this.y = y
        return this
    }

    fun setScale(scale: Float): SkbGui {
        this.scale = scale
        return this
    }

    fun setAlignment(alignment: Alignment): SkbGui {
        this.alignment = alignment
        return this
    }
        
    
    fun setCondition(condition: () -> Boolean): SkbGui {
        this.condition = condition
        return this
    }

    fun setSettingsKey(settingsKey: () -> Boolean): SkbGui {
        this.settingsKey = settingsKey
        return this
    }

    fun setApplyCustomStyleKey(applyCustomStyleKey: () -> Boolean): SkbGui {
        this.applyCustomStyleKey = applyCustomStyleKey
        return this
    }

    fun setClickable(isClickable: Boolean): SkbGui {
        this.isClickable = isClickable
        return this
    }

    private data class ScrollableLines(
        val header: List<LineInfo>,
        val rows: List<LineInfo>,
        val footer: List<LineInfo>,
    )

    fun setScrollableLines(
        header: List<LineInfo>,
        rows: List<LineInfo>,
        footer: List<LineInfo>,
        limit: Int,
        resetScroll: Boolean,
    ) {
        val content = ScrollableLines(header, rows, footer)
        scrollableLines = content
        if (resetScroll) viewport.reset()
        viewport.resize(rows.size, limit)
        updateVisibleLines(content)
    }

    private fun updateVisibleLines(content: ScrollableLines) {
        val first = viewport.offset
        val end = first + viewport.visibleCount
        lines = buildList {
            addAll(content.header)
            addAll(content.rows.subList(first, end))
            if (viewport.canScroll) {
                add(LineInfo("${GRAY}Items ${first + 1}–$end / ${content.rows.size} ↕"))
            }
            addAll(content.footer)
        }
        contentLayout = null
        interactiveLayout = null
    }

    /** The screen keeps its own wheel handling outside a visible, overflowing list. */
    fun scrollRows(screen: Screen, mouseX: Double, mouseY: Double, vertical: Double): Boolean {
        val content = scrollableLines ?: return false
        if (!viewport.canScroll || vertical == 0.0 || !vertical.isFinite()) return false
        if (!isClickable || !canDraw()) return false

        val frame = renderedOverlay?.takeIf { it.screen === screen && it.scrollable === content } ?: return false
        val hovered = frame.hitbox.rowAt(mouseX, mouseY, frame.layout.lines.size) ?: return false
        val first = frame.buttonCount + content.header.size
        val indicator = first + viewport.visibleCount
        if (hovered !in first..indicator) return false

        if (viewport.scroll(vertical)) updateVisibleLines(content)
        return true
    }

    /**
     * Sets the list of buttons. Each button has lineIndex, text, and onClick callback.
     */
    fun setButtons(buttons: List<GuiButton>): SkbGui {
        this.buttons = buttons.sortedBy { it.lineIndex }
        interactiveLayout = null
        return this
    }

    fun getButtons(): List<GuiButton> = buttons

    fun clearLines(): SkbGui {
        this.lines = emptyList()
        this.buttons = emptyList()
        scrollableLines = null
        renderedOverlay = null
        viewport.reset()
        contentLayout = null
        interactiveLayout = null
        return this
    }

    fun setSampleLines(sampleLines: List<String>): SkbGui {
        this.sampleLines = sampleLines
        return this
    }

    private fun onDraw(drawContext: GuiGraphics, textRenderer: Font, mcClient: Minecraft) {
        if (mcClient.getScreenCompat() is InventoryScreen && isClickable) return
        draw(drawContext, textRenderer, mcClient)
    }

    private fun onPostDrawAfterBackground(event: ScreenAfterBackgroundRenderEvent) { // Draw in front of dark background when Inventory is opened
        if (!isClickable) return
        if (event.screen !is InventoryScreen) return

        draw(event.drawContext, event.textRenderer, event.mcClient, event.mouseX, event.mouseY)
    }

    fun click(screen: Screen, click: MouseButtonEvent): Boolean {
        if (!isClickable || !canDraw() || !InputUtils.isLeftMouseButton(click.buttonInfo().button)) return false
        if (OverlayStyle.BUTTONS_REQUIRE_CTRL_CLICK && !InputUtils.hasControlDown(click)) return false
        val frame = renderedOverlay?.takeIf { it.screen === screen } ?: return false
        val index = frame.hitbox.rowAt(click.x(), click.y(), frame.layout.lines.size) ?: return false
        if (!frame.hitbox.isOverText(click.y(), index)) return false
        val line = frame.layout.lines[index]
        val action = when {
            frame.inlineRow == index && line.actions.isNotEmpty() -> getClickedLineAction(frame, line, click.x())?.onClick
            isOverClickableText(frame, line, click.x()) -> line.onClick
            else -> null
        } ?: return false

        try {
            Debug.log("[click] ${screen.javaClass.simpleName} row=$index x=${click.x()} y=${click.y()}")
            action()
        } catch (ex: Exception) {
            Debug.log("[click] failed: ${ex.javaClass.simpleName} ${ex.message}")
        }
        return true
    }

    private fun isOverClickableText(frame: RenderedOverlay, line: LineInfo, mouseX: Double): Boolean {
        if (line.onClick == null) return false
        val width = getLineWidth(frame.font, line)
        val start = alignedRowLeft(frame.hitbox.alignment, frame.hitbox.left, frame.hitbox.width, width)
        val suffix = line.clickSuffix ?: return frame.hitbox.containsX(mouseX, start, width)
        val suffixWidth = frame.font.width(Component.literal(suffix))
        return frame.hitbox.containsX(mouseX, start + width - suffixWidth, suffixWidth)
    }

    /**
     * Recalculates x coordinate when alignment changes to keep overlay at the same visual position.
     * @param textRenderer Font to calculate text widths
     * @param oldAlignment Previous alignment
     * @param newAlignment New alignment
     */
    fun recalculateXForAlignment(textRenderer: Font, oldAlignment: Alignment, newAlignment: Alignment) {
        if (oldAlignment == newAlignment) return
        
        val linesToUse = when {
            sampleLines.isNotEmpty() -> sampleLines
            else -> emptyList()
        }
        if (linesToUse.isEmpty()) return
        
        val maxWidth = linesToUse.maxOfOrNull { textRenderer.width(Component.literal(it)) } ?: 0
        val renderedWidth = (maxWidth * scale).toInt()
        
        // Convert current x from old alignment reference point to actual left edge (screen space)
        val leftEdge = when (oldAlignment) {
            Alignment.LEFT -> x
            Alignment.CENTER -> x - renderedWidth / 2
            Alignment.RIGHT -> x - renderedWidth
        }
        
        // Convert left edge to new alignment reference point
        val newX = when (newAlignment) {
            Alignment.LEFT -> leftEdge
            Alignment.CENTER -> leftEdge + renderedWidth / 2
            Alignment.RIGHT -> leftEdge + renderedWidth
        }
        
        this.x = newX
    }
    
    private fun getLineWidth(textRenderer: Font, lineInfo: LineInfo): Int {
        if (lineInfo.usesCells()) return lineInfo.tableWidth ?: 0
        return textRenderer.width(Component.literal(lineInfo.text))
    }

    private data class PreparedText(val xOffset: Int, val text: Component)

    private class OverlayLayout(
        val lines: List<LineInfo>,
        val maxWidth: Int,
        val alignment: Alignment,
        val rows: List<List<PreparedText>>,
    ) {
        val hoveredRows = mutableMapOf<Int, List<PreparedText>>()
    }

    private data class RenderedOverlay(
        val screen: Screen,
        val font: Font,
        val layout: OverlayLayout,
        val hitbox: OverlayHitbox,
        val inlineRow: Int?,
        val scrollable: ScrollableLines?,
        val buttonCount: Int,
    )

    private fun getLayout(textRenderer: Font, showButtons: Boolean): OverlayLayout {
        if (layoutFont !== textRenderer) {
            layoutFont = textRenderer
            contentLayout = null
            interactiveLayout = null
        }
        val cached = if (showButtons) interactiveLayout else contentLayout
        if (cached != null && cached.alignment == alignment) return cached

        val displayLines = if (showButtons) buttons.map { LineInfo(it.text, onClick = it.onClick) } + lines else lines
        val widths = displayLines.map { getLineWidth(textRenderer, it) }
        val maxWidth = widths.maxOrNull() ?: 0
        val rows = displayLines.mapIndexed { index, line ->
            try {
                prepareLine(textRenderer, line, widths[index], maxWidth)
            } catch (ex: Exception) {
                Debug.log("[draw] could not prepare line $index: ${ex.message}")
                emptyList()
            }
        }
        val layout = OverlayLayout(displayLines, maxWidth, alignment, rows)
        if (showButtons) interactiveLayout = layout else contentLayout = layout
        return layout
    }

    /**
     * Converts x coordinate (which represents different points based on alignment) to actual left edge.
     * Uses scaled width for CENTER/RIGHT so that the reference point stays correct after matrices.scale(scale).
     */
    private fun scaledLeftEdge(maxWidth: Int): Int {
        val renderedWidth = (maxWidth * scale).toInt()
        val left = when (alignment) {
            Alignment.LEFT -> x
            Alignment.CENTER -> x - renderedWidth / 2
            Alignment.RIGHT -> x - renderedWidth
        }
        return (left / scale).toInt()
    }

    private fun scaledTop(): Int = (y / scale).toInt()

    private fun canDraw(): Boolean =
        lines.isNotEmpty() && WorldUtils.isInSkyblock() && settingsKey?.invoke() != false && condition()

    private fun draw(drawContext: GuiGraphics, textRenderer: Font, mcClient: Minecraft, mouseX: Int? = null, mouseY: Int? = null) {
        renderedOverlay = null
        if (!canDraw()) return
        val screen = mcClient.getScreenCompat()
        if (screen is MoveGuisScreen) return

        val interactive = isClickable && (screen is InventoryScreen || screen is ChatScreen)
        val layout = getLayout(textRenderer, showButtons = interactive)
        val left = scaledLeftEdge(layout.maxWidth)
        val top = scaledTop()
        val rowHeight = textRenderer.lineHeight + 2
        val hitbox = if (interactive) OverlayHitbox(left, top, layout.maxWidth, textRenderer.lineHeight, scale, alignment) else null

        val hoveredLineIndex = if (mouseX != null && mouseY != null) {
            hitbox?.rowAt(mouseX.toDouble(), mouseY.toDouble(), layout.lines.size)
        } else null
        val hoveredLineInfo = hoveredLineIndex?.let { layout.lines[it] }

        val hoveredActions = hoveredLineInfo?.actions?.takeIf { it.isNotEmpty() }
        val hoveredTooltip = hoveredLineInfo?.tooltip

        val height = layout.lines.size * rowHeight
        var currentY = top

        if (applyCustomStyleKey()) {
            val overlayBackgroundCoords = getOverlayBackgroundCoords(left, top, layout.maxWidth, height)
            val overlayScreenCoords = toScreenCoords(overlayBackgroundCoords)
            drawOverlayBackground(drawContext, overlayScreenCoords)
            drawOverlayBorder(drawContext, overlayScreenCoords) // I had to draw it before scaling, so the border is not scaled, and aligned with background.
        }

        drawContext.pose().pushMatrix()
        drawContext.pose().scale(scale, scale)

        for (index in layout.rows.indices) {
            try {
                val row = if (index == hoveredLineIndex && hoveredActions != null) {
                    layout.hoveredRows.getOrPut(index) {
                        prepareHoveredLine(textRenderer, layout.lines[index], layout.maxWidth)
                    }
                } else {
                    layout.rows[index]
                }
                for (placement in row) {
                    drawStringCompat(drawContext, textRenderer, placement.text, left + placement.xOffset, currentY, color, true)
                }
            } catch (ex: Exception) {
                Debug.log("[draw] line $index failed (hovered=$hoveredLineIndex): ${ex.message}")
            }
            currentY += rowHeight
        }

        drawContext.pose().popMatrix()

        if (screen != null && hitbox != null) {
            renderedOverlay = RenderedOverlay(
                screen, textRenderer, layout, hitbox,
                hoveredLineIndex.takeIf { hoveredActions != null }, scrollableLines, buttons.size,
            )
        }

        if (!hoveredTooltip.isNullOrEmpty() && mouseX != null && mouseY != null) {
            drawContext.setComponentTooltipForNextFrame(textRenderer, hoveredTooltip, mouseX, mouseY)
        }
    }

    private fun LineInfo?.usesCells(): Boolean = this != null && !this.cells.isNullOrEmpty()

    private data class HoveredLineMetrics(
        val buttonsStr: String,
        val buttonsWidth: Int,
        val reservedWidth: Int,
        val lineAvailableWidth: Int,
        val clippedLine: String,
        val clippedLineWidth: Int,
        val usesCells: Boolean,
    )

    private fun getHoveredLineMetrics(
        textRenderer: Font,
        lineInfo: LineInfo?,
        line: String,
        maxWidth: Int,
        actions: List<LineAction>,
    ): HoveredLineMetrics {
        val buttonsStr = actions.joinToString("") { it.text }
        val buttonsWidth = textRenderer.width(Component.literal(buttonsStr))
        val reservedWidth = buttonsWidth + textRenderer.width(Component.literal(" "))
        val usesCells = lineInfo.usesCells()
        val lineAvailableWidth = (maxWidth - reservedWidth).coerceAtLeast(0)
        val clippedLine = if (usesCells) line else trimTextEndingToMaxWidth(textRenderer, line, lineAvailableWidth)
        val clippedLineWidth = if (usesCells) lineInfo?.tableWidth ?: 0
        else textRenderer.width(Component.literal(clippedLine))
        return HoveredLineMetrics(
            buttonsStr, buttonsWidth, reservedWidth, lineAvailableWidth, clippedLine, clippedLineWidth, usesCells,
        )
    }

    private fun prepareHoveredLine(font: Font, line: LineInfo, maxWidth: Int): List<PreparedText> {
        val metrics = getHoveredLineMetrics(font, line, line.text, maxWidth, line.actions)
        val buttonsLeft = inlineActionsLeft(alignment, 0, maxWidth, line, metrics)
        val buttons = PreparedText(buttonsLeft, Component.literal(metrics.buttonsStr))
        val content = if (metrics.usesCells) {
            when (alignment) {
                Alignment.LEFT -> prepareCellsLine(
                    font, line, 0, maxWidth, firstColumnDrawLeftOverride = metrics.reservedWidth,
                )
                Alignment.RIGHT -> prepareCellsLine(
                    font, line, 0, maxWidth, clipRightOverride = metrics.lineAvailableWidth,
                )
                Alignment.CENTER -> prepareCellsLine(
                    font, line, buttonsLeft, line.tableWidth ?: metrics.clippedLineWidth,
                    firstColumnDrawLeftOverride = buttonsLeft + metrics.reservedWidth,
                )
            }
        } else {
            val left = if (alignment == Alignment.RIGHT) metrics.lineAvailableWidth - metrics.clippedLineWidth
            else buttonsLeft + metrics.reservedWidth
            prepareTextLine(font, metrics.clippedLine, left, metrics.lineAvailableWidth)
        }
        return if (alignment == Alignment.RIGHT) content + buttons else listOf(buttons) + content
    }

    private fun prepareLine(font: Font, line: LineInfo, width: Int, maxWidth: Int): List<PreparedText> {
        val start = if (alignment == Alignment.RIGHT && line.usesCells()) 0
        else alignedRowLeft(alignment, 0, maxWidth, width)
        val contentWidth = if (alignment == Alignment.RIGHT) maxWidth else line.tableWidth ?: maxWidth
        return if (line.usesCells()) prepareCellsLine(font, line, start, contentWidth)
        else prepareTextLine(font, line.text, start, contentWidth)
    }

    private fun prepareTextLine(font: Font, text: String, start: Int, width: Int): List<PreparedText> {
        val displayText = trimTextEndingToMaxWidth(font, text, width)
        return if (displayText.isEmpty()) emptyList() else listOf(PreparedText(start, Component.literal(displayText)))
    }

    private fun prepareCellsLine(
        textRenderer: Font,
        lineInfo: LineInfo,
        lineStartX: Int,
        contentWidth: Int,
        clipRightOverride: Int? = null,
        firstColumnDrawLeftOverride: Int? = null,
    ): List<PreparedText> = buildList {
        val tableWidth = lineInfo.tableWidth
            ?: lineInfo.cells!!.maxOf { it.xOffset + textRenderer.width(Component.literal(it.text)) }
        val defaultClipLeft = lineStartX
        val clipRight = clipRightOverride ?: (lineStartX + contentWidth)
        val cellOriginX = when (alignment) {
            Alignment.RIGHT -> lineStartX + contentWidth - tableWidth
            else -> lineStartX
        }
        val firstColumnXOffset = lineInfo.cells!!.minOf { it.xOffset }

        fun resolveCellDrawBounds(
            cell: LineCell,
            columnStart: Int,
            columnEnd: Int,
        ): Pair<Int, Int>? {
            val isFirstColumnShifted = cell.xOffset == firstColumnXOffset && firstColumnDrawLeftOverride != null
            if (isFirstColumnShifted) {
                val drawLeft = firstColumnDrawLeftOverride!!
                val drawRight = minOf(columnEnd, clipRight)
                return if (drawRight <= drawLeft) null else drawLeft to drawRight
            }

            if (columnEnd <= defaultClipLeft || columnStart >= clipRight) return null
            val drawLeft = maxOf(columnStart, defaultClipLeft)
            val drawRight = minOf(columnEnd, clipRight)
            return if (drawRight <= drawLeft) null else drawLeft to drawRight
        }

        fun getCellDisplayText(
            cell: LineCell,
            drawLeft: Int,
            columnStart: Int,
            isFirstColumnShifted: Boolean,
            drawWidth: Int,
        ): String? {
            val cellText = if (isFirstColumnShifted) {
                cell.text
            } else {
                var text = cell.text
                val leadingSkip = drawLeft - columnStart
                if (leadingSkip > 0) {
                    text = trimTextLeading(textRenderer, text, leadingSkip)
                }
                text
            }
            if (cellText.isEmpty()) return null
            val displayText = trimTextEndingToMaxWidth(textRenderer, cellText, drawWidth)
            return displayText.takeIf { it.isNotEmpty() }
        }

        fun getCellDrawX(
            drawLeft: Int,
            drawRight: Int,
            drawWidth: Int,
            displayTextWidth: Int,
            isFirstColumnShifted: Boolean,
        ): Int = when {
            isFirstColumnShifted -> drawLeft
            alignment == Alignment.RIGHT -> drawRight - displayTextWidth
            alignment == Alignment.CENTER -> drawLeft + (drawWidth - displayTextWidth) / 2
            else -> drawLeft
        }

        for (cell in lineInfo.cells) {
            val cellTextWidth = textRenderer.width(Component.literal(cell.text))
            val columnWidth = cell.columnWidth.takeIf { it > 0 } ?: cellTextWidth
            val columnStart = cellOriginX + cell.xOffset
            val columnEnd = columnStart + columnWidth
            val isFirstColumnShifted = cell.xOffset == firstColumnXOffset && firstColumnDrawLeftOverride != null

            val bounds = resolveCellDrawBounds(cell, columnStart, columnEnd) ?: continue
            val (drawLeft, drawRight) = bounds
            val drawWidth = drawRight - drawLeft

            val displayText = getCellDisplayText(cell, drawLeft, columnStart, isFirstColumnShifted, drawWidth)
                ?: continue
            val displayTextWidth = textRenderer.width(Component.literal(displayText))
            val cellX = getCellDrawX(drawLeft, drawRight, drawWidth, displayTextWidth, isFirstColumnShifted)

            add(PreparedText(cellX, Component.literal(displayText)))
        }
    }

    private fun drawStringCompat(drawContext: GuiGraphics, textRenderer: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean) {
        drawContext.text(textRenderer, text, x, y, color, shadow)
    }

    /**
     * Trims the ending of the text to fit the maximum width, preserving formatting codes.
     * Useful for trimming end of a cell/line to draw inline buttons on the very left.
     */
    private fun trimTextEndingToMaxWidth(textRenderer: Font, text: String, maxWidth: Int): String {
        if (maxWidth <= 0 || text.isEmpty()) return ""
        if (textRenderer.width(Component.literal(text)) <= maxWidth) return text

        val result = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '§' && i + 1 < text.length) { // formatting code
                result.append(ch)
                result.append(text[i + 1])
                i += 2
                continue
            }

            val next = result.toString() + ch
            if (textRenderer.width(Component.literal(next)) > maxWidth) break
            result.append(ch)
            i++
        }

        return result.toString()
    }

    /** 
     * Trims the leading characters of the text to skip the given width, preserving formatting codes.
     * Useful for trimming start of a cell/line to draw inline buttons on the very right.
     */
    private fun trimTextLeading(textRenderer: Font, text: String, skipWidth: Int): String {
        if (skipWidth <= 0 || text.isEmpty()) return text

        var index = 0
        var removedWidth = 0
        while (index < text.length && removedWidth < skipWidth) {
            val nextIndex = if (text[index] == '§' && index + 1 < text.length) index + 2 else index + 1
            removedWidth = textRenderer.width(Component.literal(text.substring(0, nextIndex)))
            index = nextIndex
        }

        return text.substring(index)
    }

    private data class OverlayBackgroundCoords(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Gets the scaled coordinates of the rectangle around overlay text, with some padding.
     */
    private fun getOverlayBackgroundCoords(scaledLeftEdge: Int, scaledY: Int, maxWidth: Int, height: Int): OverlayBackgroundCoords {
        return OverlayBackgroundCoords(
            scaledLeftEdge - 4,
            scaledY - 4,
            scaledLeftEdge + maxWidth + 4,
            scaledY + height + 2,
        )
    }

    private data class OverlayScreenCoords(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun toScreenCoords(backgroundCoords: OverlayBackgroundCoords): OverlayScreenCoords {
        return OverlayScreenCoords(
            (backgroundCoords.left * scale).roundToInt(),
            (backgroundCoords.top * scale).roundToInt(),
            (backgroundCoords.right * scale).roundToInt(),
            (backgroundCoords.bottom * scale).roundToInt(),
        )
    }

    private fun drawOverlayBackground(drawContext: GuiGraphics, screenCoords: OverlayScreenCoords) {
        if (!OverlayStyle.BACKGROUND) return


        drawContext.fillGradient(
            screenCoords.left,
            screenCoords.top,
            screenCoords.right,
            screenCoords.bottom,
            OverlayStyle.BACKGROUND_COLOR_TOP,
            OverlayStyle.BACKGROUND_COLOR_BOTTOM
        )
    }

    private fun drawOverlayBorder(drawContext: GuiGraphics, screenCoords: OverlayScreenCoords) {
        if (!OverlayStyle.BORDER) return

        val borderColor = OverlayStyle.BORDER_COLOR
        val borderWidth = OverlayStyle.BORDER_WIDTH.coerceIn(1..5)
        val left = screenCoords.left
        val top = screenCoords.top
        val right = screenCoords.right
        val bottom = screenCoords.bottom

        // Draw border borderWidth pixels outside the overlay, so it does not overlap the background.
        // Also made lines not overlap in the corners.
        drawContext.fill(left - borderWidth, top - borderWidth, right + borderWidth, top, borderColor) // top
        drawContext.fill(left - borderWidth, bottom, right + borderWidth, bottom + borderWidth, borderColor) // bottom
        drawContext.fill(left - borderWidth, top, left, bottom, borderColor) // left
        drawContext.fill(right, top, right + borderWidth, bottom, borderColor) // right
    }

    private fun inlineActionsLeft(
        alignment: Alignment,
        left: Int,
        width: Int,
        line: LineInfo?,
        metrics: HoveredLineMetrics,
    ): Int {
        val blockWidth = when (alignment) {
            Alignment.LEFT, Alignment.RIGHT -> metrics.buttonsWidth
            Alignment.CENTER -> if (metrics.usesCells) line?.tableWidth ?: metrics.clippedLineWidth
            else metrics.reservedWidth + minOf(line?.tableWidth ?: metrics.clippedLineWidth, metrics.lineAvailableWidth)
        }
        return alignedRowLeft(alignment, left, width, blockWidth)
    }

    private fun getClickedLineAction(frame: RenderedOverlay, line: LineInfo, mouseX: Double): LineAction? {
        val hitbox = frame.hitbox
        val metrics = getHoveredLineMetrics(frame.font, line, line.text, hitbox.width, line.actions)
        var start = inlineActionsLeft(hitbox.alignment, hitbox.left, hitbox.width, line, metrics)
        for (action in line.actions) {
            val width = frame.font.width(Component.literal(action.text))
            if (hitbox.containsX(mouseX, start, width)) return action
            start += width
        }
        return null
    }

    /**
     * Draws the sample overlay from sampleLines, for move GUIs screen.
     */
    fun drawSample(drawContext: GuiGraphics, textRenderer: Font, mcClient: Minecraft) {
        if (sampleLines.isEmpty()) return
        if (!WorldUtils.isInSkyblock()) return
        if (mcClient.getScreenCompat() !is MoveGuisScreen) return

        drawContext.pose().pushMatrix()
        drawContext.pose().scale(scale, scale)

        val scaledY = (y / scale).toInt()
        
        val maxWidth = getMaxSampleWidth(textRenderer)
        val height = (sampleLines.size * (textRenderer.lineHeight + 2))
        
        // Convert x (which represents different points based on alignment) to left edge
        val leftEdge = getLeftSampleEdge(textRenderer)
        val scaledLeftEdge = (leftEdge / scale).toInt()

        val backgroundCoords = getOverlayBackgroundCoords(scaledLeftEdge, scaledY, maxWidth, height)
        drawContext.fill(
            backgroundCoords.left,
            backgroundCoords.top,
            backgroundCoords.right,
            backgroundCoords.bottom,
            Color(0, 0, 0, 80).rgb
        )

        var currentY = scaledY
        for (line in sampleLines) {
            val text = Component.literal(line)
            val textWidth = textRenderer.width(text)
            
            // Text is aligned within the overlay block based on alignment
            val actualX = when (alignment) {
                Alignment.LEFT -> scaledLeftEdge
                Alignment.RIGHT -> scaledLeftEdge + maxWidth - textWidth
                Alignment.CENTER -> scaledLeftEdge + (maxWidth - textWidth) / 2
            }

            drawStringCompat(drawContext, textRenderer, text, actualX, currentY, color, true)
            currentY += textRenderer.lineHeight + 2
        }

        drawContext.pose().popMatrix()

        val alignmentLabel = when (alignment) {
            Alignment.LEFT -> "[L]"
            Alignment.RIGHT -> "[R]"
            Alignment.CENTER -> "[C]"
        }
        val labelText = Component.literal("${GRAY}$alignmentLabel")
        val labelX = leftEdge - 2
        val labelY = y - 2 - textRenderer.lineHeight
        drawStringCompat(drawContext, textRenderer, labelText, labelX, labelY, color, true)
    }

    /**
     * Checks if the mouse is over the sample overlay.
     */
    fun isInSample(textRenderer: Font, @Suppress("UNUSED_PARAMETER") client: Minecraft, mouseX: Double, mouseY: Double): Boolean {
        if (sampleLines.isEmpty()) return false

        val maxWidth = getMaxSampleWidth(textRenderer) * scale
        val height = (sampleLines.size * (textRenderer.lineHeight + 2)) * scale
        
        // Convert x to left edge for hitbox calculation
        val leftEdge = getLeftSampleEdge(textRenderer).toFloat()
            
        if (mouseX >= leftEdge - 4 &&
            mouseX <= leftEdge + maxWidth + 4 &&
            mouseY >= y - 4 &&
            mouseY <= y + height + 2) {        
            return true
        }
        return false
    }

    /**
     * Converts x coordinate (which represents different points based on alignment) to actual left edge.
     * Uses scaled width for CENTER/RIGHT so that the reference point stays correct after matrices.scale(scale).
     */
    private fun getLeftSampleEdge(textRenderer: Font): Int {
        val maxWidth = getMaxSampleWidth(textRenderer)
        val renderedWidth = (maxWidth * scale).toInt()
        return when (alignment) {
            Alignment.LEFT -> x
            Alignment.CENTER -> x - renderedWidth / 2
            Alignment.RIGHT -> x - renderedWidth
        }
    }
 
    /**
     * Gets the maximum width of the sample overlay.
     */
    private fun getMaxSampleWidth(textRenderer: Font): Int {
        val linesToUse = when {
            sampleLines.isNotEmpty() -> sampleLines
            else -> emptyList()
        }
        return linesToUse.maxOfOrNull { textRenderer.width(Component.literal(it)) } ?: 0
    }
}
