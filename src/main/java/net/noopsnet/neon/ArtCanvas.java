// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Editor operations, independent of the game UI. One undo entry per stroke. */
public final class ArtCanvas {
    private int[] pixels;
    private final ArrayDeque<int[]> undo = new ArrayDeque<>();

    public ArtCanvas(SignArt art) {
        pixels = art.pixels();
    }

    public SignArt art() {
        return new SignArt(pixels);
    }

    public int pixel(int x, int y) {
        return pixels[x + SignArt.SIZE * y];
    }

    public void beginStroke() {
        if (undo.size() == 32) undo.removeFirst();
        undo.addLast(pixels.clone());
    }

    public void undo() {
        if (!undo.isEmpty()) pixels = undo.removeLast();
    }

    public void replace(SignArt art) {
        beginStroke();
        pixels = art.pixels();
    }

    public void clear() {
        beginStroke();
        Arrays.fill(pixels, 0);
    }

    public void line(int x, int y, int X, int Y, int color) {
        int dx = Math.abs(X - x),
                sx = x < X ? 1 : -1,
                dy = -Math.abs(Y - y),
                sy = y < Y ? 1 : -1,
                error = dx + dy;
        while (true) {
            if (x >= 0 && y >= 0 && x < 32 && y < 32) pixels[x + 32 * y] = color;
            if (x == X && y == Y) break;
            int e = 2 * error;
            if (e >= dy) {
                error += dy;
                x += sx;
            }
            if (e <= dx) {
                error += dx;
                y += sy;
            }
        }
    }

    public void fill(int x, int y, int color) {
        int old = pixel(x, y);
        if (old == color) return;
        beginStroke();
        var queue = new ArrayDeque<Integer>();
        queue.add(x + 32 * y);
        pixels[x + 32 * y] = color;
        while (!queue.isEmpty()) {
            int p = queue.removeFirst(), px = p % 32, py = p / 32;
            for (int next :
                    new int[] {
                        px > 0 ? p - 1 : -1,
                        px < 31 ? p + 1 : -1,
                        py > 0 ? p - 32 : -1,
                        py < 31 ? p + 32 : -1
                    })
                if (next >= 0 && pixels[next] == old) {
                    pixels[next] = color;
                    queue.add(next);
                }
        }
    }
}
