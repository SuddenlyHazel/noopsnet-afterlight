// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

/** Personal percentages are relative to the art-directed stage-haze baseline. */
public final class FogDensityScale {
    public static final double BASELINE = 1.5;

    private FogDensityScale() {}

    public static double opticalMultiplier(double personalMultiplier) {
        return BASELINE
                * (Double.isFinite(personalMultiplier)
                        ? Math.clamp(personalMultiplier, 0.0, 3.0)
                        : 1.0);
    }
}
