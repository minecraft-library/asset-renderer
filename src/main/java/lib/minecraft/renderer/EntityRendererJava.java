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
import lib.minecraft.renderer.kit.EntityGeometryKitJava;
import lib.minecraft.renderer.kit.GlintKit;
import lib.minecraft.renderer.options.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * Side-by-side sibling of {@link EntityRenderer} that consumes the Java-derived entity
 * pipeline's output ({@code entity_models_java.json} / {@code entity_geometry_java.json}) and
 * renders via {@link EntityGeometryKitJava}'s Y-down engine path. The bedrock-derived
 * {@link EntityRenderer} keeps producing its established output unchanged so the two pipelines
 * can be compared side-by-side without one perturbing the other.
 * <p>
 * Texture resolution flows through the vanilla pack only via
 * {@link RendererContext#resolveTexture}; the bedrock cache is intentionally not consulted so
 * Java-side gaps (overlay PNGs missing from bedrock, HD resamples sampled with 64x64 UVs)
 * surface as missing entities rather than getting silently papered over with bedrock fallbacks.
 */
@RequiredArgsConstructor
public final class EntityRendererJava implements Renderer<EntityOptions> {

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
     * Effective entity-render scale per entity id. Combines three vanilla scaling sources:
     * <ul>
     * <li>Per-renderer {@code scale(state, ps)} overrides - {@code WitherBossRenderer} bakes
     * a constant {@code scale(2, 2, 2)} into its submit chain.</li>
     * <li>{@code state.scale} set by {@code extractRenderState} from the entity's own scale
     * field - the {@code Giant} entity carries scale 6 on its instance, applied by
     * {@code LivingEntityRenderer.submit}'s {@code scale(state.scale, ...)} call.</li>
     * <li>{@code MeshTransformer.scaling(F)} wraps applied at LayerDefinition build time -
     * the wrap recursively multiplies every cube + bone pivot in the layer definition, so a
     * model with no internal bone hierarchy (e.g. polar_bear: cubes hang off a few flat bones)
     * comes out correct under the simpler "scale every kit output vertex by F" approximation
     * we apply here. Models with deeper bone trees (elder_guardian's spike loop, happy_ghast's
     * leash anchor) get wrong final positions under this approximation because the bone pivot
     * stays at its raw value while the cube vertices get scaled out from under it. Those
     * entities need the scaling baked into JSON at parse time, tracked as a separate fix.</li>
     * </ul>
     * Hardcoded for now; later sessions can ASM-extract from the renderer's {@code scale}
     * override + entity constructor + the {@code MeshTransformer.scaling(F)} call site in the
     * model factory. Unlisted entities default to 1. Excluded baby/conditional cases (zoglin
     * baby 0.5x) since the static renderer never renders babies.
     */
    private static final @NotNull Map<String, Float> RENDERER_SCALE_OVERRIDES = Map.ofEntries(
        Map.entry("minecraft:wither", 2.0f),
        Map.entry("minecraft:giant", 6.0f),
        Map.entry("minecraft:ghast", 4.5f),
        Map.entry("minecraft:polar_bear", 1.2f)
    );

    /** Renderer context for texture resolution + isometric engine setup; not used for entity lookup. */
    private final @NotNull RendererContext context;

    /**
     * Java-derived entity definitions keyed by namespaced id, loaded via
     * {@link EntityModelLoader#loadJava()}. Replaces the bedrock-side {@code context.findEntity()}
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
        Box baseBounds = EntityGeometryKitJava.computeBounds(model);
        for (EntityModelLoader.OverlayLayer overlay : definition.overlays()) {
            if (overlay.model().getBones().isEmpty()) continue;
            Box overlayBounds = EntityGeometryKitJava.computeBounds(overlay.model());
            baseBounds = new Box(
                Math.min(baseBounds.minX(), overlayBounds.minX()),
                Math.min(baseBounds.minY(), overlayBounds.minY()),
                Math.min(baseBounds.minZ(), overlayBounds.minZ()),
                Math.max(baseBounds.maxX(), overlayBounds.maxX()),
                Math.max(baseBounds.maxY(), overlayBounds.maxY()),
                Math.max(baseBounds.maxZ(), overlayBounds.maxZ())
            );
        }

        EulerRotation user = options.getRotation();
        EulerRotation effective = new EulerRotation(
            user.pitch(),
            user.yaw() + model.getInventoryYRotation(),
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
        CanvasFit fit = computeCanvasFit(model, effective, modelScale);
        PixelBuffer buffer = PixelBuffer.create(fit.canvasW(), fit.canvasH());

        // Centre the silhouette on the canvas. The kit subtracts a model-space "anchor" point
        // from each vertex; whatever point we pass becomes NDC origin, which the rasterizer
        // maps to canvas-centre. Naively passing {@code scaledBounds.centre()} (the model-space
        // AABB centre) over-pads non-brick silhouettes: cod's outer AABB extends z=[-4,11] but
        // most cubes hug z=[0,7], so {@code iso(aabb_centre)} lands several pixels off the
        // tight silhouette midpoint. Inverse-projecting the screen-space silhouette midpoint
        // through the iso transform gives the model-space point whose iso image IS the
        // silhouette midpoint - using it as the kit anchor centres the silhouette exactly.
        Vector3f modelAnchor = computeCentreAnchor(model, effective, modelScale, fit);

        EntityGeometryKit.BuildResult buildResult = EntityGeometryKitJava.buildTriangles(
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
            triangles.addAll(EntityGeometryKitJava.buildTriangles(
                overlay.model(), overlayTex.get(), modelAnchor, overlay.emissive(), fit.ndcScale(), modelScale).triangles());
        }

        // Phase E.5: block-model overlays (mooshroom mushrooms, copper-golem flower, etc).
        // The vanilla render layer renders a block model at a transform-stack-applied position
        // on top of the entity body. Each row carries the block id, an optional entity bone the
        // overlay attaches to (so head-mounted overlays follow the head's bind pose), and the
        // ordered list of pose-stack ops the layer issues between {@code pushPose} / {@code
        // popPose}. See {@link EntityModelLoader.BlockOverlayLayer}.
        if (!definition.blockOverlays().isEmpty()) {
            Matrix4f entityFit = EntityGeometryKitJava.buildEntityFitMatrix(modelAnchor, fit.ndcScale() * modelScale);
            for (EntityModelLoader.BlockOverlayLayer blockOverlay : definition.blockOverlays())
                triangles.addAll(buildBlockOverlayTriangles(blockOverlay, model, entityFit));
        }

        IsometricEngine engine = IsometricEngine.standard(this.context);
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
     * Mirrors {@link EntityRenderer#resolveEntityTexture} but reads the texture_ref from the
     * Java-pipeline {@link EntityModelLoader.EntityDefinition} directly rather than via the
     * bedrock-derived {@code Entity} DTO.
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
            Vector3f pivot = EntityGeometryKitJava.resolveBonePivot(model, overlay.attachedBone());
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
     * through the iso transform (matching {@link EntityGeometryKitJava#computeScreenBounds the
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
    private static @NotNull CanvasFit computeCanvasFit(
        @NotNull EntityModelData model,
        @NotNull EulerRotation userRotation,
        float modelScale
    ) {
        Matrix4f transform = composeIsoTransform(userRotation);
        Box screenBounds = EntityGeometryKitJava.computeScreenBounds(model, transform, modelScale);
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
    private static @NotNull Vector3f computeCentreAnchor(
        @NotNull EntityModelData model,
        @NotNull EulerRotation userRotation,
        float modelScale,
        @NotNull CanvasFit fit
    ) {
        Matrix4f isoTransform = composeIsoTransform(userRotation);
        Box screenBounds = EntityGeometryKitJava.computeScreenBounds(model, isoTransform, modelScale);
        float sxMid = (screenBounds.minX() + screenBounds.maxX()) * 0.5f;
        float syMid = (screenBounds.minY() + screenBounds.maxY()) * 0.5f;
        float szMid = (screenBounds.minZ() + screenBounds.maxZ()) * 0.5f;
        Matrix4f isoInverse = composeIsoInverse(userRotation);
        return Vector3f.transform(new Vector3f(sxMid, syMid, szMid), isoInverse);
    }

    /**
     * Inverse of {@link #composeIsoTransform}. Iso is a pure rotation (orthogonal matrix) so
     * the inverse is the transpose. We compose it directly from the negated rotation angles
     * in reversed order: {@code (modelRotation × camera)^-1 = camera^-1 × modelRotation^-1}
     * with {@code R(θ)^-1 = R(-θ)} for each axis-aligned rotation factor.
     */
    private static @NotNull Matrix4f composeIsoInverse(@NotNull EulerRotation userRotation) {
        EulerRotation iso = EulerRotation.STANDARD_ISO_BLOCK;
        Matrix4f cameraInverse = Matrix4f.createRotationX(-iso.pitchRadians())
            .multiply(Matrix4f.createRotationY(-iso.yawRadians()))
            .multiply(Matrix4f.createRotationZ(-iso.rollRadians()));
        if (userRotation.pitch() == 0f && userRotation.yaw() == 0f && userRotation.roll() == 0f)
            return cameraInverse;
        Matrix4f modelRotInverse = Matrix4f.createRotationZ(-userRotation.rollRadians())
            .multiply(Matrix4f.createRotationX(-userRotation.pitchRadians()))
            .multiply(Matrix4f.createRotationY(-userRotation.yawRadians()));
        return cameraInverse.multiply(modelRotInverse);
    }

    /**
     * Builds the transform applied to model vertices during rasterization (model rotation × iso
     * camera). Mirrors the composition done internally by
     * {@code ModelEngine.rasterize(..., EulerRotation)}; duplicated here so canvas sizing can
     * project bounds through the same matrix without a second rasterize call.
     */
    private static @NotNull Matrix4f composeIsoTransform(@NotNull EulerRotation userRotation) {
        Matrix4f modelRotation = (userRotation.pitch() == 0f && userRotation.yaw() == 0f && userRotation.roll() == 0f)
            ? Matrix4f.IDENTITY
            : Matrix4f.createRotationY(userRotation.yawRadians())
                .multiply(Matrix4f.createRotationX(userRotation.pitchRadians()))
                .multiply(Matrix4f.createRotationZ(userRotation.rollRadians()));
        EulerRotation iso = EulerRotation.STANDARD_ISO_BLOCK;
        Matrix4f camera = Matrix4f.createRotationZ(iso.rollRadians())
            .multiply(Matrix4f.createRotationY(iso.yawRadians()))
            .multiply(Matrix4f.createRotationX(iso.pitchRadians()));
        return modelRotation.multiply(camera);
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

    /** Identical to {@link EntityRenderer}'s alpha-bumping helper; duplicated here for code isolation. */
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
