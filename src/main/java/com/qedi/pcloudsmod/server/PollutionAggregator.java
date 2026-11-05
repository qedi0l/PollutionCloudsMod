package com.qedi.pcloudsmod.server;

import com.qedi.pcloudsmod.Config;
import com.qedi.pcloudsmod.PCloudsMod;
import com.qedi.pcloudsmod.common.PollutantEntry;
import com.qedi.pcloudsmod.common.PollutionAccessor;
import com.qedi.pcloudsmod.common.PollutionData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side handler that reads chunk pollution from AdPother and produces
 * a list of {@link CloudPoint}s for each online player.
 *
 * <p>Runs every {@link #SYNC_INTERVAL_TICKS} ticks (default 20 = 1 second).
 * For each player, scans a chunk area around the player and groups
 * connected polluted chunks into merged clouds using flood-fill (BFS).
 *
 * <p>Each connected component becomes a single ellipsoid cloud whose
 * bounding box matches the spatial extent of the merged chunks.
 *
 * <p>The resulting cloud lists are stored per-player and can be read by the
 * network sync layer to send to the client.
 */
@EventBusSubscriber(modid = PCloudsMod.MODID)
public final class PollutionAggregator {

    /** Minimum pollution factor for a chunk to produce a cloud. */
    private static final float MIN_FACTOR_THRESHOLD = 0.01f;

    /** Maximum number of clouds per player. */
    private static final int MAX_CLOUDS = 1024;

    private static int tickCounter = 0;

    /**
     * Per-player cloud data, updated every sync interval.
     * Keyed by player UUID string for thread safety (read by network layer).
     */
    private static final ConcurrentHashMap<String, List<CloudPoint>> playerClouds =
            new ConcurrentHashMap<>();

    private PollutionAggregator() {
    }

    /**
     * Called every server tick. Performs the actual scan every
     * sync interval ticks.
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

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        int scanRadius = Config.CLOUD_SCAN_CHUNKS.get();
        int queryRadius = Config.CLOUD_QUERY_RADIUS.get();
        float baseRadius = Config.CLOUD_BASE_RADIUS.get().floatValue();
        float maxRadius = Config.CLOUD_MAX_RADIUS.get().floatValue();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            List<CloudPoint> clouds = scanForPlayer(level, player.blockPosition(), scanRadius, queryRadius, baseRadius, maxRadius);
            playerClouds.put(player.getUUID().toString(), clouds);
        }

        // Clean up entries for players who have left.
        playerClouds.keySet().removeIf(uuid ->
                server.getPlayerList().getPlayer(UUID.fromString(uuid)) == null
        );
    }

    /**
     * Returns the latest computed cloud list for the given player, or an empty list.
     * Called by the network sync layer.
     */
    public static List<CloudPoint> getCloudsForPlayer(UUID playerUuid) {
        return playerClouds.getOrDefault(playerUuid.toString(), List.of());
    }

    /**
     * Scans the area around the given position and produces a list of cloud points.
     *
     * <p>Produces a <b>complete</b> cloud set spanning two radii:
     * <ul>
     *   <li><b>Inner radius</b> ({@code scanRadiusChunks}): fresh AdPother data.
     *       Cache misses are resolved by querying AdPother directly and storing
     *       the result in the {@link CloudCache}.</li>
     *   <li><b>Outer annulus</b> ({@code scanRadiusChunks} &lt; dist &le;
     *       {@code queryRadiusChunks}): cache-only. No AdPother query — this
     *       region is only populated from previously scanned data.</li>
     * </ul>
     *
     * <p>Matching the outer bound to the client's {@code cloud_query_radius}
     * ensures the periodic server sync and the client's cache query produce
     * the <b>same</b> complete set, preventing flicker from competing
     * data sources overwriting each other.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Collect polluted chunks (cache-first; AdPother fallback in inner radius)</li>
     *   <li>Group connected chunks (4-neighborhood) into components via BFS flood-fill</li>
     *   <li>For each component, compute a single ellipsoid cloud matching the bounding box</li>
     * </ol>
     *
     * <p>Clouds are sorted by distance to the player (nearest first) and
     * truncated to {@link #MAX_CLOUDS}.
     */
    private static List<CloudPoint> scanForPlayer(ServerLevel level, BlockPos playerPos,
                                                   int scanRadiusChunks, int queryRadiusChunks,
                                                   float baseRadius, float maxRadius) {
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        // ── Step 1: Collect polluted chunks (using cache) ──
        Map<Long, PollutionData> pollutedChunks = new HashMap<>();
        CloudCache cache = CloudCache.forLevel(level);

        // Outer bound is the larger of the two radii.
        int outer = Math.max(scanRadiusChunks, queryRadiusChunks);

        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                int chunkX = playerChunkX + dx;
                int chunkZ = playerChunkZ + dz;
                long key = CloudCache.chunkKey(chunkX, chunkZ);

                boolean inFreshRadius =
                        Math.abs(dx) <= scanRadiusChunks && Math.abs(dz) <= scanRadiusChunks;

                PollutionData data;
                if (inFreshRadius) {
                    // Inner radius: always read fresh AdPother data and refresh the cache.
                    if (!level.hasChunk(chunkX, chunkZ)) {
                        continue;
                    }
                    BlockPos center = new BlockPos((chunkX << 4) + 8, 0, (chunkZ << 4) + 8);
                    data = PollutionAccessor.getChunkPollution(level, center);
                    cache.put(key, data);
                } else {
                    // Outer annulus: cache-only (populated by previous inner scans).
                    data = cache.get(key);
                    if (data == null) {
                        continue;
                    }
                }

                if (!data.isEmpty() && data.maxFactor() >= MIN_FACTOR_THRESHOLD) {
                    pollutedChunks.put(key, data);
                }
            }
        }

        return buildCloudsFromChunks(pollutedChunks, level, playerPos, baseRadius, maxRadius);
    }

    /**
     * Queries only the {@link CloudCache} for cloud data in the given area.
     * Does NOT query AdPother — returns clouds only for chunks that are
     * already cached.
     *
     * <p>This is used by the client-side query path: the client requests
     * cloud data from the server cache within a configurable radius.
     *
     * @param level        the server level
     * @param playerPos    the center position for the query
     * @param queryRadius  the query radius in chunks
     * @param baseRadius   base cloud Y half-extent
     * @param maxRadius    max cloud Y half-extent
     * @return list of cloud points from cached data only (may be empty)
     */
    public static List<CloudPoint> queryCache(ServerLevel level, BlockPos playerPos,
                                               int queryRadius, float baseRadius, float maxRadius) {
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        Map<Long, PollutionData> pollutedChunks = new HashMap<>();
        CloudCache cache = CloudCache.forLevel(level);

        for (int dx = -queryRadius; dx <= queryRadius; dx++) {
            for (int dz = -queryRadius; dz <= queryRadius; dz++) {
                int chunkX = playerChunkX + dx;
                int chunkZ = playerChunkZ + dz;
                long key = CloudCache.chunkKey(chunkX, chunkZ);

                PollutionData cached = cache.get(key);
                if (cached != null && !cached.isEmpty() && cached.maxFactor() >= MIN_FACTOR_THRESHOLD) {
                    pollutedChunks.put(key, cached);
                }
            }
        }

        return buildCloudsFromChunks(pollutedChunks, level, playerPos, baseRadius, maxRadius);
    }

    /**
     * Builds a list of {@link CloudPoint}s from a map of polluted chunks.
     *
     * <p>Shared by {@link #scanForPlayer} and {@link #queryCache}.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Group connected chunks (4-neighborhood) into components via BFS flood-fill</li>
     *   <li>For each component, compute a single ellipsoid cloud matching the bounding box</li>
     *   <li>Sort by distance to the player (nearest first), truncate to {@link #MAX_CLOUDS}</li>
     * </ol>
     */
    private static List<CloudPoint> buildCloudsFromChunks(Map<Long, PollutionData> pollutedChunks,
                                                           ServerLevel level, BlockPos playerPos,
                                                           float baseRadius, float maxRadius) {
        if (pollutedChunks.isEmpty()) {
            return List.of();
        }

        // ── Find connected components (4-neighborhood BFS) ──
        Set<Long> visited = new HashSet<>();
        List<Set<Long>> components = new ArrayList<>();

        for (Long startKey : pollutedChunks.keySet()) {
            if (visited.contains(startKey)) {
                continue;
            }

            Set<Long> component = new HashSet<>();
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(startKey);
            visited.add(startKey);

            while (!queue.isEmpty()) {
                Long current = queue.poll();
                component.add(current);

                int cx = CloudCache.chunkXFromKey(current);
                int cz = CloudCache.chunkZFromKey(current);

                // 4 neighbors: N, S, W, E
                long[] neighbors = {
                        CloudCache.chunkKey(cx, cz - 1),
                        CloudCache.chunkKey(cx, cz + 1),
                        CloudCache.chunkKey(cx - 1, cz),
                        CloudCache.chunkKey(cx + 1, cz)
                };

                for (long neighbor : neighbors) {
                    if (!visited.contains(neighbor) && pollutedChunks.containsKey(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }

            components.add(component);
        }

        // ── Build one cloud per component ──
        List<CloudPoint> clouds = new ArrayList<>();

        for (Set<Long> component : components) {
            CloudPoint cloud = buildCloudForComponent(component, pollutedChunks, level, baseRadius, maxRadius);
            if (cloud != null) {
                clouds.add(cloud);
            }
        }

        // Sort by distance to player (nearest first)
        double px = playerPos.getX() + 0.5;
        double py = playerPos.getY() + 0.5;
        double pz = playerPos.getZ() + 0.5;
        clouds.sort(Comparator.comparingDouble(c -> {
            double dx = c.x() - px;
            double dy = c.y() - py;
            double dz = c.z() - pz;
            return dx * dx + dy * dy + dz * dz;
        }));

        // Truncate to max clouds
        if (clouds.size() > MAX_CLOUDS) {
            return clouds.subList(0, MAX_CLOUDS);
        }
        return clouds;
    }

    /**
     * Builds a single {@link CloudPoint} for a connected component of polluted chunks.
     *
     * <p>The cloud's X/Z extent matches the bounding box of the component's chunks.
     * The Y position is the factor-weighted average altitude of all pollutants.
     * The Y half-extent is thinner than X/Z to represent a pollution layer.
     *
     * @return the cloud point, or null if the component has no valid data
     */
    private static CloudPoint buildCloudForComponent(Set<Long> component,
                                                      Map<Long, PollutionData> pollutedChunks,
                                                      ServerLevel level,
                                                      float baseRadius, float maxRadius) {
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;

        float totalWeight = 0.0f;
        float maxFactor = 0.0f;
        float altitudeWeightedSum = 0.0f;
        int blendedR = 0, blendedG = 0, blendedB = 0;

        for (Long chunkKey : component) {
            int cx = CloudCache.chunkXFromKey(chunkKey);
            int cz = CloudCache.chunkZFromKey(chunkKey);

            minChunkX = Math.min(minChunkX, cx);
            maxChunkX = Math.max(maxChunkX, cx);
            minChunkZ = Math.min(minChunkZ, cz);
            maxChunkZ = Math.max(maxChunkZ, cz);

            PollutionData data = pollutedChunks.get(chunkKey);
            for (PollutantEntry entry : data.entries()) {
                float f = entry.factor();
                totalWeight += f;
                maxFactor = Math.max(maxFactor, f);
                altitudeWeightedSum += entry.altitude() * f;

                int argb = entry.color();
                blendedR += ((argb >> 16) & 0xFF) * (int) (f * 100);
                blendedG += ((argb >> 8) & 0xFF) * (int) (f * 100);
                blendedB += (argb & 0xFF) * (int) (f * 100);
            }
        }

        if (totalWeight <= 0.0f) {
            return null;
        }

        // Compute blended color
        int scale = (int) (totalWeight * 100);
        blendedR /= scale;
        blendedG /= scale;
        blendedB /= scale;
        int blendedColor = 0xFF000000 | (blendedR << 16) | (blendedG << 8) | blendedB;

        // Compute bounding box in block space
        int minBlockX = minChunkX << 4;
        int maxBlockX = (maxChunkX + 1) << 4;
        int minBlockZ = minChunkZ << 4;
        int maxBlockZ = (maxChunkZ + 1) << 4;

        // Center of the bounding box
        float centerX = (minBlockX + maxBlockX) / 2.0f;
        float centerZ = (minBlockZ + maxBlockZ) / 2.0f;

        // Half-extents in X and Z (half the bounding box dimension)
        float sizeX = (maxBlockX - minBlockX) / 2.0f;
        float sizeZ = (maxBlockZ - minBlockZ) / 2.0f;

        // Y position: factor-weighted average altitude
        float avgAltitude = altitudeWeightedSum / totalWeight;
        int altitude = Math.round(avgAltitude);
        altitude = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, altitude));
        float centerY = altitude + 0.5f;

        // Y half-extent: thinner than X/Z, scaled by pollution intensity
        float clampedFactor = Math.min(maxFactor, 1.0f);
        float sizeY = baseRadius * 0.5f + clampedFactor * (maxRadius - baseRadius) * 1.4f;

        return new CloudPoint(centerX, centerY, centerZ, sizeX, sizeY, sizeZ, blendedColor);
    }

}
