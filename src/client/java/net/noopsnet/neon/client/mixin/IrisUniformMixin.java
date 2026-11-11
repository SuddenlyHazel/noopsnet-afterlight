// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client.mixin;

import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.noopsnet.neon.client.AfterlightLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class IrisUniformMixin {
    @Inject(method = "addNonDynamicUniforms", at = @At("TAIL"), remap = false)
    private static void afterlight$uniforms(UniformHolder uniforms, net.irisshaders.iris.shaderpack.IdMap ids,
            net.irisshaders.iris.shaderpack.properties.PackDirectives directives,
            net.irisshaders.iris.uniforms.FrameUpdateNotifier notifier, CallbackInfo ci) {
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "afterlightVolumeOrigin", AfterlightLighting::origin);
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "afterlightVolumeSize", AfterlightLighting::size);
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "afterlightPreviousOrigin", AfterlightLighting::previousOrigin);
        uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "afterlightPreviousSize", AfterlightLighting::previousSize);
        uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "afterlightVolumeBlend", (java.util.function.DoubleSupplier) AfterlightLighting::blend);
    }
}
