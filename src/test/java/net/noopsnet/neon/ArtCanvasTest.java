// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ArtCanvasTest {
    @Test
    void strokesFillUndoAndTransparencyPreserveTheDraft() {
        var canvas = new ArtCanvas(new SignArt(new int[1024]));
        canvas.beginStroke();
        canvas.line(0, 0, 31, 31, 0xFFFF0080);
        for (int i = 0; i < 32; i++) assertEquals(0xFFFF0080, canvas.pixel(i, i));
        canvas.fill(31, 0, 0xFF00FFFF);
        assertEquals(0, canvas.pixel(0, 31));
        assertEquals(0xFF00FFFF, canvas.pixel(30, 0));
        canvas.undo();
        assertEquals(0, canvas.pixel(30, 0));
        assertEquals(0xFFFF0080, canvas.pixel(0, 0));
        canvas.undo();
        for (int p : canvas.art().pixels()) assertEquals(0, p);
        canvas.fill(10, 10, 0xFFFFFFFF);
        canvas.clear();
        assertEquals(0, canvas.pixel(10, 10));
        canvas.undo();
        assertEquals(0xFFFFFFFF, canvas.pixel(10, 10));
    }

    @Test
    void imagesAreImmutableAndMalformedPixelsAreRejected() {
        int[] pixels = new int[1024];
        pixels[7] = 0xFF123456;
        var art = new SignArt(pixels);
        pixels[7] = 0;
        assertEquals(0xFF123456, art.pixel(7));
        var copy = art.pixels();
        copy[7] = 0;
        assertEquals(0xFF123456, art.pixel(7));
        assertEquals(art, new SignArt(art.pixels()));
        assertThrows(IllegalArgumentException.class, () -> new SignArt(new int[4096]));
        pixels[0] = 0x7FFFFFFF;
        assertThrows(IllegalArgumentException.class, () -> new SignArt(pixels));
    }
}
