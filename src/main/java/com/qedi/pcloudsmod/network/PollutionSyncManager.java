package com.qedi.pcloudsmod.network;

import com.qedi.pcloudsmod.Config;
import com.qedi.pcloudsmod.PCloudsMod;
import com.qedi.pcloudsmod.common.PollutionAccessor;
import com.qedi.pcloudsmod.server.CloudPoint;
import com.qedi.pcloudsmod.server.PollutionAggregator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;

/**
 * Manages the periodic server→client sync of pollution cloud data.
 *
 * <h2>Server side</h2>
 * <p>On every {@link #SYNC_INTERVAL_TICKS}-th server tick, reads the latest
 * cloud list for each player from {@link PollutionAggregator} and sends a
 * {@link PollutionPayload} via {@link PacketDistributor#sendToPlayer}.
 *
 * <h2>Client side</h2>
 * <p>Stores the most recently received {@code List<CloudPoint>} in a
 * {@link volatile} reference. The render thread reads this reference each
 * frame — no locking needed since the list is immutable and the reference
 * is volatile.
 *
 * <p>This class is registered on the NeoForge game event bus via
 * {@link EventBusSubscriber}.
 */
@EventBusSubscriber(modid = PCloudsMod.MODID)
public final class PollutionSyncManager {

    private static int tickCounter = 0;

    // ─── Client-side storage ───────────────────────────────────────────────

    /**
     * The latest cloud data received from the server.
     * Written on the main thread (payload handler), read on the render thread.
     * Volatile ensures visibility across threads; the list itself is immutable.
     */
    private static volatile List<CloudPoint> latestClouds = List.of();

    // ─── Server-side: periodic send ────────────────────────────────────────

    /**
     * Called every server tick. Sends cloud data to all players every
     * {@link #SYNC_INTERVAL_TICKS} ticks.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!PollutionAccessor.isAvailable() || !Config.CLOUD_ENABLED.get()) {
            return;
        }

        int interval = Config.CLOUD_SYNC_INTERVAL_TICKS.get();
        tickCounter++;
        if (tickCounter < interval) {
            return;
        }
        tickCounter = 0;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            List<CloudPoint> clouds = PollutionAggregator.getCloudsForPlayer(player.getUUID());
            if (clouds == null || clouds.isEmpty()) {
                // Send an empty list to clear stale clouds on the client
                PacketDistributor.sendToPlayer(player, new PollutionPayload(List.of()));
            } else {
                PacketDistributor.sendToPlayer(player, new PollutionPayload(clouds));
            }
        }
    }

    // ─── Client-side: storage ──────────────────────────────────────────────

    /**
     * Called on the client's main thread when a {@link PollutionPayload} is received.
     * Stores the cloud list for the render thread to read.
     *
     * @param clouds the cloud points received from the server
     */
    public static void onPayloadReceived(List<CloudPoint> clouds) {
        latestClouds = clouds;
    }

    /**
     * Returns the latest cloud data received from the server.
     * Safe to call from the render thread.
     *
     * @return immutable list of cloud points (empty if no data received yet)
     */
    public static List<CloudPoint> getLatestClouds() {
        return latestClouds;
    }
}
