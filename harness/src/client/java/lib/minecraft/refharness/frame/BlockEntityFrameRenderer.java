package lib.minecraft.refharness.frame;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.FrameRenderer;
import lib.minecraft.refharness.pip.PipScope;
import lib.minecraft.refharness.pip.PipTarget;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a block-entity-bearing {@link BlockState} via vanilla's
 * {@link BlockEntityRenderDispatcher} - the same renderer that draws signs / bells / campfires /
 * beacons / shelves in-world. A block whose item the client draws through special renderers alone -
 * a chest, a bed, a banner, a head - never reaches it: the block sweep draws that block as the
 * client's slot does, through {@link ItemFrameRenderer}, each layer posed by its special's
 * {@code transformation} and its base's {@code display.gui}, which an in-world draw does not
 * reproduce.
 *
 * <p><b>The static block half takes the icon split.</b> A block entity does not stop a block from
 * having an inventory icon vanilla bakes from a block model - 22 of the 55 blocks reaching
 * {@link #submitRawBlockEntity} have one, the shelves and {@code structure_block} among them - so
 * that half runs {@link BlockIconGeometry#swapIn} exactly as {@link BlockFrameRenderer} does. The
 * block-entity submit is unaffected and still runs on top: an icon-backed block whose entity adds
 * geometry composes both, the same way the blockstate model and the entity mesh composed before.
 *
 * <p>Setup: a transient {@link BlockEntity} is constructed via
 * {@code EntityBlock.newBlockEntity(pos, state)}, given a {@link Level}
 * reference via {@link BlockEntity#setLevel} so the dispatcher's {@code hasLevel()} check
 * passes, then handed to the dispatcher's renderer for state extraction. Submission goes
 * through the same iso PIP pose chain {@link BlockFrameRenderer} uses, so the output PNG drops
 * into the same coordinate system as the existing block references.
 *
 * <p>The transient BE never enters the world's tick / save loop - it's a stack-local
 * placeholder for the renderer's state-extraction step.
 *
 * <p>The raw in-world render is drawn at the block-centred iso pose. A sign alone is turned, a half
 * turn about the block's vertical so its face meets the camera.
 *
 * <p><b>It does not implement {@link FrameRenderer}.</b> The stack the static block half's icon is
 * read from is part of what is drawn rather than a setting on the draw, so a render takes the state
 * and the stack together, as {@link BlockFrameRenderer}'s does.
 */
@Parity(claim = "harness-block-sweep", mode = Mode.DEMOTE)
public final class BlockEntityFrameRenderer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Half-extent of the orthographic depth range. Block-entity geometry is unit-scale, so the
     * standard range comfortably contains the posed model.
     *
     * <p>asset-renderer's {@code DepthMath.VANILLA_DEPTH_RANGE} holds this same value, and so do
     * the block, block-entity, entity, item and player frame renderers in this build. Changing it
     * means editing all five in one commit.
     */
    private static final float DEPTH_RANGE = 1000.0f;

    /** Stable position for the transient BE - never written to the world. */
    private static final BlockPos TRANSIENT_POS = new BlockPos(0, 64, 0);

    private static final int[] NO_TINTS = new int[0];

    private final PipTarget pip = new PipTarget("BE", DEPTH_RANGE);
    private final CameraRenderState cameraState = new CameraRenderState();
    private final RandomSource random = RandomSource.create();
    private final List<BlockStateModelPart> partsScratch = new ArrayList<>();

    /**
     * The block's authored {@code display.gui} transform for the subject being rendered, resolved
     * once per {@link #render} call and read by {@link #blockCenteredPose}. Falls back to
     * {@link BlockGuiTransform#DEFAULT_BLOCK_GUI} when unreadable.
     */
    private ItemTransform guiTransform = BlockGuiTransform.DEFAULT_BLOCK_GUI;

    /**
     * Renders the block-entity geometry of {@code state} as an iso-pose icon and writes the
     * result PNG to {@code out}.
     *
     * @param client the active client; supplies the block-entity dispatcher and the model manager
     * @param state the block state to render
     * @param stack the stack the static block half's icon is read from - the block's own item,
     *              carrying whatever components the subject gives it
     * @param canvas the canvas to draw onto
     * @param out where to write the PNG; parent directories are created on demand
     * @return whether a PNG was written; declined when the block has no {@link EntityBlock}-style
     *         entity, has no registered renderer, or its submit ladder throws part-way - the caller
     *         should fall back to another path in that case
     * @throws IOException if the PNG file write fails
     */
    public boolean render(Minecraft client, BlockState state, ItemStack stack, Canvas canvas, Path out) throws IOException {
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

        // The block's own authored display.gui pose, honored by both pose builders below (falls back
        // to the standard [30,225,0]+0.625 iso pose when the item model exposes no readable transform).
        guiTransform = BlockGuiTransform.resolve(client, state);

        return pip.draw(client, canvas, out, scope -> {
            scope.lighting().setupFor(Lighting.Entry.ITEMS_3D);
            dispatcher.prepare(net.minecraft.world.phys.Vec3.ZERO);

            SubmitNodeStorage storage = scope.storage();
            try {
                // A sign turns its face toward the camera; every other block entity renders raw.
                if ((Object) renderer instanceof AbstractSignRenderer)
                    submitSignIcon(scope, renderer, (SignRenderState) renderState, storage);
                else
                    submitRawBlockEntity(scope, client, state, stack, renderer, renderState, storage);
            } catch (RuntimeException ex) {
                LOG.warn("BlockEntityFrameRenderer: submit failed for {}: {}", state, ex.toString());
                return false;
            }
            return true;
        });
    }

    /**
     * Raw in-world BE render path - the standard block-centred iso pose. Submits the static block
     * model first (beacon cube, suspicious_sand overlay base) then the BE renderer on top. Used
     * for every block-entity but a sign.
     */
    private void submitRawBlockEntity(PipScope scope, Minecraft client, BlockState state, ItemStack stack,
                                      BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer,
                                      BlockEntityRenderState renderState, SubmitNodeStorage storage) {
        PoseStack poseStack = blockCenteredPose(scope);

        // 1. Submit the static block model first - same call BlockFrameRenderer makes for plain
        //    blocks, and it takes the same split: where vanilla bakes the block's inventory icon
        //    from a block model, that bake replaces the blockstate parts, so a shelf draws
        //    block/<wood>_shelf_inventory rather than the multipart's assembled shelf and
        //    structure_block draws block/structure_block rather than its default mode's model.
        //    For blocks where the BE adds geometry on top of an existing block model (beacon =
        //    obsidian/glass cube + beacon stand; suspicious_sand = full cube + brushed item
        //    overlay), this draws the cube part. For blocks where the block model is empty
        //    (signs, chests, banners), it's a no-op and the BE renderer supplies all geometry.
        BlockStateModelSet modelSet = client.getModelManager().getBlockStateModelSet();
        BlockStateModel blockStateModel = modelSet.get(state);
        if (blockStateModel != null) {
            partsScratch.clear();
            blockStateModel.collectParts(random, partsScratch);
            BlockIconGeometry.swapIn(client, stack, partsScratch);
            if (!partsScratch.isEmpty()) {
                RenderType renderType = Sheets.cutoutBlockSheet();
                storage.submitBlockModel(poseStack, renderType, partsScratch, NO_TINTS,
                    PipScope.FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            }
        }

        // 2. Submit the BlockEntity renderer on top - the actual sign post / chest box / head model.
        renderer.submit(renderState, poseStack, storage, cameraState);
    }

    /**
     * Composes a sign inventory icon for every variant - standing (board + post), wall (board
     * only), and the three hanging forms (ceiling V-chains, ceiling-middle straight chains, wall
     * bar + splayed chains). {@link AbstractSignRenderer} already renders each block at the
     * attachment its default state selects: {@code extractRenderState} populates
     * {@code transformations.body()} with the per-attachment mount transform (the wall mount adds
     * its own translate) and {@code getSignModel(state)} picks the matching mesh by
     * {@code attachmentType}. The only icon adjustment is a 180-degree yaw about the block-centre
     * vertical axis pre-composed into the body transform, turning the sign face toward the camera
     * to match asset-renderer's iso icon (confirmed: standing 1.52 -&gt; 0.06). The same yaw applies
     * to all sign types - each block's default state already carries a {@code rotation=8} /
     * {@code facing=north} yaw of 180 degrees, so the pre-composed flip lands the face at the
     * canonical reference orientation asset-renderer bakes its inventory transform at.
     */
    private void submitSignIcon(PipScope scope, BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer,
                                SignRenderState state, SubmitNodeStorage storage) {
        SignRenderState.SignTransformations t = state.transformations;
        Matrix4f body = new Matrix4f()
            .translate(0.5f, 0f, 0.5f)
            .rotateY((float) Math.PI)
            .translate(-0.5f, 0f, -0.5f)
            .mul(t.body().getMatrix());
        state.transformations = new SignRenderState.SignTransformations(new Transformation(body), t.frontText(), t.backText());
        renderer.submit(state, blockCenteredPose(scope), storage, cameraState);
    }

    /**
     * The standard block-centred iso PIP pose: centre on canvas, 1 model unit -&gt; canvas pixels
     * (Y-down -&gt; Y-up), then the block's authored {@code display.gui} transform via
     * {@link ItemTransform#apply} (translation, {@code rotationXYZ}, scale, and the {@code [0,1]}
     * block-model centring). Used by the raw BE path and by heads that fit inside the block.
     */
    private PoseStack blockCenteredPose(PipScope scope) {
        PoseStack ps = scope.guiPose();
        guiTransform.apply(false, ps.last());
        return ps;
    }

    @Override
    public void close() {
        pip.close();
    }
}
