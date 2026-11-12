// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import net.noopsnet.neon.client.TextureUploadState;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL14;
import org.lwjgl.egl.EGL15;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Actual production upload scope, real GL textures, no Minecraft/window/UI. */
public final class TextureUploadGpuCheck {
    private static final int[] PARAMETERS = {
        GL33C.GL_UNPACK_ALIGNMENT,
        GL33C.GL_UNPACK_ROW_LENGTH,
        GL33C.GL_UNPACK_IMAGE_HEIGHT,
        GL33C.GL_UNPACK_SKIP_PIXELS,
        GL33C.GL_UNPACK_SKIP_ROWS,
        GL33C.GL_UNPACK_SKIP_IMAGES,
        GL33C.GL_UNPACK_SWAP_BYTES,
        GL33C.GL_UNPACK_LSB_FIRST,
        GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING
    };

    public static void main(String[] args) throws Exception {
        // Surfaceless EGL avoids opening or controlling any window. The raw
        // entry point permits EGL_DEFAULT_DISPLAY (0), which LWJGL's wrapper
        // incorrectly rejects for this platform's native_display argument.
        long platformDisplay = EGL.getCapabilities().eglGetPlatformDisplay;
        require(platformDisplay != 0, "EGL platform displays unavailable");
        long display = org.lwjgl.system.JNI.callPPP(0x31DD, 0L, 0L, platformDisplay);
        IntBuffer major = BufferUtils.createIntBuffer(1), minor = BufferUtils.createIntBuffer(1);
        require(EGL14.eglInitialize(display, major, minor), "eglInitialize");
        EGL.createDisplayCapabilities(display);
        require(EGL14.eglBindAPI(EGL14.EGL_OPENGL_API), "eglBindAPI");
        PointerBuffer configs = BufferUtils.createPointerBuffer(1);
        IntBuffer count = BufferUtils.createIntBuffer(1);
        require(
                EGL14.eglChooseConfig(
                                display,
                                ints(
                                        EGL14.EGL_SURFACE_TYPE,
                                        EGL14.EGL_PBUFFER_BIT,
                                        EGL14.EGL_RENDERABLE_TYPE,
                                        EGL14.EGL_OPENGL_BIT,
                                        EGL14.EGL_NONE),
                                configs,
                                count)
                        && count.get(0) > 0,
                "eglChooseConfig");
        long surface =
                EGL14.eglCreatePbufferSurface(
                        display,
                        configs.get(0),
                        ints(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE));
        long context =
                EGL14.eglCreateContext(
                        display,
                        configs.get(0),
                        EGL14.EGL_NO_CONTEXT,
                        ints(
                                EGL15.EGL_CONTEXT_MAJOR_VERSION,
                                3,
                                EGL15.EGL_CONTEXT_MINOR_VERSION,
                                3,
                                EGL14.EGL_NONE));
        require(
                surface != 0
                        && context != 0
                        && EGL14.eglMakeCurrent(display, surface, surface, context),
                "eglMakeCurrent");
        GL.createCapabilities();
        try {
            String renderer = GL33C.glGetString(GL33C.GL_RENDERER);
            check(false);
            check(true);
            require(GL33C.glGetError() == GL33C.GL_NO_ERROR, "OpenGL error");
            String report =
                    "{\n  \"renderer\": \""
                            + renderer
                            + "\",\n"
                            + "  \"unguarded_upload_corruption_reproduced\": true,\n"
                            + "  \"rgba32f_beam_and_rgba16f_volume_round_trips\": true,\n"
                            + "  \"allocation_and_update_with_dirty_unpack_state\": true,\n"
                            + "  \"pixel_unpack_buffer_and_state_restored\": true,\n"
                            + "  \"passed\": true\n"
                            + "}\n";
            Path out = Path.of("build/texture-upload-validation/result.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report);
            System.out.print(report);
        } finally {
            GL.setCapabilities(null);
            EGL14.eglMakeCurrent(display, 0, 0, 0);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglTerminate(display);
        }
    }

    private static void check(boolean volume) {
        int width = volume ? 8 : 4,
                height = 8,
                depth = volume ? 8 : 1,
                length = width * height * depth * 4;
        // Padding makes the deliberately broken baseline read defined memory.
        FloatBuffer data = BufferUtils.createFloatBuffer(16384);
        float[] expected = new float[length];
        for (int i = 0; i < length; i++) expected[i] = ((i * 13) % 127 - 63) / 16f;
        data.put(expected);
        while (data.hasRemaining()) data.put(.125f);
        data.flip();
        int texture = GL33C.glGenTextures(),
                target = volume ? GL33C.GL_TEXTURE_3D : GL33C.GL_TEXTURE_2D;
        int buffer = GL33C.glGenBuffers();
        GL33C.glBindTexture(target, texture);
        try {
            // NativeImage's atlas subregion uploads leave row length and skip
            // offsets behind. Reproduce with those alone, without byte swapping.
            int[] atlas = {4, 16, 0, 0, 16, 0, 0, 0};
            for (int i = 0; i < atlas.length; i++) GL33C.glPixelStorei(PARAMETERS[i], atlas[i]);
            // Prove the old code corrupts a correctly sized, otherwise valid upload.
            upload(volume, true, data, width, height, depth);
            require(
                    !Arrays.equals(expected, read(target, length)),
                    "Baseline must reproduce corruption");
            dirty(buffer);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, buffer);
            GL33C.glBufferData(GL33C.GL_PIXEL_UNPACK_BUFFER, 16, GL33C.GL_STATIC_DRAW);
            int[] before = state();
            try (var unpack = TextureUploadState.tightlyPacked()) {
                upload(volume, true, data, width, height, depth);
            }
            require(Arrays.equals(before, state()), "Allocation leaked unpack state");
            require(Arrays.equals(expected, read(target, length)), "Allocation round-trip differs");
            // Change all channels, so a skipped/stale update cannot pass.
            for (int i = 0; i < length; i++) {
                expected[i] *= -1;
                data.put(i, expected[i]);
            }
            try (var unpack = TextureUploadState.tightlyPacked()) {
                upload(volume, false, data, width, height, depth);
            }
            require(Arrays.equals(before, state()), "Update leaked unpack state");
            require(Arrays.equals(expected, read(target, length)), "Update round-trip differs");
        } finally {
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL33C.glDeleteBuffers(buffer);
            GL33C.glDeleteTextures(texture);
        }
    }

