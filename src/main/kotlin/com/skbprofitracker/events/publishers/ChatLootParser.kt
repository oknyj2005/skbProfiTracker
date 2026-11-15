package com.skbprofitracker.events.publishers

/**
 * Shared parsing for the loot blocks the server prints in chat.
 *
 * Both the frozen corpse and the Crystal Nucleus print their rewards as a small table:
 * a header line, then one item per line indented by four spaces, then a line of 64 `▬`
 * characters. Those lines carry the exact item and amount, which is far more reliable than
 * guessing from the inventory diff afterwards, so both slices are booked from here.
 */
object ChatLootParser {
    private const val BLOCK_CHAR = '▬'
    private const val MIN_BLOCK_LENGTH = 60

    /** `Fine Onyx Gemstone x2` -> ("Fine Onyx Gemstone", 2). */
    private val AMOUNT = Regex("^(.*?)\\s+x([\\d,]+)$")

    fun isBlockEnd(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length >= MIN_BLOCK_LENGTH && trimmed.all { it == BLOCK_CHAR }
    }

    /**
     * Reads one indented reward line. The item name is stripped of the leading rarity glyph
     * (`☠`, `✦`, ...) and of the trailing `xN`, so it can be looked up in the item table the
     * same way an inventory hover name is.
     */
    fun parseItemLine(text: String): Pair<String, Long>? {
        var body = text.trim()
        if (body.isEmpty()) return null

        body = body.trimStart { !it.isLetterOrDigit() && it != '(' }
        if (body.isEmpty()) return null

        val amountMatch = AMOUNT.matchEntire(body)
        val name: String
        val amount: Long
        if (amountMatch != null) {
            name = amountMatch.groupValues[1].trim()
            amount = amountMatch.groupValues[2].replace(",", "").toLongOrNull() ?: 1L
        } else {
            name = body
            amount = 1L
        }

        return if (name.isEmpty() || amount <= 0) null else name to amount
    }

    /** Reward rows are indented by four spaces; the padding must survive the chat pipeline. */
    fun isRewardLine(text: String): Boolean = text.length - text.trimStart().length >= INDENT

    const val INDENT = 4
}
