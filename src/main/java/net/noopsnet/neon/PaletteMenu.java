// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * This menu can only update the palette item bound when it was opened. It never edits a world
 * block.
 */
public final class PaletteMenu extends AbstractContainerMenu {
    public final int initialColor;
    public final LightSettings initialSettings;
    public final int initialMode;
    private final Inventory inventory;
    private final int slot;
    private final ItemStack bound;

    public PaletteMenu(int id, Inventory inventory, InteractionHand hand, int initialColor) {
        this(
                id,
                inventory,
                hand,
                initialColor,
                LightSettings.get(
                        inventory.getItem(
                                hand == InteractionHand.OFF_HAND ? 40 : inventory.selected)),
                PaletteItem.mode(
                        inventory.getItem(
                                hand == InteractionHand.OFF_HAND ? 40 : inventory.selected)));
    }

    public PaletteMenu(
            int id,
            Inventory inventory,
            InteractionHand hand,
            int initialColor,
            LightSettings settings,
            int mode) {
        super(NoopsnetNeon.PALETTE_MENU.get(), id);
        this.inventory = inventory;
        this.slot = hand == InteractionHand.OFF_HAND ? 40 : inventory.selected;
        this.bound = inventory.getItem(slot);
        this.initialColor = initialColor & 0xFFFFFF;
        this.initialSettings = settings;
        this.initialMode = mode;
    }

    @Override
    public boolean stillValid(Player player) {
        return player == inventory.player
                && player.isAlive()
                && !player.isSpectator()
                && inventory.getItem(slot) == bound
                && bound.is(NoopsnetNeon.PALETTE.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int rgb) {
        if (player.level().isClientSide || !stillValid(player) || rgb < 0 || rgb > 0xFFFFFF)
            return false;
        PaletteItem.setColor(bound, rgb);
        inventory.setChanged();
        // This menu has no inventory slots. Send the bound inventory slot
        // directly so the offhand also updates while this menu is still open.
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.connection.send(
                    new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                            -2, 0, slot, bound.copy()));
        }
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    public boolean configure(Player player, int rgb, LightSettings settings, int mode) {
        if (player.level().isClientSide
                || !stillValid(player)
                || rgb < 0
                || rgb > 0xFFFFFF
                || mode < 0
                || mode > 2) return false;
        PaletteItem.configure(bound, settings, mode);
        return clickMenuButton(player, rgb);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }
}
