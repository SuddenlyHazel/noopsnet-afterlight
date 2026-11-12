// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.noopsnet.neon.client.ClientLightingSnapshots;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LightingChunkMixin implements ClientLightingSnapshots.TrackedChunk {
    @Inject(
            method =
                    "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/"
                            + "minecraft/world/level/block/state/BlockState;Z)Lnet/"
                            + "minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void afterlight$blockChanged(
            BlockPos pos,
            BlockState state,
            boolean moving,
            CallbackInfoReturnable<BlockState> callback) {
        var chunk = (LevelChunk) (Object) this;
        if (callback.getReturnValue() != null && chunk.getLevel() instanceof ClientLevel level)
            ClientLightingSnapshots.blockChanged(level, pos);
    }
}
