// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LightVolumeBoundsTest {
    @Test
    void venueStripsStayInsideFromStageToDoorway() {
        for (int camera = -10; camera <= 24; camera++) {
            int origin = LightVolumeBounds.origin(camera, -13, 8, 64);
            assertTrue(-13 - origin >= 3 && 8 - origin <= 61);
            assertTrue(camera - origin >= 24 && camera - origin <= 43);
        }
    }

    @Test
    void emptyAndRemoteWindowsRemainBoundedIncludingNegativeCoordinates() {
        for (int camera = -100; camera <= 100; camera++) {
            int empty = LightVolumeBounds.origin(camera, Integer.MAX_VALUE, Integer.MIN_VALUE, 64);
            assertTrue(camera - empty >= 32 && camera - empty <= 35);
            for (int source : new int[] {-1000, 1000}) {
                int origin = LightVolumeBounds.origin(camera, source, source, 64);
                assertTrue(Math.abs(origin - empty) <= 8);
            }
        }
    }
}
