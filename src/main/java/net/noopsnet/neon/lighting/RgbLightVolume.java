// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/** Immutable world snapshots in, linear RGB + energy voxels out. Alpha -1 marks opaque cells. */
public final class RgbLightVolume {
    public record Source(int x, int y, int z, int rgb, int level) {}
    public record Result(int size, int x, int y, int z, float[] rgba) {}

    private RgbLightVolume() {}

    public static Result build(int size, int ox, int oy, int oz, byte[] opacity, List<Source> sources) {
        if (size < 2 || size > 64 || opacity.length != size * size * size || sources.size() > 96)
            throw new IllegalArgumentException("Unbounded lighting snapshot");
        int count = opacity.length, plane = size * size;
        float[] rgba = new float[count * 4];
        byte[] reached = new byte[count];
        @SuppressWarnings("unchecked") ArrayDeque<Integer>[] buckets = new ArrayDeque[16];
        for (int i = 0; i < 16; i++) buckets[i] = new ArrayDeque<>();
        for (Source source : sources) {
            int x = source.x - ox, y = source.y - oy, z = source.z - oz;
            if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size) continue;
            int level = Math.clamp(source.level, 0, 15);
            Arrays.fill(reached, (byte) 0);
            int seed = x + size * y + plane * z;
            reached[seed] = (byte) level;
            buckets[level].add(seed);
            float r = linear(source.rgb >> 16 & 255), g = linear(source.rgb >> 8 & 255), b = linear(source.rgb & 255);
            for (int light = level; light > 0; light--) {
                var bucket = buckets[light];
                while (!bucket.isEmpty()) {
                    int index = bucket.removeFirst();
                    if (reached[index] != light) continue;
                    float weight = (float) Math.pow(light / 15.0, 2.2);
                    int p = index * 4;
                    rgba[p] += r * weight; rgba[p + 1] += g * weight; rgba[p + 2] += b * weight; rgba[p + 3] += weight;
                    int px = index % size, py = index / size % size, pz = index / plane;
                    if (px > 0) spread(index - 1, light, opacity, reached, buckets);
                    if (px + 1 < size) spread(index + 1, light, opacity, reached, buckets);
                    if (py > 0) spread(index - size, light, opacity, reached, buckets);
                    if (py + 1 < size) spread(index + size, light, opacity, reached, buckets);
                    if (pz > 0) spread(index - plane, light, opacity, reached, buckets);
                    if (pz + 1 < size) spread(index + plane, light, opacity, reached, buckets);
                }
            }
            buckets[0].clear();
        }
        // Keep unlit air distinct from solid geometry. The shader excludes
        // opaque samples from interpolation instead of diluting nearby light.
        // A light source can itself be opaque, so retain reached source cells.
        for (int i = 0; i < count; i++)
            if ((opacity[i] & 255) >= 15 && rgba[i * 4 + 3] == 0) rgba[i * 4 + 3] = -1;
        return new Result(size, ox, oy, oz, rgba);
    }

    private static void spread(int index, int light, byte[] opacity, byte[] reached, ArrayDeque<Integer>[] buckets) {
        int next = light - Math.max(1, opacity[index] & 255);
        if (next > reached[index]) {
            reached[index] = (byte) next;
            buckets[next].add(index);
        }
    }

    public static float linear(int component) {
        double value = component / 255.0;
        return (float) (value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4));
    }
}
