package com.skbprofitracker.features.overlays.base

import com.skbprofitracker.SkbMod
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.ChatUtils
import com.skbprofitracker.utils.RegisterUtils
import com.skbprofitracker.utils.enums.ColorCodes.*
import com.skbprofitracker.utils.enums.FormattingCodes.*
import com.skbprofitracker.utils.gui.GuiButton

/** 
 * Interface for trackers (without view modes) that can be reset via various methods.
 */
interface IResettableTracker : ITracker {
    val resetCommand: String

    fun hasData(): Boolean
    fun resetData(force: Boolean = false)
    
    fun refreshGui()

    fun bulkReset() {
        resetData()
        refreshGui()
    }

    fun resetOnGameClosed() {
        if (!hasData()) return
        resetData(force = true)
        SkbMod.LOGGER.info("[Skb] Automatically reset $trackerName on game closed.")
    }

    fun requestReset(isConfirmed: Boolean = false, needsChatFeedback: Boolean = true) {
        resetWithConfirmation(isConfirmed, needsChatFeedback)
    }

    /**
     * Returns a GUI button for resetting the given tracker.
     * @param onClick The action to perform when the button is clicked.
     * @return The GUI button.
     */
    fun getResetGuiButton(buttonIndex: Int = 0, onClick: () -> Unit): GuiButton {
        return GuiButton(buttonIndex, "${GRAY}[${RED}Click to reset${GRAY}]", onClick)
    }

    /**
     * Registers a reset command (/skbprofitrackerReset<TrackerName>) for the given tracker.
     * @param tracker The tracker to register the reset command for.
     */
    fun registerResetCommand() {
        RegisterUtils.command(resetCommand) { args ->
            val isConfirmed = args.isNotEmpty() && args[0] == "noconfirm"
            resetWithConfirmation(isConfirmed)
        }
    }

    /**
     * Resets the given tracker with confirmation.
     * @param isConfirmed Whether to confirm the reset.
     * @param needsChatFeedback Whether to send a chat message when the reset is successful.
     */
    private fun resetWithConfirmation(isConfirmed: Boolean = false, needsChatFeedback: Boolean = true) {
        CommonUtils.runWithCatching("Failed to reset ${trackerName}") {
            if (!isConfirmed) {
                ChatUtils.sendLocalChat("${WHITE}Do you want to reset ${trackerName}?", true)
                ChatUtils.sendLocalChatWithCommand(
                    "${RED}${BOLD}[Click to confirm]",
                    "${resetCommand} noconfirm"
                )
                return
            }

            resetData()
            refreshGui()
            if (needsChatFeedback) ChatUtils.sendLocalChat("${WHITE}${trackerName} was reset.", true)
        }
    }
}
