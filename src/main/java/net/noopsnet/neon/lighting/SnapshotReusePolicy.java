// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.lighting;

import net.minecraft.world.level.block.state.BlockState;
import net.noopsnet.neon.ConnectedNeonBlock;
import net.noopsnet.neon.NeonBlock;

/** Classifications whose inputs are covered by the block/chunk/light dirty hooks. */
public final class SnapshotReusePolicy {
    public static boolean reusable(BlockState state) {
        var block = state.getBlock();
        if (!block.hasDynamicShape()) return true;
        // These exact classes opt into dynamicShape for detailed selection geometry.
        // Their RGB light blocking and fog full-block classification are fixed for
        // a block state, including every joining mask. Colour is a separate input.
        // An arbitrary subclass may override shape or opacity using untracked data.
        return knownStableDynamicType(block.getClass());
    }

    static boolean knownStableDynamicType(Class<?> type) {
        return type == NeonBlock.class || type == ConnectedNeonBlock.class;
    }

    private SnapshotReusePolicy() {}
}
