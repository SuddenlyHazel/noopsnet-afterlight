// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Fixed-size canvas; the packet cannot allocate an attacker-selected image size. */
public record SignArtPayload(int containerId, SignArt art) implements CustomPacketPayload {
    public static final Type<SignArtPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NoopsnetNeon.ID, "sign_art"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SignArtPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.containerId);
                        writeArt(buf, payload.art);
                    },
                    buf -> new SignArtPayload(buf.readVarInt(), readArt(buf)));

    public static void writeArt(RegistryFriendlyByteBuf buf, SignArt art) {
        buf.writeBoolean(art != null);
        if (art != null) for (int i = 0; i < SignArt.PIXELS; i++) buf.writeInt(art.pixel(i));
    }

    public static SignArt readArt(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) return null;
        int[] pixels = new int[SignArt.PIXELS];
        for (int i = 0; i < pixels.length; i++) pixels[i] = buf.readInt();
        return new SignArt(pixels);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
