// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

public final class PaletteItem extends Item {
    public static final int DEFAULT_COLOR = 0xFFA743;

    public static int mode(ItemStack stack) {
        int value =
                stack.getOrDefault(
                                DataComponents.CUSTOM_DATA,
                                net.minecraft.world.item.component.CustomData.EMPTY)
                        .copyTag()
                        .getInt("afterlight_apply");
        return value >= 0 && value <= 2 ? value : 0;
    }

    public static void configure(ItemStack stack, LightSettings settings, int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Invalid apply mode");
        settings.put(stack);
        var tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.putInt("afterlight_apply", mode);
        stack.set(
                DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
    }

    public PaletteItem(Properties properties) {
        super(properties);
    }

    public static int color(ItemStack stack) {
        return stack.getOrDefault(
                                DataComponents.DYED_COLOR, new DyedItemColor(DEFAULT_COLOR, false))
                        .rgb()
                & 0xFFFFFF;
    }

    public static void setColor(ItemStack stack, int rgb) {
        if (rgb < 0 || rgb > 0xFFFFFF)
            throw new IllegalArgumentException("RGB outside 24-bit range");
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb, false));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(
                    new SimpleMenuProvider(
                            (id, inventory, user) ->
                                    new PaletteMenu(
                                            id,
                                            inventory,
                                            hand,
                                            color(stack),
                                            LightSettings.get(stack),
                                            mode(stack)),
                            Component.translatable("noopsnet_neon.palette.title")),
                    data -> {
                        data.writeEnum(hand);
                        data.writeVarInt(color(stack));
                        var settings = LightSettings.get(stack);
                        data.writeByte(settings.glow());
                        data.writeByte(settings.reach());
                        data.writeBoolean(settings.joining());
                        data.writeByte(mode(stack));
                    });
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Actual painting uses vanilla item-on-block interaction, so protection mods can cancel it. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null
                || player.isSpectator()
                || !player.mayBuild()
                || !player.mayUseItemAt(
                        context.getClickedPos(), context.getClickedFace(), context.getItemInHand()))
            return InteractionResult.FAIL;
        if (!(context.getLevel().getBlockEntity(context.getClickedPos())
                instanceof NeonBlockEntity light)) return InteractionResult.PASS;
        if (!context.getLevel().isClientSide) {
            if (player.isShiftKeyDown()) {
                setColor(context.getItemInHand(), light.color());
                configure(context.getItemInHand(), light.settings(), mode(context.getItemInHand()));
                player.displayClientMessage(
                        Component.translatable(
                                "noopsnet_neon.palette.sampled",
                                String.format("#%06X", light.color())),
                        true);
            } else {
                if (mode(context.getItemInHand()) != 2)
                    light.setColor(color(context.getItemInHand()));
                if (mode(context.getItemInHand()) != 1)
                    light.applySettings(LightSettings.get(context.getItemInHand()));
            }
            player.getInventory().setChanged();
            if (player instanceof ServerPlayer serverPlayer)
                serverPlayer.inventoryMenu.broadcastChanges();
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(
                Component.literal(String.format("#%06X", color(stack)))
                        .withStyle(ChatFormatting.GOLD));
        var settings = LightSettings.get(stack);
        lines.add(
                Component.literal(
                                "Glow "
                                        + settings.glow() * 25
                                        + "% · Light "
                                        + (settings.reach() < 0 ? "Auto" : settings.reach())
                                        + " · "
                                        + (settings.joining() ? "Auto join" : "Fixed"))
                        .withStyle(ChatFormatting.GRAY));
        lines.add(
                Component.literal(
                                switch (mode(stack)) {
                                    case 1 -> "Applies colour only";
                                    case 2 -> "Applies lighting only";
                                    default -> "Applies colour and lighting";
                                })
                        .withStyle(ChatFormatting.GRAY));
        lines.add(
                Component.translatable("noopsnet_neon.palette.help")
                        .withStyle(ChatFormatting.GRAY));
    }
}
