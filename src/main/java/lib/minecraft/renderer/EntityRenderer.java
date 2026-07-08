package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.RendererDebug;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.FitRequest;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.camera.Placement;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.Finalize;
import lib.minecraft.renderer.engine.compose.FrameCompositor;
import lib.minecraft.renderer.engine.compose.layer.GeometryLayer;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.kit.ArmorKit;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.engine.kit.EntityGeometryKit;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.option.CopperWeathering;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.HorseMarking;
import lib.minecraft.renderer.option.slot.EntitySlot;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.engine.texture.Biome;
import lib.minecraft.renderer.option.spec.DyeColor;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.option.TintAxis;
import lib.minecraft.renderer.option.TropicalFishPattern;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
@RequiredArgsConstructor
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
    private final @NotNull Map<String, EntityModelLoader.EntityDefinition> javaEntities;

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
            return FrameCompositor.emptyFrame();

        EntityModelLoader.EntityDefinition definition = this.javaEntities.get(options.getEntityId().get());
        if (definition == null)
            return FrameCompositor.emptyFrame();

        // Fold the age / carried policy into a single resolved definition up front, so every
        // downstream site (texture, ortho bounds, geometry contributors) reads it unconditionally
        // with no scattered !baby gates. resolveFor is a no-op for a non-baby, non-carried appearance.
        EntityModelLoader.EntityDefinition resolved = definition.resolveFor(options.getAppearance());
        EntityModelData model = resolved.model();

        Optional<PixelBuffer> texture = resolveEntityTexture(resolved, options);
        if (texture.isEmpty())
            return FrameCompositor.emptyFrame();

        if (model.getBones().isEmpty())
            return FrameCompositor.emptyFrame();

        // Combined bounds across the base entity AND every overlay so the shared auto-fit
        // window contains both. Slime's outer shell (8x8x8) extends beyond the inner body
        // (6x6x6); without including the shell in the bounds the auto-fit normalizes to the
        // inner body and the shell renders larger-than-window. Block-overlay rendering applies
        // its own transform chain after entity-fit normalization, so its bounds aren't included
        // here - only model-overlay (cube tree) geometries that share the entity's frame.
        Box baseBounds = EntityGeometryKit.computeBounds(model);
        for (EntityModelLoader.OverlayLayer overlay : resolved.overlays()) {
            if (overlay.model().getBones().isEmpty()) continue;
            Box overlayBounds = EntityGeometryKit.computeBounds(overlay.model());
            baseBounds = new Box(
                Math.min(baseBounds.minX(), overlayBounds.minX()),
                Math.min(baseBounds.minY(), overlayBounds.minY()),
                Math.min(baseBounds.minZ(), overlayBounds.minZ()),
                Math.max(baseBounds.maxX(), overlayBounds.maxX()),
                Math.max(baseBounds.maxY(), overlayBounds.maxY()),
                Math.max(baseBounds.maxZ(), overlayBounds.maxZ())
            );
        }
        // Fold a selected equipment overlay's mesh into the bounds union so an inflated / protruding
        // equipment mesh (horse/nautilus/wolf armor, the llama carpet's CubeDeformation) can't crop at
        // the canvas edge. Gated on the equipment axis, so the default (unequipped) render is
        // byte-identical (mirrors the EQUIPMENT feature's render gate).
        for (EntityModelLoader.EquipmentOverlay equipment : resolved.equipment()) {
            if (!equipmentSelected(equipment, options.getAppearance())) continue;
            baseBounds = unionBoxes(baseBounds, EntityGeometryKit.computeBounds(equipment.model()));
        }

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
        // via the {@code modelScale} parameter on the new buildTriangles overload.
        float modelScale = definition.rendererScale();
        Box scaledBounds = scaleBox(baseBounds, modelScale);

        // The entity is a normal projection subject: the camera is the caller's projection display pose
        // DIRECTLY (default VANILLA_ISO = the facing-neutral rotationXYZ(30,225,0)), and its model->world
        // facing (humanoid R_Y(180)) + Y-down flip + chirality is the single ENTITY_FACING Placement.
        // render = pose · ENTITY_FACING · model_Ydown lands the entity upright AND facing under ANY
        // projection (exactly like the player's R_Y(180) facing, plus the Y-down flip). For the default,
        // R(30,225,0) · ENTITY_FACING = R(30,45,0) · flip180 reproduces the harness orientation.
        Camera entityCamera = options.getOutput().getProjection().resolve(EulerRotation.NONE, options.getOutput().getFacing());
        ModelEngine engine = new ModelEngine(this.context, entityCamera, ENTITY_PLACEMENT);
        Lens lens = entityCamera.lens();

        // Fit resolution forks on the projection lens family. The kit always emits FIT-NEUTRAL geometry
        // (only the model scale baked in); the engine's rasterizeFitted applies the fit in ONE place, so
        // entity rendering flows through the same auto-fit pipeline the player uses.
        //
        // ORTHOGRAPHIC (VANILLA_ISO + axonometric): size a native pixels-per-block canvas from the
        // entity's alpha-tight (optionally family-unioned) silhouette - measured through the EXACT render
        // orientation ({@code engine.orient}), dispatched on FitMode (OUTPUT_SIZE honours canvasSize +
        // padding; UNION_BOUNDS / FAMILY_BOUNDS auto-size from the entity's own / family-unioned bounds).
        // The engine bakes that explicit scale in 3D and centres the measured silhouette midpoint in
        // screen space (NATIVE_SCALE) - so a non-brick silhouette (cod's z=[-4,11] AABB vs its z=[0,7]
        // cube hug) stays tightly centred, without the old model-space anchor inverse. Per-entity
        // setupRotations shifts (squid, pufferfish) ride on the model geometry.
        //
        // PERSPECTIVE / OBLIQUE: a 3D model-scale fit can't correct strong foreshortening / depth-shear in
        // one pass (scaling the model changes the foreshortening), so unit-normalize the model into a
        // well-behaved range and defer the final screen fill to rasterizeFitted's 2D auto-fit (Fit2D).
        // This is what lets long entities (cod) fit uncropped under PORTRAIT / cavalier / cabinet / military.
        int canvasW;
        int canvasH;
        Vector3f kitAnchor;
        float kitNdcScale;
        final FitRequest fitRequest;
        if (lens.kind() == Lens.Kind.ORTHOGRAPHIC) {
            BoundsScope scope = boundsScopeFor(options.getFitMode());
            Matrix4f renderOrient = engine.orient(effective);
            Box screenBounds = computeScreenBoundsFor(scope, options.getEntityId().get(), resolved,
                renderOrient, modelScale, texture.get());
            // Fold a selected equipment overlay's mesh into the pre-measured silhouette so an inflated /
            // protruding equipment mesh can't crop at the canvas edge under the NATIVE_SCALE fit (which
            // sizes from these bounds, not the rendered triangles). A null texture measures the mesh's
            // geometric AABB - conservative, no equipment-texture resolution. Gated on the equipment
            // axis, so the default (unequipped) canvas stays byte-identical.
            for (EntityModelLoader.EquipmentOverlay equipment : resolved.equipment()) {
                if (!equipmentSelected(equipment, options.getAppearance())) continue;
                screenBounds = unionBoxes(screenBounds,
                    EntityGeometryKit.computeScreenBounds(equipment.model(), renderOrient, modelScale, null));
            }
            RendererDebug.fitBounds(options.getEntityId().get(), screenBounds);
            CanvasFit fit = computeCanvas(options, screenBounds, lens);
            canvasW = fit.canvasW();
            canvasH = fit.canvasH();
            kitAnchor = Vector3f.ZERO;
            kitNdcScale = 1f;
            fitRequest = FitRequest.nativeScale(fit.ndcScale(), screenBounds);
        } else {
            int canvasSize = Math.max(1, options.getOutput().getCanvasSize());
            int padding = Math.max(0, options.getPadding());
            canvasW = canvasSize;
            canvasH = canvasSize;
            EntityGeometryKit.UnitFit unit = EntityGeometryKit.unitFit(scaledBounds);
            kitAnchor = unit.centre();
            kitNdcScale = unit.ndcScale();
            fitRequest = FitRequest.autoFill(Math.max(1e-3f, (canvasSize - 2f * padding) / (float) canvasSize));
        }

        EntityGeometryKit.BuildResult buildResult = EntityGeometryKit.buildTriangles(
            model, texture.get(), kitAnchor, false, kitNdcScale, modelScale, resolved.baseTintArgb());
        if (buildResult.triangles().isEmpty())
            return FrameCompositor.staticFrame(PixelBuffer.create(canvasW, canvasH));

        ConcurrentList<VisibleTriangle> triangles = buildResult.triangles();

        // The base body is built imperatively above and is always the first geometry in the sink (it
        // also produces the bone bounds the armor layer consumes). The remaining sources - model
        // overlays, block overlays, worn armor - are GeometryLayers that append to the SAME triangle
        // list in slot order, then rasterize together in one shared depth pass. Emission order is
        // load-bearing (depth tie-break, translucent sort, emissive depth-skip), so the slot order
        // reproduces the historic base -> overlays -> block-overlays -> armor sequence exactly.
        // Callers can splice their own layers via EntityOptions.layerDecorator. All layers are built
        // fit-neutral and fitted together by the single rasterizeFitted call below.
        // Assemble the appended geometry layers via the feature registry. Each feature self-gates on
        // the resolved definition's data + the appearance and appends to its slot; the registry order
        // fixes emission order (model overlays -> collar -> block overlays -> armor). The base body
        // built above is always first; callers can splice custom layers via layerDecorator. FeatureContext
        // also carries the shared geometry-build frame (anchor, scales, textures, pack context) the static
        // feature constants cannot capture from the renderer instance.
        LayerStack<GeometryLayer> stack = new LayerStack<>();
        FeatureContext featureCtx = new FeatureContext(
            resolved, options, model, buildResult,
            texture.get(), kitAnchor, kitNdcScale, modelScale, engine.textures(), this.context);
        for (EntityFeature feature : EntityFeature.values())
            feature.contribute(featureCtx, stack);

        Layers.foldInto(stack, options.getLayerDecorator(), triangles);

        boolean enchanted = ArmorKit.hasEnchantedArmor(
            options.getArmor().getHelmet(), options.getArmor().getChestplate(),
            options.getArmor().getLeggings(), options.getArmor().getBoots()
        );

        // Rasterize + optional FXAA + supersample-downscale + masked glint via the shared tail.
        // The glint mask is recorded at the raster size and downsampled so the foil is confined to
        // the (glinted) armor rather than the whole entity silhouette.
        int ssaa = Math.max(1, options.getOutput().getSupersample());
        return Finalize.render(
            Finalize.FinalizeSpec.staticFrame(canvasW, canvasH, ssaa, options.getOutput().isAntiAlias())
                .withGlint(Finalize.Glint.armor(engine.textures()::tryResolveTexture, enchanted), enchanted),
            (target, mask, tick) -> engine.rasterizeFitted(triangles, target, effective, fitRequest, mask));
    }

    /**
     * Resolves the entity texture as the first present source of an ordered precedence: an explicit
     * {@link EntityOptions#getTextureId() texture id on options} (user override, authoritative when
     * present - looked up against the Java atlas via the pack stack) &gt; the {@code <variant>_baby}
     * texture when the resolved definition renders the baby mesh &gt; an
     * {@link EntityAppearance#getState() state} selection matching one of the definition's
     * {@link EntityModelLoader.EntityDefinition#stateTextures() state textures} (wolf
     * {@code tame}/{@code angry}) &gt; the entity's own
     * {@link EntityModelLoader.EntityDefinition#textureRef() texture_ref}. Each family-form ref is
     * resolved against the vanilla pack at {@code minecraft:entity/<ref>} via {@link #resolveEntityRef}.
     */
    private @NotNull Optional<PixelBuffer> resolveEntityTexture(
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull EntityOptions options
    ) {
        if (options.getTextureId().isPresent())
            return options.getTextureId().flatMap(this.context::resolveTexture);

        EntityAppearance appearance = options.getAppearance();
        return babyTexture(definition, appearance)
            .or(() -> selectWeatheringTexture(definition, appearance).flatMap(this::resolveEntityRef))
            .or(() -> selectStateTexture(definition, appearance).flatMap(this::resolveEntityRef))
            .or(() -> definition.textureRef().flatMap(this::resolveEntityRef));
    }

    /**
     * Selects the copper golem's weathered body base texture when the resolved definition supports
     * weathering (it carries a {@code texture_by: weathering} eye overlay) and a non-{@link
     * CopperWeathering#UNAFFECTED} state is chosen; empty otherwise (so the caller falls back to the
     * default {@code texture_ref}, which is the {@code UNAFFECTED} texture). Keeps the default
     * (unweathered) render byte-identical.
     */
    private @NotNull Optional<String> selectWeatheringTexture(
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull EntityAppearance appearance
    ) {
        if (appearance.getWeathering() == CopperWeathering.UNAFFECTED) return Optional.empty();
        boolean supportsWeathering = definition.overlays().stream()
            .anyMatch(o -> o.textureBy().filter("weathering"::equals).isPresent());
        return supportsWeathering ? Optional.of(appearance.getWeathering().baseTexture()) : Optional.empty();
    }

    /**
     * Resolves a family-form entity texture ref (a {@code textures/entity/} sub-path) against the
     * vanilla pack at {@code minecraft:entity/<ref>}.
     *
     * @param ref the entity texture sub-path (without {@code minecraft:entity/} or {@code .png})
     * @return the resolved texture, or empty when the pack has no such texture
     */
    private @NotNull Optional<PixelBuffer> resolveEntityRef(@NotNull String ref) {
        return new Textures(this.context).resolveEntityTexture(ref);
    }

    /**
     * The baby texture when the resolved definition renders the baby mesh - the baby mesh has its
     * own UV layout, so it binds the matching {@code <variant>_baby} texture carried in
     * {@link EntityModelLoader.EntityDefinition#stateTextures() stateTextures} under {@code "baby"}.
     * Empty when the render is not a baby, the entity has no baby mesh, or no baby texture is
     * present (so the caller falls through to the state / default texture).
     */
    private @NotNull Optional<PixelBuffer> babyTexture(
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull EntityAppearance appearance
    ) {
        if (!appearance.isBaby() || definition.babyModel().isEmpty())
            return Optional.empty();
        return Optional.ofNullable(definition.stateTextures().get("baby")).flatMap(this::resolveEntityRef);
    }

    /**
     * Selects the definition's state-specific texture when {@link EntityAppearance#getState() state}
     * names one it carries; empty otherwise (so the caller falls back to the default
     * {@code texture_ref}). The default {@code wild} state resolves to the same path as
     * {@code texture_ref}, so an unset or {@code wild} state leaves the render byte-identical.
     */
    private @NotNull Optional<String> selectStateTexture(
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull EntityAppearance appearance
    ) {
        return appearance.getState().map(definition.stateTextures()::get);
    }

    /**
     * The entity's geometry contributors, each constant packing its target {@link EntitySlot
     * slot}, its self-gating policy, and its geometry contribution in one place - the entity analogue of
     * the self-contained {@link Projection} / engine kits. Declaration order IS emission order: model
     * overlays, then the dyed collar (both in the {@code MODEL_OVERLAY} slot, where the insertion-order
     * tie-break lets the collar band win the coplanar depth tie over the overlays), then block overlays,
     * then worn armor. The base body is built imperatively in {@link #renderEntity} and is always emitted
     * first. Each constant self-gates on the resolved {@link FeatureContext#definition() definition} +
     * the {@link EntityAppearance}, so growing the appearance is one new constant here - never a new gate
     * in {@link #renderEntity}.
     */
    @RequiredArgsConstructor
    private enum EntityFeature {

        /**
         * Model overlays (spider / enderman eyes, saddles, sheep wool) sharing the entity frame. The
         * resolved definition carries no overlays for a baby (adult overlay geometry would render
         * adult-sized around the baby body), so this contributes nothing then without an age gate.
         */
        MODEL_OVERLAYS(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                EntityAppearance appearance = ctx.options().getAppearance();
                // The entity texture prefix (villager -> "villager", zombie_villager ->
                // "zombie_villager") derived from the definition's own texture ref, prepended to the
                // villager profession-layer overlays' prefix-relative sub-paths (type / profession /
                // profession_level) so one shared VillagerType / VillagerProfession / VillagerLevel enum
                // serves both entities.
                String texturePrefix = texturePrefix(ctx.definition());
                for (EntityModelLoader.OverlayLayer overlay : ctx.definition().overlays()) {
                    // A requires_tint overlay (sheep wool undercoat) only renders once its tint_by colour
                    // is selected; skip it for the default (untinted) entity so the default is unchanged.
                    if (overlay.requiresTint() && !hasSelectedTint(overlay, appearance)) continue;
                    int overlayTint = resolveOverlayTint(overlay, appearance);
                    Optional<String> overlayRef = resolveOverlayTextureRef(overlay, appearance, texturePrefix);
                    // A texture_by overlay whose axis resolves to no texture draws nothing - the base /
                    // "none" state (iron golem Crackiness.NONE) - so skip it, keeping the default
                    // (unselected) render byte-identical. Overlays with a baked default (tropical fish
                    // pattern's KOB) always resolve, so they are never skipped here.
                    if (overlay.textureBy().isPresent() && overlayRef.isEmpty()) continue;
                    stack.append(this.slot, sink -> {
                        if (overlay.model().getBones().isEmpty()) return;
                        Optional<PixelBuffer> overlayTex = overlayRef.isPresent()
                            ? ctx.textures().resolveEntityTexture(overlayRef.get())
                            : Optional.of(ctx.baseTexture());
                        if (overlayTex.isEmpty()) return;
                        // The overlay's declared blend / alpha (default NORMAL / 1.0) ride onto every
                        // emitted triangle via EntityBuildParams - the additive energy-swirl glow and
                        // the warden pulsating-spots opacity multiplier; every un-annotated overlay
                        // keeps the source-over full-opacity default.
                        sink.addAll(EntityGeometryKit.buildTriangles(overlay.model(), overlayTex.get(),
                            new EntityGeometryKit.EntityBuildParams(ctx.modelAnchor(), overlay.emissive(),
                                ctx.ndcScale(), ctx.modelScale(), overlayTint, overlay.blend(), overlay.alpha())
                        ).triangles());
                    });
                }
            }
        },

        /**
         * The dyed collar (wolf, cat): a body-geometry cutout tinted by the collar colour, drawn on top
         * of the base body when a collar colour is supplied and the resolved definition carries a collar
         * texture (empty for a baby). The collar texture is transparent except the neck band, so the
         * tinted band wins the coplanar depth tie (last-drawn LEQUAL) over the body beneath it.
         */
        COLLAR(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                Optional<DyeColor> collar = ctx.options().getAppearance().tint(TintAxis.COLLAR);
                Optional<String> collarRef = ctx.definition().collarTexture();
                if (collar.isEmpty() || collarRef.isEmpty()) return;
                EntityModelData model = ctx.model();
                int collarTint = collar.get().argb();
                String ref = collarRef.get();
                stack.append(this.slot, sink -> {
                    Optional<PixelBuffer> collarTex = ctx.textures().resolveEntityTexture(ref);
                    if (collarTex.isEmpty()) return;
                    sink.addAll(EntityGeometryKit.buildTriangles(
                        model, collarTex.get(), ctx.modelAnchor(), false,
                        ctx.ndcScale(), ctx.modelScale(), collarTint).triangles());
                });
            }
        },

        /**
         * The horse marking (white socks / blaze / patches): a same-geometry translucent overlay of the
         * base body, textured by the selected {@link HorseMarking} and drawn over the coat. Gated on the
         * resolved definition supporting markings (the horse) and a non-{@link HorseMarking#NONE}
         * selection, so the default (unmarked) render draws nothing and stays byte-identical. Reuses the
         * base body model - the baby mesh is baby-aware here, binding the marking's {@code _baby} texture
         * - and, like the collar, wins the coplanar depth tie over the body beneath it (last-drawn LEQUAL).
         */
        MARKINGS(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                if (!ctx.definition().markings()) return;
                EntityAppearance appearance = ctx.options().getAppearance();
                Optional<String> markingRef = appearance.getMarkings().overlayTexture();
                if (markingRef.isEmpty()) return;
                String ref = appearance.isBaby() ? markingRef.get() + "_baby" : markingRef.get();
                EntityModelData model = ctx.model();
                stack.append(this.slot, sink -> {
                    Optional<PixelBuffer> markingTex = ctx.textures().resolveEntityTexture(ref);
                    if (markingTex.isEmpty()) return;
                    sink.addAll(EntityGeometryKit.buildTriangles(
                        model, markingTex.get(), ctx.modelAnchor(), false,
                        ctx.ndcScale(), ctx.modelScale(), ColorMath.WHITE).triangles());
                });
            }
        },

        /**
         * Equipment overlays (saddle / body armor): a saddle or armor mesh with its own baked geometry
         * rendered on the body only when the {@code equipment} axis selects its slot. The texture is the
         * axis-selected material resolved through the layer's {@code <material>} template (or the layer
         * default - leather armor / the saddle - when the slot is selected without one); a material whose
         * equipment texture is absent from the vanilla pack drops out (no fallback). The resolved
         * definition carries no equipment for a baby, so this contributes nothing then without an age gate.
         */
        EQUIPMENT(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                EntityAppearance appearance = ctx.options().getAppearance();
                for (EntityModelLoader.EquipmentOverlay equipment : ctx.definition().equipment()) {
                    Optional<String> material = appearance.equipmentMaterial(equipment.slot());
                    if (material.isEmpty()) continue;
                    String textureRef = equipment.textureFor(material.get());
                    stack.append(this.slot, sink -> {
                        if (equipment.model().getBones().isEmpty()) return;
                        Optional<PixelBuffer> equipmentTex = ctx.textures().resolveEntityTexture(textureRef);
                        if (equipmentTex.isEmpty()) return;
                        sink.addAll(EntityGeometryKit.buildTriangles(
                            equipment.model(), equipmentTex.get(), ctx.modelAnchor(), false,
                            ctx.ndcScale(), ctx.modelScale(), ColorMath.WHITE).triangles());
                    });
                }
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
                Matrix4f entityFit = EntityGeometryKit.buildEntityFitMatrix(
                    ctx.modelAnchor(), ctx.ndcScale() * ctx.modelScale());
                for (EntityModelLoader.BlockOverlayLayer blockOverlay : ctx.definition().blockOverlays())
                    stack.append(this.slot, sink ->
                        sink.addAll(buildBlockOverlayTriangles(ctx.context(), blockOverlay, model, entityFit)));
            }
        },

        /**
         * Worn armor (+ trim). Always appended; resolves to no triangles when no pieces are equipped.
         */
        ARMOR(EntitySlot.ARMOR) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                EntityOptions options = ctx.options();
                stack.append(this.slot, sink ->
                    sink.addAll(ArmorKit.buildEntityArmor3D(ctx.buildResult().boneBounds(),
                        options.getArmor().getHelmet(), options.getArmor().getChestplate(),
                        options.getArmor().getLeggings(), options.getArmor().getBoots(), ctx.textures())));
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
     * {@link EntityModelLoader.EntityDefinition definition}, the {@link EntityOptions} (appearance +
     * armor pieces), the primary {@link EntityModelData model} (adult or baby), and the base body build
     * result whose bone bounds the armor feature consumes, plus the resolved base texture, model anchor,
     * NDC + model scale, {@link Textures} service, and {@link RendererContext}. The scene-frame fields
     * travel here because the static {@link EntityFeature} constants cannot capture them from the
     * renderer instance.
     *
     * @param definition the age / carried-resolved definition the features read
     * @param options the render options (appearance + armor pieces)
     * @param model the primary mesh being rendered (adult or baby)
     * @param buildResult the base body build result (bone bounds for the armor feature)
     * @param baseTexture the resolved base entity texture the layers sample from
     * @param modelAnchor the model-space point the rasterizer maps to canvas centre
     * @param ndcScale the normalized-device scale from the auto-fit window
     * @param modelScale the per-subject render scale (renderer scale combined with state scale)
     * @param textures the texture-resolution service the layers sample overlay / armor textures through
     * @param context the renderer context for overlay-texture and block lookups
     */
    private record FeatureContext(
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull EntityOptions options,
        @NotNull EntityModelData model,
        @NotNull EntityGeometryKit.BuildResult buildResult,
        @NotNull PixelBuffer baseTexture,
        @NotNull Vector3f modelAnchor,
        float ndcScale,
        float modelScale,
        @NotNull Textures textures,
        @NotNull RendererContext context
    ) { }

    /**
     * The effective texture ref for a model overlay: the {@code texture_by} axis selection when the
     * overlay is axis-driven and the appearance supplies it, else the overlay's baked
     * {@link EntityModelLoader.OverlayLayer#textureRef() default texture} (empty = reuse the base
     * entity texture). Axes: {@code pattern} (tropical fish, baked default {@code KOB}),
     * {@code crackiness} (iron golem, empty at {@code NONE} so the overlay is skipped),
     * {@code weathering} (copper-golem eyes, always resolves to the state's eye texture), and the
     * villager profession-layer trio {@code type} / {@code profession} / {@code profession_level}
     * (prefix-relative sub-paths the {@code texturePrefix} qualifies; {@code profession} and
     * {@code profession_level} resolve empty at their {@code NONE} default so the overlay is skipped).
     * The default keeps an unselected overlay byte-identical; a selection swaps in that axis' texture.
     *
     * @param overlay the overlay layer to resolve a texture ref for
     * @param appearance the axis selections to resolve against
     * @param texturePrefix the entity texture prefix ({@code villager} / {@code zombie_villager})
     *     prepended to the villager profession-layer axes' prefix-relative sub-paths
     * @return the effective texture ref, or empty when the overlay's axis resolves to nothing
     */
    private static @NotNull Optional<String> resolveOverlayTextureRef(@NotNull EntityModelLoader.OverlayLayer overlay, @NotNull EntityAppearance appearance, @NotNull String texturePrefix) {
        if (overlay.textureBy().filter("pattern"::equals).isPresent())
            return appearance.getPattern().map(TropicalFishPattern::overlayTexture).or(overlay::textureRef);
        if (overlay.textureBy().filter("crackiness"::equals).isPresent())
            return appearance.getCrackiness().overlayTexture().or(overlay::textureRef);
        if (overlay.textureBy().filter("weathering"::equals).isPresent())
            return Optional.of(appearance.getWeathering().eyeTexture());
        if (overlay.textureBy().filter("type"::equals).isPresent())
            return Optional.of(texturePrefix + "/" + appearance.getVillagerType().overlaySubPath());
        if (overlay.textureBy().filter("profession"::equals).isPresent())
            return appearance.getVillagerProfession().overlaySubPath().map(sub -> texturePrefix + "/" + sub);
        if (overlay.textureBy().filter("profession_level"::equals).isPresent())
            return appearance.getVillagerProfession().drawsBadge()
                ? appearance.getVillagerLevel().overlaySubPath().map(sub -> texturePrefix + "/" + sub)
                : Optional.empty();
        return overlay.textureRef();
    }

    /**
     * The entity texture prefix (the first path segment of the definition's {@code texture_ref}, e.g.
     * {@code villager/villager} -&gt; {@code villager}) prepended to the villager profession-layer
     * overlays' prefix-relative sub-paths. Empty when the definition carries no texture ref.
     *
     * @param definition the resolved entity definition
     * @return the texture prefix, or the empty string when no texture ref is present
     */
    private static @NotNull String texturePrefix(@NotNull EntityModelLoader.EntityDefinition definition) {
        return definition.textureRef().map(ref -> {
            int slash = ref.indexOf('/');
            return slash < 0 ? ref : ref.substring(0, slash);
        }).orElse("");
    }

    /**
     * The effective multiplicative tint for a model overlay: the {@code tint_by} axis colour when the
     * overlay is dye-driven ({@code wool_color} sheep wool, {@code pattern_color} tropical fish) and
     * the appearance supplies that {@link TintAxis axis}' dye, else the overlay's baked
     * {@link EntityModelLoader.OverlayLayer#tintArgb() default tint}. The default keeps an
     * unselected overlay byte-identical; a selected dye multiplies the overlay by the dye's ARGB
     * (mirroring vanilla's {@code coloredCutoutModelRender} colour arg), exactly like the collar tint.
     */
    private static int resolveOverlayTint(@NotNull EntityModelLoader.OverlayLayer overlay, @NotNull EntityAppearance appearance) {
        return selectedOverlayTint(overlay, appearance).map(DyeColor::argb).orElse(overlay.tintArgb());
    }

    /**
     * The dye selected for the overlay's {@code tint_by} axis, or empty when the overlay is untinted
     * or the appearance leaves that axis at its default.
     */
    private static @NotNull Optional<DyeColor> selectedOverlayTint(@NotNull EntityModelLoader.OverlayLayer overlay, @NotNull EntityAppearance appearance) {
        return overlay.tintBy().flatMap(TintAxis::ofToken).flatMap(appearance::tint);
    }

    /**
     * Whether the appearance supplies the overlay's {@code tint_by} axis colour. Drives both the tint
     * override and the {@code requires_tint} render gate (the sheep wool undercoat).
     */
    private static boolean hasSelectedTint(@NotNull EntityModelLoader.OverlayLayer overlay, @NotNull EntityAppearance appearance) {
        return selectedOverlayTint(overlay, appearance).isPresent();
    }

    /**
     * Builds the rasterizer-ready triangles for one {@link EntityModelLoader.BlockOverlayLayer}.
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
     * @return the rasterizer-ready triangles, or an empty list when the block or its textures are missing
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildBlockOverlayTriangles(
        @NotNull RendererContext context,
        @NotNull EntityModelLoader.BlockOverlayLayer overlay,
        @NotNull EntityModelData model,
        @NotNull Matrix4f entityFit
    ) {
        Optional<Block> block = context.findBlock(overlay.blockId());
        if (block.isEmpty()) return Concurrent.newList();

        // Pre-load each face's texture by dereferencing #variable bindings against the model's
        // texture map, exactly mirroring {@code BlockRenderer.Isometric3D.buildFromBlockElements}.
        // Faces whose ref still resolves to a {@code #} after dereference (broken bindings) skip
        // texture loading; the kit treats them as no-texture faces.
        ConcurrentMap<String, PixelBuffer> faceTextures = Textures.loadElementFaceTextures(
            block.get().getModel().getElements(), block.get().getModel().getTextures(),
            context::resolveTexture);
        if (faceTextures.isEmpty()) return Concurrent.newList();

        // Apply the block's tint to its tint-indexed faces, exactly as the block icon does - a
        // carried grass block's top face (tintindex 0) samples the grass colormap green, while its
        // untinted dirt sides stay white. Sampled against the default biome (there is no world
        // context for a held block); untinted (tintindex -1) faces keep white.
        int blockTint = BlockRenderer.resolveBlockTint(context, block.get(), Biome.Vanilla.PLAINS);
        ConcurrentList<VisibleTriangle> blockTris = BlockGeometryKit.buildFromElements(
            block.get().getModel().getElements(), faceTextures, blockTint, ColorMath.WHITE);
        if (blockTris.isEmpty()) return Concurrent.newList();

        // Compose the per-overlay transform matrix in vanilla block units. PoseStack ops apply
        // in bytecode order to the LOCAL frame: under the column-vector convention each new op
        // post-multiplies, matching vanilla's PoseStack `pose = pose * newOp`. Final composite
        // applies the most-recently-appended op first to the cube-local vertex. The bone anchor is
        // applied separately in pixel space (see finalMatrix) so it composes the bone's FULL
        // ancestor chain, not just the attached bone's own local pivot / rotation.
        Matrix4f blockUnitChain = Matrix4f.IDENTITY;

        for (EntityModelLoader.TransformOp op : overlay.transforms()) {
            blockUnitChain = switch (op) {
                case EntityModelLoader.Translate t -> blockUnitChain.translate(t.x(), t.y(), t.z());
                case EntityModelLoader.RotateY r -> blockUnitChain.rotateY((float) Math.toRadians(r.degrees()));
                case EntityModelLoader.RotateX r -> blockUnitChain.rotateX((float) Math.toRadians(r.degrees()));
                case EntityModelLoader.Scale s -> blockUnitChain.scale(s.x(), s.y(), s.z());
            };
        }

        // Vanilla expects block-model vertices in {@code [0, 1]} (corner-at-origin) since the
        // last pose op {@code translate(-0.5, -0.5, -0.5)} re-centers them at origin before the
        // submit. {@link BlockGeometryKit#buildFromElements} pre-centers the cube to
        // {@code [-0.5, 0.5]} for inventory/atlas use, so add 0.5 on each axis to recover the
        // corner-at-origin convention before the chain applies. Appended last so that, in
        // column-vector composition, this op is rightmost and applies first to the input vertex.
        blockUnitChain = blockUnitChain.translate(0.5f, 0.5f, 0.5f);

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
            // Re-shade with entity Lambertian lighting on the post-transform normal. The block kit
            // baked cardinal-bucket shading (Lighting.ITEMS_3D-style: 1.0/0.8/0.6/0.5), but vanilla
            // submits these mushroom/flower block models through the entity render type which dots
            // the post-pose-stack normal against ENTITY_IN_UI lights per pixel - continuous, not
            // bucketed. Sampling mooshroom mushroom red showed our 0.67-0.90 block-cardinal range
            // vs vanilla's 0.45-0.71 Lambertian range.
            //
            // Shade against a Y-flipped copy of the normal, matching EntityGeometryKit.buildTriangles'
            // lighting frame (positions + stored normal stay Y-up; the shade uses (x, -y, z)). Without
            // the flip, axis-aligned up/down faces shade against the wrong light hemisphere - the
            // snow-golem carved_pumpkin top rendered at the 0.4 ambient floor instead of ~1.0.
            // Mushroom-cross overlays are unaffected: their plane normals are horizontal (y ~= 0), so
            // the flip is a no-op and mooshroom parity is unchanged.
            Vector3f shadingNormal = new Vector3f(transformedNormal.x(), -transformedNormal.y(), transformedNormal.z());
            float shading = Lighting.entityInUi(shadingNormal);
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
            out.add(new VisibleTriangle(
                tri.position0().transform(finalMatrix),
                tri.position1().transform(finalMatrix),
                tri.position2().transform(finalMatrix),
                tri.uv0(), tri.uv1(), tri.uv2(),
                tri.texture(), tri.tintArgb(),
                transformedNormal,
                shading, new SurfaceTraits(true, tri.traits().emissive(), false, false)
            ));
        }
        return out;
    }

    /**
     * Selects whether canvas sizing + silhouette centring measure this entity alone (base
     * model + non-{@code skipBounds} overlays unioned together) or also union across every
     * family member from {@link EntityModelLoader#loadFamilies()}. {@link EntityOptions.FitMode}
     * picks which source via {@link #boundsScopeFor}; both modes share the same per-entity
     * + overlay primitives so the only difference is whether the family loop runs.
     */
    private enum BoundsScope { ENTITY_UNION, FAMILY_UNION }

    /**
     * Maps a public {@link EntityOptions.FitMode} to the internal {@link BoundsScope} the
     * canvas / centring math should measure against. {@code OUTPUT_SIZE} and
     * {@code UNION_BOUNDS} measure this entity only; {@code FAMILY_BOUNDS} additionally
     * unions every family member so cow + cow_warm + mooshroom share the same canvas.
     */
    private static @NotNull BoundsScope boundsScopeFor(@NotNull EntityOptions.FitMode mode) {
        return mode == EntityOptions.FitMode.FAMILY_BOUNDS ? BoundsScope.FAMILY_UNION : BoundsScope.ENTITY_UNION;
    }

    /**
     * Computes the screen-space bounds for the active {@link BoundsScope}. The two existing
     * primitives ({@link #computeUnionScreenBounds} for one entity, {@link
     * #computeFamilyUnionScreenBounds} for the whole family) stay unchanged; this method is
     * the single dispatch point so canvas-sizing and centring agree on which bounds to use.
     */
    private @NotNull Box computeScreenBoundsFor(
        @NotNull BoundsScope scope,
        @NotNull String entityId,
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer texture
    ) {
        return switch (scope) {
            case ENTITY_UNION -> computeUnionScreenBounds(definition, transform, modelScale, texture);
            case FAMILY_UNION -> computeFamilyUnionScreenBounds(entityId, definition, transform, modelScale, texture);
        };
    }

    /**
     * Sizes the output canvas + model-units-to-NDC scale from a pre-measured projected silhouette,
     * dispatched on {@link EntityOptions#getFitMode()}. The caller measures the (alpha-tight, optionally
     * family-unioned) {@code screenBounds} through the render orientation ({@link ModelEngine#orient});
     * this is the pure sizing math the orthographic entity path feeds into a
     * {@link FitRequest#nativeScale(float, Box) NATIVE_SCALE} fit.
     *
     * <p>{@code UNION_BOUNDS} / {@code FAMILY_BOUNDS}: take the tight screen-space extent in
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
        int canvasW = Math.max(1, (int) Math.ceil(rawW * shrink));
        int canvasH = Math.max(1, (int) Math.ceil(rawH * shrink));
        float effectivePxPerEntityUnit = pxPerEntityUnit * shrink;
        int minDim = Math.min(canvasW, canvasH);
        float ndcScale = effectivePxPerEntityUnit / (minDim * projectionScale);
        return new CanvasFit(canvasW, canvasH, ndcScale);
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
     */
    private @NotNull Box computeUnionScreenBounds(
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer texture
    ) {
        Box bounds = EntityGeometryKit.computeScreenBounds(definition.model(), transform, modelScale, texture);
        RendererDebug.baseBounds(bounds);
        for (EntityModelLoader.OverlayLayer overlay : definition.overlays()) {
            if (overlay.model().getBones().isEmpty()) continue;
            // Overlays flagged skipBounds (LlamaDecorLayer-style equipment-driven overlays) still
            // render but don't contribute to bounds, mirroring the vanilla harness's
            // NO_RENDER_LAYER_SUFFIXES treatment of those layer classes.
            if (overlay.skipBounds()) continue;
            Box overlayBounds = EntityGeometryKit.computeScreenBounds(overlay.model(), transform, modelScale, texture);
            RendererDebug.overlayBounds(overlay.textureRef().orElse("<unset>"), overlayBounds);
            bounds = unionBoxes(bounds, overlayBounds);
        }
        // Block-model overlays: build the same fit-neutral geometry the render produces (entity fit
        // = buildEntityFitMatrix(ZERO, modelScale), so the positions live in the entity-pixel frame
        // the body bounds use), then union its alpha-tight silhouette measured through the render
        // orientation.
        if (!definition.blockOverlays().isEmpty()) {
            Matrix4f fitNeutral = EntityGeometryKit.buildEntityFitMatrix(Vector3f.ZERO, modelScale);
            for (EntityModelLoader.BlockOverlayLayer blockOverlay : definition.blockOverlays()) {
                ConcurrentList<VisibleTriangle> tris = buildBlockOverlayTriangles(this.context, blockOverlay, definition.model(), fitNeutral);
                Box boBounds = EntityGeometryKit.computeBlockOverlayScreenBounds(tris, transform);
                bounds = unionBoxes(bounds, boBounds);
            }
        }
        return bounds;
    }

    /**
     * Unions screen-space bounds across every family member of {@code entityId}, mirroring the
     * vanilla harness's {@code EntitySweeper.computeFamilyFits} pre-pass so variant siblings
     * (cow_cold / cow_warm, chicken_cold / chicken_warm, mooshroom in cow's family) render into
     * a single canvas sized to the largest member. Without this the family's smaller variants
     * canvas-fit to their own (tighter) bound and the family-locked geometry shifts position
     * between variants - vanilla's pixel-identical-canvas guarantee requires every family
     * member to share the same canvas dimensions, scale, and anchor.
     * <p>
     * Per-member: load the variant's own definition + default texture (NOT the current render's
     * options-override texture), apply the variant's {@link EntityModelLoader.EntityDefinition#rendererScale rendererScale} model
     * scale, run {@code computeUnionScreenBounds}, union the result. Family members whose
     * texture / definition can't be resolved (missing PNG, unloaded variant) are skipped - the
     * union degrades to the available members rather than throwing.
     * <p>
     * Members are sourced from {@code EntityModelLoader.loadFamilies()} - {@code variant_of}
     * for variant-of-same-entity groupings plus the top-level {@code families} table (derived
     * (mooshroom -> cow). Singleton entities return a 1-element family list so this method
     * collapses to {@link #computeUnionScreenBounds} for non-family-bearing entities.
     */
    private @NotNull Box computeFamilyUnionScreenBounds(
        @NotNull String entityId,
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer texture
    ) {
        Box bounds = computeUnionScreenBounds(definition, transform, modelScale, texture);
        List<String> members = EntityModelLoader.loadFamilies().getOrDefault(entityId, List.of(entityId));
        if (members.size() <= 1) return bounds;
        for (String memberId : members) {
            if (memberId.equals(entityId)) continue;
            EntityModelLoader.EntityDefinition memberDef = this.javaEntities.get(memberId);
            if (memberDef == null || memberDef.model().getBones().isEmpty()) continue;
            Optional<PixelBuffer> memberTexture = resolveFamilyMemberTexture(memberDef);
            if (memberTexture.isEmpty()) continue;
            float memberScale = memberDef.rendererScale();
            Box memberBounds = computeUnionScreenBounds(memberDef, transform, memberScale, memberTexture.get());
            bounds = unionBoxes(bounds, memberBounds);
        }
        return bounds;
    }

    /**
     * Resolves a family-member's default texture for the family-fit bound walk. Unlike
     * {@link #resolveEntityTexture} this ignores {@code options.textureId} (family-fit measures
     * each variant's OWN bound, not the current-render texture override).
     */
    private @NotNull Optional<PixelBuffer> resolveFamilyMemberTexture(@NotNull EntityModelLoader.EntityDefinition definition) {
        if (definition.textureRef().isEmpty()) return Optional.empty();
        return resolveEntityRef(definition.textureRef().get());
    }

    /**
     * Whether the appearance selects this equipment overlay's slot - mirrors the {@code EQUIPMENT}
     * feature's render gate ({@link EntityAppearance#equipmentMaterial(String)}) so the bounds union
     * folds in exactly the equipment meshes that render. A slot with no selected material (the default
     * appearance) or an empty mesh contributes nothing, keeping the unequipped canvas byte-identical.
     *
     * @param equipment the equipment overlay to test
     * @param appearance the render appearance carrying the equipment axis selection
     * @return {@code true} when the overlay's slot is selected and its mesh is non-empty
     */
    private static boolean equipmentSelected(
        @NotNull EntityModelLoader.EquipmentOverlay equipment,
        @NotNull EntityAppearance appearance
    ) {
        return appearance.equipmentMaterial(equipment.slot()).isPresent()
            && !equipment.model().getBones().isEmpty();
    }

    /**
     * Returns the axis-aligned union of two boxes - the smallest {@link Box} containing both.
     */
    private static @NotNull Box unionBoxes(@NotNull Box a, @NotNull Box b) {
        return new Box(
            Math.min(a.minX(), b.minX()),
            Math.min(a.minY(), b.minY()),
            Math.min(a.minZ(), b.minZ()),
            Math.max(a.maxX(), b.maxX()),
            Math.max(a.maxY(), b.maxY()),
            Math.max(a.maxZ(), b.maxZ())
        );
    }

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
