// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class LightSnapshotTest {
    @Test
    void stationarySnapshotsStayIdenticalButRealEditsInvalidateThem() {
        byte[] opacity = new byte[64];
        var source = new RgbLightVolume.Source(2, 2, 2, 0x202020, 14);
        var base = new LightSnapshot(4, 0, 0, 0, opacity, List.of(source));
        for (int i = 0; i < 100; i++)
            assertTrue(
                    new LightSnapshot(4, 0, 0, 0, opacity.clone(), List.of(source))
                            .sameContent(base));
        byte[] wall = opacity.clone();
        wall[10] = 15;
        assertFalse(new LightSnapshot(4, 0, 0, 0, wall, List.of(source)).sameContent(base));
        assertFalse(new LightSnapshot(4, 4, 0, 0, opacity, List.of(source)).sameContent(base));
        assertFalse(
                new LightSnapshot(
                                4,
                                0,
                                0,
                                0,
                                opacity,
                                List.of(new RgbLightVolume.Source(2, 2, 2, 0xFFFFFF, 14)))
                        .sameContent(base));
        assertFalse(new LightSnapshot(4, 0, 0, 0, opacity, List.of()).sameContent(base));
    }
}
