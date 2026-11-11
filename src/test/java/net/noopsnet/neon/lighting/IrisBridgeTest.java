// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/** Check the actual pinned binary ABI without starting a game client. */
class IrisBridgeTest {
    @Test void irisRoutesEyesThroughBlockShaderAndSupportsOurExplicitPbrLoader() throws Exception {
        try (ZipFile iris = new ZipFile(System.getProperty("afterlight.irisJar"))) {
            ClassNode renderer=new ClassNode();
            new ClassReader(iris.getInputStream(iris.getEntry("net/irisshaders/iris/mixin/MixinGameRenderer.class"))).accept(renderer,0);
            MethodNode eyes=renderer.methods.stream().filter(m->m.name.equals("iris$overrideEntityEyesShader")).findFirst().orElseThrow();
            boolean routesToBlock=false;
            for(var insn:eyes.instructions)if(insn instanceof FieldInsnNode field && field.name.equals("BLOCK_ENTITY"))routesToBlock=true;
            assertTrue(routesToBlock,"Eyes is not an emissive guarantee inside a block-entity renderer");
            ClassNode registry=new ClassNode();
            new ClassReader(iris.getInputStream(iris.getEntry("net/irisshaders/iris/pbr/loader/PBRTextureLoaderRegistry.class"))).accept(registry,0);
            assertTrue(registry.methods.stream().anyMatch(m->m.name.equals("register")&&m.desc.equals("(Ljava/lang/Class;Lnet/irisshaders/iris/pbr/loader/PBRTextureLoader;)V")));
            ClassNode consumer=new ClassNode();
            new ClassReader(iris.getInputStream(iris.getEntry("net/irisshaders/iris/pbr/loader/PBRTextureLoader$PBRTextureConsumer.class"))).accept(consumer,0);
            assertTrue(consumer.methods.stream().anyMatch(m->m.name.equals("acceptSpecularTexture")&&m.desc.equals("(Lnet/minecraft/client/renderer/texture/AbstractTexture;)V")));
        }
    }
    @Test void mixinCallbacksMatchPinnedIrisAndEveryInjectionTargetExists() throws Exception {
        try (ZipFile iris = new ZipFile(System.getProperty("afterlight.irisJar"))) {
            int callbacks = 0;
            for (String name : List.of("IrisSamplerMixin", "IrisUniformMixin")) {
                ClassNode mixin = new ClassNode();
                new ClassReader(Files.readAllBytes(Path.of(System.getProperty("afterlight.clientClasses"),
                    "net/noopsnet/neon/client/mixin/" + name + ".class"))).accept(mixin,0);
                String target = name.equals("IrisSamplerMixin") ? "net/irisshaders/iris/samplers/IrisSamplers" : "net/irisshaders/iris/uniforms/CommonUniforms";
                ClassNode upstream = new ClassNode(); new ClassReader(iris.getInputStream(iris.getEntry(target+".class"))).accept(upstream,0);
                for (MethodNode method : mixin.methods) {
                    if (!method.name.startsWith("afterlight$")) continue;
                    callbacks++;
                    String injection = method.name.endsWith("level") ? "addLevelSamplers" : method.name.endsWith("composite") ? "addCompositeSamplers" : "addNonDynamicUniforms";
                    MethodNode original = upstream.methods.stream().filter(m -> m.name.equals(injection)).findFirst().orElseThrow();
                    String expected = original.desc.replace(")V", "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
                    assertEquals(expected,method.desc,injection);
                    assertTrue((method.access & Opcodes.ACC_STATIC) != 0);
                }
            }
            assertEquals(3,callbacks);
        }
    }
}
