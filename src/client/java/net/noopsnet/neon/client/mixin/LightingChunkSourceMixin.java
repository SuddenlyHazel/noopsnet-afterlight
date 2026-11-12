// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.noopsnet.neon.client.ClientLightingSnapshots;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
public abstract class LightingChunkSourceMixin implements ClientLightingSnapshots.TrackedSource {
    @Shadow @Final private ClientLevel level;

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void afterlight$chunkData(
            int x,
            int z,
            FriendlyByteBuf bytes,
            CompoundTag tag,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
            CallbackInfoReturnable<LevelChunk> callback) {
        ClientLightingSnapshots.chunkChanged(level, x, z);
    }

    @Inject(method = "drop", at = @At("RETURN"))
    private void afterlight$drop(ChunkPos pos, CallbackInfo callback) {
        ClientLightingSnapshots.chunkChanged(level, pos.x, pos.z);
    }

    @Inject(method = "onLightUpdate", at = @At("RETURN"))
    private void afterlight$light(LightLayer layer, SectionPos section, CallbackInfo callback) {
        ClientLightingSnapshots.lightChanged(level, section.x(), section.y(), section.z());
    }

    @Inject(method = "updateViewCenter", at = @At("RETURN"))
    private void afterlight$center(int x, int z, CallbackInfo callback) {
        ClientLightingSnapshots.viewChanged(level);
    }

    @Inject(method = "updateViewRadius", at = @At("RETURN"))
    private void afterlight$radius(int radius, CallbackInfo callback) {
        ClientLightingSnapshots.viewChanged(level);
    }
}
