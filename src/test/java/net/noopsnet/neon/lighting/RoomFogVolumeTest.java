// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class RoomFogVolumeTest {
    private static final int N = 16;

    private static int index(int x, int y, int z) {
        return (z * N + y) * N + x;
    }

    private static float density(RgbLightVolume.Result v, int x, int y, int z) {
        return v.rgba()[index(x, y, z) * 4 + 3];
    }

    private static RoomFogVolume.Emitter source(float spread) {
        return new RoomFogVolume.Emitter(3.5, 3.5, 8.5, 1, spread);
    }

    private static RoomFogVolume.Emitter source(float spread, float age) {
        return new RoomFogVolume.Emitter(3.5, 3.5, 8.5, 1, spread, age);
    }

    private static RgbLightVolume.Result build(
            byte[] wall,
            List<RoomFogVolume.Emitter> sources,
            RgbLightVolume.Result previous,
            float dt) {
        return RoomFogVolume.build(
                N, 0, 0, 0, wall, new float[N * N * N * 2], sources, previous, dt);
    }

    @Test
    void sealedPartitionContainsFogButOpeningDoorLetsItFillAdjacentRoom() {
        byte[] wall = new byte[N * N * N];
        for (int y = 0; y < N; y++) for (int z = 0; z < N; z++) wall[index(8, y, z)] = 1;
        var sealed = build(wall, List.of(source(1)), null, 1);
        assertTrue(density(sealed, 6, 3, 8) > .1);
        assertEquals(0, density(sealed, 9, 3, 8));
        wall[index(8, 3, 8)] = 0;
        var open = build(wall, List.of(source(1)), sealed, 1);
        assertTrue(density(open, 10, 3, 8) > .1, "Fog must flow through the doorway");
        assertEquals(0, density(open, 8, 6, 8), "Solid wall cells must remain empty");
    }

    @Test
    void outputBuildsGraduallyAndLingersInsteadOfPoppingOff() {
        byte[] air = new byte[N * N * N];
        var a = build(air, List.of(source(1)), null, .25f);
        var b = build(air, List.of(source(1)), a, .25f);
        var stopped = build(air, List.of(), b, .25f);
        assertTrue(density(b, 3, 3, 8) > density(a, 3, 3, 8));
        assertTrue(density(stopped, 3, 3, 8) < density(b, 3, 3, 8));
        assertTrue(density(stopped, 3, 3, 8) > density(b, 3, 3, 8) * .9);
        for (int i = 0; i < 40; i++) stopped = build(air, List.of(), stopped, 1);
        assertEquals(0, density(stopped, 3, 3, 8));
    }

    @Test
    void shiftingCameraVolumePreservesWorldDensityAndNewWallsClearIt() {
        byte[] air = new byte[N * N * N];
        var a = build(air, List.of(source(1)), null, 1);
        var b = RoomFogVolume.build(N, 4, 0, 0, air, new float[N * N * N * 2], List.of(), a, 0);
        assertEquals(density(a, 6, 3, 8), density(b, 2, 3, 8));
        air[index(2, 3, 8)] = 1;
        var closed =
                RoomFogVolume.build(N, 4, 0, 0, air, new float[N * N * N * 2], List.of(), b, 0);
        assertEquals(0, density(closed, 2, 3, 8));
        assertEquals(1, closed.rgba()[index(2, 3, 8) * 4 + 2]);
    }

    @Test
    void spreadControlsReachAndInvalidSourcesCannotPoisonTheVolume() {
        byte[] air = new byte[N * N * N];
        assertEquals(0, density(build(air, List.of(source(0)), null, 1), 15, 3, 8));
        assertTrue(density(build(air, List.of(source(1)), null, 1), 15, 3, 8) > .1);
        var invalid = new RoomFogVolume.Emitter(Double.NaN, 3, 8, Float.POSITIVE_INFINITY, 1);
        var v = build(air, List.of(invalid), null, 1);
        for (float f : v.rgba()) {
            assertTrue(Float.isFinite(f));
            assertEquals(0, f);
        }
    }

    @Test
    void aMachineInsideSolidOrOutsideLoadedBoundsCannotEmitThroughWalls() {
        byte[] solid = new byte[N * N * N];
        solid[index(3, 3, 8)] = 1;
        var v =
                build(
                        solid,
                        List.of(source(1), new RoomFogVolume.Emitter(-10, 0, 0, 1, 1)),
                        null,
                        1);
        for (int i = 3; i < v.rgba().length; i += 4) assertEquals(0, v.rgba()[i]);
    }

    @Test
    void newHazeBuildsAtNozzleBeforeSpreadingAndEventuallyFillsItsReach() {
        byte[] air = new byte[N * N * N];
        var fresh = build(air, List.of(source(1, 0)), null, .25f);
        assertTrue(density(fresh, 3, 3, 8) > .15, "The nozzle fills on the first update");
        assertTrue(density(fresh, 4, 3, 8) > 0, "The front has a soft nearby edge");
        assertEquals(0, density(fresh, 6, 3, 8), "A fresh machine cannot fill the room at once");
        var volume = fresh;
        for (int step = 1; step <= 4; step++)
            volume = build(air, List.of(source(1, step * .25f)), volume, .25f);
        assertTrue(density(volume, 3, 3, 8) > density(volume, 8, 3, 8));
        assertTrue(density(volume, 8, 3, 8) > 0, "The front reaches five blocks after one second");
        assertEquals(0, density(volume, 14, 3, 8), "Far cells must wait for the advancing front");
        for (int step = 5; step <= 28; step++)
            volume = build(air, List.of(source(1, step * .25f)), volume, .25f);
        assertTrue(
                density(volume, 14, 3, 8) > .2,
                "Sustained output eventually fills reachable cells");
    }

    @Test
    void movingTheGridPreservesAnAdvancingFrontInWorldCoordinates() {
        byte[] air = new byte[N * N * N];
        float[] environment = new float[N * N * N * 2];
        var before = build(air, List.of(source(1, .5f)), null, .25f);
        var rebased =
                RoomFogVolume.build(
                        N, 2, 0, 0, air, environment, List.of(source(1, .5f)), before, 0);
        assertEquals(density(before, 6, 3, 8), density(rebased, 4, 3, 8));
        var stayed = build(air, List.of(source(1, 1)), before, .25f);
        var moved =
                RoomFogVolume.build(
                        N, 2, 0, 0, air, environment, List.of(source(1, 1)), rebased, .25f);
        assertTrue(density(stayed, 8, 3, 8) > 0);
        assertEquals(density(stayed, 8, 3, 8), density(moved, 6, 3, 8), 1e-6);
        assertEquals(0, density(moved, 12, 3, 8));
    }

    @Test
    void frontUsesDoorwayPathLengthAndOldFogContinuesToFadeOnRestart() {
        byte[] wall = new byte[N * N * N];
        for (int y = 0; y < N; y++) for (int z = 0; z < N; z++) wall[index(8, y, z)] = 1;
        var sealed = build(wall, List.of(source(1, 4)), null, 1);
        assertEquals(0, density(sealed, 10, 3, 8));
        wall[index(8, 8, 8)] = 0; // shortest route to target is 17 voxels, not seven
        var early = build(wall, List.of(source(1, 2)), sealed, 1);
        assertEquals(0, density(early, 10, 3, 8), "The front must travel through the doorway");
        var reached = build(wall, List.of(source(1, 3)), early, 1);
        assertTrue(density(reached, 10, 3, 8) > 0);
        var stopped = build(wall, List.of(), reached, .25f);
        assertTrue(density(stopped, 10, 3, 8) < density(reached, 10, 3, 8));
        var restarted = build(wall, List.of(source(1, 0)), stopped, .25f);
        assertTrue(density(restarted, 3, 3, 8) > density(stopped, 3, 3, 8));
        assertTrue(
                density(restarted, 10, 3, 8) < density(stopped, 10, 3, 8),
                "The new front must not instantly refill old distant haze");
        assertTrue(density(restarted, 10, 3, 8) > 0, "Previously emitted haze still lingers");
    }

    @Test
    void emitterAgesSurviveReorderingAndResetOnStopOrWorldClear() {
        var tracker = new FogEmitterAges();
        double[] two = {1.5, 2.5, 3.5, 1, .5, 8.5, 2.5, 3.5, .4, 1};
        var initial = tracker.update(two, 1_000_000_000L);
        assertEquals(0, initial.get(0).ageSeconds());
        assertEquals(0, initial.get(1).ageSeconds());
        double[] reversed = {8.5, 2.5, 3.5, .4, 1, 1.5, 2.5, 3.5, 1, .5};
        var later = tracker.update(reversed, 2_000_000_000L);
        assertEquals(1, later.get(0).ageSeconds());
        assertEquals(1, later.get(1).ageSeconds());
        tracker.update(new double[] {8.5, 2.5, 3.5, .4, 1}, 2_250_000_000L);
        var restarted = tracker.update(two, 3_000_000_000L);
        assertEquals(0, restarted.get(0).ageSeconds(), "A stopped machine restarts its front");
        assertEquals(2, restarted.get(1).ageSeconds(), "The other machine keeps its own clock");
        tracker.clear();
        assertEquals(
                0,
                tracker.update(two, 4_000_000_000L).get(1).ageSeconds(),
                "World changes clear clocks");
        tracker.update(new double[] {1.5, 2.5, 3.5, 0, .5}, 6_000_000_000L);
        assertEquals(
                0,
                tracker.update(two, 7_000_000_000L).get(0).ageSeconds(),
                "A zero cue stops the nozzle");
        assertTrue(tracker.update(new double[] {Double.NaN, 0, 0, 1, 1}, 8_000_000_000L).isEmpty());
    }
}
