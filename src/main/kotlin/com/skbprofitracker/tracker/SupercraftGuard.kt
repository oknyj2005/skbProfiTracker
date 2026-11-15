package com.skbprofitracker.tracker

/**
 * Supercrafting turns stock the player already owns into higher tier items.
 *
 * Both halves of that trip look exactly like fresh loot: the crafted stack is grabbed back into
 * the inventory ("CLICK HERE to grab x1485 ... from sacks") and Hypixel may also report it in a
 * `[Sacks]` line. The ingredients were already credited when they were mined, so the crafted
 * output must be ignored or the tracker double counts a full sack of gems.
 *
 * The guard only remembers item names for a short window: a player who supercrafts Flawed
 * Peridot and then goes back to mining Peridot must start counting again right away.
 */
object SupercraftGuard {
    private const val PREFIX = "You Supercrafted "

    /** Grabbing the crafted stack out of the sacks happens within a couple of seconds. */
    private const val INVENTORY_WINDOW_MS = 10_000L

    /** The sack chat line can lag behind by up to ~30s (same as [SacksItemPickupPublisher]). */
    private const val SACK_WINDOW_MS = 35_000L

    private val craftedAt = mutableMapOf<String, Long>()

    fun onChat(text: String) {
        if (!text.startsWith(PREFIX)) return
        val name = normalize(text.removePrefix(PREFIX))
        if (name.isEmpty()) return
        val now = System.currentTimeMillis()
        craftedAt[name] = now
        craftedAt.entries.removeIf { now - it.value > SACK_WINDOW_MS }
    }

    fun guardsInventory(itemName: String): Boolean =
        isRecent(itemName, INVENTORY_WINDOW_MS)

    fun guardsSack(itemName: String): Boolean =
        isRecent(itemName, SACK_WINDOW_MS)

    private fun isRecent(itemName: String, windowMs: Long): Boolean {
        val normalized = normalize(itemName)
        if (normalized.isEmpty()) return false
        val at = craftedAt[normalized] ?: return false
        return System.currentTimeMillis() - at <= windowMs
    }

    /** `✦ Flawed Peridot Gemstone x1,485` -> `flawed peridot gemstone` */
    private fun normalize(raw: String): String =
        raw.substringBefore(" x")
            .filter { it.isLetterOrDigit() || it == ' ' || it == '\'' }
            .trim()
            .lowercase()
}
