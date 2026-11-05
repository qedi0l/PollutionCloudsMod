package com.qedi.pcloudsmod.network;

import com.qedi.pcloudsmod.server.CloudPoint;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Network payload carrying a list of {@link CloudPoint}s from server to client.
 *
 * <p>Encoding layout:
 * <pre>
 *   VAR_INT count
 *   per cloud:
 *     FLOAT x
 *     FLOAT y
 *     FLOAT z
 *     FLOAT sizeX
 *     FLOAT sizeY
 *     FLOAT sizeZ
 *     VAR_INT color (ARGB)
 * </pre>
 *
 * <p>Worst-case size: 4 + 1024 × 25 ≈ 25.6 KB — trivial at 1 Hz.
 */
public record PollutionPayload(List<CloudPoint> clouds) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PollutionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("pslgmod", "pollution_sync"));

    /** Helper record to group the 3 size floats into a single codec field. */
    private record CloudSize(float x, float y, float z) {}

    /** StreamCodec for the 3 size half-extents. */
    private static final StreamCodec<FriendlyByteBuf, CloudSize> CLOUD_SIZE_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, CloudSize::x,
            ByteBufCodecs.FLOAT, CloudSize::y,
            ByteBufCodecs.FLOAT, CloudSize::z,
            CloudSize::new
    );

    /** StreamCodec for a single {@link CloudPoint}. */
    public static final StreamCodec<FriendlyByteBuf, CloudPoint> CLOUD_POINT_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, CloudPoint::x,
            ByteBufCodecs.FLOAT, CloudPoint::y,
            ByteBufCodecs.FLOAT, CloudPoint::z,
            CLOUD_SIZE_CODEC, cp -> new CloudSize(cp.sizeX(), cp.sizeY(), cp.sizeZ()),
            ByteBufCodecs.VAR_INT, CloudPoint::color,
            (x, y, z, size, color) -> new CloudPoint(x, y, z, size.x(), size.y(), size.z(), color)
    );

    /** StreamCodec for the full payload: a VAR_INT-sized list of cloud points. */
    public static final StreamCodec<FriendlyByteBuf, PollutionPayload> STREAM_CODEC = StreamCodec.composite(
            CLOUD_POINT_CODEC.apply(ByteBufCodecs.list()), PollutionPayload::clouds,
            PollutionPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
