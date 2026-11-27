package com.skbprofitracker.items

import com.skbprofitracker.utils.data.PersistentDataManager

/**
 * Remembers the rarity colour of every item key the player has ever picked up.
 *
 * Feesh ships a table where every display name already carries its rarity prefix; we
 * generate our table from NEU names, so the colour has to be captured from the hover
 * lore at pickup time and persisted, otherwise the overlay looks monochrome.
 */
object ItemColors {
    private const val DEFAULT = "§f"
    internal var revision = 0L
        private set

    fun remember(key: String, prefix: String?) {
        if (prefix.isNullOrBlank()) return

        val colors = PersistentDataManager.skbprofitrackerData.itemColors
        if (colors.put(key, prefix) != prefix) {
            revision++
            PersistentDataManager.saveSkbDataToFileAsync()
        }
    }

    fun get(key: String): String =
        ItemTable.getByKey(key)?.color
            ?: PersistentDataManager.skbprofitrackerData.itemColors[key]
            ?: DEFAULT
}
