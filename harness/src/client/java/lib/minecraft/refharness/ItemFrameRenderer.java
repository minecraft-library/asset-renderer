package lib.minecraft.refharness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders an {@link ItemStack} via the vanilla GUI item pipeline ({@link ItemModelResolver}
 * + {@link Lighting.Entry#ITEMS_3D ITEMS_3D} lighting + {@link FeatureRenderDispatcher})
 * to an offscreen RGBA8 texture, then reads it back as a PNG. Modeled on
 * {@code GuiItemAtlas.drawToSlot} and {@code OversizedItemRenderer.renderToTexture} - the
 * same code paths the vanilla inventory uses to draw block-as-item icons.
 *
 * <p>Why this exists: {@code BlockSweeper}'s old approach placed blocks in-world and
 * captured the main framebuffer at the iso camera pose. That picks up vanilla's
 * <em>world</em>-rendering shading (Lambertian diffuse via {@link Lighting.Entry#LEVEL})
 * for block-entity renderers (chest, sign, banner, ...), which doesn't match
 * asset-renderer's inventory-style ground truth. Rendering through this PIP pipeline
 * uses the same lighting + pose ({@code [30, 225, 0]} GUI display transform) the actual
 * inventory uses, so BERs come out shaded the same way asset-renderer expects.
 *
 * <p>Shares allocations across calls: the color/depth textures are reused while the
 * requested {@code width x height} stays constant. Single-instance, owned by
 * {@code BlockSweeper} for the duration of the block phase.
 */
public final class ItemFrameRenderer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Packed light value for "fully lit": skylight 15 << 20 | blocklight 15 << 4 = 15728880.
     * This is what {@code GuiItemAtlas.drawToSlot} passes - inventory items are always
     * full-bright so their visible shade comes purely from the diffuse lighting + per-face
     * cardinal shade, not from any per-block-position light value.
     */
    private static final int FULL_BRIGHT_LIGHT = 15728880;

    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionMatrixBuffer = new ProjectionMatrixBuffer("refharness item PIP");

    private GpuTexture colorTexture;
    private GpuTextureView colorTextureView;
    private GpuTexture depthTexture;
    private GpuTextureView depthTextureView;
    private int textureWidth;
    private int textureHeight;

    /**
     * Renders the given stack into the internal texture and writes the texture's pixels to
     * {@code outputPath} as a PNG. Synchronous: blocks the calling thread (the render
     * thread, since we use {@link RenderSystem}) until the GPU readback completes.
     *
     * @param client the active client; supplies the {@link ItemModelResolver},
     *               {@link FeatureRenderDispatcher}, {@link Lighting}, and
     *               {@link MultiBufferSource.BufferSource}
     * @param stack the item to render. Empty stacks are silently skipped
     * @param size the square edge length (pixels) of the output PNG
     * @param outputPath where to write the PNG; parent directories are created on demand
     * @throws IOException if the PNG file write fails
     */
    public void renderAndWrite(Minecraft client, ItemStack stack, int size, Path outputPath) throws IOException {
        if (stack.isEmpty()) return;
        ensureTextures(size, size);

        Level level = client.level;
        ItemModelResolver resolver = client.getItemModelResolver();
        TrackingItemStackRenderState state = new TrackingItemStackRenderState();
        // ItemDisplayContext.GUI: applies the model's "gui" display transform ([30, 225, 0]
        // for vanilla blocks). Owner=null is fine for non-equipped items - matches what
        // GuiGraphicsExtractor.fakeItem passes.
        resolver.updateForTopItem(state, stack, ItemDisplayContext.GUI, level, /*owner*/ null, /*seed*/ 0);

        FeatureRenderDispatcher fed = client.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage storage = fed.getSubmitNodeStorage();
        MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();
        Lighting lighting = client.gameRenderer.getLighting();

        GpuDevice device = RenderSystem.getDevice();
        device.createCommandEncoder().clearColorAndDepthTextures(colorTexture, 0, depthTexture, 1.0);

        RenderSystem.outputColorTextureOverride = colorTextureView;
        RenderSystem.outputDepthTextureOverride = depthTextureView;
        try {
            projection.setupOrtho(-1000.0f, 1000.0f, textureWidth, textureHeight, /*invertY*/ true);
            RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);

            PoseStack poseStack = new PoseStack();
            poseStack.translate(textureWidth / 2.0f, textureHeight / 2.0f, 0.0f);
            // Item models are unit-scale (1 model unit = 1 inventory slot = 16 pixels in
            // GUI). Scale by `size` so the model fills the texture; the GUI display
            // transform on the model itself ([30, 225, 0] + ~0.625 scale) leaves a small
            // margin around the item, which matches the inventory icon's visible footprint.
            // -Y so screen-down maps to model-down (GuiItemAtlas uses -slotTextureSize for Y);
            // -Z because PIP uses -scale on Z too (matches PictureInPictureRenderer).
            poseStack.scale(textureWidth, -textureHeight, textureWidth);

            // ITEMS_3D = inventory iso pose lighting for block items (uses block light).
            // ITEMS_FLAT for 2D item icons (no block light). Matches GuiItemAtlas's branch.
            lighting.setupFor(state.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);

            // Submit the item's geometry into the SubmitNodeStorage, then run the
            // feature-renderer dispatcher (which actually draws to the bound textures via
            // the bufferSource), then flush the bufferSource so all batched render-types
            // are committed to the texture before readback.
            state.submit(poseStack, storage, FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, /*seed*/ 0);
            fed.renderAllFeatures();
            bufferSource.endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }

        writeTextureToPng(outputPath);
    }

    private void ensureTextures(int width, int height) {
        if (colorTexture != null && textureWidth == width && textureHeight == height) return;
        closeTextures();

        GpuDevice device = RenderSystem.getDevice();
        // Usage flags 13 = COPY_SRC | COPY_DST | RENDER_ATTACHMENT | TEXTURE_BINDING (the
        // mask GuiItemAtlas / PictureInPictureRenderer use for color attachments). 9 for
        // depth = COPY_SRC | RENDER_ATTACHMENT | DEPTH_STENCIL_ATTACHMENT.
        colorTexture = device.createTexture(() -> "refharness item color", 13,
            TextureFormat.RGBA8, width, height, 1, 1);
        colorTextureView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture(() -> "refharness item depth", 9,
            TextureFormat.DEPTH32, width, height, 1, 1);
        depthTextureView = device.createTextureView(depthTexture);
        textureWidth = width;
        textureHeight = height;
    }

    private void writeTextureToPng(Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        // Capture as locals: copyTextureToBuffer's callback runs async (RenderSystem
        // executePendingTasks on a later frame). By the time it fires, this renderer's
        // textureWidth/textureHeight may have been reset by close() / ensureTextures(),
        // and reading them as 0 produces NativeImage(0, 0) -> IllegalArgumentException.
        final int width = textureWidth;
        final int height = textureHeight;
        final int pixelSize = colorTexture.getFormat().pixelSize();
        long byteSize = (long) width * height * pixelSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "refharness-item-readback", /*usage*/ 9, byteSize);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        IOException[] err = new IOException[1];

        encoder.copyTextureToBuffer(colorTexture, buffer, 0L, () -> {
            try (GpuBuffer.MappedView read = encoder.mapBuffer(buffer, true, false);
                 NativeImage image = new NativeImage(width, height, false)) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = read.data().getInt((x + y * width) * pixelSize);
                        // Y-flip + alpha preservation for transparent backgrounds, same as
                        // IsoRenderer.writeRenderTarget. PIP textures are bottom-up like
                        // OpenGL framebuffers.
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
