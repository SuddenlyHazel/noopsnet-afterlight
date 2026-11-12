// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** One immutable grid, read on the client thread; dirty notifications may arrive elsewhere. */
public final class WorldSnapshotCache<T> {
    public record Built<T>(T value, boolean reusable) {
        public Built {
            Objects.requireNonNull(value);
        }
    }

    private record Key(Object world, int size, int x, int y, int z) {
        boolean same(Key other) {
            return other != null
                    && world == other.world
                    && size == other.size
                    && x == other.x
                    && y == other.y
                    && z == other.z;
        }

        boolean intersects(long ax, long ay, long az, long bx, long by, long bz) {
            return ax < (long) x + size
                    && bx > x
                    && ay < (long) y + size
                    && by > y
                    && az < (long) z + size
                    && bz > z;
        }

        boolean column(long ax, long az, long bx, long bz) {
            return ax < (long) x + size && bx > x && az < (long) z + size && bz > z;
        }
    }

    private volatile Key key;
    private final AtomicLong revision = new AtomicLong();
    private long builtRevision = -1;
    private Built<T> built;
    private long requests, hits, rebuilds;

    public record Stats(long requests, long hits, long rebuilds, long revision, boolean reusable) {}

    /** Main-thread diagnostic counters, with no world data or per-cell logging. */
    public Stats stats() {
        long current = revision.get();
        return new Stats(
                requests,
                hits,
                rebuilds,
                current,
                built != null && built.reusable && builtRevision == current);
    }

    public T get(Object world, int size, int x, int y, int z, Supplier<Built<T>> reader) {
        requests++;
        Objects.requireNonNull(world);
        if (size < 1 || size > 64) throw new IllegalArgumentException("Unbounded snapshot");
        Key wanted = new Key(world, size, x, y, z);
        if (!wanted.same(key)) {
            key = wanted;
            built = null;
            revision.incrementAndGet();
        }
        long before = revision.get();
        if (built != null && built.reusable && builtRevision == before) {
            hits++;
            return built.value;
        }
        rebuilds++;
        Built<T> next = Objects.requireNonNull(reader.get());
        // Never mark a snapshot reusable if an update arrived while reading it.
        // The returned grid has the same point-in-time semantics as the former
        // uncached scan; the next request must read again after a concurrent dirty.
        if (revision.get() == before) {
            built = next;
            builtRevision = before;
        } else built = null;
        return next.value;
    }

    public void clear() {
        key = null;
        built = null;
        revision.incrementAndGet();
    }

    public void invalidateWorld(Object world) {
        Key k = key;
        if (k != null && k.world == world) revision.incrementAndGet();
    }

    public void invalidateCell(Object world, int x, int y, int z) {
        Key k = key;
        if (k != null
                && k.world == world
                && k.intersects(
                        (long) x - 1,
                        (long) y - 1,
                        (long) z - 1,
                        (long) x + 2,
                        (long) y + 2,
                        (long) z + 2)) revision.incrementAndGet();
    }

    public void invalidateSection(Object world, int x, int y, int z) {
        Key k = key;
        long ax = (long) x * 16, ay = (long) y * 16, az = (long) z * 16;
        if (k != null && k.world == world && k.intersects(ax, ay, az, ax + 16, ay + 16, az + 16))
            revision.incrementAndGet();
    }

    public void invalidateColumn(Object world, int x, int z) {
        Key k = key;
        long ax = (long) x * 16, az = (long) z * 16;
        if (k != null && k.world == world && k.column(ax - 1, az - 1, ax + 17, az + 17))
            revision.incrementAndGet();
    }
}
