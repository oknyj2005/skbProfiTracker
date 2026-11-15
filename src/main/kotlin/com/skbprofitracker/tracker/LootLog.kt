package com.skbprofitracker.tracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last few loot lines the mod read from chat, kept in memory so a corpse or nucleus run can
 * be checked afterwards with `/skbprofit loot` instead of having to watch the overlay live.
 *
 * Unresolved names are kept as well (with a null key), because a missing entry in the item table
 * is the most likely reason for a slice to come out short.
 */
object LootLog {
    data class Entry(
        val at: String,
        val slice: String,
        val source: String,
        val name: String,
        val key: String?,
        val amount: Long,
    )

    private const val MAX_ENTRIES = 200
    private val format = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val entries = ArrayDeque<Entry>()

    fun add(slice: String, source: String, name: String, key: String?, amount: Long) {
        entries.addLast(Entry(format.format(Date()), slice, source, name, key, amount))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    fun recent(count: Int): List<Entry> = entries.toList().takeLast(count)

    fun size(): Int = entries.size

    fun clear() = entries.clear()
}
