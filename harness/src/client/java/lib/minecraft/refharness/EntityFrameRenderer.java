package lib.minecraft.refharness;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
            for (Model<?> layerModel : findLayerModels(layer)) {
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
     * Returns every {@link Model} instance reachable through this layer's instance fields.
     * Most layers carry exactly one (`model`); a few carry multiple (e.g. {@code SheepWoolLayer}
     * keeps separate {@code adultModel} and {@code babyModel} so the wool layer matches the
     * baby vs adult silhouette without redoing layer construction). Walks the layer's own class
     * fields plus its superclass fields up to (but not including) {@link RenderLayer} - the
     * base class only holds the parent renderer reference, never an overlay model.
     */
    private static List<Model<?>> findLayerModels(RenderLayer<?, ?> layer) {
        List<Model<?>> models = new java.util.ArrayList<>();
        Class<?> cls = layer.getClass();
        while (cls != null && cls != RenderLayer.class && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!Model.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(layer);
                    if (value instanceof Model<?> m) models.add(m);
                } catch (IllegalAccessException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return models;
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
        if (!part.visible) return;
        Map<String, ModelPart> children = childrenOf(part);
        List<ModelPart.Cube> cubes = cubesOf(part);
        if (cubes.isEmpty() && children.isEmpty()) return;
        ps.pushPose();
        part.translateAndRotate(ps);
        if (!part.skipDraw) {
            PoseStack.Pose pose = ps.last();
            for (ModelPart.Cube cube : cubes) {
                for (ModelPart.Polygon polygon : cube.polygons) {
                    contributePolygonExtents(polygon, pose, texture, output);
                }
            }
        }
        for (ModelPart child : children.values()) {
            walkVisibleExtents(child, ps, texture, output);
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
    private static void contributePolygonExtents(ModelPart.Polygon polygon, PoseStack.Pose pose, NativeImage texture, Consumer<? super org.joml.Vector3fc> output) {
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
        // Skip degenerate (zero-area) polygons. Plane cubes (e.g. warden tendril at 16×16×0)
        // contribute four zero-area edge polygons whose UVs collapse to a line; they render
        // no pixels, but their 4 vertex positions span the full cube extent. Dropping them
        // is bounds-preserving because the visible-face polygons already cover the screen
        // extent through their per-opaque-texel contributions.
        if (uMin == uMax || vMin == vMax) return;
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
        if (firstOpaquePx == Integer.MAX_VALUE) return;
        // Convert opaque-pixel range to UV-space using texel edges (a texel at (px, py)
        // covers UV [px/W, (px+1)/W] × [py/H, (py+1)/H]). Clamp the resulting UV box to the
        // polygon's UV range so we don't overshoot the geometry.
        float opaqueUMin = Math.max(uMin, (float) firstOpaquePx / width);
        float opaqueUMax = Math.min(uMax, (float) (lastOpaquePx + 1) / width);
        float opaqueVMin = Math.max(vMin, (float) firstOpaquePy / height);
        float opaqueVMax = Math.min(vMax, (float) (lastOpaquePy + 1) / height);
        // Contribute the four bilinear-interpolated corners of the opaque sub-rect.
        contributeBilinearCorner(opaqueUMin, opaqueVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        contributeBilinearCorner(opaqueUMax, opaqueVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        contributeBilinearCorner(opaqueUMax, opaqueVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        contributeBilinearCorner(opaqueUMin, opaqueVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
    }

    private static void contributeBilinearCorner(
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
