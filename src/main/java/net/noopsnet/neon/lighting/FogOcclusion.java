// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Room-scale barriers. Small fixtures must not punch block-sized holes in mist. */
public final class FogOcclusion {
    public static boolean blocks(BlockState state, BlockGetter world, BlockPos pos) {
        if (!state.getFluidState().isEmpty()) return true;
        if (state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN)) return false;
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock)
            return true;
        // Full glass blocks seal a room even though they transmit light. A strip,
        // lantern, stair or other partial decoration leaves air around its mesh;
        // the rendered depth buffer clips fog at the actual visible surface.
        return state.isCollisionShapeFullBlock(world, pos);
    }

    private FogOcclusion() {}
}
