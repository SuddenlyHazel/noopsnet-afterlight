// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tracks uninterrupted emissions by world-space nozzle, independently of submission order. */
public final class FogEmitterAges {
    private record Nozzle(double x, double y, double z) {}

    private final Map<Nozzle, Long> started = new HashMap<>();

    /** Consume the existing five-double bridge records and publish immutable ages. */
    public List<RoomFogVolume.Emitter> update(double[] fog, long now) {
        return update(fog, now, 5);
    }

    public List<RoomFogVolume.Emitter> updateDirected(double[] fog, long now) {
        return update(fog, now, 7);
    }

    private List<RoomFogVolume.Emitter> update(double[] fog, long now, int stride) {
        var result = new ArrayList<RoomFogVolume.Emitter>();
        Set<Nozzle> active = new HashSet<>();
        for (int i = 0; i + stride <= fog.length && result.size() < 8; i += stride) {
            boolean finite = true;
            for (int j = 0; j < stride; j++) finite &= Double.isFinite(fog[i + j]);
            if (!finite || fog[i + 3] <= 0) continue;
            var nozzle = new Nozzle(fog[i], fog[i + 1], fog[i + 2]);
            active.add(nozzle);
            long start = started.computeIfAbsent(nozzle, ignored -> now);
            float age = (float) Math.max(0.0, (now - start) / 1_000_000_000.0);
            result.add(
                    new RoomFogVolume.Emitter(
                            nozzle.x,
                            nozzle.y,
                            nozzle.z,
                            (float) Math.clamp(fog[i + 3], 0, 1),
                            (float) Math.clamp(fog[i + 4], 0, 1),
                            age,
                            stride == 7 ? (float) Math.clamp(fog[i + 5], -1, 1) : 0,
                            stride == 7 ? (float) Math.clamp(fog[i + 6], -1, 1) : 0));
        }
        started.keySet().retainAll(active);
        return List.copyOf(result);
    }

    public void clear() {
        started.clear();
    }
}
