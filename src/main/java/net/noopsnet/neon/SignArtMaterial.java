// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

/** Pixel-exact LabPBR material shared by texture upload and regression tests. */
public record SignArtMaterial(SignArt art, boolean lit, int glow) {
    public SignArtMaterial(SignArt art, boolean lit) {
        this(art, lit, 4);
    }

    public SignArtMaterial {
        if (glow < 0 || glow > 4) throw new IllegalArgumentException("Invalid emission");
    }

    public static final int EMISSION = 248; // LabPBR reserves 255 for non-emissive pixels.

    /** Keep painted colours unless a live show explicitly requests blackout. */
    public static int artVertexTint(boolean lit, Integer liveColorOverride) {
        if (!lit) return 0xFF252525;
        return liveColorOverride != null && liveColorOverride == 0 ? 0xFF000000 : 0xFFFFFFFF;
    }

    public int albedoAbgr(int index) {
        int p = art.pixel(index);
        return (p & 0xFF00FF00) | ((p & 255) << 16) | ((p >>> 16) & 255);
    }

    public int specularAbgr(int index) {
        int emission = lit && art.pixel(index) != 0 ? EMISSION * glow / 4 : 0;
        // ABGR: emission alpha, zero porosity, dielectric F0, satin smoothness.
        return emission << 24 | 10 << 8 | 185;
    }
}
