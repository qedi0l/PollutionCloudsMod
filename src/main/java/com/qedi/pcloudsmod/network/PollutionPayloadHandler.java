package com.qedi.pcloudsmod.network;

import com.qedi.pcloudsmod.Config;
import com.qedi.pcloudsmod.PCloudsMod;
import com.qedi.pcloudsmod.server.CloudCache;
import com.qedi.pcloudsmod.server.CloudPoint;
import com.qedi.pcloudsmod.server.PollutionAggregator;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Registers the {@link PollutionPayload} (server→client) and
 * {@link CloudQueryPayload} (client→server) with NeoForge's payload system.
 *
 * <h2>Server→client: {@link PollutionPayload}</h2>
 * <p>Carries a list of {@link CloudPoint}s. The client stores them in
 * {@link PollutionSyncManager} for the render thread.
 *
 * <h2>Client→server: {@link CloudQueryPayload}</h2>
 * <p>The client periodically requests cloud data from the server-side
 * {@link CloudCache}. The server responds with a
 * {@link PollutionPayload} built from cached data only.
 */
public final class PollutionPayloadHandler {

    private PollutionPayloadHandler() {
    }

    /**
     * Registers the payload types, codecs, and handlers on the mod event bus.
     * Called during mod construction.
     */
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");

        // Server→client: cloud data sync
        registrar.playToClient(
                PollutionPayload.TYPE,
                PollutionPayload.STREAM_CODEC,
                PollutionPayloadHandler::handleOnClient
        );

        // Client→server: cloud cache query
        registrar.playToServer(
                CloudQueryPayload.TYPE,
                CloudQueryPayload.STREAM_CODEC,
                PollutionPayloadHandler::handleQueryOnServer
        );

        PCloudsMod.LOGGER.info("[PCloudsMod] Registered PollutionPayload (server→client) and CloudQueryPayload (client→server)");
    }

    /**
     * Client-side handler — stores the received cloud data in the sync manager.
     * Runs on the main thread by default.
     */
    private static void handleOnClient(PollutionPayload payload, IPayloadContext context) {
        PollutionSyncManager.onPayloadReceived(payload.clouds());
    }

    /**
     * Server-side handler — responds to a client's cloud cache query.
     *
     * <p>Queries the {@link CloudCache} for the
     * requesting player's position and sends back the resulting clouds.
     * Runs on the main thread by default.
     */
    private static void handleQueryOnServer(CloudQueryPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        int radius = payload.radius();
        float baseRadius = Config.CLOUD_BASE_RADIUS.get().floatValue();
        float maxRadius = Config.CLOUD_MAX_RADIUS.get().floatValue();

        List<CloudPoint> clouds = PollutionAggregator.queryCache(
                player.serverLevel(), player.blockPosition(), radius, baseRadius, maxRadius
        );

        PacketDistributor.sendToPlayer(player, new PollutionPayload(clouds));
    }
}
