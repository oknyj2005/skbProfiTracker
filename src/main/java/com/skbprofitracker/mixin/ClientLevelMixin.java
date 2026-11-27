package com.skbprofitracker.mixin;

import com.skbprofitracker.events.publishers.EssenceWatcher;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void skbprofitracker$onServerBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        EssenceWatcher.INSTANCE.observeFossilBlockUpdate(pos, state);
    }
}
