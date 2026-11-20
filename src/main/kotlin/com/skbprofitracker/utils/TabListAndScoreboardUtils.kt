package com.skbprofitracker.utils

import com.skbprofitracker.SkbMod
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

object TabListAndScoreboardUtils {
    
    fun getUnformattedLines(): List<String> {
        val playerList = getPlayerList()

        return playerList.map { entry ->
            val displayName = entry.tabListDisplayName ?: entry.profile.name.let { Component.literal(it) }
            return@map displayName.getUnformattedString()
        }
    }

    /*
     * Get the TabList line after the specified startsWith string. No formatting preserved.
     * E.g. getLineAfter("Area:") will return the trimmed text after the "Area:" string.
     * @param startsWith The unformatted string to search for.
     * @returns {String} The line after the specified startsWith string.
     */
    fun getLineAfter(startsWith: String): String {
        val playerList = getPlayerList()
        if (playerList.isEmpty()) return ""
        
        for (entry in playerList) {
            val displayName = entry.tabListDisplayName ?: entry.profile.name.let { Component.literal(it) }
            val text = displayName.getUnformattedString()
            
            if (text.contains(startsWith)) {
                val entryIndex = text.indexOf(startsWith)
                if (entryIndex != -1) {
                    val value = text.substring(entryIndex + startsWith.length).trim()
                    return value.ifEmpty { "" }
                }
            }
        }
        
        return ""
    }

    fun getUnformattedScoreboardLines(client: Minecraft = SkbMod.mc): List<String> {
        val scoreboard = client.level?.scoreboard ?: return emptyList()
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()

        return buildList {
            for (entry in scoreboard.listPlayerScores(objective)) {
                if (entry.isHidden) continue
                val team = scoreboard.getPlayersTeam(entry.owner)
                add(PlayerTeam.formatNameForTeam(team, entry.ownerName()).getUnformattedString())
            }
        }
    }

    private fun getPlayerList(): Collection<PlayerInfo> {
        val networkHandler = SkbMod.mc.connection ?: return emptyList()
        return networkHandler.listedOnlinePlayers
    }
}
