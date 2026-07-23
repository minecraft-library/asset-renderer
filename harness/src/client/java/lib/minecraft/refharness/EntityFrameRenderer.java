package lib.minecraft.refharness;

import java.io.IOException;
import java.nio.file.Path;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.FrameRenderer;
import lib.minecraft.refharness.api.HarnessPose;
import lib.minecraft.refharness.pip.PipScope;
import lib.minecraft.refharness.pip.PipTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Half-extent of the orthographic depth range.
     *
     * <p>The range has to fit the model's diagonal extent in the rotated, family-scaled frame. A
     * 256 pixels-per-block scale plus the iso rotation, the chirality scale and a 6x giant push the
     * nearest and farthest corners past the block-scale range, which clips a giant's front corners
     * entirely. This value fits a giant comfortably while keeping the depth buffer precise enough to
     * avoid z-fighting between adjacent cube faces - an order of magnitude further out was enough to
     * cause visible front-corner cutouts on the ghast and dark artifacts on the dragon.
     */
    private static final float DEPTH_RANGE = 10000.0f;

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
