package com.skbprofitracker

import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.publishers.InventoryItemPickupPublisher
import com.skbprofitracker.events.publishers.PetLevelUpPublisher
import com.skbprofitracker.events.publishers.CorpseLootPublisher
import com.skbprofitracker.events.publishers.NucleusLootPublisher
import com.skbprofitracker.events.publishers.PurseWatcher
import com.skbprofitracker.events.publishers.SacksItemPickupPublisher
import com.skbprofitracker.items.ItemTable
import com.skbprofitracker.price.PriceCache
import com.skbprofitracker.features.commands.SkbProfitCommand
import com.skbprofitracker.tracker.ActivityBucket
import com.skbprofitracker.tracker.BucketLedger
import com.skbprofitracker.tracker.PetTracker
import com.skbprofitracker.tracker.ProfitOverlay
import com.skbprofitracker.utils.ChatUtils
import com.skbprofitracker.utils.ColorUtils
import com.skbprofitracker.utils.GuiUtils
import com.skbprofitracker.utils.PlayerUtils
import com.skbprofitracker.utils.WorldUtils
import com.skbprofitracker.utils.data.PersistentDataManager
import com.skbprofitracker.utils.gui.MoveGuis
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

class SkbMod : ClientModInitializer {
    companion object {
        internal const val MOD_ID = "skbprofitracker"
        internal const val MOD_NAME = "Skb"

        internal val LOGGER = LoggerFactory.getLogger(MOD_ID)

        lateinit var version: String

        @JvmField
        val mc: Minecraft = Minecraft.getInstance()

        @JvmField
        val mcVersion: String = SharedConstants.getCurrentVersion().id()

        @JvmStatic
        lateinit var INSTANCE: SkbMod
            private set
    }

    init {
        INSTANCE = this
    }

    override fun onInitializeClient() {
        version = getModVersion()
        LOGGER.info("Loading $MOD_NAME v$version for $mcVersion...")

        PersistentDataManager.init()
        EventBus.init()

        // Data
        ItemTable.init()
        PriceCache.init()

        // Trackers
        BucketLedger.init()
        ActivityBucket.init()
        PetTracker.init()
        ProfitOverlay.init()
        SkbProfitCommand.init()

        // Utils
        WorldUtils.init()
        PlayerUtils.init()
        ChatUtils.init()
        ColorUtils.init()
        GuiUtils.init()

        // Event publishers
        PetLevelUpPublisher.init()
        PurseWatcher.init()
        CorpseLootPublisher.init()
        NucleusLootPublisher.init()
        SacksItemPickupPublisher.init()
        InventoryItemPickupPublisher.init()

        MoveGuis.init()

        LOGGER.info("$MOD_NAME loaded successfully!")
    }

    private fun getModVersion(): String {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("unspecified")
    }
}
