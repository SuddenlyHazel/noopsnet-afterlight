// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.*;
import java.nio.file.*;
import java.util.List;

class LightingFixtureTest {
    @Test
    void exportEnclosedRoomForCornerAndBlackoutGpuVerification() throws Exception {
        int n = 24;
        byte[] opacity = new byte[n * n * n];
        // Closed ceiling and back wall intersect at (y=16,z=16). The room side
        // has light; the air beyond each complete plane must remain dark.
        for (int z = 0; z < n; z++)
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++)
                    if (y == 16 || z == 16) opacity[x + n * y + n * n * z] = 15;
        var volume =
                RgbLightVolume.build(
                        n,
                        0,
                        0,
                        0,
                        opacity,
                        List.of(new RgbLightVolume.Source(12, 12, 13, 0x00FF00, 15)));
        ByteBuffer bytes =
                ByteBuffer.allocate(volume.rgba().length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : volume.rgba()) bytes.putFloat(value);
        Path dir = Path.of("build/lighting-fixture");
        Files.createDirectories(dir);
        Files.write(dir.resolve("corner-volume.rgba32f"), bytes.array());
    }

    @Test
    void exportActualLightingEngineVolumeForGpuVerification() throws Exception {
        int n = 48;
        byte[] opacity = new byte[n * n * n];
        // Floor and back wall; a closed divider is verified separately in RgbLightVolumeTest.
        for (int z = 0; z < n; z++)
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++)
                    if (y <= 8 || z >= 27) opacity[x + n * y + n * n * z] = 15;
        var lights =
                List.of(
                        new RgbLightVolume.Source(-3, 3, 2, 0xFF329A, 15),
                        new RgbLightVolume.Source(3, 3, 2, 0x30EBFF, 15),
                        new RgbLightVolume.Source(0, 2, -2, 0xAF5BFF, 15));
        long start = System.nanoTime();
        var v = RgbLightVolume.build(n, -24, -8, -24, opacity, lights);
        assertTrue(
                System.nanoTime() - start < 5_000_000_000L,
                "Bounded fixture should complete within five seconds");
        ByteBuffer bytes = ByteBuffer.allocate(v.rgba().length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v.rgba()) bytes.putFloat(f);
        Path dir = Path.of("build/lighting-fixture");
        Files.createDirectories(dir);
        Files.write(dir.resolve("volume.rgba32f"), bytes.array());
        Files.writeString(
                dir.resolve("volume.json"),
                "{\"size\":48,\"origin\":[-24,-8,-24],\"sources\":3,\"build_ms\":"
                        + (System.nanoTime() - start) / 1_000_000
                        + "}\n");
    }
}
