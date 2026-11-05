package com.qedi.pcloudsmod.server;

import com.qedi.pcloudsmod.PCloudsMod;
import com.qedi.pcloudsmod.common.PollutantEntry;
import com.qedi.pcloudsmod.common.PollutionData;
import net.minecraft.server.level.ServerLevel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent server-side cache of chunk pollution data.
 *
 * <p>Maps chunk coordinates to {@link PollutionData} records. Entries are
 * created only when the server actually scans a chunk (no pre-generation).
 * There is no TTL — entries persist until overwritten by a newer scan.
 *
 * <h2>Persistence</h2>
 * <p>The cache is stored as a custom binary file in the world folder:
 * <pre>
 *   &lt;world&gt;/pslgmod/cloud_cache.dat
 * </pre>
 *
 * <p>File format (little-endian, via {@link DataOutputStream}):
 * <pre>
 *   byte[4]  magic   = "PSLG"
 *   int     version = 1
 *   int     entryCount
 *   per entry:
 *     int     chunkX
 *     int     chunkZ
 *     int     pollutantCount
 *     per pollutant:
 *       float factor
 *       int   color
 *       int   altitude
 * </pre>
 *
 * <h2>Thread safety</h2>
 * <p>Backed by a {@link ConcurrentHashMap}. All public methods are safe to
 * call from any thread.
 */
public final class CloudCache {

    private static final byte[] MAGIC = {'P', 'S', 'L', 'G'};
    private static final int VERSION = 1;
    private static final String DIR_NAME = "pcloudsmod";
    private static final String FILE_NAME = "cloud_cache.dat";

    /** Per-level cache instances, keyed by level. */
    private static final ConcurrentHashMap<ServerLevel, CloudCache> instances =
            new ConcurrentHashMap<>();

    /** Chunk key → pollution data. */
    private final ConcurrentHashMap<Long, PollutionData> entries = new ConcurrentHashMap<>();

    /** Set to true when new entries are added and the cache needs to be saved. */
    private volatile boolean dirty = false;

    private CloudCache() {
    }

    // ── Per-level cache access ─────────────────────────────────────────────

    /**
     * Returns the {@link CloudCache} for the given level, creating one if
     * it does not yet exist.
     *
     * <p>Each {@link ServerLevel} (dimension) gets its own cache instance,
     * so caches are isolated per dimension.
     */
    public static CloudCache forLevel(ServerLevel level) {
        return instances.computeIfAbsent(level, l -> new CloudCache());
    }

    /**
     * Removes and returns the {@link CloudCache} for the given level, if any.
     * Called when a level is unloaded.
     */
    public static CloudCache removeForLevel(ServerLevel level) {
        return instances.remove(level);
    }

    // ── Cache operations ───────────────────────────────────────────────────

    /**
     * Returns the cached {@link PollutionData} for the given chunk, or
     * {@code null} if the chunk is not in the cache.
     */
    public PollutionData get(long chunkKey) {
        return entries.get(chunkKey);
    }

    /**
     * Stores pollution data for the given chunk. If the chunk already has
     * an entry, it is overwritten.
     *
     * <p>Marks the cache as dirty so it will be saved on the next save event.
     */
    public void put(long chunkKey, PollutionData data) {
        entries.put(chunkKey, data);
        dirty = true;
    }

    /**
     * Returns {@code true} if the cache has an entry for the given chunk.
     */
    public boolean contains(long chunkKey) {
        return entries.containsKey(chunkKey);
    }

    /** Returns the number of cached chunks. */
    public int size() {
        return entries.size();
    }

    /** Returns {@code true} if the cache has unsaved changes. */
    public boolean isDirty() {
        return dirty;
    }

    /** Clears the dirty flag (called after a successful save). */
    public void clearDirty() {
        dirty = false;
    }

    // ── Persistence ────────────────────────────────────────────────────────

    /**
     * Saves the cache to a file in the world folder.
     *
     * @param worldPath the root path of the world folder
     * @return the path to the saved file, or {@code null} on failure
     */
    public Path save(Path worldPath) {
        Path dir = worldPath.resolve(DIR_NAME);
        Path file = dir.resolve(FILE_NAME);
        try {
            Files.createDirectories(dir);
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
                out.write(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(entries.size());

                for (var entry : entries.entrySet()) {
                    long key = entry.getKey();
                    PollutionData data = entry.getValue();

                    out.writeInt(chunkXFromKey(key));
                    out.writeInt(chunkZFromKey(key));
                    out.writeInt(data.entries().size());

                    for (PollutantEntry pe : data.entries()) {
                        out.writeFloat(pe.factor());
                        out.writeInt(pe.color());
                        out.writeInt(pe.altitude());
                    }
                }
            }
            dirty = false;
            PCloudsMod.LOGGER.info("[PSLGMod] Saved cloud cache: {} entries → {}", entries.size(), file);
            return file;
        } catch (IOException e) {
            PCloudsMod.LOGGER.error("[PSLGMod] Failed to save cloud cache to {}", file, e);
            return null;
        }
    }

    /**
     * Loads the cache from a file in the world folder.
     *
     * <p>If the file does not exist, the cache remains empty (no error).
     * If the file is corrupt or has an incompatible version, the cache is
     * cleared and a warning is logged.
     *
     * @param worldPath the root path of the world folder
     */
    public void load(Path worldPath) {
        Path file = worldPath.resolve(DIR_NAME).resolve(FILE_NAME);
        if (!Files.exists(file)) {
            PCloudsMod.LOGGER.info("[PSLGMod] No cloud cache file found at {} — starting empty", file);
            return;
        }

        entries.clear();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1]
                    || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
                PCloudsMod.LOGGER.warn("[PSLGMod] Cloud cache file {} has invalid magic — ignoring", file);
                return;
            }

            int version = in.readInt();
            if (version != VERSION) {
                PCloudsMod.LOGGER.warn("[PSLGMod] Cloud cache file {} has unsupported version {} (expected {}) — ignoring",
                        file, version, VERSION);
                return;
            }

            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                int pollutantCount = in.readInt();

                List<PollutantEntry> pollutantEntries = new ArrayList<>(pollutantCount);
                for (int j = 0; j < pollutantCount; j++) {
                    float factor = in.readFloat();
                    int color = in.readInt();
                    int altitude = in.readInt();
                    pollutantEntries.add(new PollutantEntry(factor, color, altitude));
                }

                long key = chunkKey(chunkX, chunkZ);
                entries.put(key, new PollutionData(chunkX, chunkZ, pollutantEntries));
            }

            PCloudsMod.LOGGER.info("[PSLGMod] Loaded cloud cache: {} entries from {}", entries.size(), file);
        } catch (IOException e) {
            PCloudsMod.LOGGER.error("[PSLGMod] Failed to load cloud cache from {} — starting empty", file, e);
            entries.clear();
        }
    }

    // ── Chunk key encoding/decoding ────────────────────────────────────────

    /** Encodes a chunk coordinate pair into a single long. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (((long) chunkZ) & 0xFFFFFFFFL);
    }

    /** Extracts the X coordinate from a chunk key. */
    public static int chunkXFromKey(long key) {
        return (int) (key >> 32);
    }

    /** Extracts the Z coordinate from a chunk key. */
    public static int chunkZFromKey(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }
}
