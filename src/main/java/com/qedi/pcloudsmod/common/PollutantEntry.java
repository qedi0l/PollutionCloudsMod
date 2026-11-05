package com.qedi.pcloudsmod.common;

/**
 * A single pollutant's contribution to a chunk's pollution.
 *
 * @param factor  Normalized pollution level (0.0+; 1.0 = critical amount reached)
 * @param color   ARGB color of the pollutant (0xAARRGGBB)
 * @param altitude Y-level where this pollutant accumulates in the given biome
 */
public record PollutantEntry(float factor, int color, int altitude) {
}
