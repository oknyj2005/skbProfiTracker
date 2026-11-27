package com.skbprofitracker.mixin;

import com.skbprofitracker.events.publishers.InventoryItemPickupPublisher;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerMixin {
    @Inject(method = "send", at = @At("HEAD"))
    private void skbprofitracker$observeManualInventoryMove(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundPlayerActionPacket actionPacket) {
            switch (actionPacket.getAction()) {
                case DROP_ITEM, DROP_ALL_ITEMS -> InventoryItemPickupPublisher.INSTANCE.onHandStackMoved(false);
                case SWAP_ITEM_WITH_OFFHAND -> InventoryItemPickupPublisher.INSTANCE.onHandStackMoved(true);
                default -> { }
            }
        } else if (packet instanceof ServerboundContainerClickPacket click
                && InventoryItemPickupPublisher.INSTANCE.hasPendingConsumableUses()) {
            int swapSlot = click.containerInput() == ContainerInput.SWAP ? click.buttonNum() : -1;
            InventoryItemPickupPublisher.INSTANCE.onContainerClick(
                    click.containerId(), click.slotNum(), click.changedSlots().keySet().toIntArray(), swapSlot);
        }
    }
}
