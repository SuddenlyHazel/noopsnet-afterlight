// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import java.util.Arrays;

/** Immutable 32x32 ARGB canvas. Transparent or fully opaque pixels only. */
public final class SignArt {
    public static final int SIZE = 32, PIXELS = SIZE * SIZE;
    private final int[] pixels;
    private final int hash;

    public SignArt(int[] pixels) {
        if (pixels.length != PIXELS) throw new IllegalArgumentException("Expected a 32x32 canvas");
        this.pixels = pixels.clone();
        for (int p : this.pixels)
            if (p != 0 && (p >>> 24) != 255)
                throw new IllegalArgumentException(
                        "Pixels must be opaque ARGB or transparent zero");
        hash = Arrays.hashCode(this.pixels);
    }

    public int pixel(int index) {
        return pixels[index];
    }

    public int[] pixels() {
        return pixels.clone();
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SignArt art && Arrays.equals(pixels, art.pixels);
    }
}
