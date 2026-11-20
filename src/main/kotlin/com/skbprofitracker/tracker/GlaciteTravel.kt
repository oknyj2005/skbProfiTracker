package com.skbprofitracker.tracker

internal object GlaciteTravel {
    private const val SECOND_MS = 1_000L

    enum class Region { DWARVEN_MINES, MINESHAFT }

    enum class Arrival { WAITING, CONFIRMED, CANCELLED }

    fun region(world: String): Region? = when {
        world.equals("Dwarven Mines", ignoreCase = true) -> Region.DWARVEN_MINES
        world.contains("mineshaft", ignoreCase = true) -> Region.MINESHAFT
        else -> null
    }

    fun includes(world: String, zone: String): Boolean = when (region(world)) {
        Region.DWARVEN_MINES -> zone.equals("Dwarven Base Camp", ignoreCase = true) ||
            zone.equals("Glacite Tunnels", ignoreCase = true)
        Region.MINESHAFT -> true
        null -> false
    }

    data class Transfer(
        val origin: Region,
        val departedAtMs: Long,
        val countedThroughMs: Long,
        val signalAtMs: Long,
        val signalSource: String,
        private val graceMs: Long,
    ) {
        fun arrival(world: String, now: Long): Arrival {
            if (now - departedAtMs > graceMs) return Arrival.CANCELLED
            val destination = region(world)
            return when {
                world.isBlank() || destination == origin -> Arrival.WAITING
                destination != null -> Arrival.CONFIRMED
                else -> Arrival.CANCELLED
            }
        }

        fun secondsToCredit(now: Long): Long =
            (minOf(now, signalAtMs + graceMs) - countedThroughMs).coerceAtLeast(0L) / SECOND_MS
    }
}
