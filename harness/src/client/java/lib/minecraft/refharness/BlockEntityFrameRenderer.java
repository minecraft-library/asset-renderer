package lib.minecraft.refharness;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

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
import com.mojang.math.Axis;
import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.model.object.banner.BannerModel;
import net.minecraft.client.model.object.skull.SkullModelBase;
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
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.blockentity.state.BannerRenderState;
import net.minecraft.client.renderer.blockentity.state.BedRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.SkullBlockRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
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
 *
 * <h2>Inventory-icon composition (bed / banner / wall_banner)</h2>
 * For most block-entities the raw in-world BEWR render <em>is</em> the icon: skull, shulker_box,
 * chest, conduit, decorated_pot and beacon all sit on or inside the unit block, so the harness's
 * block-centred iso pose reproduces asset-renderer's icon directly. Three families diverge,
 * because asset-renderer composes them into an inventory icon rather than rendering the raw
 * in-world block:
 * <ul>
 *   <li><b>Facing</b> - {@code BedRenderer.createModelTransform} and {@code BannerRenderer}'s
 *       per-rotation/per-facing transform turn the geometry to its in-world direction. The icon
 *       faces a fixed canonical direction instead (asset-renderer's {@code iconRotation}).</li>
 *   <li><b>Centering / fit</b> - the raw geometry is centred on the block, but the bed spans two
 *       blocks and the wall-banner flag hangs below the block, so both fall off a block-centred
 *       canvas. asset-renderer recenters on the geometry bbox and shrinks anything taller than
 *       {@value #ICON_FIT_EXTENT} units to fit ({@code recenterAndFit}).</li>
 *   <li><b>Multi-block</b> - the bed default state is one half (the foot); asset-renderer merges
 *       both halves. The harness renders both pieces with the in-world block offset between
 *       them.</li>
 * </ul>
 * Mirrors {@code BlockRenderer.Isometric3D}'s composition: it reuses vanilla's own geometry-extent
 * walkers ({@link BedRenderer#getExtents}, {@link BannerRenderer#getExtents}) to size the fit, then
 * submits through the unchanged vanilla BE renderer so sprites / dye / patterns stay vanilla-correct.
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

    /**
     * Maximum bbox extent (block units) the icon-composition fit pass allows; geometry taller /
     * longer than this is shrunk uniformly so it fits the canvas. Matches asset-renderer's
     * {@code BlockRenderer.recenterAndFit} threshold.
     */
    private static final float ICON_FIT_EXTENT = 1.4f;

    /**
     * Canonical bed facing - the in-world {@code BedRenderer.createModelTransform} is built for
     * this direction so the icon faces a fixed way regardless of the block's default-state facing.
     * {@code NORTH} yields {@code toYRot()=180} so the transform's {@code Z(180+toYRot())} term is
     * a no-op rotation, leaving only the lay-flat {@code X+90}.
     */
    private static final Direction BED_CANONICAL_FACING = Direction.NORTH;

    /** Y-rotation (degrees) applied to the merged bed before fit - asset-renderer's {@code iconRotation}. */
    private static final float BED_ICON_ROTATION_DEG = 90f;

    /**
     * Skull animation position that closes the ender-dragon jaw to match asset-renderer's static
     * (bind-pose) geometry. {@code DragonHeadModel.setupAnim} drives {@code jaw.xRot =
     * (sin(animationPos * PI * 0.2) + 1) * 0.2}; this is the {@code sin = -1} root so the jaw rests
     * flush with the head. At the default {@code 0} the jaw gapes open by {@code 0.2} rad.
     */
    private static final float DRAGON_JAW_CLOSED_ANIM = -2.5f;

    /**
     * Canonical standing-banner rotation (degrees) - turns the flag to the icon direction. At 0
     * the pole faces the camera (in front of the cloth); the icon wants the pole behind the
     * cloth, a 180-degree flip about the vertical.
     */
    private static final float BANNER_CANONICAL_DEG = 180f;

    /** Canonical wall-banner rotation (degrees) - replaces the in-world {@code facing.toYRot()}. */
    private static final float WALL_BANNER_CANONICAL_DEG = 180f;

    /** Banner model-frame translation - {@code BannerRenderer.MODEL_TRANSLATION}. */
    private static final Vector3fc BANNER_MODEL_TRANSLATION = new Vector3f(0.5f, 0f, 0.5f);

    /** Banner model-frame scale (Y/Z negated = stand-upright flip) - {@code BannerRenderer.MODEL_SCALE}. */
    private static final Vector3fc BANNER_MODEL_SCALE = new Vector3f(0.6666667f, -0.6666667f, -0.6666667f);

    /** Wall-banner sub-models (no pole, hanging flag) - private on {@link BannerRenderer}, walked for bbox. */
    private static final Field BANNER_WALL_MODEL;
    private static final Field BANNER_WALL_FLAG_MODEL;

    /** Per-type skull model factory - private on {@link SkullBlockRenderer}, used to walk the dragon-head bbox. */
    private static final Field SKULL_MODEL_BY_TYPE;

    static {
        try {
            BANNER_WALL_MODEL = BannerRenderer.class.getDeclaredField("wallModel");
            BANNER_WALL_MODEL.setAccessible(true);
            BANNER_WALL_FLAG_MODEL = BannerRenderer.class.getDeclaredField("wallFlagModel");
            BANNER_WALL_FLAG_MODEL.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("BannerRenderer wall-model field layout changed in this Minecraft version", e);
        }
        try {
            SKULL_MODEL_BY_TYPE = SkullBlockRenderer.class.getDeclaredField("modelByType");
            SKULL_MODEL_BY_TYPE.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("SkullBlockRenderer model-factory field layout changed in this Minecraft version", e);
        }
    }

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

            lighting.setupFor(Lighting.Entry.ITEMS_3D);
            dispatcher.prepare(net.minecraft.world.phys.Vec3.ZERO);

            try {
                // Bed / banner / wall_banner: compose an inventory icon (canonical facing, geometry-
                // bbox fit, both bed halves) instead of rendering the raw in-world block. Every other
                // block-entity renders raw - for symmetric BEs (skull / chest / shulker_box / conduit /
                // decorated_pot / beacon) the in-world render already equals the icon.
                if ((Object) renderer instanceof BedRenderer bed) {
                    submitBedIcon(bed, (BedRenderState) renderState, storage);
                } else if ((Object) renderer instanceof BannerRenderer banner) {
                    submitBannerIcon(banner, (BannerRenderState) renderState, storage);
                } else if ((Object) renderer instanceof SkullBlockRenderer skull) {
                    submitSkullIcon(skull, (SkullBlockRenderState) renderState, state, storage);
                } else {
                    submitRawBlockEntity(client, state, renderer, renderState, storage);
                }
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

    /**
     * Raw in-world BE render path - the standard block-centred iso pose. Submits the static block
     * model first (beacon cube, suspicious_sand overlay base) then the BE renderer on top. Used
     * for every block-entity except the three icon-composition families.
     */
    private void submitRawBlockEntity(Minecraft client, BlockState state,
                                      BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer,
                                      BlockEntityRenderState renderState, SubmitNodeStorage storage) {
        PoseStack poseStack = blockCenteredPose();

        // 1. Submit the static block model first - same call BlockFrameRenderer makes for plain
        //    blocks. For blocks where the BE adds geometry on top of an existing block model
        //    (beacon = obsidian/glass cube + beacon stand; suspicious_sand = full cube + brushed
        //    item overlay), this draws the cube part. For blocks where the block model is empty
        //    (signs, chests, banners), it's a no-op and the BE renderer supplies all geometry.
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

        // 2. Submit the BlockEntity renderer on top - the actual sign post / chest box / head model.
        renderer.submit(renderState, poseStack, storage, cameraState);
    }

    /**
     * Composes a bed inventory icon: both halves merged at a canonical facing, rotated by
     * {@link #BED_ICON_ROTATION_DEG}, recentred on the merged bbox and shrunk to fit. The two
     * pieces are submitted through vanilla's {@link BedRenderer} (so the dye sprite stays correct)
     * with the in-world head-block offset between them.
     */
    private void submitBedIcon(BedRenderer bed, BedRenderState state, SubmitNodeStorage storage) {
        Matrix4f modelTransform = new Matrix4f(BedRenderer.modelTransform(BED_CANONICAL_FACING).getMatrix());
        float iconRad = (float) Math.toRadians(BED_ICON_ROTATION_DEG);
        Vec3i headOffset = BED_CANONICAL_FACING.getUnitVec3i();

        // Bounds in the post-(iconRot . createModelTransform) frame, matching the submit pose below.
        Matrix4f footFrame = new Matrix4f().rotateY(iconRad).mul(modelTransform);
        Matrix4f headFrame = new Matrix4f().rotateY(iconRad)
            .translate(headOffset.getX(), headOffset.getY(), headOffset.getZ())
            .mul(modelTransform);
        Bounds bounds = new Bounds();
        expandExtents(bounds, footFrame, c -> bed.getExtents(BedPart.FOOT, c));
        expandExtents(bounds, headFrame, c -> bed.getExtents(BedPart.HEAD, c));

        PoseStack ps = isoFitPose(bounds);
        ps.mulPose(Axis.YP.rotationDegrees(BED_ICON_ROTATION_DEG));

        state.facing = BED_CANONICAL_FACING;
        state.part = BedPart.FOOT;
        bed.submit(state, ps, storage, cameraState);

        ps.pushPose();
        ps.translate(headOffset.getX(), headOffset.getY(), headOffset.getZ());
        state.part = BedPart.HEAD;
        bed.submit(state, ps, storage, cameraState);
        ps.popPose();
    }

    /**
     * Composes a standing- or wall-banner inventory icon: the in-world facing transform is
     * replaced with a canonical one (no per-rotation / per-facing yaw), then the post + flag (or
     * the wall bar + hanging flag) are recentred on their bbox and shrunk to fit. Submitted
     * through vanilla's {@link BannerRenderer} so the base dye + patterns stay correct.
     */
    private void submitBannerIcon(BannerRenderer banner, BannerRenderState state, SubmitNodeStorage storage) {
        boolean wall = state.attachmentType == BannerBlock.AttachmentType.WALL;
        Transformation canonical = bannerModelTransformation(wall ? WALL_BANNER_CANONICAL_DEG : BANNER_CANONICAL_DEG);
        Matrix4f frame = new Matrix4f(canonical.getMatrix());

        Bounds bounds = new Bounds();
        if (wall) {
            expandExtents(bounds, frame, c -> walkWallBannerExtents(banner, c));
        } else {
            expandExtents(bounds, frame, banner::getExtents);
        }

        PoseStack ps = isoFitPose(bounds);
        state.transformation = canonical;
        // Pin the wave phase to 0 for reproducibility (extractRenderState seeds it from world
        // position + game time). The cloth is flattened to match asset-renderer's flat flag by
        // BannerFlagModelMixin (which cancels the wave entirely); this just removes the game-time
        // dependency for any path the mixin doesn't cover and documents the intent.
        state.phase = 0f;
        banner.submit(state, ps, storage, cameraState);
    }

    /**
     * Composes a skull / mob-head inventory icon. Floor heads already match asset-renderer (they sit
     * centred on the block), so the work is twofold:
     * <ul>
     *   <li><b>Wall heads</b> ({@link WallSkullBlock}) - the in-world transform mounts the head on a
     *       wall (a {@code 0.25} offset toward the wall + a facing rotation). asset-renderer renders
     *       the wall block with the same centred icon as the floor block (one {@code skull_head}
     *       entry covers both), so the wall head is re-pointed at the canonical ground transform at
     *       rotation 0 - exactly what the floor head already uses.</li>
     *   <li><b>Dragon head</b> - the ender-dragon head model is far larger than a block and overflows
     *       a block-centred canvas. It keeps the (correct) ground orientation but is recentred on its
     *       bbox and shrunk to fit, mirroring asset-renderer's {@code recenterAndFit}. Normal heads
     *       fit within the block so they take the plain block-centred pose untouched.</li>
     * </ul>
     */
    private void submitSkullIcon(SkullBlockRenderer skull, SkullBlockRenderState state, BlockState blockState, SubmitNodeStorage storage) {
        if (blockState.getBlock() instanceof WallSkullBlock) {
            state.transformation = SkullBlockRenderer.TRANSFORMATIONS.freeTransformations(0);
        }

        // Only the ender-dragon head is larger than a block; every other head fits and takes the
        // plain block-centred pose (matching the floor heads that already hit ~0). The dragon keeps
        // its (correct) ground orientation but is recentred on its bbox and shrunk to fit.
        PoseStack ps;
        if (state.skullType == SkullBlock.Types.DRAGON) {
            // Close the jaw to the bind pose asset-renderer baked (the default open jaw would diverge
            // and inflate the silhouette). Both the bbox walk and the submit read animationProgress,
            // so they stay in the same pose.
            state.animationProgress = DRAGON_JAW_CLOSED_ANIM;
            Matrix4f frame = new Matrix4f(state.transformation.getMatrix());
            Bounds bounds = new Bounds();
            expandExtents(bounds, frame, c -> walkSkullExtents(skull, state, c));
            ps = isoFitPose(bounds);
        } else {
            ps = blockCenteredPose();
        }
        skull.submit(state, ps, storage, cameraState);
    }

    /**
     * Walks the skull model for {@code state.skullType} in its raw mesh frame, feeding each vertex to
     * {@code out}. The per-type {@link SkullModelBase} factory is private on
     * {@link SkullBlockRenderer}, so it is reached reflectively; {@code setupAnim} is run at the same
     * {@code state.animationProgress} the submit uses (the closed-jaw pose for the dragon) so the
     * measured bbox matches the rendered geometry.
     */
    private void walkSkullExtents(SkullBlockRenderer skull, SkullBlockRenderState state, Consumer<Vector3fc> out) {
        try {
            @SuppressWarnings("unchecked")
            java.util.function.Function<Object, SkullModelBase> factory =
                (java.util.function.Function<Object, SkullModelBase>) SKULL_MODEL_BY_TYPE.get(skull);
            SkullModelBase model = factory.apply(state.skullType);
            SkullModelBase.State animState = new SkullModelBase.State();
            animState.animationPos = state.animationProgress;
            model.setupAnim(animState);
            model.root().getExtentsForGui(new PoseStack(), out);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to read SkullBlockRenderer model factory", e);
        }
    }

    /**
     * Walks the wall-banner sub-models (bar + hanging flag, no pole) the same way
     * {@link BannerRenderer#getExtents} walks the standing models, feeding each raw vertex to
     * {@code out}. Vanilla's public {@code getExtents} only exposes the standing models, so the
     * wall variants are reached reflectively.
     */
    private void walkWallBannerExtents(BannerRenderer banner, Consumer<Vector3fc> out) {
        try {
            BannerModel wallModel = (BannerModel) BANNER_WALL_MODEL.get(banner);
            BannerFlagModel wallFlagModel = (BannerFlagModel) BANNER_WALL_FLAG_MODEL.get(banner);
            PoseStack identity = new PoseStack();
            wallModel.root().getExtentsForGui(identity, out);
            wallFlagModel.setupAnim(0f);
            wallFlagModel.root().getExtentsForGui(identity, out);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to read BannerRenderer wall models", e);
        }
    }

    /**
     * Builds the canonical banner facing transform ({@code BannerRenderer.modelTransformation}):
     * {@code T(0.5,0,0.5) . R_Y(-deg) . S(0.667,-0.667,-0.667)}. Replaces the in-world
     * rotation/facing yaw with a fixed icon yaw.
     */
    private static Transformation bannerModelTransformation(float deg) {
        Matrix4f matrix = new Matrix4f()
            .translation(BANNER_MODEL_TRANSLATION.x(), BANNER_MODEL_TRANSLATION.y(), BANNER_MODEL_TRANSLATION.z())
            .rotateY((float) Math.toRadians(-deg))
            .scale(BANNER_MODEL_SCALE.x(), BANNER_MODEL_SCALE.y(), BANNER_MODEL_SCALE.z());
        return new Transformation(matrix);
    }

    /**
     * Feeds every raw geometry vertex from {@code source} through {@code frame} and expands
     * {@code bounds} with the result. {@code source} is a vanilla extent walker
     * ({@code getExtents} / {@code getExtentsForGui}) that emits raw mesh-frame vertices;
     * {@code frame} carries them into the same coordinate frame the submit pose composes on top of.
     */
    private void expandExtents(Bounds bounds, Matrix4f frame, Consumer<Consumer<Vector3fc>> source) {
        source.accept(v -> {
            Vector3f t = frame.transformPosition(v.x(), v.y(), v.z(), new Vector3f());
            bounds.expand(t.x(), t.y(), t.z());
        });
    }

    /**
     * The standard block-centred iso PIP pose: centre on canvas, 1 model unit -&gt; canvas pixels
     * (Y-down -&gt; Y-up), the block {@code display.gui} rotation + scale, then centre the
     * {@code [0,1]} block model on the origin. Used by the raw BE path and by heads that fit inside
     * the block.
     */
    private PoseStack blockCenteredPose() {
        PoseStack ps = new PoseStack();
        ps.translate(textureWidth / 2.0f, textureHeight / 2.0f, 0.0f);
        ps.scale(textureWidth, -textureHeight, textureWidth);
        ps.mulPose(BLOCK_GUI_ROTATION);
        ps.scale(BLOCK_GUI_SCALE, BLOCK_GUI_SCALE, BLOCK_GUI_SCALE);
        ps.translate(-0.5f, -0.5f, -0.5f);
        return ps;
    }

    /**
     * Builds the iso PIP pose with a bbox-fit baked in: the standard block iso chain (no fixed
     * block centring), then a uniform shrink so the bbox's largest extent is at most
     * {@link #ICON_FIT_EXTENT}, then a recenter that maps the bbox midpoint to the origin (canvas
     * centre). Mirrors asset-renderer's {@code recenterAndFit}. Callers append any per-piece
     * model transform (bed icon rotation, banner facing) after this returns.
     */
    private PoseStack isoFitPose(Bounds bounds) {
        float extent = bounds.maxExtent();
        float scale = extent > ICON_FIT_EXTENT ? ICON_FIT_EXTENT / extent : 1.0f;

        PoseStack ps = new PoseStack();
        ps.translate(textureWidth / 2.0f, textureHeight / 2.0f, 0.0f);
        ps.scale(textureWidth, -textureHeight, textureWidth);
        ps.mulPose(BLOCK_GUI_ROTATION);
        ps.scale(BLOCK_GUI_SCALE, BLOCK_GUI_SCALE, BLOCK_GUI_SCALE);
        ps.scale(scale, scale, scale);
        ps.translate(-bounds.centerX(), -bounds.centerY(), -bounds.centerZ());
        return ps;
    }

    /** Mutable axis-aligned bounding box accumulated over transformed geometry vertices. */
    private static final class Bounds {
        private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        void expand(float x, float y, float z) {
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }

        float centerX() { return (minX + maxX) * 0.5f; }
        float centerY() { return (minY + maxY) * 0.5f; }
        float centerZ() { return (minZ + maxZ) * 0.5f; }

        float maxExtent() {
            return Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ);
        }
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
