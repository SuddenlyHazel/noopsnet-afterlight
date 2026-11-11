// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@GameTestHolder(NoopsnetNeon.ID)
@PrefixGameTestTemplate(false)
public final class NeonGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void catalogAndRecipes(GameTestHelper helper) {
        helper.assertTrue(NoopsnetNeon.CATALOG.size() == 75, "75 registered blocks");
        for (var entry : NoopsnetNeon.CATALOG.entrySet()) {
            var id = ResourceLocation.fromNamespaceAndPath(NoopsnetNeon.ID, entry.getKey());
            var block = entry.getValue().get();
            helper.assertTrue(BuiltInRegistries.BLOCK.get(id) == block, "Block registered: " + id);
            helper.assertTrue(BuiltInRegistries.ITEM.get(id) == block.asItem(), "Matching item: " + id);
            helper.assertTrue(helper.getLevel().getRecipeManager().byKey(id).isPresent(), "Recipe decoded: " + id);
            helper.assertTrue(block.getLootTable().location().equals(id.withPrefix("blocks/")), "Loot table: " + id);
        }
        for (String color : NeonCatalog.COLORS) for (String form : NeonCatalog.FORMS) {
            var id = ResourceLocation.fromNamespaceAndPath(NoopsnetNeon.ID, color + "_" + form + "_recolor");
            helper.assertTrue(helper.getLevel().getRecipeManager().byKey(id).isPresent(), "Recolour recipe: " + id);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void redstoneWaterAndRotation(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        for (String color : NeonCatalog.COLORS) for (String form : NeonCatalog.FORMS) {
            var block = NoopsnetNeon.CATALOG.get(color + "_" + form).get();
            var on = block.defaultBlockState();
            level.setBlockAndUpdate(pos.east(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos, on);
            helper.assertTrue(on.getLightEmission(level, pos) == NeonCatalog.light(form), "Light output: " + color + form);
            level.setBlockAndUpdate(pos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            var off = level.getBlockState(pos);
            helper.assertTrue(!off.getValue(NeonBlock.LIT), "Redstone disables lamp: " + color + form);
            helper.assertTrue(off.getLightEmission(level, pos) == 0, "Blackout emits no light");
            level.setBlockAndUpdate(pos.east(), Blocks.AIR.defaultBlockState());
            helper.assertTrue(level.getBlockState(pos).getValue(NeonBlock.LIT), "Removing power restores lamp");
            var wet = on.setValue(NeonBlock.WATERLOGGED, true);
            level.setBlockAndUpdate(pos, wet);
            helper.assertTrue(wet.getFluidState().getType() == Fluids.WATER, "Waterlogged source");
            helper.assertTrue(wet.hasBlockEntity() && level.getBlockEntity(pos) instanceof NeonBlockEntity,
                "Colour entity exists without custom renderer/ticker");
            helper.assertTrue(on.rotate(Rotation.CLOCKWISE_90).getValue(NeonBlock.FACING) == Direction.EAST,
                "WorldEdit/structure rotation: " + form);
            helper.assertTrue(on.setValue(NeonBlock.FACING, Direction.EAST).mirror(Mirror.FRONT_BACK)
                .getValue(NeonBlock.FACING) == Direction.WEST, "Structure mirror: " + form);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                var state = on.setValue(NeonBlock.FACING, direction);
                var bounds = state.getShape(level, pos).bounds();
                helper.assertTrue(bounds.minX >= 0 && bounds.maxX <= 1 && bounds.minZ >= 0 && bounds.maxZ <= 1,
                    "Collision stays in block bounds: " + form + direction);
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rgbPersistenceDropsAndPlacement(GameTestHelper helper) {
        var level=helper.getLevel(); var pos=helper.absolutePos(new BlockPos(2,2,2));
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AfterlightRGB"));
        player.setGameMode(GameType.SURVIVAL);
        for (String c : NeonCatalog.COLORS) for (String f : NeonCatalog.FORMS) {
            var block=NoopsnetNeon.CATALOG.get(c+"_"+f).get(); var state=block.defaultBlockState();
            level.setBlockAndUpdate(pos,state);
            var light=(NeonBlockEntity)level.getBlockEntity(pos);
            helper.assertTrue(light.color()==NeonCatalog.color(c),"Preset fallback: "+c+f);
            for (int rgb : new int[]{0,0xFFFFFF,0x123ABC,0xFFC399}) {
                light.setColor(rgb);
                var saved=light.saveWithFullMetadata(level.registryAccess());
                var restored=(NeonBlockEntity)BlockEntity.loadStatic(pos,state,saved,level.registryAccess());
                helper.assertTrue(restored!=null && restored.color()==rgb,"Save/load preserves exact RGB");
                var clientCopy=new NeonBlockEntity(pos,state);
                clientCopy.handleUpdateTag(light.getUpdateTag(level.registryAccess()),level.registryAccess());
                helper.assertTrue(clientCopy.color()==rgb && light.getUpdatePacket().getTag().getInt("color")==rgb,
                    "Chunk/update packet preserves exact RGB");
                var drops=Block.getDrops(state,level,pos,light,player,new ItemStack(Items.DIAMOND_PICKAXE));
                helper.assertTrue(drops.size()==1 && drops.getFirst().get(DataComponents.DYED_COLOR).rgb()==rgb,
                    "Normal loot keeps custom RGB");
                helper.assertTrue(block.getCloneItemStack(level,pos,state).get(DataComponents.DYED_COLOR).rgb()==rgb,
                    "Creative pick-block keeps exact RGB");
                var stack=drops.getFirst();
                level.removeBlock(pos,false);level.setBlockAndUpdate(pos,state);
                ((NeonBlockEntity)level.getBlockEntity(pos)).applyComponentsFromItemStack(stack);
                block.setPlacedBy(level,pos,state,player,stack);
                light=(NeonBlockEntity)level.getBlockEntity(pos);
                helper.assertTrue(light.color()==rgb,"Recovered item places with exact colour");
            }
            var palette=new ItemStack(NoopsnetNeon.PALETTE.get());PaletteItem.setColor(palette,0xABCDEF);
            player.setItemInHand(InteractionHand.OFF_HAND,palette);
            var fresh=new ItemStack(block);
            block.setPlacedBy(level,pos,state,player,fresh);
            helper.assertTrue(light.color()==0xABCDEF,"Offhand palette colours fresh lights");
            fresh.set(DataComponents.DYED_COLOR,new net.minecraft.world.item.component.DyedItemColor(0x123456,false));
            light.applyComponentsFromItemStack(fresh);block.setPlacedBy(level,pos,state,player,fresh);
            helper.assertTrue(light.color()==0x123456,"Recovered custom colour wins over palette");
            level.removeBlock(pos,false);player.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void paletteValidationAndProtectedPainting(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(2,2,2));
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AfterlightPaint"));
        player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atCenterOf(pos));
        var wand=new ItemStack(NoopsnetNeon.PALETTE.get());player.setItemInHand(InteractionHand.MAIN_HAND,wand);
        var menu=new PaletteMenu(7,player.getInventory(),InteractionHand.MAIN_HAND,PaletteItem.DEFAULT_COLOR);
        helper.assertTrue(!menu.clickMenuButton(player,-1) && !menu.clickMenuButton(player,0x1000000),"Reject invalid network RGB");
        helper.assertTrue(menu.clickMenuButton(player,0xF3A8C1) && PaletteItem.color(wand)==0xF3A8C1,"Menu applies exact RGB to palette");
        var offhand=new ItemStack(NoopsnetNeon.PALETTE.get());player.setItemInHand(InteractionHand.OFF_HAND,offhand);
        var offMenu=new PaletteMenu(8,player.getInventory(),InteractionHand.OFF_HAND,PaletteItem.DEFAULT_COLOR);
        helper.assertTrue(offMenu.clickMenuButton(player,0x789ABC) && PaletteItem.color(offhand)==0x789ABC
            && PaletteItem.color(wand)==0xF3A8C1,"Offhand picker updates its own palette only");
        player.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
        var intruder=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AfterlightOther"));
        helper.assertTrue(!menu.clickMenuButton(intruder,0),"Different player cannot edit bound palette");
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STICK));
        helper.assertTrue(!menu.stillValid(player) && !menu.clickMenuButton(player,0),"Swapped palette invalidates menu");
        player.setItemInHand(InteractionHand.MAIN_HAND,wand);
        var state=NoopsnetNeon.CATALOG.get("ice_sign").get().defaultBlockState();level.setBlockAndUpdate(pos,state);
        var hit=new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false);
        var light=(NeonBlockEntity)level.getBlockEntity(pos);int original=light.color();
        Consumer<PlayerInteractEvent.RightClickBlock> deny=event->{
            if (event.getEntity()==player && event.getPos().equals(pos)) {
                event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
                event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
            }
        };
        NeoForge.EVENT_BUS.addListener(deny);
        try {player.gameMode.useItemOn(player,level,wand,InteractionHand.MAIN_HAND,hit);}
        finally {NeoForge.EVENT_BUS.unregister(deny);}
        helper.assertTrue(light.color()==original,"Claim-style item-use denial prevents repainting");
        player.gameMode.useItemOn(player,level,wand,InteractionHand.MAIN_HAND,hit);
        helper.assertTrue(light.color()==0xF3A8C1,"Permitted ordinary item use paints block");
        player.setShiftKeyDown(true);PaletteItem.setColor(wand,0xFFFFFF);
        player.gameMode.useItemOn(player,level,wand,InteractionHand.MAIN_HAND,hit);
        helper.assertTrue(PaletteItem.color(wand)==light.color(),"Sneak-use samples exact colour");
        helper.assertTrue(level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(NoopsnetNeon.ID,"palette")).isPresent(),"Palette recipe decoded");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void signArtPersistenceAndPacket(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(2,2,2));
        int[] pixels=new int[1024];pixels[0]=0xFFFF0044;pixels[513]=0xFF00FFFF;pixels[1023]=0xFFFFFFFF;
        var art=new SignArt(pixels);
        for(String form:new String[]{"sign","blade"}) {
            var block=NoopsnetNeon.CATALOG.get("pink_"+form).get();var state=block.defaultBlockState();level.setBlockAndUpdate(pos,state);
            var sign=(NeonBlockEntity)level.getBlockEntity(pos);sign.setArt(art);
            var restored=(NeonBlockEntity)BlockEntity.loadStatic(pos,state,sign.saveWithFullMetadata(level.registryAccess()),level.registryAccess());
            helper.assertTrue(restored.art().equals(art),"Art survives world save/reload");
            var client=new NeonBlockEntity(pos,state);client.handleUpdateTag(sign.getUpdateTag(level.registryAccess()),level.registryAccess());
            helper.assertTrue(client.art().equals(art),"Tracking clients receive exact pixels");
            var drops=Block.getDrops(state,level,pos,sign);helper.assertTrue(drops.size()==1,"One sign drops");
            var copy=new NeonBlockEntity(pos,state);copy.applyComponentsFromItemStack(drops.getFirst());
            helper.assertTrue(copy.art().equals(art),"Normal drop and placement preserve pixels");
            copy=new NeonBlockEntity(pos,state);copy.applyComponentsFromItemStack(block.getCloneItemStack(level,pos,state));
            helper.assertTrue(copy.art().equals(art),"Creative pick preserves pixels");
            sign.setArt(new SignArt(new int[1024]));helper.assertTrue(sign.art()!=null,"Blank custom design is distinct from stock art");
            sign.setArt(null);helper.assertTrue(!sign.getUpdateTag(level.registryAccess()).contains("art"),"Original removes custom canvas");
        }
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
        try {
            SignArtPayload.CODEC.encode(buffer,new SignArtPayload(12,art));helper.assertTrue(buffer.readableBytes()==4098,"Canvas payload is fixed and bounded");
            var decoded=SignArtPayload.CODEC.decode(buffer);helper.assertTrue(decoded.containerId()==12&&decoded.art().equals(art),"Full RGB/alpha packet round-trip");
            SignArtPayload.CODEC.encode(buffer,new SignArtPayload(12,null));helper.assertTrue(SignArtPayload.CODEC.decode(buffer).art()==null,"Reset packet round-trip");
        } finally {buffer.release();}
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void signArtPermissionAndConcurrentEditing(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(2,2,2));
        var state=NoopsnetNeon.CATALOG.get("cyan_sign").get().defaultBlockState();level.setBlockAndUpdate(pos,state);
        var sign=(NeonBlockEntity)level.getBlockEntity(pos);
        // FakePlayer intentionally stubs openMenu; capture the real provider/extra-data path.
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AfterlightArtist")) {
            @Override public java.util.OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
                    java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> extra) {
                var data=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
                try {
                    extra.accept(data);helper.assertTrue(data.readBlockPos().equals(pos),"Editor opens for the bound position");
                    helper.assertTrue(SignArtPayload.readArt(data)==null,"Stock artwork opening data");
                    helper.assertTrue(data.readInt()==sign.color()&&!data.readBoolean()&&data.readableBytes()==0,"Exact initial brush/form data");
                } finally {data.release();}
                containerMenu=provider.createMenu(101,getInventory(),this);return java.util.OptionalInt.of(101);
            }
        };player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atCenterOf(pos));
        var art=new SignArt(new int[1024]);
        player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false));
        helper.assertTrue(player.containerMenu instanceof SignArtMenu,"Empty-hand right click opens the native art menu");
        player.closeContainer();
        var menu=new SignArtMenu(30,player.getInventory(),pos,null,sign.color(),false);
        Consumer<PlayerInteractEvent.RightClickBlock> deny=event->{if(event.getEntity()==player&&event.getPos().equals(pos))event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);};
        NeoForge.EVENT_BUS.addListener(deny);
        try {helper.assertTrue(!menu.save(player,art)&&sign.art()==null,"Claim denial is rechecked when saving");}
        finally {NeoForge.EVENT_BUS.unregister(deny);}
        var other=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"OtherArtist"));other.setGameMode(GameType.SURVIVAL);other.setPos(Vec3.atCenterOf(pos));
        helper.assertTrue(!menu.save(other,art),"Other player cannot use the bound menu");
        var stale=new SignArtMenu(31,player.getInventory(),pos,null,sign.color(),false);sign.setArt(art);
        helper.assertTrue(!stale.save(player,null)&&stale.status.get()==3,"Concurrent draft cannot overwrite newer art");
        var current=new SignArtMenu(32,player.getInventory(),pos,art,sign.color(),false);
        player.setGameMode(GameType.ADVENTURE);helper.assertTrue(!current.save(player,null),"Adventure editing denied");player.setGameMode(GameType.SURVIVAL);
        player.setPos(Vec3.atCenterOf(pos).add(20,0,0));helper.assertTrue(!current.save(player,null),"Out-of-range editing denied");player.setPos(Vec3.atCenterOf(pos));
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STICK));helper.assertTrue(!current.save(player,null),"Changed hand invalidates editing");player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        helper.assertTrue(new SignArtMenu(33,player.getInventory(),pos,art,sign.color(),false).save(player,null)&&sign.art()==null,"Permitted save restores stock artwork");
        var replaced=new SignArtMenu(34,player.getInventory(),pos,null,sign.color(),false);level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(pos,state);
        helper.assertTrue(!replaced.save(player,art),"Replaced block cannot be edited by an old menu");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void connectedTrimJunctions(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(2,2,2));
        for(String form:new String[]{"strip","elbow","channel"})for(Direction facing:Direction.Plane.HORIZONTAL) {
            var block=(ConnectedNeonBlock)NoopsnetNeon.CATALOG.get("cyan_"+form).get();
            var state=block.defaultBlockState().setValue(NeonBlock.FACING,facing).setValue(ConnectedNeonBlock.JOIN,true);
            Direction left=facing.getCounterClockWise(),right=facing.getClockWise();
            level.setBlockAndUpdate(pos,state);
            var neighbor=(ConnectedNeonBlock)NoopsnetNeon.CATALOG.get("pink_"+(form.equals("channel")?"channel":"strip")).get();
            var other=neighbor.defaultBlockState().setValue(NeonBlock.FACING,facing).setValue(ConnectedNeonBlock.JOIN,true);
            level.setBlockAndUpdate(pos.relative(left),other);level.setBlockAndUpdate(pos.above(),other);
            helper.assertTrue(((ConnectedNeonBlock)level.getBlockState(pos).getBlock()).connections(level.getBlockState(pos),level,pos)==5,"Different colours make a 90-degree bend: "+form+facing);
            level.setBlockAndUpdate(pos.relative(right),other);
            helper.assertTrue(((ConnectedNeonBlock)level.getBlockState(pos).getBlock()).connections(level.getBlockState(pos),level,pos)==7,"T junction");
            level.setBlockAndUpdate(pos.below(),other);
            helper.assertTrue(((ConnectedNeonBlock)level.getBlockState(pos).getBlock()).connections(level.getBlockState(pos),level,pos)==15,"Cross junction");
            level.removeBlock(pos.above(),false);
            helper.assertTrue(((ConnectedNeonBlock)level.getBlockState(pos).getBlock()).connections(level.getBlockState(pos),level,pos)==11,"Removing a branch updates neighbors");
            var bent=state;
            helper.assertTrue(bent.rotate(Rotation.CLOCKWISE_90).getValue(NeonBlock.FACING)==facing.getClockWise(),"Rotation retains local ports");
            for(Mirror mirror:new Mirror[]{Mirror.LEFT_RIGHT,Mirror.FRONT_BACK})helper.assertTrue(bent.mirror(mirror).mirror(mirror).equals(bent),"Mirroring is reversible");
            var light=(NeonBlockEntity)level.getBlockEntity(pos);light.applySettings(new LightSettings(4,-1,false));
            helper.assertTrue(!ConnectedNeonBlock.isJoining(level.getBlockState(pos))&&((ConnectedNeonBlock)level.getBlockState(pos).getBlock()).connections(level.getBlockState(pos),level,pos)==16,"Fixed mode restores original geometry");
            for(Direction direction:new Direction[]{left,right,Direction.UP,Direction.DOWN})level.removeBlock(pos.relative(direction),false);
            level.removeBlock(pos,false);
        }
        var legacy=net.minecraft.nbt.NbtUtils.writeBlockState(NoopsnetNeon.CATALOG.get("cyan_strip").get().defaultBlockState());
        legacy.getCompound("Properties").remove("joining");legacy.getCompound("Properties").remove("light_level");
        var migrated=net.minecraft.nbt.NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),legacy);
        helper.assertTrue(!ConnectedNeonBlock.isJoining(migrated)&&migrated.getValue(NeonBlock.LIGHT_LEVEL)==14,"Old blockstate NBT preserves fixed shape and original light level");
        // Inlay depth and rail depth are intentionally separate connection families.
        var rail=NoopsnetNeon.CATALOG.get("cyan_strip").get().defaultBlockState().setValue(ConnectedNeonBlock.JOIN,true);
        level.setBlockAndUpdate(pos,rail);level.setBlockAndUpdate(pos.east(),NoopsnetNeon.CATALOG.get("cyan_channel").get().defaultBlockState().setValue(ConnectedNeonBlock.JOIN,true));
        helper.assertTrue(((ConnectedNeonBlock)level.getBlockState(pos).getBlock()).connections(level.getBlockState(pos),level,pos)==0,"Different mounting depths do not make false joins");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void lightingSettingsStorageAndPropagation(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(2,2,2));
        var block=NoopsnetNeon.CATALOG.get("ice_bollard").get();var state=block.defaultBlockState();level.setBlockAndUpdate(pos,state);
        var light=(NeonBlockEntity)level.getBlockEntity(pos);var settings=new LightSettings(1,15,false);light.applySettings(settings);
        helper.assertTrue(state.getLightEmission(level,pos)==15&&light.getModelData().get(NeonModelData.GLOW)==1,"Independent reach and emissive model data");
        var restored=(NeonBlockEntity)BlockEntity.loadStatic(pos,state,light.saveWithFullMetadata(level.registryAccess()),level.registryAccess());
        helper.assertTrue(restored.settings().equals(settings),"World save retains profile");
        var client=new NeonBlockEntity(pos,state);client.handleUpdateTag(light.getUpdateTag(level.registryAccess()),level.registryAccess());helper.assertTrue(client.settings().equals(settings),"Tracking clients receive profile");
        var drop=Block.getDrops(state,level,pos,light).getFirst();helper.assertTrue(LightSettings.get(drop).equals(settings),"Loot carries lighting settings");
        helper.assertTrue(LightSettings.get(block.getCloneItemStack(level,pos,state)).equals(settings),"Creative pick carries lighting settings");
        var replacement=new NeonBlockEntity(pos,state);replacement.applyComponentsFromItemStack(drop);helper.assertTrue(replacement.settings().equals(settings),"Replacement preserves profile");
        level.getLightEngine().setLightEnabled(new net.minecraft.world.level.ChunkPos(pos),true);
        level.getLightEngine().updateSectionStatus(net.minecraft.core.SectionPos.of(pos),false);
        level.getLightEngine().checkBlock(pos);
        final boolean[] switched={false};
        helper.succeedWhen(()->{
            level.getChunkSource().getLightEngine().tryScheduleUpdate();
            int brightness=level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,pos);
            if(!switched[0]) {
                helper.assertTrue(brightness==15,"Vanilla light engine applies dynamic reach; actual="+brightness);
                light.applySettings(new LightSettings(0,0,true));switched[0]=true;
                helper.assertTrue(false,"Waiting for removal of the old source");
            }
            helper.assertTrue(level.getBlockState(pos).getLightEmission(level,pos)==0&&brightness<15,"Vanilla light engine removes the old source; actual="+brightness);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void paletteSettingsScopesAndPacket(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(2,2,2));
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AfterlightSettings"));player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atCenterOf(pos));
        var tool=new ItemStack(NoopsnetNeon.PALETTE.get());player.setItemInHand(InteractionHand.MAIN_HAND,tool);
        var menu=new PaletteMenu(50,player.getInventory(),InteractionHand.MAIN_HAND,PaletteItem.DEFAULT_COLOR);
        var profile=new LightSettings(2,8,true);helper.assertTrue(menu.configure(player,0xABCDEF,profile,2),"Validated menu saves profile and scope");
        var block=NoopsnetNeon.CATALOG.get("pink_sign").get();var state=block.defaultBlockState();level.setBlockAndUpdate(pos,state);var light=(NeonBlockEntity)level.getBlockEntity(pos);
        var art=new SignArt(new int[1024]);light.setArt(art);int before=light.color();
        var hit=new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false);
        player.gameMode.useItemOn(player,level,tool,InteractionHand.MAIN_HAND,hit);
        helper.assertTrue(light.color()==before&&light.settings().glow()==2&&light.settings().reach()==8&&light.art().equals(art),"Lighting-only retains colour and artwork");
        PaletteItem.configure(tool,new LightSettings(0,0,false),1);player.gameMode.useItemOn(player,level,tool,InteractionHand.MAIN_HAND,hit);
        helper.assertTrue(light.color()==0xABCDEF&&light.settings().glow()==2&&light.settings().reach()==8,"Colour-only retains lighting");
        player.setShiftKeyDown(true);player.gameMode.useItemOn(player,level,tool,InteractionHand.MAIN_HAND,hit);
        helper.assertTrue(LightSettings.get(tool).glow()==2&&LightSettings.get(tool).reach()==8&&PaletteItem.mode(tool)==1,"Sampling picks up settings without changing apply scope");
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
        try {var payload=new PaletteSettingsPayload(50,0xABCDEF,profile,2);PaletteSettingsPayload.CODEC.encode(buffer,payload);helper.assertTrue(buffer.readableBytes()==9&&PaletteSettingsPayload.CODEC.decode(buffer).equals(payload),"Bounded palette packet round-trip");}finally{buffer.release();}
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STICK));helper.assertTrue(!menu.configure(player,0,profile,0),"Moved item invalidates settings menu");
        player.setShiftKeyDown(false);player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);player.setItemInHand(InteractionHand.OFF_HAND,tool);PaletteItem.configure(tool,profile,2);
        var trim=NoopsnetNeon.CATALOG.get("cyan_strip").get();var target=pos.east(2);level.removeBlock(target,false);level.setBlockAndUpdate(target.below(),Blocks.STONE.defaultBlockState());
        var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,new ItemStack(trim),new BlockHitResult(Vec3.atCenterOf(target.below()),Direction.UP,target.below(),false));
        var fresh=trim.getStateForPlacement(context);helper.assertTrue(ConnectedNeonBlock.isJoining(fresh),"Fresh placement enables joining");
        level.setBlockAndUpdate(target,fresh);trim.setPlacedBy(level,target,fresh,player,new ItemStack(trim));
        var placed=(NeonBlockEntity)level.getBlockEntity(target);helper.assertTrue(placed.settings().glow()==2&&placed.settings().reach()==8&&placed.color()==NeonCatalog.color("cyan"),"Offhand lighting-only configures fresh placements without recolouring");
        var recovered=new ItemStack(trim);new LightSettings(1,3,false).put(recovered);level.removeBlock(target,false);level.setBlockAndUpdate(target,fresh);
        ((NeonBlockEntity)level.getBlockEntity(target)).applyComponentsFromItemStack(recovered);trim.setPlacedBy(level,target,fresh,player,recovered);
        placed=(NeonBlockEntity)level.getBlockEntity(target);helper.assertTrue(placed.settings().glow()==1&&placed.settings().reach()==3&&!placed.settings().joining(),"Recovered settings win over the offhand palette");
        boolean rejected=false;try{new LightSettings(5,16,true);}catch(IllegalArgumentException expected){rejected=true;}helper.assertTrue(rejected,"Out-of-range lighting values are rejected");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pickerMathAndHex(GameTestHelper helper) {
        for (int rgb : new int[]{0,0xFFFFFF,0xFF0000,0x00FF00,0x0000FF,0x123ABC,0xA9CDD9,0xFFD9AD}) {
            var hsv=ColorMath.toHsv(rgb);
            helper.assertTrue(ColorMath.hsv(hsv[0],hsv[1],hsv[2])==rgb,"HSV round-trip: "+rgb);
            helper.assertTrue(ColorMath.parseHex(String.format("#%06X",rgb))==rgb,"Exact hex parsing");
            var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                var packet=new net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket(7,rgb);
                var codec=net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket.STREAM_CODEC;
                codec.encode(buffer,packet);var decoded=codec.decode(buffer);
                helper.assertTrue(decoded.buttonId()==rgb && decoded.containerId()==7,"Actual menu packet codec preserves 24-bit RGB");
            } finally {buffer.release();}
        }
        for (String invalid : new String[]{"","#","#123","1234567","#GG0000","-12345"})
            helper.assertTrue(ColorMath.parseHex(invalid)==null,"Reject invalid hex text");
        helper.succeed();
    }
}
