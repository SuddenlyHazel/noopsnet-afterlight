// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client.mixin;

import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.noopsnet.neon.client.AfterlightLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.samplers.IrisSamplers", remap = false)
public abstract class IrisSamplerMixin {
    @Inject(method = "addLevelSamplers", at = @At("TAIL"), remap = false)
    private static void afterlight$level(SamplerHolder samplers, net.irisshaders.iris.pipeline.WorldRenderingPipeline pipeline,
            net.minecraft.client.renderer.texture.AbstractTexture white, boolean texture, boolean lightmap, boolean overlay, CallbackInfo ci) {
        samplers.addDynamicSampler(TextureType.TEXTURE_3D, AfterlightLighting::texture, null, "afterlightLightVolume");
        samplers.addDynamicSampler(TextureType.TEXTURE_3D, AfterlightLighting::previousTexture, null, "afterlightPreviousVolume");
    }
    @Inject(method = "addCompositeSamplers", at = @At("TAIL"), remap = false)
    private static void afterlight$composite(SamplerHolder samplers, net.irisshaders.iris.targets.RenderTargets targets, CallbackInfo ci) {
        samplers.addDynamicSampler(TextureType.TEXTURE_3D, AfterlightLighting::texture, null, "afterlightLightVolume");
        samplers.addDynamicSampler(TextureType.TEXTURE_3D, AfterlightLighting::previousTexture, null, "afterlightPreviousVolume");
    }
}
