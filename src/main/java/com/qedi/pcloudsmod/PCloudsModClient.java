package com.qedi.pcloudsmod;

import com.qedi.pcloudsmod.client.PollutionSsboWriter;
import com.qedi.pcloudsmod.network.CloudQueryPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.PacketDistributor;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = PCloudsMod.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = PCloudsMod.MODID, value = Dist.CLIENT)
public class PCloudsModClient {

    /** Client-side tick counter for periodic cache queries. */
    private static int queryTickCounter = 0;

    public PCloudsModClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        PCloudsMod.LOGGER.info("HELLO FROM CLIENT SETUP");
        PCloudsMod.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        PollutionSsboWriter.onRenderLevelStage(event);
    }

    /**
     * Called every client tick. Periodically sends a {@link CloudQueryPayload}
     * to the server to request cloud data from the server-side cache.
     *
     * <p>The query is sent every {@code cloud_sync_interval_ticks} ticks
     * (same interval as the server's periodic sync), using the client's
     * configurable {@code cloud_query_radius}.
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!Config.CLOUD_ENABLED.get()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        int interval = Config.CLOUD_SYNC_INTERVAL_TICKS.get();
        queryTickCounter++;
        if (queryTickCounter < interval) {
            return;
        }
        queryTickCounter = 0;

        int radius = Config.CLOUD_QUERY_RADIUS.get();
        PacketDistributor.sendToServer(new CloudQueryPayload(radius));
    }
}
