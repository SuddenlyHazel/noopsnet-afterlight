// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FogDensityScaleTest {
    @Test
    void personalPercentagesUseTheStrongerBaselineWithoutTheOldTwoTimesClamp() {
        assertEquals(0, FogDensityScale.opticalMultiplier(0));
        assertEquals(1.5, FogDensityScale.opticalMultiplier(1));
        assertEquals(3, FogDensityScale.opticalMultiplier(2));
        assertEquals(4.5, FogDensityScale.opticalMultiplier(3));
        assertEquals(4.5, FogDensityScale.opticalMultiplier(99));
        assertEquals(0, FogDensityScale.opticalMultiplier(-1));
        assertEquals(1.5, FogDensityScale.opticalMultiplier(Double.NaN));
        assertEquals(1.5, FogDensityScale.opticalMultiplier(Double.POSITIVE_INFINITY));
    }
}
