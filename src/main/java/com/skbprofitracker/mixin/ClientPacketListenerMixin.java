package com.skbprofitracker.mixin;

import com.skbprofitracker.events.publishers.InventoryItemPickupPublisher;
import com.skbprofitracker.events.publishers.PurseWatcher;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleSystemChat", at = @At("RETURN"))
    private void skbprofitracker$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (!packet.overlay()) PurseWatcher.INSTANCE.onServerChat(packet.content().getString());
    }

    // At RETURN the packet handler has already moved execution to the client thread.
    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void skbprofitracker$onContainerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        InventoryItemPickupPublisher.INSTANCE.onPlayerMenuSlot(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("RETURN"))
    private void skbprofitracker$onInventorySlot(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        InventoryItemPickupPublisher.INSTANCE.onPlayerInventorySlot(packet.slot(), packet.contents());
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void skbprofitracker$onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        InventoryItemPickupPublisher.INSTANCE.onPlayerMenuContents(packet.containerId(), packet.items());
    }
}
