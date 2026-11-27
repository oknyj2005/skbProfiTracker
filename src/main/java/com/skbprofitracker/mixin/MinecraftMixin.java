package com.skbprofitracker.mixin;

import com.skbprofitracker.events.EventBus;
import com.skbprofitracker.events.models.GuiOpenedEvent;
import com.skbprofitracker.events.models.WorldUnloadEvent;
import com.skbprofitracker.events.publishers.InventoryItemPickupPublisher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    private ClientLevel level;

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void skbprofitracker$onSackAttack(CallbackInfoReturnable<Boolean> cir) {
        InventoryItemPickupPublisher.INSTANCE.onHandSackUse();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"))
    private void skbprofitracker$onSackUse(CallbackInfo ci) {
        InventoryItemPickupPublisher.INSTANCE.onHandSackUse();
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void skbprofitracker$onWorldUnload(ClientLevel newWorld, CallbackInfo ci) {
        if (this.level != null) {
            EventBus.INSTANCE.publish(new WorldUnloadEvent());
        }
    }

    @Inject(method = "setScreenAndShow", at = @At("HEAD"))
    private void skbprofitracker$onScreenOpened(Screen screen, CallbackInfo ci) {
        if (screen != null) {
            EventBus.INSTANCE.publish(new GuiOpenedEvent(screen));
        }
    }
}
