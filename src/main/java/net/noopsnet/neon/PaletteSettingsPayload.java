// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PaletteSettingsPayload(int containerId, int color, LightSettings settings, int mode)
        implements CustomPacketPayload {
    public PaletteSettingsPayload {
        if (color < 0 || color > 0xFFFFFF || mode < 0 || mode > 2)
            throw new IllegalArgumentException("Invalid palette settings");
    }

    public static final Type<PaletteSettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NoopsnetNeon.ID, "palette_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PaletteSettingsPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.containerId);
                        buf.writeInt(p.color);
                        buf.writeByte(p.settings.glow());
                        buf.writeByte(p.settings.reach());
                        buf.writeBoolean(p.settings.joining());
                        buf.writeByte(p.mode);
                    },
                    buf ->
                            new PaletteSettingsPayload(
                                    buf.readVarInt(),
                                    buf.readInt(),
                                    new LightSettings(
                                            buf.readUnsignedByte(),
                                            buf.readByte(),
                                            buf.readBoolean()),
                                    buf.readUnsignedByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
