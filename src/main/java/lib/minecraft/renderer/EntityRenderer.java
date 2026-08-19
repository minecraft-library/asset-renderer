package lib.minecraft.renderer;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.DyeColor;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.appearance.AppearanceGate;
import lib.minecraft.renderer.asset.appearance.CopperWeathering;
import lib.minecraft.renderer.asset.appearance.HorseMarking;
import lib.minecraft.renderer.asset.appearance.TintAxis;
import lib.minecraft.renderer.asset.appearance.TropicalFishPattern;
import lib.minecraft.renderer.asset.appearance.Villager;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.RendererDebug;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.FitRequest;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.camera.Placement;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.camera.RenderFrame;
import lib.minecraft.renderer.engine.compose.RasterPass;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.kit.ArmorKit;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.engine.kit.ElytraKit;
import lib.minecraft.renderer.engine.kit.EntityGeometryKit;
import lib.minecraft.renderer.engine.kit.EquipmentKit;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.PassDeclaration;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Biome;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.option.slot.EntitySlot;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntFunction;

/**
 * Renders mob entities as 3D icons from the Java-derived entity pipeline
 * ({@code entity_models.json} + {@code entity_geometry.json}, produced by
 * {@code ToolingEntityModels} from the vanilla client jar) via {@link EntityGeometryKit}'s
 * Y-down engine path. Texture resolution flows through the vanilla pack via
 * {@link RendererContext#resolveTexture}; missing textures surface as missing entities rather
 * than being papered over with cache fallbacks.
 *
 * <p>The entity is a plain projection subject: the camera is the caller's
 * {@link OutputOptions#getProjection() projection} display pose directly (default
 * {@link Projection#VANILLA_ISO}, the facing-neutral {@code rotationXYZ(30, 225, 0)}), and the entity's
 * model-to-world facing - the humanoid yaw flip plus the Y-down-to-Y-up flip and chirality - is the
 * single {@link #ENTITY_PLACEMENT} {@link Placement}. That split lets any projection be swapped in and
 * still present the subject's front, upright.
 */
public final class EntityRenderer implements Renderer<EntityOptions> {

    /**
     * Renderer context for texture resolution + isometric engine setup; not used for entity lookup.
     */
    private final @NotNull RendererContext context;

    /**
     * Entity definitions keyed by namespaced id, loaded via {@link EntityModelLoader#load()}.
     * Passed in directly rather than queried through {@code context.findEntity()} so visual tests
     * can swap in custom fixtures.
     */
    private final @NotNull Map<String, Entity> javaEntities;

    /**
     * The pack-aware texture-resolution service, bound once to {@link #context}, that every
     * base / variant / group-member texture lookup on this renderer flows through.
     */

    /** The bone the elytra wings hang from - vanilla's torso part on every winged entity. */
    private static final @NotNull String BODY_BONE = "body";

    /**
     * The entity's model-to-world facing - the humanoid {@code R_Y(180)} yaw flip (same as the player's,
     * turning the {@code +Z} front to the camera) composed with vanilla
     * {@code LivingEntityRenderer.submit}'s {@code rotateY(180) * scale(-1,-1,1) = flip180} (the Y-down
     * to Y-up flip + chirality): {@code R_Y(180) * flip180 = R_Z(180) = diag(-1,-1,1)}, which is exactly
     * the {@code scale(-1,-1,1)} built here. Applied as the entity {@link Placement} so
     * {@link Projection#VANILLA_ISO} stays a facing-neutral {@code [30,225,0]} pose like block/player:
     * {@code R(30,225,0) * ENTITY_FACING = R(30,45,0) * flip180} reproduces the shipped orientation, and
     * any projection swapped in keeps the entity upright AND facing.
     */
    private static final @NotNull Matrix4f ENTITY_FACING = Matrix4f.IDENTITY.scale(-1f, -1f, 1f);

    /** The entity's model-to-world {@link Placement} - {@link #ENTITY_FACING} as a placement. */
    private static final @NotNull Placement ENTITY_PLACEMENT = new Placement(ENTITY_FACING);

    /** The path segment marking a baked robe ref as the baby robe directory rather than {@code type/}. */
    private static final @NotNull String BABY_ROBE_SEGMENT = "/baby/";

    /**
     * The prefix qualifying an entity texture ref into the id its sidecar is read under, and the one
     * {@link #resolveEntityTextureAtTick} applies to a pixel read.
     */
    private static final @NotNull String ENTITY_TEXTURE_PREFIX = "minecraft:entity/";

    /**
     * Constructs an entity renderer bound to the given context and entity definitions.
     *
     * @param context the renderer context for texture resolution + isometric engine setup
     * @param javaEntities the entity definitions keyed by namespaced id
     */
    public EntityRenderer(@NotNull RendererContext context, @NotNull Map<String, Entity> javaEntities) {
        this.context = context;
        this.javaEntities = javaEntities;
    }

    /**
     * Renders the entity and composites it over the caller's background. Returns an empty frame
     * (composited over the background) when the entity id is absent, unknown, has no texture, or
     * carries no bones.
     */
    @Override
    public @NotNull ImageData render(@NotNull EntityOptions options) {
        return options.getBackground().composite(renderEntity(options));
    }

