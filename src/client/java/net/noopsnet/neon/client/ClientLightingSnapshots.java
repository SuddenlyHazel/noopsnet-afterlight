// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.noopsnet.neon.lighting.FogOcclusion;
import net.noopsnet.neon.lighting.SnapshotReusePolicy;
import net.noopsnet.neon.lighting.WorldSnapshotCache;

/** Cached inputs only. RGB propagation and time-dependent mist still run independently. */
public final class ClientLightingSnapshots {
    /** Added by the corresponding mixins; absent hooks force uncached reads. */
    public interface TrackedChunk {}

    public interface TrackedSource {}

    public record Fog(byte[] solid, float[] environment) {}

    private static final WorldSnapshotCache<byte[]> RGB = new WorldSnapshotCache<>();
    private static final WorldSnapshotCache<Fog> FOG = new WorldSnapshotCache<>();
    private static final byte[] EMPTY_RGB = new byte[64 * 64 * 64];

    private ClientLightingSnapshots() {}

    public static java.util.Map<String, WorldSnapshotCache.Stats> diagnostics() {
        return java.util.Map.of("rgb", RGB.stats(), "fog", FOG.stats());
    }

    public static byte[] emptyRgb() {
        return EMPTY_RGB;
    }

    public static void clear() {
        RGB.clear();
        FOG.clear();
    }

    public static void blockChanged(ClientLevel level, BlockPos pos) {
        RGB.invalidateCell(level, pos.getX(), pos.getY(), pos.getZ());
        FOG.invalidateCell(level, pos.getX(), pos.getY(), pos.getZ());
    }

    public static void lightChanged(ClientLevel level, int x, int y, int z) {
        FOG.invalidateSection(level, x, y, z);
    }

    public static void chunkChanged(ClientLevel level, int x, int z) {
        RGB.invalidateColumn(level, x, z);
        FOG.invalidateColumn(level, x, z);
    }

    public static void viewChanged(ClientLevel level) {
        RGB.invalidateWorld(level);
        FOG.invalidateWorld(level);
    }

    private static boolean hooksPresent(ClientLevel level) {
        return level.getChunkSource() instanceof TrackedSource
                && TrackedChunk.class.isAssignableFrom(LevelChunk.class);
    }

    public static byte[] opacity(ClientLevel level, int size, int ox, int oy, int oz) {
        return RGB.get(
                level,
                size,
                ox,
                oy,
                oz,
                () -> {
                    byte[] opacity = new byte[size * size * size];
                    boolean reusable = hooksPresent(level);
                    var pos = new BlockPos.MutableBlockPos();
                    for (int z = 0, i = 0; z < size; z++)
                        for (int y = 0; y < size; y++)
                            for (int x = 0; x < size; x++, i++) {
                                pos.set(ox + x, oy + y, oz + z);
                                if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos)) {
                                    opacity[i] = 15;
                                    continue;
                                }
                                var state = level.getBlockState(pos);
                                // Match vanilla's static-shape cache contract. Context-sensitive
                                // shapes retain the old every-eligible-tick scan behavior.
                                if (!SnapshotReusePolicy.reusable(state)) reusable = false;
                                opacity[i] = (byte) state.getLightBlock(level, pos);
                            }
                    return new WorldSnapshotCache.Built<>(opacity, reusable);
                });
    }

    public static Fog fog(ClientLevel level, int size, int ox, int oy, int oz) {
        return FOG.get(
                level,
                size,
                ox,
                oy,
                oz,
                () -> {
                    byte[] solid = new byte[size * size * size];
                    float[] environment = new float[solid.length * 2];
                    boolean reusable = hooksPresent(level);
                    var pos = new BlockPos.MutableBlockPos();
                    for (int z = 0, i = 0; z < size; z++)
                        for (int y = 0; y < size; y++)
                            for (int x = 0; x < size; x++, i++) {
                                pos.set(ox + x, oy + y, oz + z);
                                if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos)) {
                                    solid[i] = 1;
                                    continue;
                                }
                                var state = level.getBlockState(pos);
                                if (!SnapshotReusePolicy.reusable(state)) reusable = false;
                                if (FogOcclusion.blocks(state, level, pos)) {
                                    solid[i] = 1;
                                    continue;
                                }
                                environment[i * 2] =
                                        level.getBrightness(LightLayer.BLOCK, pos) / 15f;
                                environment[i * 2 + 1] =
                                        level.getBrightness(LightLayer.SKY, pos) / 15f;
                            }
                    return new WorldSnapshotCache.Built<>(new Fog(solid, environment), reusable);
                });
    }
}
