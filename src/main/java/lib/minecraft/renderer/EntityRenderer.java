package lib.minecraft.renderer;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.bake.armor.ElytraKit;
import lib.minecraft.renderer.bake.armor.EntityArmorKit;
import lib.minecraft.renderer.bake.armor.EquipmentKit;
import lib.minecraft.renderer.bake.mesh.BlockGeometryKit;
import lib.minecraft.renderer.bake.mesh.EntityGeometryKit;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.bake.texture.GlintKit;
import lib.minecraft.renderer.diagnostic.DebugChannel;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.CanvasFit;
import lib.minecraft.renderer.engine.camera.CanvasSolver;
import lib.minecraft.renderer.engine.camera.FitFrame;
import lib.minecraft.renderer.engine.camera.FitRequest;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.camera.Placement;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.draw.GeometryLayer;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.frame.RasterPass;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Vector2f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.port.answer.CitResult;
import lib.minecraft.renderer.request.AnimationOptions;
import lib.minecraft.renderer.request.AppearanceOptions;
import lib.minecraft.renderer.request.Biome;
import lib.minecraft.renderer.request.EntityOptions;
import lib.minecraft.renderer.request.OutputOptions;
import lib.minecraft.renderer.slot.EntitySlot;
import lib.minecraft.renderer.vanilla.DyeColor;
import lib.minecraft.renderer.vanilla.appearance.AppearanceGate;
import lib.minecraft.renderer.vanilla.appearance.TintAxis;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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
     * Renderer context for entity lookup, texture resolution + isometric engine setup.
     */
    private final @NotNull RendererContext context;

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
     * The prefix qualifying an entity texture ref into the id its sidecar is read under, and the one
     * {@link #resolveEntityTextureAtTick} applies to a pixel read.
     */
    private static final @NotNull String ENTITY_TEXTURE_PREFIX = "minecraft:entity/";

    /**
     * Constructs an entity renderer bound to the given context.
     *
     * @param context the renderer context for entity lookup, texture resolution + isometric engine setup
     */
    public EntityRenderer(@NotNull RendererContext context) {
        this.context = context;
    }

    /**
     * The definition one id resolves to, refusing an id the index does not hold - a render never
     * invents an entity.
     *
     * @param entityId the namespaced entity id
     * @return the indexed definition
     * @throws RendererException if the index holds no such entity
     */
    private @NotNull Entity indexed(@NotNull String entityId) {
        return this.context.findEntity(entityId)
            .orElseThrow(() -> new RendererException("Entity '%s' is not an entity the index resolves", entityId));
    }

    /**
     * The entity's shipped style catalog - the discovery half of which styles an entity supports.
     * An entity the index holds whose definition names no styles answers the bind-only catalog; an
     * unknown id throws the same refusal a render of it does.
     *
     * @param entityId the namespaced entity id
     * @return the shipped catalog
     * @throws RendererException if the index holds no such entity
     */
    public @NotNull StyleCatalog styles(@NotNull String entityId) {
        return indexed(entityId).styles();
    }

    /**
     * Renders the entity and composites it over the caller's background. An id the index does not
     * hold, and a style the entity's catalog refuses, throw; an entity with no texture or no bones
     * answers an empty frame composited over the background.
     */
    @Override
    public @NotNull ImageData render(@NotNull EntityOptions options) {
        return options.getBackground().composite(renderEntity(options));
    }

    /**
     * The appearance with the style's entailed bone toggles unioned in - a selection that inflates
     * a bone draws it without the caller naming the toggle beside the style.
     *
     * @param appearance the appearance the caller named
     * @param toggles the style's entailed toggles
     * @return the appearance carrying both toggle sets, or the appearance itself when the style
     *     entails none
     */
    private static @NotNull AppearanceOptions styled(
        @NotNull AppearanceOptions appearance, @NotNull ConcurrentList<String> toggles) {

        if (toggles.isEmpty()) return appearance;
        Set<String> union = new LinkedHashSet<>(appearance.getToggles());
        union.addAll(toggles);
        return appearance.mutate().toggles(union).build();
    }

    /**
     * Resolves the entity definition, style, texture, and bounds; sizes the canvas; assembles the
     * base body plus its overlay / block-overlay / armor {@link GeometryLayer geometry layers}; then
     * rasterizes every layer in one shared depth pass through {@link Rasterizer}. An id the index
     * does not hold, and a style the entity's catalog refuses, throw; a missing texture and an
     * empty bone tree return an empty frame.
     */
    private @NotNull ImageData renderEntity(@NotNull EntityOptions options) {
        Entity definition = indexed(options.getEntityId());
        // Resolved twice on purpose: the first answers against the shipped union, so a refusal lists
        // every id the entity supports and the row's entailed toggles are in hand before the
        // appearance resolves; the second reads the same id off the in-force view, so what moves is
        // what the resolved subject moves.
        PoseStyle requested = definition.styles()
            .resolve(options.getStyle(), options.getAppearance()::applies, options.getEntityId());
        // Fold the age / carried policy into a single resolved definition up front, so every
        // downstream site (texture, ortho bounds, geometry contributors) reads it unconditionally
        // with no scattered !baby gates. The resolve is a no-op for a non-baby, non-carried appearance.
        Entity resolved = styled(options.getAppearance(), requested.toggles()).resolve(definition);
        PoseStyle style = resolved.styles()
            .resolve(options.getStyle(), options.getAppearance()::applies, options.getEntityId());
        AnimationOptions anim = options.getAnimation().resolved(
            style.moves() ? StyleCatalog.STRIP_FRAMES : 1,
            resolved.styles().stripTicksPerFrame(style));
        PosePlayer.PosedFrames posed = PosePlayer.frames(resolved, style, resolved.styles().periodTicks());
        EntityMesh model = resolved.model();

        // Resolve the base texture at the timeline's start tick: frame 0 of a sidecar-carrying
        // entity texture, or the raw strip for a
        // sidecar-less texture (every vanilla entity, so byte-identical on the vanilla roster). This
        // start-tick texture drives the missing-texture early-out and canvas sizing; the per-frame
        // render re-resolves inside the rasterizer callback so an opted-in animated texture rebuilds.
        Timeline.TickTimeline timeline = anim.timeline();
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
        ConcurrentList<EquippedOverlay> equipped = resolveEquippedOverlays(resolved, options.getAppearance(), startTick);
        // Whether the wings draw: the elytra selection, on a row whose vanilla renderer builds the wings
        // layer. One answer for the wings feature and both canvas folds, so the two cannot disagree.
        // Asked of the indexed definition, whose every form carries the same answer.
        boolean wings = options.getAppearance().isElytra() && definition.layers().wings();
        // Resolve the wing texture on the same terms as the equipment overlays above: it decides whether
        // the wings bound the canvas at all, and the silhouette they contribute below. Wings the pack
        // ships no texture for render nothing, so they are empty here rather than bounding the canvas.
        Optional<PixelBuffer> wingTexture = wings
            ? ElytraKit.wingsTexture(this.context, Optional.empty(), startTick)
            : Optional.empty();
        // The age the wings are drawn at, which the canvas folds and the wings feature share. Asked of
        // the indexed definition: the resolved one already wears the shell that age picks.
        boolean babyWings = options.getAppearance().rendersBaby(definition);

        EulerRotation user = options.getOutput().getRotation();
        EulerRotation effective = new EulerRotation(
            user.pitch(),
            user.yaw(),
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
        Rasterizer engine = new Rasterizer(entityCamera, ENTITY_PLACEMENT);
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
        final FitFrame kitFrame;
        final FitRequest fitRequest;
        if (lens.kind() == Lens.Kind.ORTHOGRAPHIC) {
            CanvasSolver.BoundsScope scope = boundsScopeFor(options.getFitMode());
            Matrix4f renderOrient = engine.orient(effective);
            Box screenBounds = computeScreenBoundsAcrossFrames(scope, options.getEntityId(),
                resolved, options, posed, timeline, renderOrient, modelScale, texture.get());
            // Fold a selected equipment overlay's mesh into the pre-measured silhouette so an inflated /
            // protruding equipment mesh can't crop at the canvas edge under the NATIVE_SCALE fit (which
            // sizes from these bounds, not the rendered triangles). Measured through the overlay's own
            // composited texture, so it bounds the canvas by what it actually draws - the same
            // alpha-tight walk the base body already gets. Equipment textures are mostly transparent
            // (a saddle is a few straps over a whole equine body), so the geometric AABB would size the
            // canvas for a silhouette an order of magnitude larger than the render. Gated on the
            // equipment axis, so the default (unequipped) canvas is unchanged. Each overlay is
            // measured where its pose leaves it at every tick the body is measured at, because an
            // equipment mesh moves with its wearer and a posed part - the happy ghast's goggles turned
            // up onto its brow, a striding horse's armour - can reach past where it rests.
            for (int frame = 0; frame < timeline.frames(); frame++) {
                Entity posedSubject = posed.at(timeline.tickAt(frame));
                for (EquippedOverlay equipment : equipped)
                    screenBounds = screenBounds.union(EntityGeometryKit.computeScreenBounds(
                        equipment.meshIn(posedSubject), renderOrient, modelScale, equipment.texture()));
            }
            // Measured through the wings' own texture, like the equipment overlays. The walk keeps each
            // face's opaque-texel sub-rectangle, and each wing's outward face is opaque along all four
            // edges of its UV box, its outline running on the diagonal: so the fold reaches that face's
            // transparent lower-outer corner, the same point the raw box reaches, and the canvas carries
            // those blank columns beside the wing. The texture walk still trims a pack whose wing art
            // leaves an edge of a face clear. It is the mesh the wings feature draws, at the age it
            // draws them, so a baby's wings are measured where they hang.
            if (wingTexture.isPresent())
                screenBounds = screenBounds.union(EntityGeometryKit.computeScreenBounds(
                    ElytraKit.wingsMesh(babyWings),
                    renderOrient, modelScale, wingTexture.get()));
            // And the worn-armor shell, for the same reason: it stands clear of the body on every
            // side vanilla inflates it, and a baby's is a hooded shroud around a body a third its
            // bulk. Gated on a piece actually being equipped, so an unarmored render - which is all
            // but fourteen rows of the entity sweep - measures exactly what it measured before.
            Optional<Box> armorBounds = resolved.humanoidArmor().flatMap(shell -> EntityArmorKit.screenBounds(shell,
                options.getArmor().equipped(), options.getArmor().getItems(),
                renderOrient, modelScale, this.context));
            if (armorBounds.isPresent()) screenBounds = screenBounds.union(armorBounds.get());
            DebugChannel.fitBounds(options.getEntityId(),
                screenBounds.minX(), screenBounds.maxX(), screenBounds.minY(), screenBounds.maxY());
            CanvasFit fit = CanvasSolver.solve(screenBounds, entityCamera,
                options.getFitMode() == EntityOptions.FitMode.OUTPUT_SIZE,
                options.getOutput().getCanvasSize(), options.getPadding(),
                options.getPixelsPerBlock(), options.getMaxCanvasSize());
            canvasW = fit.canvasW();
            canvasH = fit.canvasH();
            kitFrame = new FitFrame(Vector3f.ZERO, 1f, modelScale);
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
            // canvas edge. Gated with the wings feature, so a render that draws no wings is unchanged.
            if (wingTexture.isPresent())
                modelBounds = modelBounds.union(EntityGeometryKit.computeBounds(
                    ElytraKit.wingsMesh(babyWings)));
            EntityGeometryKit.UnitFit unit = EntityGeometryKit.unitFit(scaleBox(modelBounds, modelScale));
            kitFrame = new FitFrame(unit.centre(), unit.ndcScale(), modelScale);
            fitRequest = FitRequest.autoFill(Math.max(1e-3f, (canvasSize - 2f * padding) / (float) canvasSize));
        }

        // Per-frame geometry build: the base body plus its model-overlay /
        // block-overlay / armor feature layers, with every entity / overlay / carried-block texture
        // resolved at the frame's tick. Emission order is load-bearing (depth tie-break, translucent
        // sort, emissive depth-skip), so the slot order stays base -> overlays -> block
        // overlays -> armor; the base body (built imperatively here) is always first and produces the
        // bone bounds the armor layer consumes. Callers splice their own layers via
        // EntityOptions.layerDecorator. All layers are built fit-neutral and fitted together by the
        // single rasterizeFitted call in the callback. A frameCount=1 render bakes once at startTick,
        // byte-identical to the pre-animation path; a sidecar-less entity resolves the same raw strip
        // at every tick. The build MUST stay inside the callback (the fluid invariant) so an opted-in
        // animated texture is not frozen on frame 0; the Rasterizer is rebuilt per frame for
        // thread-safe parallel strip baking. FeatureContext carries the shared geometry-build frame
        // (the render frame, textures, pack context, tick) the static feature constants cannot capture.
        IntFunction<ConcurrentList<VisibleTriangle>> buildAtTick = tick -> {
            // The whole subject at this tick, body and every overlay pass, so a pass drawing geometry
            // of its own moves with the body rather than staying where it was authored.
            Entity posedSubject = posed.at(tick);
            PixelBuffer frameTexture = resolveEntityTexture(resolved, options, tick).orElse(texture.get());
            ConcurrentList<VisibleTriangle> triangles = EntityGeometryKit.buildTriangles(posedSubject.model(), frameTexture,
                new EntityGeometryKit.EntityBuildParams(
                    kitFrame, PassDeclaration.DEFAULT, resolved.baseTintArgb())).triangles();
            LayerStack<GeometryLayer> stack = new LayerStack<>();
            FeatureContext featureCtx = new FeatureContext(posedSubject, options, wings, babyWings, posedSubject.model(),
                frameTexture, kitFrame, this.context, tick);
            for (EntityFeature feature : EntityFeature.values())
                feature.contribute(featureCtx, stack);
            Layers.foldInto(stack, options.getLayerDecorator(), triangles);
            // One relight per draw, over the folded stack, the way vanilla binds Lighting.ENTITY_IN_UI
            // once per GUI entity before any layer is submitted - so a wearer, its overlays, its carried
            // block and everything it wears light under one entry. Every producer above emits geometry
            // and no shade, and each stores its normal in the one frame the kit emits in - which is what
            // AxisSigns.MIRROR_Y carries into the frame the two light directions are resolved in.
            return Shading.relightForEntityInUi(triangles, LightingFrame.ENTITY_IN_UI, AxisSigns.MIRROR_Y);
        };

        // Build frame 0 once, up front, for the empty-geometry early-out (a bones-but-no-triangles
        // entity renders a transparent canvas, exactly as before). A one-frame schedule draws this
        // very geometry - it samples the start tick and there is no other frame to draw - so a static
        // render still builds exactly once. A schedule with frames to spare builds each of them,
        // because a posed subject stands somewhere different at every tick and nothing about a tick's
        // geometry can be carried to its neighbour.
        ConcurrentList<VisibleTriangle> startTriangles = buildAtTick.apply(startTick);
        if (startTriangles.isEmpty())
            return Timeline.still(PixelBuffer.create(canvasW, canvasH));

        boolean single = timeline.frames() == 1;
        boolean enchanted = options.getArmor().hasEnchanted();

        // Rasterize + optional FXAA + supersample-downscale + masked glint via the shared tail. The
        // glint mask is recorded at the raster size and downsampled so the foil is confined to the
        // (glinted) armor rather than the whole entity silhouette.
        //
        // Route through the schedule UNCONDITIONALLY (the FluidRenderer pattern): a frameCount=1 timeline
        // yields the same single static frame but sampled at the timeline's start tick, so bake draws
        // at timeline.tickAt(0) == startTick, which is the frame already built above. A raw Static(0)
        // would instead hardcode tick 0, which - since the canvas/bounds/startTriangles above are built
        // at startTick - would size the canvas for startTick's frame yet DRAW frame 0 (a wrong-frame
        // mismatch on an animated texture with a non-zero startTick).
        // At frameCount=1 the timeline is a Static at startTick and the foil takes the scroll
        // direction; at frameCount>1 it bakes the strip and stamps per frame. Default (startTick=0,
        // frameCount=1) is byte-identical.
        //
        // The orthographic canvas + NATIVE_SCALE silhouette are measured across EVERY frame the
        // schedule samples, each through its own posed mesh and its own tick's texture, and the
        // union is what the canvas is sized from. Both halves of a growing silhouette need that: a
        // bone the pose swings wider at a later tick, and a flipbook painting opaque texels outside
        // frame 0's outline. A static schedule measures the one frame it draws, so it sizes exactly
        // the canvas it always did. The perspective path measures nothing here - it auto-fills per
        // frame in the engine.
        int ssaa = options.getOutput().getSupersample();
        return timeline.bake(
            RasterPass.of(canvasW, canvasH, ssaa, options.getOutput().isAntiAlias(), (target, tick) ->
                    new Rasterizer(entityCamera, ENTITY_PLACEMENT).rasterizeFitted(
                        single ? startTriangles : buildAtTick.apply(tick), target, effective, fitRequest))
                .withMask(enchanted)
                .finishing(GlintKit.Foil.armor(this.context::resolveTexture, enchanted)));
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
        String textureId = ENTITY_TEXTURE_PREFIX + ref;
        return Flipbook.atTick(context.resolveTexture(textureId), context.findFlipbook(textureId), tick);
    }

    /**
     * Resolves the entity texture as the first present source of an ordered precedence: an explicit
     * {@link EntityOptions#getTextureId() texture id on options} (user override, authoritative when
     * present - looked up against the Java atlas via the pack stack) &gt; the {@code <variant>_baby}
     * texture when the resolved definition renders the baby mesh &gt; the copper golem's weathered
     * base when a weathering state is chosen &gt; an {@link AppearanceOptions#getState() state}
     * selection the definition carries (wolf {@code tame} / {@code angry}) &gt; the state the
     * definition is already in, which is its {@link Entity#textureRef() texture_ref}.
     *
     * <p>Three of those four are the same lookup at different keys - {@code baby}, the selected state,
     * and the state axis' declared option - so what orders them is which key to try rather than where
     * to look. Each is resolved against the vanilla pack at {@code minecraft:entity/<ref>} via
     * {@link #resolveEntityTextureAtTick}, and a candidate whose texture is MISSING falls through to
     * the next, which is why they are tried in turn rather than reduced to one key up front.
     */
    private @NotNull Optional<PixelBuffer> resolveEntityTexture(
        @NotNull Entity definition,
        @NotNull EntityOptions options,
        int tick
    ) {
        if (options.getTextureId().isPresent())
            return options.getTextureId().flatMap(id ->
                Flipbook.atTick(this.context.resolveTexture(id), this.context.findFlipbook(id), tick));

        AppearanceOptions appearance = options.getAppearance();
        Entity.Variation<String, String> state = definition.axes().state();
        return definition.babyTextureRef(appearance.isBaby()).flatMap(ref -> resolveEntityTextureAtTick(this.context, ref, tick))
            .or(() -> appearance.getWeathering().stateKey().flatMap(state::select)
                .flatMap(ref -> resolveEntityTextureAtTick(this.context, ref, tick)))
            .or(() -> definition.stateTextureRef(appearance.getState()).flatMap(ref -> resolveEntityTextureAtTick(this.context, ref, tick)))
            .or(() -> definition.textureRef().flatMap(ref -> resolveEntityTextureAtTick(this.context, ref, tick)));
    }

    /**
     * The entity's geometry contributors, each constant packing its target {@link EntitySlot
     * slot}, its self-gating policy, and its geometry contribution in one place - the entity analogue of
     * the self-contained {@link Projection} / engine kits. Declaration order IS emission order: model
     * overlays, then block overlays, then worn armor. The base body is built imperatively in
     * {@link #renderEntity} and is always emitted
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
                String texturePrefix = ctx.definition().texturePrefix();
                for (Entity.OverlayLayer overlay : ctx.definition().overlays()) {
                    // A tint-gated overlay (sheep wool undercoat) renders only once its tint_by axis
                    // selects a colour differing from its baked tint, which is vanilla's own early
                    // return on the dye its layer compares against. Evaluated through the gate rather
                    // than beside it, so there is one definition of the condition.
                    if (overlay.gate().filter(AppearanceGate.TintedGate.class::isInstance)
                        .filter(gate -> !appearance.passes(gate)).isPresent()) continue;
                    int overlayTint = resolveOverlayTint(overlay, appearance);
                    Optional<String> overlayRef = overlay.textureBy()
                        .map(axis -> appearance.texture(axis, texturePrefix, overlay.textureRef()))
                        .orElse(overlay.textureRef());
                    // A texture_by overlay whose axis resolves to no texture draws nothing - the base /
                    // "none" state (iron golem Crackiness.NONE) - so skip it, keeping the default
                    // (unselected) render unchanged. Overlays with a baked default (tropical fish
                    // pattern's KOB) always resolve, so they are never skipped here.
                    if (overlay.textureBy().isPresent() && overlayRef.isEmpty()) continue;
                    // The mesh this pass draws: its own, unless the villager hat rule suppresses the
                    // head subtree in favour of the profession's own hat.
                    EntityMesh overlayMesh = selectOverlayMesh(ctx, overlay, overlayRef, texturePrefix);
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
                        // The pass's own texture offset, applied to the emitted UVs rather than to the
                        // mesh: vanilla builds it into the render type's texture matrix, so it moves
                        // where the pass samples and never where it stands.
                        sink.addAll(scrolled(EntityGeometryKit.buildTriangles(overlayMesh, overlayTex.get(),
                            new EntityGeometryKit.EntityBuildParams(ctx.frame(), overlay.pass(), overlayTint)
                        ).triangles(), overlay.textureOffsetAt(ctx.tick())));
                    });
                }
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
         * gated on the {@code elytra} appearance selection and on the row's vanilla renderer building the
         * wings layer, and drawn at the age the subject renders at - the half-scale pair on a baby and on
         * a small armour stand alike. Resolves to no triangles when the entity wears no elytra, its
         * renderer builds no wings layer, or the pack ships no elytra wing texture (no fallback).
         */
        WINGS(EntitySlot.MODEL_OVERLAY) {
            @Override
            void contribute(@NotNull FeatureContext ctx, @NotNull LayerStack<GeometryLayer> stack) {
                if (!ctx.wings()) return;
                stack.append(this.slot, sink ->
                    sink.addAll(ElytraKit.buildWings3D(ctx.context(), ctx.baby(), ctx.frame(),
                        Optional.empty(), ctx.tick())));
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
                EntityMesh model = ctx.model();
                FitFrame frame = ctx.frame();
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
                    sink.addAll(EntityArmorKit.buildEntityArmor3D(armor.get(), ctx.frame(),
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
     * armor pieces), whether the wings draw, whether the subject renders as a baby, and the primary
     * {@link EntityMesh model} (adult or baby), plus the resolved
     * base texture, the {@link FitFrame} the body was built through, and the
     * {@link RendererContext}. The scene-frame fields travel here because the static
     * {@link EntityFeature} constants cannot capture them from the renderer instance.
     *
     * @param definition the age / carried-resolved definition the features read
     * @param options the render options (appearance + armor pieces)
     * @param wings whether the wings draw - the elytra selection on a row whose vanilla renderer builds
     *     the wings layer, the same answer the canvas folds read
     * @param baby whether the subject renders at the age vanilla calls a baby, the age its wings are
     *     drawn at
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
        boolean wings,
        boolean baby,
        @NotNull EntityMesh model,
        @NotNull PixelBuffer baseTexture,
        @NotNull FitFrame frame,
        @NotNull RendererContext context,
        int tick
    ) { }

    /**
     * One pass's triangles sampling where its render type says, or the list itself where it says
     * nowhere.
     *
     * <p>Applied to the emitted UVs rather than to the mesh, and after the build rather than inside
     * it, because that is what the offset IS: vanilla translates the texture matrix the pass is
     * submitted through, which moves the sample point and leaves the geometry exactly where the
     * layer put it. The breeze's wind is the corpus's one scrolling pass and its silhouette is
     * identical across every frame on both sides, which is the same statement read off the pixels.
     *
     * <p>An offset carries a UV past the sheet's own edge, where the fetch wraps it back in. That is
     * the one place a face samples outside its authored rectangle, and it is deliberate.
     *
     * @param triangles the pass's triangles as the kit built them
     * @param offset what to add to every UV, or empty where the pass scrolls none
     * @return the triangles sampling at the offset, or the given list where there is none
     */
    private static @NotNull ConcurrentList<VisibleTriangle> scrolled(
        @NotNull ConcurrentList<VisibleTriangle> triangles, @NotNull Optional<Vector2f> offset) {

        if (offset.isEmpty()) return triangles;
        Vector2f by = offset.get();
        // The wrap already rides the pass, baked at index build, so a frame whose offset lands on a
        // whole turn - tick zero among them - has nothing to move and keeps the built triangles.
        if (by.x() == 0f && by.y() == 0f) return triangles;
        return triangles.stream()
            .map(triangle -> new VisibleTriangle(
                triangle.position0(), triangle.position1(), triangle.position2(),
                shifted(triangle.uv0(), by), shifted(triangle.uv1(), by), shifted(triangle.uv2(), by),
                triangle.texture(), triangle.tintArgb(), triangle.normal(), triangle.shading(),
                triangle.traits(), triangle.debugTag()))
            .collect(Concurrent.toWideList());
    }

    /** One UV corner moved by the pass's offset. */
    private static @NotNull Vector2f shifted(@NotNull Vector2f uv, @NotNull Vector2f by) {
        return new Vector2f(uv.x() + by.x(), uv.y() + by.y());
    }

    /**
     * The mesh a model overlay draws with: its own mesh, unless the overlay declares an alternate
     * suppressed-pass mesh and the villager hat rule selects it. The hat flags come from the
     * {@code villager} sidecar of the pass' {@link Entity.OverlayLayer#typeHatRef type ref} (the robe
     * texture) and of the selected profession texture, so a resource pack that changes either sidecar
     * changes the decision. An overlay with no alternate, and every context whose texture lookup
     * yields no sidecar, keep the overlay's own mesh.
     *
     * @param ctx the feature context supplying the appearance and the sidecar lookup
     * @param overlay the overlay layer to pick a mesh for
     * @param overlayRef the overlay's already-resolved texture ref
     * @param texturePrefix the entity texture prefix the profession sub-path is qualified with
     * @return the mesh to build triangles from
     */
    private static @NotNull EntityMesh selectOverlayMesh(
        @NotNull FeatureContext ctx,
        @NotNull Entity.OverlayLayer overlay,
        @NotNull Optional<String> overlayRef,
        @NotNull String texturePrefix
    ) {
        if (overlay.noHatModel().isEmpty()) return overlay.model();
        AppearanceOptions appearance = ctx.options().getAppearance();
        MCMeta.Villager.Hat typeHat = villagerHat(ctx.context(),
            overlay.typeHatRef(appearance.getVillagerType(), texturePrefix, overlayRef));
        MCMeta.Villager.Hat professionHat = villagerHat(ctx.context(),
            appearance.getVillagerProfession().textureRef(texturePrefix));
        return useFullModel(professionHat, typeHat) ? overlay.model() : overlay.noHatModel().get();
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
     * The effective multiplicative tint for a model overlay: the {@code tint_by} axis colour when the
     * overlay is dye-driven ({@code wool_color} sheep wool, {@code collar_color} the collar band) and
     * that {@link TintAxis axis}' {@link AppearanceOptions#selection selection} resolves a dye, else the
     * overlay's baked {@link Entity.OverlayLayer#tintArgb() default tint}. The default keeps an
     * unselected overlay unchanged; a selected dye multiplies the overlay by whatever colour that
     * axis draws the dye as ({@link TintAxis#resolve}), mirroring vanilla's
     * {@code coloredCutoutModelRender} colour arg.
     */
    private static int resolveOverlayTint(@NotNull Entity.OverlayLayer overlay, @NotNull AppearanceOptions appearance) {
        return overlay.tintBy()
            .flatMap(axis -> appearance.selection(axis).map(axis::resolve))
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
     * Scales the overlay's transform chain (in vanilla block units) up to entity pixel-units (x16),
     * places it on the bone anchor {@link EntityGeometryKit#resolveBoneAnchorMatrix} answers in
     * pixel-units - the seated container, then the attached part's own step - and applies the
     * entity-fit normalization so the block sits in the same auto-fit window as the entity body.
     * Missing block / texture refs return an empty list rather than failing the render.
     *
     * <p>Static so the {@link EntityFeature#BLOCK_OVERLAYS} constant can call it; both callers pass this
     * renderer's own {@link RendererContext} - the render path via {@link FeatureContext#context()},
     * which answers this renderer's {@code context}, and the orthographic bounds pre-pass
     * ({@link #computeUnionScreenBounds}) directly.
     *
     * @param context the renderer context for block + face-texture lookups
     * @param overlay the block-overlay layer to build
     * @param model the entity mesh supplying the attach-bone anchor chain
     * @param entityFit the entity-fit normalization matrix
     * @param tick the animation tick the carried block's face textures are sampled at (a carried
     *     animated block - e.g. magma - shows frame 0 when static, or its flipbook frame when animated)
     * @return the rasterizer-ready triangles, or an empty list when the block or its textures are missing
     */
    static @NotNull ConcurrentList<VisibleTriangle> buildBlockOverlayTriangles(
        @NotNull RendererContext context,
        @NotNull Entity.BlockOverlayLayer overlay,
        @NotNull EntityMesh model,
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
        ModelData blockModel = drawn.isPresent() && drawn.get().geometry() instanceof Block.ElementGeometry(ModelData model1)
            ? model1
            : block.get().model();

        // Pre-load each face's texture by dereferencing #variable bindings against the model's
        // texture map, walking the same loader the block icon walks in
        // {@code BlockRenderer.Isometric3D.Assembly.elementsAt} - and reading the port's RESOLVING arm
        // where that one substitutes. The two see the same id string off the same block model, so the
        // empty below is the only thing that can tell them apart: an overlay whose texture no pack
        // supplies is dropped here, where a block face draws the checkerboard. That is why the
        // substitution cannot be centralised on the texture id.
        // Faces whose ref still resolves to a {@code #} after dereference (broken bindings) skip
        // texture loading; the kit treats them as no-texture faces. Sampled at the frame's tick so a
        // carried animated block matches the block-icon path (which also flattens to frame 0 by default).
        ConcurrentMap<String, PixelBuffer> faceTextures = blockModel.loadElementFaceTextures(
            id -> Flipbook.atTick(context.resolveTexture(id), context.findFlipbook(id), tick));
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

        // The per-overlay placement in vanilla block units, composed at index build from the ops the
        // layer's shipped row declares: PoseStack ops apply in bytecode order to the LOCAL frame, so
        // under the column-vector convention each post-multiplies, matching vanilla's
        // `pose = pose * newOp`, and the last-declared op applies first to the cube-local vertex. The
        // bone anchor is composed separately, in pixel space (see finalMatrix).
        Matrix4f blockUnitChain = overlay.transform();

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

        // Bone anchor, in entity pixel-units: the steps the posed mesh is seated under - what the
        // subject's renderer composes above the model - then the attached part's own
        // translateAndRotate and none of its ancestors'. Vanilla's carrying layers take the part off
        // the model and apply its step on the stack they were handed, which no ancestor's step is on.
        // A block attached to no part stands on the seat alone, as a layer drawing on that stack does.
        Optional<String> seat = PosePlayer.seat(model);
        Optional<String> part = Optional.ofNullable(overlay.attachedBone());
        Matrix4f boneAnchor = EntityGeometryKit.resolveBoneAnchorMatrix(model, seat, part);
        // Whether the steps that anchor composes carry a non-uniform pose scale, which turns the block's
        // normals by the placement's inverse-transpose rather than by the placement itself, as vanilla's
        // normal matrix does under the same stack. The anchor is where such a scale enters: every scale
        // op a shipped overlay row declares is uniform in magnitude.
        boolean anchorNonUniform = EntityGeometryKit.anchorScalesNonUniformly(model, seat, part);

        // Place the block-unit chain at the bone anchor, converting block-unit positions to entity
        // pixel-units (x16), then run the entity-fit normalization to land in the rasterizer's
        // working frame. Column-vector chain reads right-to-left: blockUnitChain first, then
        // blockToPixel, then the bone anchor, then entityFit.
        Matrix4f finalMatrix = entityFit.multiply(boneAnchor).scale(16f, 16f, 16f).multiply(blockUnitChain);

        return blockTris.stream().map(tri -> {
            Vector3f transformedNormal = EntityGeometryKit.chainNormal(tri.normal(), finalMatrix, anchorNonUniform);
            // The transformed normal is stored rather than shaded against here: a carried block is part
            // of the entity draw, and vanilla submits these mushroom / flower models through the entity
            // render type, which dots the post-pose-stack normal against the ENTITY_IN_UI lights per
            // pixel - continuous, not the block kit's cardinal buckets (1.0/0.8/0.6/0.5). Sampling
            // mooshroom mushroom red showed our 0.67-0.90 block-cardinal range against vanilla's
            // 0.45-0.71 Lambertian range.
            //
            // The pass that lights the folded stack reads this stored normal through AxisSigns.MIRROR_Y,
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
            return new VisibleTriangle(
                tri.position0().transform(finalMatrix),
                tri.position1().transform(finalMatrix),
                tri.position2().transform(finalMatrix),
                tri.uv0(), tri.uv1(), tri.uv2(),
                tri.texture(), tri.tintArgb(),
                transformedNormal,
                Shading.UNLIT, new SurfaceTraits(true, false, false, true,
                    PassDeclaration.DEFAULT.withEmissive(tri.traits().pass().emissive()))
            );
        }).collect(Concurrent.toWideList());
    }

    /**
     * Maps a public {@link EntityOptions.FitMode} to the {@link CanvasSolver.BoundsScope} the
     * canvas / centring math should measure against. {@code OUTPUT_SIZE} and
     * {@code UNION_BOUNDS} measure this entity only; {@code GROUP_BOUNDS} additionally
     * unions every group member from the definition's {@link Entity#members()}, so camel +
     * camel_husk share the same canvas.
     */
    private static @NotNull CanvasSolver.BoundsScope boundsScopeFor(@NotNull EntityOptions.FitMode mode) {
        return mode == EntityOptions.FitMode.GROUP_BOUNDS
            ? CanvasSolver.BoundsScope.GROUP_UNION
            : CanvasSolver.BoundsScope.ENTITY_UNION;
    }

    /**
     * Computes the screen-space bounds for the active {@link CanvasSolver.BoundsScope}. The two existing
     * primitives ({@link #computeUnionScreenBounds} for one entity, {@link
     * #computeGroupUnionScreenBounds} for the whole group) stay unchanged; this method is
     * the single dispatch point so canvas-sizing and centring agree on which bounds to use.
     */
    private @NotNull Box computeScreenBoundsFor(
        @NotNull CanvasSolver.BoundsScope scope,
        @NotNull String entityId,
        @NotNull Entity definition,
        @NotNull PosePlayer.PosedFrames posed,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer texture,
        int tick
    ) {
        return switch (scope) {
            case ENTITY_UNION -> computeUnionScreenBounds(definition, transform, modelScale, texture, tick,
                boundsBlockOverlays(definition, this.context.findEntity(entityId).orElse(null)));
            case GROUP_UNION ->
                computeGroupUnionScreenBounds(entityId, definition, posed, transform, modelScale, texture, tick);
        };
    }

    /**
     * Unions {@link #computeScreenBoundsFor} across every frame the schedule samples - the canvas has
     * to hold each of them, and it is sized once.
     *
     * <p>Wrapped from outside that dispatch rather than folded into it, so the two unions compose:
     * a frame is measured against whichever scope the fit mode asked for, and neither the group union
     * nor this one swallows the other.
     *
     * <p>Each frame is measured through its own posed mesh and its own tick's texture, which is what
     * a frame actually draws. A one-frame schedule is one measurement of the frame it draws, at the
     * start tick and through the texture already resolved there - the same call, with the same
     * arguments, that sizing has always made.
     *
     * @param scope whether a frame measures this entity alone or its whole canvas group
     * @param entityId the namespaced id the group scope resolves its members from
     * @param resolved the age / carried-resolved definition being measured
     * @param options the render options supplying the texture precedence
     * @param posed the per-render memo every frame's subject - and every member and coat measured
     *     beside it - is posed through
     * @param timeline the frame schedule whose sample ticks are measured
     * @param transform the exact render orientation the silhouette is measured through
     * @param modelScale the per-entity render scale the bounds are taken at
     * @param startTexture the base texture already resolved at the schedule's start tick
     * @return the union of every frame's projected silhouette
     */
    private @NotNull Box computeScreenBoundsAcrossFrames(
        @NotNull CanvasSolver.BoundsScope scope,
        @NotNull String entityId,
        @NotNull Entity resolved,
        @NotNull EntityOptions options,
        @NotNull PosePlayer.PosedFrames posed,
        @NotNull Timeline.TickTimeline timeline,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer startTexture
    ) {
        int startTick = timeline.tickAt(0);
        Box bounds = computeScreenBoundsFor(scope, entityId, posed.at(startTick),
            posed, transform, modelScale, startTexture, startTick);
        for (int frame = 1; frame < timeline.frames(); frame++) {
            int tick = timeline.tickAt(frame);
            PixelBuffer frameTexture = resolveEntityTexture(resolved, options, tick).orElse(startTexture);
            bounds = bounds.union(computeScreenBoundsFor(scope, entityId, posed.at(tick),
                posed, transform, modelScale, frameTexture, tick));
        }
        return bounds;
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
        DebugChannel.baseBounds(bounds.minX(), bounds.maxX(), bounds.minY(), bounds.maxY());
        for (Entity.OverlayLayer overlay : definition.overlays()) {
            if (overlay.model().getBones().isEmpty()) continue;
            // Overlays flagged skipBounds (LlamaDecorLayer-style equipment-driven overlays) still
            // render but don't contribute to bounds, mirroring the vanilla harness's
            // NO_RENDER_LAYER_SUFFIXES treatment of those layer classes.
            if (overlay.skipBounds()) continue;
            Box overlayBounds = EntityGeometryKit.computeScreenBounds(overlay.model(), transform, modelScale, texture);
            DebugChannel.overlayBounds(overlay.textureRef().orElse("<unset>"),
                overlayBounds.minX(), overlayBounds.maxX(), overlayBounds.minY(), overlayBounds.maxY());
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
     * vanilla harness's {@code EntitySweep.prepare} pre-pass so grouped siblings
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
     * membership the generators bake onto every member of a group, clustered on shared primary
     * geometry and written as the same self-inclusive list on each, so the reader joins nothing. A
     * singleton carries an empty member list, so this method collapses to
     * {@link #computeUnionScreenBounds} for non-group-bearing entities.
     * <p>
     * <b>This list and the harness's {@code EntityRoster.FAMILY_OVERRIDES} are one set spelled
     * twice, and they have to stay that way.</b> A member is measured in the pose the render draws,
     * so a member the harness measures apart would union a canvas vanilla keeps separate - worth 333
     * of animated delta over the piglin family alone when the two sets disagreed. There is no
     * mechanism holding them together: the tooling derives this one from shared primary geometry and
     * the harness declares its one by hand, so a group added on either side is added on both, and
     * the evidence that they agree is that the two canvases agree.
     */
    private @NotNull Box computeGroupUnionScreenBounds(
        @NotNull String entityId,
        @NotNull Entity definition,
        @NotNull PosePlayer.PosedFrames posed,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer texture,
        int tick
    ) {
        Entity base = this.context.findEntity(entityId).orElse(null);
        Box bounds = computeUnionScreenBounds(definition, transform, modelScale, texture, tick,
            boundsBlockOverlays(definition, base));
        // Option-encoded variant coats live on the base definition's axes.variants rather than as
        // separate group-member rows, so union each coat's silhouette here. A no-op while variant is
        // id-encoded (each coat is a member row measured below) or the model has no variant axis.
        bounds = unionVariantSilhouettes(bounds, base, posed, transform, tick);
        ConcurrentList<String> members = definition.members();
        if (members.size() <= 1) return bounds;
        for (String memberId : members) {
            if (memberId.equals(entityId)) continue;
            Entity memberDef = this.context.findEntity(memberId).orElse(null);
            if (memberDef == null || memberDef.model().getBones().isEmpty()) continue;
            Optional<PixelBuffer> memberTexture = resolveGroupMemberTexture(memberDef);
            if (memberTexture.isEmpty()) continue;
            float memberScale = memberDef.rendererScale();
            // Posed through the shared memo at the tick being measured, like the coats above and for
            // the same reason: a canvas is a union, so every silhouette in it is measured in the
            // pose the render draws. Measuring a member posed is only right where the harness unions
            // the same one, and it answers that from EntityRoster.FAMILY_OVERRIDES, which this list
            // is held to.
            Box memberBounds = computeUnionScreenBounds(posed.at(memberDef, tick), transform,
                memberScale, memberTexture.get(), tick, memberDef.blockOverlays());
            bounds = bounds.union(memberBounds);
            bounds = unionVariantSilhouettes(bounds, memberDef, posed, transform, tick);
        }
        return bounds;
    }

    /**
     * Unions the screen-space silhouettes of a definition's option-encoded variant coats
     * ({@link Entity.Axes#variant()}) into {@code bounds}, each measured at its
     * own coat texture + render scale (mirroring the group-member walk). A no-op when the definition is
     * absent or carries no variant coats (id-encoded / non-variant models).
     * <p>
     * Every coat measures its block overlays as the DEFAULT coat draws them - see
     * {@link #boundsBlockOverlays} - so a family whose coats differ only in which block they carry
     * keeps one canvas.
     */
    private @NotNull Box unionVariantSilhouettes(
        @NotNull Box bounds, @Nullable Entity definition, @NotNull PosePlayer.PosedFrames posed,
        @NotNull Matrix4f transform, int tick) {

        if (definition == null) return bounds;
        for (Entity coat : definition.axes().variant().options().values()) {
            if (coat.model().getBones().isEmpty()) continue;
            Optional<PixelBuffer> coatTexture = resolveGroupMemberTexture(coat);
            if (coatTexture.isEmpty()) continue;
            // Posed through the shared memo at the tick being measured - a canvas is a union, so
            // every silhouette in it is measured in the pose the render draws.
            bounds = bounds.union(computeUnionScreenBounds(posed.at(coat, tick), transform,
                coat.rendererScale(), coatTexture.get(), tick, boundsBlockOverlays(coat, definition)));
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
    private @NotNull ConcurrentList<EquippedOverlay> resolveEquippedOverlays(
        @NotNull Entity resolved,
        @NotNull AppearanceOptions appearance,
        int tick
    ) {
        return resolved.layers().equipment()
            .stream()
            .filter(equipment -> !equipment.model().getBones().isEmpty())
            .flatMap(equipment -> appearance.equipmentMaterial(equipment.slot())
                .flatMap(equipment::assetFor)
                .flatMap(assetId -> EquipmentKit.composite(this.context, assetId, equipment.layerType(),
                    appearance.tint(TintAxis.EQUIPMENT).map(DyeColor::argb), CitResult.NONE, OptionalInt.of(tick)))
                .map(texture -> new EquippedOverlay(equipment, texture))
                .stream())
            .collect(Concurrent.toWideUnmodifiableList());
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
    ) {

        /**
         * The mesh this overlay draws on a posed subject - the posed subject's overlay on the same
         * slot, which is unique within a family, or the resting mesh where the subject carries none.
         *
         * @param posedSubject the subject as its style leaves it at one tick
         * @return the mesh this overlay draws at that tick
         */
        @NotNull EntityMesh meshIn(@NotNull Entity posedSubject) {
            for (Entity.EquipmentOverlay posedOverlay : posedSubject.layers().equipment())
                if (posedOverlay.slot().equals(this.overlay.slot())) return posedOverlay.model();
            return this.overlay.model();
        }

    }

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
