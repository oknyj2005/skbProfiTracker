package com.skbprofitracker.capture

import com.skbprofitracker.events.publishers.ChatLootParser

/** Reads the item rows following a Fossil Excavator completion message. */
internal class ExcavationRewardBlock {
    private var resultAt: Long? = null
    private var blockOpen = false
    private var readingRewards = false

    fun accept(text: String, now: Long): Pair<String, Long>? {
        val line = text.trim()
        if (line == "EXCAVATION COMPLETE") {
            resultAt = now
            blockOpen = true
            readingRewards = false
            return null
        }
        if (!blockOpen || !recentResult(now)) return null
        when {
            line == "REWARDS" -> readingRewards = true
            ChatLootParser.isBlockEnd(text) -> blockOpen = false
            readingRewards && ChatLootParser.isRewardLine(text) -> return ChatLootParser.parseItemLine(text)
        }
        return null
    }

    fun recentResult(now: Long): Boolean = resultAt?.let { now - it in 0..RESULT_WINDOW_MS } == true

    fun clear() {
        resultAt = null
        blockOpen = false
        readingRewards = false
    }

    companion object {
        const val RESULT_WINDOW_MS = 3_500L

        fun isExcavator(title: String?): Boolean = title?.trim() == "Fossil Excavator"
    }
}
