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
import lib.minecraft.renderer.kit.BlockGeometryKit;
import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.kit.GlintKit;
import lib.minecraft.renderer.options.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pipeline.util.RendererDebug;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders mob entities as isometric 3D icons from the Java-derived entity pipeline
 * ({@code entity_models.json} + {@code entity_geometry.json}, produced by
 * {@code ToolingEntityModels} from the vanilla client jar) via {@link EntityGeometryKit}'s
 * Y-down engine path. Texture resolution flows through the vanilla pack via
 * {@link RendererContext#resolveTexture}; missing textures surface as missing entities rather
 * than being papered over with cache fallbacks.
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
     * Renderer context for texture resolution + isometric engine setup; not used for entity lookup.
     */
    private final @NotNull RendererContext context;

    /**
     * Entity definitions keyed by namespaced id, loaded via {@link EntityModelLoader#load()}.
     * Passed in directly rather than queried through {@code context.findEntity()} so visual tests
     * can swap in custom fixtures.
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

        EulerRotation user = options.getRotation();
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
        Vector3f modelAnchor = computeCentreAnchor(options.getEntityId().get(), definition, effective, modelScale, fit, texture.get());

        EntityGeometryKit.BuildResult buildResult = EntityGeometryKit.buildTriangles(
            model, texture.get(), modelAnchor, false, fit.ndcScale(), modelScale, definition.baseTintArgb());
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
                overlay.model(), overlayTex.get(), modelAnchor, overlay.emissive(), fit.ndcScale(), modelScale, overlay.tintArgb()).triangles());
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

        IsometricEngine engine = IsometricEngine.forEntityIcon(this.context);
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

        if (definition.textureRef().isPresent())
            return this.context.resolveTexture("minecraft:entity/" + definition.textureRef().get());

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
                String resolvedId = TextureEngine.resolveTextureReference(ref, variables);
                if (resolvedId.startsWith("#")) continue;
                Optional<PixelBuffer> tex = this.context.resolveTexture(resolvedId);
                tex.ifPresent(pixelBuffer -> faceTextures.put(ref, pixelBuffer));
            }
        }
        if (faceTextures.isEmpty()) return Concurrent.newList();

        ConcurrentList<VisibleTriangle> blockTris = BlockGeometryKit.buildFromElements(
            block.get().getModel().getElements(), faceTextures, ColorMath.WHITE);
        if (blockTris.isEmpty()) return Concurrent.newList();

        // Compose the per-overlay transform matrix in vanilla block units. PoseStack ops apply
        // in bytecode order to the LOCAL frame: under the column-vector convention each new op
        // post-multiplies, matching vanilla's PoseStack `pose = pose * newOp`. Final composite
        // applies the most-recently-appended op first to the cube-local vertex.
        Matrix4f blockUnitChain = Matrix4f.IDENTITY;

        // Optional bone anchor: append the bone's {@code translateAndRotate} equivalent. The
        // bone's pivot is in entity pixel-units, so divide by 16 to get block units; the rotation
        // is built via the same {@code Quaternionf.rotationZYX} entry point vanilla uses.
        if (overlay.attachedBone() != null) {
            Vector3f pivot = EntityGeometryKit.resolveBonePivot(model, overlay.attachedBone());
            Matrix4f tBone = Matrix4f.createTranslation(
                pivot.x() / 16f, pivot.y() / 16f, pivot.z() / 16f);
            blockUnitChain = blockUnitChain.multiply(tBone);
            EntityModelData.Bone bone = model.getBones().get(overlay.attachedBone());
            if (bone != null) {
                EulerRotation rot = bone.getRotation();
                if (rot.pitch() != 0f || rot.yaw() != 0f || rot.roll() != 0f) {
                    Matrix4f rotMat = Quaternionf
                        .rotationZYX(rot.rollRadians(), rot.yawRadians(), rot.pitchRadians())
                        .toMatrix4f();
                    blockUnitChain = blockUnitChain.multiply(rotMat);
                }
            }
        }

        for (EntityModelLoader.TransformOp op : overlay.transforms()) {
            Matrix4f opMat = switch (op) {
                case EntityModelLoader.Translate t -> Matrix4f.createTranslation(t.x(), t.y(), t.z());
                case EntityModelLoader.RotateY r -> Matrix4f.createRotationY((float) Math.toRadians(r.degrees()));
                case EntityModelLoader.Scale s -> Matrix4f.createScale(s.x(), s.y(), s.z());
            };
            blockUnitChain = blockUnitChain.multiply(opMat);
        }

        // Vanilla expects block-model vertices in {@code [0, 1]} (corner-at-origin) since the
        // last pose op {@code translate(-0.5, -0.5, -0.5)} re-centers them at origin before the
        // submit. {@link BlockGeometryKit#buildFromElements} pre-centers the cube to
        // {@code [-0.5, 0.5]} for inventory/atlas use, so add 0.5 on each axis to recover the
        // corner-at-origin convention before the chain applies. Appended last so that, in
        // column-vector composition, this op is rightmost and applies first to the input vertex.
        Matrix4f uncenter = Matrix4f.createTranslation(0.5f, 0.5f, 0.5f);
        blockUnitChain = blockUnitChain.multiply(uncenter);

        // Convert block-unit positions to entity pixel-units (x16), then run the entity-fit
        // normalization to land in the rasterizer's working frame. Column-vector chain reads
        // right-to-left: blockUnitChain first, then blockToPixel, then entityFit.
        Matrix4f blockToPixel = Matrix4f.createScale(16f, 16f, 16f);
        Matrix4f finalMatrix = entityFit.multiply(blockToPixel).multiply(blockUnitChain);

        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle tri : blockTris) {
            Vector3f transformedNormal = Vector3f.normalize(Vector3f.transformNormal(tri.normal(), finalMatrix));
            // Re-shade with entity Lambertian lighting on the post-transform normal. The block kit
            // baked cardinal-bucket shading (Lighting.ITEMS_3D-style: 1.0/0.8/0.6/0.5), but vanilla
            // submits these mushroom/flower block models through the entity render type which dots
            // the post-pose-stack normal against ENTITY_IN_UI lights per pixel - continuous, not
            // bucketed. Sampling mooshroom mushroom red showed our 0.67-0.90 block-cardinal range
            // vs vanilla's 0.45-0.71 Lambertian range.
            float shading = RenderEngine.computeEntityInUiLighting(transformedNormal);
            out.add(new VisibleTriangle(
                Vector3f.transform(tri.position0(), finalMatrix),
                Vector3f.transform(tri.position1(), finalMatrix),
                Vector3f.transform(tri.position2(), finalMatrix),
                tri.uv0(), tri.uv1(), tri.uv2(),
                tri.texture(), tri.tintArgb(),
                transformedNormal,
                shading, tri.cullBackFaces(), tri.emissive()
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
        RendererDebug.fitBounds(entityId, screenBounds);
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
     * Inverse of {@link #composeIsoTransform}. The forward composite is
     * {@code flipY * modelRotation * scale(1,1,-1) * isoRotation * scale(1,1,-1) * scale(1,-1,1)}
     * in column-vector form; the inverse reverses the chain with each factor inverted. Diagonal
     * scales and {@code flipY} are self-inverse. The two rotation factors invert via
     * {@code rotationXYZ(x, y, z) ^ -1 = rotationZYX(-z, -y, -x)}.
     */
    private static @NotNull Matrix4f composeIsoInverse(@NotNull EulerRotation userRotation) {
        EulerRotation iso = EulerRotation.STANDARD_ISO_ENTITY;
        Matrix4f flipY = Matrix4f.createScale(1f, -1f, 1f);
        Matrix4f scaleZneg = Matrix4f.createScale(1f, 1f, -1f);
        Matrix4f isoRotationInverse = Quaternionf
            .rotationZYX(0f, -iso.yawRadians(), -iso.pitchRadians())
            .toMatrix4f();
        Matrix4f modelRotationInverse = (userRotation.pitch() == 0f && userRotation.yaw() == 0f && userRotation.roll() == 0f)
            ? Matrix4f.IDENTITY
            : Quaternionf
                .rotationZYX(-userRotation.rollRadians(), -userRotation.yawRadians(), -userRotation.pitchRadians())
                .toMatrix4f();
        // Forward = flipY * scaleZneg * isoRotation * scaleZneg * modelRotation * flipY (col-vec).
        // Inverse reverses the factor order and inverts each. flipY and scaleZneg are diagonal
        // self-inverses; the two rotations invert via their Quaternionf conjugate above.
        return flipY
            .multiply(modelRotationInverse)
            .multiply(scaleZneg)
            .multiply(isoRotationInverse)
            .multiply(scaleZneg)
            .multiply(flipY);
    }

    /**
     * Builds the orientation-only transform that maps a Y-down model vertex to its pre-projection
     * screen position - matching what the rasterizer applies internally for entity rendering. In
     * column-vector form the composite is
     * {@code flipY * scale(1,1,-1) * isoRotation * scale(1,1,-1) * modelRotation * flipY}, and a
     * vertex sees the rightmost factor first. Listed in application order:
     * <ul>
     * <li>{@code flipY = scale(1,-1,1)} - kit Y-down-to-Y-up flip applied to the model vertex.</li>
     * <li>{@code modelRotation} - per-render user rotation as a single
     *     {@link Quaternionf#rotationXYZ} quaternion. Identity when the user pose is
     *     {@link EulerRotation#NONE}.</li>
     * <li>{@code scale(1,1,-1)} - first half of the kit's Z-axis chirality compensation.</li>
     * <li>{@code isoRotation} - {@code Quaternionf.rotationXYZ(210°, 45°, 0°)} rotation matrix
     *     (bit-identical to vanilla harness's iso pose).</li>
     * <li>{@code scale(1,1,-1)} - second half of Z-axis chirality compensation.</li>
     * <li>{@code flipY = scale(1,-1,1)} - vanilla's outer Y flip into clip space.</li>
     * </ul>
     * Centering / NDC scaling are translation + uniform scale that the canvas-fit math handles
     * separately, so they aren't included here.
     */
    private static @NotNull Matrix4f composeIsoTransform(@NotNull EulerRotation userRotation) {
        EulerRotation iso = EulerRotation.STANDARD_ISO_ENTITY;
        boolean userIdentity = userRotation.pitch() == 0f && userRotation.yaw() == 0f && userRotation.roll() == 0f;
        // Vanilla PoseStack-equivalent chain via fluent ops. Application order rightmost-first
        // under col-vec: flipY -> modelRotation -> scaleZneg -> isoRotation -> scaleZneg -> flipY.
        Matrix4f m = Matrix4f.IDENTITY
            .scale(1f, -1f, 1f)
            .scale(1f, 1f, -1f)
            .rotate(Quaternionf.rotationXYZ(iso.pitchRadians(), iso.yawRadians(), 0f))
            .scale(1f, 1f, -1f);
        if (!userIdentity)
            m = m.rotate(Quaternionf.rotationXYZ(userRotation.pitchRadians(), userRotation.yawRadians(), userRotation.rollRadians()));
        return m.scale(1f, -1f, 1f);
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
