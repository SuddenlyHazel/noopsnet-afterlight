// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.noopsnet.neon.*;

import java.util.*;

/** Neighbour-derived geometry avoids multiplying every topology by every light level. */
public final class ConnectedModel extends BakedModelWrapper<BakedModel> {
    private final BakedModel[][] models;
    private final Map<BakedQuad, BakedQuad[]> rotations = new IdentityHashMap<>();

    public ConnectedModel(BakedModel fixed, BakedModel[][] models) {
        super(fixed);
        this.models = models;
    }

    @Override
    public ModelData getModelData(
            BlockAndTintGetter world, BlockPos pos, BlockState state, ModelData data) {
        return super.getModelData(world, pos, state, data)
                .derive()
                .with(
                        NeonModelData.CONNECTIONS,
                        ((ConnectedNeonBlock) state.getBlock()).connections(state, world, pos))
                .build();
    }

    @Override
    public List<BakedQuad> getQuads(
            BlockState state,
            Direction side,
            RandomSource random,
            ModelData data,
            RenderType type) {
        Integer mask = data.get(NeonModelData.CONNECTIONS);
        if (mask == null || mask == 16 || state == null)
            return super.getQuads(state, side, random, data, type);
        int steps =
                switch (state.getValue(NeonBlock.FACING)) {
                    case EAST -> 1;
                    case SOUTH -> 2;
                    case WEST -> 3;
                    default -> 0;
                };
        Direction local = side;
        for (int i = 0; i < steps && local != null; i++)
            local = Rotation.COUNTERCLOCKWISE_90.rotate(local);
        var quads =
                models[state.getValue(NeonBlock.LIT) ? 1 : 0][mask].getQuads(
                        state, local, random, data, type);
        if (steps == 0) return quads;
        return quads.stream().map(q -> rotate(q, steps)).toList();
    }

    private BakedQuad rotate(BakedQuad quad, int steps) {
        synchronized (rotations) {
            var cached = rotations.computeIfAbsent(quad, q -> new BakedQuad[4]);
            if (cached[steps] != null) return cached[steps];
            int[] vertices = quad.getVertices().clone();
            Direction direction = quad.getDirection();
            for (int turn = 0; turn < steps; turn++) {
                direction = Rotation.CLOCKWISE_90.rotate(direction);
                for (int i = 0; i < 4; i++) {
                    int p = i * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
                    float x = Float.intBitsToFloat(vertices[p]),
                            z = Float.intBitsToFloat(vertices[p + 2]);
                    vertices[p] = Float.floatToRawIntBits(1 - z);
                    vertices[p + 2] = Float.floatToRawIntBits(x);
                    int n = i * IQuadTransformer.STRIDE + IQuadTransformer.NORMAL,
                            packed = vertices[n];
                    int nx = (byte) packed, nz = (byte) (packed >>> 16);
                    vertices[n] = (packed & 0xFF00FF00) | ((-nz) & 255) | ((nx & 255) << 16);
                }
            }
            return cached[steps] =
                    new BakedQuad(
                            vertices,
                            quad.getTintIndex(),
                            direction,
                            quad.getSprite(),
                            quad.isShade(),
                            quad.hasAmbientOcclusion());
        }
    }
}
