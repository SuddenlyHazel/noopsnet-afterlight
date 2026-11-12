// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exports the actual CPU fog field for offline GPU renders; never starts Minecraft. */
public final class FogPlumePreview {
    public static void main(String[] args) throws Exception {
        int size = 64, count = size * size * size;
        int ox = -24, oy = -16, oz = -8;
        byte[] solid = new byte[count];
        float[] environment = new float[count * 2];
        for (int z = 0, i = 0; z < size; z++)
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++, i++) {
                    if (oy + y <= -4 || oz + z == 22) solid[i] = 1;
                    environment[i * 2 + 1] = 1;
                }
        Path output = Path.of("build/fog-plume-preview");
        Files.createDirectories(output);
        RgbLightVolume.Result volume = null;
        StringBuilder json =
                new StringBuilder("{\"size\":64,\"origin\":[-24,-16,-8],\"snapshots\":[");
        long total = 0;
        int frames = 0;
        for (int step = 0; step <= 240; step++) {
            float age = step * .25f;
            long start = System.nanoTime();
            volume =
                    RoomFogVolume.build(
                            size,
                            ox,
                            oy,
                            oz,
                            solid,
                            environment,
                            List.of(new RoomFogVolume.Emitter(-8.5, -1.5, 6.5, 1, .8f, age, 1, 0)),
                            volume,
                            .25f);
            total += System.nanoTime() - start;
            frames++;
            if (step == 1 || step == 8 || step == 32 || step == 120 || step == 240) {
                String file = "fog-" + step + ".f32";
                ByteBuffer data =
                        ByteBuffer.allocate(volume.rgba().length * 4)
                                .order(ByteOrder.LITTLE_ENDIAN);
                for (float value : volume.rgba()) data.putFloat(value);
                Files.write(output.resolve(file), data.array());
                if (json.charAt(json.length() - 1) != '[') json.append(',');
                json.append("{\"seconds\":")
                        .append(age)
                        .append(",\"file\":\"")
                        .append(file)
                        .append("\"}");
            }
        }
        json.append("],\"mean_update_ms\":").append(total / 1e6 / frames).append('}');
        Files.writeString(output.resolve("snapshots.json"), json);
        System.out.println(
                "Actual fog density snapshots exported; mean update: "
                        + total / 1e6 / frames
                        + " ms");
    }
}
