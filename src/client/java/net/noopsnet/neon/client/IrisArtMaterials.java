// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import com.mojang.blaze3d.platform.NativeImage;

import net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Optional Iris integration. Only our texture class gets this loader. */
public final class IrisArtMaterials {
    private IrisArtMaterials() {}

    public static void register() {
        PBRTextureLoaderRegistry.INSTANCE.register(
                SignArtTexture.class,
                (texture, resources, consumer) -> {
                    NativeImage specular = new NativeImage(32, 32, false);
                    for (int i = 0; i < 1024; i++)
                        specular.setPixelRGBA(i % 32, i / 32, texture.material.specularAbgr(i));
                    consumer.acceptSpecularTexture(new DynamicTexture(specular));
                });
    }
}
