package lib.minecraft.refharness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders a {@link BlockState} directly through the vanilla block-model rendering pipeline
 * ({@link SubmitNodeStorage#submitBlockModel}), bypassing the item-model dispatch that
 * {@link ItemFrameRenderer} relies on. The two paths converge for full-cube blocks whose item
 * model inherits from {@code block/block.json}, but they diverge for blocks whose item model
 * uses {@code item/generated} as its parent (rails, vines, ladders, lily_pad, seagrass,
 * sculk_vein, doors, hanging signs, ...). Those blocks render as flat 2D billboards through
 * the inventory path; this renderer always emits the actual 3D block geometry at the standard
 * iso pose, which is what asset-renderer's {@code BlockRenderer.ISOMETRIC_3D} produces.
 *
 * <p>Pose chain (col-vector form, applied right-to-left to a model vertex):
 * <pre>
 *   T(canvasW/2, canvasH/2, 0)  // center on canvas
 *   × S(canvasW, -canvasH, canvasW)  // 1 model unit -> canvasW pixels, Y-down -> Y-up
 *   × R_XYZ(30°, 225°, 0°)  // vanilla block display.gui rotation
 *   × S(0.625)              // vanilla block display.gui scale
 *   × T(-0.5, -0.5, -0.5)   // center block model (baked in [0,1] coords) on origin
 * </pre>
 *
 * <p>Tints are not resolved (passes {@code int[0]} per part). Biome-tinted blocks (grass_block,
 * oak_leaves, water) consequently render at their untinted texture colour rather than the
 * Plains-biome inventory icon vanilla draws. Acceptable for the iso-3D-geometry parity goal;
 * tint resolution can be layered on later via a {@link net.minecraft.client.renderer.block.MovingBlockRenderState}
 * stub if needed.
 *
 * <p>Lifecycle mirrors {@link ItemFrameRenderer}: PIP textures are reused across calls while
 * the requested {@code width × height} stays constant, and the textures are deliberately leaked
 * past {@link #close} to avoid racing the async GPU readback callback.
 */
public final class BlockFrameRenderer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Packed light value for "fully lit"; same constant {@link ItemFrameRenderer} uses. Inventory
     * icons are always full-bright so their visible shade comes from the diffuse lighting and
     * per-face cardinal shade, not from any per-block-position light value.
     */
    private static final int FULL_BRIGHT_LIGHT = 15728880;

    /**
     * Empty tint-layer array, used for blocks with no registered {@link BlockTintSource} (the vast
     * majority). Biome-tinted and constant-tinted blocks resolve a real array via
     * {@link #resolveInventoryTints} instead; see that method for why the inventory (no-world)
     * colour is the correct ground truth.
     */
    private static final int[] NO_TINTS = new int[0];

    /**
     * Vanilla's standard {@code display.gui} pose for blocks. Pre-built once; same quaternion
     * vanilla bakes into {@code block/block.json}'s root display transform. Stairs / slabs /
     * fence gates author their own gui rotation in JSON; we intentionally ignore those for the
     * reference renders since the goal is a uniform iso pose for parity testing.
     */
    private static final Quaternionf BLOCK_GUI_ROTATION = new Quaternionf().rotationXYZ(
        (float) Math.toRadians(30),
        (float) Math.toRadians(225),
        0f
    );

    /**
     * Vanilla's {@code block/block.json} {@code display.gui.scale}. Applied after the iso
     * rotation; matches the iso-projected silhouette to ~88% of the canvas width.
     */
    private static final float BLOCK_GUI_SCALE = 0.625f;

    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionMatrixBuffer = new ProjectionMatrixBuffer("refharness block PIP");
    // Pinned-to-index-0 random so weighted variant lists (bedrock/stone/netherrack rotations)
    // always emit variants[0], matching asset-renderer's BlockStateLoader.parseVariants pick.
    // A live RandomSource.create() baked a random rotation into the reference, rotating the texture
    // noise relative to the asset on an otherwise byte-matching silhouette. See FirstVariantRandomSource.
    private final RandomSource random = new FirstVariantRandomSource();
    private final List<BlockStateModelPart> partsScratch = new ArrayList<>();

    private GpuTexture colorTexture;
    private GpuTextureView colorTextureView;
    private GpuTexture depthTexture;
    private GpuTextureView depthTextureView;
    private int textureWidth;
    private int textureHeight;

    /**
     * Renders the given {@code state} as an iso-pose block icon and writes the result PNG to
     * {@code outputPath}. The block's {@link BlockStateModel} is looked up from the active
     * {@link Minecraft#getModelManager() ModelManager} and submitted directly via
     * {@link SubmitNodeStorage#submitBlockModel}, so the output reflects the actual 3D block
     * geometry regardless of how the inventory item model would have routed it.
     *
     * @param client the active client; supplies the model manager, feature dispatcher,
     *               lighting, and buffer source
     * @param state the block state to render; defaults via {@code block.defaultBlockState()}
     * @param size the square edge length (pixels) of the output PNG
     * @param outputPath where to write the PNG; parent directories are created on demand
     * @throws IOException if the PNG file write fails
     */
    public void renderAndWrite(Minecraft client, BlockState state, int size, Path outputPath) throws IOException {
        ensureTextures(size, size);

        BlockStateModelSet modelSet = client.getModelManager().getBlockStateModelSet();
        BlockStateModel model = modelSet.get(state);
        if (model == null) {
            LOG.warn("BlockFrameRenderer: no BlockStateModel for {}", state);
            return;
        }

        partsScratch.clear();
        model.collectParts(random, partsScratch);
        if (partsScratch.isEmpty()) {
            LOG.warn("BlockFrameRenderer: empty parts list for {}", state);
            return;
        }

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
            poseStack.scale(textureWidth, -textureHeight, textureWidth);
            poseStack.mulPose(BLOCK_GUI_ROTATION);
            poseStack.scale(BLOCK_GUI_SCALE, BLOCK_GUI_SCALE, BLOCK_GUI_SCALE);
            poseStack.translate(-0.5f, -0.5f, -0.5f);

            // ITEMS_3D = inventory iso pose lighting (same setup the item path uses for block
            // items). Matches the lighting frame the asset-renderer entity pipeline already
            // mirrors via its L_kit pattern.
            lighting.setupFor(Lighting.Entry.ITEMS_3D);

            // Sheet choice: cutout covers solid + cutout (most blocks) and is the default
            // BlockModelRenderState picks when its translucent flag is false. Translucent
            // blocks (glass, water, leaves with transparency, ice) would technically want
            // Sheets.translucentBlockSheet(), but for parity comparison cutout works for
            // the majority and is the default vanilla submitBlockModel handles for moving
            // blocks too. Promote to translucent on a per-block basis if needed.
            RenderType renderType = Sheets.cutoutBlockSheet();

            // Resolve biome / constant tints to vanilla's INVENTORY colour (no world context), the
            // same value vanilla bakes into a block-item GUI icon. Without this, grass / leaves /
            // vine etc. rendered at their raw grayscale texture while asset-renderer tints them.
            int[] tints = resolveInventoryTints(client, state);

            storage.submitBlockModel(poseStack, renderType, partsScratch, tints,
                FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            fed.renderAllFeatures();
            bufferSource.endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }

        writeTextureToPng(outputPath);
    }

    /**
     * Resolves the per-tint-index colour array vanilla bakes into a block-item GUI icon.
     *
     * <p>Vanilla 26.1 resolves block tints through {@link BlockTintSource}: {@code color(state)} is
     * the no-world-context "in hand" colour (a block-item icon, a held block), while
     * {@code colorInWorld(state, level, pos)} samples the actual biome. The GUI inventory icon uses
     * {@code color(state)}, which for grass / foliage returns the colormap DEFAULT
     * ({@code GrassColor.getDefaultColor()} = colormap centre, temperature 0.5 / downfall 1.0) and
     * for the constant-tint blocks (birch / spruce leaves, lily_pad) returns their fixed colour.
     * That is the value asset-renderer must match, so the reference uses it rather than a biome
     * sample. {@link BlockColors#getTintSources} returns one source per tint index in index order
     * (see {@code ModelBlockRenderer}); blocks with no source get {@link #NO_TINTS}.
     */
    private static int[] resolveInventoryTints(Minecraft client, BlockState state) {
        BlockColors blockColors = client.getBlockColors();
        List<BlockTintSource> sources = blockColors.getTintSources(state);
        if (sources.isEmpty()) return NO_TINTS;
        int[] tints = new int[sources.size()];
        for (int i = 0; i < sources.size(); i++)
            tints[i] = sources.get(i).color(state);
        return tints;
    }

    private void ensureTextures(int width, int height) {
        if (colorTexture != null && textureWidth == width && textureHeight == height) return;
        closeTextures();

        GpuDevice device = RenderSystem.getDevice();
        // Same usage flag mask ItemFrameRenderer uses (13 = COPY_SRC | COPY_DST |
        // RENDER_ATTACHMENT | TEXTURE_BINDING for colour; 9 for depth).
        colorTexture = device.createTexture(() -> "refharness block color", 13,
            TextureFormat.RGBA8, width, height, 1, 1);
        colorTextureView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture(() -> "refharness block depth", 9,
            TextureFormat.DEPTH32, width, height, 1, 1);
        depthTextureView = device.createTextureView(depthTexture);
        textureWidth = width;
        textureHeight = height;
    }

    private void writeTextureToPng(Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        final int width = textureWidth;
        final int height = textureHeight;
        final int pixelSize = colorTexture.getFormat().pixelSize();
        long byteSize = (long) width * height * pixelSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            () -> "refharness-block-readback", /*usage*/ 9, byteSize);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        IOException[] err = new IOException[1];

        encoder.copyTextureToBuffer(colorTexture, buffer, 0L, () -> {
            try (GpuBuffer.MappedView read = encoder.mapBuffer(buffer, true, false);
                 NativeImage image = new NativeImage(width, height, false)) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = read.data().getInt((x + y * width) * pixelSize);
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