    /**
     * Resolves the entity definition, texture, and bounds; sizes the canvas; assembles the base body
     * plus its overlay / block-overlay / armor {@link GeometryLayer geometry layers}; then rasterizes
     * every layer in one shared depth pass through {@link ModelEngine}. Returns an empty frame at any
     * step that cannot produce geometry (absent / unknown id, missing texture, no bones).
     */
    private @NotNull ImageData renderEntity(@NotNull EntityOptions options) {
        if (options.getEntityId().isEmpty())
            return Timeline.empty();

        Entity definition = this.javaEntities.get(options.getEntityId().get());
        if (definition == null)
            return Timeline.empty();

        // Fold the age / carried policy into a single resolved definition up front, so every
        // downstream site (texture, ortho bounds, geometry contributors) reads it unconditionally
        // with no scattered !baby gates. The resolve is a no-op for a non-baby, non-carried appearance.
        Entity resolved = definition.resolve(options.getAppearance());
        EntityModelData model = resolved.model();

        // Resolve the base texture at the timeline's start tick: frame 0 of a sidecar-carrying
        // entity texture, or the raw strip for a
        // sidecar-less texture (every vanilla entity, so byte-identical on the vanilla roster). This
        // start-tick texture drives the missing-texture early-out and canvas sizing; the per-frame
        // render re-resolves inside the rasterizer callback so an opted-in animated texture rebuilds.
        AnimationOptions anim = options.getAnimation();
        Timeline.TickTimeline timeline = Timeline.schedule(anim);
        int startTick = timeline.tickAt(0);
        Optional<PixelBuffer> texture = resolveEntityTexture(resolved, options, startTick);
        if (texture.isEmpty())
            return Timeline.empty();

        if (model.getBones().isEmpty())
            return Timeline.empty();

        // Resolve each selected equipment overlay's composited texture ONCE, up front: it decides both
        // whether the overlay bounds the canvas at all and, on the orthographic path below, the
        // alpha-tight silhouette it contributes. An overlay whose texture does not resolve draws
        // nothing, so it is absent here rather than bounding the canvas with a mesh that never appears.
        List<EquippedOverlay> equipped = resolveEquippedOverlays(resolved, options.getAppearance(), startTick);
        // Resolve the wing texture on the same terms as the equipment overlays above: it decides whether
        // the wings bound the canvas at all, and the silhouette they contribute below. Wings the pack
        // ships no texture for render nothing, so they are empty here rather than bounding the canvas.
        Optional<PixelBuffer> wingTexture = options.getAppearance().isElytra()
            ? ElytraKit.wingsTexture(this.context, Optional.empty(), startTick)
            : Optional.empty();
        // The body bone the wings hang from, in model space - the same seat the render applies, resolved
        // here because the canvas is sized before any geometry is built.
        Optional<Box> bodyBoneBounds = EntityGeometryKit.computeBoneBounds(model, BODY_BONE);

        EulerRotation user = options.getOutput().getRotation();
        EulerRotation effective = new EulerRotation(
            user.pitch(),
            user.yaw() + model.getInventoryYRotation() + definition.setupYawAddend(),
            user.roll()
        );
        // Apply the per-entity scale override (vanilla's combined renderer-scale + state-scale)
        // by scaling the bounds before sizing the canvas. With K-scaled bounds, canvas dimensions
        // grow K x and the projected entity also grows K x so the entity's screen footprint
        // matches the harness's submit-time scale chain. The kit's K x of model vertices happens
        // via the {@code modelScale} parameter on the new buildTriangles overload. Read off the
        // RESOLVED definition: the size axis folds its factor onto rendererScale at resolve
        // (slime / magma_cube non-default sizes), so a non-default size renders at that
        // size rather than byte-identically to the default.
        float modelScale = resolved.rendererScale();

        // The entity is a normal projection subject: the camera is the caller's projection display pose
        // DIRECTLY (default VANILLA_ISO = the facing-neutral rotationXYZ(30,225,0)), and its model->world
        // facing (humanoid R_Y(180)) + Y-down flip + chirality is the single ENTITY_FACING Placement.
        // render = pose · ENTITY_FACING · model_Ydown lands the entity upright AND facing under ANY
        // projection (exactly like the player's R_Y(180) facing, plus the Y-down flip). For the default,
        // R(30,225,0) · ENTITY_FACING = R(30,45,0) · flip180 reproduces the harness orientation.
        Camera entityCamera = options.getOutput().getProjection().resolve(EulerRotation.NONE, options.getOutput().getFacing()).camera();
        ModelEngine engine = new ModelEngine(this.context, entityCamera, ENTITY_PLACEMENT);
        Lens lens = entityCamera.lens();

        // Fit resolution forks on the projection lens family. The kit always emits FIT-NEUTRAL geometry
        // (only the model scale baked in); the engine's rasterizeFitted applies the fit in ONE place, so
        // entity rendering flows through the same auto-fit pipeline the player uses.
        //
        // ORTHOGRAPHIC (VANILLA_ISO + axonometric): size a native pixels-per-block canvas from the
        // entity's alpha-tight (optionally group-unioned) silhouette - measured through the EXACT render
        // orientation ({@code engine.orient}), dispatched on FitMode (OUTPUT_SIZE honours canvasSize +
        // padding; UNION_BOUNDS / GROUP_BOUNDS auto-size from the entity's own / group-unioned bounds).
        // The engine bakes that explicit scale in 3D and centres the measured silhouette midpoint in
        // screen space (NATIVE_SCALE) - so a non-brick silhouette (cod's z=[-4,11] AABB vs its z=[0,7]
        // cube hug) stays tightly centred, without the old model-space anchor inverse. Per-entity
        // setupRotations shifts (squid) ride on the model geometry, applied at load - so they move the
        // silhouette measured here and the geometry drawn from it together, and cannot disagree. That
        // makes such a shift a no-op for a subject measured against its own bounds (this fit centres
        // what it measured) and visible only inside a group-unioned canvas, which is where vanilla
        // makes it visible too.
        //
        // PERSPECTIVE / OBLIQUE: a 3D model-scale fit can't correct strong foreshortening / depth-shear in
        // one pass (scaling the model changes the foreshortening), so unit-normalize the model into a
        // well-behaved range and defer the final screen fill to rasterizeFitted's 2D auto-fit (Fit2D).
        // This is what lets long entities (cod) fit uncropped under PORTRAIT / cavalier / cabinet / military.
        int canvasW;
        int canvasH;
        final RenderFrame kitFrame;
        final FitRequest fitRequest;
        if (lens.kind() == Lens.Kind.ORTHOGRAPHIC) {
            BoundsScope scope = boundsScopeFor(options.getFitMode());
            Matrix4f renderOrient = engine.orient(effective);
            Box screenBounds = computeScreenBoundsFor(scope, options.getEntityId().get(), resolved,
                renderOrient, modelScale, texture.get(), startTick);
            // Fold a selected equipment overlay's mesh into the pre-measured silhouette so an inflated /
            // protruding equipment mesh can't crop at the canvas edge under the NATIVE_SCALE fit (which
            // sizes from these bounds, not the rendered triangles). Measured through the overlay's own
            // composited texture, so it bounds the canvas by what it actually draws - the same
            // alpha-tight walk the base body already gets. Equipment textures are mostly transparent
            // (a saddle is a few straps over a whole equine body), so the geometric AABB would size the
            // canvas for a silhouette an order of magnitude larger than the render. Gated on the
            // equipment axis, so the default (unequipped) canvas is unchanged.
            for (EquippedOverlay equipment : equipped)
                screenBounds = screenBounds.union(EntityGeometryKit.computeScreenBounds(
                    equipment.overlay().model(), renderOrient, modelScale, equipment.texture()));
            // Measured through the wings' own texture, like the equipment overlays: the wing box is a
            // 10x20x2 slab whose texture is largely transparent, so its geometric AABB would size the
            // canvas well outside the drawn wing outline. Seated on the body first, so a baby's dropped
            // wings are measured where they draw rather than where they are authored.
            if (wingTexture.isPresent())
                screenBounds = screenBounds.union(EntityGeometryKit.computeScreenBounds(
                    ElytraKit.wingsMesh(options.getAppearance().isBaby(), bodyBoneBounds),
                    renderOrient, modelScale, wingTexture.get()));
            // And the worn-armor shell, for the same reason: it stands clear of the body on every
            // side vanilla inflates it, and a baby's is a hooded shroud around a body a third its
            // bulk. Gated on a piece actually being equipped, so an unarmored render - which is all
            // but fourteen rows of the entity sweep - measures exactly what it measured before.
            Optional<Box> armorBounds = resolved.humanoidArmor().flatMap(shell -> ArmorKit.screenBounds(shell,
                options.getArmor().equipped(), options.getArmor().getItems(),
                renderOrient, modelScale, this.context));
            if (armorBounds.isPresent()) screenBounds = screenBounds.union(armorBounds.get());
            RendererDebug.fitBounds(options.getEntityId().get(), screenBounds);
            CanvasFit fit = computeCanvas(options, screenBounds, lens);
            canvasW = fit.canvasW();
            canvasH = fit.canvasH();
            kitFrame = new RenderFrame(Vector3f.ZERO, 1f, modelScale);
            fitRequest = FitRequest.nativeScale(fit.ndcScale(), screenBounds);
        } else {
            int canvasSize = Math.max(1, options.getOutput().getCanvasSize());
            int padding = Math.max(0, options.getPadding());
            canvasW = canvasSize;
            canvasH = canvasSize;
            // Model-space bounds, combined across the base entity AND every overlay so the shared
            // auto-fit window contains both. Slime's outer shell (8x8x8) extends beyond the inner body
            // (6x6x6); without including the shell in the bounds the auto-fit normalizes to the inner
            // body and the shell renders larger-than-window. Block-overlay rendering applies its own
            // transform chain after entity-fit normalization, so its bounds aren't included here - only
            // model-overlay (cube tree) geometries that share the entity's frame.
            //
            // Built inside this branch because ONLY this branch reads it. The orthographic path above
            // measures an alpha-tight screen silhouette instead, so building this union before the fork
            // was one to five whole chain-transform walks per render, computed and discarded on the
            // default path and on every row of the parity sweep.
            Box modelBounds = EntityGeometryKit.computeBounds(model);
            for (Entity.OverlayLayer overlay : resolved.overlays()) {
                if (overlay.model().getBones().isEmpty()) continue;
                modelBounds = modelBounds.union(EntityGeometryKit.computeBounds(overlay.model()));
            }
            // Fold a selected equipment overlay's mesh into the bounds union so an inflated / protruding
            // equipment mesh (horse/nautilus/wolf armor, the llama carpet's CubeDeformation) can't crop
            // at the canvas edge. Gated on the equipment axis, so the default (unequipped) render is
            // unchanged, matching the EQUIPMENT feature's render gate. Measured geometrically: the
            // perspective / oblique fit this feeds re-measures the real triangle silhouette in the
            // engine, so a tighter pre-normalisation would buy nothing.
            for (EquippedOverlay equipment : equipped)
                modelBounds = modelBounds.union(EntityGeometryKit.computeBounds(equipment.overlay().model()));
            // Fold the elytra wings into the bounds union so the protruding wings can't crop at the
            // canvas edge. Gated on the elytra selection, so the default (no elytra) render is unchanged.
            if (wingTexture.isPresent())
                modelBounds = modelBounds.union(EntityGeometryKit.computeBounds(
                    ElytraKit.wingsMesh(options.getAppearance().isBaby(), bodyBoneBounds)));
            EntityGeometryKit.UnitFit unit = EntityGeometryKit.unitFit(scaleBox(modelBounds, modelScale));
            kitFrame = new RenderFrame(unit.centre(), unit.ndcScale(), modelScale);
            fitRequest = FitRequest.autoFill(Math.max(1e-3f, (canvasSize - 2f * padding) / (float) canvasSize));
        }

        // Per-frame geometry build: the base body plus its model-overlay /
        // block-overlay / armor feature layers, with every entity / overlay / carried-block texture
        // resolved at the frame's tick. Emission order is load-bearing (depth tie-break, translucent
        // sort, emissive depth-skip), so the slot order stays base -> overlays -> collar -> block
        // overlays -> armor; the base body (built imperatively here) is always first and produces the
        // bone bounds the armor layer consumes. Callers splice their own layers via
        // EntityOptions.layerDecorator. All layers are built fit-neutral and fitted together by the
        // single rasterizeFitted call in the callback. A frameCount=1 render bakes once at startTick,
        // byte-identical to the pre-animation path; a sidecar-less entity resolves the same raw strip
        // at every tick. The build MUST stay inside the callback (the fluid invariant) so an opted-in
        // animated texture is not frozen on frame 0; the ModelEngine is rebuilt per frame for
        // thread-safe parallel strip baking. FeatureContext carries the shared geometry-build frame
        // (the render frame, textures, pack context, tick) the static feature constants cannot capture.
        IntFunction<ConcurrentList<VisibleTriangle>> buildAtTick = tick -> {
            PixelBuffer frameTexture = resolveEntityTexture(resolved, options, tick).orElse(texture.get());
            ConcurrentList<VisibleTriangle> triangles = EntityGeometryKit.buildTriangles(model, frameTexture,
                new EntityGeometryKit.EntityBuildParams(
                    kitFrame, PassDeclaration.DEFAULT, resolved.baseTintArgb())).triangles();
            LayerStack<GeometryLayer> stack = new LayerStack<>();
            FeatureContext featureCtx = new FeatureContext(resolved, options, model, frameTexture,
                kitFrame, this.context, tick);
            for (EntityFeature feature : EntityFeature.values())
                feature.contribute(featureCtx, stack);
            Layers.foldInto(stack, options.getLayerDecorator(), triangles);
            // One relight per draw, over the folded stack, the way vanilla binds Lighting.ENTITY_IN_UI
            // once per GUI entity before any layer is submitted - so a wearer, its overlays, its carried
            // block and everything it wears light under one entry. Every producer above emits geometry
            // and no shade, and each stores its normal in the one frame the kit emits in - which is what
            // Turn.MIRROR_Y carries into the frame the two light directions are resolved in.
            return Shading.relightForEntityInUi(triangles, EntityGeometryKit.DEFAULT_ENTITY_LIGHTING, Turn.MIRROR_Y);
        };

        // Build frame 0 once, up front, for the empty-geometry early-out (a bones-but-no-triangles
        // entity renders a transparent canvas, exactly as before). It doubles as the frame-0 geometry
        // the callback reuses, so a static render never rebuilds.
        ConcurrentList<VisibleTriangle> startTriangles = buildAtTick.apply(startTick);
        if (startTriangles.isEmpty())
            return Timeline.still(PixelBuffer.create(canvasW, canvasH));

        boolean enchanted = options.getArmor().hasEnchanted();

        // Rasterize + optional FXAA + supersample-downscale + masked glint via the shared tail. The
        // glint mask is recorded at the raster size and downsampled so the foil is confined to the
        // (glinted) armor rather than the whole entity silhouette.
        //
        // Route through the schedule UNCONDITIONALLY (the FluidRenderer pattern): a frameCount=1 timeline
        // yields the same single static frame but sampled at the timeline's start tick, so bake draws
        // at timeline.tickAt(0) == startTick and the callback's `tick == startTick` reuse fires. A raw
        // Static(0) would instead hardcode tick 0, which - since the canvas/bounds/startTriangles
        // above are built at startTick - would size the canvas for startTick's frame yet DRAW frame 0
        // (a wrong-frame + wasted-rebuild mismatch on an animated texture with a non-zero startTick).
        // At frameCount=1 the timeline is a Static at startTick and the foil takes the scroll
        // direction; at frameCount>1 it bakes the strip and stamps per frame. Default (startTick=0,
        // frameCount=1) is byte-identical.
        //
        // Canvas-sizing tradeoff (opt-in animated textures): the orthographic canvas + NATIVE_SCALE
        // silhouette are measured ONCE from the startTick frame's alpha-tight bounds, but frames render
        // at per-tick textures. A flipbook whose later frames paint opaque texels outside frame 0's
        // silhouette can crop at the fixed canvas edge. Geometry-driven bounds are
        // frame-invariant, so this only bites texture-alpha-driven silhouette growth; vanilla never
        // triggers it (no animated entity texture). Callers needing an uncropped fat-later-frame render
        // can pad via canvasSize/padding or the perspective path (which auto-fills per frame).
        int ssaa = options.getOutput().getSupersample();
        return timeline.bake(
            RasterPass.of(canvasW, canvasH, ssaa, options.getOutput().isAntiAlias(), (target, tick) ->
                    new ModelEngine(this.context, entityCamera, ENTITY_PLACEMENT).rasterizeFitted(
                        tick == startTick ? startTriangles : buildAtTick.apply(tick), target, effective, fitRequest))
                .withMask(enchanted)
                .finishing(GlintKit.Foil.armor(engine.context()::resolveTexture, enchanted)));
    }

