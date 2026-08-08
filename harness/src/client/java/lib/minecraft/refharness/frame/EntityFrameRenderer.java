package lib.minecraft.refharness.frame;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.FrameRenderer;
import lib.minecraft.refharness.api.HarnessPose;
import lib.minecraft.refharness.pip.PipScope;
import lib.minecraft.refharness.pip.PipTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Renders an {@link Entity} via the vanilla GUI entity pipeline ({@link EntityRenderDispatcher}
 * + {@link Lighting.Entry#ENTITY_IN_UI ENTITY_IN_UI} lighting + {@link FeatureRenderDispatcher})
 * to an offscreen RGBA8 texture, then reads it back as a PNG. Modeled on
 * {@code GuiEntityRenderer.renderToTexture} - the same code path the inventory uses for
 * the player-model preview, the trade-screen villager preview, and the smithing UI's
 * armor stand.
 *
 * <p>Computes exact-fit scale + centering by walking the entity model's actual cube
 * hierarchy through the same transform chain used for rendering, collecting the visible
 * polygon vertex extents, then deriving scale = canvas / extent and centering offset.
 * Mirrors {@link ModelPart#render render}'s visibility filtering ({@code visible},
 * {@code skipDraw}) so polygons that don't render don't pad the bounds.
 *
 * <p>Known limitations of bounds-from-cube-vertices:
 * <ul>
 *   <li>Cubes with partially-transparent textures (warden tendrils are flat
 *       16×16×0 planes whose textures show only a small visible region) report cube
 *       bounds far larger than what's actually drawn - the extra space appears as
 *       padding in the output.</li>
 *   <li>Models whose part positions are computed in {@code setupAnim} from per-tick
 *       state (ender dragon tail segments via {@code subEntities[]} positions, partly
 *       wither's swaying ribcage) collapse to origin for our zeroed transient entity -
 *       bounds underestimate the rendered extent and the entity slightly clips.</li>
 * </ul>
 */
public final class EntityFrameRenderer implements FrameRenderer<Entity> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Half-extent of the orthographic depth range - the span vanilla's own picture-in-picture entity
     * renderer projects through, and the one every other frame renderer here already uses.
     *
     * <p>The range only has to fit the model's diagonal extent in the rotated, family-scaled frame,
     * and the canvas cap is what bounds that: a family whose silhouette would exceed
     * {@link lib.minecraft.refharness.HarnessConfig#MAX_CANVAS_SIZE} is shrunk to fit it, so the depth
     * extent is bounded along with it. It was once ten times wider, from before the canvas fit was
     * measured, to keep a 6x giant's front corners off the near plane; measured across the whole
     * corpus at this range, no subject loses a pixel of coverage - giant, ghast and ender_dragon
     * included.
     *
     * <p>The width is not free. Every window depth lands beside {@code 0.5}, where a {@code float} step
     * is {@code 2^-24}, so widening the range coarsens what the reference can tell apart in direct
     * proportion - and two surfaces closer than one step are recorded at the same depth, leaving their
     * order to whichever drew last.
     *
     * <p>asset-renderer's {@code ModelEngine.VANILLA_DEPTH_RANGE} holds this same value, and so
     * does every other {@link FrameRenderer} in this build. Changing it means editing all of them in
     * one commit.
     */
    private static final float DEPTH_RANGE = 1000.0f;

    private final PipTarget pip = new PipTarget("entity", DEPTH_RANGE);
    private final EntityBoundsWalker walker = new EntityBoundsWalker();




    /**
     * Renders the given entity onto the canvas and writes its pixels to {@code out} as a PNG.
     *
     * <p>A canvas carrying a fit renders at that fit's scale and anchor rather than the entity's
     * own, which is how every member of a family lands its shared geometry on the same pixels. A
     * canvas with no fit is scaled to the entity's own bounds, filling it.
     *
     * @param client the active client, for the entity render dispatcher and the per-frame handles
     * @param entity the entity to draw
     * @param canvas the canvas to draw onto
     * @param out where to write the PNG; parent directories are created on demand
     * @return whether a PNG was written; entities are never declined
     * @throws IOException if the PNG file write fails
     */
    @Override
    public boolean render(Minecraft client, Entity entity, Canvas canvas, Path out) throws IOException {
        return renderInternal(client, entity, HarnessPose.ISO, canvas, out);
    }

    /**
     * Renders the given entity at a caller-supplied rotation rather than the shared iso pose.
     *
     * @param client the active client, for the entity render dispatcher and the per-frame handles
     * @param entity the entity to draw
     * @param canvas the canvas to draw onto
     * @param out where to write the PNG; parent directories are created on demand
     * @param rotation the pose to present the entity at
     * @return whether a PNG was written
     * @throws IOException if the PNG file write fails
     */
    public boolean renderAtRotation(Minecraft client, Entity entity, Canvas canvas, Path out, Quaternionf rotation)
        throws IOException {
        return renderInternal(client, entity, rotation, canvas, out);
    }

    /**
     * Measures the entity's screen-space bounds at the standard iso pose, mirroring the
     * exact transform chain {@link #renderAndWrite} uses but skipping the GPU work. The
     * returned bounds are in entity-local screen coords (post-iso, pre-canvas-scale) and
     * are the same units the bounds walker collects internally - the sweeper unions these
     * across family members to size the family canvas.
     */
    public Bounds measureBounds(Minecraft client, Entity entity) {
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        EntityRenderer<? super Entity, ?> renderer = renderer(dispatcher, entity);
        EntityRenderState state = createRenderState(renderer, entity);
        Quaternionf effectiveRotation = (renderer instanceof LivingEntityRenderer<?, ?, ?>)
            ? HarnessPose.ISO
            : new Quaternionf(HarnessPose.ISO).rotateY((float) Math.PI);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.lightCoords = PipScope.FULL_BRIGHT_LIGHT;
        return walker.computeScreenBounds(renderer, state, effectiveRotation);
    }

    /**
     * Forces named bones on the mesh this entity will be measured and drawn with, until the returned
     * handle is closed.
     *
     * <p>The harness draws the authored bind pose, so the {@code setupAnim} call where vanilla turns a
     * chest or a horn on and off never runs and the render-state field it reads is inert. The flag has
     * to be written onto the part, and the bracket has to span the measurement as well as the draw -
     * the bounds walker reads the same flag, so a pin that covers only the draw frames the subject
     * against a mesh it is not.
     *
     * @param client the active client, for the entity render dispatcher
     * @param entity the entity whose mesh carries the bones
     * @param pins the bones to force, mapped to the visibility to force them to
     * @return a handle that puts every pinned bone back as it was
     */
    public AutoCloseable pinBones(Minecraft client, Entity entity, Map<String, Boolean> pins) {
        if (pins.isEmpty()) return () -> {};
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        EntityRenderer<? super Entity, ?> renderer = renderer(dispatcher, entity);
        Map<ModelPart, Boolean> previous = walker.pinBones(renderer, createRenderState(renderer, entity), pins);
        return () -> previous.forEach((part, visible) -> part.visible = visible);
    }

    private boolean renderInternal(
        Minecraft client,
        Entity entity,
        Quaternionf rotation,
        Canvas canvas,
        Path outputPath
    ) throws IOException {
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        EntityRenderer<? super Entity, ?> renderer = renderer(dispatcher, entity);
        EntityRenderState state = createRenderState(renderer, entity);
        Quaternionf effectiveRotation = (renderer instanceof LivingEntityRenderer<?, ?, ?>)
            ? rotation
            : new Quaternionf(rotation).rotateY((float) Math.PI);
        // Suppress shadows + outlines that GuiEntityRenderer also clears for inventory
        // previews - they're a world-rendering artifact that doesn't belong in a static
        // ground-truth render.
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.lightCoords = PipScope.FULL_BRIGHT_LIGHT;

        return pip.draw(client, canvas, outputPath, scope -> {
            float scale;
            float anchorX;
            float anchorY;
            if (canvas.fit().isPresent()) {
                // Family-locked: every family member uses the family's scale + anchor so the
                // shared geometry (cow body across cow / cow_cold / cow_warm / mooshroom) lands
                // on the same canvas pixels regardless of which variant is rendering. The
                // family canvas was sized to the union of all member bounds, so each variant's
                // bounds are guaranteed to fit.
                Canvas.Fit fit = canvas.fit().get();
                scale = fit.scale();
                anchorX = fit.anchorX();
                anchorY = fit.anchorY();
            } else {
                // Diagnostic pose sweep: scale to fit the local bounds in the supplied square
                // canvas. Not pixel-comparable across frames, but that's the point of a pose
                // sweep - we want to see the whole entity at every angle.
                Bounds bounds = walker.computeScreenBounds(renderer, state, effectiveRotation);
                float modelW = bounds.width();
                float modelH = bounds.height();
                scale = (modelW <= 0 || modelH <= 0)
                    ? Math.min(scope.width(), scope.height())
                    : Math.min(scope.width() / modelW, scope.height() / modelH);
                anchorX = bounds.centerX();
                anchorY = bounds.centerY();
            }
            float translateX = scope.width() / 2.0f - anchorX * scale;
            float translateY = scope.height() / 2.0f - anchorY * scale;

            PoseStack poseStack = new PoseStack();
            poseStack.translate(translateX, translateY, 0.0f);
            poseStack.scale(scale, scale, scale);
            // Chirality compensation. The transform chain has an odd number of reflections
            // by default - PIP's poseStack.scale(s, s, -s) (1 negation, det -1) + vanilla's
            // setupRotations Y180 + scale(-1, -1, 1) (0 + 2 negations, det +1) - so models
            // would render with back-faces showing through (lights inside, textures mirrored).
            // scale(1, 1, -1) here adds the missing reflection (det -1) so the cumulative
            // negation count becomes even and chirality is preserved.
            poseStack.scale(1.0f, 1.0f, -1.0f);
            poseStack.mulPose(effectiveRotation);

            scope.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

            walker.dumpTrianglesIfRequested(renderer, state, translateX, translateY, scale, effectiveRotation);

            CameraRenderState cameraRenderState = new CameraRenderState();
            dispatcher.submit(state, cameraRenderState, /*x*/ 0.0, /*y*/ 0.0, /*z*/ 0.0,
                poseStack, scope.storage());
            return true;
        });
    }

    @SuppressWarnings("unchecked")
    private static EntityRenderer<? super Entity, ?> renderer(EntityRenderDispatcher dispatcher, Entity entity) {
        return (EntityRenderer<? super Entity, ?>) dispatcher.getRenderer(entity);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EntityRenderState createRenderState(EntityRenderer renderer, Entity entity) {
        return renderer.createRenderState(entity, /*partialTick*/ 0.0f);
    }


    @Override
    public void close() {
        pip.close();
        walker.close();
    }
}
