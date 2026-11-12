// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.noopsnet.neon.NeonBlock;
import net.noopsnet.neon.NeonModelData;

import java.util.*;

/** Retexture only luminous quads; material maps remain ordinary atlas LabPBR textures. */
public final class GlowModel extends BakedModelWrapper<BakedModel> {
    private final Map<String, TextureAtlasSprite[]> profiles;
    private final Map<BakedQuad, BakedQuad[]> cache =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public GlowModel(BakedModel model, Map<String, TextureAtlasSprite[]> profiles) {
        super(model);
        this.profiles = profiles;
    }

    @Override
    public List<BakedQuad> getQuads(
            BlockState state,
            Direction side,
            RandomSource random,
            ModelData data,
            RenderType type) {
        var quads = super.getQuads(state, side, random, data, type);
        Integer glow = data.get(NeonModelData.GLOW);
        if (glow == null || glow == 4 || state == null || !state.getValue(NeonBlock.LIT))
            return quads;
        return quads.stream().map(q -> retexture(q, glow)).toList();
    }

    private BakedQuad retexture(BakedQuad quad, int glow) {
        if (!quad.isTinted()) return quad;
        String name = quad.getSprite().contents().name().getPath();
        String kind = name.substring(name.lastIndexOf('_') + 1);
        var sprites = profiles.get(kind);
        if (sprites == null) return quad;
        synchronized (cache) {
            BakedQuad[] levels = cache.computeIfAbsent(quad, q -> new BakedQuad[4]);
            if (levels[glow] != null) return levels[glow];
            var old = quad.getSprite();
            var sprite = sprites[glow];
            int[] vertices = quad.getVertices().clone();
            for (int i = 0; i < 4; i++) {
                int uv = i * IQuadTransformer.STRIDE + IQuadTransformer.UV0;
                float u =
                        (Float.intBitsToFloat(vertices[uv]) - old.getU0())
                                / (old.getU1() - old.getU0());
                float v =
                        (Float.intBitsToFloat(vertices[uv + 1]) - old.getV0())
                                / (old.getV1() - old.getV0());
                vertices[uv] =
                        Float.floatToRawIntBits(
                                sprite.getU0() + u * (sprite.getU1() - sprite.getU0()));
                vertices[uv + 1] =
                        Float.floatToRawIntBits(
                                sprite.getV0() + v * (sprite.getV1() - sprite.getV0()));
                if (glow == 0) vertices[i * IQuadTransformer.STRIDE + IQuadTransformer.UV2] = 0;
            }
            return levels[glow] =
                    new BakedQuad(
                            vertices,
                            quad.getTintIndex(),
                            quad.getDirection(),
                            sprite,
                            glow == 0 || quad.isShade(),
                            glow == 0 || quad.hasAmbientOcclusion());
        }
    }
}
