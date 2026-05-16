package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.TextureEngine;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.kit.ArmorKit;
import lib.minecraft.renderer.kit.BlockModelGeometryKit;
import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.kit.GlintKit;
import lib.minecraft.renderer.options.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders mob entities as isometric 3D icons from the Java-derived entity pipeline
 * ({@code entity_models_java.json} + {@code entity_geometry_java.json}, produced by
 * {@code ToolingJavaEntityModels} from the vanilla client jar) via {@link EntityGeometryKit}'s
 * Y-down engine path. Texture resolution flows through the vanilla pack only via
 * {@link RendererContext#resolveTexture} - the renderer renders Java throughput end-to-end and
 * surfaces missing textures as missing entities rather than papering over them with bedrock
 * fallbacks.
 */
@RequiredArgsConstructor
public final class EntityRenderer implements Renderer<EntityOptions> {

    /**
     * Texel resolution (image-pixels per Minecraft block-unit). Mirrors
     * {@code HarnessConfig.PIXELS_PER_BLOCK} in the sibling vanilla-reference-harness so both
     * pipelines render at the same screen scale. Override with {@code -Drefharness.pixelsPerBlock=N}.
     * Vanilla model parts author cubes in entity-pixels (16 entity-pixels = 1 block); the
     * canvas-pixels-per-entity-pixel ratio is therefore {@code PIXELS_PER_BLOCK / 16}.
     */
    private static final int PIXELS_PER_BLOCK = Integer.getInteger("refharness.pixelsPerBlock", 256);

    /**
     * Hard cap (pixels) on either side of the rendered canvas. Entities whose screen-space
     * bounds × {@link #PIXELS_PER_BLOCK} would exceed this on the longer axis (ender_dragon,
     * giant) are scaled down uniformly so the longer side equals the cap; the shorter side and
     * the per-pixel scale shrink proportionally so the entity still fits within the canvas.
     * Mirrors {@code HarnessConfig.MAX_CANVAS_SIZE}; override with {@code -Drefharness.maxCanvasSize=N}.
     */
    private static final int MAX_CANVAS_SIZE = Integer.getInteger("refharness.maxCanvasSize", 1024);

    /**
     * Effective entity-render scale per entity id. The single remaining vanilla scale source the
     * pipeline can't bake into geometry: per-renderer {@code scale(state, ps)} overrides like
     * {@code WitherBossRenderer} which bakes a constant {@code scale(2, 2, 2)} into its submit
     * chain.
     * <p>
     * {@code MeshTransformer.scaling(F)} wraps (polar_bear 1.2, ghast 4.5, happy_ghast 4.0,
     * cat 0.8, horse 1.1, giant 6.0, villager / witch / illager-family 0.9375, husk 1.0625,
     * wither_skeleton 1.2, elder_guardian 2.35, donkey 0.87, mule 0.92, ...) are now baked into
     * bone {@code pivot} + {@code scale} fields at tooling time across three patterns:
     * <ul>
     * <li>inline {@code .apply(MeshTransformer.scaling(F))} in the model's createBodyLayer
     *     (polar_bear, ghast, happy_ghast)</li>
     * <li>static-field MeshTransformer in the model class's {@code <clinit>}, applied via
     *     getstatic in another factory (elder_guardian's {@code ELDER_GUARDIAN_SCALE})</li>
     * <li>LayerDefinitions-level chains: either {@code .apply(getstatic <Y>_TRANSFORMER)} on
     *     class fields (cat) or {@code .apply(aload <slot>)} from a local-slot scaling result
     *     (horse, villager, husk, giant, ...)</li>
     * </ul>
     * Same applies to the {@code state.scale} sourced from {@code state.scale} - notably
     * {@code Giant.scale=6} which we used to model here but is now baked via the LayerDefinitions
     * pattern (the F=6.0 lives on a local slot in {@code LayerDefinitions.createRoots} applied to
     * the {@code GIANT} layer via {@code .apply(MeshTransformer)}). Unlisted entities default to 1.
     * Excluded baby / conditional cases since the static renderer never renders babies.
     */
    private static final @NotNull Map<String, Float> RENDERER_SCALE_OVERRIDES = Map.ofEntries(
        Map.entry("minecraft:wither", 2.0f)
    );

    /**
     * Per-entity-id model-space pre-transform that mirrors a vanilla
     * {@code setupRotations(state, ps, bodyRot, scale)} override producing a different LER chain
     * than the default {@code rotateY(180 - bodyRot)}. Currently always empty: the frozen-state
     * audit (see {@code notes/JAVA_PIPELINE_RESEARCH.md} "A4 audit") found 11 of the 14 overriders
     * collapse to the default under our zero-tick state, and empirical parity (2026-05-14)
     * showed the remaining three are also no-ops for our auto-centered pipeline:
     * <ul>
     * <li>{@code SquidRenderer} (translates {@code 0.5} / {@code -1.2}) and
     *     {@code PufferfishRenderer} (translate {@code 0.08}) - the kit's family-fit centring
     *     (anchor = {@code inverse-iso(screen-midpoint)}) absorbs pure translates; applying them
     *     as a {@code modelAnchor} shift uncentred squid (6.26 -> 145.23) and reverting restored
     *     the original. Vanilla's harness family-fit centring does the same cancellation.</li>
     * <li>{@code ShulkerRenderer} with default {@code attachFace=DOWN}: collapses to "no
     *     rotateY(180)" which would require a 180° yaw addend. Empirical test moved shulker
     *     11.50 -> 22.97 - either the model is rotationally symmetric enough that the wrong
     *     direction reads correctly, or the diff exposes a separate texture-seam mismatch.
     *     Leaving the wiring in place against future state-dependent overrides (e.g. when we
     *     start respecting {@code attachFace} for placed shulkers, or {@code lieDownAmount}
     *     for tame cats / wolves) - those will be NON-translation transforms that actually
     *     affect the rendered pixels.</li>
     * </ul>
     * Unlisted entities get the identity override.
     */
    private record SetupRotationsOverride(@NotNull Vector3f modelAnchorShift, float yawDegrees) {
        static final @NotNull SetupRotationsOverride IDENTITY = new SetupRotationsOverride(Vector3f.ZERO, 0f);
    }

    private static final @NotNull Map<String, SetupRotationsOverride> SETUP_ROTATIONS_OVERRIDES = Map.of();

    /** Renderer context for texture resolution + isometric engine setup; not used for entity lookup. */
    private final @NotNull RendererContext context;

    /**
     * Java-derived entity definitions keyed by namespaced id, loaded via
     * {@link EntityModelLoader#load()}. Replaces the bedrock-side {@code context.findEntity()}
     * call so the two pipelines stay decoupled.
     */
    private final @NotNull Map<String, EntityModelLoader.EntityDefinition> javaEntities;

    @Override
    public @NotNull ImageData render(@NotNull EntityOptions options) {
        if (options.getEntityId().isEmpty())
            return RenderEngine.staticFrame(PixelBuffer.create(1, 1));

        EntityModelLoader.EntityDefinition definition = this.javaEntities.get(options.getEntityId().get());
        if (definition == null)
            return RenderEngine.staticFrame(PixelBuffer.create(1, 1));

        Optional<PixelBuffer> texture = resolveEntityTexture(definition, options);
        if (texture.isEmpty())
            return RenderEngine.staticFrame(PixelBuffer.create(1, 1));

        EntityModelData model = definition.model();
        if (model.getBones().isEmpty())
            return RenderEngine.staticFrame(PixelBuffer.create(1, 1));

        // Combined bounds across the base entity AND every overlay so the shared auto-fit
        // window contains both. Slime's outer shell (8x8x8) extends beyond the inner body
        // (6x6x6); without including the shell in the bounds the auto-fit normalizes to the
        // inner body and the shell renders larger-than-window. Block-overlay rendering applies
        // its own transform chain after entity-fit normalization, so its bounds aren't included
        // here - only model-overlay (cube tree) geometries that share the entity's frame.
        Box baseBounds = EntityGeometryKit.computeBounds(model);
        for (EntityModelLoader.OverlayLayer overlay : definition.overlays()) {
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

        SetupRotationsOverride override = SETUP_ROTATIONS_OVERRIDES.getOrDefault(
            options.getEntityId().get(), SetupRotationsOverride.IDENTITY);

        EulerRotation user = options.getRotation();
        EulerRotation effective = new EulerRotation(
            user.pitch(),
            user.yaw() + model.getInventoryYRotation() + override.yawDegrees(),
            user.roll()
        );
        // Apply the per-entity scale override (vanilla's combined renderer-scale + state-scale)
        // by scaling the bounds before sizing the canvas. With K-scaled bounds, canvas dimensions
        // grow K x and the projected entity also grows K x so the entity's screen footprint
        // matches the harness's submit-time scale chain. The kit's K x of model vertices happens
        // via the {@code modelScale} parameter on the new buildTriangles overload.
        float modelScale = RENDERER_SCALE_OVERRIDES.getOrDefault(options.getEntityId().get(), 1.0f);
        Box scaledBounds = scaleBox(baseBounds, modelScale);

        // Match the vanilla-reference-harness's family-fit algorithm (see
        // vanilla-reference-harness's HarnessConfig + EntitySweeper.computeFamilyFits): canvas
        // dimensions are the entity's screen-space bounds (in Minecraft block-units, post-iso)
        // multiplied by PIXELS_PER_BLOCK, capped uniformly so the longer side does not exceed
        // MAX_CANVAS_SIZE. The kit then scales model coordinates to NDC such that the rasterizer
        // projects each entity-pixel-unit to {@code PIXELS_PER_BLOCK/16} canvas pixels - native
        // resolution, no auto-fit. EntityOptions#getOutputSize is intentionally ignored here so
        // the Java pipeline matches the harness's PNG dimensions byte-for-byte where the same
        // entity is being rendered in both projects.
        CanvasFit fit = computeCanvasFit(options.getEntityId().get(), definition, effective, modelScale, texture.get());
        PixelBuffer buffer = PixelBuffer.create(fit.canvasW(), fit.canvasH());

        // Centre the silhouette on the canvas. The kit subtracts a model-space "anchor" point
        // from each vertex; whatever point we pass becomes NDC origin, which the rasterizer
        // maps to canvas-centre. Naively passing {@code scaledBounds.centre()} (the model-space
        // AABB centre) over-pads non-brick silhouettes: cod's outer AABB extends z=[-4,11] but
        // most cubes hug z=[0,7], so {@code iso(aabb_centre)} lands several pixels off the
        // tight silhouette midpoint. Inverse-projecting the screen-space silhouette midpoint
        // through the iso transform gives the model-space point whose iso image IS the
        // silhouette midpoint - using it as the kit anchor centres the silhouette exactly.
        // Per-entity setupRotations overrides translate the model vertex by
        // {@code override.modelAnchorShift} (the kit subtracts modelAnchor from every vertex, so
        // subtracting the shift from the anchor adds it to every vertex). For squid that's a
        // {@code (0, +11.2, 0)} pixel pre-translate; pufferfish gets {@code (0, -1.28, 0)};
        // shulker has zero translate but a 180° yaw addend folded into {@code effective} above.
        Vector3f modelAnchor = computeCentreAnchor(options.getEntityId().get(), definition, effective, modelScale, fit, texture.get())
            .subtract(override.modelAnchorShift());

        EntityGeometryKit.BuildResult buildResult = EntityGeometryKit.buildTriangles(
            model, texture.get(), modelAnchor, false, fit.ndcScale(), modelScale);
        if (buildResult.triangles().isEmpty())
            return RenderEngine.staticFrame(buffer);

        ConcurrentList<VisibleTriangle> triangles = buildResult.triangles();

        for (EntityModelLoader.OverlayLayer overlay : definition.overlays()) {
            if (overlay.model().getBones().isEmpty()) continue;
            Optional<PixelBuffer> overlayTex = overlay.textureRef().isPresent()
                ? this.context.resolveTexture("minecraft:entity/" + overlay.textureRef().get())
                : Optional.of(texture.get());
            if (overlayTex.isEmpty()) continue;
            triangles.addAll(EntityGeometryKit.buildTriangles(
                overlay.model(), overlayTex.get(), modelAnchor, overlay.emissive(), fit.ndcScale(), modelScale).triangles());
        }

        // Phase E.5: block-model overlays (mooshroom mushrooms, copper-golem flower, etc).
        // The vanilla render layer renders a block model at a transform-stack-applied position
        // on top of the entity body. Each row carries the block id, an optional entity bone the
        // overlay attaches to (so head-mounted overlays follow the head's bind pose), and the
        // ordered list of pose-stack ops the layer issues between {@code pushPose} / {@code
        // popPose}. See {@link EntityModelLoader.BlockOverlayLayer}.
        if (!definition.blockOverlays().isEmpty()) {
            Matrix4f entityFit = EntityGeometryKit.buildEntityFitMatrix(modelAnchor, fit.ndcScale() * modelScale);
            for (EntityModelLoader.BlockOverlayLayer blockOverlay : definition.blockOverlays())
                triangles.addAll(buildBlockOverlayTriangles(blockOverlay, model, entityFit));
        }

        IsometricEngine engine = IsometricEngine.entityStandard(this.context);
        triangles.addAll(ArmorKit.buildEntityArmor3D(buildResult.boneBounds(),
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots(), engine));

        engine.rasterize(triangles, buffer, PerspectiveParams.ISOMETRIC_BLOCK, effective);

        if (options.isAntiAlias())
            buffer.applyFxaa();

        boolean enchanted = ArmorKit.hasEnchantedArmor(
            options.getHelmet(), options.getChestplate(),
            options.getLeggings(), options.getBoots()
        );
        return engine.finaliseWithGlint(buffer, enchanted, GlintKit.GlintOptions.armorDefault(30));
    }

    /**
     * Resolves the entity texture. Precedence: an explicit
     * {@link EntityOptions#getTextureId() texture id on options} (user override; looked up
     * against the Java atlas via the pack stack) &gt; the entity's own
     * {@link EntityModelLoader.EntityDefinition#textureRef() texture_ref} resolved against the
     * vanilla pack at {@code minecraft:entity/<ref>}.
     */
    private @NotNull Optional<PixelBuffer> resolveEntityTexture(
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull EntityOptions options
    ) {
        if (options.getTextureId().isPresent())
            return this.context.resolveTexture(options.getTextureId().get());

        if (definition.textureRef().isPresent()) {
            String ref = definition.textureRef().get();
            // Vanilla pack only - the Java pipeline tests Java throughput end-to-end. The
            // bedrock cache exists for the bedrock-derived sibling pipeline; mixing the two
            // here masks Java-side gaps (overlay PNGs missing from bedrock, HD resamples
            // sampled with 64x64 UVs reading only the upper-left quadrant, etc.). When a
            // texture isn't in the vanilla pack the entity drops out, which surfaces the
            // gap rather than papering over it.
            Optional<PixelBuffer> loaded = this.context.resolveTexture("minecraft:entity/" + ref);
            if (loaded.isPresent() && definition.forceOpaque())
                return Optional.of(bumpAlphaToOpaque(loaded.get()));
            return loaded;
        }
        return Optional.empty();
    }

    /**
     * Builds the rasterizer-ready triangles for one {@link EntityModelLoader.BlockOverlayLayer}.
     * Composes the overlay's transform chain (in vanilla block units) with the optional bone
     * anchor (whose pivot+rotation comes from the entity geometry, divided by 16 to convert from
     * pixel-units to block-units), then converts back to entity pixel-units (x16) and applies
     * the entity-fit normalization so the block sits in the same auto-fit window as the entity
     * body. Missing block / texture refs return an empty list rather than failing the render.
     */
    private @NotNull ConcurrentList<VisibleTriangle> buildBlockOverlayTriangles(
        @NotNull EntityModelLoader.BlockOverlayLayer overlay,
        @NotNull EntityModelData model,
        @NotNull Matrix4f entityFit
    ) {
        Optional<Block> block = this.context.findBlock(overlay.blockId());
        if (block.isEmpty()) return Concurrent.newList();

        // Pre-load each face's texture by dereferencing #variable bindings against the model's
        // texture map, exactly mirroring {@code BlockRenderer.Isometric3D.buildFromBlockElements}.
        // Faces whose ref still resolves to a {@code #} after dereference (broken bindings) skip
        // texture loading; the kit treats them as no-texture faces.
        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        ConcurrentMap<String, String> variables = block.get().getModel().getTextures();
        for (ModelElement element : block.get().getModel().getElements()) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                String resolvedId = TextureEngine.dereferenceVariable(ref, variables);
                if (resolvedId.startsWith("#")) continue;
                Optional<PixelBuffer> tex = this.context.resolveTexture(resolvedId);
                tex.ifPresent(pixelBuffer -> faceTextures.put(ref, pixelBuffer));
            }
        }
        if (faceTextures.isEmpty()) return Concurrent.newList();

        ConcurrentList<VisibleTriangle> blockTris = BlockModelGeometryKit.buildFromElements(
            block.get().getModel().getElements(), faceTextures, ColorMath.WHITE);
        if (blockTris.isEmpty()) return Concurrent.newList();

        // Compose the per-overlay transform matrix in vanilla block units. PoseStack ops apply
        // in bytecode order to the LOCAL frame, which means each new op goes to the LEFT in our
        // row-vector composition - pre-multiply each op so the result {@code M = opN * ... * op1}
        // produces the same final-vertex position as vanilla's {@code M_col = T_1 * R_2 * ...}.
        Matrix4f blockUnitChain = Matrix4f.IDENTITY;

        // Optional bone anchor: prepend the bone's {@code translateAndRotate} equivalent. The
        // bone's pivot is in entity pixel-units, so divide by 16 to get block units; the rotation
        // applies as Z-Y-X around the (post-translation) origin.
        if (overlay.attachedBone() != null) {
            Vector3f pivot = EntityGeometryKit.resolveBonePivot(model, overlay.attachedBone());
            Matrix4f tBone = Matrix4f.createTranslation(
                pivot.x() / 16f, pivot.y() / 16f, pivot.z() / 16f);
            blockUnitChain = tBone.multiply(blockUnitChain);
            EntityModelData.Bone bone = model.getBones().get(overlay.attachedBone());
            if (bone != null) {
                EulerRotation rot = bone.getRotation();
                if (rot.pitch() != 0f || rot.yaw() != 0f || rot.roll() != 0f) {
                    Matrix4f rotMat = Matrix4f.createRotationZ(rot.rollRadians())
                        .multiply(Matrix4f.createRotationY(rot.yawRadians()))
                        .multiply(Matrix4f.createRotationX(rot.pitchRadians()));
                    blockUnitChain = rotMat.multiply(blockUnitChain);
                }
            }
        }

        for (EntityModelLoader.TransformOp op : overlay.transforms()) {
            Matrix4f opMat = switch (op) {
                case EntityModelLoader.Translate t -> Matrix4f.createTranslation(t.x(), t.y(), t.z());
                case EntityModelLoader.RotateY r -> Matrix4f.createRotationY((float) Math.toRadians(r.degrees()));
                case EntityModelLoader.Scale s -> Matrix4f.createScale(s.x(), s.y(), s.z());
            };
            blockUnitChain = opMat.multiply(blockUnitChain);
        }

        // Vanilla expects block-model vertices in {@code [0, 1]} (corner-at-origin) since the
        // last pose op {@code translate(-0.5, -0.5, -0.5)} re-centers them at origin before the
        // submit. {@link BlockModelGeometryKit#buildFromElements} pre-centers the cube to
        // {@code [-0.5, 0.5]} for inventory/atlas use, so add 0.5 on each axis to recover the
        // corner-at-origin convention before the chain applies. Pre-multiplied AFTER the chain
        // so that, in row-vector composition, this op is the LEFTMOST in {@code M = T_uncenter
        // * ... * pose_ops} - making it the first applied to the input vertex.
        Matrix4f uncenter = Matrix4f.createTranslation(0.5f, 0.5f, 0.5f);
        blockUnitChain = uncenter.multiply(blockUnitChain);

        // Convert block-unit positions to entity pixel-units (x16), then run the entity-fit
        // normalization to land in the rasterizer's working frame.
        Matrix4f blockToPixel = Matrix4f.createScale(16f, 16f, 16f);
        Matrix4f finalMatrix = blockUnitChain.multiply(blockToPixel).multiply(entityFit);

        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle tri : blockTris) {
            out.add(new VisibleTriangle(
                Vector3f.transform(tri.position0(), finalMatrix),
                Vector3f.transform(tri.position1(), finalMatrix),
                Vector3f.transform(tri.position2(), finalMatrix),
                tri.uv0(), tri.uv1(), tri.uv2(),
                tri.texture(), tri.tintArgb(),
                Vector3f.transformNormal(tri.normal(), finalMatrix),
                tri.shading(), tri.cullBackFaces(), tri.emissive()
            ));
        }
        return out;
    }

    /**
     * Mirrors the vanilla-reference-harness's family-fit math: walks every cube's vertices
     * through the iso transform (matching {@link EntityGeometryKit#computeScreenBounds the
     * harness's per-cube measurement}), takes the tight screen-space extent in entity-pixel-
     * units, then sizes the canvas to {@code (extent * PIXELS_PER_BLOCK / 16)} pixels per axis
     * with a uniform shrink to keep the longer side at or below {@link #MAX_CANVAS_SIZE}.
     *
     * <p>Earlier this function projected only the 8 corners of the model's outer AABB, which
     * over-padded canvases for non-brick-shaped entities (cod, salmon, ender_dragon...) where
     * the AABB's extent is much larger than the union of actual cube extents. The harness walks
     * each cube vertex; we now do the same so canvas dimensions agree to within the bounds
     * walker's discretisation.
     *
     * <p>Returned {@link CanvasFit#ndcScale} is computed from the inverse of the rasterizer's
     * own projection ({@code screen_px = ndc * min(canvasW, canvasH) * projectionScale}), so
     * applying it as the kit's model-units-to-NDC scale produces the desired pixels-per-block
     * ratio at the rasterization step.
     */
    private @NotNull CanvasFit computeCanvasFit(
        @NotNull String entityId,
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull EulerRotation userRotation,
        float modelScale,
        @NotNull PixelBuffer texture
    ) {
        Matrix4f transform = composeIsoTransform(userRotation);
        Box screenBounds = computeFamilyUnionScreenBounds(entityId, definition, transform, modelScale, texture);
        float extentX = Math.max(0f, screenBounds.maxX() - screenBounds.minX());
        float extentY = Math.max(0f, screenBounds.maxY() - screenBounds.minY());
        float pxPerEntityUnit = PIXELS_PER_BLOCK / 16f;
        int rawW = Math.max(1, (int) Math.ceil(extentX * pxPerEntityUnit));
        int rawH = Math.max(1, (int) Math.ceil(extentY * pxPerEntityUnit));
        int longest = Math.max(rawW, rawH);
        float shrink = longest > MAX_CANVAS_SIZE ? (float) MAX_CANVAS_SIZE / longest : 1f;
        int canvasW = Math.max(1, (int) Math.ceil(rawW * shrink));
        int canvasH = Math.max(1, (int) Math.ceil(rawH * shrink));
        float effectivePxPerEntityUnit = pxPerEntityUnit * shrink;
        int minDim = Math.min(canvasW, canvasH);
        float ndcScale = effectivePxPerEntityUnit / (minDim * PerspectiveParams.ISOMETRIC_BLOCK.projectionScale());
        return new CanvasFit(canvasW, canvasH, ndcScale);
    }

    /**
     * Computes the model-space point that, after iso transformation, lands at the screen-space
     * silhouette midpoint - so passing it as the kit's centre subtraction places the silhouette
     * tightly centred on the canvas.
     *
     * <p>Without this, the kit subtracts the model-space AABB centre, which after iso projects
     * to a point that is NOT the silhouette midpoint for non-brick-shaped entities (long fish,
     * dragons with tails, T-pose humanoids, etc). The result was visible right/down shift in
     * the canvas, surfaced by {@code TestEntityParityVanilla}'s coverage diff lens as
     * vanilla-only pixels along the left/top silhouette edge and java-only pixels along the
     * right/bottom edge.
     */
    private @NotNull Vector3f computeCentreAnchor(
        @NotNull String entityId,
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull EulerRotation userRotation,
        float modelScale,
        @NotNull CanvasFit fit,
        @NotNull PixelBuffer texture
    ) {
        Matrix4f isoTransform = composeIsoTransform(userRotation);
        Box screenBounds = computeFamilyUnionScreenBounds(entityId, definition, isoTransform, modelScale, texture);
        float sxMid = (screenBounds.minX() + screenBounds.maxX()) * 0.5f;
        float syMid = (screenBounds.minY() + screenBounds.maxY()) * 0.5f;
        float szMid = (screenBounds.minZ() + screenBounds.maxZ()) * 0.5f;
        Matrix4f isoInverse = composeIsoInverse(userRotation);
        return Vector3f.transform(new Vector3f(sxMid, syMid, szMid), isoInverse);
    }

    /**
     * Unions the screen-space bounds of the base entity model with each non-empty entity-model
     * overlay. Vanilla's family-fit pre-pass walks every {@link
     * net.minecraft.client.renderer.entity.layers.RenderLayer}'s {@code EntityModel}-typed field
     * through the same pose stack as the primary model and expands the bounds. Mirrors
     * {@code EntityFrameRenderer.walkLayerExtents} in the vanilla-reference-harness.
     * <p>
     * Block-model overlays (mooshroom mushrooms, copper-golem flower, iron-golem flower) are
     * deliberately NOT included - vanilla's family-fit reflection only matches {@code Model<?>}
     * fields, so block-rendering RenderLayer subclasses don't contribute either. If they did,
     * mooshroom's mushrooms would extend the canvas; matching vanilla means accepting the
     * mushrooms render slightly past the canvas edge (same as vanilla). Empirically: including
     * block overlays at unit-cube {@code [-0.5, 0.5]^3} bounds expanded mooshroom canvas
     * 442x482 -> 505x726 vs vanilla's 442x482; reverting matches vanilla's canvas exactly.
     */
    private static @NotNull Box computeUnionScreenBounds(
        @NotNull EntityModelLoader.EntityDefinition definition,
        @NotNull Matrix4f transform,
        float modelScale,
        @NotNull PixelBuffer texture
    ) {
        Box bounds = EntityGeometryKit.computeScreenBounds(definition.model(), transform, modelScale, texture);
        for (EntityModelLoader.OverlayLayer overlay : definition.overlays()) {
            if (overlay.model().getBones().isEmpty()) continue;
            Box overlayBounds = EntityGeometryKit.computeScreenBounds(overlay.model(), transform, modelScale, texture);
            bounds = unionBoxes(bounds, overlayBounds);
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
     * options-override texture), apply the variant's {@link #RENDERER_SCALE_OVERRIDES} model
     * scale, run {@code computeUnionScreenBounds}, union the result. Family members whose
     * texture / definition can't be resolved (missing PNG, unloaded variant) are skipped - the
     * union degrades to the available members rather than throwing.
     * <p>
     * Members are sourced from {@code EntityModelLoader.loadFamilies()} - {@code variant_of}
     * for variant-of-same-entity groupings plus {@code FAMILY_OVERRIDES} for cross-entity ones
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
            float memberScale = RENDERER_SCALE_OVERRIDES.getOrDefault(memberId, 1.0f);
            Box memberBounds = computeUnionScreenBounds(memberDef, transform, memberScale, memberTexture.get());
            bounds = unionBoxes(bounds, memberBounds);
        }
        return bounds;
    }

    /**
     * Resolves a family-member's default texture for the family-fit bound walk. Unlike
     * {@link #resolveEntityTexture} this ignores {@code options.textureId} (family-fit measures
     * each variant's OWN bound, not the current-render texture override) and skips the
     * {@code forceOpaque} alpha bump (the bound walker only checks alpha != 0, so partial-alpha
     * texels contribute either way).
     */
    private @NotNull Optional<PixelBuffer> resolveFamilyMemberTexture(@NotNull EntityModelLoader.EntityDefinition definition) {
        if (definition.textureRef().isEmpty()) return Optional.empty();
        return this.context.resolveTexture("minecraft:entity/" + definition.textureRef().get());
    }

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
     * Inverse of {@link #composeIsoTransform}. The composite is
     * {@code FLIP_Y × modelRotation × engine_camera_row} which has det=-1 (FLIP_Y) × det=+1
     * (rotation) × det=-1 (engine_camera_row from two scale(1,1,-1) outer factors with one
     * R_Y(45°) × R_X(210°) inside) actually det(engine_camera_row) = +1 so overall det=-1. But
     * the composite is still invertible; we build the inverse as the reversed-order product of
     * factor inverses: {@code engine_camera_row^-1 × modelRotation^-1 × FLIP_Y^-1}. FLIP_Y is
     * self-inverse (scale(1,-1,1) twice = identity).
     */
    private static @NotNull Matrix4f composeIsoInverse(@NotNull EulerRotation userRotation) {
        EulerRotation iso = EulerRotation.STANDARD_ISO_ENTITY;
        // engine_camera_row = scale(1,1,-1) × R_Y(yaw) × R_X(pitch) × scale(1,1,-1) × scale(1,-1,1).
        // Inverse: scale(1,-1,1) × scale(1,1,-1) × R_X(-pitch) × R_Y(-yaw) × scale(1,1,-1).
        Matrix4f cameraInverse = Matrix4f.createScale(1f, -1f, 1f)
            .multiply(Matrix4f.createScale(1f, 1f, -1f))
            .multiply(Matrix4f.createRotationX(-iso.pitchRadians()))
            .multiply(Matrix4f.createRotationY(-iso.yawRadians()))
            .multiply(Matrix4f.createScale(1f, 1f, -1f));
        Matrix4f modelRotInverse = (userRotation.pitch() == 0f && userRotation.yaw() == 0f && userRotation.roll() == 0f)
            ? Matrix4f.IDENTITY
            : Matrix4f.createRotationZ(-userRotation.rollRadians())
                .multiply(Matrix4f.createRotationX(-userRotation.pitchRadians()))
                .multiply(Matrix4f.createRotationY(-userRotation.yawRadians()));
        Matrix4f flipYInverse = Matrix4f.createScale(1f, -1f, 1f);
        return cameraInverse.multiply(modelRotInverse).multiply(flipYInverse);
    }

    /**
     * Builds the orientation-only transform that maps a Y-down model vertex to its pre-projection
     * screen position - matching what the rasterizer applies internally for entity rendering.
     * The composite is {@code FLIP_X × FLIP_Y × modelRotation × engine_camera_row}:
     * <ul>
     * <li>{@code FLIP_X = scale(-1, 1, 1)} - the kit's X-axis chirality compensation applied to
     *     vertex positions inside {@code EntityGeometryKit.buildTrianglesWithScale}; balances
     *     out the iso camera chain's embedded reflection so total pipeline chirality matches
     *     vanilla's harness output (model-LEFT renders at camera-LEFT).</li>
     * <li>{@code FLIP_Y = scale(1, -1, 1)} - the kit's Y-down-to-Y-up flip applied to vertex
     *     positions inside {@code EntityGeometryKit.buildTrianglesWithScale}.</li>
     * <li>{@code modelRotation} - per-render user rotation (yaw × pitch × roll), composed by
     *     {@code ModelEngine.buildModelRotation}.</li>
     * <li>{@code engine_camera_row = scale(1,1,-1) × R_Y(45°) × R_X(210°) × scale(1,1,-1)
     *     × scale(1,-1,1)} - {@link IsometricEngine#entityStandard}'s camera matrix.</li>
     * </ul>
     * Mirrors the engine's per-vertex chain so {@link EntityGeometryKit#computeScreenBounds}
     * sees the same model-to-screen mapping. Centering / NDC scaling are translation + uniform
     * scale that the canvas-fit math handles separately, so they aren't included here.
     */
    private static @NotNull Matrix4f composeIsoTransform(@NotNull EulerRotation userRotation) {
        EulerRotation iso = EulerRotation.STANDARD_ISO_ENTITY;
        Matrix4f flipY = Matrix4f.createScale(1f, -1f, 1f);
        Matrix4f modelRotation = (userRotation.pitch() == 0f && userRotation.yaw() == 0f && userRotation.roll() == 0f)
            ? Matrix4f.IDENTITY
            : Matrix4f.createRotationY(userRotation.yawRadians())
                .multiply(Matrix4f.createRotationX(userRotation.pitchRadians()))
                .multiply(Matrix4f.createRotationZ(userRotation.rollRadians()));
        Matrix4f cameraEntity = Matrix4f.createScale(1f, 1f, -1f)
            .multiply(Matrix4f.createRotationY(iso.yawRadians()))
            .multiply(Matrix4f.createRotationX(iso.pitchRadians()))
            .multiply(Matrix4f.createScale(1f, 1f, -1f))
            .multiply(Matrix4f.createScale(1f, -1f, 1f));
        return flipY.multiply(modelRotation).multiply(cameraEntity);
    }

    /**
     * Canvas size + NDC scale for one render. {@code canvasW × canvasH} are the dimensions of
     * the destination buffer; {@code ndcScale} is the model-units-to-NDC scale the kit applies
     * so the rasterizer projects each entity-pixel-unit to {@code PIXELS_PER_BLOCK/16} canvas
     * pixels.
     */
    private record CanvasFit(int canvasW, int canvasH, float ndcScale) {}

    /** Returns a new {@link Box} with every coordinate multiplied by {@code k}. No-op when {@code k == 1}. */
    private static @NotNull Box scaleBox(@NotNull Box bounds, float k) {
        if (k == 1f) return bounds;
        return new Box(
            bounds.minX() * k, bounds.minY() * k, bounds.minZ() * k,
            bounds.maxX() * k, bounds.maxY() * k, bounds.maxZ() * k
        );
    }

    /**
     * Returns a copy of {@code source} with every partial-alpha texel bumped to {@code alpha=255},
     * leaving fully-opaque and fully-transparent pixels alone. Used by entities flagged
     * {@link EntityModelLoader.EntityDefinition#forceOpaque() forceOpaque} - the runtime
     * equivalent of the bedrock pipeline's authored alpha bump, applied per load so the bundled
     * PNG stays unchanged. Allocates a new {@link PixelBuffer} so cached or shared instances are
     * never mutated.
     */
    private static @NotNull PixelBuffer bumpAlphaToOpaque(@NotNull PixelBuffer source) {
        int w = source.width();
        int h = source.height();
        int[] src = source.data();
        int[] out = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            int p = src[i];
            int a = ColorMath.alpha(p);
            out[i] = (a > 0 && a < 255)
                ? ColorMath.withAlpha(p, 255)
                : p;
        }
        return PixelBuffer.of(out, w, h);
    }

}
