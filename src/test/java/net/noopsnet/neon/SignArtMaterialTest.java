// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SignArtMaterialTest {
    private static int tintedRgb(int abgr, int tint) {
        int r = (abgr & 255) * ((tint >>> 16) & 255) / 255;
        int g = ((abgr >>> 8) & 255) * ((tint >>> 8) & 255) / 255;
        int b = ((abgr >>> 16) & 255) * (tint & 255) / 255;
        return (r << 16) | (g << 8) | b;
    }

    @Test
    void liveBlackoutDarkensPaintedPixelsAndRestoresTheirAuthoredColours() {
        int[] pixels = new int[1024];
        pixels[0] = 0xFFEA265A;
        pixels[1] = 0xFF1BC2EE;
        var art = new SignArt(pixels);
        var lit = new SignArtMaterial(art, true, 4);
        int unchanged = SignArtMaterial.artVertexTint(true, null);
        int nonzeroCue = SignArtMaterial.artVertexTint(true, 0x123456);
        int blackout = SignArtMaterial.artVertexTint(true, 0);
        assertEquals(0xFFFFFFFF, unchanged);
        assertEquals(unchanged, nonzeroCue, "Nonzero live cues do not recolor custom art");
        assertEquals(0xFF000000, blackout);
        for (int i = 0; i < 2; i++) {
            int albedo = lit.albedoAbgr(i);
            assertEquals(pixels[i] & 0xFFFFFF, tintedRgb(albedo, unchanged));
            assertEquals(
                    0, tintedRgb(albedo, blackout), "Blackout makes the shader's source RGB zero");
            assertEquals(
                    tintedRgb(albedo, unchanged),
                    tintedRgb(albedo, SignArtMaterial.artVertexTint(true, null)),
                    "Restoring the show restores the art");
            assertEquals(
                    248,
                    lit.specularAbgr(i) >>> 24,
                    "The cached material is unchanged by live tint");
        }
        assertEquals(0xFF252525, SignArtMaterial.artVertexTint(false, null));
        assertEquals(
                0xFF252525,
                SignArtMaterial.artVertexTint(false, 0),
                "Redstone off keeps its existing appearance");
        assertEquals(0, new SignArtMaterial(art, false, 4).specularAbgr(0) >>> 24);
    }

    @Test
    void paintedPixelsCarryTheirOwnLabPbrEmissionAndCorrectColours() throws Exception {
        int[] pixels = new int[1024];
        pixels[0] = 0xFFFF0000;
        pixels[1] = 0xFF00FF00;
        pixels[2] = 0xFF0000FF;
        pixels[3] = 0xFFFFFFFF;
        pixels[4] = 0xFF202020;
        var material = new SignArtMaterial(new SignArt(pixels), true);
        assertEquals(0xFF0000FF, material.albedoAbgr(0));
        assertEquals(0xFF00FF00, material.albedoAbgr(1));
        assertEquals(0xFFFF0000, material.albedoAbgr(2));
        for (int i = 0; i < 5; i++) assertEquals(248, material.specularAbgr(i) >>> 24);
        assertEquals(0, material.albedoAbgr(5));
        assertEquals(
                0, material.specularAbgr(5) >>> 24, "Transparent pixels must not produce bloom");
        var off = new SignArtMaterial(material.art(), false);
        for (int i = 0; i < 1024; i++)
            assertEquals(0, off.specularAbgr(i) >>> 24, "Redstone blackout removes emission");
        assertNotEquals(material, off, "Lit and off textures must not share a cached material");
        var directory = java.nio.file.Path.of("build/art-material-fixture");
        java.nio.file.Files.createDirectories(directory);
        for (String name : new String[] {"albedo", "lit-specular", "off-specular"}) {
            var bytes = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 1024; i++)
                bytes.putInt(
                        name.equals("albedo")
                                ? material.albedoAbgr(i)
                                : name.equals("lit-specular")
                                        ? material.specularAbgr(i)
                                        : off.specularAbgr(i));
            java.nio.file.Files.write(directory.resolve(name + ".rgba8"), bytes.array());
        }
        for (int glow = 0; glow <= 4; glow++) {
            var profile = new SignArtMaterial(material.art(), true, glow);
            var bytes = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 1024; i++) {
                assertEquals(
                        material.albedoAbgr(i),
                        profile.albedoAbgr(i),
                        "Glow does not repaint the pixel art");
                bytes.putInt(profile.specularAbgr(i));
            }
            java.nio.file.Files.write(
                    directory.resolve("profile-" + glow + "-specular.rgba8"), bytes.array());
        }
    }
}
