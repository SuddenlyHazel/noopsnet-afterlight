// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** Check actual mapped NeoForge/Minecraft hook ABIs, not a guessed event surface. */
class SnapshotHooksTest {
    private static class UnknownNeonSubclass extends net.noopsnet.neon.NeonBlock {
        UnknownNeonSubclass() {
            super(
                    "strip",
                    0,
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                            .dynamicShape());
        }
    }

    @Test
    void dynamicExemptionDoesNotAdmitUnknownSubclasses() {
        assertTrue(SnapshotReusePolicy.knownStableDynamicType(net.noopsnet.neon.NeonBlock.class));
        assertTrue(
                SnapshotReusePolicy.knownStableDynamicType(
                        net.noopsnet.neon.ConnectedNeonBlock.class));
        assertFalse(SnapshotReusePolicy.knownStableDynamicType(UnknownNeonSubclass.class));
        assertFalse(
                SnapshotReusePolicy.knownStableDynamicType(
                        net.minecraft.world.level.block.Block.class));
    }

    private static ClassNode type(String name) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(name).accept(node, 0);
        return node;
    }

    private static ClassNode mixin(String name) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(
                        Files.readAllBytes(
                                Path.of(
                                        System.getProperty("afterlight.clientClasses"),
                                        "net/noopsnet/neon/client/mixin/" + name + ".class")))
                .accept(node, 0);
        return node;
    }

    @Test
    void everyRequiredHookMatchesTheActualDescriptorAndMarkerIsPresent() throws Exception {
        int callbacks = 0;
        for (var pair :
                Map.of(
                                "LightingChunkMixin",
                                "net.minecraft.world.level.chunk.LevelChunk",
                                "LightingChunkSourceMixin",
                                "net.minecraft.client.multiplayer.ClientChunkCache")
                        .entrySet()) {
            ClassNode mixin = mixin(pair.getKey()), target = type(pair.getValue());
            assertTrue(
                    mixin.interfaces.stream()
                            .anyMatch(i -> i.contains("ClientLightingSnapshots$Tracked")));
            for (MethodNode callback : mixin.methods) {
                if (!callback.name.startsWith("afterlight$")) continue;
                callbacks++;
                AnnotationNode injection =
                        callback.visibleAnnotations.stream()
                                .filter(a -> a.desc.endsWith("/Inject;"))
                                .findFirst()
                                .orElseThrow();
                int index = injection.values.indexOf("method");
                String selector = ((List<?>) injection.values.get(index + 1)).getFirst().toString();
                String name = selector.split("\\(", 2)[0];
                MethodNode original =
                        target.methods.stream()
                                .filter(m -> m.name.equals(name))
                                .findFirst()
                                .orElseThrow();
                String arguments = original.desc.substring(0, original.desc.indexOf(')'));
                String info =
                        original.desc.endsWith(")V")
                                ? "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
                                : "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
                assertEquals(arguments + info + ")V", callback.desc, selector);
            }
        }
        assertEquals(6, callbacks);
        String config = Files.readString(Path.of("src/main/resources/noopsnet-neon.mixins.json"));
        assertTrue(
                config.contains("LightingChunkMixin")
                        && config.contains("LightingChunkSourceMixin")
                        && config.contains("\"defaultRequire\": 1"));
    }

    @Test
    void lightNotificationHappensAfterVisibleDataIsPublished() throws Exception {
        MethodNode swap =
                type("net.minecraft.world.level.lighting.LayerLightSectionStorage").methods.stream()
                        .filter(m -> m.name.equals("swapSectionMap"))
                        .findFirst()
                        .orElseThrow();
        int index = 0, publish = -1, notify = -1;
        for (var instruction : swap.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("visibleSectionData")) publish = index;
            if (instruction instanceof MethodInsnNode call && call.name.equals("onLightUpdate"))
                notify = index;
            index++;
        }
        assertTrue(
                publish >= 0 && notify > publish,
                "Dirty notification must follow visible light map replacement");
    }

    @Test
    void successfulBlockMutationReturnsTheOldStateAndUnchangedReturnsNull() throws Exception {
        MethodNode set =
                type("net.minecraft.world.level.chunk.LevelChunk").methods.stream()
                        .filter(m -> m.name.equals("setBlockState"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(set.instructions.size() > 0);
        boolean sectionWrite = false, nullReturn = false, objectReturn = false;
        for (var instruction : set.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.endsWith("/LevelChunkSection")
                    && call.name.equals("setBlockState")) sectionWrite = true;
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                var previous = instruction.getPrevious();
                while (previous.getOpcode() < 0) previous = previous.getPrevious();
                if (previous.getOpcode() == Opcodes.ACONST_NULL) nullReturn = true;
                else objectReturn = true;
            }
        }
        assertTrue(sectionWrite && nullReturn && objectReturn);
    }
}
