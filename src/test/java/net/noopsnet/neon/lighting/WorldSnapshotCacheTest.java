// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

class WorldSnapshotCacheTest {
    private final Object world = new Object();
    private final WorldSnapshotCache<int[]> cache = new WorldSnapshotCache<>();
    private final AtomicInteger reads = new AtomicInteger();
    private final Supplier<WorldSnapshotCache.Built<int[]>> reader =
            () -> new WorldSnapshotCache.Built<>(new int[] {reads.incrementAndGet()}, true);

    private int[] read() {
        return cache.get(world, 64, -31, -17, 15, reader);
    }

    @Test
    void unchangedInputsReuseExactlyAndDirtyReplacementDoesNotMutateWorkersInput() {
        int[] before = read();
        for (int i = 0; i < 240; i++) assertSame(before, read());
        assertEquals(1, reads.get());
        cache.invalidateCell(world, 0, 0, 32);
        int[] after = read();
        assertNotSame(before, after);
        assertArrayEquals(new int[] {1}, before);
        assertArrayEquals(new int[] {2}, after);
    }

    @Test
    void distantAndWrongWorldEventsDoNotDiscardValidGridButNeighborHaloDoes() {
        int[] first = read();
        cache.invalidateCell(new Object(), 0, 0, 32);
        cache.invalidateCell(world, 100, 100, 100);
        assertSame(first, read());
        cache.invalidateCell(world, -32, 0, 32);
        assertNotSame(first, read());
        int[] second = read();
        cache.invalidateCell(world, -33, 0, 32);
        assertSame(second, read());
    }

    @Test
    void negativeSectionCoordinatesAndPartialChunkIntersectionsInvalidate() {
        int[] first = read();
        cache.invalidateSection(world, -2, -2, 0);
        assertNotSame(first, read());
        first = read();
        cache.invalidateSection(world, -3, -2, 0);
        assertSame(first, read());
        cache.invalidateColumn(world, 2, 4);
        assertNotSame(first, read()); // x32 is final in-grid column.
        first = read();
        cache.invalidateColumn(world, 3, 4);
        assertSame(first, read());
    }

    @Test
    void worldIdentityOriginSizeAndViewWindowChangesRebuild() {
        Object equalA = new String("world"), equalB = new String("world");
        int[] first = cache.get(equalA, 64, 0, 0, 0, reader);
        assertNotSame(first, cache.get(equalB, 64, 0, 0, 0, reader));
        first = cache.get(world, 64, 0, 0, 0, reader);
        assertNotSame(first, cache.get(world, 64, 1, 0, 0, reader));
        first = cache.get(world, 64, 1, 0, 0, reader);
        assertNotSame(first, cache.get(world, 32, 1, 0, 0, reader));
        first = read();
        cache.invalidateWorld(world);
        assertNotSame(first, read());
        first = read();
        cache.clear();
        assertNotSame(first, read());
    }

    @Test
    void dynamicShapesAndMissingHookMarkersRetainUncachedSemantics() {
        var uncacheable =
                (Supplier<WorldSnapshotCache.Built<int[]>>)
                        () ->
                                new WorldSnapshotCache.Built<>(
                                        new int[] {reads.incrementAndGet()}, false);
        int[] first = cache.get(world, 4, 0, 0, 0, uncacheable);
        assertNotSame(first, cache.get(world, 4, 0, 0, 0, uncacheable));
    }

    @Test
    void updateDuringSnapshotReadCannotPublishAReusableStaleGrid() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var dirtied = new java.util.concurrent.CountDownLatch(1);
        Thread update =
                new Thread(
                        () -> {
                            try {
                                start.await();
                                cache.invalidateCell(world, 0, 0, 32);
                                dirtied.countDown();
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                        });
        update.start();
        int[] first =
                cache.get(
                        world,
                        64,
                        -31,
                        -17,
                        15,
                        () -> {
                            start.countDown();
                            try {
                                dirtied.await();
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                            return reader.get();
                        });
        update.join();
        assertNotSame(first, read());
        assertEquals(2, reads.get());
    }

    @Test
    void rgbAndFogClassificationCachesRemainIndependent() {
        WorldSnapshotCache<int[]> fog = new WorldSnapshotCache<>();
        int[] rgb = read();
        int[] fogData = fog.get(world, 64, -31, -17, 15, reader);
        fog.invalidateSection(world, 0, 0, 1);
        assertSame(rgb, read());
        assertNotSame(fogData, fog.get(world, 64, -31, -17, 15, reader));
    }

    @Test
    void movingOriginsStillRebuildAndLongCoordinateIntersectionsDoNotOverflow() {
        for (int x = 0; x < 64; x++) cache.get(world, 64, x, 0, 0, reader);
        assertEquals(64, reads.get());
        var edge = new WorldSnapshotCache<int[]>();
        int[] first = edge.get(world, 64, Integer.MAX_VALUE - 32, 0, 0, reader);
        edge.invalidateCell(world, Integer.MAX_VALUE, 0, 0);
        assertNotSame(first, edge.get(world, 64, Integer.MAX_VALUE - 32, 0, 0, reader));
    }

    @Test
    void sourceCueChangesReuseGeometryButStillChangePropagation() {
        byte[] geometry = new byte[8];
        var a =
                new LightSnapshot(
                        2,
                        0,
                        0,
                        0,
                        geometry,
                        java.util.List.of(new RgbLightVolume.Source(0, 0, 0, 0xff0000, 15)));
        var b =
                new LightSnapshot(
                        2,
                        0,
                        0,
                        0,
                        geometry,
                        java.util.List.of(new RgbLightVolume.Source(0, 0, 0, 0x0000ff, 15)));
        assertFalse(a.sameContent(b));
        assertTrue(a.build().rgba()[0] > 0);
        assertEquals(0, b.build().rgba()[0]);
        assertTrue(b.build().rgba()[2] > 0);
        assertArrayEquals(new byte[8], geometry);
    }

    @Test
    void adjacentChunkArrivalInvalidatesBoundaryAndDiagnosticsReflectDirtyState() {
        int[] first = cache.get(world, 16, 0, 0, 0, reader);
        assertTrue(cache.stats().reusable());
        cache.invalidateColumn(world, 1, 0);
        assertFalse(cache.stats().reusable());
        assertNotSame(first, cache.get(world, 16, 0, 0, 0, reader));
        assertEquals(2, cache.stats().requests());
        assertEquals(2, cache.stats().rebuilds());
        first = cache.get(world, 16, 0, 0, 0, reader);
        cache.invalidateColumn(world, 2, 0);
        assertSame(first, cache.get(world, 16, 0, 0, 0, reader));
        assertEquals(2, cache.stats().hits());
    }
}
