package com.qedi.pcloudsmod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.qedi.pcloudsmod.network.PollutionPayloadHandler;
import com.qedi.pcloudsmod.server.CloudCache;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(PCloudsMod.MODID)
public class PCloudsMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "pcloudsmod";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public PCloudsMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (PSLGMod) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register network payload handlers on the mod event bus
        modEventBus.register(PollutionPayloadHandler.class);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[PCLoudsMod] Common setup complete. Pollution clouds enabled: {}", Config.CLOUD_ENABLED.get());
    }

    // ── Cloud cache persistence ────────────────────────────────────────────

    /**
     * Called when a level (dimension) is loaded. Loads the cloud cache
     * from disk for that level.
     */
    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        CloudCache cache = CloudCache.forLevel(serverLevel);
        cache.load(server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * Called when a level (dimension) is unloaded. Saves the cloud cache
     * to disk for that level.
     */
    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        CloudCache cache = CloudCache.removeForLevel(serverLevel);
        if (cache != null && cache.isDirty()) {
            cache.save(server.getWorldPath(LevelResource.ROOT));
        }
    }

    /**
     * Called when the server is stopping. Saves all level cloud caches to
     * disk as a safety net (in case unload events did not fire for every level).
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        var root = server.getWorldPath(LevelResource.ROOT);
        for (ServerLevel level : server.getAllLevels()) {
            CloudCache cache = CloudCache.forLevel(level);
            if (cache.isDirty()) {
                cache.save(root);
            }
        }
    }
}
