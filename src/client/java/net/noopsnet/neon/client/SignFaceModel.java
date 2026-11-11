// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import java.util.List;

/** World signs use the editable face renderer; inventory models keep the stock artwork. */
public final class SignFaceModel extends BakedModelWrapper<BakedModel> {
    public SignFaceModel(BakedModel original) { super(original); }
    private static List<BakedQuad> frame(List<BakedQuad> quads) {
        return quads.stream().filter(q->{String path=q.getSprite().contents().name().getPath();
            return !path.contains("_market")&&!path.contains("_metro");}).toList();
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random) {return frame(super.getQuads(state,side,random));}
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random,ModelData data,RenderType type) {return frame(super.getQuads(state,side,random,data,type));}
}
