package com.qedi.pcloudsmod.common;

import com.endertech.minecraft.forge.world.BiomeId;
import com.endertech.minecraft.mods.adpother.AdPother;
import com.endertech.minecraft.mods.adpother.blocks.Pollutant;
import com.endertech.minecraft.mods.adpother.pollution.ChunkPollution;
import com.endertech.minecraft.mods.adpother.pollution.WorldData;
import com.qedi.pcloudsmod.PCloudsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps all AdPother API access behind a single class.
 *
 * <p>This is the <b>only</b> class in the mod that imports AdPother types.
 * If AdPother's API changes between versions, only this file needs updating.
 *
 * <p>AdPother is a compile-only dependency. At runtime the mod checks
 * {@link ModList#get().isLoaded(String)} and degrades gracefully when
 * AdPother is absent.
 */
public final class PollutionAccessor {

    private static final String ADPOTHER_MOD_ID = "adpother";

    private PollutionAccessor() {
    }

    /**
     * Returns true if AdPother is installed and loaded.
     * Cached after the first call — the mod list does not change at runtime.
     */
    public static boolean isAvailable() {
        try {
            return ModList.get().isLoaded(ADPOTHER_MOD_ID);
        } catch (Exception e) {
            PCloudsMod.LOGGER.warn("Failed to check if AdPother is loaded", e);
            return false;
        }
    }

    /**
     * Reads the pollution state of the chunk containing {@code pos} from AdPother.
     *
     * <p>Returns a {@link PollutionData} record with one {@link PollutantEntry}
     * per pollutant that has a non-zero quantity in this chunk.
     *
     * @param level the server level (must be a {@link ServerLevel})
     * @param pos   any block position inside the target chunk
     * @return pollution data for the chunk, or an empty record if AdPother is absent
     */
    public static PollutionData getChunkPollution(ServerLevel level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        if (!isAvailable()) {
            return new PollutionData(chunkX, chunkZ, List.of());
        }

        try {
            ChunkPollution chunkPollution = WorldData.getChunkPollution(level, pos);
            BiomeId biome = BiomeId.from(level, pos);

            List<PollutantEntry> entries = new ArrayList<>();
            chunkPollution.getInfos().forEach(info -> {
                if (info.isEmpty()) {
                    return;
                }
                Pollutant<?> pollutant = info.getPollutant();
                float factor = info.getFactorIn(biome);
                int color = pollutant.getColor().getARGB();
                int altitude = pollutant.getConcentrationAltitudeIn(biome);
                entries.add(new PollutantEntry(factor, color, altitude));
            });

            return new PollutionData(chunkX, chunkZ, entries);
        } catch (Exception e) {
            PCloudsMod.LOGGER.error("Failed to read chunk pollution at ({}, {})", pos.getX(), pos.getZ(), e);
            return new PollutionData(chunkX, chunkZ, List.of());
        }
    }

    /**
     * Returns the ARGB color of a pollutant by its registry name.
     * Used for debugging / logging purposes.
     */
    public static int getPollutantColor(String name) {
        if (!isAvailable()) {
            return 0;
        }
        try {
            return AdPother.getInstance()
                    .pollutants
                    .findByName(name)
                    .map(p -> p.getColor().getARGB())
                    .orElse(0);
        } catch (Exception e) {
            PCloudsMod.LOGGER.warn("Failed to get color for pollutant '{}'", name, e);
            return 0;
        }
    }
}
