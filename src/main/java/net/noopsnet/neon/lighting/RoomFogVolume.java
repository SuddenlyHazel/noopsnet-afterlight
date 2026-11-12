// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import java.util.Arrays;
import java.util.List;

/** Bounded air-space fill, not a fluid simulation. Pure worker-side code. */
public final class RoomFogVolume {
    public record Emitter(
            double x,
            double y,
            double z,
            float power,
            float spread,
            float ageSeconds,
            float directionX,
            float directionZ) {
        public Emitter(double x, double y, double z, float power, float spread, float ageSeconds) {
            this(x, y, z, power, spread, ageSeconds, 0, 0);
        }

        // Existing callers without a clock skip the advancing-front gate.
        public Emitter(double x, double y, double z, float power, float spread) {
            this(x, y, z, power, spread, Float.MAX_VALUE);
        }
    }

    private static final float FRONT_BLOCKS_PER_SECOND = 3.0f;

    public static RgbLightVolume.Result build(
            int size,
            int ox,
            int oy,
            int oz,
            byte[] solid,
            float[] environment,
            List<Emitter> emitters,
            RgbLightVolume.Result previous,
            float seconds) {
        int count = size * size * size;
        if (size < 2 || size > 64 || solid.length != count || environment.length != count * 2)
            throw new IllegalArgumentException("Invalid fog grid");
        float[] target = new float[count], emission = new float[count];
        int[] distance = new int[count], queue = new int[count];
        for (var source : emitters.stream().limit(8).toList()) {
            if (!Double.isFinite(source.x)
                    || !Double.isFinite(source.y)
                    || !Double.isFinite(source.z)
                    || !Float.isFinite(source.power)
                    || !Float.isFinite(source.spread)
                    || !Float.isFinite(source.ageSeconds)
                    || !Float.isFinite(source.directionX)
                    || !Float.isFinite(source.directionZ)
                    || source.ageSeconds < 0
                    || source.power <= 0) continue;
            int sx = (int) Math.floor(source.x) - ox,
                    sy = (int) Math.floor(source.y) - oy,
                    sz = (int) Math.floor(source.z) - oz;
            if (sx < 0 || sy < 0 || sz < 0 || sx >= size || sy >= size || sz >= size) continue;
            int start = (sz * size + sy) * size + sx;
            if (solid[start] != 0) continue;
            int reach = 10 + Math.round(22 * Math.clamp(source.spread, 0, 1));
            int frontLimit =
                    Math.min(
                            reach,
                            (int) Math.ceil(source.ageSeconds * FRONT_BLOCKS_PER_SECOND + 2f));
            float power = Math.clamp(source.power, 0, 1);
            float spread = Math.clamp(source.spread, 0, 1);
            float directionLength = (float) Math.hypot(source.directionX, source.directionZ);
            float directionX = directionLength > .001f ? source.directionX / directionLength : 0;
            float directionZ = directionLength > .001f ? source.directionZ / directionLength : 0;
            Arrays.fill(distance, -1);
            int head = 0, tail = 0;
            queue[tail++] = start;
            distance[start] = 0;
            while (head < tail) {
                int index = queue[head++], d = distance[index];
                int x = index % size, y = index / size % size, z = index / (size * size);
                float falloff = (float) Math.exp(-3.0 * d * d / (reach * reach));
                float edge = Math.clamp((reach - d) / 4f, 0, 1);
                // Fill at the nozzle immediately, then advance along the actual
                // traversable voxel path. The two-cell smooth edge avoids a
                // block-shaped wave while preventing instant room-wide haze.
                float arrival =
                        Math.clamp(
                                (source.ageSeconds * FRONT_BLOCKS_PER_SECOND - d + 2f) / 2f, 0, 1);
                arrival *= arrival * (3f - 2f * arrival);
                float plume =
                        plume(
                                source,
                                (float) (ox + x + .5 - source.x),
                                (float) (oy + y + .5 - source.y),
                                (float) (oz + z + .5 - source.z),
                                directionX,
                                directionZ,
                                spread);
                // Continuous deposition builds room haze across frames AND separate bursts.
                // The fast nozzle shape remains distinct from the slower accumulating room field.
                emission[index] += power * .10f * falloff * edge * arrival;
                target[index] = Math.min(6.0f, target[index] + power * plume * edge * arrival);
                if (d >= frontLimit) continue;
                for (int axis = 0; axis < 6; axis++) {
                    if (axis == 0 && x == 0
                            || axis == 1 && x == size - 1
                            || axis == 2 && y == 0
                            || axis == 3 && y == size - 1
                            || axis == 4 && z == 0
                            || axis == 5 && z == size - 1) continue;
                    int next =
                            index
                                    + switch (axis) {
                                        case 0 -> -1;
                                        case 1 -> 1;
                                        case 2 -> -size;
                                        case 3 -> size;
                                        case 4 -> -size * size;
                                        default -> size * size;
                                    };
                    if (distance[next] >= 0 || solid[next] != 0) continue;
                    distance[next] = d + 1;
                    queue[tail++] = next;
                }
            }
        }
        float[] rgba = new float[count * 4];
        seconds = Float.isFinite(seconds) ? Math.clamp(seconds, 0, 1) : 0;
        float fill = (float) -Math.expm1(-seconds / .65);
        float activeKeep = (float) Math.exp(-seconds / 24.0),
                idleKeep = (float) Math.exp(-seconds / 9.0);
        for (int z = 0, i = 0; z < size; z++)
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++, i++) {
                    int o = i * 4;
                    if (solid[i] != 0) {
                        rgba[o + 2] = 1;
                        continue;
                    }
                    float old = 0;
                    if (previous != null) {
                        int px = ox + x - previous.x(),
                                py = oy + y - previous.y(),
                                pz = oz + z - previous.z(),
                                ps = previous.size();
                        if (px >= 0 && py >= 0 && pz >= 0 && px < ps && py < ps && pz < ps)
                            old = previous.rgba()[((pz * ps + py) * ps + px) * 4 + 3];
                    }
                    // Newly closed walls are cleared immediately; moving the camera preserves
                    // density at the same world cell rather than dragging fog with the view.
                    float accumulated =
                            emission[i] > 0
                                    ? old * activeKeep + emission[i] * 24 * (1 - activeKeep)
                                    : old * idleKeep;
                    float density =
                            Math.min(
                                    6.0f,
                                    accumulated + Math.max(0, target[i] - accumulated) * fill);
                    rgba[o] = Math.clamp(environment[i * 2], 0, 1);
                    rgba[o + 1] = Math.clamp(environment[i * 2 + 1], 0, 1);
                    rgba[o + 3] = density < .001f ? 0 : density;
                }
        return new RgbLightVolume.Result(size, ox, oy, oz, rgba);
    }

    /** Art-directed nozzle jet: a widening, gently rising plume with traveling puffs. */
    private static float plume(
            Emitter source,
            float x,
            float y,
            float z,
            float directionX,
            float directionZ,
            float spread) {
        if (directionX == 0 && directionZ == 0)
            return 5.5f * (float) Math.exp(-(x * x + y * y + z * z) / 3.0f);
        float along = x * directionX + z * directionZ;
        float length = 6 + 8 * spread;
        if (along < -.6f || along >= length) return 0;
        float forward = Math.max(0, along);
        float side = x * directionZ - z * directionX;
        float rise = y - forward * .08f;
        float radius = .8f + forward * (.12f + .18f * spread);
        float radial = (side * side + rise * rise) / (radius * radius);
        if (radial > 6) return 0;
        float pulse = .82f + .18f * (float) Math.sin(forward * 1.6f - source.ageSeconds * 2.5);
        float tip = Math.clamp((length - forward) / 2, 0, 1);
        float rear = Math.clamp((along + .6f) / .6f, 0, 1);
        return 5.5f * (float) Math.exp(-radial * 1.1f - forward / 10) * pulse * tip * rear;
    }

    private RoomFogVolume() {}
}
