package lib.minecraft.refharness;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
// Identifier is the 26.1 rename of ResourceLocation - same package, same role.
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
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
public final class EntityFrameRenderer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Packed light value for "fully lit" (skylight 15 << 20 | blocklight 15 << 4).
     */
    private static final int FULL_BRIGHT_LIGHT = 15728880;

    /**
     * Iso pose quaternion. Empirically locked at {@code rotationXYZ(210°, 45°, 0°)} via
     * a 24-step yaw sweep + 576-frame pitch×roll sweep over a cow + chirality fix:
     * yaw=135 was the corner-correct value but JOML's getEulerAnglesXYZ returns the
     * principal Y in [-90°, 90°], which decomposed our (30°, 135°, 0°) lock-in into the
     * equivalent (210°, 45°, 180°). The pitch×roll sweep was therefore using yaw=45
     * (extracted), and pitch=210 + roll=0 was the right corner with correct face winding
     * once chirality was fixed (see scale(1, 1, -1) below).
     */
    static final Quaternionf ISO_ROTATION = new Quaternionf().rotationXYZ(
        (float) Math.toRadians(210.0),
        (float) Math.toRadians(45.0),
        0f);

    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionMatrixBuffer = new ProjectionMatrixBuffer("refharness entity PIP");

    /**
     * Diagnostic per-triangle screen-coord trace rectangle parsed from
     * {@code -Dentity.pixel.dump=x0,y0,x1,y1}. Mirrors the asset-renderer
     * {@code ModelEngine.PIXEL_DUMP_RECT} parser so both sides share one prop. When non-null and
     * non-empty, {@link #dumpTrianglesIfRequested} walks every visible polygon (primary model
     * + active layers) through the same canvas-fit + LER pose chain {@code dispatcher.submit}
     * uses internally, triangulates each quad as {@code (v0,v1,v2)+(v0,v2,v3)} to match
     * {@code EntityGeometryKit.contributeTriangles}, and emits one {@code [PX] TRI} line per
     * triangle whose projected bbox intersects the rect. Per-pixel ground truth still requires
     * a GPU stencil pass; this dump only surfaces which triangles cross the rect with what
     * screen-space corners, which is enough to distinguish chain-drift (different coords) from
     * rasterizer-coverage (same coords, different pixel pick) for the witch x=21 residual.
     */
    private static final int @org.jetbrains.annotations.Nullable [] PIXEL_DUMP_RECT = parsePixelDumpRect();

    private static int @org.jetbrains.annotations.Nullable [] parsePixelDumpRect() {
        String prop = System.getProperty("entity.pixel.dump");
        if (prop == null || prop.isBlank()) return null;
        String[] parts = prop.split(",");
        if (parts.length != 4) {
            System.out.println("[PX] malformed entity.pixel.dump: '" + prop + "' (expected x0,y0,x1,y1)");
            return null;
        }
        try {
            int x0 = Integer.parseInt(parts[0].trim());
            int y0 = Integer.parseInt(parts[1].trim());
            int x1 = Integer.parseInt(parts[2].trim());
            int y1 = Integer.parseInt(parts[3].trim());
            System.out.println("[PX-HEADER] " + String.join("\t",
                "stage", "debugTag",
                "s0x", "s0y", "s1x", "s1y", "s2x", "s2y",
                "p0x", "p0y", "p0z", "p1x", "p1y", "p1z", "p2x", "p2y", "p2z"));
            System.out.println("[PX] harness dump rect=" + x0 + "," + y0 + "-" + x1 + "," + y1);
            return new int[]{ x0, y0, x1, y1 };
        } catch (NumberFormatException nfe) {
            System.out.println("[PX] malformed entity.pixel.dump numbers: '" + prop + "'");
            return null;
        }
    }

    /**
     * Cache of loaded entity textures keyed by their resource location. Read by the bounds walker
     * to skip polygons whose four vertex UVs all land on transparent pixels (warden tendrils,
     * wither ribcage planes, etc) - those cubes are flat planes whose authored model extent far
     * exceeds the visible texture region, and including the empty extent in the screen bounds
     * shifts the rendered entity off-centre. Entries are owned by this renderer; closed in
     * {@link #close} alongside the GPU textures.
     */
    private final Map<Identifier, NativeImage> textureCache = new HashMap<>();

    private GpuTexture colorTexture;
    private GpuTextureView colorTextureView;
    private GpuTexture depthTexture;
    private GpuTextureView depthTextureView;
    private int textureWidth;
    private int textureHeight;

    /**
     * Immutable screen-space bounds of an entity at the iso pose, in entity-local screen
     * coordinates (post-iso rotation, pre-canvas-scale). Returned by
     * {@link #measureBounds} for the family-fit pre-pass; the sweeper unions these across
     * variants of the same family to size the family canvas.
     */
    public record EntityBounds(float minX, float maxX, float minY, float maxY) {

        public float width() { return maxX - minX; }

        public float height() { return maxY - minY; }

        /**
         * Returns the smallest bounds that fully contains both this and {@code other}. Used
         * to compute family-max bounds across every variant of an entity family (cow +
         * cow_cold + cow_warm + mooshroom).
         */
        public EntityBounds union(EntityBounds other) {
            return new EntityBounds(
                Math.min(minX, other.minX),
                Math.max(maxX, other.maxX),
                Math.min(minY, other.minY),
                Math.max(maxY, other.maxY));
        }
    }

    /**
     * Family-locked canvas + scale + anchor. Computed once per family in the sweeper's
     * pre-pass and reused for every render of every variant in that family, so each family
     * member shares one scale (pixel-identical body geometry across variants) and one canvas
     * (so cow.png and mooshroom.png have the same dimensions and the cow body lands on the
     * same pixels in both).
     *
     * <p>Layout:
     * <ul>
     *   <li>{@code canvasWidth} / {@code canvasHeight}: pixels of the output PNG.
     *       Independent of the {@link HarnessConfig#IMAGE_SIZE block-render IMAGE_SIZE}
     *       constant - sized to the union of family-member screen bounds × scale, with
     *       a one-pixel safety margin baked in by ceiling.</li>
     *   <li>{@code scale}: pixels per entity-local screen-coord unit. Set from
     *       {@link HarnessConfig#PIXELS_PER_BLOCK}; constant across families so cross-family
     *       relative size reflects entity size in the world.</li>
     *   <li>{@code anchorX} / {@code anchorY}: the entity-local screen-coord point that
     *       maps to the canvas centre. Set to the family-union centre, so the union exactly
     *       fills the canvas and each variant's geometry sits at the same canvas pixel as
     *       in any other family member's render.</li>
     * </ul>
     */
    public record FamilyFit(int canvasWidth, int canvasHeight, float scale, float anchorX, float anchorY) {}

    /**
     * Renders the given entity into the internal texture and writes the texture's pixels
     * to {@code outputPath} as a PNG, using the standard iso pose. See
     * {@link #renderAndWrite(Minecraft, Entity, int, Path, Quaternionf)} for the variant
     * that overrides the rotation.
     */
    public void renderAndWrite(Minecraft client, Entity entity, int size, Path outputPath) throws IOException {
        renderAndWrite(client, entity, size, outputPath, ISO_ROTATION);
    }

    /**
     * Square fit-to-canvas render, used by the diagnostic pitch-roll sweep. Each frame is
     * sized to {@code size × size} and the entity is scaled to fill that canvas based on its
     * own bounds - <em>not</em> family-locked, so direct cross-frame comparison of body
     * size in this output is not meaningful (use the family-fit overload for ground-truth
     * variant parity).
     */
    public void renderAndWrite(Minecraft client, Entity entity, int size, Path outputPath, Quaternionf rotation) throws IOException {
        renderInternal(client, entity, rotation, size, size, /*familyFit*/ null, outputPath);
    }

    /**
     * Family-locked render. The canvas dimensions and scale come from the supplied
     * {@link FamilyFit} (computed by the sweeper's pre-pass), not from the entity's own
     * bounds, so every render of every family member produces the same canvas size at the
     * same scale - shared geometry lands on the same pixels across variants.
     */
    public void renderAndWrite(Minecraft client, Entity entity, FamilyFit fit, Path outputPath) throws IOException {
        renderInternal(client, entity, ISO_ROTATION, fit.canvasWidth, fit.canvasHeight, fit, outputPath);
    }

    /**
     * Measures the entity's screen-space bounds at the standard iso pose, mirroring the
     * exact transform chain {@link #renderAndWrite} uses but skipping the GPU work. The
     * returned bounds are in entity-local screen coords (post-iso, pre-canvas-scale) and
     * are the same units the bounds walker collects internally - the sweeper unions these
     * across family members to size the family canvas.
     */
    public EntityBounds measureBounds(Minecraft client, Entity entity) {
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        EntityRenderer<? super Entity, ?> renderer = renderer(dispatcher, entity);
        EntityRenderState state = createRenderState(renderer, entity);
        Quaternionf effectiveRotation = (renderer instanceof LivingEntityRenderer<?, ?, ?>)
            ? ISO_ROTATION
            : new Quaternionf(ISO_ROTATION).rotateY((float) Math.PI);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.lightCoords = FULL_BRIGHT_LIGHT;
        ScreenBounds bounds = computeScreenBounds(renderer, state, effectiveRotation);
        return new EntityBounds(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY);
    }

    private void renderInternal(
        Minecraft client,
        Entity entity,
        Quaternionf rotation,
        int canvasWidth,
        int canvasHeight,
        FamilyFit familyFit,
        Path outputPath
    ) throws IOException {
        ensureTextures(canvasWidth, canvasHeight);

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
        state.lightCoords = FULL_BRIGHT_LIGHT;

        FeatureRenderDispatcher fed = client.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage storage = fed.getSubmitNodeStorage();
        MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();
        Lighting lighting = client.gameRenderer.getLighting();

        GpuDevice device = RenderSystem.getDevice();
        device.createCommandEncoder().clearColorAndDepthTextures(colorTexture, 0, depthTexture, 1.0);

        RenderSystem.outputColorTextureOverride = colorTextureView;
        RenderSystem.outputDepthTextureOverride = depthTextureView;
        try {
            // Z range needs to fit the model's diagonal extent in the rotated + family-scaled
            // frame. PIXELS_PER_BLOCK=256 default + iso rotation + chirality scale + 6x
            // GiantRenderer scale push the nearest/farthest corners past +/- 1000 and the
            // standard +/- 1000 range clips the front-most corners of giant entirely. +/-
            // 10000 fits giant comfortably while keeping the depth buffer precise enough to
            // avoid z-fighting between adjacent cube faces (going to +/- 100000 was enough
            // to cause visible front-corner cutouts on ghast and dark artifacts on dragon).
            projection.setupOrtho(-10000.0f, 10000.0f, textureWidth, textureHeight, /*invertY*/ true);
            RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);

            float scale;
            float anchorX;
            float anchorY;
            if (familyFit != null) {
                // Family-locked: every family member uses the family's scale + anchor so the
                // shared geometry (cow body across cow / cow_cold / cow_warm / mooshroom) lands
                // on the same canvas pixels regardless of which variant is rendering. The
                // family canvas was sized to the union of all member bounds, so each variant's
                // bounds are guaranteed to fit.
                scale = familyFit.scale;
                anchorX = familyFit.anchorX;
                anchorY = familyFit.anchorY;
            } else {
                // Diagnostic pitch-roll sweep: scale to fit the local bounds in the supplied
                // square canvas. Not pixel-comparable across frames, but that's the point of
                // a pose sweep - we want to see the whole entity at every angle.
                ScreenBounds bounds = computeScreenBounds(renderer, state, effectiveRotation);
                float modelW = bounds.width();
                float modelH = bounds.height();
                scale = (modelW <= 0 || modelH <= 0)
                    ? Math.min(textureWidth, textureHeight)
                    : Math.min(textureWidth / modelW, textureHeight / modelH);
                anchorX = (bounds.minX + bounds.maxX) / 2.0f;
                anchorY = (bounds.minY + bounds.maxY) / 2.0f;
            }
            float translateX = textureWidth / 2.0f - anchorX * scale;
            float translateY = textureHeight / 2.0f - anchorY * scale;

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

            lighting.setupFor(Lighting.Entry.ENTITY_IN_UI);

            dumpTrianglesIfRequested(renderer, state, translateX, translateY, scale, effectiveRotation);

            CameraRenderState cameraRenderState = new CameraRenderState();
            dispatcher.submit(state, cameraRenderState, /*x*/ 0.0, /*y*/ 0.0, /*z*/ 0.0,
                poseStack, storage);
            fed.renderAllFeatures();
            bufferSource.endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }

        writeTextureToPng(outputPath);
    }

    @SuppressWarnings("unchecked")
    private static EntityRenderer<? super Entity, ?> renderer(EntityRenderDispatcher dispatcher, Entity entity) {
        return (EntityRenderer<? super Entity, ?>) dispatcher.getRenderer(entity);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EntityRenderState createRenderState(EntityRenderer renderer, Entity entity) {
        return renderer.createRenderState(entity, /*partialTick*/ 0.0f);
    }

    /**
     * Walks the entity's model parts through the same transform chain we'll use for
     * rendering and collects the min/max screen-space coordinates of every visible
     * polygon vertex. Used to derive an exact-fit scale + centering offset.
     */
    private ScreenBounds computeScreenBounds(EntityRenderer<?, ?> renderer, EntityRenderState state, Quaternionf rotation) {
        Model<?> model = tryGetModel(renderer, state);
        if (model == null) {
            float w = state.boundingBoxWidth;
            float h = state.boundingBoxHeight;
            return new ScreenBounds(-w / 2, w / 2, -h, 0);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Model rawModel = model;
        if (!Boolean.getBoolean("refharness.headless")) {
            try {
                rawModel.setupAnim(state);
            } catch (RuntimeException ignored) {
                // Some renderers' setupAnim assumes additional state fields; if they fail,
                // bounds will still be measured from the model's rest pose.
            }
        }
        // In headless mode {@link SkipSetupAnimMixin} suppresses {@code setupAnim} on the
        // submit-side render path so the primary body renders at its authored bind pose.
        // The bounds walker must agree with the render path or the canvas-fit math anchors
        // off a pose vanilla then doesn't draw. Worse, {@code setupAnim} MUTATES the model's
        // bone rotations - calling it here for a bounds-only measurement would leave the
        // model in the setupAnim'd pose, and the {@code SkipSetupAnimMixin}-protected submit
        // would then queue a deferred render reading those mutated bones. Result before this
        // skip: vanilla zombie / husk / drowned / piglin / villager all rendered with the
        // iconic forward-arm pose (because {@code AbstractZombieModel.animateZombieArms}
        // unconditionally rotates arms at zero state with {@code armDrop = -pi/2.25 ~ -80
        // degrees}), even though the submit-side {@code setupAnim} was being skipped. The
        // resulting silhouette ran ~38% wider than asset-renderer's bind-pose render and the
        // family-fit canvas absorbed the asymmetric extra extent. Skipping {@code setupAnim}
        // here too keeps both bounds AND render aligned to the bind pose.

        PoseStack ps = new PoseStack();
        ps.scale(1.0f, 1.0f, -1.0f);  // chirality compensation - matches renderAndWrite
        ps.mulPose(rotation);
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> && state instanceof LivingEntityRenderState living) {
            // Mirror vanilla LivingEntityRenderer.submit's setupRotations + scale stack
            // so the bounds sit in the same frame as the render. Body-rot is zero (we
            // zeroed it before extractRenderState). setupRotations is virtual - several
            // subclass renderers (SquidRenderer, ShulkerRenderer, FoxRenderer, ...) replace
            // it with their own pose math that adds translates on top of the base Y180
            // rotation. Hardcoding the base behaviour here would make bounds disagree with
            // render on those entities (squid's tentacles fall below the canvas because
            // its setupRotations adds translate(0, -1.2, 0) that bounds didn't see), so
            // dispatch virtually via reflection to pick up whichever override applies.
            ps.scale(living.scale, living.scale, living.scale);
            invokeRendererSetupRotations(renderer, state, ps, /*bodyRot*/ 0.0f, living.scale);
            ps.scale(-1.0f, -1.0f, 1.0f);
            invokeRendererScale(renderer, state, ps);
            ps.translate(0.0f, -1.501f, 0.0f);
        } else if (renderer.getClass().getSimpleName().equals("EnderDragonRenderer")) {
            // EnderDragonRenderer extends EntityRenderer directly (not LivingEntityRenderer)
            // so the standard LER chain above doesn't apply, but submit() still does its own
            // pose-stack preamble before submitting the model: a yaw rotation based on the
            // historical sample at index 7, an x-rotation based on the y-delta between
            // historical samples 5 and 10, then translate(0, 0, 1), scale(-1, -1, 1), and
            // translate(0, -1.501, 0). The historical rotations are zero for our transient
            // entity (the entity never tickets, so flightHistory's 64 samples are all the
            // {y=0, yRot=0} default the constructor fills with) but the chirality scale and
            // y-offset translate are unconditional - without them, bounds are computed in
            // un-flipped X/Y and the rendered dragon ends up positioned outside the bounds-
            // derived canvas (clipping the wings/head off the top while leaving an empty
            // band at the bottom, which is the canonical symptom users report).
            ps.translate(0.0f, 0.0f, 1.0f);
            ps.scale(-1.0f, -1.0f, 1.0f);
            ps.translate(0.0f, -1.501f, 0.0f);
        }

        // Resolve the renderer's primary texture so polygons whose four UV samples all hit
        // transparent pixels (warden's flat tendril plane cubes, wither's ribcage plane, etc)
        // can be skipped - their authored cube extents are far larger than what's drawn, and
        // including the empty extent shifts the bounds centre off the visible silhouette.
        NativeImage texture = entityTexture(renderer, state);

        ScreenBounds bounds = new ScreenBounds();
        Consumer<? super org.joml.Vector3fc> expand = vec -> bounds.expand(vec.x(), vec.y());
        walkVisibleExtents(model.root(), ps, texture, expand);

        // Feature-renderer overlay models (sheep wool, mooshroom mushroom-cow's body overlay,
        // armor, glowing eyes, leash hat, ...) submit additional geometry on top of the primary
        // model that is NOT reachable from {@code model.root()}. Without including those layers
        // the fit-to-canvas scale is set from the bare body alone, leaving the overlay to clip
        // past the canvas edge - sheep wool is the canonical case (the wool layer inflates the
        // body cubes by ~0.5 model units so the silhouette grows by ~3% in each direction).
        // Walk every {@code EntityModel}-shaped field on each layer with the same pose stack
        // so the overlay extents fold into the bounds. Layers that render block geometry
        // instead of an EntityModel (mooshroom mushrooms, copper-golem's flower) are not
        // covered by this pass and need separate per-layer handling.
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> ler) {
            walkLayerExtents(ler, state, ps, texture, expand);
            walkBlockOverlayExtents(ler, state, ps, expand);
        }
        return bounds;
    }

    /**
     * Walks every {@code EntityModel}/{@code Model}-typed field on each registered
     * {@link RenderLayer} of {@code renderer} through the same pose stack the primary model
     * uses, expanding the supplied bounds accumulator with each polygon vertex. Reflectively
     * accesses {@link LivingEntityRenderer}'s {@code layers} list (it is {@code protected})
     * and each layer's instance fields (each layer subclass holds its overlay model in a
     * differently-named private field - {@code adultModel}/{@code babyModel} on
     * {@code SheepWoolLayer}, plain {@code model} on most others).
     * <p>
     * Each found model is dual-purposed: {@code setupAnim(state)} is invoked first so the
     * overlay tracks the same per-tick state as the primary model, then the model's
     * {@link Model#root() root} is fed into {@link #walkVisibleExtents}. Errors from
     * {@code setupAnim} are swallowed because some layers' models assume state fields that
     * a transient zero-state entity doesn't populate - in that case the overlay's rest pose
     * is what gets measured, which is what we want anyway.
     */
    private static void walkLayerExtents(
        LivingEntityRenderer<?, ?, ?> renderer,
        EntityRenderState state,
        PoseStack ps,
        NativeImage texture,
        Consumer<? super org.joml.Vector3fc> expand
    ) {
        boolean headless = Boolean.getBoolean("refharness.headless");
        List<? extends RenderLayer<?, ?>> layers = layersOf(renderer);
        for (RenderLayer<?, ?> layer : layers) {
            if (!isLayerActiveForState(layer, state)) continue;
            for (Model<?> layerModel : findLayerModels(layer, state)) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Model raw = layerModel;
                // Same reasoning as the primary-model {@code setupAnim} skip in
                // {@link #computeScreenBounds}: in headless mode the render-side
                // {@code setupAnim} call is no-op'd, and calling it here would mutate the
                // layer model into a pose vanilla then doesn't draw. Plus most layer
                // {@code setupAnim} reads from {@code state.rightArmPose} /
                // {@code state.leftArmPose} / {@code state.isAggressive}, all of which are
                // either equipment-driven (zero at headless) or already frozen by
                // {@link FreezeAnimationStateMixin}.
                if (!headless) {
                    try {
                        raw.setupAnim(state);
                    } catch (RuntimeException ignored) {
                    }
                }
                walkVisibleExtents(layerModel.root(), ps, texture, expand);
            }
        }
    }

    /**
     * Includes block-model layers (mooshroom mushrooms, snow-golem carved_pumpkin) in the bounds.
     * {@link #walkLayerExtents} only covers {@code EntityModel}-typed layer fields, so block layers
     * - which render a vanilla block model rather than an {@code EntityModel} - were skipped, and
     * the family-fit canvas cropped the overlay. The Java pipeline measures these block overlays
     * alpha-tight (opaque texels only) and grows its canvas to fit them; matching that here keeps
     * the reference PNG's canvas identical and uncropped.
     *
     * <p>Faithful capture: for each active layer with no {@code EntityModel} field (the block /
     * item layers - equipment/held-item layers are already filtered by
     * {@link #isLayerActiveForState}), invoke the layer's {@code submit} through a {@link Proxy}
     * {@link SubmitNodeCollector} that intercepts {@code submitBlockModel} and no-ops everything
     * else. Vanilla applies the exact head-bone + layer pose and the block's own transformation to
     * the pose stack before the {@code submitBlockModel} call, so the captured pose is the full
     * transform; empty blocks (iron-golem flower / enderman carried block at zero state) early-return
     * from {@code submit} and capture nothing. Each captured {@link BakedQuad} is then walked
     * alpha-tight against its sprite via {@link #contributeBlockQuadExtents}.
     */
    private static void walkBlockOverlayExtents(
        LivingEntityRenderer<?, ?, ?> renderer,
        EntityRenderState state,
        PoseStack ps,
        Consumer<? super org.joml.Vector3fc> expand
    ) {
        net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder finder = blockAtlasSpriteFinder();
        if (finder == null) return;
        for (RenderLayer<?, ?> layer : layersOf(renderer)) {
            if (!isLayerActiveForState(layer, state)) continue;
            if (!findLayerModels(layer, state).isEmpty()) continue; // EntityModel layer - handled by walkLayerExtents
            captureBlockSubmits(layer, state, ps, finder, expand);
        }
    }

    /**
     * Invokes {@code layer.submit(...)} with a proxy {@link SubmitNodeCollector} that intercepts each
     * {@code submitBlockModel(poseStack, ..., Mesh, ...)} call and contributes that block model's
     * alpha-tight extents inline (returning harmless defaults for every other collector method). The
     * Fabric renderer API reroutes the block geometry into a {@code Mesh} argument rather than the
     * vanilla {@code BlockStateModelPart} list, so the geometry is read from the mesh's
     * {@link net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView QuadView}s (converted to
     * {@link BakedQuad} via the block-atlas {@code SpriteFinder}); the captured pose already includes
     * the head-bone / layer pose and the block's own transformation. Empty blocks (iron-golem flower /
     * enderman carried block at zero state) early-return from {@code submit} and contribute nothing.
     */
    private static void captureBlockSubmits(
        RenderLayer<?, ?> layer, EntityRenderState state, PoseStack ps,
        net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder finder,
        Consumer<? super org.joml.Vector3fc> expand
    ) {
        Method submit = findLayerSubmit(layer, state);
        if (submit == null) return;
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("submitBlockModel") && args != null) {
                PoseStack pose = null;
                net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView mesh = null;
                for (Object a : args) {
                    if (a instanceof PoseStack p) pose = p;
                    else if (a instanceof net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView m) mesh = m;
                }
                if (pose != null && mesh != null) {
                    org.joml.Matrix4fc snap = new org.joml.Matrix4f(pose.last().pose());
                    mesh.forEach(quad -> {
                        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = finder.find(quad);
                        if (sprite != null)
                            contributeBlockQuadExtents(quad.toBakedQuad(sprite), snap, expand);
                    });
                }
            }
            if (method.getName().equals("order")) return proxy; // OrderedSubmitNodeCollector fluent no-op
            Class<?> ret = method.getReturnType();
            if (ret == boolean.class) return false;
            if (ret == int.class) return 0;
            if (ret == long.class) return 0L;
            if (ret == float.class) return 0f;
            if (ret == double.class) return 0d;
            if (ret == void.class || !ret.isPrimitive()) return null;
            return (byte) 0;
        };
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
            SubmitNodeCollector.class.getClassLoader(),
            new Class<?>[]{ SubmitNodeCollector.class },
            handler);
        try {
            submit.setAccessible(true);
            // submit(PoseStack, SubmitNodeCollector, int packedLight, S state, float, float)
            submit.invoke(layer, ps, collector, FULL_BRIGHT_LIGHT, state, 0.0f, 0.0f);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static final Field MODEL_MANAGER_ATLAS_MANAGER;
    static {
        Field f = null;
        try {
            f = net.minecraft.client.resources.model.ModelManager.class.getDeclaredField("atlasManager");
            f.setAccessible(true);
        } catch (NoSuchFieldException ignored) {
        }
        MODEL_MANAGER_ATLAS_MANAGER = f;
    }

    /**
     * Resolves the {@code SpriteFinder} for the block atlas (Fabric interface-injected on
     * {@link net.minecraft.client.renderer.texture.TextureAtlas}), used to recover each mesh quad's
     * sprite for alpha sampling. Returns {@code null} when the atlas manager can't be reached.
     */
    private static net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder blockAtlasSpriteFinder() {
        if (MODEL_MANAGER_ATLAS_MANAGER == null) return null;
        try {
            Object am = MODEL_MANAGER_ATLAS_MANAGER.get(Minecraft.getInstance().getModelManager());
            if (!(am instanceof net.minecraft.client.resources.model.sprite.AtlasManager atlasManager)) return null;
            // The atlas is keyed by its config id, not its texture path, so look it up by matching
            // location() == LOCATION_BLOCKS rather than getAtlasOrThrow(LOCATION_BLOCKS).
            net.minecraft.client.renderer.texture.TextureAtlas[] blockAtlas = { null };
            atlasManager.forEach((id, atlas) -> {
                if (net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS.equals(atlas.location()))
                    blockAtlas[0] = atlas;
            });
            if (blockAtlas[0] == null) return null;
            return ((net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas) (Object) blockAtlas[0]).spriteFinder();
        } catch (IllegalAccessException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Finds a layer's typed {@code submit(PoseStack, SubmitNodeCollector, int, S, float, float)}
     * override - the one whose 4th parameter is compatible with {@code state} (the
     * {@code EntityRenderState} bridge overload never carries block geometry). Returns {@code null}
     * when no matching override exists.
     */
    private static Method findLayerSubmit(RenderLayer<?, ?> layer, EntityRenderState state) {
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("submit") || m.getParameterCount() != 6) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p[0] != PoseStack.class || p[1] != SubmitNodeCollector.class || p[2] != int.class) continue;
                if (p[4] != float.class || p[5] != float.class) continue;
                if (!p[3].isInstance(state)) continue;
                if (p[3] == EntityRenderState.class) continue; // skip the bridge overload
                return m;
            }
        }
        return null;
    }

    /**
     * Alpha-tight extent contribution for one block {@link BakedQuad}. Unpacks the quad's four
     * atlas UVs, converts them to sprite-local pixel space, walks the opaque sub-rectangle via
     * {@link net.minecraft.client.renderer.texture.SpriteContents#isTransparent}, bilinearly maps
     * the opaque corners back through the quad's four transformed vertices, and feeds them to
     * {@code expand}. Fully-transparent quads contribute nothing.
     */
    private static void contributeBlockQuadExtents(
        net.minecraft.client.resources.model.geometry.BakedQuad quad,
        org.joml.Matrix4fc pose,
        Consumer<? super org.joml.Vector3fc> expand
    ) {
        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = quad.materialInfo().sprite();
        if (sprite == null) return;
        net.minecraft.client.renderer.texture.SpriteContents contents = sprite.contents();
        int W = contents.width();
        int H = contents.height();
        if (W <= 0 || H <= 0) return;

        // Transform the four vertices through the captured pose (block space -> entity-local screen).
        Vector3f[] pos = new Vector3f[4];
        float[] su = new float[4];
        float[] sv = new float[4];
        // The mesh quad's packed UVs are ATLAS coordinates. Convert to sprite-local [0, 1] by the
        // sprite's atlas bounds. The two packed components are atlas (horizontal, vertical) but the
        // renderer's u()/v() axes are transposed relative to the sprite's U/V, so assign each packed
        // component to its axis by which atlas range ([u0,u1] vs [v0,v1]) it falls in - those ranges
        // are disjoint for a stitched sprite. Fully-degenerate sprites (du/dv == 0) contribute nothing.
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        float du = u1 - u0, dv = v1 - v0;
        if (du == 0f || dv == 0f) return;
        float uLo = Math.min(u0, u1), uHi = Math.max(u0, u1), vLo = Math.min(v0, v1), vHi = Math.max(v0, v1);
        for (int i = 0; i < 4; i++) {
            org.joml.Vector3fc p = quad.position(i);
            pos[i] = pose.transformPosition(p.x(), p.y(), p.z(), new Vector3f());
            long packed = quad.packedUV(i);
            float a = Float.intBitsToFloat((int) (packed & 0xFFFFFFFFL));
            float b = Float.intBitsToFloat((int) (packed >>> 32));
            boolean aIsU = a >= uLo - 1e-4f && a <= uHi + 1e-4f && !(a >= vLo - 1e-4f && a <= vHi + 1e-4f);
            float atlasU = aIsU ? a : b;
            float atlasV = aIsU ? b : a;
            su[i] = (atlasU - u0) / du;
            sv[i] = (atlasV - v0) / dv;
        }

        float uMin = Math.min(Math.min(su[0], su[1]), Math.min(su[2], su[3]));
        float uMax = Math.max(Math.max(su[0], su[1]), Math.max(su[2], su[3]));
        float vMin = Math.min(Math.min(sv[0], sv[1]), Math.min(sv[2], sv[3]));
        float vMax = Math.max(Math.max(sv[0], sv[1]), Math.max(sv[2], sv[3]));
        if (uMin == uMax || vMin == vMax) return;

        // Classify the four vertices into the sprite-UV rect corners (BL/BR/TR/TL).
        Vector3f bl = null, br = null, tr = null, tl = null;
        float eps = 1e-4f;
        for (int i = 0; i < 4; i++) {
            boolean atUMin = Math.abs(su[i] - uMin) < eps, atUMax = Math.abs(su[i] - uMax) < eps;
            boolean atVMin = Math.abs(sv[i] - vMin) < eps, atVMax = Math.abs(sv[i] - vMax) < eps;
            if (atUMin && atVMin) bl = pos[i];
            else if (atUMax && atVMin) br = pos[i];
            else if (atUMax && atVMax) tr = pos[i];
            else if (atUMin && atVMax) tl = pos[i];
        }
        if (bl == null || br == null || tr == null || tl == null) {
            for (Vector3f p : pos) expand.accept(p);
            return;
        }

        // Same texel-overlap bounds as contributePolygonExtents / contributeFaceAlphaTight.
        int pxMin = clampPixel((int) Math.floor(uMin * W), W);
        int pxMax = clampPixel((int) Math.ceil(uMax * W) - 1, W);
        int pyMin = clampPixel((int) Math.floor(vMin * H), H);
        int pyMax = clampPixel((int) Math.ceil(vMax * H) - 1, H);
        int firstPx = Integer.MAX_VALUE, lastPx = Integer.MIN_VALUE, firstPy = Integer.MAX_VALUE, lastPy = Integer.MIN_VALUE;
        for (int py = pyMin; py <= pyMax; py++) {
            for (int px = pxMin; px <= pxMax; px++) {
                if (contents.isTransparent(0, px, py)) continue;
                if (px < firstPx) firstPx = px;
                if (px > lastPx) lastPx = px;
                if (py < firstPy) firstPy = py;
                if (py > lastPy) lastPy = py;
            }
        }
        if (firstPx == Integer.MAX_VALUE) return; // fully transparent

        float oUMin = Math.max(uMin, (float) firstPx / W);
        float oUMax = Math.min(uMax, (float) (lastPx + 1) / W);
        float oVMin = Math.max(vMin, (float) firstPy / H);
        float oVMax = Math.min(vMax, (float) (lastPy + 1) / H);
        expand.accept(bilinearCorner(oUMin, oVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl));
        expand.accept(bilinearCorner(oUMax, oVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl));
        expand.accept(bilinearCorner(oUMax, oVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl));
        expand.accept(bilinearCorner(oUMin, oVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl));
    }

    /**
     * Bilinearly interpolates the four transformed quad corners at sprite-UV fraction
     * {@code (u, v)} within {@code [uMin, uMax] x [vMin, vMax]}. The corners are already in
     * entity-local screen space, so no further transform is applied.
     */
    private static Vector3f bilinearCorner(
        float u, float v, float uMin, float uMax, float vMin, float vMax,
        Vector3f bl, Vector3f br, Vector3f tr, Vector3f tl
    ) {
        float s = (u - uMin) / (uMax - uMin);
        float t = (v - vMin) / (vMax - vMin);
        float w00 = (1 - s) * (1 - t), w10 = s * (1 - t), w11 = s * t, w01 = (1 - s) * t;
        return new Vector3f(
            w00 * bl.x() + w10 * br.x() + w11 * tr.x() + w01 * tl.x(),
            w00 * bl.y() + w10 * br.y() + w11 * tr.y() + w01 * tl.y(),
            w00 * bl.z() + w10 * br.z() + w11 * tr.z() + w01 * tl.z());
    }

    /**
     * Class-name suffixes / exact matches for {@link RenderLayer} subclasses whose
     * {@code submit()} renders nothing for the zero-state entity the harness uses (no
     * equipment, no held items, no stuck arrows, no special poses). The family-fit pre-pass
     * walks each layer's {@code Model<?>} fields to pad bounds; including these layers
     * over-pads the canvas with margin around invisible geometry, because the model exists
     * (constructed at layer-init time) but is never rasterised at zero state. Silhouette-bbox
     * audit 2026-05-15 confirmed: wolf_pale / pig_warm / horse / skeleton_horse have vanilla
     * canvases with 5-47px transparent margin from layer-model bounds that never render.
     * <p>
     * Patterns:
     * <ul>
     *   <li>{@code *ArmorLayer} - HumanoidArmorLayer, WolfArmorLayer, HorseArmorLayer,
     *       LeatherHorseArmorLayer, ... - gates on {@code state.equipment.X.isEmpty()} or
     *       analogous variant-armor fields, all empty at zero state</li>
     *   <li>{@code *EquipmentLayer} - generic equipment overlay base class on horses, wolves,
     *       skeletons in 1.21+; same gating shape</li>
     *   <li>{@code ItemInHandLayer} / {@code PlayerItemInHandLayer} - submits the held-item
     *       model; bails when both hands hold {@code ItemStack.EMPTY}</li>
     *   <li>{@code ElytraLayer} - gates on the elytra equipment slot being non-empty</li>
     *   <li>{@code SaddleLayer} / {@code HorseSaddleLayer} - pig / equine saddle, gates on
     *       {@code state.saddle.isEmpty()}</li>
     *   <li>{@code *CollarLayer} - wolf / cat dyed collar, gates on {@code state.isTame}</li>
     *   <li>{@code StuckInBodyLayer} - parent of arrow / bee-stinger layers, gates on stack
     *       count {@code > 0}</li>
     *   <li>{@code TridentLayer} / {@code ShieldLayer} / {@code BeeStingerLayer} -
     *       held-item variants for specific items, all empty at zero state</li>
     *   <li>{@code SnowLayer} (powdered-snow stack) / {@code LlamaDecorLayer} (carpet) -
     *       cosmetic state overlays, all absent at zero state</li>
     *   <li>{@code ParrotOnShoulderLayer} - player-only; zero state has no parrot</li>
     *   <li>{@code CapeLayer} / {@code Deadmau5EarsLayer} / {@code EarsLayer} - player cosmetic
     *       overlays, zero state has none</li>
     *   <li>{@code DolphinCarryingItemLayer} - dolphin-specific held item</li>
     *   <li>{@code FoxHeldItemLayer} - fox-specific held item</li>
     * </ul>
     * The match walks the layer's class hierarchy so subclasses still hit the gate. If a
     * vanilla refactor renames a class, the default-true branch preserves current
     * behaviour (over-padded canvases) until the list is updated.
     */
    private static final java.util.Set<String> NO_RENDER_LAYER_SUFFIXES = java.util.Set.of(
        "ArmorLayer",
        "EquipmentLayer",
        "ItemInHandLayer",
        "ElytraLayer",
        "WingsLayer",  // 1.21+ rename of ElytraLayer; HumanoidMobRenderer adds it for every humanoid
        "CustomHeadLayer",  // renders mob-head / pumpkin / dragon-head equipment; HumanoidMobRenderer adds it for every humanoid
        "SaddleLayer",
        "CollarLayer",
        "StuckInBodyLayer",
        "TridentLayer",
        "ShieldLayer",
        "BeeStingerLayer",
        "SnowLayer",
        "LlamaDecorLayer",
        "ParrotOnShoulderLayer",
        "CapeLayer",
        "Deadmau5EarsLayer",
        "EarsLayer",
        "DolphinCarryingItemLayer",
        "FoxHeldItemLayer",
        "RopesLayer"  // happy_ghast leash-holder gate (state.isLeashHolder && bodyItem.is(HARNESSES));
                      // never fires for zero-state entity, but the layer's adultModel/babyModel
                      // fields have CubeDeformation(0.2F) inflate that would inflate bounds by ~22
                      // canvas pixels if walked - leaving the canvas oversized for the actual body
    );

    /**
     * Checks whether a layer would actually render for the given state. Returns {@code true}
     * by default - we walk the layer's geometry. Returns {@code false} when the layer's submit
     * is known to skip rendering for this state, so the layer's model shouldn't pad bounds.
     * <p>
     * Two gate families:
     * <ol>
     *   <li><b>{@code EnergySwirlLayer}</b> (charged creeper, wither armor electric overlay)
     *       - reflectively invokes {@code isPowered(state)} to detect the un-powered case.
     *       Specific because the charged-creeper mesh is visibly larger than the body, so
     *       over-padding here used to ~2x the silhouette.</li>
     *   <li><b>{@link #NO_RENDER_LAYER_SUFFIXES Equipment-driven layers}</b> - any layer in
     *       the well-known no-render-at-zero-state class list returns {@code false}. Catches
     *       HumanoidArmorLayer / ItemInHandLayer / ElytraLayer / SaddleLayer / etc. without
     *       reflectively probing per-state-class equipment fields.</li>
     * </ol>
     * Falls back to walking the layer when reflection / class-lookup fails - over-padded
     * bounds is preferable to clipped bounds. Other conditional layers
     * ({@code LivingEntityEmissiveLayer} with alpha=0, etc.) aren't handled here; add cases
     * as new entities surface bounds-padding regressions.
     */
    private static boolean isLayerActiveForState(RenderLayer<?, ?> layer, EntityRenderState state) {
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String simpleName = c.getSimpleName();
            if (simpleName.equals("EnergySwirlLayer")) {
                try {
                    Method m = c.getDeclaredMethod("isPowered", EntityRenderState.class);
                    m.setAccessible(true);
                    Object result = m.invoke(layer, state);
                    return result instanceof Boolean b ? b : true;
                } catch (ReflectiveOperationException ignored) {
                    return true;
                }
            }
            for (String suffix : NO_RENDER_LAYER_SUFFIXES)
                if (simpleName.endsWith(suffix)) return false;
        }
        return true;
    }

    private static final Field LIVING_RENDERER_LAYERS;
    static {
        try {
            LIVING_RENDERER_LAYERS = LivingEntityRenderer.class.getDeclaredField("layers");
            LIVING_RENDERER_LAYERS.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("LivingEntityRenderer.layers field not found - vanilla refactored its layer storage", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<? extends RenderLayer<?, ?>> layersOf(LivingEntityRenderer<?, ?, ?> renderer) {
        try {
            Object value = LIVING_RENDERER_LAYERS.get(renderer);
            return value instanceof List<?> list
                ? (List<? extends RenderLayer<?, ?>>) list
                : Collections.emptyList();
        } catch (IllegalAccessException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Returns every {@link Model} instance the layer's {@code submit()} would render for the
     * given {@code state}. Walks the layer's own class fields plus its superclass fields up to
     * (but not including) {@link RenderLayer} - the base class only holds the parent renderer
     * reference, never an overlay model.
     *
     * <p>Most layers carry exactly one {@code Model<?>} field and submit it unconditionally; for
     * those the field walk returns the right Model directly. A few layers carry multiple Model
     * fields and dispatch between them inside {@code submit()} - the layer renders ONE of them
     * per call based on the {@link EntityRenderState}. Walking all of them for bounds would
     * over-pad the canvas with extents from a Model that {@code submit()} never reaches at the
     * current state. The harness needs to mirror the gate so bounds and render agree.
     *
     * <p>Known multi-model layers (handled below):
     * <ul>
     *   <li>{@code SheepWoolLayer} - {@code adultModel} vs {@code babyModel}, gated on
     *       {@code state.isBaby}. Adult is the default; baby model is smaller, so walking both
     *       at zero state didn't change the canvas - this case is included for correctness.</li>
     *   <li>{@code TropicalFishPatternLayer} - {@code modelSmall} vs {@code modelLarge}, gated
     *       on {@code state.pattern.base()}. The default pattern (KOB) is in {@code SMALL}, so
     *       only {@code modelSmall} renders for the headless tropical fish; the previous "walk
     *       both" behaviour over-padded the canvas by one Y-pixel because modelLarge's left_fin
     *       reaches further up the Y axis than any modelSmall opaque texel does. The asset-
     *       renderer's submit-side pipeline picks the same single Model and produced a tighter
     *       canvas, so vanilla's PNG used to land 103x94 while the asset-renderer's came in
     *       103x93 - this gate brings the harness onto the same single-Model bound as the
     *       in-game renderer and the parity-test compare-target.</li>
     * </ul>
     * When the layer isn't on the recognised multi-model list, the full Model field set is
     * returned so any future layer with a single Model field "just works" (single field = single
     * Model = no over-pad risk).
     */
    private static List<Model<?>> findLayerModels(RenderLayer<?, ?> layer, EntityRenderState state) {
        List<Model<?>> models = new java.util.ArrayList<>();
        // Walk every Model<?> field by name so the multi-model gate below can pick by field name
        // instead of by index (field declaration order is JVM-stable per class but easier to
        // reason about with explicit names).
        java.util.LinkedHashMap<String, Model<?>> fieldModels = new java.util.LinkedHashMap<>();
        Class<?> cls = layer.getClass();
        while (cls != null && cls != RenderLayer.class && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!Model.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(layer);
                    if (value instanceof Model<?> m) fieldModels.putIfAbsent(f.getName(), m);
                } catch (IllegalAccessException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        if (fieldModels.isEmpty()) return models;
        String activeName = pickActiveModelName(layer, state, fieldModels.keySet());
        if (activeName != null) {
            Model<?> active = fieldModels.get(activeName);
            if (active != null) {
                models.add(active);
                return models;
            }
        }
        models.addAll(fieldModels.values());
        return models;
    }

    /**
     * For layers whose {@code submit()} picks one of several Model fields based on state, returns
     * the field name of the model that submit() would render at {@code state}. Returns
     * {@code null} for layers not on the recognised multi-model list - the caller then falls back
     * to walking every Model field.
     *
     * <p>Detection is by layer class simple name so subclasses that don't override the gate also
     * pick up the parent's selection rule. Reflection over {@code state} accessors keeps the
     * recognised set decoupled from concrete state types (the harness doesn't link against
     * {@code TropicalFishRenderState} or {@code TropicalFish.Pattern} directly).
     */
    private static @org.jetbrains.annotations.Nullable String pickActiveModelName(
        RenderLayer<?, ?> layer, EntityRenderState state, java.util.Set<String> fieldNames
    ) {
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String simple = c.getSimpleName();
            if ("SheepWoolLayer".equals(simple)) {
                boolean isBaby = state instanceof LivingEntityRenderState lrs && lrs.isBaby;
                return isBaby ? "babyModel" : "adultModel";
            }
            if ("TropicalFishPatternLayer".equals(simple)) {
                // state.pattern is a TropicalFish$Pattern enum; pattern.base() returns
                // TropicalFish$Pattern$Base, an enum with SMALL / LARGE values. Resolve via
                // reflection so the harness's bounds module doesn't pull in the fish package.
                try {
                    java.lang.reflect.Field patternField = state.getClass().getField("pattern");
                    Object pattern = patternField.get(state);
                    if (pattern != null) {
                        Object base = pattern.getClass().getMethod("base").invoke(pattern);
                        String baseName = base == null ? "" : base.toString();
                        if ("SMALL".equals(baseName)) return "modelSmall";
                        if ("LARGE".equals(baseName)) return "modelLarge";
                    }
                } catch (ReflectiveOperationException ignored) {
                }
                // Default to the small model when the state probe fails - matches vanilla's
                // DEFAULT_VARIANT (KOB, WHITE, WHITE) whose base is SMALL.
                if (fieldNames.contains("modelSmall")) return "modelSmall";
            }
        }
        return null;
    }

    /**
     * Returns the renderer's primary texture as a {@link NativeImage}, suitable for alpha
     * sampling. Looks up {@code getTextureLocation(state)} reflectively because vanilla
     * declares it {@code protected} on every {@link EntityRenderer} subclass; results are
     * cached in {@link #textureCache} keyed by resource location so repeat renders on the
     * same entity (variant sweeps, side-by-side captures) don't reopen the resource each call.
     * Returns {@code null} when the renderer hides the method, the location does not resolve
     * to a resource, or the PNG fails to decode - the bounds walker degrades to including all
     * polygons in that case, matching pre-task #11 behaviour.
     */
    private NativeImage entityTexture(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        Identifier location = invokeGetTextureLocation(renderer, state);
        if (location == null) return null;
        NativeImage cached = textureCache.get(location);
        if (cached != null) return cached;
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(location).orElse(null);
            if (resource == null) return null;
            NativeImage image;
            try (InputStream stream = resource.open()) {
                image = NativeImage.read(stream);
            }
            textureCache.put(location, image);
            return image;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Reflectively invokes the renderer's {@code getTextureLocation(state)} method. Walks the
     * class hierarchy (LivingEntityRenderer, MobRenderer, AgeableMobRenderer subclasses define
     * it at varying depths) and matches by name + state-compatible parameter. Returns
     * {@code null} when no compatible override exists - block-entity-style renderers and the
     * ender dragon's custom path do not declare it.
     */
    private static Identifier invokeGetTextureLocation(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        Class<?> cls = renderer.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("getTextureLocation") || m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(state)) continue;
                if (!Identifier.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object value = m.invoke(renderer, state);
                    return value instanceof Identifier rl ? rl : null;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static final Field MODEL_PART_CHILDREN;
    private static final Field MODEL_PART_CUBES;
    static {
        try {
            MODEL_PART_CHILDREN = ModelPart.class.getDeclaredField("children");
            MODEL_PART_CHILDREN.setAccessible(true);
            MODEL_PART_CUBES = ModelPart.class.getDeclaredField("cubes");
            MODEL_PART_CUBES.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("ModelPart structure changed in this Minecraft version - children/cubes fields not found", e);
        }
    }

    /**
     * Emits one {@code [PX] TRI} line per visible-polygon triangle whose projected bbox
     * intersects {@link #PIXEL_DUMP_RECT}, for diagnostic comparison against
     * {@code ModelEngine}'s same-named line. Builds the full canvas-fit + chirality + iso
     * + LER chain pose stack vanilla's {@code dispatcher.submit} composes internally so the
     * emitted {@code s0/s1/s2} coordinates are in the same pixel-space frame the asset-renderer
     * side emits. Walks both the primary model and every active layer (matching the bounds
     * walker's coverage). Triangulation is fixed at {@code (v0,v1,v2)+(v0,v2,v3)} to match
     * {@code EntityGeometryKit.contributeTriangles}.
     * <p>
     * No-op when {@link #PIXEL_DUMP_RECT} is unset.
     */
    private void dumpTrianglesIfRequested(
        EntityRenderer<?, ?> renderer,
        EntityRenderState state,
        float translateX,
        float translateY,
        float canvasScale,
        Quaternionf effectiveRotation
    ) {
        if (PIXEL_DUMP_RECT == null) return;
        Model<?> model = tryGetModel(renderer, state);
        if (model == null) return;

        PoseStack ps = new PoseStack();
        ps.translate(translateX, translateY, 0.0f);
        ps.scale(canvasScale, canvasScale, canvasScale);
        // Chirality compensation - mirrors renderInternal exactly.
        ps.scale(1.0f, 1.0f, -1.0f);
        ps.mulPose(effectiveRotation);
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> && state instanceof LivingEntityRenderState living) {
            ps.scale(living.scale, living.scale, living.scale);
            invokeRendererSetupRotations(renderer, state, ps, /*bodyRot*/ 0.0f, living.scale);
            ps.scale(-1.0f, -1.0f, 1.0f);
            invokeRendererScale(renderer, state, ps);
            ps.translate(0.0f, -1.501f, 0.0f);
        } else if (renderer.getClass().getSimpleName().equals("EnderDragonRenderer")) {
            ps.translate(0.0f, 0.0f, 1.0f);
            ps.scale(-1.0f, -1.0f, 1.0f);
            ps.translate(0.0f, -1.501f, 0.0f);
        }

        walkPolyTrianglesImpl(model.root(), "root", ps);
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> ler) {
            boolean headless = Boolean.getBoolean("refharness.headless");
            List<? extends RenderLayer<?, ?>> layers = layersOf(ler);
            for (RenderLayer<?, ?> layer : layers) {
                if (!isLayerActiveForState(layer, state)) continue;
                for (Model<?> layerModel : findLayerModels(layer, state)) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Model raw = layerModel;
                    if (!headless) {
                        try { raw.setupAnim(state); } catch (RuntimeException ignored) {}
                    }
                    walkPolyTrianglesImpl(layerModel.root(), "layer:" + layer.getClass().getSimpleName(), ps);
                }
            }
        }
    }

    private static void walkPolyTrianglesImpl(ModelPart part, String bonePath, PoseStack ps) {
        if (!part.visible) return;
        Map<String, ModelPart> children = childrenOf(part);
        List<ModelPart.Cube> cubes = cubesOf(part);
        if (cubes.isEmpty() && children.isEmpty()) return;
        ps.pushPose();
        part.translateAndRotate(ps);
        if (!part.skipDraw) {
            PoseStack.Pose pose = ps.last();
            int cubeIndex = 0;
            for (ModelPart.Cube cube : cubes) {
                int polyIndex = 0;
                for (ModelPart.Polygon polygon : cube.polygons) {
                    emitPolygonTriangles(polygon, pose, bonePath, cubeIndex, polyIndex);
                    polyIndex++;
                }
                cubeIndex++;
            }
        }
        for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
            walkPolyTrianglesImpl(entry.getValue(), bonePath + "/" + entry.getKey(), ps);
        }
        ps.popPose();
    }

    /**
     * Transforms a polygon's 4 vertices through {@code pose}, triangulates as
     * {@code (v0,v1,v2)+(v0,v2,v3)}, and emits one {@code [PX] TRI} line per triangle whose
     * screen-space bbox intersects {@link #PIXEL_DUMP_RECT}. Coordinates are emitted in
     * post-pose pixel-space - the ortho with {@code invertY=true} maps math-Y to screen-Y so
     * pose-space-Y == screen-Y (origin at top, increasing downward), matching the
     * asset-renderer's {@code s0/s1/s2} convention.
     */
    private static void emitPolygonTriangles(ModelPart.Polygon polygon, PoseStack.Pose pose,
                                              String bonePath, int cubeIndex, int polyIndex) {
        ModelPart.Vertex[] verts = polygon.vertices();
        if (verts.length < 4) return;
        Vector3f v0 = pose.pose().transformPosition(verts[0].worldX(), verts[0].worldY(), verts[0].worldZ(), new Vector3f());
        Vector3f v1 = pose.pose().transformPosition(verts[1].worldX(), verts[1].worldY(), verts[1].worldZ(), new Vector3f());
        Vector3f v2 = pose.pose().transformPosition(verts[2].worldX(), verts[2].worldY(), verts[2].worldZ(), new Vector3f());
        Vector3f v3 = pose.pose().transformPosition(verts[3].worldX(), verts[3].worldY(), verts[3].worldZ(), new Vector3f());

        String tagA = bonePath + "/cube" + cubeIndex + "/poly" + polyIndex + "/triA";
        String tagB = bonePath + "/cube" + cubeIndex + "/poly" + polyIndex + "/triB";
        emitTriangleIfIntersects(tagA, v0, v1, v2);
        emitTriangleIfIntersects(tagB, v0, v2, v3);
    }

    private static void emitTriangleIfIntersects(String debugTag, Vector3f s0, Vector3f s1, Vector3f s2) {
        float minX = Math.min(s0.x(), Math.min(s1.x(), s2.x()));
        float maxX = Math.max(s0.x(), Math.max(s1.x(), s2.x()));
        float minY = Math.min(s0.y(), Math.min(s1.y(), s2.y()));
        float maxY = Math.max(s0.y(), Math.max(s1.y(), s2.y()));
        if (maxX < PIXEL_DUMP_RECT[0] || minX > PIXEL_DUMP_RECT[2]) return;
        if (maxY < PIXEL_DUMP_RECT[1] || minY > PIXEL_DUMP_RECT[3]) return;
        // Screen coords ARE pose-space x/y after the canvas-fit + LER chain; depth is z.
        // The harness emits one combined line per triangle to match ModelEngine.projectTriangle.
        System.out.println("[PX]\tTRI\t" + debugTag
            + "\ts0=" + s0.x() + "," + s0.y()
            + "\ts1=" + s1.x() + "," + s1.y()
            + "\ts2=" + s2.x() + "," + s2.y()
            + "\tp0=" + s0.x() + "," + s0.y() + "," + s0.z()
            + "\tp1=" + s1.x() + "," + s1.y() + "," + s1.z()
            + "\tp2=" + s2.x() + "," + s2.y() + "," + s2.z());
    }

    /**
     * Custom hierarchy walker that mirrors {@link ModelPart#render}'s visibility filtering:
     * skips parts where {@code visible} is false, skips own cubes where {@code skipDraw}
     * is true. Vanilla's {@link ModelPart#getExtentsForGui} ignores both flags, so it
     * would include polygons that {@code render()} skips.
     * <p>
     * Additionally, when {@code texture} is non-null, polygons whose four corner UVs all
     * sample fully-transparent pixels are excluded from the bounds. This catches plane-style
     * cubes whose authored extent dwarfs the visible texture region (warden's
     * {@code left_tendril}/{@code right_tendril}, wither ribcage planes) and would otherwise
     * pad the bounds by an unrendered margin and shift the on-canvas centre. Mirrors
     * vanilla's per-pixel alpha discard at the rasteriser level - if every vertex of a quad
     * lands on alpha=0, the rasteriser would discard every pixel inside that quad too.
     * Polygons with opaque corners but transparent interior (rare for cube-based MC entity
     * textures) still expand the bounds.
     */
    private static void walkVisibleExtents(ModelPart part, PoseStack ps, NativeImage texture, Consumer<? super org.joml.Vector3fc> output) {
        walkVisibleExtentsImpl(part, "root", ps, texture, output);
    }

    private static void walkVisibleExtentsImpl(ModelPart part, String bonePath, PoseStack ps, NativeImage texture, Consumer<? super org.joml.Vector3fc> output) {
        if (!part.visible) return;
        Map<String, ModelPart> children = childrenOf(part);
        List<ModelPart.Cube> cubes = cubesOf(part);
        if (cubes.isEmpty() && children.isEmpty()) return;
        ps.pushPose();
        part.translateAndRotate(ps);
        if (!part.skipDraw) {
            PoseStack.Pose pose = ps.last();
            int cubeIndex = 0;
            for (ModelPart.Cube cube : cubes) {
                for (ModelPart.Polygon polygon : cube.polygons) {
                    contributePolygonExtents(polygon, pose, texture, output, bonePath, cubeIndex, cube);
                }
                cubeIndex++;
            }
        }
        for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
            walkVisibleExtentsImpl(entry.getValue(), bonePath + "/" + entry.getKey(), ps, texture, output);
        }
        ps.popPose();
    }

    /**
     * Expands the bounds accumulator with positions inside {@code polygon} that map to
     * opaque texels on {@code texture}. Replaces an older binary include/exclude filter
     * (which contributed all 4 vertex corners whenever <em>any</em> sampled texel was
     * opaque, ballooning bounds on plane-cubes like the warden's tendril where a small
     * sticker shared a 16×16 quad with mostly-transparent pixels) and a sparser 5×5 sample
     * version (which under-cut bounds at polygon edges when the corner texels happened to
     * be transparent - the bee wings, frog feet, and warden tendril tips all visibly clipped).
     * <p>
     * Algorithm: walk every texel inside the polygon's UV bounding box, accumulate the
     * tightest sub-rectangle that contains all opaque texels (snapped to texel edges so an
     * opaque texel at (px, py) extends its UV contribution to {@code [px/W, (px+1)/W] ×
     * [py/H, (py+1)/H]}, capturing the texel's full footprint). Then bilinearly interpolate
     * the four corners of that opaque sub-rectangle through the polygon's four vertex
     * positions and feed those four 3D positions to the bounds accumulator. Net effect:
     * <ul>
     *   <li>Fully opaque polygons: opaque box equals polygon UV box - the four corners are
     *       just the polygon's four vertices, identical to the legacy 4-corner behaviour.</li>
     *   <li>Polygons with a sparse opaque sticker (warden tendrils, etc.): opaque box is
     *       tight to the sticker's texels, four corners reflect just the sticker extent.</li>
     *   <li>Fully transparent polygons: no opaque texels, polygon contributes nothing.</li>
     * </ul>
     * <p>
     * Identifies the four vertices' corner roles (BL/BR/TR/TL) by their (u, v) values matching
     * the polygon's UV-axis-aligned bounding box. If the polygon's UVs aren't axis-aligned
     * (rare for vanilla cube faces), classification fails and we fall back to contributing the
     * 4 raw vertex positions.
     * <p>
     * Cost is O(W × H) per polygon's UV box (e.g. 256 alpha lookups for a 16×16 face). The
     * harness pre-pass measures ~5000 polygons across 100 entities, so total alpha-walk work
     * is ~1.3M lookups - tens of milliseconds in practice, dominated by setupAnim and pose
     * stack work.
     */
    private static final boolean BOUNDS_DUMP = Boolean.getBoolean("refharness.boundsDump");

    private static void contributePolygonExtents(ModelPart.Polygon polygon, PoseStack.Pose pose, NativeImage texture, Consumer<? super org.joml.Vector3fc> output,
                                                  String bonePath, int cubeIndex, ModelPart.Cube cube) {
        if (texture == null) {
            for (ModelPart.Vertex vertex : polygon.vertices()) {
                Vector3f p = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new Vector3f());
                output.accept(p);
            }
            return;
        }
        int width = texture.getWidth();
        int height = texture.getHeight();
        if (width <= 0 || height <= 0) {
            for (ModelPart.Vertex vertex : polygon.vertices()) {
                Vector3f p = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new Vector3f());
                output.accept(p);
            }
            return;
        }
        float uMin = Float.POSITIVE_INFINITY, uMax = Float.NEGATIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY, vMax = Float.NEGATIVE_INFINITY;
        for (ModelPart.Vertex vertex : polygon.vertices()) {
            uMin = Math.min(uMin, vertex.u());
            uMax = Math.max(uMax, vertex.u());
            vMin = Math.min(vMin, vertex.v());
            vMax = Math.max(vMax, vertex.v());
        }
        String dumpPrefix = BOUNDS_DUMP
            ? String.format("[BD] bone=%s cube=%d cubebbox=(%g,%g,%g)-(%g,%g,%g)",
                bonePath, cubeIndex,
                cube.minX, cube.minY, cube.minZ,
                cube.maxX, cube.maxY, cube.maxZ)
            : null;
        // Skip degenerate (zero-area) polygons. Plane cubes (e.g. warden tendril at 16×16×0)
        // contribute four zero-area edge polygons whose UVs collapse to a line; they render
        // no pixels, but their 4 vertex positions span the full cube extent. Dropping them
        // is bounds-preserving because the visible-face polygons already cover the screen
        // extent through their per-opaque-texel contributions.
        if (uMin == uMax || vMin == vMax) {
            if (dumpPrefix != null) System.out.println(dumpPrefix + " DEGEN_UV");
            return;
        }
        // Classify the 4 vertices by which corner of the UV rect they sit on.
        ModelPart.Vertex[] verts = polygon.vertices();
        ModelPart.Vertex bl = null, br = null, tr = null, tl = null;
        float eps = 1e-4f;
        for (ModelPart.Vertex v : verts) {
            boolean atUMin = Math.abs(v.u() - uMin) < eps;
            boolean atUMax = Math.abs(v.u() - uMax) < eps;
            boolean atVMin = Math.abs(v.v() - vMin) < eps;
            boolean atVMax = Math.abs(v.v() - vMax) < eps;
            if (atUMin && atVMin) bl = v;
            else if (atUMax && atVMin) br = v;
            else if (atUMax && atVMax) tr = v;
            else if (atUMin && atVMax) tl = v;
        }
        if (bl == null || br == null || tr == null || tl == null) {
            for (ModelPart.Vertex vertex : verts) {
                Vector3f p = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new Vector3f());
                output.accept(p);
            }
            if (dumpPrefix != null) System.out.println(dumpPrefix + " NON_AXIS_UV_FALLBACK_4_CORNERS");
            return;
        }
        // Walk every texel inside the polygon's UV box and accumulate the opaque-texel bbox.
        // Each texel at (px, py) covers the half-open UV rect [px/W, (px+1)/W) x
        // [py/H, (py+1)/H), so the inclusive upper bound is ceil(uMax*W) - 1, not
        // floor(uMax*W). The floor form over-includes the adjacent texel row when uMax*W
        // (or vMax*H) lands exactly on an integer boundary - which happens whenever a face
        // UV is texel-aligned, i.e. for every standard 16x16 / 32x32 / 64x64 cube face
        // including all HumanoidModel sleeves / pants / jacket outer-layer faces. This was
        // surfaced by the asset-renderer side's identical bug: piglin_brute's right_sleeve
        // WEST face has vMax = 48/64 = 0.75 exactly; with floor() the walker admitted texel
        // row 48 which is part of the adjacent left_arm UP face, making the (otherwise
        // transparent) sleeve "look" opaque and contribute its inflated extent to the
        // bounds.
        int pxMin = clampPixel((int) Math.floor(uMin * width), width);
        int pxMax = clampPixel((int) Math.ceil(uMax * width) - 1, width);
        int pyMin = clampPixel((int) Math.floor(vMin * height), height);
        int pyMax = clampPixel((int) Math.ceil(vMax * height) - 1, height);
        int firstOpaquePx = Integer.MAX_VALUE, lastOpaquePx = Integer.MIN_VALUE;
        int firstOpaquePy = Integer.MAX_VALUE, lastOpaquePy = Integer.MIN_VALUE;
        for (int py = pyMin; py <= pyMax; py++) {
            for (int px = pxMin; px <= pxMax; px++) {
                int alpha = (texture.getPixel(px, py) >>> 24) & 0xFF;
                if (alpha == 0) continue;
                if (px < firstOpaquePx) firstOpaquePx = px;
                if (px > lastOpaquePx) lastOpaquePx = px;
                if (py < firstOpaquePy) firstOpaquePy = py;
                if (py > lastOpaquePy) lastOpaquePy = py;
            }
        }
        if (firstOpaquePx == Integer.MAX_VALUE) {
            if (dumpPrefix != null) System.out.printf("%s uv_px=%d,%d,%d,%d ALL_TRANSPARENT%n",
                dumpPrefix, pxMin, pyMin, pxMax, pyMax);
            return;
        }
        // Convert opaque-pixel range to UV-space using texel edges (a texel at (px, py)
        // covers UV [px/W, (px+1)/W] × [py/H, (py+1)/H]). Clamp the resulting UV box to the
        // polygon's UV range so we don't overshoot the geometry.
        float opaqueUMin = Math.max(uMin, (float) firstOpaquePx / width);
        float opaqueUMax = Math.min(uMax, (float) (lastOpaquePx + 1) / width);
        float opaqueVMin = Math.max(vMin, (float) firstOpaquePy / height);
        float opaqueVMax = Math.min(vMax, (float) (lastOpaquePy + 1) / height);
        // Contribute the four bilinear-interpolated corners of the opaque sub-rect.
        Vector3f cBl = contributeBilinearCorner(opaqueUMin, opaqueVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        Vector3f cBr = contributeBilinearCorner(opaqueUMax, opaqueVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        Vector3f cTr = contributeBilinearCorner(opaqueUMax, opaqueVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        Vector3f cTl = contributeBilinearCorner(opaqueUMin, opaqueVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        if (dumpPrefix != null) System.out.printf(
            "%s uv_px=%d,%d,%d,%d opaque_px=%d,%d,%d,%d screen_bl=(%g,%g,%g) screen_br=(%g,%g,%g) screen_tr=(%g,%g,%g) screen_tl=(%g,%g,%g)%n",
            dumpPrefix,
            pxMin, pyMin, pxMax, pyMax,
            firstOpaquePx, firstOpaquePy, lastOpaquePx, lastOpaquePy,
            cBl.x(), cBl.y(), cBl.z(),
            cBr.x(), cBr.y(), cBr.z(),
            cTr.x(), cTr.y(), cTr.z(),
            cTl.x(), cTl.y(), cTl.z());
    }

    private static Vector3f contributeBilinearCorner(
        float u, float v,
        float uMin, float uMax, float vMin, float vMax,
        ModelPart.Vertex bl, ModelPart.Vertex br, ModelPart.Vertex tr, ModelPart.Vertex tl,
        PoseStack.Pose pose, Consumer<? super org.joml.Vector3fc> output
    ) {
        float s = (u - uMin) / (uMax - uMin);
        float t = (v - vMin) / (vMax - vMin);
        float w00 = (1 - s) * (1 - t);
        float w10 = s * (1 - t);
        float w11 = s * t;
        float w01 = (1 - s) * t;
        float px = w00 * bl.worldX() + w10 * br.worldX() + w11 * tr.worldX() + w01 * tl.worldX();
        float py = w00 * bl.worldY() + w10 * br.worldY() + w11 * tr.worldY() + w01 * tl.worldY();
        float pz = w00 * bl.worldZ() + w10 * br.worldZ() + w11 * tr.worldZ() + w01 * tl.worldZ();
        Vector3f p = pose.pose().transformPosition(px, py, pz, new Vector3f());
        output.accept(p);
        return p;
    }

    /**
     * Legacy binary include/exclude polygon filter, retained as a reference. Replaced in the
     * walk path by {@link #contributePolygonExtents}, which keeps a similar 5×5 sampling
     * approach but contributes <em>per-opaque-sample</em> bilinearly-interpolated positions
     * to the bounds instead of the full 4 vertex corners. That's strictly tighter for
     * polygons with sparse opaque regions (warden tendrils) while matching the legacy
     * behaviour for fully-opaque polygons (every sample is opaque, all 25 contribute, the
     * union of their bilinear positions equals the 4-corner bounds).
     */
    @SuppressWarnings("unused")
    private static boolean polygonAllTransparent(ModelPart.Polygon polygon, NativeImage texture) {
        int width = texture.getWidth();
        int height = texture.getHeight();
        if (width <= 0 || height <= 0) return false;
        float uMin = Float.POSITIVE_INFINITY, uMax = Float.NEGATIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY, vMax = Float.NEGATIVE_INFINITY;
        for (ModelPart.Vertex vertex : polygon.vertices()) {
            uMin = Math.min(uMin, vertex.u());
            uMax = Math.max(uMax, vertex.u());
            vMin = Math.min(vMin, vertex.v());
            vMax = Math.max(vMax, vertex.v());
        }
        // Sample on a 5x5 grid spanning the UV box (inclusive of edges). Step=1/4 puts a
        // sample at each corner, each edge midpoint, the four edge quarter-points, and the
        // four interior quarter-points. 25 samples is overkill for huge faces but makes the
        // tiny-face case robust against off-by-one alpha-edge artifacts.
        for (int j = 0; j <= 4; j++) {
            float v = vMin + (vMax - vMin) * (j / 4.0f);
            int y = clampPixel((int) Math.floor(v * height), height);
            for (int i = 0; i <= 4; i++) {
                float u = uMin + (uMax - uMin) * (i / 4.0f);
                int x = clampPixel((int) Math.floor(u * width), width);
                int alpha = (texture.getPixel(x, y) >>> 24) & 0xFF;
                if (alpha != 0) return false;
            }
        }
        return true;
    }

    private static int clampPixel(int value, int size) {
        if (value < 0) return 0;
        if (value >= size) return size - 1;
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> childrenOf(ModelPart part) {
        try {
            return (Map<String, ModelPart>) MODEL_PART_CHILDREN.get(part);
        } catch (IllegalAccessException e) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ModelPart.Cube> cubesOf(ModelPart part) {
        try {
            return (List<ModelPart.Cube>) MODEL_PART_CUBES.get(part);
        } catch (IllegalAccessException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Calls the renderer's {@code setupRotations(state, poseStack, bodyRot, scale)} override
     * on the given pose stack, mirroring vanilla's virtual dispatch in
     * {@link LivingEntityRenderer#submit}. Several renderers replace the base implementation
     * with custom pose math (squid translates the body up before rotation and back down by
     * a different amount, shulker rotates around its peek pivot, fox tilts when sleeping,
     * etc.); without this dispatch the bounds walker silently uses the base
     * {@code mulPose(Y, 180-bodyRot)} and disagrees with render whenever an override adds a
     * translation - the canonical symptom is squid tentacles falling below the canvas.
     * <p>
     * Walks the class hierarchy looking for a {@code setupRotations} method whose first
     * argument is state-compatible and whose remaining three arguments are
     * {@code (PoseStack, float, float)}. Both the bridge variant
     * ({@code (LivingEntityRenderState, ...)}) and the narrowed variant
     * ({@code (SquidRenderState, ...)}) live on the renderer class - either resolves to the
     * same final method via the standard JVM bridge so we accept the first match.
     * <p>
     * Falls back to {@code mulPose(YP.rotationDegrees(180 - bodyRot))} on reflective failure
     * so the iso-pose calibration still holds for renderers that hide the method or throw
     * during pose extraction.
     */
    private static void invokeRendererSetupRotations(
        EntityRenderer<?, ?> renderer,
        EntityRenderState state,
        PoseStack ps,
        float bodyRot,
        float scale
    ) {
        Class<?> cls = renderer.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("setupRotations") || m.getParameterCount() != 4) continue;
                Class<?>[] params = m.getParameterTypes();
                if (!params[0].isInstance(state) || params[1] != PoseStack.class) continue;
                if (params[2] != float.class || params[3] != float.class) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(renderer, state, ps, bodyRot, scale);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        ps.mulPose(Axis.YP.rotationDegrees(180.0f - bodyRot));
    }

    /**
     * Calls the renderer's {@code scale(state, poseStack)} override on the given
     * pose stack, mirroring what vanilla's {@link LivingEntityRenderer#submit} does.
     * Reached via reflection because the method is protected; falls back to no-op if
     * the class hierarchy walk doesn't find it.
     */
    private static void invokeRendererScale(EntityRenderer<?, ?> renderer, EntityRenderState state, PoseStack ps) {
        Class<?> cls = renderer.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("scale") || m.getParameterCount() != 2) continue;
                Class<?>[] params = m.getParameterTypes();
                if (!params[0].isInstance(state) || params[1] != PoseStack.class) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(renderer, state, ps);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
    }

    /**
     * Resolves the entity model from a renderer. {@link LivingEntityRenderer} exposes it
     * via {@code getModel()}; for everything else we walk the class hierarchy looking for
     * a field whose declared type is a {@link Model} subclass.
     * <p>
     * For variant-using renderers ({@code CowRenderer}, {@code PigRenderer},
     * {@code ChickenRenderer}) the renderer keeps a {@code Map<ModelType, AdultAndBabyModelPair>}
     * instead of a single model and mutates {@code this.model} inside {@code submit} to the
     * variant-specific instance before rendering. The bounds walker runs <em>before</em>
     * any submit, so {@code getModel()} returns the constructor's default - which has
     * different geometry than the actual variant being rendered (cow_warm uses
     * {@code WarmCowModel} with bigger horns; the default {@code CowModel} has none).
     * Resolves the variant-specific model up front via {@link #tryResolveVariantModel} so
     * bounds reflect the actual rendered geometry.
     */
    private static Model<?> tryGetModel(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        Model<?> variantModel = tryResolveVariantModel(renderer, state);
        if (variantModel != null) return variantModel;
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> ler) {
            return ler.getModel();
        }
        Class<?> cls = renderer.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (Model.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object value = f.get(renderer);
                        if (value instanceof Model<?> m) return m;
                    } catch (IllegalAccessException ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Resolves the variant-specific model for variant-using renderers (Cow, Pig, Chicken in
     * 26.1) by replicating their submit-time selection logic reflectively. Each such
     * renderer keeps a {@code Map<ModelType, AdultAndBabyModelPair<Model>>} field where the
     * key comes from {@code state.variant.modelAndTexture().model()}; the pair returns adult
     * or baby via {@code getModel(boolean)}. Returns {@code null} for renderers that don't
     * fit this pattern; the caller falls back to {@code renderer.getModel()}.
     */
    private static Model<?> tryResolveVariantModel(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        try {
            Field variantField = findFieldByName(state.getClass(), "variant");
            if (variantField == null) return null;
            variantField.setAccessible(true);
            Object variant = variantField.get(state);
            if (variant == null) return null;
            Method modelAndTexture = findMethodByName(variant.getClass(), "modelAndTexture", 0);
            if (modelAndTexture == null) return null;
            modelAndTexture.setAccessible(true);
            Object mat = modelAndTexture.invoke(variant);
            if (mat == null) return null;
            Method modelKeyGetter = findMethodByName(mat.getClass(), "model", 0);
            if (modelKeyGetter == null) return null;
            modelKeyGetter.setAccessible(true);
            Object modelKey = modelKeyGetter.invoke(mat);
            if (modelKey == null) return null;
            Field modelsField = findFieldByName(renderer.getClass(), "models");
            if (modelsField == null || !Map.class.isAssignableFrom(modelsField.getType())) return null;
            modelsField.setAccessible(true);
            Object pair = ((Map<?, ?>) modelsField.get(renderer)).get(modelKey);
            if (pair == null) return null;
            // Two model-map shapes exist. Cow / pig / chicken key on an
            // {@code AdultAndBabyModelPair} (getModel(boolean) picks adult vs baby); zombie_nautilus
            // keys directly on the {@code NautilusModel} value ({@code Map<ModelType, NautilusModel>}).
            // Without this direct-model branch the pair-only path fails for zombie_nautilus and the
            // caller falls back to the DEFAULT NautilusModel - which has none of the WARM variant's
            // coral bones - so the bounds pre-pass sizes the canvas to the bare shell and the coral
            // (the ZombieNautilusCoralModel's corals sub-tree) is cropped in the reference PNG.
            if (pair instanceof Model<?> directModel) return directModel;
            Method getModel = findMethodByName(pair.getClass(), "getModel", 1);
            if (getModel == null) return null;
            getModel.setAccessible(true);
            boolean isBaby = state instanceof LivingEntityRenderState lrs && lrs.isBaby;
            Object model = getModel.invoke(pair, isBaby);
            return model instanceof Model<?> m ? m : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Field findFieldByName(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
        }
        return null;
    }

    private static Method findMethodByName(Class<?> cls, String name, int arity) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
            }
        }
        return null;
    }

    /**
     * Mutable 2D bounds accumulator.
     */
    private static final class ScreenBounds {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        ScreenBounds() {}

        ScreenBounds(float minX, float maxX, float minY, float maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        void expand(float x, float y) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }

        float width() { return maxX - minX; }
        float height() { return maxY - minY; }
    }

    private void ensureTextures(int width, int height) {
        if (colorTexture != null && textureWidth == width && textureHeight == height) return;
        closeTextures();

        GpuDevice device = RenderSystem.getDevice();
        colorTexture = device.createTexture(() -> "refharness entity color", 13,
            TextureFormat.RGBA8, width, height, 1, 1);
        colorTextureView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture(() -> "refharness entity depth", 9,
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
            () -> "refharness-entity-readback", /*usage*/ 9, byteSize);
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
        for (NativeImage image : textureCache.values()) {
            image.close();
        }
        textureCache.clear();
    }
}