    /**
     * Resolves an entity texture ref against the vanilla pack at {@code minecraft:entity/<ref>} at a
     * specific animation tick. Centralises the {@code minecraft:entity/} prefix idiom the base /
     * overlay / collar / equipment / family-member paths all share. A sidecar-less entity texture
     * (every vanilla entity) answers its buffer unchanged, so {@code tick 0} is byte-identical to the
     * raw lookup; a sidecar-carrying texture samples the frame for {@code tick}.
     *
     * @param context the renderer context resolving the texture
     * @param ref the entity texture sub-path (without the {@code minecraft:entity/} prefix or the
     *     {@code .png} suffix)
     * @param tick the current animation tick (free-running, signed)
     * @return the resolved frame, or empty when the pack has no match
     */
    private static @NotNull Optional<PixelBuffer> resolveEntityTextureAtTick(
        @NotNull RendererContext context, @NotNull String ref, int tick) {
        return context.resolveTextureAtTick(ENTITY_TEXTURE_PREFIX + ref, tick);
    }

    /**
     * Resolves the entity texture as the first present source of an ordered precedence: an explicit
     * {@link EntityOptions#getTextureId() texture id on options} (user override, authoritative when
     * present - looked up against the Java atlas via the pack stack) &gt; the {@code <variant>_baby}
     * texture when the resolved definition renders the baby mesh &gt; an
     * {@link AppearanceOptions#getState() state} selection matching one of the definition's
     * {@link Entity.Axes#stateTextures() state textures} (wolf
     * {@code tame}/{@code angry}) &gt; the entity's own
     * {@link Entity#textureRef() texture_ref}. Each model-form ref is resolved against the vanilla
     * pack at {@code minecraft:entity/<ref>} via {@link #resolveEntityTextureAtTick}.
     */
    private @NotNull Optional<PixelBuffer> resolveEntityTexture(
        @NotNull Entity definition,
        @NotNull EntityOptions options,
        int tick
    ) {
        if (options.getTextureId().isPresent())
            return options.getTextureId().flatMap(id -> this.context.resolveTextureAtTick(id, tick));

        AppearanceOptions appearance = options.getAppearance();
        return babyTexture(definition, appearance, tick)
            .or(() -> selectWeatheringTexture(definition, appearance).flatMap(ref -> resolveEntityTextureAtTick(this.context, ref, tick)))
            .or(() -> selectStateTexture(definition, appearance).flatMap(ref -> resolveEntityTextureAtTick(this.context, ref, tick)))
            .or(() -> definition.textureRef().flatMap(ref -> resolveEntityTextureAtTick(this.context, ref, tick)));
    }

    /**
     * Selects the copper golem's weathered body base texture when the resolved definition supports
     * weathering (it carries a {@code texture_by: weathering} eye overlay) and a non-{@link
     * CopperWeathering#UNAFFECTED} state is chosen; empty otherwise (so the caller falls back to the
     * default {@code texture_ref}, which is the {@code UNAFFECTED} texture). Keeps the default
     * (unweathered) render unchanged.
     */
    private @NotNull Optional<String> selectWeatheringTexture(
        @NotNull Entity definition,
        @NotNull AppearanceOptions appearance
    ) {
        if (appearance.getWeathering() == CopperWeathering.UNAFFECTED) return Optional.empty();
        boolean supportsWeathering = definition.overlays().stream()
            .anyMatch(o -> o.textureBy().filter("weathering"::equals).isPresent());
        return supportsWeathering ? Optional.of(appearance.getWeathering().baseTexture()) : Optional.empty();
    }

    /**
     * The baby texture when the resolved definition renders the baby mesh - the baby mesh has its
     * own UV layout, so it binds the matching {@code <variant>_baby} texture carried in
     * {@link Entity.Axes#stateTextures() stateTextures} under {@code "baby"}.
     * Empty when the render is not a baby, the entity has no baby mesh, or no baby texture is
     * present (so the caller falls through to the state / default texture).
     */
    private @NotNull Optional<PixelBuffer> babyTexture(
        @NotNull Entity definition,
        @NotNull AppearanceOptions appearance,
        int tick
    ) {
        if (!appearance.isBaby() || definition.axes().babyModel().isEmpty())
            return Optional.empty();
        return Optional.ofNullable(definition.axes().stateTextures().get("baby")).flatMap(ref -> resolveEntityTextureAtTick(this.context, ref, tick));
    }

    /**
     * Selects the definition's state-specific texture when {@link AppearanceOptions#getState() state}
     * names one it carries; empty otherwise (so the caller falls back to the default
     * {@code texture_ref}). The default {@code wild} state resolves to the same path as
     * {@code texture_ref}, so an unset or {@code wild} state leaves the render unchanged.
     */
    private @NotNull Optional<String> selectStateTexture(
        @NotNull Entity definition,
        @NotNull AppearanceOptions appearance
    ) {
        return appearance.getState().map(definition.axes().stateTextures()::get);
    }

    /**
     * The entity's geometry contributors, each constant packing its target {@link EntitySlot
     * slot}, its self-gating policy, and its geometry contribution in one place - the entity analogue of
     * the self-contained {@link Projection} / engine kits. Declaration order IS emission order: model
     * overlays, then the dyed collar (both in the {@code MODEL_OVERLAY} slot, where the insertion-order
     * tie-break lets the collar band win the coplanar depth tie over the overlays), then block overlays,
     * then worn armor. The base body is built imperatively in {@link #renderEntity} and is always emitted
     * first. Each constant self-gates on the resolved {@link FeatureContext#definition() definition} +
     * the {@link AppearanceOptions}, so growing the appearance is one new constant here - never a new gate
     * in {@link #renderEntity}.
     */
    @RequiredArgsConstructor
    private enum EntityFeature {

        /**
         * Model overlays (spider / enderman eyes, saddles, sheep wool) sharing the entity frame. For a baby
         * the resolved definition carries the baby overlay list instead (adult overlay geometry would render
         * adult-sized around the baby body), so this contributes the baby passes alone - the villager biome
         * robe, the trader llama's baby caparison - and nothing for an entity whose overlays declare no baby
         * form, still without an age gate.
         */
        MODEL_OVERLAYS(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                AppearanceOptions appearance = ctx.options().getAppearance();
                // The entity texture prefix (villager -> "villager", zombie_villager ->
                // "zombie_villager") derived from the definition's own texture ref, prepended to the
                // villager profession-layer overlays' prefix-relative sub-paths (type / profession /
                // profession_level) so one shared Villager.Type / Villager.Profession / Villager.Level enum
                // serves both entities.
                String texturePrefix = texturePrefix(ctx.definition());
                for (Entity.OverlayLayer overlay : ctx.definition().overlays()) {
                    // A tint-gated overlay (sheep wool undercoat) renders only once its tint_by axis
                    // selects a colour differing from its baked tint, which is vanilla's own early
                    // return on the dye its layer compares against. Evaluated through the gate rather
                    // than beside it, so there is one definition of the condition.
                    if (overlay.gate().filter(AppearanceGate.TintedGate.class::isInstance)
                        .filter(gate -> !gate.test(appearance)).isPresent()) continue;
                    int overlayTint = resolveOverlayTint(overlay, appearance);
                    Optional<String> overlayRef = resolveOverlayTextureRef(overlay, appearance, texturePrefix);
                    // A texture_by overlay whose axis resolves to no texture draws nothing - the base /
                    // "none" state (iron golem Crackiness.NONE) - so skip it, keeping the default
                    // (unselected) render unchanged. Overlays with a baked default (tropical fish
                    // pattern's KOB) always resolve, so they are never skipped here.
                    if (overlay.textureBy().isPresent() && overlayRef.isEmpty()) continue;
                    // The mesh this pass draws: its own, unless the villager hat rule suppresses the
                    // head subtree in favour of the profession's own hat.
                    EntityModelData overlayMesh = selectOverlayMesh(ctx, overlay, overlayRef, texturePrefix);
                    stack.append(this.slot, sink -> {
                        if (overlayMesh.getBones().isEmpty()) return;
                        Optional<PixelBuffer> overlayTex = overlayRef.map(s -> resolveEntityTextureAtTick(ctx.context(), s, ctx.tick()))
                            .orElseGet(() -> Optional.of(ctx.baseTexture()));
                        if (overlayTex.isEmpty()) return;
                        // The overlay's declared pipeline state rides onto every emitted triangle via
                        // EntityBuildParams - the additive energy-swirl glow, the warden pulsating-spots
                        // opacity multiplier, and the depth-write / quad-sort pair vanilla declares on
                        // the pass itself; every un-annotated overlay keeps the source-over full-opacity
                        // depth-writing default.
                        sink.addAll(EntityGeometryKit.buildTriangles(overlayMesh, overlayTex.get(),
                            new EntityGeometryKit.EntityBuildParams(ctx.frame(), overlay.pass(), overlayTint)
                        ).triangles());
                    });
                }
            }
        },

