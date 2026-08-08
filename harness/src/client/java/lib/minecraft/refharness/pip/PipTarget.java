package lib.minecraft.refharness.pip;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import lib.minecraft.refharness.api.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The offscreen picture-in-picture target every frame renderer draws through - a reused RGBA8 colour
 * texture plus a DEPTH32 companion, an orthographic projection, and the asynchronous read-back that
 * turns the colour texture into a PNG.
 *
 * <p>The textures are reallocated only when the requested canvas changes size, and are never closed
 * while a sweep is running: the read-back completes on a later frame, so closing them at end of
 * sweep would hand the pending copy a zero-sized image. They are left to the JVM exit instead.
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

        writeTextureToPng(out);
        return true;
    }

    private void ensureTextures(int width, int height) {
        if (colorTexture != null && textureWidth == width && textureHeight == height) return;
        closeTextures();

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

    private void writeTextureToPng(Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        // Capture as locals: copyTextureToBuffer's callback runs async (pending tasks are executed on
        // a later frame). By the time it fires, this target's textureWidth / textureHeight may have
        // been reset by close() / ensureTextures(), and reading them as 0 produces NativeImage(0, 0)
        // -> IllegalArgumentException.
        final int width = textureWidth;
        final int height = textureHeight;
        final int pixelSize = colorTexture.getFormat().pixelSize();
        long byteSize = (long) width * height * pixelSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "refharness-" + debugName + "-readback", /*usage*/ 9, byteSize);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        IOException[] err = new IOException[1];

        encoder.copyTextureToBuffer(colorTexture, buffer, 0L, () -> {
            try (GpuBuffer.MappedView read = encoder.mapBuffer(buffer, true, false);
                 NativeImage image = new NativeImage(width, height, false)) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = read.data().getInt((x + y * width) * pixelSize);
                        // Y-flip + alpha preservation for transparent backgrounds. PIP textures are
                        // bottom-up like OpenGL framebuffers.
                        image.setPixelABGR(x, height - y - 1, argb);
                    }
                }
                image.writeToFile(outputPath);
            } catch (IOException ex) {
                err[0] = ex;
            } finally {
                buffer.close();
            }
        }, /*mipLevel*/ 0);

        if (err[0] != null) throw err[0];
    }

    private void closeTextures() {
        if (colorTexture != null) { colorTexture.close(); colorTexture = null; }
        if (colorTextureView != null) { colorTextureView.close(); colorTextureView = null; }
        if (depthTexture != null) { depthTexture.close(); depthTexture = null; }
        if (depthTextureView != null) { depthTextureView.close(); depthTextureView = null; }
        textureWidth = 0;
        textureHeight = 0;
    }

    @Override
    public void close() {
        closeTextures();
        projectionMatrixBuffer.close();
    }
}
