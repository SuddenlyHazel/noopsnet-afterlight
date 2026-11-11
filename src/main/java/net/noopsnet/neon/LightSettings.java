// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Independent material emission (quarters) and vanilla light reach (-1 = form default). */
public record LightSettings(int glow, int reach, boolean joining) {
    public static final int MAX_GLOW=4;
    public static final LightSettings DEFAULT=new LightSettings(4,-1,true);
    public static final String KEY="afterlight_settings";
    public LightSettings {
        if(glow<0||glow>4||reach< -1||reach>15)throw new IllegalArgumentException("Invalid lighting settings");
    }
    public CompoundTag save() {
        var tag=new CompoundTag();tag.putInt("glow",glow);tag.putInt("reach",reach);tag.putBoolean("joining",joining);return tag;
    }
    public static LightSettings load(CompoundTag tag) {
        if(!tag.contains("glow",3))return DEFAULT;
        try{return new LightSettings(tag.getInt("glow"),tag.getInt("reach"),tag.getBoolean("joining"));}
        catch(IllegalArgumentException ignored){return DEFAULT;}
    }
    public static boolean present(ItemStack stack) {return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().contains(KEY,10);}
    public static LightSettings get(ItemStack stack) {return load(stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound(KEY));}
    public void put(ItemStack stack) {
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();tag.put(KEY,save());
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
    }
    public int emissionAlpha(int full) {return full*glow/4;}
}