        /**
         * The collar (wolf, cat): a body-geometry cutout tinted by the collar colour, drawn on top of
         * the base body when the appearance {@link AppearanceOptions#collarTint() wears one} and the
         * resolved definition carries a collar texture (empty for a baby). A tamed subject wears one
         * whether or not a dye is named, which is what vanilla's renderers express by filling the
         * collar colour for a tamed subject alone. The collar texture is transparent except the neck
         * band, so the tinted band wins the coplanar depth tie (last-drawn LEQUAL) over the body
         * beneath it.
         */
        COLLAR(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                Optional<DyeColor> collar = ctx.options().getAppearance().collarTint();
                Optional<String> collarRef = ctx.definition().layers().collar();
                if (collar.isEmpty() || collarRef.isEmpty()) return;
                EntityModelData model = ctx.model();
                int collarTint = TintAxis.COLLAR.resolve(collar.get());
                String ref = collarRef.get();
                stack.append(this.slot, sink -> {
                    Optional<PixelBuffer> collarTex = resolveEntityTextureAtTick(ctx.context(), ref, ctx.tick());
                    if (collarTex.isEmpty()) return;
                    sink.addAll(EntityGeometryKit.buildTriangles(model, collarTex.get(),
                        new EntityGeometryKit.EntityBuildParams(ctx.frame(), PassDeclaration.DEFAULT, collarTint)).triangles());
                });
            }
        },

        /**
         * The horse marking (white socks / blaze / patches): a same-geometry translucent overlay of the
         * base body, textured by the selected {@link HorseMarking} and drawn over the coat. Gated on the
         * resolved definition supporting markings (the horse) and a non-{@link HorseMarking#NONE}
         * selection, so the default (unmarked) render draws nothing. Reuses the
         * base body model - the baby mesh is baby-aware here, binding the marking's {@code _baby} texture
         * - and, like the collar, wins the coplanar depth tie over the body beneath it (last-drawn LEQUAL).
         */
        MARKINGS(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                if (!ctx.definition().layers().markings()) return;
                AppearanceOptions appearance = ctx.options().getAppearance();
                HorseMarking marking = appearance.getMarkings();
                // The marking texture comes from the HorseMarking enum - horse markings are a fixed
                // vanilla set. NONE has no ref, so it draws nothing.
                Optional<String> markingRef = marking.overlayTexture();
                if (markingRef.isEmpty()) return;
                String ref = appearance.isBaby() ? markingRef.get() + "_baby" : markingRef.get();
                EntityModelData model = ctx.model();
                stack.append(this.slot, sink -> {
                    Optional<PixelBuffer> markingTex = resolveEntityTextureAtTick(ctx.context(), ref, ctx.tick());
                    if (markingTex.isEmpty()) return;
                    sink.addAll(EntityGeometryKit.buildTriangles(model, markingTex.get(),
                        new EntityGeometryKit.EntityBuildParams(
                            ctx.frame(), PassDeclaration.DEFAULT, ColorMath.WHITE)).triangles());
                });
            }
        },

        /**
         * Equipment overlays (saddle / body armor): a saddle or armor mesh with its own baked geometry
         * rendered on the body only when the {@code equipment} axis selects its slot. The axis-selected
         * material (or the layer default - horse leather armor / the saddle - when the slot is selected
         * without one) names an equipment asset, whose layers composite through {@link EquipmentKit} the
         * same way worn humanoid armor does; a material naming no asset of the layer, or an asset whose
         * textures are absent from the pack, draws nothing (no fallback). The {@link TintAxis#EQUIPMENT}
         * dye is the wearer's, tinting whichever of the asset's layers declare themselves dyeable - the
         * wolf's armadillo-scute overlay draws only when it is selected, the horse's leather base takes
         * its own undyed brown when it is not. The resolved definition carries no equipment for a baby,
         * so this contributes nothing then without an age gate.
         */
        EQUIPMENT(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                AppearanceOptions appearance = ctx.options().getAppearance();
                Optional<Integer> dye = appearance.tint(TintAxis.EQUIPMENT).map(DyeColor::argb);
                for (Entity.EquipmentOverlay equipment : ctx.definition().layers().equipment()) {
                    Optional<ResourceId> assetId = appearance.equipmentMaterial(equipment.slot())
                        .flatMap(equipment::assetFor);
                    if (assetId.isEmpty()) continue;
                    stack.append(this.slot, sink -> {
                        if (equipment.model().getBones().isEmpty()) return;
                        Optional<PixelBuffer> equipmentTex = EquipmentKit.composite(
                            ctx.context(), assetId.get(), equipment.layerType(),
                            dye, CitResult.NONE, OptionalInt.of(ctx.tick()));
                        if (equipmentTex.isEmpty()) return;
                        sink.addAll(EntityGeometryKit.buildTriangles(equipment.model(), equipmentTex.get(),
                            new EntityGeometryKit.EntityBuildParams(
                            ctx.frame(), PassDeclaration.DEFAULT, ColorMath.WHITE)).triangles());
                    });
                }
            }
        },

        /**
         * Elytra wings: the two-bone {@code ElytraModel} mesh rendered on the back as a model overlay,
         * gated on the {@code elytra} appearance selection. Resolves to no triangles when the entity
         * wears no elytra or the pack ships no elytra wing texture (no fallback).
         */
        WINGS(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                AppearanceOptions appearance = ctx.options().getAppearance();
                if (!appearance.isElytra()) return;
                Optional<Box> bodyBounds = EntityGeometryKit.computeBoneBounds(ctx.model(), BODY_BONE);
                stack.append(this.slot, sink ->
                    sink.addAll(ElytraKit.buildWings3D(ctx.context(), appearance.isBaby(), bodyBounds,
                        ctx.frame(), Optional.empty(), ctx.tick())));
            }
        },

        /**
         * Block-model overlays (mooshroom mushrooms, copper-golem flower): a block model rendered at a
         * pose-stack-applied position on top of the body; the shared {@code entityFit} is computed once.
         * The resolved definition carries no block overlays for a baby or when the carried option drops
         * them (a sheared snow golem), so this contributes nothing then without an age / carried gate.
         */
        BLOCK_OVERLAYS(EntitySlot.BLOCK_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                if (ctx.definition().blockOverlays().isEmpty()) return;
                EntityModelData model = ctx.model();
                RenderFrame frame = ctx.frame();
                Matrix4f entityFit = EntityGeometryKit.buildEntityFitMatrix(
                    frame.anchor(), frame.ndcScale() * frame.modelScale());
                for (Entity.BlockOverlayLayer blockOverlay : ctx.definition().blockOverlays())
                    stack.append(this.slot, sink ->
                        sink.addAll(buildBlockOverlayTriangles(ctx.context(), blockOverlay, model, entityFit, ctx.tick())));
            }
        },

        /**
         * Worn armor (+ trim), gated on the resolved definition carrying an armor shell so only the
         * entities vanilla arms with a {@code HumanoidArmorLayer} render it. Resolves to no triangles
         * when no pieces are equipped.
         */
        ARMOR(EntitySlot.ARMOR) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                Optional<Shell> armor = ctx.definition().humanoidArmor();
                if (armor.isEmpty()) return;
                EntityOptions options = ctx.options();
                stack.append(this.slot, sink ->
                    sink.addAll(ArmorKit.buildEntityArmor3D(armor.get(), ctx.frame(),
                        options.getArmor().equipped(), options.getArmor().getItems(), ctx.context())));
            }
        };

        /** The layer-stack slot this feature appends its geometry to. */
        final @NotNull EntitySlot slot;

        /**
         * Contributes this feature's geometry layers to the stack, self-gating on the resolved
         * definition + appearance; a feature that does not apply appends nothing.
         *
         * @param ctx the resolved render context
         * @param stack the layer stack to append to
         */
        abstract void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack);

    }

    /**
     * The per-render inputs an {@link EntityFeature} needs, bundling the feature-dispatch data with the
     * shared geometry-build frame the layers rasterize in: the age / carried-resolved
     * {@link Entity definition}, the {@link EntityOptions} (appearance +
     * armor pieces), and the primary {@link EntityModelData model} (adult or baby), plus the resolved
     * base texture, the {@link RenderFrame} the body was built through, and the
     * {@link RendererContext}. The scene-frame fields travel here because the static
     * {@link EntityFeature} constants cannot capture them from the renderer instance.
     *
     * @param definition the age / carried-resolved definition the features read
     * @param options the render options (appearance + armor pieces)
     * @param model the primary mesh being rendered (adult or baby)
     * @param baseTexture the resolved base entity texture the layers sample from
     * @param frame the render frame the base body was built through, which every feature building in the
     *     body's own frame passes straight on
     * @param context the renderer context for overlay-texture and block lookups
     * @param tick the animation tick every overlay / carried-block texture is sampled at
     */
    private record FeatureContext(
        @NotNull Entity definition,
        @NotNull EntityOptions options,
        @NotNull EntityModelData model,
        @NotNull PixelBuffer baseTexture,
        @NotNull RenderFrame frame,
        @NotNull RendererContext context,
        int tick
    ) { }

    /**
     * The effective texture ref for a model overlay: the {@code texture_by} axis selection when the
     * overlay is axis-driven and the appearance supplies it, else the overlay's baked
     * {@link Entity.OverlayLayer#textureRef() default texture} (empty = reuse the base
     * entity texture). Axes: {@code pattern} (tropical fish, baked default {@code KOB}),
     * {@code crackiness} (iron golem, empty at {@code NONE} so the overlay is skipped),
     * {@code weathering} (copper-golem eyes, always resolves to the state's eye texture), and the
     * villager profession-layer trio {@code type} / {@code profession} / {@code profession_level}
     * (prefix-relative sub-paths the {@code texturePrefix} qualifies; {@code profession} resolves
     * empty at its {@code NONE} default so the overlay is skipped, and {@code profession_level}
     * resolves empty only for a profession that draws no badge - vanilla has no badge-less job
     * villager, so an unnamed tier resolves to the first rather than to nothing).
     * The {@code type} axis resolves its biome under the pass' own robe directory, mirroring the layer's
     * {@code isBaby ? "baby" : "type"} token swap.
     * The default keeps an unselected overlay unchanged; a selection swaps in that axis' texture.
     *
     * @param overlay the overlay layer to resolve a texture ref for
     * @param appearance the axis selections to resolve against
     * @param texturePrefix the entity texture prefix ({@code villager} / {@code zombie_villager})
     *     prepended to the villager profession-layer axes' prefix-relative sub-paths
     * @return the effective texture ref, or empty when the overlay's axis resolves to nothing
     */
    static @NotNull Optional<String> resolveOverlayTextureRef(@NotNull Entity.OverlayLayer overlay, @NotNull AppearanceOptions appearance, @NotNull String texturePrefix) {
        if (overlay.textureBy().filter("pattern"::equals).isPresent())
            return appearance.getPattern().map(TropicalFishPattern::overlayTexture).or(overlay::textureRef);
        if (overlay.textureBy().filter("crackiness"::equals).isPresent())
            return appearance.getCrackiness().overlayTexture().or(overlay::textureRef);
        if (overlay.textureBy().filter("weathering"::equals).isPresent())
            return Optional.of(appearance.getWeathering().eyeTexture());
        if (overlay.textureBy().filter("type"::equals).isPresent()) {
            Villager.Type type = appearance.getVillagerType();
            return Optional.of(texturePrefix + "/" + (drawsBabyRobe(overlay) ? type.babyOverlaySubPath() : type.overlaySubPath()));
        }
        if (overlay.textureBy().filter("profession"::equals).isPresent())
            return professionTextureRef(appearance, texturePrefix);
        if (overlay.textureBy().filter("profession_level"::equals).isPresent())
            return appearance.getVillagerProfession().drawsBadge()
                ? Optional.of(texturePrefix + "/"
                    + appearance.getVillagerLevel().orElseGet(Villager.Level::minimum).overlaySubPath())
                : Optional.empty();
        return overlay.textureRef();
    }

    /**
     * Whether a {@code type} pass draws the baby robe directory rather than the adult {@code type/} one,
     * read off the pass' OWN baked texture ref - the baby overlay list bakes {@code <prefix>/baby/<biome>}
     * and the adult one {@code <prefix>/type/<biome>}. Keyed on the pass rather than on the appearance's
     * age so the directory swap and the baby-mesh swap can never disagree: the baby robe's UV layout
     * belongs to the baby mesh, so binding it over the adult mesh would garble its texels. A pass whose
     * baby form probed no texture of its own inherits the adult ref and so keeps the adult directory,
     * which is what the jar actually ships.
     *
     * @param overlay the type pass to read the robe directory off
     * @return {@code true} when the pass bakes the baby robe directory
     */
    private static boolean drawsBabyRobe(@NotNull Entity.OverlayLayer overlay) {
        return overlay.textureRef().filter(ref -> ref.contains(BABY_ROBE_SEGMENT)).isPresent();
    }

    /**
     * The mesh a model overlay draws with: its own mesh, unless the overlay declares an alternate
     * suppressed-pass mesh and the villager hat rule selects it. The hat flags come from the
     * {@code villager} sidecar of the pass' {@link #typeHatTextureRef type ref} (the robe texture) and of
     * the selected profession texture, so a resource pack that changes either sidecar changes the
     * decision. An overlay with no alternate, and every context whose texture lookup yields no sidecar,
     * keep the overlay's own mesh.
     *
     * @param ctx the feature context supplying the appearance and the sidecar lookup
     * @param overlay the overlay layer to pick a mesh for
     * @param overlayRef the overlay's already-resolved texture ref
     * @param texturePrefix the entity texture prefix the profession sub-path is qualified with
     * @return the mesh to build triangles from
     */
    private static @NotNull EntityModelData selectOverlayMesh(
        @NotNull FeatureContext ctx,
        @NotNull Entity.OverlayLayer overlay,
        @NotNull Optional<String> overlayRef,
        @NotNull String texturePrefix
    ) {
        if (overlay.noHatModel().isEmpty()) return overlay.model();
        AppearanceOptions appearance = ctx.options().getAppearance();
        MCMeta.Villager.Hat typeHat = villagerHat(ctx.context(),
            typeHatTextureRef(overlay, appearance, texturePrefix, overlayRef));
        MCMeta.Villager.Hat professionHat = villagerHat(ctx.context(),
            professionTextureRef(appearance, texturePrefix));
        return useFullModel(professionHat, typeHat) ? overlay.model() : overlay.noHatModel().get();
    }

    /**
     * The ref whose {@code villager} sidecar supplies the type hat flag: for a {@code type}-axis pass the
     * ADULT {@code <prefix>/type/<biome>} robe ref, whatever the age, else the pass' own resolved ref.
     * Vanilla reads the type hat off a hardcoded {@code "type"} directory token before it ever tests the
     * age, and only the drawn TEXTURE swaps to {@code baby/} - and the {@code baby/} directory ships no
     * sidecars at all, so sourcing the flag from the baby ref would silently read {@code NONE} and stop
     * the desert / snow full-hat suppression applying to a baby. For an adult {@code type} pass this
     * recomputes the ref the pass already holds, so the decision is unchanged.
     *
     * @param overlay the overlay layer whose hat flag is being resolved
     * @param appearance the axis selections to resolve against
     * @param texturePrefix the entity texture prefix the type sub-path is qualified with
     * @param overlayRef the overlay's already-resolved texture ref
     * @return the ref to read the type hat flag from
     */
    static @NotNull Optional<String> typeHatTextureRef(
        @NotNull Entity.OverlayLayer overlay,
        @NotNull AppearanceOptions appearance,
        @NotNull String texturePrefix,
        @NotNull Optional<String> overlayRef
    ) {
        if (overlay.textureBy().filter("type"::equals).isEmpty()) return overlayRef;
        return Optional.of(texturePrefix + "/" + appearance.getVillagerType().overlaySubPath());
    }

    /**
     * Whether a hat-bearing pass draws its full mesh rather than the head-stripped alternate: a hatless
     * profession never suppresses, and a partial-hat profession suppresses only over a full-hat type. A
     * full-hat profession always suppresses, so its own hat is the only one drawn. Package-private so the
     * truth table can be pinned directly.
     *
     * @param professionHat the hat flag of the selected profession texture
     * @param typeHat the hat flag of the selected type texture
     * @return {@code true} when the full mesh is drawn
     */
    static boolean useFullModel(@NotNull MCMeta.Villager.Hat professionHat, @NotNull MCMeta.Villager.Hat typeHat) {
        return professionHat == MCMeta.Villager.Hat.NONE || (professionHat == MCMeta.Villager.Hat.PARTIAL && typeHat != MCMeta.Villager.Hat.FULL);
    }

    /**
     * The villager hat flag an entity texture ref declares: the {@code villager} section of the sidecar
     * shipped beside {@code minecraft:entity/<ref>}, so a resource pack editing that sidecar moves the
     * mesh select. An axis that selected no ref, a texture shipping no sidecar, and a sidecar carrying
     * no {@code villager} section all read as {@link MCMeta.Villager.Hat#NONE} - vanilla's own default
     * for an absent sidecar. Package-private so the qualification and that default can be pinned.
     *
     * @param context the renderer context the sidecar is read through
     * @param ref the entity texture sub-path (no {@code minecraft:entity/} prefix, no {@code .png}
     *     suffix), or empty when the axis selected no texture
     * @return the declared hat flag, or {@link MCMeta.Villager.Hat#NONE}
     */
    static @NotNull MCMeta.Villager.Hat villagerHat(@NotNull RendererContext context, @NotNull Optional<String> ref) {
        return ref.flatMap(sub -> context.findMeta(ENTITY_TEXTURE_PREFIX + sub))
            .flatMap(MCMeta::villager)
            .map(MCMeta.Villager::hat)
            .orElse(MCMeta.Villager.Hat.NONE);
    }

    /**
     * The profession pass' prefix-qualified texture ref, empty at the {@code NONE} profession.
     *
     * @param appearance the axis selections to resolve against
     * @param texturePrefix the entity texture prefix the profession sub-path is qualified with
     * @return the profession texture ref, or empty when no profession is selected
     */
    private static @NotNull Optional<String> professionTextureRef(@NotNull AppearanceOptions appearance, @NotNull String texturePrefix) {
        return appearance.getVillagerProfession().overlaySubPath().map(sub -> texturePrefix + "/" + sub);
    }

    /**
     * The entity texture prefix (the first path segment of the definition's {@code texture_ref}, e.g.
     * {@code villager/villager} -&gt; {@code villager}) prepended to the villager profession-layer
     * overlays' prefix-relative sub-paths. Empty when the definition carries no texture ref.
     *
     * @param definition the resolved entity definition
     * @return the texture prefix, or the empty string when no texture ref is present
     */
    private static @NotNull String texturePrefix(@NotNull Entity definition) {
        return definition.textureRef().map(ref -> {
            int slash = ref.indexOf('/');
            return slash < 0 ? ref : ref.substring(0, slash);
        }).orElse("");
    }

    /**
     * The effective multiplicative tint for a model overlay: the {@code tint_by} axis colour when the
     * overlay is dye-driven ({@code wool_color} sheep wool, {@code pattern_color} tropical fish) and
     * the appearance supplies that {@link TintAxis axis}' dye, else the overlay's baked
     * {@link Entity.OverlayLayer#tintArgb() default tint}. The default keeps an unselected overlay
     * unchanged; a selected dye multiplies the overlay by whatever colour that axis draws the dye as
     * ({@link TintAxis#resolve}), mirroring vanilla's {@code coloredCutoutModelRender} colour arg.
     */
    private static int resolveOverlayTint(@NotNull Entity.OverlayLayer overlay, @NotNull AppearanceOptions appearance) {
        return overlay.tintBy()
            .flatMap(TintAxis::ofToken)
            .flatMap(axis -> appearance.tint(axis).map(axis::resolve))
            .orElse(overlay.tintArgb());
    }

    /**
     * The variant vanilla draws for a block an entity carries, but only where that draw is a choice:
     * the block's default state must have authored an array, and the drawn entry must resolve to
     * element geometry. Empty otherwise, which leaves the caller on the block's own model exactly as
     * before - the case for every block whose default state authors a single variant, and so for every
     * block-overlay subject in the corpus but the enderman's grass_block.
     * <p>
     * A carried block is always drawn at its default state, because a {@link Entity.BlockOverlayLayer}
     * names a block id and carries no state of its own; vanilla's own carried-block references are set
     * from {@code defaultBlockState()} too. The lookup is by the joined default-state key, falling back
     * to the unconditional {@code ""} key a property-less block authors.
     *
     * @param block the carried block
     * @return the variant to draw, empty when the default state authors no array
     */
    private static @NotNull Optional<Block.Variant> carriedVariant(@NotNull Block block) {
        Block.Variant authored = block.variants().get(block.defaultStateKey());
        if (authored == null) authored = block.variants().get("");
        if (authored == null) return Optional.empty();

        return authored.noPosition().filter(variant -> variant.geometry() instanceof Block.ElementGeometry);
    }

    /**
     * Builds the rasterizer-ready triangles for one {@link Entity.BlockOverlayLayer}.
     * Composes the overlay's transform chain (in vanilla block units) with the optional bone
     * anchor (whose pivot+rotation comes from the entity geometry, divided by 16 to convert from
     * pixel-units to block-units), then converts back to entity pixel-units (x16) and applies
     * the entity-fit normalization so the block sits in the same auto-fit window as the entity
     * body. Missing block / texture refs return an empty list rather than failing the render.
     *
     * <p>Static so the {@link EntityFeature#BLOCK_OVERLAYS} constant can call it; both callers pass the
     * same {@link RendererContext} the method previously read from {@code this.context} - the render
     * path via {@link FeatureContext#context()} (which is this renderer's {@code context}) and the
     * orthographic bounds pre-pass ({@link #computeUnionScreenBounds}) directly.
     *
     * @param context the renderer context for block + face-texture lookups
     * @param overlay the block-overlay layer to build
     * @param model the entity mesh supplying the attach-bone anchor chain
     * @param entityFit the entity-fit normalization matrix
     * @param tick the animation tick the carried block's face textures are sampled at (a carried
     *     animated block - e.g. magma - shows frame 0 when static, or its flipbook frame when animated)
     * @return the rasterizer-ready triangles, or an empty list when the block or its textures are missing
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildBlockOverlayTriangles(
        @NotNull RendererContext context,
        @NotNull Entity.BlockOverlayLayer overlay,
        @NotNull EntityModelData model,
        @NotNull Matrix4f entityFit,
        int tick
    ) {
        Optional<Block> block = context.findBlock(overlay.blockId());
        if (block.isEmpty()) return Concurrent.newList();

        // A carried block is an IN-WORLD block, and vanilla reaches it through its BLOCKSTATE:
        // CarriedBlockLayer hands the resolver a BlockState, which takes BlockModelSet.get(state) ->
        // BlockStateModelSet.get(state) and draws the blockstate model, variant rotation baked in.
        // Where that state's key authored an array vanilla draws one entry of it, and having no world
        // position to seed the draw with it uses a constant, which the index build already resolved.
        // This is the MIRROR IMAGE of the inventory-icon rule, not the same decision - an icon is
        // reached through the item model and so takes neither the draw nor the variant rotation.
        // Empty for every block whose default state authors a single variant, which is all but 34 of
        // the 971 and every block any entity currently carries bar grass_block.
        Optional<Block.Variant> drawn = carriedVariant(block.get());
        ModelData blockModel = drawn.isPresent() && drawn.get().geometry() instanceof Block.ElementGeometry element
            ? element.model()
            : block.get().model();

        // Pre-load each face's texture by dereferencing #variable bindings against the model's
        // texture map, exactly mirroring {@code BlockRenderer.Isometric3D.buildFromBlockElements}.
        // Faces whose ref still resolves to a {@code #} after dereference (broken bindings) skip
        // texture loading; the kit treats them as no-texture faces. Sampled at the frame's tick so a
        // carried animated block matches the block-icon path (which also flattens to frame 0 by default).
        ConcurrentMap<String, PixelBuffer> faceTextures = blockModel.loadElementFaceTextures(
            id -> context.resolveTextureAtTick(id, tick));
        if (faceTextures.isEmpty()) return Concurrent.newList();

        // Apply the block's tint to its tint-indexed faces, exactly as the block icon does - a
        // carried grass block's top face (tintindex 0) samples the grass colormap green, while its
        // untinted dirt sides stay white. A held block reaches vanilla's tint through
        // BlockTintSource.color(state) rather than colorInWorld - the submit carries no level and no
        // position - so the biome the entity stands in never reaches it and the no-world-context
        // point applies; untinted (tintindex -1) faces keep white.
        int blockTint = BlockRenderer.resolveBlockTint(context, block.get(), Biome.INVENTORY_DEFAULT);
        var forceRefs = blockModel.resolveForceTranslucentRefs();
        ConcurrentList<VisibleTriangle> blockTris = BlockGeometryKit.buildFromElements(
            blockModel.getElements(), faceTextures, blockTint, ColorMath.WHITE, forceRefs);
        if (blockTris.isEmpty()) return Concurrent.newList();

        // Compose the per-overlay transform matrix in vanilla block units. PoseStack ops apply
        // in bytecode order to the LOCAL frame: under the column-vector convention each new op
        // post-multiplies, matching vanilla's PoseStack `pose = pose * newOp`. Final composite
        // applies the most-recently-appended op first to the cube-local vertex. The bone anchor is
        // applied separately in pixel space (see finalMatrix) so it composes the bone's FULL
        // ancestor chain, not just the attached bone's own local pivot / rotation.
        Matrix4f blockUnitChain = Matrix4f.IDENTITY;
        for (Entity.TransformOp op : overlay.transforms())
            blockUnitChain = op.appendTo(blockUnitChain);

        // Vanilla expects block-model vertices in {@code [0, 1]} (corner-at-origin) since the
        // last pose op {@code translate(-0.5, -0.5, -0.5)} re-centers them at origin before the
        // submit. {@link BlockGeometryKit#buildFromElements} pre-centers the cube to
        // {@code [-0.5, 0.5]} for inventory/atlas use, so add 0.5 on each axis to recover the
        // corner-at-origin convention before the chain applies. Appended last so that, in
        // column-vector composition, this op is rightmost and applies first to the input vertex.
        blockUnitChain = blockUnitChain.translate(0.5f, 0.5f, 0.5f);

        // The drawn variant's own rotation, appended AFTER that translate so it is rightmost and
        // therefore applies first, to the still-origin-centred cube - which makes it a rotation about
        // the cube's own centre, where vanilla bakes it ({@code FaceBakery.rotateVertexBy} about
        // {@code BLOCK_MIDDLE} (0.5, 0.5, 0.5)). Both angles are negated for the same reason
        // {@code BlockRenderer.buildVariantRotation} negates them: blockstate rotation is specified in
        // the opposite sense from this codebase's right-handed matrices. Built with the fluent rotate
        // path (post-multiply, bit-identical to vanilla's {@code PoseStack.mulPose}) applying X then Y,
        // matching that method's composite. No uvlock counter-rotation is applied because no shipped
        // array carries {@code uvlock}; one that did would need the kit's variantRotationX/Y pair, the
        // way the block path passes it.
        if (drawn.isPresent() && drawn.get().hasRotation()) {
            Block.Variant variant = drawn.get();
            if (variant.x() != 0) blockUnitChain = blockUnitChain.rotateX((float) Math.toRadians(-variant.x()));
            if (variant.y() != 0) blockUnitChain = blockUnitChain.rotateY((float) Math.toRadians(-variant.y()));
        }

        // Bone anchor: the attached bone's FULL ancestor chain in entity pixel-units - the same
        // {@code translateAndRotate} composition the kit applies at render, so an attach bone with
        // rotated / offset ancestors anchors correctly (not just its own local pivot). Identity
        // when no bone is attached. For a bone parented directly to the identity mesh root this
        // reduces to {@code T(pivot) * R}, matching the previous single-bone anchor byte-for-byte.
        Matrix4f boneAnchor = overlay.attachedBone() != null
            ? EntityGeometryKit.resolveBoneAnchorMatrix(model, overlay.attachedBone())
            : Matrix4f.IDENTITY;

        // Place the block-unit chain at the bone anchor, converting block-unit positions to entity
        // pixel-units (x16), then run the entity-fit normalization to land in the rasterizer's
        // working frame. Column-vector chain reads right-to-left: blockUnitChain first, then
        // blockToPixel, then the bone anchor, then entityFit.
        Matrix4f finalMatrix = entityFit.multiply(boneAnchor).scale(16f, 16f, 16f).multiply(blockUnitChain);

        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle tri : blockTris) {
            Vector3f transformedNormal = tri.normal().transformNormal(finalMatrix).normalize();
            // The transformed normal is stored rather than shaded against here: a carried block is part
            // of the entity draw, and vanilla submits these mushroom / flower models through the entity
            // render type, which dots the post-pose-stack normal against the ENTITY_IN_UI lights per
            // pixel - continuous, not the block kit's cardinal buckets (1.0/0.8/0.6/0.5). Sampling
            // mooshroom mushroom red showed our 0.67-0.90 block-cardinal range against vanilla's
            // 0.45-0.71 Lambertian range.
            //
            // The pass that lights the folded stack reads this stored normal through Turn.MIRROR_Y,
            // which is what lands an axis-aligned face in the right light hemisphere - without that flip
            // the snow-golem carved_pumpkin top sits at the 0.4 ambient floor instead of ~1.0.
            // Mushroom-cross planes are unaffected either way: their normals are horizontal (y ~= 0), so
            // the flip is a no-op on them.
            //
            // No signed-byte SNORM round trip reaches this geometry, unlike both GUI relights, and that
            // is a measured refusal rather than an oversight. Adding one is the identity on a cardinal
            // normal, so it moves nothing on the snow golem's carved_pumpkin, and nothing on the iron
            // golem's poppy either - all four of that model's cross-plane normals saturate at the 0.4
            // ambient floor or the 1.0 ceiling, so the quantization never escapes a clamp. It reaches
            // only the two subjects whose normals sit unsaturated, and it pulls them apart: the
            // mooshroom's cross planes improve on the metric (brown 0.164 -> 0.133 mean delta, red
            // 0.211 -> 0.199) while the enderman's rotated grass_block cube degrades (0.042 -> 0.060).
            //
            // Declined on the renders rather than on the sum, which is net -0.025 and would have
            // argued for it. Neither mooshroom looks any different with it, so the metric moves where
            // the eye cannot follow; and what the cube loses is concentrated on its camera-facing
            // side, which is the half a viewer actually reads. A quantization vanilla either does or
            // does not apply cannot be right for the planes and wrong for the cube, so the residual
            // both sides share is a second difference nobody has named yet.
            //
            // It is not the carried block's pose, which was the first guess and is wrong.
            // CarriedBlockLayer#submit works in the entity root frame: it never calls
            // ModelPart.translateAndRotate, so the block hangs off no bone and does not follow the
            // arms. EntityBlockOverlayResolver latches attached_bone on exactly that call, which is
            // why the shipped table gives the iron golem's flower right_arm and the snow golem's
            // pumpkin head, and gives the enderman's block nothing. Vanilla's six-op chain matches
            // the table one-for-one, both negatives of scale(-0.5, -0.5, 0.5) included, and reads no
            // animation state at all. EndermanModel#setupAnim does pose the arms while a block is
            // carried - xRot -0.5 on both, zRot +-0.05 - but the block is independent of them, and
            // the harness no-ops setupAnim, so that pose is absent from the reference as well and is
            // symmetric across the gate rather than a difference it could explain.
            //
            // Force back-face culling, matching vanilla's block render types (all bind GL culling)
            // exactly as Shading.relightForItems3d does for plain block models. The
            // {@code red_mushroom} cross model emits its two zero-thickness planes as paired
            // north+south / west+east quads with opposite winding so vanilla's cull keeps exactly the
            // camera-facing one. {@link BlockGeometryKit} marks those quads two-sided
            // ({@code cullBackFaces=false}); carrying that flag through here drew BOTH coincident
            // faces, and once the global depth tie-break became GL_LEQUAL (last-drawn-wins) the two
            // faces - sampling horizontally-mirrored UVs - won per-pixel by sub-ULP depth noise,
            // producing the mushroom-cap speckle / apparent UV flip. Culling drops the away-facing
            // half so only the correctly-oriented face survives, no depth fight.
            //
            // These traits are read for lighting as well as for coverage, and the two are one decision
            // rather than two that happen to agree. The pass that lights the folded stack takes its
            // per-face orientation from {@code cullBackFaces} and its full-bright arm from
            // {@code directionalLight}, so the {@code true} pair below is what puts a cross plane on the
            // Lambertian at all: the block kit hands one {@code cullBackFaces=false} (zero thickness)
            // and {@code directionalLight=false} ({@code "shade": false}), and either of those carried
            // through lights the mooshroom's and iron golem's planes by the wrong rule - the second one
            // full-bright. Relax either literal for the coverage reason above and the lighting moves
            // with it, at a distance from the pass that reads it.
            out.add(new VisibleTriangle(
                tri.position0().transform(finalMatrix),
                tri.position1().transform(finalMatrix),
                tri.position2().transform(finalMatrix),
                tri.uv0(), tri.uv1(), tri.uv2(),
                tri.texture(), tri.tintArgb(),
                transformedNormal,
                Shading.UNLIT, new SurfaceTraits(true, false, false, true,
                    PassDeclaration.DEFAULT.withEmissive(tri.traits().pass().emissive()))
            ));
        }
        return out;
    }

    /**
     * Selects whether canvas sizing + silhouette centring measure this entity alone (base
     * model + non-{@code skipBounds} overlays unioned together) or also union across every
     * group member from the definition's {@link Entity#members()}. {@link EntityOptions.FitMode}
     * picks which source via {@link #boundsScopeFor}; both modes share the same per-entity
     * + overlay primitives so the only difference is whether the group loop runs.
     */
    private enum BoundsScope { ENTITY_UNION, GROUP_UNION }

    /**
     * Maps a public {@link EntityOptions.FitMode} to the internal {@link BoundsScope} the
     * canvas / centring math should measure against. {@code OUTPUT_SIZE} and
     * {@code UNION_BOUNDS} measure this entity only; {@code GROUP_BOUNDS} additionally
     * unions every group member so camel + camel_husk share the same canvas.
     */
    private static @NotNull BoundsScope boundsScopeFor(@NotNull EntityOptions.FitMode mode) {
        return mode == EntityOptions.FitMode.GROUP_BOUNDS ? BoundsScope.GROUP_UNION : BoundsScope.ENTITY_UNION;
    }

    /**
     * Computes the screen-space bounds for the active {@link BoundsScope}. The two existing
     * primitives ({@link #computeUnionScreenBounds} for one entity, {@link
     * #computeGroupUnionScreenBounds} for the whole group) stay unchanged; this method is
     * the single dispatch point so canvas-sizing and centring agree on which bounds to use.
     */
    private @NotNull Box computeScreenBoundsFor(
        @NotNull BoundsScope scope,
        @NotNull String entityId,
        @NotNull Entity definition,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer texture,
        int tick
    ) {
        return switch (scope) {
            case ENTITY_UNION -> computeUnionScreenBounds(definition, transform, modelScale, texture, tick,
                boundsBlockOverlays(definition, this.javaEntities.get(entityId)));
            case GROUP_UNION -> computeGroupUnionScreenBounds(entityId, definition, transform, modelScale, texture, tick);
        };
    }

    /**
     * Sizes the output canvas + model-units-to-NDC scale from a pre-measured projected silhouette,
     * dispatched on {@link EntityOptions#getFitMode()}. The caller measures the (alpha-tight, optionally
     * group-unioned) {@code screenBounds} through the render orientation ({@link ModelEngine#orient});
     * this is the pure sizing math the orthographic entity path feeds into a
     * {@link FitRequest#nativeScale(float, Box) NATIVE_SCALE} fit.
     *
     * <p>{@code UNION_BOUNDS} / {@code GROUP_BOUNDS}: take the tight screen-space extent in
     * entity-pixel-units, size the canvas to {@code (extent * pixelsPerBlock / 16)} pixels per axis plus
     * {@code 2 * padding} on each axis, then uniformly shrink so the longer side stays at or below
     * {@link EntityOptions#getMaxCanvasSize() maxCanvasSize}.
     *
     * <p>{@code OUTPUT_SIZE}: canvas is fixed at {@code canvasSize x canvasSize}. Available silhouette
     * area is {@code canvasSize - 2 * padding} on the longer axis; the entity is scaled to fit.
     *
     * <p>Returned {@link CanvasFit#ndcScale} is the inverse of the rasterizer's own projection
     * ({@code screen_px = ndc * min(canvasW, canvasH) * projectionScale}), so applying it as the fit's
     * model-units-to-NDC scale produces the desired pixels-per-block ratio at rasterization.
     *
     * @param options the render options (fit mode, output size, padding, pixels-per-block, cap)
     * @param screenBounds the pre-measured projected silhouette bounds
     * @param lens the projection lens (supplies the projection scale)
     * @return the canvas dimensions + model-units-to-NDC scale
     */
    private @NotNull CanvasFit computeCanvas(
        @NotNull EntityOptions options,
        @NotNull Box screenBounds,
        @NotNull Lens lens
    ) {
        float extentX = Math.max(0f, screenBounds.maxX() - screenBounds.minX());
        float extentY = Math.max(0f, screenBounds.maxY() - screenBounds.minY());
        int padding = Math.max(0, options.getPadding());
        float projectionScale = lens.projectionScale();

        if (options.getFitMode() == EntityOptions.FitMode.OUTPUT_SIZE) {
            int canvasSize = Math.max(1, options.getOutput().getCanvasSize());
            int avail = Math.max(1, canvasSize - 2 * padding);
            float extent = Math.max(Math.max(extentX, extentY), 1e-6f);
            float pxPerEntityUnit = avail / extent;
            float ndcScale = pxPerEntityUnit / (canvasSize * projectionScale);
            return new CanvasFit(canvasSize, canvasSize, ndcScale);
        }

        int maxCanvasSize = Math.max(1, options.getMaxCanvasSize());
        float pxPerEntityUnit = options.getPixelsPerBlock() / 16f;
        int rawW = Math.max(1, (int) Math.ceil(extentX * pxPerEntityUnit)) + 2 * padding;
        int rawH = Math.max(1, (int) Math.ceil(extentY * pxPerEntityUnit)) + 2 * padding;
        int longest = Math.max(rawW, rawH);
        float shrink = longest > maxCanvasSize ? (float) maxCanvasSize / longest : 1f;
        int canvasW = evenWidth(Math.max(1, (int) Math.ceil(rawW * shrink)));
        int canvasH = Math.max(1, (int) Math.ceil(rawH * shrink));
        float effectivePxPerEntityUnit = pxPerEntityUnit * shrink;
        int minDim = Math.min(canvasW, canvasH);
        float ndcScale = effectivePxPerEntityUnit / (minDim * projectionScale);
        return new CanvasFit(canvasW, canvasH, ndcScale);
    }

    /**
     * Rounds a canvas width up to the next even value, so the fit's anchor lands on a pixel boundary
     * rather than on a pixel centre.
     *
     * <p>A left-right symmetric subject's front vertical corner <b>is</b> the anchor the fit centres,
     * so it projects to exactly {@code width / 2} whatever the subject's extent - the content width
     * cancels out entirely. At an odd width that is a half-integer, which is exactly a pixel centre
     * and therefore exactly a sample point, and the screen edge where the corner's two faces meet
     * passes through it; the sample is then decided by which face the fill rule and the texel fetch
     * hand it to rather than by coverage. At an even width it is an integer - a pixel boundary no
     * sample can land on - so the tie never forms.
     *
     * <p>Only the width has such an axis, since a subject is symmetric left to right and not top to
     * bottom, so the height is left alone. The vanilla-reference-harness rounds its own canvas width
     * the same way in {@code EntitySweep}, so the reference and this render stay in lockstep; the
     * bump cannot move an already-even canvas, and it cannot exceed
     * {@link EntityOptions#getMaxCanvasSize() maxCanvasSize}, which is itself even by default.
     *
     * @param width the canvas width in pixels
     * @return the width, rounded up to the next even value
     */
    private static int evenWidth(int width) {
        return width + (width & 1);
    }

    /**
     * Unions the screen-space bounds of the base entity model with each non-empty entity-model
     * overlay. Vanilla's family-fit pre-pass walks every {@code net.minecraft.client.renderer.entity.layers.RenderLayer}'s {@code EntityModel}-typed field
     * through the same pose stack as the primary model and expands the bounds. Mirrors
     * {@code EntityFrameRenderer.walkLayerExtents} in the vanilla-reference-harness.
     * <p>
     * Block-model overlays (mooshroom mushrooms, snow-golem carved_pumpkin, iron/copper-golem
     * flower) ARE included, measured alpha-tight: the overlay extends the canvas exactly as far as
     * its opaque texels reach, not its full authored quad extent. The mushroom-cross block texture
     * is mostly transparent, so a whole-quad AABB would over-size the canvas; walking the opaque
     * sub-rectangle per face keeps it tight to what renders. The vanilla harness measures the same
     * block-model layers in its family-fit pre-pass, so both canvases fit the overlay uncropped.
     * <p>
     * The rows measured are supplied rather than read off {@code definition}, so a variant coat can
     * be measured against the block the family's default coat draws - see
     * {@link #boundsBlockOverlays}.
     *
     * @param definition the definition whose model and model overlays are measured
     * @param transform the render orientation the silhouette is measured through
     * @param modelScale the per-render vertex pre-scale
     * @param texture the base texture the model's silhouette is measured against
     * @param tick the animation tick the block overlays are built at
     * @param blockOverlays the block-overlay rows to measure
     * @return the unioned screen-space bounds
     */
    private @NotNull Box computeUnionScreenBounds(
        @NotNull Entity definition,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer texture,
        int tick,
        @NotNull List<Entity.BlockOverlayLayer> blockOverlays
    ) {
        Box bounds = EntityGeometryKit.computeScreenBounds(definition.model(), transform, modelScale, texture);
        RendererDebug.baseBounds(bounds);
        for (Entity.OverlayLayer overlay : definition.overlays()) {
            if (overlay.model().getBones().isEmpty()) continue;
            // Overlays flagged skipBounds (LlamaDecorLayer-style equipment-driven overlays) still
            // render but don't contribute to bounds, mirroring the vanilla harness's
            // NO_RENDER_LAYER_SUFFIXES treatment of those layer classes.
            if (overlay.skipBounds()) continue;
            Box overlayBounds = EntityGeometryKit.computeScreenBounds(overlay.model(), transform, modelScale, texture);
            RendererDebug.overlayBounds(overlay.textureRef().orElse("<unset>"), overlayBounds);
            bounds = bounds.union(overlayBounds);
        }
        // Block-model overlays: build the same fit-neutral geometry the render produces (entity fit
        // = buildEntityFitMatrix(ZERO, modelScale), so the positions live in the entity-pixel frame
        // the body bounds use), then union its alpha-tight silhouette measured through the render
        // orientation.
        if (!blockOverlays.isEmpty()) {
            Matrix4f fitNeutral = EntityGeometryKit.buildEntityFitMatrix(Vector3f.ZERO, modelScale);
            for (Entity.BlockOverlayLayer blockOverlay : blockOverlays) {
                ConcurrentList<VisibleTriangle> tris = buildBlockOverlayTriangles(this.context, blockOverlay, definition.model(), fitNeutral, tick);
                Box boBounds = EntityGeometryKit.computeBlockOverlayScreenBounds(tris, transform);
                bounds = bounds.union(boBounds);
            }
        }
        return bounds;
    }

    /**
     * Unions screen-space bounds across every group member of {@code entityId}, mirroring the
     * vanilla harness's {@code EntitySweeper.computeFamilyFits} pre-pass so grouped siblings
     * (camel_husk in camel's group, stray in skeleton's group) render into a single canvas sized
     * to the largest member. Without this the group's smaller members canvas-fit to their own
     * (tighter) bound and the group-locked geometry shifts position between members - vanilla's
     * pixel-identical-canvas guarantee requires every group member to share the same canvas
     * dimensions, scale, and anchor.
     * <p>
     * Per-member: load the member's own definition + default texture (NOT the current render's
     * options-override texture), apply the member's {@link Entity#rendererScale rendererScale} model
     * scale, run {@code computeUnionScreenBounds}, union the result. Group members whose
     * texture / definition can't be resolved (missing PNG, unloaded member) are skipped - the
     * union degrades to the available members rather than throwing.
     * <p>
     * Members are read from the definition's own {@link Entity#members()} - the canvas-group
     * membership baked at load from {@code variant_of} / {@code group_of}. A singleton carries an
     * empty member list, so this method collapses to {@link #computeUnionScreenBounds} for
     * non-group-bearing entities.
     */
    private @NotNull Box computeGroupUnionScreenBounds(
        @NotNull String entityId,
        @NotNull Entity definition,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer texture,
        int tick
    ) {
        Entity base = this.javaEntities.get(entityId);
        Box bounds = computeUnionScreenBounds(definition, transform, modelScale, texture, tick,
            boundsBlockOverlays(definition, base));
        // Option-encoded variant coats live on the base definition's axes.variants rather than as
        // separate group-member rows, so union each coat's silhouette here. A no-op while variant is
        // id-encoded (each coat is a member row measured below) or the model has no variant axis.
        bounds = unionVariantSilhouettes(bounds, base, transform, tick);
        List<String> members = definition.members();
        if (members.size() <= 1) return bounds;
        for (String memberId : members) {
            if (memberId.equals(entityId)) continue;
            Entity memberDef = this.javaEntities.get(memberId);
            if (memberDef == null || memberDef.model().getBones().isEmpty()) continue;
            Optional<PixelBuffer> memberTexture = resolveGroupMemberTexture(memberDef);
            if (memberTexture.isEmpty()) continue;
            float memberScale = memberDef.rendererScale();
            Box memberBounds = computeUnionScreenBounds(memberDef, transform, memberScale, memberTexture.get(), tick,
                memberDef.blockOverlays());
            bounds = bounds.union(memberBounds);
            bounds = unionVariantSilhouettes(bounds, memberDef, transform, tick);
        }
        return bounds;
    }

    /**
     * Unions the screen-space silhouettes of a definition's option-encoded variant coats
     * ({@link Entity.Axes#variants()}) into {@code bounds}, each measured at its
     * own coat texture + render scale (mirroring the group-member walk). A no-op when the definition is
     * absent or carries no variant coats (id-encoded / non-variant models).
     * <p>
     * Every coat measures its block overlays as the DEFAULT coat draws them - see
     * {@link #boundsBlockOverlays} - so a family whose coats differ only in which block they carry
     * keeps one canvas.
     */
    private @NotNull Box unionVariantSilhouettes(@NotNull Box bounds, @Nullable Entity definition, @NotNull Matrix4f transform, int tick) {
        if (definition == null) return bounds;
        for (Entity coat : definition.axes().variants().values()) {
            if (coat.model().getBones().isEmpty()) continue;
            Optional<PixelBuffer> coatTexture = resolveGroupMemberTexture(coat);
            if (coatTexture.isEmpty()) continue;
            bounds = bounds.union(computeUnionScreenBounds(coat, transform, coat.rendererScale(),
                coatTexture.get(), tick, boundsBlockOverlays(coat, definition)));
        }
        return bounds;
    }

    /**
     * A definition's block overlays as the canvas pre-pass sees them: every fixed row drawing the
     * block the family's default coat draws rather than the one the selected coat draws.
     *
     * <p>Vanilla sizes an entity's frame from a freshly built render state, whose variant is the
     * enum's default, so the pre-pass resolves the default coat's block model and never the coat
     * being drawn. A mooshroom's canvas is therefore the red mushroom's on both coats, and the brown
     * one - taller by a texel of sprite - simply reaches further up inside it. Sizing per coat
     * instead would give the two coats different canvases where the reference gives them one.
     *
     * <p>Fixed rows correspond one-for-one in order because the appearance drops them all or none;
     * a selectable row is left alone, its block being the caller's held one, which the pre-pass does
     * see.
     *
     * @param definition the definition being measured
     * @param base the family's base definition, whose rows carry the default coat's blocks
     * @return the rows to measure, the argument's own when there is nothing to substitute
     */
    private static @NotNull List<Entity.BlockOverlayLayer> boundsBlockOverlays(
        @NotNull Entity definition, @Nullable Entity base) {
        List<Entity.BlockOverlayLayer> rows = definition.blockOverlays();
        if (base == null || rows.isEmpty()) return rows;
        List<Entity.BlockOverlayLayer> defaults = base.blockOverlays().stream()
            .filter(row -> !row.selectable()).toList();
        List<Entity.BlockOverlayLayer> out = new ArrayList<>(rows.size());
        int fixed = 0;
        for (Entity.BlockOverlayLayer row : rows)
            out.add(row.selectable() || fixed >= defaults.size()
                ? row
                : row.withBlockId(defaults.get(fixed++).blockId()));
        return List.copyOf(out);
    }

    /**
     * Resolves a group-member's default texture for the group-fit bound walk. Unlike
     * {@link #resolveEntityTexture} this ignores {@code options.textureId} (group-fit measures
     * each variant's OWN bound, not the current-render texture override).
     */
    private @NotNull Optional<PixelBuffer> resolveGroupMemberTexture(@NotNull Entity definition) {
        if (definition.textureRef().isEmpty()) return Optional.empty();
        return resolveEntityTextureAtTick(this.context, definition.textureRef().get(), 0);
    }

    /**
     * The equipment overlays that will actually draw, each paired with the texture it draws - mirrors
     * the {@code EQUIPMENT} feature's render gate exactly, so the bounds union folds in the equipment
     * meshes that appear and only those. An overlay is absent when its slot carries no selected
     * material (the default appearance), when its mesh is empty, or when its texture does not resolve:
     * the last is what keeps a material the pack ships no texture for from bounding the canvas with a
     * mesh that renders nothing.
     *
     * @param resolved the appearance-resolved definition carrying the equipment layers
     * @param appearance the render appearance carrying the equipment axis selection and dye
     * @param tick the animation tick to sample each layer texture at
     * @return the drawable overlays, in layer order
     */
    private @NotNull List<EquippedOverlay> resolveEquippedOverlays(
        @NotNull Entity resolved,
        @NotNull AppearanceOptions appearance,
        int tick
    ) {
        List<EquippedOverlay> out = new ArrayList<>(resolved.layers().equipment().size());
        for (Entity.EquipmentOverlay equipment : resolved.layers().equipment()) {
            if (equipment.model().getBones().isEmpty()) continue;
            Optional<ResourceId> assetId = appearance.equipmentMaterial(equipment.slot())
                .flatMap(equipment::assetFor);
            if (assetId.isEmpty()) continue;
            EquipmentKit.composite(this.context, assetId.get(), equipment.layerType(),
                    appearance.tint(TintAxis.EQUIPMENT).map(DyeColor::argb), CitResult.NONE, OptionalInt.of(tick))
                .ifPresent(texture -> out.add(new EquippedOverlay(equipment, texture)));
        }
        return out;
    }

    /**
     * One equipment overlay that will draw, with the composited texture it draws - resolved once so
     * the canvas-bounds walk and the render agree on both membership and silhouette.
     *
     * @param overlay the equipment overlay
     * @param texture the composited texture the overlay draws
     */
    private record EquippedOverlay(
        @NotNull Entity.EquipmentOverlay overlay,
        @NotNull PixelBuffer texture
    ) {}

    /**
     * Canvas size + NDC scale for one render. {@code canvasW × canvasH} are the dimensions of
     * the destination buffer; {@code ndcScale} is the model-units-to-NDC scale the kit applies
     * so the rasterizer projects each entity-pixel-unit to {@code PIXELS_PER_BLOCK/16} canvas
     * pixels.
     */
    private record CanvasFit(int canvasW, int canvasH, float ndcScale) {}

    /**
     * Returns a new {@link Box} with every coordinate multiplied by {@code k}. No-op when {@code k == 1}.
     */
    private static @NotNull Box scaleBox(@NotNull Box bounds, float k) {
        if (k == 1f) return bounds;
        return new Box(
            bounds.minX() * k, bounds.minY() * k, bounds.minZ() * k,
            bounds.maxX() * k, bounds.maxY() * k, bounds.maxZ() * k
        );
    }

}
