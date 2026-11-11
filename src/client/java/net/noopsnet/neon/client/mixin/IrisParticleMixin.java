// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.irisshaders.iris.pipeline.programs.ShaderAccess;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Iris 1.8.14 NeoForge ships its split-particle hook but omits it from its mixin config. */
@Mixin(value=ParticleEngine.class,remap=false)
public abstract class IrisParticleMixin {
    @Redirect(method="render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
        at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V"),remap=false)
    private void afterlight$particleShader(Supplier<ShaderInstance> original,LightTexture light,Camera camera,float partialTick,
            Frustum frustum,Predicate<ParticleRenderType> filter){
        // Preserve vanilla, the opaque pass, and shadow rendering. Iris's accessor
        // falls back to vanilla when no shader pack is active.
        boolean translucent=filter!=null&&!filter.test(ParticleRenderType.PARTICLE_SHEET_OPAQUE)
            &&filter.test(ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT);
        RenderSystem.setShader(translucent&&!ShadowRenderingState.areShadowsCurrentlyBeingRendered()
            ?ShaderAccess::getParticleTranslucentShader:original);
    }
}
