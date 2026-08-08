package lib.minecraft.refharness.frame;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.FrameRenderer;
import lib.minecraft.refharness.api.HarnessPose;
import lib.minecraft.refharness.pip.PipScope;
import lib.minecraft.refharness.pip.PipTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Renders the vanilla {@link PlayerModel} (default steve skin) to an offscreen PNG under the same
 * {@link Lighting.Entry#ENTITY_IN_UI ENTITY_IN_UI} lighting vanilla uses for its inventory
 * player-model preview - the ground truth the sibling asset-renderer's {@code PlayerRenderer} 3D
 * output is diffed against.
 *
 * <p>Unlike {@link EntityFrameRenderer} (which drives a real {@code Entity} through the
 * {@code EntityRenderDispatcher}), a player is not a spawnable entity, so this bakes the
 * {@link ModelLayers#PLAYER} layer and submits the model directly via
 * {@link SubmitNodeCollector#submitModel}. The pose chain replicates the entity harness's iso
 * presentation - {@code scale(1,1,-1)} chirality, {@link #ISO_ROTATION}, then the
 * {@code LivingEntityRenderer.submit} humanoid chain ({@code R_Y(180)}, {@code scale(-1,-1,1)},
 * {@code translate(0,-1.501,0)}) - so the Y-down vanilla model lands upright, front-facing, at the
 * shared iso pose, matching asset-renderer's presented player pose ({@code [30,45,0]}).
 *
 * <p>The silhouette is measured through {@link ModelPart#getExtentsForGui} and scaled to
 * {@link #FILL} of the canvas, mirroring asset-renderer's {@code PLAYER_FILL} auto-fit. Under
 * {@code refharness.headless} the {@code SkipSetupAnimMixin} suppresses the model's {@code setupAnim},
 * so the authored bind pose is rendered (consistent with every other harness subject).
 */
public final class PlayerFrameRenderer implements FrameRenderer<PlayerFrameRenderer.Scope> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Half-extent of the orthographic depth range - the span vanilla's own picture-in-picture
     * renderer projects through, and the one every other frame renderer here uses.
     *
     * <p>It was once ten times wider, on the reasoning that the iso rotation and the fit scale push a
     * humanoid's corners past a block-scale range. The fit is what makes that unnecessary: the
     * silhouette is measured and scaled to {@link #FILL} of a capped canvas, so the depth extent is
     * bounded along with it.
     *
     * <p>The width is not free. Every window depth lands beside {@code 0.5}, where a {@code float}
     * step is {@code 2^-24}, so the range sets in direct proportion how close two surfaces may be
     * before the reference records them at the same depth and leaves their order to whichever drew
     * last.
     *
     * <p>asset-renderer's {@code ModelEngine.VANILLA_DEPTH_RANGE} holds this same value, and so
     * does every other {@link FrameRenderer} in this build. Changing it means editing all of them in
     * one commit.
     */
    private static final float DEPTH_RANGE = 1000.0f;

    /** Fraction of the canvas the fitted silhouette spans - matches asset-renderer {@code PLAYER_FILL}. */
    private static final float FILL = 1.0f;

    /** The two body scopes with a vanilla ground truth: full body and head-only. */
    public enum Scope { FULL, SKULL }

    private final PipTarget pip = new PipTarget("player", DEPTH_RANGE);

    private PlayerModel playerModel;
    private RenderType renderType;
    private AvatarRenderState renderState;

    /**
     * Renders the player at the given {@code subject} scope onto the canvas and writes it to
     * {@code out} as a PNG.
     *
     * @param client the active client, for the model bake and the per-frame render handles
     * @param subject the body scope to draw
     * @param canvas the canvas to draw onto
     * @param out where to write the PNG; parent directories are created on demand
     * @return whether a PNG was written; the player scopes are never declined
     * @throws IOException if the PNG file write fails
     */
    @Override
    public boolean render(Minecraft client, Scope subject, Canvas canvas, Path out) throws IOException {
        ensureModel(client);

        // Measure the silhouette in the presentation frame (no fit yet), then scale to fill the canvas.
        PoseStack measure = new PoseStack();
        appendPresentationChain(measure);
        Extents extents = new Extents();
        walkExtents(subject, measure, v -> extents.expand(v.x(), v.y()));
        Bounds bounds = extents.toBounds();
        int size = canvas.width();
        float w = bounds.width();
        float h = bounds.height();
        float scale = (w <= 0f || h <= 0f) ? size : FILL * Math.min(size / w, size / h);
        float translateX = size / 2.0f - bounds.centerX() * scale;
        float translateY = size / 2.0f - bounds.centerY() * scale;

        return pip.draw(client, canvas, out, scope -> {
            scope.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

            PoseStack poseStack = new PoseStack();
            poseStack.translate(translateX, translateY, 0.0f);
            poseStack.scale(scale, scale, scale);
            appendPresentationChain(poseStack);

            submit(subject, poseStack, scope.storage());
            return true;
        });
    }

    /**
     * Appends the iso presentation transform: PIP chirality compensation, the iso rotation, then the
     * vanilla {@code LivingEntityRenderer.submit} humanoid chain that lifts a Y-down model upright and
     * turns its front to the camera. Mirrors {@link EntityFrameRenderer}'s render chain for a humanoid
     * at zero body-rotation and unit scale.
     */
    private static void appendPresentationChain(PoseStack ps) {
        ps.scale(1.0f, 1.0f, -1.0f);
        ps.mulPose(HarnessPose.ISO);
        ps.mulPose(Axis.YP.rotationDegrees(180.0f)); // setupRotations: face the camera
        ps.scale(-1.0f, -1.0f, 1.0f);                // humanoid chirality (Y-down -> Y-up)
        ps.translate(0.0f, -1.501f, 0.0f);           // model offset (absorbed by the bbox re-centre)
    }

    private void walkExtents(Scope scope, PoseStack ps, Consumer<Vector3fc> out) {
        if (scope == Scope.FULL) {
            playerModel.root().getExtentsForGui(ps, out);
        } else {
            playerModel.head.getExtentsForGui(ps, out);
            playerModel.hat.getExtentsForGui(ps, out);
        }
    }

    private void submit(Scope scope, PoseStack ps, SubmitNodeCollector storage) {
        if (scope == Scope.FULL) {
            storage.submitModel(playerModel, renderState, ps, renderType,
                PipScope.FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, /*crumbling*/ null);
        } else {
            storage.submitModelPart(playerModel.head, ps, renderType,
                PipScope.FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, /*sprite*/ null);
            storage.submitModelPart(playerModel.hat, ps, renderType,
                PipScope.FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, /*sprite*/ null);
        }
    }

    private void ensureModel(Minecraft client) {
        if (playerModel != null) return;
        playerModel = new PlayerModel(client.getEntityModels().bakeLayer(ModelLayers.PLAYER), /*slim*/ false);
        Identifier skin = DefaultPlayerSkin.getDefaultTexture();
        renderType = playerModel.renderType(skin);
        renderState = new AvatarRenderState();
        renderState.skin = DefaultPlayerSkin.getDefaultSkin();
        renderState.showHat = true;
        renderState.showJacket = true;
    }

    /** Mutable screen-space bounding box accumulated over transformed model-part corners. */
    private static final class Extents {
        private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        void expand(float x, float y) {
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }

        Bounds toBounds() {
            return new Bounds(minX, maxX, minY, maxY);
        }
    }

    @Override
    public void close() {
        pip.close();
    }
}
