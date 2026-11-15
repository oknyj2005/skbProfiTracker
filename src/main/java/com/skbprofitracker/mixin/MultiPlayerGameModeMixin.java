package com.skbprofitracker.mixin;

import com.skbprofitracker.events.EventBus;
import com.skbprofitracker.events.models.InteractActionType;
import com.skbprofitracker.events.models.PlayerInteractEvent;
import com.skbprofitracker.tracker.ActivityBucket;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "useItem", at = @At("HEAD"))
    private void skbprofitracker$onInteractItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND) return;
        EventBus.INSTANCE.publish(new PlayerInteractEvent(InteractActionType.USE_ITEM, true));
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void skbprofitracker$onInteractBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND) return;
        EventBus.INSTANCE.publish(new PlayerInteractEvent(InteractActionType.USE_BLOCK, true));
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void skbprofitracker$onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ActivityBucket.INSTANCE.onStrongSignal();
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void skbprofitracker$onAttack(Player player, Entity target, CallbackInfo ci) {
        ActivityBucket.INSTANCE.onStrongSignal();
    }
}
