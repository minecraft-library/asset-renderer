package lib.minecraft.refharness.pip;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The offscreen picture-in-picture target every frame renderer draws through - a reused RGBA8 colour
 * texture plus a DEPTH32 companion, an orthographic projection, and the asynchronous read-back that
 * turns the colour texture into a PNG.
 *
 * <p>The textures are reallocated only when the requested canvas changes size, and a replaced set is
 * RETIRED rather than closed: the read-back completes on a later frame, so releasing one the moment a
 * differently-sized canvas arrives would hand a pending copy a released texture, and closing at end of
 * sweep would hand it a zero-sized image. Retirement is what lets several renders be in flight, which
 * is what lets a tick carry more than one of them.
 *
 * <p>This is a collaborator a renderer calls, not a base class that calls the renderer. That is what
 * lets a renderer decline a subject before any GPU work happens - no texture is allocated, cleared
 * or bound for a subject that is never drawn.
 */
public final class PipTarget implements AutoCloseable {

    private final String debugName;
    private final float depthRange;
    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionMatrixBuffer;

    private GpuTexture colorTexture;
    private GpuTextureView colorTextureView;
    private GpuTexture depthTexture;
    private GpuTextureView depthTextureView;
    private int textureWidth;
    private int textureHeight;

    /**
     * Textures a resize replaced, held until enough later renders have gone by that any copy queued
     * against them has certainly executed, then closed.
     *
     * <p><b>Retiring rather than closing is what lets more than one render be in flight.</b> The
     * read-back's copy runs on a later frame, so closing a texture the moment a differently-sized
     * canvas arrives hands that copy a released texture - which the old one-subject-per-tick pacing
     * made unreachable by timing rather than by construction, a tick being far longer than a copy.
     * A generation counter is the construction: a retired set is closed once {@link #RETIRE_LAG}
     * further renders have been queued behind it, which bounds the held memory at a handful of
     * canvases rather than one per distinct size.
     */
    private final Deque<Retired> retired = new ArrayDeque<>();

    private long generation;

    /**
     * How many renders must be queued behind a retired texture before it is closed.
     *
     * <p><b>It scales with the pacing rather than being a constant, and that is the whole point.</b> A
     * copy lands a frame or two after it is queued, and a tick is never shorter than a frame - so a
     * lag of two ticks' worth of renders outlives any copy whatever the pacing. A fixed eight would
     * have been under one tick's work as soon as the pacing passed eight per tick, which is the same
     * class of accident as the released-texture race this queue exists to remove: correct only while
     * a number nobody was watching stayed large enough.
     */
    private static final int RETIRE_LAG = Math.max(8, 2 * HarnessConfig.RENDERS_PER_TICK);

    /**
     * Constructs a new {@code PipTarget}.
     *
     * @param debugName the label the GPU device tags this target's allocations with
     * @param depthRange the half-extent of the orthographic depth range, in model units
     */
    public PipTarget(String debugName, float depthRange) {
        this.debugName = debugName;
        this.depthRange = depthRange;
        this.projectionMatrixBuffer = new ProjectionMatrixBuffer("refharness " + debugName + " PIP");
    }

    /**
     * Draws one frame and writes it as a PNG.
     *
     * @param client the running client, for the per-frame render handles
     * @param canvas the pixel canvas to draw onto
     * @param out the output path; parent directories are created on demand
     * @param body the subject-specific pose and submit step
     * @return whether a PNG was written; {@code false} means {@code body} abandoned the frame
     * @throws IOException if the PNG write fails
     */
    public boolean draw(Minecraft client, Canvas canvas, Path out, PipDraw body) throws IOException {
        ensureTextures(canvas.width(), canvas.height());

        FeatureRenderDispatcher fed = client.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage storage = fed.getSubmitNodeStorage();
        MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();
        Lighting lighting = client.gameRenderer.getLighting();

        GpuDevice device = RenderSystem.getDevice();
        device.createCommandEncoder().clearColorAndDepthTextures(colorTexture, 0, depthTexture, 1.0);

        RenderSystem.outputColorTextureOverride = colorTextureView;
        RenderSystem.outputDepthTextureOverride = depthTextureView;
        try {
            projection.setupOrtho(-depthRange, depthRange, textureWidth, textureHeight, /*invertY*/ true);
            RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);

            if (!body.submit(new PipScope(client, textureWidth, textureHeight, fed, storage, bufferSource, lighting)))
                return false;

            fed.renderAllFeatures();
            bufferSource.endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }

        // The extent is handed in rather than read inside, because the read-back completes on a later
        // frame and these two fields are reset by ensureTextures / close.
        generation++;
        TextureReadback.writeToPng(colorTexture, textureWidth, textureHeight, debugName, out);
        return true;
    }

    private void ensureTextures(int width, int height) {
        releaseRetired();
        if (colorTexture != null && textureWidth == width && textureHeight == height) return;
        retireTextures();

        GpuDevice device = RenderSystem.getDevice();
        // Usage flags 13 = COPY_SRC | COPY_DST | RENDER_ATTACHMENT | TEXTURE_BINDING (the mask
        // vanilla's GUI item atlas / picture-in-picture renderer use for colour attachments). 9 for
        // depth = COPY_SRC | RENDER_ATTACHMENT | DEPTH_STENCIL_ATTACHMENT.
        colorTexture = device.createTexture(() -> "refharness " + debugName + " color", 13,
            TextureFormat.RGBA8, width, height, 1, 1);
        colorTextureView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture(() -> "refharness " + debugName + " depth", 9,
            TextureFormat.DEPTH32, width, height, 1, 1);
        depthTextureView = device.createTextureView(depthTexture);
        textureWidth = width;
        textureHeight = height;
    }

    /** Hands the current textures to the retirement queue, which closes them once the lag has passed. */
    private void retireTextures() {
        if (colorTexture == null) { closeTextures(); return; }
        retired.addLast(new Retired(generation, colorTexture, colorTextureView, depthTexture, depthTextureView));
        colorTexture = null;
        colorTextureView = null;
        depthTexture = null;
        depthTextureView = null;
        textureWidth = 0;
        textureHeight = 0;
    }

    private void releaseRetired() {
        while (!retired.isEmpty() && generation - retired.peekFirst().generation() >= RETIRE_LAG)
            retired.removeFirst().close();
    }

    private void closeTextures() {
        if (colorTexture != null) { colorTexture.close(); colorTexture = null; }
        if (colorTextureView != null) { colorTextureView.close(); colorTextureView = null; }
        if (depthTexture != null) { depthTexture.close(); depthTexture = null; }
        if (depthTextureView != null) { depthTextureView.close(); depthTextureView = null; }
        textureWidth = 0;
        textureHeight = 0;
    }

    /**
     * Releases nothing a copy could still be reading.
     *
     * <p>The retirement queue is dropped rather than drained for the reason the live textures are:
     * the last renders' copies are in flight when a run ends, and closing into one of those reads a
     * zero-sized image. The JVM exit is what releases them.
     */
    @Override
    public void close() {
        projectionMatrixBuffer.close();
    }

    /**
     * One resize's worth of textures and the render generation they were replaced at.
     *
     * @param generation the render count at which these were retired
     * @param color the colour texture
     * @param colorView its view
     * @param depth the depth texture
     * @param depthView its view
     */
    private record Retired(long generation, GpuTexture color, GpuTextureView colorView,
                           GpuTexture depth, GpuTextureView depthView) {

        void close() {
            color.close();
            colorView.close();
            depth.close();
            depthView.close();
        }
    }
}
