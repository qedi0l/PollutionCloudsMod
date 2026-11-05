package com.qedi.pcloudsmod.common;

import java.util.List;

/**
 * Plain data record representing the aggregated pollution state of a single chunk.
 * Contains no AdPother types — safe to serialize and send over the network.
 *
 * @param chunkX  Chunk X coordinate
 * @param chunkZ  Chunk Z coordinate
 * @param entries One entry per pollutant present in this chunk (empty if clean)
 */
public record PollutionData(int chunkX, int chunkZ, List<PollutantEntry> entries) {

    /** Returns true if this chunk has no measurable pollution. */
    public boolean isEmpty() {
        return entries == null || entries.isEmpty();
    }

    /** Returns the maximum pollution factor across all pollutants, or 0 if empty. */
    public float maxFactor() {
        if (entries == null) return 0.0f;
        float max = 0.0f;
        for (PollutantEntry e : entries) {
            if (e.factor() > max) max = e.factor();
        }
        return max;
    }
}
