// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignArtMaterialTest {
    @Test void paintedPixelsCarryTheirOwnLabPbrEmissionAndCorrectColours() throws Exception {
        int[] pixels=new int[1024];
        pixels[0]=0xFFFF0000;pixels[1]=0xFF00FF00;pixels[2]=0xFF0000FF;
        pixels[3]=0xFFFFFFFF;pixels[4]=0xFF202020;
        var material=new SignArtMaterial(new SignArt(pixels),true);
        assertEquals(0xFF0000FF,material.albedoAbgr(0));
        assertEquals(0xFF00FF00,material.albedoAbgr(1));
        assertEquals(0xFFFF0000,material.albedoAbgr(2));
        for(int i=0;i<5;i++)assertEquals(248,material.specularAbgr(i)>>>24);
        assertEquals(0,material.albedoAbgr(5));
        assertEquals(0,material.specularAbgr(5)>>>24,"Transparent pixels must not produce bloom");
        var off=new SignArtMaterial(material.art(),false);
        for(int i=0;i<1024;i++)assertEquals(0,off.specularAbgr(i)>>>24,"Redstone blackout removes emission");
        assertNotEquals(material,off,"Lit and off textures must not share a cached material");
        var directory=java.nio.file.Path.of("build/art-material-fixture");java.nio.file.Files.createDirectories(directory);
        for(String name:new String[]{"albedo","lit-specular","off-specular"}) {
            var bytes=java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<1024;i++)bytes.putInt(name.equals("albedo")?material.albedoAbgr(i):name.equals("lit-specular")?material.specularAbgr(i):off.specularAbgr(i));
            java.nio.file.Files.write(directory.resolve(name+".rgba8"),bytes.array());
        }
        for(int glow=0;glow<=4;glow++) {
            var profile=new SignArtMaterial(material.art(),true,glow);
            var bytes=java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<1024;i++) {
                assertEquals(material.albedoAbgr(i),profile.albedoAbgr(i),"Glow does not repaint the pixel art");
                bytes.putInt(profile.specularAbgr(i));
            }
            java.nio.file.Files.write(directory.resolve("profile-"+glow+"-specular.rgba8"),bytes.array());
        }
    }
}
