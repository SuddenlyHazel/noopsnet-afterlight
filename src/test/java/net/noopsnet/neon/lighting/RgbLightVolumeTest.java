// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RgbLightVolumeTest {
    private static int cell(int n, int x, int y, int z) { return (x + n * y + n * n * z) * 4; }
    @Test void actualRgbColorsMixAndRemainIndependentOfBlockVariant() {
        int n = 20;
        var red = new RgbLightVolume.Source(8, 10, 10, 0xFF0000, 15);
        var blue = new RgbLightVolume.Source(12, 10, 10, 0x0000FF, 15);
        var volume = RgbLightVolume.build(n, 0, 0, 0, new byte[n*n*n], List.of(red, blue));
        int p = cell(n,10,10,10);
        assertEquals(volume.rgba()[p], volume.rgba()[p+2], 1e-6);
        assertEquals(0, volume.rgba()[p+1]);
        assertTrue(volume.rgba()[cell(n,8,10,10)] > volume.rgba()[cell(n,8,10,10)+2]);
        var repainted = RgbLightVolume.build(n, 0, 0, 0, new byte[n*n*n],
            List.of(new RgbLightVolume.Source(8,10,10,0x00FF00,15)));
        assertEquals(0, repainted.rgba()[p]); assertTrue(repainted.rgba()[p+1] > 0);
    }
    @Test void opaqueWallBlocksSpillAndDoorwayAllowsItAroundCorners() {
        int n = 24; byte[] opacity = new byte[n*n*n];
        for (int y=0;y<n;y++) for (int z=0;z<n;z++) opacity[cell(n,12,y,z)/4] = 15;
        var sources = List.of(new RgbLightVolume.Source(10,12,12,0xFF00FF,15));
        var closed = RgbLightVolume.build(n,0,0,0,opacity,sources);
        assertEquals(0,closed.rgba()[cell(n,14,12,12)+3]);
        opacity[cell(n,12,12,14)/4] = 0;
        var open = RgbLightVolume.build(n,0,0,0,opacity,sources);
        assertTrue(open.rgba()[cell(n,14,12,12)+3] > 0);
        assertTrue(open.rgba()[cell(n,14,12,12)+3] < open.rgba()[cell(n,11,12,12)+3]);
    }
    @Test void unloadedVolumeIsOpaqueAndBlackSuppressesItsWhiteLightWithoutRadiance() {
        int n=16; byte[] opacity=new byte[n*n*n]; java.util.Arrays.fill(opacity,(byte)15);
        var volume=RgbLightVolume.build(n,0,0,0,opacity,List.of(new RgbLightVolume.Source(8,8,8,0xFFFFFF,15)));
        assertEquals(-1,volume.rgba()[cell(n,9,8,8)+3],"Opaque cells must not dilute adjacent air during filtering");
        float[] empty=RgbLightVolume.build(n,0,0,0,opacity,List.of()).rgba();
        for (int i=0;i<empty.length;i++) assertEquals(i%4==3?-1:0,empty[i]);
        var black=RgbLightVolume.build(n,0,0,0,opacity,List.of(new RgbLightVolume.Source(8,8,8,0,15)));
        int seed=cell(n,8,8,8);
        assertEquals(0,black.rgba()[seed]);assertEquals(0,black.rgba()[seed+1]);assertEquals(0,black.rgba()[seed+2]);
        assertTrue(black.rgba()[seed+3]>0,"Coverage removes the matching vanilla white contribution");
    }
    @Test void opacityMarkerDistinguishesSolidGeometryFromDarkAirAndOpaqueSources() {
        int n=16; byte[] opacity=new byte[n*n*n];
        opacity[cell(n,8,8,8)/4]=15;
        var empty=RgbLightVolume.build(n,0,0,0,opacity,List.of());
        assertEquals(-1,empty.rgba()[cell(n,8,8,8)+3]);
        assertEquals(0,empty.rgba()[cell(n,9,8,8)+3],"Dark air remains a valid interpolation sample");
        var source=RgbLightVolume.build(n,0,0,0,opacity,List.of(new RgbLightVolume.Source(8,8,8,0x00FF00,15)));
        assertEquals(1,source.rgba()[cell(n,8,8,8)+3],"An opaque emitting block still contributes light");
        assertTrue(source.rgba()[cell(n,9,8,8)+1]>0,"Its emitted light can leave the source voxel");
    }
    @Test void worldOriginAndGammaAreHandledBeforeBlending() {
        int n=16;
        var v=RgbLightVolume.build(n,-30000000,-64,29999980,new byte[n*n*n],
            List.of(new RgbLightVolume.Source(-29999992,-56,29999988,0x808080,15)));
        assertEquals(.21586f,v.rgba()[cell(n,8,8,8)],1e-4);
        assertEquals(0,v.rgba()[cell(n,0,0,0)]);
        assertThrows(IllegalArgumentException.class,()->RgbLightVolume.build(100,0,0,0,new byte[1],List.of()));
    }
}
