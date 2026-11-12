// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.noopsnet.neon.*;

@Mod(value = NoopsnetNeon.ID, dist = Dist.CLIENT)
public final class NeonClient {
    public NeonClient(IEventBus bus) {
        bus.addListener(NeonClient::blocks);
        bus.addListener(NeonClient::items);
        bus.addListener(NeonClient::screens);
        bus.addListener(
                (net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) -> {
                    if (net.neoforged.fml.ModList.get().isLoaded("iris"))
                        event.enqueueWork(IrisArtMaterials::register);
                });
        bus.addListener(
                (net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers
                                event) ->
                        event.registerBlockEntityRenderer(
                                NoopsnetNeon.LIGHT_ENTITY.get(), SignArtRenderer::new));
        bus.addListener(
                (net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional event) -> {
                    for (String color : NeonCatalog.COLORS)
                        for (String form : new String[] {"channel", "strip", "elbow"})
                            for (int mask = 0; mask < 16; mask++)
                                for (String mode : new String[] {"on", "off"})
                                    event.register(
                                            net.minecraft.client.resources.model
                                                    .ModelResourceLocation.standalone(
                                                    net.minecraft.resources.ResourceLocation
                                                            .fromNamespaceAndPath(
                                                                    NoopsnetNeon.ID,
                                                                    "block/"
                                                                            + color
                                                                            + "_"
                                                                            + form
                                                                            + "_connect"
                                                                            + mask
                                                                            + "_"
                                                                            + mode)));
                    for (String kind : new String[] {"tube", "market", "metro", "holo"})
                        for (int glow = 0; glow < 4; glow++)
                            event.register(
                                    net.minecraft.client.resources.model.ModelResourceLocation
                                            .standalone(
                                                    net.minecraft.resources.ResourceLocation
                                                            .fromNamespaceAndPath(
                                                                    NoopsnetNeon.ID,
                                                                    "block/glow_profile_"
                                                                            + kind
                                                                            + "_"
                                                                            + glow)));
                });
        bus.addListener(
                (net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult event) -> {
                    var profiles =
                            new java.util.HashMap<
                                    String,
                                    net.minecraft.client.renderer.texture.TextureAtlasSprite[]>();
                    for (String kind : new String[] {"tube", "market", "metro", "holo"}) {
                        var sprites =
                                new net.minecraft.client.renderer.texture.TextureAtlasSprite[4];
                        for (int glow = 0; glow < 4; glow++)
                            sprites[glow] =
                                    event.getTextureGetter()
                                            .apply(
                                                    new net.minecraft.client.resources.model
                                                            .Material(
                                                            net.minecraft.client.renderer.texture
                                                                    .TextureAtlas.LOCATION_BLOCKS,
                                                            net.minecraft.resources.ResourceLocation
                                                                    .fromNamespaceAndPath(
                                                                            NoopsnetNeon.ID,
                                                                            "block/" + kind
                                                                                    + "_glow"
                                                                                    + glow)));
                        profiles.put(kind, sprites);
                    }
                    var connected =
                            new java.util.HashMap<
                                    String, net.minecraft.client.resources.model.BakedModel[][]>();
                    for (String color : NeonCatalog.COLORS)
                        for (String form : new String[] {"channel", "strip", "elbow"}) {
                            var models = new net.minecraft.client.resources.model.BakedModel[2][16];
                            for (int lit = 0; lit < 2; lit++)
                                for (int mask = 0; mask < 16; mask++) {
                                    var id =
                                            net.minecraft.client.resources.model
                                                    .ModelResourceLocation.standalone(
                                                    net.minecraft.resources.ResourceLocation
                                                            .fromNamespaceAndPath(
                                                                    NoopsnetNeon.ID,
                                                                    "block/"
                                                                            + color
                                                                            + "_"
                                                                            + form
                                                                            + "_connect"
                                                                            + mask
                                                                            + "_"
                                                                            + (lit == 1
                                                                                    ? "on"
                                                                                    : "off")));
                                    models[lit][mask] =
                                            java.util.Objects.requireNonNull(
                                                    event.getModels().get(id),
                                                    "Missing connected mesh " + id);
                                }
                            connected.put(color + "_" + form, models);
                        }
                    var wrapped =
                            new java.util.IdentityHashMap<
                                    net.minecraft.client.resources.model.BakedModel,
                                    net.minecraft.client.resources.model.BakedModel>();
                    event.getModels()
                            .replaceAll(
                                    (id, model) -> {
                                        String path = id.id().getPath();
                                        if (!id.id().getNamespace().equals(NoopsnetNeon.ID)
                                                || id.variant().equals("inventory")
                                                || path.contains("_connect")
                                                || path.contains("glow_profile_")) return model;
                                        String name =
                                                path.replaceFirst("^block/", "")
                                                        .replaceFirst("_(on|off)$", "");
                                        return wrapped.computeIfAbsent(
                                                model,
                                                original -> {
                                                    if (path.contains("_sign")
                                                            || path.contains("_blade"))
                                                        original = new SignFaceModel(original);
                                                    if (connected.containsKey(name))
                                                        original =
                                                                new ConnectedModel(
                                                                        original,
                                                                        connected.get(name));
                                                    return new GlowModel(original, profiles);
                                                });
                                    });
                });
        bus.addListener(
                (net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) ->
                        event.registerReloadListener(
                                (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                                        manager -> SignArtRenderer.clearCache()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(AfterlightAtmosphere::tick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(AfterlightAtmosphere::frame);
        NeonBlockEntity.clientLoaded = AfterlightLighting::loaded;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(AfterlightLighting::tick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(AfterlightLighting::frame);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(SignArtRenderer::endFrame);
    }

    private static void blocks(RegisterColorHandlersEvent.Block event) {
        for (var entry : NoopsnetNeon.CATALOG.values())
            if (entry.get() instanceof NeonBlock block) {
                event.register(
                        (state, world, pos, tint) ->
                                tint != 0
                                        ? -1
                                        : 0xFF000000
                                                | (world != null
                                                                && pos != null
                                                                && world.getBlockEntity(pos)
                                                                        instanceof
                                                                        NeonBlockEntity light
                                                        ? light.color()
                                                        : block.defaultColor),
                        block);
            }
    }

    private static void items(RegisterColorHandlersEvent.Item event) {
        for (var entry : NoopsnetNeon.CATALOG.values())
            if (entry.get() instanceof NeonBlock block) {
                event.register(
                        (stack, tint) ->
                                tint != 0
                                        ? -1
                                        : 0xFF000000
                                                | stack.getOrDefault(
                                                                DataComponents.DYED_COLOR,
                                                                new DyedItemColor(
                                                                        block.defaultColor, false))
                                                        .rgb(),
                        block.asItem());
            }
        event.register(
                (stack, tint) -> tint == 0 ? 0xFF000000 | PaletteItem.color(stack) : -1,
                NoopsnetNeon.PALETTE.get());
    }

    private static void screens(RegisterMenuScreensEvent event) {
        event.register(NoopsnetNeon.PALETTE_MENU.get(), PaletteScreen::new);
        event.register(NoopsnetNeon.SIGN_ART_MENU.get(), SignArtScreen::new);
    }
}
