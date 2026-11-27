package com.skbprofitracker.mixin;

import com.skbprofitracker.events.EventBus;
import com.skbprofitracker.events.models.InteractActionType;
import com.skbprofitracker.events.models.PlayerInteractEvent;
import com.skbprofitracker.events.publishers.EssenceWatcher;
import com.skbprofitracker.tracker.ActivityBucket;
import com.skbprofitracker.utils.Debug;
import net.minecraft.client.Minecraft;
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
        EventBus.INSTANCE.publish(new PlayerInteractEvent(InteractActionType.USE_ITEM, hand == InteractionHand.MAIN_HAND));
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void skbprofitracker$onInteractBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        EventBus.INSTANCE.publish(new PlayerInteractEvent(InteractActionType.USE_BLOCK, hand == InteractionHand.MAIN_HAND));
    }

    @Inject(method = "startDestroyBlock", at = @At("RETURN"))
    private void skbprofitracker$onStartDestroyBlock(BlockPos pos, net.minecraft.core.Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            ActivityBucket.INSTANCE.onMiningContact(pos.asLong());
        }
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void skbprofitracker$observeFossilTarget(BlockPos pos, net.minecraft.core.Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (Debug.INSTANCE.getVerbose() && Minecraft.getInstance().level != null) {
            EssenceWatcher.INSTANCE.observeFossilTarget(pos, Minecraft.getInstance().level.getBlockState(pos));
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("RETURN"))
    private void skbprofitracker$onContinueDestroyBlock(BlockPos pos, net.minecraft.core.Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            ActivityBucket.INSTANCE.onMiningContact(pos.asLong());
        }
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void skbprofitracker$onAttack(Player player, Entity target, CallbackInfo ci) {
        ActivityBucket.INSTANCE.onAttack();
    }
}
