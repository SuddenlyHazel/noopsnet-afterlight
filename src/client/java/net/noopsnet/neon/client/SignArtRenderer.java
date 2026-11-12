// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.noopsnet.neon.*;

import java.util.LinkedHashMap;

/** Only the sign face is dynamic. A bounded shared texture cache avoids per-sign allocations. */
public final class SignArtRenderer implements BlockEntityRenderer<NeonBlockEntity> {
    private static final LinkedHashMap<SignArtMaterial, ResourceLocation> CACHE =
            new LinkedHashMap<>(32, .75F, true);
    private static long nextTexture;

    public SignArtRenderer(BlockEntityRendererProvider.Context context) {}

    public static void clearCache() {
        var manager = Minecraft.getInstance().getTextureManager();
        CACHE.values().forEach(manager::release);
        CACHE.clear();
    }

    private static ResourceLocation texture(SignArt art, boolean lit, int glow) {
        SignArtMaterial material = new SignArtMaterial(art, lit, glow);
        ResourceLocation id = CACHE.get(material);
        if (id != null) return id;
        var manager = Minecraft.getInstance().getTextureManager();
        id =
                ResourceLocation.fromNamespaceAndPath(
                        NoopsnetNeon.ID, "dynamic/sign_art/" + nextTexture++);
        manager.register(id, new SignArtTexture(material));
        CACHE.put(material, id);
        return id;
    }

    public static void endFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        // Buffered eyes/cutout draws must finish before their textures can be deleted.
        var manager = Minecraft.getInstance().getTextureManager();
        while (CACHE.size() > 256) {
            var oldest = CACHE.entrySet().iterator();
            var entry = oldest.next();
            manager.release(entry.getValue());
            oldest.remove();
        }
    }

    @Override
    public void render(
            NeonBlockEntity sign,
            float partial,
            PoseStack poses,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        if (!sign.editableSign()) return;
        var state = sign.getBlockState();
        var block = (NeonBlock) state.getBlock();
        boolean lit = state.getValue(NeonBlock.LIT);
        int glow = sign.glow();
        String kind = block.bladeSign() ? "metro" : "market";
        var art = sign.art();
        ResourceLocation tex =
                art != null
                        ? texture(art, lit, glow)
                        : ResourceLocation.fromNamespaceAndPath(
                                NoopsnetNeon.ID,
                                "textures/block/"
                                        + (lit && glow < 4
                                                ? kind + "_glow" + glow
                                                : "amber_" + kind + (lit ? "" : "_off"))
                                        + ".png");
        int color =
                art != null
                        ? SignArtMaterial.artVertexTint(lit, sign.liveColorOverride())
                        : 0xFF000000 | sign.color();
        // Iris routes block-entity eyes through gbuffers_block, so use actual
        // LabPBR emission rather than relying on the eyes shader's material ID.
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(tex));
        poses.pushPose();
        poses.translate(.5, 0, .5);
        poses.mulPose(
                Axis.YP.rotationDegrees(
                        switch (state.getValue(NeonBlock.FACING)) {
                            case EAST -> -90;
                            case SOUTH -> 180;
                            case WEST -> 90;
                            default -> 0;
                        }));
        poses.translate(-.5, 0, -.5);
        if (block.bladeSign()) {
            face(
                    out,
                    poses,
                    6.44F,
                    1,
                    1,
                    6.44F,
                    15,
                    11,
                    color,
                    lit && glow > 0 ? LightTexture.FULL_BRIGHT : light,
                    true);
            face(
                    out,
                    poses,
                    9.56F,
                    1,
                    11,
                    9.56F,
                    15,
                    1,
                    color,
                    lit && glow > 0 ? LightTexture.FULL_BRIGHT : light,
                    true);
        } else
            face(
                    out,
                    poses,
                    15,
                    1,
                    11.97F,
                    1,
                    15,
                    11.97F,
                    color,
                    lit && glow > 0 ? LightTexture.FULL_BRIGHT : light,
                    false);
        poses.popPose();
    }

    private static void face(
            VertexConsumer out,
            PoseStack poses,
            float x,
            float y,
            float z,
            float X,
            float Y,
            float Z,
            int color,
            int light,
            boolean side) {
        float nx = side ? (Z > z ? -1 : 1) : 0, nz = side ? 0 : -1;
        vertex(out, poses, x, Y, z, 0, 0, color, light, nx, nz);
        vertex(out, poses, x, y, z, 0, 1, color, light, nx, nz);
        vertex(out, poses, X, y, Z, 1, 1, color, light, nx, nz);
        vertex(out, poses, X, Y, Z, 1, 0, color, light, nx, nz);
    }

    private static void vertex(
            VertexConsumer out,
            PoseStack poses,
            float x,
            float y,
            float z,
            float u,
            float v,
            int color,
            int light,
            float nx,
            float nz) {
        out.addVertex(poses.last().pose(), x / 16, y / 16, z / 16)
                .setColor(color)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(poses.last(), nx, 0, nz);
    }
}
