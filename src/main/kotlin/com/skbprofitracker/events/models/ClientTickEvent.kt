package com.skbprofitracker.events.models

import com.skbprofitracker.utils.TabListAndScoreboardUtils
import net.minecraft.client.Minecraft

data class ClientTickEvent(val mc: Minecraft) {
    private var sidebarSnapshot: List<String>? = null

    /** All subscribers run on the client thread and see the same sidebar for this tick. */
    val scoreboardLines: List<String>
        get() = sidebarSnapshot ?: TabListAndScoreboardUtils.getUnformattedScoreboardLines(mc).also {
            sidebarSnapshot = it
        }
}
