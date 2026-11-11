// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

/** Render-frame state shared by every shader pass. All methods run on the client thread. */
public final class LightVolumeTransition {
    private static final long DURATION_NS = 180_000_000L;
    private RgbLightVolume.Result current, previous;
    private long started;
    private double blend = 1;

    public void reset() {
        current = previous = null;
        blend = 1;
    }

    /** Sample time once, before any program reads its per-frame uniforms. */
    public void beginFrame(long now) {
        blend = current == null ? 1 : Math.clamp((now - started) / (double) DURATION_NS, 0, 1);
    }

    public boolean canAccept() { return blend == 1; }

    /** Keep an in-flight fade intact; its endpoint must become visible before reuse. */
    public void accept(RgbLightVolume.Result next, long now) {
        if (!canAccept()) throw new IllegalStateException("RGB volume fade still in progress");
        previous = current;
        current = next;
        started = now;
        blend = 0;
    }

    public RgbLightVolume.Result current() { return current; }
    public RgbLightVolume.Result previous() { return previous; }
    public double blend() { return blend; }
}
