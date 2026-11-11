// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.noopsnet.neon.NeonBlock;
import net.noopsnet.neon.NeonBlockEntity;
import net.noopsnet.neon.lighting.RgbLightVolume;
import net.noopsnet.neon.lighting.LightSnapshot;
import net.noopsnet.neon.lighting.LightVolumeTransition;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33C;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded asynchronous lighting. The worker never reads a mutable Minecraft world. */
public final class AfterlightLighting {
    private static final int SIZE = 48, COUNT = SIZE * SIZE * SIZE;
    private static final Set<NeonBlockEntity> LOADED = new HashSet<>();
    private static final java.util.concurrent.ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Afterlight RGB lighting"); t.setDaemon(true); return t;
    });
    private static final AtomicReference<Completed> PENDING = new AtomicReference<>();
    private static volatile boolean busy;
    private static long epoch;
    private static ClientLevel level;
    private static int ticks, front;
    private static final int[] textures = new int[2];
    private static LightSnapshot lastSnapshot;
    private static final LightVolumeTransition TRANSITION = new LightVolumeTransition();
    private static final FloatBuffer UPLOAD = BufferUtils.createFloatBuffer(COUNT * 4);
    private record Completed(long epoch, RgbLightVolume.Result result) {}
    private AfterlightLighting() {}

    public static void loaded(NeonBlockEntity entity) { LOADED.add(entity); }

    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (level != mc.level) {
            level = mc.level; epoch++; TRANSITION.reset(); lastSnapshot = null; PENDING.set(null);
            LOADED.removeIf(e -> e.getLevel() != level || e.isRemoved());
        }
        LOADED.removeIf(e -> e.isRemoved() || e.getLevel() != level);
        if (level == null || textures[0] == 0 || mc.getCameraEntity() == null || ++ticks % 5 != 0 || busy) return;
        BlockPos center = mc.gameRenderer.getMainCamera().getBlockPosition();
        int ox = Math.floorDiv(center.getX(), 4) * 4 - SIZE / 2;
        int oy = Math.floorDiv(center.getY(), 4) * 4 - SIZE / 2;
        int oz = Math.floorDiv(center.getZ(), 4) * 4 - SIZE / 2;
        var lights = LOADED.stream().filter(e -> e.getBlockState().getValue(NeonBlock.LIT)
                && e.getBlockState().getValue(NeonBlock.LIGHT_LEVEL)>0)
            .filter(e -> e.getBlockPos().getX() >= ox && e.getBlockPos().getX() < ox + SIZE
                && e.getBlockPos().getY() >= oy && e.getBlockPos().getY() < oy + SIZE
                && e.getBlockPos().getZ() >= oz && e.getBlockPos().getZ() < oz + SIZE)
            .sorted(Comparator.<NeonBlockEntity>comparingDouble(e -> e.getBlockPos().distSqr(center)).thenComparingLong(e -> e.getBlockPos().asLong())).limit(96).toList();
        var sources = new ArrayList<RgbLightVolume.Source>();
        for (var e : lights) {
            var p = e.getBlockPos();
            sources.add(new RgbLightVolume.Source(p.getX(), p.getY(), p.getZ(), e.color(),
                e.getBlockState().getLightEmission(level, p)));
        }
        byte[] opacity = new byte[COUNT];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        if (!sources.isEmpty()) for (int z = 0, i = 0; z < SIZE; z++) for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++, i++) {
            pos.set(ox + x, oy + y, oz + z);
            opacity[i] = (byte) (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) ? 15 :
                level.getBlockState(pos).getLightBlock(level, pos));
        }
        var snapshot = new LightSnapshot(SIZE, ox, oy, oz, opacity, sources);
        if (snapshot.sameContent(lastSnapshot)) return;
        lastSnapshot = snapshot;
        long jobEpoch = epoch;
        busy = true;
        WORK.execute(() -> {
            try { PENDING.set(new Completed(jobEpoch, snapshot.build())); }
            finally { busy = false; }
        });
    }

    /** Iris calls this on the render thread when a program binds our sampler. */
    public static int texture() { return texture(front); }
    public static int previousTexture() { return texture(1 - front); }
    private static int texture(int slot) {
        RenderSystem.assertOnRenderThread();
        if (textures[slot] == 0) {
            textures[slot] = GL33C.glGenTextures();
            int old = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_3D);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, textures[slot]);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
            for (int axis : new int[]{GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_TEXTURE_WRAP_R})
                GL33C.glTexParameteri(GL33C.GL_TEXTURE_3D, axis, GL33C.GL_CLAMP_TO_EDGE);
            UPLOAD.clear(); while (UPLOAD.hasRemaining()) UPLOAD.put(0); UPLOAD.flip();
            GL33C.glTexImage3D(GL33C.GL_TEXTURE_3D, 0, GL33C.GL_RGBA16F, SIZE, SIZE, SIZE, 0, GL33C.GL_RGBA, GL33C.GL_FLOAT, UPLOAD);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, old);
        }
        return textures[slot];
    }

    public static void frame(RenderFrameEvent.Pre event) {
        long now = System.nanoTime();
        TRANSITION.beginFrame(now);
        // Worker completion and render timings can compress the nominal 250 ms
        // update spacing. Leave the latest result pending until the current fade
        // completes, instead of jumping from its displayed mixture to its endpoint.
        if (!TRANSITION.canAccept()) return;
        Completed done = PENDING.getAndSet(null);
        if (done == null || done.epoch != epoch || Minecraft.getInstance().level != level) return;
        if (TRANSITION.current() != null) front = 1 - front;
        int id = texture();
        int old = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_3D);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, id);
        UPLOAD.clear(); UPLOAD.put(done.result.rgba()).flip();
        GL33C.glTexSubImage3D(GL33C.GL_TEXTURE_3D, 0, 0, 0, 0, SIZE, SIZE, SIZE, GL33C.GL_RGBA, GL33C.GL_FLOAT, UPLOAD);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_3D, old);
        TRANSITION.accept(done.result, now);
    }

    public static Vector3f origin() { return origin(TRANSITION.current()); }
    public static Vector3f previousOrigin() { return origin(TRANSITION.previous()); }
    private static Vector3f origin(RgbLightVolume.Result volume) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return volume == null ? new Vector3f() : new Vector3f((float) (volume.x() - camera.x),
            (float) (volume.y() - camera.y), (float) (volume.z() - camera.z));
    }
    public static double blend() { return TRANSITION.blend(); }
    public static Vector3f previousSize() { return new Vector3f(TRANSITION.previous() == null ? 0 : SIZE); }
    public static Vector3f size() { return new Vector3f(TRANSITION.current() == null ? 0 : SIZE); }
}
