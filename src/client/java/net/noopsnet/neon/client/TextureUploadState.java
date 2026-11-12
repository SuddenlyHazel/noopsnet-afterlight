// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import org.lwjgl.opengl.GL33C;

/** Scope CPU uploads independently of Minecraft's last atlas/video upload. */
public final class TextureUploadState implements AutoCloseable {
    private static final int[] PARAMETERS = {
        GL33C.GL_UNPACK_ALIGNMENT, GL33C.GL_UNPACK_ROW_LENGTH,
        GL33C.GL_UNPACK_IMAGE_HEIGHT, GL33C.GL_UNPACK_SKIP_PIXELS,
        GL33C.GL_UNPACK_SKIP_ROWS, GL33C.GL_UNPACK_SKIP_IMAGES,
        GL33C.GL_UNPACK_SWAP_BYTES, GL33C.GL_UNPACK_LSB_FIRST
    };
    private final int[] previous = new int[PARAMETERS.length];
    private final int buffer;

    private TextureUploadState() {
        buffer = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
        for (int i = 0; i < PARAMETERS.length; i++) {
            previous[i] = GL33C.glGetInteger(PARAMETERS[i]);
            GL33C.glPixelStorei(PARAMETERS[i], i == 0 ? 1 : 0);
        }
    }

    public static TextureUploadState tightlyPacked() {
        return new TextureUploadState();
    }

    @Override
    public void close() {
        for (int i = 0; i < PARAMETERS.length; i++) GL33C.glPixelStorei(PARAMETERS[i], previous[i]);
        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, buffer);
    }
}
