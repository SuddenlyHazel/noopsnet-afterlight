// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import net.noopsnet.neon.SignArtMaterial;

/** Fully initialized before registration/upload, including any Iris material lookup. */
public final class SignArtTexture extends AbstractTexture {
    public final SignArtMaterial material;
    public SignArtTexture(SignArtMaterial material) { this.material=material; }
    @Override public void load(ResourceManager resources) {
        try (NativeImage image=new NativeImage(32,32,false)) {
            for(int i=0;i<1024;i++)image.setPixelRGBA(i%32,i/32,material.albedoAbgr(i));
            TextureUtil.prepareImage(getId(),32,32);
            setFilter(false,false);
            image.upload(0,0,0,false);
        }
    }
    @Override public void close() { releaseId(); }
}
