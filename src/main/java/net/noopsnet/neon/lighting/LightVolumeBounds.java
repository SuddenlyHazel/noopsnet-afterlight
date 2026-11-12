// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

/** A bounded window biased toward nearby fixtures, independent of view direction. */
public final class LightVolumeBounds {
    private LightVolumeBounds() {}

    public static int origin(int camera, int minimumSource, int maximumSource, int size) {
        int center = Math.floorDiv(camera, 4) * 4;
        if (minimumSource <= maximumSource) {
            int sourceCenter =
                    Math.floorDiv(minimumSource + (maximumSource - minimumSource) / 2, 4) * 4;
            // Keep the viewer at least 24 blocks inside a 64-block window, but
            // don't drop an entire strip merely because its seed passed an edge.
            center += Math.clamp(sourceCenter - center, -8, 8);
        }
        return center - size / 2;
    }
}
