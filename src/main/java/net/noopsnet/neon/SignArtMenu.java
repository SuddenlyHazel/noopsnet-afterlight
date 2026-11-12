// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.TriState;

/** A draft belongs to one sign instance and revision. Saving rechecks access. */
public final class SignArtMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    public final SignArt initialArt;
    public final int initialColor;
    public final boolean blade;
    public final DataSlot status = DataSlot.standalone();
    private final Player owner;
    private final NeonBlockEntity bound;
    private final int revision;
    private long lastAttempt = Long.MIN_VALUE / 2;

    public SignArtMenu(
            int id,
            Inventory inventory,
            BlockPos pos,
            SignArt initialArt,
            int color,
            boolean blade) {
        super(NoopsnetNeon.SIGN_ART_MENU.get(), id);
        this.owner = inventory.player;
        this.pos = pos.immutable();
        this.initialArt = initialArt;
        this.initialColor = color;
        this.blade = blade;
        bound = owner.level().getBlockEntity(pos) instanceof NeonBlockEntity entity ? entity : null;
        revision = bound == null ? 0 : bound.artRevision();
        addDataSlot(status);
    }

    @Override
    public boolean stillValid(Player player) {
        return player == owner
                && player.isAlive()
                && !player.isSpectator()
                && player.mayBuild()
                && player.getMainHandItem().isEmpty()
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64
                && bound != null
                && !bound.isRemoved()
                && player.level() == bound.getLevel()
                && player.level().getBlockEntity(pos) == bound
                && bound.editableSign();
    }

    public boolean save(Player player, SignArt art) {
        if (player.level().isClientSide || !stillValid(player)) {
            status.set(2);
            return false;
        }
        long now = player.level().getGameTime();
        if (now - lastAttempt < 4) return false;
        lastAttempt = now;
        // Run the same right-click protection hooks again: claims may change while editing.
        var event =
                CommonHooks.onRightClickBlock(
                        player,
                        InteractionHand.MAIN_HAND,
                        pos,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        if (event.isCanceled() || event.getUseBlock() == TriState.FALSE) {
            status.set(2);
            return false;
        }
        if (bound.artRevision() != revision) {
            status.set(3);
            return false;
        }
        bound.setArt(art);
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }
}