    private static void upload(
            boolean volume, boolean allocate, FloatBuffer data, int w, int h, int d) {
        if (volume) {
            if (allocate)
                GL33C.glTexImage3D(
                        GL33C.GL_TEXTURE_3D,
                        0,
                        GL33C.GL_RGBA16F,
                        w,
                        h,
                        d,
                        0,
                        GL33C.GL_RGBA,
                        GL33C.GL_FLOAT,
                        data);
            else
                GL33C.glTexSubImage3D(
                        GL33C.GL_TEXTURE_3D,
                        0,
                        0,
                        0,
                        0,
                        w,
                        h,
                        d,
                        GL33C.GL_RGBA,
                        GL33C.GL_FLOAT,
                        data);
        } else {
            if (allocate)
                GL33C.glTexImage2D(
                        GL33C.GL_TEXTURE_2D,
                        0,
                        GL33C.GL_RGBA32F,
                        w,
                        h,
                        0,
                        GL33C.GL_RGBA,
                        GL33C.GL_FLOAT,
                        data);
            else
                GL33C.glTexSubImage2D(
                        GL33C.GL_TEXTURE_2D, 0, 0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_FLOAT, data);
        }
    }

    private static void dirty(int buffer) {
        int[] values = {8, 12, 11, 2, 3, 1, 1, 1};
        for (int i = 0; i < values.length; i++) GL33C.glPixelStorei(PARAMETERS[i], values[i]);
        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, buffer);
    }

    private static int[] state() {
        return Arrays.stream(PARAMETERS).map(GL33C::glGetInteger).toArray();
    }

    private static float[] read(int target, int length) {
        FloatBuffer result = BufferUtils.createFloatBuffer(length);
        GL33C.glGetTexImage(target, 0, GL33C.GL_RGBA, GL33C.GL_FLOAT, result);
        float[] values = new float[length];
        result.get(values);
        return values;
    }

    private static IntBuffer ints(int... values) {
        IntBuffer b = BufferUtils.createIntBuffer(values.length);
        return b.put(values).flip();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
