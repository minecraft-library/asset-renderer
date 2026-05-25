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
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders a block-entity-bearing {@link BlockState} via vanilla's
 * {@link BlockEntityRenderDispatcher} - the same renderer that draws signs / beds / banners /
 * bells / campfires / heads / shulker_boxes / decorated_pots / shelves / copper_golem_statues
 * in-world. Inventory icons for these blocks use {@code item/generated} 2D billboard sprites
 * (because their item models do), so the existing {@link ItemFrameRenderer} dispatch produces a
 * flat sprite that has nothing to do with the actual block geometry. Routing them through this
 * BE-renderer path captures the real 3D geometry vanilla draws in-world, which is what
 * asset-renderer's {@code BlockRenderer} composes via {@code Block.Entity.parts()}.
 *
 * <p>Setup: a transient {@link BlockEntity} is constructed via
 * {@code EntityBlock.newBlockEntity(pos, state)}, given a {@link net.minecraft.world.level.Level}
 * reference via {@link BlockEntity#setLevel} so the dispatcher's {@code hasLevel()} check
 * passes, then handed to the dispatcher's renderer for state extraction. Submission goes
 * through the same iso PIP pose chain {@link BlockFrameRenderer} uses, so the output PNG drops
 * into the same coordinate system as the existing block references.
 *
 * <p>The transient BE never enters the world's tick / save loop - it's a stack-local
 * placeholder for the renderer's state-extraction step.
 */
public final class BlockEntityFrameRenderer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private static final int FULL_BRIGHT_LIGHT = 15728880;

    /** Stable position for the transient BE - never written to the world. */
    private static final BlockPos TRANSIENT_POS = new BlockPos(0, 64, 0);

    private static final Quaternionf BLOCK_GUI_ROTATION = new Quaternionf().rotationXYZ(
        (float) Math.toRadians(30),
        (float) Math.toRadians(225),
        0f
    );

    private static final float BLOCK_GUI_SCALE = 0.625f;

    private static final int[] NO_TINTS = new int[0];

    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionMatrixBuffer = new ProjectionMatrixBuffer("refharness BE PIP");
    private final CameraRenderState cameraState = new CameraRenderState();
    private final RandomSource random = RandomSource.create();
    private final List<BlockStateModelPart> partsScratch = new ArrayList<>();

    private GpuTexture colorTexture;
    private GpuTextureView colorTextureView;
    private GpuTexture depthTexture;
    private GpuTextureView depthTextureView;
    private int textureWidth;
    private int textureHeight;

    /**
     * Renders the block-entity geometry of {@code state} as an iso-pose icon and writes the
     * result PNG to {@code outputPath}. Returns {@code false} when the block has no
     * {@link EntityBlock}-style entity, no registered renderer, or the renderer returns a
     * null render state - the caller should fall back to another path in that case.
     */
    public boolean renderAndWrite(Minecraft client, BlockState state, int size, Path outputPath) throws IOException {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) return false;

        BlockEntity blockEntity = entityBlock.newBlockEntity(TRANSIENT_POS, state);
        if (blockEntity == null) return false;
        if (client.level == null) return false;
        blockEntity.setLevel(client.level);

        BlockEntityRenderDispatcher dispatcher = client.getBlockEntityRenderDispatcher();
        BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = dispatcher.getRenderer(blockEntity);
        if (renderer == null) return false;

        BlockEntityRenderState renderState = renderer.createRenderState();
        renderer.extractRenderState(blockEntity, renderState, 0f, /*cameraPos*/ net.minecraft.world.phys.Vec3.ZERO, /*breakProgress*/ null);

        ensureTextures(size, size);

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

            lighting.setupFor(Lighting.Entry.ITEMS_3D);

            // 1. Submit the static block model first - same call BlockFrameRenderer makes for
            //    plain blocks. For blocks where the BE adds geometry on top of an existing
            //    block model (beacon = obsidian/glass cube + beacon stand; suspicious_sand =
            //    full cube + brushed item overlay), this draws the cube part. For blocks where
            //    the block model is empty (signs, chests, banners), submitBlockModel is a no-op
            //    and the BE renderer in step 2 supplies all geometry.
            BlockStateModelSet modelSet = client.getModelManager().getBlockStateModelSet();
            BlockStateModel blockStateModel = modelSet.get(state);
            if (blockStateModel != null) {
                partsScratch.clear();
                blockStateModel.collectParts(random, partsScratch);
                if (!partsScratch.isEmpty()) {
                    RenderType renderType = Sheets.cutoutBlockSheet();
                    storage.submitBlockModel(poseStack, renderType, partsScratch, NO_TINTS,
                        FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
                }
            }

            // 2. Submit the BlockEntity renderer on top - the actual sign post / bed blanket /
            //    chest box / head model / banner cloth / etc.
            dispatcher.prepare(net.minecraft.world.phys.Vec3.ZERO);
            try {
                renderer.submit(renderState, poseStack, storage, cameraState);
            } catch (RuntimeException ex) {
                LOG.warn("BlockEntityFrameRenderer: submit failed for {}: {}", state, ex.toString());
                return false;
            }
            fed.renderAllFeatures();
            bufferSource.endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }

        writeTextureToPng(outputPath);
        return true;
    }

    private void ensureTextures(int width, int height) {
        if (colorTexture != null && textureWidth == width && textureHeight == height) return;
        closeTextures();
        GpuDevice device = RenderSystem.getDevice();
        colorTexture = device.createTexture(() -> "refharness BE color", 13,
            TextureFormat.RGBA8, width, height, 1, 1);
        colorTextureView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture(() -> "refharness BE depth", 9,
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
            () -> "refharness-be-readback", /*usage*/ 9, byteSize);
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
