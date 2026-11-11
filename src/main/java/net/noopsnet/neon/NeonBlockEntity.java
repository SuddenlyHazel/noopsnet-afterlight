// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A single RGB value, persisted and synchronized using vanilla block-entity packets. */
public final class NeonBlockEntity extends BlockEntity {
    // Assigned only by the client entry point; dedicated servers have no client class references.
    public static java.util.function.Consumer<NeonBlockEntity> clientLoaded = entity -> {};
    private int color;
    private LightSettings lighting=LightSettings.DEFAULT;
    public int lightReach(){return lighting.reach();}
    public int glow(){return lighting.glow();}
    public LightSettings settings() {
        boolean join=getBlockState().getBlock() instanceof ConnectedNeonBlock?ConnectedNeonBlock.isJoining(getBlockState()):lighting.joining();
        return new LightSettings(lighting.glow(),lighting.reach(),join);
    }
    public void applySettings(LightSettings settings) {
        lighting=settings;setChanged();
        if(level!=null) {
            var block=(NeonBlock)getBlockState().getBlock();
            var next=getBlockState().setValue(NeonBlock.LIGHT_LEVEL,settings.reach()<0?NeonCatalog.light(block.form):settings.reach());
            if(block instanceof ConnectedNeonBlock connected)next=ConnectedNeonBlock.withJoining(next,settings.joining());
            if(next!=getBlockState())level.setBlock(worldPosition,next,Block.UPDATE_ALL);
            if(level.isClientSide)requestModelDataUpdate();
            level.getLightEngine().checkBlock(worldPosition);
            level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
        }
    }
    @Override public net.neoforged.neoforge.client.model.data.ModelData getModelData() {
        return net.neoforged.neoforge.client.model.data.ModelData.builder().with(NeonModelData.GLOW,lighting.glow()).build();
    }
    private SignArt art;
    private int artRevision;
    public SignArt art() { return art; }
    public int artRevision() { return artRevision; }
    public boolean editableSign() { return getBlockState().getBlock() instanceof NeonBlock light && light.editableSign(); }
    public void setArt(SignArt art) {
        if (!editableSign()) throw new IllegalStateException("Only signs have a canvas");
        if (java.util.Objects.equals(this.art,art)) return;
        this.art=art;artRevision++;setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    public void copyArtTo(net.minecraft.world.item.ItemStack stack) {
        if (art != null) {
            var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            tag.putIntArray("afterlight_art",art.pixels());
            stack.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
        }
    }
    private void loadArt(CompoundTag tag, String key) {
        art=null;
        if (editableSign() && tag.contains(key,11)) {
            try { art=new SignArt(tag.getIntArray(key)); } catch (IllegalArgumentException ignored) { /* Ignore malformed saved art. */ }
        }
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) clientLoaded.accept(this);
    }

    public NeonBlockEntity(BlockPos pos, BlockState state) {
        super(NoopsnetNeon.LIGHT_ENTITY.get(), pos, state);
        color = state.getBlock() instanceof NeonBlock light ? light.defaultColor : PaletteItem.DEFAULT_COLOR;
    }

    private Integer liveColor;
    /** Client-only transient show override. Never changes saved colour/settings or server data. */
    public void setLiveColor(Integer rgb) {
        if(level==null||!level.isClientSide)return;
        if(rgb!=null&&(rgb<0||rgb>0xFFFFFF))throw new IllegalArgumentException("Invalid live RGB");
        if(java.util.Objects.equals(liveColor,rgb))return;
        liveColor=rgb;
        level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    public int color() { return level!=null&&level.isClientSide&&liveColor!=null?liveColor:color; }

    public void setColor(int rgb) {
        if (rgb < 0 || rgb > 0xFFFFFF) throw new IllegalArgumentException("RGB outside 24-bit range");
        if (color == rgb) return;
        color = rgb;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("color", color);
        tag.put(LightSettings.KEY,settings().save());
        if (art != null) tag.putIntArray("art",art.pixels());
        tag.putInt("art_revision",artRevision);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("color", 3)) color = tag.getInt("color") & 0xFFFFFF;
        lighting=LightSettings.load(tag.getCompound(LightSettings.KEY));
        loadArt(tag,"art");artRevision=tag.getInt("art_revision");
    }

    @Override protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        builder.set(DataComponents.DYED_COLOR, new DyedItemColor(color, false));
        var tag=new CompoundTag();tag.put(LightSettings.KEY,settings().save());
        if(art!=null)tag.putIntArray("afterlight_art",art.pixels());
        builder.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
    }

    @Override protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        DyedItemColor dyed = input.get(DataComponents.DYED_COLOR);
        if (dyed != null) color = dyed.rgb() & 0xFFFFFF;
        var custom=input.get(DataComponents.CUSTOM_DATA);
        if (custom != null) {loadArt(custom.copyTag(),"afterlight_art");lighting=LightSettings.load(custom.copyTag().getCompound(LightSettings.KEY));}
    }

    @Override public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("color");
        tag.remove("art");tag.remove("art_revision");
        tag.remove(LightSettings.KEY);
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }

    @Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
        if (level != null && level.isClientSide) {
            requestModelDataUpdate();
            level.getLightEngine().checkBlock(worldPosition);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
