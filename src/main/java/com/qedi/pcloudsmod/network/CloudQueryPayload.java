package com.qedi.pcloudsmod.network;

import com.qedi.pcloudsmod.server.CloudCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client→server payload requesting cloud data from the server-side
 * {@link CloudCache}.
 *
 * <p>The client sends this periodically (every few ticks) with its desired
 * query radius. The server responds with a {@link PollutionPayload}
 * containing clouds built from cached data only (no AdPother query).
 *
 * <p>The player's position is implicit — the server knows where the
 * querying player is.
 *
 * @param radius the query radius in chunks (client-configurable)
 */
public record CloudQueryPayload(int radius) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CloudQueryPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("pslgmod", "cloud_query"));

    /** StreamCodec: a single VAR_INT radius. */
    public static final StreamCodec<FriendlyByteBuf, CloudQueryPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CloudQueryPayload::radius,
            CloudQueryPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
