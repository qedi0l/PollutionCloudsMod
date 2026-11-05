package com.qedi.pcloudsmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qedi.pcloudsmod.PCloudsMod;
import com.qedi.pcloudsmod.network.PollutionSyncManager;
import com.qedi.pcloudsmod.server.CloudPoint;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL46C;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Writes pollution cloud data (received from the server via
 * {@link PollutionSyncManager}) into the Iris SSBO declared by the
 * pollution_shader pack (bufferObject.0, binding 0).
 *
 * <h2>SSBO layout (std430)</h2>
 * <pre>
 *   int    positionCount          → offset 0,     padded to 16
 *   vec3   positions[1024]        → offset 16,    16 bytes each → ends at 16400
 *   vec3   sizes[1024]            → offset 16400, 16 bytes each → ends at 32784
 *   vec4   colors[1024]           → offset 32784, 16 bytes each → ends at 49168
 * </pre>
 *
 * <p>Unlike the old {@code GlassSsboWriter}, this class performs no chunk
 * scanning. All data arrives over the network from the server-side
 * {@code PollutionAggregator} and is stored in
 * {@link PollutionSyncManager#getLatestClouds()}.
 *
 * <p>Must only be called from the render thread.
 */
public final class PollutionSsboWriter {

    public static final int MAX_CLOUDS = 1024;

    /**
     * std430 layout:
     *   int    positionCount   → offset 0,     padded to 16
     *   vec3   positions[1024] → offset 16,    16 bytes each → ends at 16400
     *   vec3   sizes[1024]     → offset 16400, 16 bytes each → ends at 32784
     *   vec4   colors[1024]    → offset 32784, 16 bytes each → ends at 49168
     */
    public static final int SSBO_SIZE = 49168;

    private static final int POSITIONS_OFFSET = 16;
    private static final int VEC3_STRIDE = 16;
    private static final int SIZES_OFFSET = 16400;
    private static final int SIZES_STRIDE = 16;
    private static final int COLORS_OFFSET = 32784;
    private static final int VEC4_STRIDE = 16;

    private static final ByteBuffer buffer =
            ByteBuffer.allocateDirect(SSBO_SIZE).order(ByteOrder.LITTLE_ENDIAN);

    // Tracks the last uploaded cloud list to avoid redundant GL uploads.
    private static List<CloudPoint> lastUploaded = List.of();

    // 0 = unknown, 1 = Iris present, -1 = Iris absent
    private static int irisState = 0;

    // Cached SSBO lookup; invalidated when the Iris pipeline (re)builds.
    private static volatile Object cachedPipeline;
    private static volatile int cachedSsboId = 0;
    private static volatile boolean ssboAcquiredLogged = false;

    private PollutionSsboWriter() {
    }

    /**
     * Called from {@link RenderLevelStageEvent} on the render thread.
     * Reads the latest cloud data from {@link PollutionSyncManager} and
     * uploads it to the Iris SSBO if it has changed since the last upload.
     */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!isSsboReady()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        List<CloudPoint> clouds = PollutionSyncManager.getLatestClouds();

        // Skip upload if data hasn't changed since last frame.
        if (clouds.equals(lastUploaded)) {
            return;
        }

        upload(clouds);
    }

    /**
     * Uploads cloud positions, radii, and colors to the SSBO.
     *
     * @param clouds the cloud points to upload (may be empty)
     */
    private static void upload(List<CloudPoint> clouds) {
        RenderSystem.assertOnRenderThread();

        int ssboId = getIrisSsboId();
        if (ssboId == 0) {
            return;
        }
        // The pipeline may have been rebuilt (and its buffers deleted) since
        // the cached id was taken.
        if (!GL46C.glIsBuffer(ssboId)) {
            cachedSsboId = 0;
            return;
        }

        int count = Math.min(clouds.size(), MAX_CLOUDS);

        // Write the int count at offset 0.
        buffer.clear();
        buffer.putInt(0, count);
        buffer.limit(4);
        GL46C.glNamedBufferSubData(ssboId, 0, buffer);

        if (count > 0) {
            // ── Write vec3 positions starting at offset 16 ──
            int posByteCount = count * VEC3_STRIDE;
            buffer.clear();
            int offset = 0;
            for (int i = 0; i < count; i++) {
                CloudPoint cp = clouds.get(i);
                buffer.putFloat(offset,      cp.x());
                buffer.putFloat(offset + 4,  cp.y());
                buffer.putFloat(offset + 8,  cp.z());
                // 4 bytes padding — std430 vec3 stride is 16, not 12.
                offset += VEC3_STRIDE;
            }
            buffer.limit(posByteCount);
            GL46C.glNamedBufferSubData(ssboId, POSITIONS_OFFSET, buffer);

            // ── Write vec3 sizes starting at SIZES_OFFSET ──
            int sizeByteCount = count * SIZES_STRIDE;
            buffer.clear();
            offset = 0;
            for (int i = 0; i < count; i++) {
                CloudPoint cp = clouds.get(i);
                buffer.putFloat(offset,      cp.sizeX());
                buffer.putFloat(offset + 4,  cp.sizeY());
                buffer.putFloat(offset + 8,  cp.sizeZ());
                // 4 bytes padding — std430 vec3 stride is 16, not 12.
                offset += SIZES_STRIDE;
            }
            buffer.limit(sizeByteCount);
            GL46C.glNamedBufferSubData(ssboId, SIZES_OFFSET, buffer);

            // ── Write vec4 colors starting at COLORS_OFFSET ──
            int colorByteCount = count * VEC4_STRIDE;
            buffer.clear();
            offset = 0;
            for (int i = 0; i < count; i++) {
                int argb = clouds.get(i).color();
                // ARGB: 0xAARRGGBB → unpack to RGBA floats
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                buffer.putFloat(offset,      r / 255.0f);
                buffer.putFloat(offset + 4,  g / 255.0f);
                buffer.putFloat(offset + 8,  b / 255.0f);
                buffer.putFloat(offset + 12, a / 255.0f);
                offset += VEC4_STRIDE;
            }
            buffer.limit(colorByteCount);
            GL46C.glNamedBufferSubData(ssboId, COLORS_OFFSET, buffer);
        }

        // Make the update visible to the next pass that reads the SSBO.
        GL46C.glMemoryBarrier(GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);

        lastUploaded = clouds;
        PCloudsMod.LOGGER.debug("Uploaded {} pollution clouds to Iris SSBO {}", count, ssboId);

        // Read back and log to verify GPU-side data.
        // readbackAndLog(ssboId, count);
    }

    /**
     * Reads back the SSBO contents from the GPU and prints them to the log.
     * Uses {@code glGetNamedBufferSubData} to copy data from the named buffer
     * into client-side memory, then logs the count and first few entries.
     *
     * @param ssboId the GL buffer object id
     * @param count  the number of cloud entries expected
     */
    private static void readbackAndLog(int ssboId, int count) {
        try {
            // ── Read back the int count at offset 0 ──
            ByteBuffer countBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);
            GL46C.glGetNamedBufferSubData(ssboId, 0, countBuf);
            countBuf.rewind();
            int readCount = countBuf.getInt();

            PCloudsMod.LOGGER.info("[SSBO Readback] count = {} (expected {})", readCount, count);

            if (readCount <= 0) {
                return;
            }

            int logCount = Math.min(readCount, 5); // log first 5 entries

            // ── Read back positions (vec3, stride 16) ──
            int posBytes = logCount * VEC3_STRIDE;
            ByteBuffer posBuf = ByteBuffer.allocateDirect(posBytes).order(ByteOrder.LITTLE_ENDIAN);
            GL46C.glGetNamedBufferSubData(ssboId, POSITIONS_OFFSET, posBuf);
            posBuf.rewind();

            // ── Read back sizes (vec3, stride 16) ──
            int sizeBytes = logCount * VEC3_STRIDE;
            ByteBuffer sizeBuf = ByteBuffer.allocateDirect(sizeBytes).order(ByteOrder.LITTLE_ENDIAN);
            GL46C.glGetNamedBufferSubData(ssboId, SIZES_OFFSET, sizeBuf);
            sizeBuf.rewind();

            // ── Read back colors (vec4, stride 16) ──
            int colorBytes = logCount * VEC4_STRIDE;
            ByteBuffer colorBuf = ByteBuffer.allocateDirect(colorBytes).order(ByteOrder.LITTLE_ENDIAN);
            GL46C.glGetNamedBufferSubData(ssboId, COLORS_OFFSET, colorBuf);
            colorBuf.rewind();

            // ── Log entries ──
            PCloudsMod.LOGGER.info("[SSBO Readback] First {} entries:", logCount);
            for (int i = 0; i < logCount; i++) {
                float px = posBuf.getFloat(i * VEC3_STRIDE);
                float py = posBuf.getFloat(i * VEC3_STRIDE + 4);
                float pz = posBuf.getFloat(i * VEC3_STRIDE + 8);

                float sx = sizeBuf.getFloat(i * VEC3_STRIDE);
                float sy = sizeBuf.getFloat(i * VEC3_STRIDE + 4);
                float sz = sizeBuf.getFloat(i * VEC3_STRIDE + 8);

                float cr = colorBuf.getFloat(i * VEC4_STRIDE);
                float cg = colorBuf.getFloat(i * VEC4_STRIDE + 4);
                float cb = colorBuf.getFloat(i * VEC4_STRIDE + 8);
                float ca = colorBuf.getFloat(i * VEC4_STRIDE + 12);

                PCloudsMod.LOGGER.info("  [{}] pos=({}, {}, {}) size=({}, {}, {}) color=({}, {}, {}, {})",
                        i, px, py, pz, sx, sy, sz, cr, cg, cb, ca);
            }
        } catch (Exception e) {
            PCloudsMod.LOGGER.warn("[SSBO Readback] Failed to read back SSBO data", e);
        }
    }

    /**
     * Returns the GL buffer object id of the SSBO Iris created for index 0,
     * or 0 if it is not available.
     */
    private static int getIrisSsboId() {
        try {
            Object pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (!(pipeline instanceof IrisRenderingPipeline irisPipeline)) {
                return 0;
            }

            // Fast path: the pipeline hasn't changed since the last lookup.
            if (pipeline == cachedPipeline && cachedSsboId != 0) {
                return cachedSsboId;
            }

            Field holderField = IrisRenderingPipeline.class.getDeclaredField("shaderStorageBufferHolder");
            holderField.setAccessible(true);
            Object holder = holderField.get(irisPipeline);
            if (!(holder instanceof ShaderStorageBufferHolder ssboHolder)) {
                return 0;
            }

            int id;
            try {
                id = ssboHolder.getBufferIndex(0);
            } catch (RuntimeException e) {
                id = 0;
            }

            cachedPipeline = pipeline;
            cachedSsboId = id;

            if (id != 0 && !ssboAcquiredLogged) {
                ssboAcquiredLogged = true;
                PCloudsMod.LOGGER.info("Acquired Iris SSBO id {} for bufferObject.0", id);
            }
            return id;
        } catch (Exception e) {
            PCloudsMod.LOGGER.warn("Failed to query Iris SSBO id", e);
            return 0;
        }
    }

    /** True if Iris is installed AND the active shader pack declares SSBO index 0. */
    private static boolean isSsboReady() {
        if (irisState == 0) {
            try {
                Class.forName("net.irisshaders.iris.Iris");
                irisState = 1;
            } catch (ClassNotFoundException e) {
                irisState = -1;
                return false;
            }
        }
        if (irisState != 1) {
            return false;
        }

        return Iris.getCurrentPack()
                .map(pack -> pack.getBufferObjects().containsKey(0))
                .orElse(false);
    }
}
