// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import java.util.Arrays;
import java.util.List;

/** Exact content comparison prevents periodic rebuilding/uploading of static lighting. */
public record LightSnapshot(
        int size, int x, int y, int z, byte[] opacity, List<RgbLightVolume.Source> sources) {
    public LightSnapshot {
        sources = List.copyOf(sources);
    }

    public boolean sameContent(LightSnapshot other) {
        return other != null
                && size == other.size
                && x == other.x
                && y == other.y
                && z == other.z
                && sources.equals(other.sources)
                && Arrays.equals(opacity, other.opacity);
    }

    public RgbLightVolume.Result build() {
        return RgbLightVolume.build(size, x, y, z, opacity, sources);
    }
}
