// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightVolumeTransitionTest {
    private static RgbLightVolume.Result volume(float liveRed, float liveBlue) {
        // Constant green fixture beside a red/blue animated fixture; equal reach.
        float[] rgba = new float[2 * 2 * 2 * 4];
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = liveRed; rgba[i + 1] = .25f; rgba[i + 2] = liveBlue; rgba[i + 3] = 1.25f;
        }
        return new RgbLightVolume.Result(2, 0, 0, 0, rgba);
    }

    private static double channel(LightVolumeTransition transition, int index) {
        float previous = transition.previous() == null ? 0 : transition.previous().rgba()[index];
        return previous + (transition.current().rgba()[index] - previous) * transition.blend();
    }

    @Test void workerBurstsCannotInterruptTheDisplayedMixtureOrChangeTheStaticLight() {
        var transition = new LightVolumeTransition();
        long now = 1_000_000_000L;
        transition.accept(volume(1, 0), now);
        transition.beginFrame(now += 250_000_000L);
        transition.accept(volume(0, 1), now);
        transition.beginFrame(now += 90_000_000L);
        assertEquals(.5, channel(transition, 0), 1e-8);
        assertEquals(.5, channel(transition, 2), 1e-8);
        assertEquals(.25, channel(transition, 1), 1e-8);
        // A quick worker result follows a delayed one. The old bridge promoted
        // the blue endpoint immediately here, causing a 0.5 red/blue jump.
        assertFalse(transition.canAccept());
        long burstTime = now;
        assertThrows(IllegalStateException.class, () -> transition.accept(volume(1, 0), burstTime));
        assertEquals(.5, channel(transition, 0), 1e-8);
        transition.beginFrame(now += 90_000_000L);
        assertTrue(transition.canAccept());
        double[] before = {channel(transition, 0), channel(transition, 1), channel(transition, 2)};
        transition.accept(volume(1, 0), now);
        for (int i = 0; i < 3; i++) assertEquals(before[i], channel(transition, i), 1e-8);
        transition.beginFrame(now + 90_000_000L);
        assertEquals(.25, channel(transition, 1), 1e-8);
    }

    @Test void everyProgramUsesTheSameFrameTimeAndWorldChangesDiscardBothVolumes() {
        var transition = new LightVolumeTransition();
        long now = 1_000_000_000L;
        transition.accept(volume(1, 0), now);
        transition.beginFrame(now + 60_000_000L);
        double opaqueBlend = transition.blend();
        for (int pass = 0; pass < 16; pass++) assertEquals(opaqueBlend, transition.blend());
        transition.beginFrame(now + 120_000_000L);
        assertEquals(2 * opaqueBlend, transition.blend(), 1e-8);
        transition.reset();
        assertNull(transition.current());
        assertNull(transition.previous());
        assertTrue(transition.canAccept());
        transition.accept(volume(0, 1), now + 130_000_000L);
        assertNull(transition.previous());
        assertEquals(0, transition.blend());
    }
}
