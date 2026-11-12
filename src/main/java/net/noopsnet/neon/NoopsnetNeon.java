// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@Mod(NoopsnetNeon.ID)
public final class NoopsnetNeon {
    public static final String ID = "noopsnet_neon";
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    public static final Map<String, DeferredBlock<Block>> CATALOG = new LinkedHashMap<>();
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, ID);
    public static final Supplier<Item> PALETTE =
            ITEMS.register("palette", () -> new PaletteItem(new Item.Properties().stacksTo(1)));
    public static final Supplier<MenuType<PaletteMenu>> PALETTE_MENU =
            MENUS.register(
                    "palette",
                    () ->
                            IMenuTypeExtension.create(
                                    (id, inventory, data) ->
                                            new PaletteMenu(
                                                    id,
                                                    inventory,
                                                    data.readEnum(
                                                            net.minecraft.world.InteractionHand
                                                                    .class),
                                                    data.readVarInt(),
                                                    new LightSettings(
                                                            data.readUnsignedByte(),
                                                            data.readByte(),
                                                            data.readBoolean()),
                                                    data.readUnsignedByte())));
    public static final Supplier<MenuType<SignArtMenu>> SIGN_ART_MENU =
            MENUS.register(
                    "sign_art",
                    () ->
                            IMenuTypeExtension.create(
                                    (id, inventory, data) ->
                                            new SignArtMenu(
                                                    id,
                                                    inventory,
                                                    data.readBlockPos(),
                                                    SignArtPayload.readArt(data),
                                                    data.readInt(),
                                                    data.readBoolean())));
    public static final Supplier<BlockEntityType<NeonBlockEntity>> LIGHT_ENTITY =
            ENTITIES.register(
                    "light",
                    () ->
                            BlockEntityType.Builder.of(
                                            NeonBlockEntity::new,
                                            CATALOG.entrySet().stream()
                                                    .filter(
                                                            entry ->
                                                                    entry.getValue().get()
                                                                            instanceof NeonBlock)
                                                    .map(entry -> entry.getValue().get())
                                                    .toArray(Block[]::new))
                                    .build(null));

    static {
        for (String material : NeonCatalog.MATERIALS) {
            register(
                    material,
                    () ->
                            new Block(
                                    BlockBehaviour.Properties.of()
                                            .strength(1.8F, 6F)
                                            .requiresCorrectToolForDrops()
                                            .sound(SoundType.DEEPSLATE_TILES)));
        }
        for (String color : NeonCatalog.COLORS) {
            for (String form : NeonCatalog.FORMS) {
                register(
                        color + "_" + form,
                        () ->
                                createLight(
                                        form,
                                        NeonCatalog.color(color),
                                        BlockBehaviour.Properties.of()
                                                .strength(1.5F, 6F)
                                                .requiresCorrectToolForDrops()
                                                .sound(SoundType.METAL)
                                                .noOcclusion()
                                                .dynamicShape()
                                                .lightLevel(
                                                        state ->
                                                                state.getValue(NeonBlock.LIT)
                                                                        ? state.getValue(
                                                                                NeonBlock
                                                                                        .LIGHT_LEVEL)
                                                                        : 0)));
            }
        }
        TABS.register(
                "afterlight",
                () ->
                        CreativeModeTab.builder()
                                .title(Component.translatable("itemGroup.noopsnet_neon"))
                                .icon(
                                        () ->
                                                CATALOG.get("cyan_blade")
                                                        .get()
                                                        .asItem()
                                                        .getDefaultInstance())
                                .displayItems(
                                        (parameters, output) -> {
                                            output.accept(PALETTE.get());
                                            CATALOG.values()
                                                    .forEach(block -> output.accept(block.get()));
                                        })
                                .build());
    }

    private static NeonBlock createLight(
            String form, int color, BlockBehaviour.Properties properties) {
        return switch (form) {
            case "channel", "strip", "elbow" -> new ConnectedNeonBlock(form, color, properties);
            default -> new NeonBlock(form, color, properties);
        };
    }

    private static void register(String name, Supplier<Block> factory) {
        var block = BLOCKS.register(name, factory);
        CATALOG.put(name, block);
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public NoopsnetNeon(IEventBus bus) {
        bus.addListener(
                (net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) ->
                        event.registrar("1")
                                .executesOn(
                                        net.neoforged.neoforge.network.registration.HandlerThread
                                                .MAIN)
                                .playToServer(
                                        SignArtPayload.TYPE,
                                        SignArtPayload.CODEC,
                                        (payload, context) -> {
                                            var player = context.player();
                                            if (player.containerMenu instanceof SignArtMenu menu
                                                    && menu.containerId == payload.containerId())
                                                menu.save(player, payload.art());
                                        })
                                .playToServer(
                                        PaletteSettingsPayload.TYPE,
                                        PaletteSettingsPayload.CODEC,
                                        (payload, context) -> {
                                            var player = context.player();
                                            if (player.containerMenu instanceof PaletteMenu menu
                                                    && menu.containerId == payload.containerId()
                                                    && menu.configure(
                                                            player,
                                                            payload.color(),
                                                            payload.settings(),
                                                            payload.mode())
                                                    && player
                                                            instanceof
                                                            net.minecraft.server.level.ServerPlayer
                                                                    serverPlayer)
                                                serverPlayer.closeContainer();
                                        }));
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TABS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
    }
}
