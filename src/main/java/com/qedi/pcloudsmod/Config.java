package com.qedi.pcloudsmod;

import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ─── Pollution Cloud Settings ──────────────────────────────────────────

    public static final ModConfigSpec.BooleanValue CLOUD_ENABLED = BUILDER
            .comment("Whether pollution cloud rendering is enabled.")
            .define("cloud_enabled", true);

    public static final ModConfigSpec.DoubleValue CLOUD_BASE_RADIUS = BUILDER
            .comment("Base cloud minimum vertical thickness.")
            .defineInRange("cloud_base_radius", 2.0, 0.5, 16.0);

    public static final ModConfigSpec.DoubleValue CLOUD_MAX_RADIUS = BUILDER
            .comment("Maximum cloud thickness at full pollution factor.")
            .defineInRange("cloud_max_radius", 8.0, 1.0, 64.0);

    public static final ModConfigSpec.IntValue CLOUD_SCAN_CHUNKS = BUILDER
            .comment("Radius of the server-side chunk scan area in chunks 2 (+1 from player) = 5×5 area.")
            .defineInRange("cloud_scan_chunks", 2, 1, 5);

    public static final ModConfigSpec.IntValue CLOUD_SYNC_INTERVAL_TICKS = BUILDER
            .comment("How often (in ticks) to sync cloud data to clients. 20 = 1 second.")
            .defineInRange("cloud_sync_interval_ticks", 20, 5, 200);

    public static final ModConfigSpec.IntValue CLOUD_QUERY_RADIUS = BUILDER
            .comment("Client-side clouds cache radius (in chunks).")
            .defineInRange("cloud_query_radius", 16, 1, 64);

    static final ModConfigSpec SPEC = BUILDER.build();
}
