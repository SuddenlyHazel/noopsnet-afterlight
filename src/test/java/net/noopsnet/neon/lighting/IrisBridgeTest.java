// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/** Check the actual pinned binary ABI without starting a game client. */
class IrisBridgeTest {
    @Test
    void translucentParticleHookRepairsThePinnedNeoforgeGap() throws Exception {
        String desc =
                "(Lnet/minecraft/client/renderer/LightTexture;Lnet/"
                        + "minecraft/client/Camera;FLnet/minecraft/client/"
                        + "renderer/culling/Frustum;Ljava/util/function/Predicate;"
                        + ")V";
        try (ZipFile iris = new ZipFile(System.getProperty("afterlight.irisJar"))) {
            String config =
                    new String(
                            iris.getInputStream(iris.getEntry("mixins.iris.fantastic.json"))
                                    .readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(
                    config.contains("MixinParticleEngine"),
                    "Iris now registers its hook; remove/review our redirect before updating the"
                            + " pinned dependency");
            ClassNode access = new ClassNode();
            new ClassReader(
                            iris.getInputStream(
                                    iris.getEntry(
                                            "net/irisshaders/iris/pipeline/programs/ShaderAccess.class")))
                    .accept(access, 0);
            MethodNode selector =
                    access.methods.stream()
                            .filter(m -> m.name.equals("getParticleTranslucentShader"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    java.util.stream.StreamSupport.stream(
                                    selector.instructions.spliterator(), false)
                            .anyMatch(
                                    i ->
                                            i instanceof FieldInsnNode f
                                                    && f.name.equals("PARTICLES_TRANS")));
            ClassNode engine = new ClassNode();
            new ClassReader("net.minecraft.client.particle.ParticleEngine").accept(engine, 0);
            MethodNode render =
                    engine.methods.stream()
                            .filter(m -> m.name.equals("render") && m.desc.equals(desc))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    1,
                    java.util.stream.StreamSupport.stream(render.instructions.spliterator(), false)
                            .filter(
                                    i ->
                                            i instanceof MethodInsnNode call
                                                    && call.owner.equals(
                                                            "com/mojang/blaze3d/systems/RenderSystem")
                                                    && call.name.equals("setShader"))
                            .count());
            ClassNode mixin = new ClassNode();
            new ClassReader(
                            Files.readAllBytes(
                                    Path.of(
                                            System.getProperty("afterlight.clientClasses"),
                                            "net/noopsnet/neon/client/mixin/IrisParticleMixin.class")))
                    .accept(mixin, 0);
            MethodNode redirect =
                    mixin.methods.stream()
                            .filter(m -> m.name.equals("afterlight$particleShader"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("(Ljava/util/function/Supplier;" + desc.substring(1), redirect.desc);
            assertTrue(
                    redirect.visibleAnnotations.stream()
                            .filter(a -> a.desc.endsWith("/Redirect;"))
                            .anyMatch(a -> a.values.toString().contains("render" + desc)));
            String ours = Files.readString(Path.of("src/main/resources/noopsnet-neon.mixins.json"));
            assertTrue(ours.contains("IrisParticleMixin") && ours.contains("IrisCompatPlugin"));
        }
    }

    @Test
    void particleHookIsDisabledWhenIrisIsAbsent() throws Exception {
        try (var loader =
                new java.net.URLClassLoader(
                        new java.net.URL[] {
                            Path.of(System.getProperty("afterlight.clientClasses")).toUri().toURL()
                        },
                        getClass().getClassLoader())) {
            var plugin =
                    Class.forName("net.noopsnet.neon.client.mixin.IrisCompatPlugin", true, loader)
                            .getConstructor()
                            .newInstance();
            var apply = plugin.getClass().getMethod("shouldApplyMixin", String.class, String.class);
            assertEquals(
                    false,
                    apply.invoke(
                            plugin,
                            "net.minecraft.client.particle.ParticleEngine",
                            "net.noopsnet.neon.client.mixin.IrisParticleMixin"));
        }
    }

    @Test
    void irisRoutesEyesThroughBlockShaderAndSupportsOurExplicitPbrLoader() throws Exception {
        try (ZipFile iris = new ZipFile(System.getProperty("afterlight.irisJar"))) {
            ClassNode renderer = new ClassNode();
            new ClassReader(
                            iris.getInputStream(
                                    iris.getEntry(
                                            "net/irisshaders/iris/mixin/MixinGameRenderer.class")))
                    .accept(renderer, 0);
            MethodNode eyes =
                    renderer.methods.stream()
                            .filter(m -> m.name.equals("iris$overrideEntityEyesShader"))
                            .findFirst()
                            .orElseThrow();
            boolean routesToBlock = false;
            for (var insn : eyes.instructions)
                if (insn instanceof FieldInsnNode field && field.name.equals("BLOCK_ENTITY"))
                    routesToBlock = true;
            assertTrue(
                    routesToBlock,
                    "Eyes is not an emissive guarantee inside a block-entity renderer");
            ClassNode registry = new ClassNode();
            new ClassReader(
                            iris.getInputStream(
                                    iris.getEntry(
                                            "net/irisshaders/iris/pbr/loader/PBRTextureLoaderRegistry.class")))
                    .accept(registry, 0);
            assertTrue(
                    registry.methods.stream()
                            .anyMatch(
                                    m ->
                                            m.name.equals("register")
                                                    && m.desc.equals(
                                                            "(Ljava/lang/Class;Lnet/irisshaders/iris/pbr/loader/"
                                                                + "PBRTextureLoader;)V")));
            ClassNode consumer = new ClassNode();
            new ClassReader(
                            iris.getInputStream(
                                    iris.getEntry(
                                            "net/irisshaders/iris/pbr/loader/"
                                                    + "PBRTextureLoader$PBRTextureConsumer.class")))
                    .accept(consumer, 0);
            assertTrue(
                    consumer.methods.stream()
                            .anyMatch(
                                    m ->
                                            m.name.equals("acceptSpecularTexture")
                                                    && m.desc.equals(
                                                            "(Lnet/minecraft/client/renderer/texture/"
                                                                + "AbstractTexture;)V")));
        }
    }

    @Test
    void mixinCallbacksMatchPinnedIrisAndEveryInjectionTargetExists() throws Exception {
        try (ZipFile iris = new ZipFile(System.getProperty("afterlight.irisJar"))) {
            int callbacks = 0;
            for (String name : List.of("IrisSamplerMixin", "IrisUniformMixin")) {
                ClassNode mixin = new ClassNode();
                new ClassReader(
                                Files.readAllBytes(
                                        Path.of(
                                                System.getProperty("afterlight.clientClasses"),
                                                "net/noopsnet/neon/client/mixin/"
                                                        + name
                                                        + ".class")))
                        .accept(mixin, 0);
                String target =
                        name.equals("IrisSamplerMixin")
                                ? "net/irisshaders/iris/samplers/IrisSamplers"
                                : "net/irisshaders/iris/uniforms/CommonUniforms";
                ClassNode upstream = new ClassNode();
                new ClassReader(iris.getInputStream(iris.getEntry(target + ".class")))
                        .accept(upstream, 0);
                for (MethodNode method : mixin.methods) {
                    if (!method.name.startsWith("afterlight$")) continue;
                    callbacks++;
                    String injection =
                            method.name.endsWith("level")
                                    ? "addLevelSamplers"
                                    : method.name.endsWith("composite")
                                            ? "addCompositeSamplers"
                                            : "addNonDynamicUniforms";
                    MethodNode original =
                            upstream.methods.stream()
                                    .filter(m -> m.name.equals(injection))
                                    .findFirst()
                                    .orElseThrow();
                    String expected =
                            original.desc.replace(
                                    ")V",
                                    "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
                    assertEquals(expected, method.desc, injection);
                    assertTrue((method.access & Opcodes.ACC_STATIC) != 0);
                }
            }
            assertEquals(3, callbacks);
        }
    }
}
