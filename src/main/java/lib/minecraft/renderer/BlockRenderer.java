package lib.minecraft.renderer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.compose.FinalizeStage;
import lib.minecraft.renderer.engine.compose.Frames;
import lib.minecraft.renderer.engine.compose.GeometryLayer;
import lib.minecraft.renderer.engine.compose.LayerStack;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.options.BlockOptions;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.request.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Renders a {@link Block} as either a full 3D isometric tile or a single flat face by
 * dispatching to one of two sub-renderers based on {@link BlockOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;BlockOptions&gt;}:
 * <ul>
 * <li>{@link Isometric3D} uses a {@link ModelEngine} fixed to the standard
 * {@code [30, 225, 0]} block-icon pose by default (via {@link Projection#VANILLA_ISO}). The
 * vanilla-reference harness renders every block at this uniform iso pose and ignores each
 * model's authored {@code display.gui} (stairs/slabs/fence gates ship {@code [30, 135, 0]}),
 * so per-state orientation comes from the baked blockstate variant rotation, not the camera.</li>
 * <li>{@link BlockFace2D} delegates to {@link RasterEngine} for single-face output.</li>
 * </ul>
 * Shared block lookup and biome tint resolution live as package-private static helpers on this
 * class so both sub-renderers can reach them without duplicating logic. CTM / Connected Textures
 * are resolved by the caller before invoking the renderer (the CTM integration hook lives on
 * {@link RasterEngine}).
 */
public final class BlockRenderer implements Renderer<BlockOptions> {

    /** Sub-renderer for the full 3D isometric tile path ({@link BlockOptions.Type#ISOMETRIC_3D}). */
    private final @NotNull Isometric3D isometric3D;
    /** Sub-renderer for the flat single-face path ({@link BlockOptions.Type#BLOCK_FACE_2D}). */
    private final @NotNull BlockFace2D blockFace2D;

    /**
     * Constructs a new {@code BlockRenderer} bound to the given context, eagerly creating both
     * sub-renderers so a caller can dispatch either render type without re-instantiation.
     *
     * @param context the render context supplying the block index and texture lookups
     */
    public BlockRenderer(@NotNull RendererContext context) {
        this.isometric3D = new Isometric3D(context);
        this.blockFace2D = new BlockFace2D(context);
    }

    /**
     * Renders the block, dispatching to the isometric or single-face sub-renderer per
     * {@link BlockOptions#getType()}, then composites the result over the options background.
     *
     * @param options the block options
     * @return the rendered image composited over {@link BlockOptions#getBackground()}
     */
    @Override
    public @NotNull ImageData render(@NotNull BlockOptions options) {
        ImageData rendered = switch (options.getType()) {
            case ISOMETRIC_3D -> this.isometric3D.render(options);
            case BLOCK_FACE_2D -> this.blockFace2D.render(options);
        };
        return options.getBackground().composite(rendered);
    }

    /**
     * Looks up a block by id in the renderer context, throwing a descriptive
     * {@link RenderException} when the block is missing.
     */
    static @NotNull Block requireBlock(@NotNull RendererContext context, @NotNull String blockId) {
        return context.findBlock(blockId).orElseThrow(() -> new RenderException("No block registered for id '%s'", blockId));
    }

    /**
     * Resolves the ARGB tint applied to a block's faces based on its
     * {@link Block.TintTarget}. Returns opaque white for {@code NONE}, the block's hardcoded
     * constant for {@code CONSTANT}, or a colormap sample for {@code GRASS} / {@code FOLIAGE} /
     * {@code DRY_FOLIAGE}.
     */
    static int resolveBlockTint(@NotNull RendererContext context, @NotNull Block block, @NotNull BlockOptions options) {
        Block.TintTarget target = block.getTint().target();

        if (target == Block.TintTarget.NONE)
            return ColorMath.WHITE;

        if (target == Block.TintTarget.CONSTANT)
            return block.getTint().constant().orElse(ColorMath.WHITE);

        return new Textures(context).sampleBiomeTint(target, options.getBiome());
    }

    /**
     * Walks a block's texture map for a given direction key, falling back through
     * {@code all} → {@code side} → {@code particle} when the direction is not bound. Shared
     * between the isometric and face sub-renderers so both have a single definition of the
     * fallback chain.
     */
    static @NotNull String resolveTextureRef(@NotNull Block block, @NotNull String directionKey) {
        return block.getTextures().getOrDefault(directionKey,
            block.getTextures().getOrDefault("all",
                block.getTextures().getOrDefault("side",
                    block.getTextures().getOrDefault("particle", ""))));
    }

    /**
     * Full 3D isometric block tile renderer. Every block - single- or multi-element (chests, doors,
     * pistons) - is built from its resolved model's full element list via
     * {@link BlockGeometryKit#buildFromElements}. Geometry is assembled through a
     * {@link GeometryLayer} stack (primary model, then additive / merged block-entity parts), then
     * recentered / re-lit and rasterized through {@link Projection#VANILLA_ISO}. Biome tint is
     * applied per face via the shared
     * {@link BlockRenderer#resolveBlockTint(RendererContext, Block, BlockOptions)} helper (faces
     * with {@code tintindex >= 0} only).
     */
    @RequiredArgsConstructor
    public static final class Isometric3D implements Renderer<BlockOptions> {

        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull BlockOptions options) {
            Block block = requireBlock(this.context, options.getBlockId());
            // Every block renders at the standard [30, 225, 0] iso pose - NOT the model's own
            // authored display.gui. The vanilla-reference harness deliberately ignores each
            // model's display.gui and hardcodes BLOCK_GUI_ROTATION = rotationXYZ(30, 225, 0) for
            // both its plain-block (BlockFrameRenderer) and entity-block (BlockEntityFrameRenderer)
            // paths, so a uniform iso pose is the ground truth. Reading the model's display.gui
            // mirrored every y=90/270 oriented block (stairs/slabs/fence-gates ship [30, 135, 0],
            // the reflection of 225 about yaw=180): the stair step faced the opposite horizontal
            // side from vanilla. The blockstate variant rotation (baked into the harness's
            // BlockStateModel quads, applied here via buildVariantRotation) supplies the real
            // per-state orientation; the camera pose stays fixed.
            // The caller's rotation is composed onto the projection's base pose, so it poses the
            // camera AND the inventory-relight lighting together (resolved.lightingPose()); the
            // rasterize call below applies no separate model-spin. A default render passes
            // EulerRotation.NONE, leaving the pose at the base [30, 225, 0] iso - byte-identical.
            var resolved = options.getProjection().resolve(options.getRotation(), options.getFacing());
            EulerRotation guiRotation = resolved.lightingPose();
            ModelEngine engine = new ModelEngine(this.context, resolved);

            // Block-entity mappings may supply a per-entry tint that overrides the block's
            // biome / constant tint. Used for banners: vanilla resolves DyeColor via
            // BlockColors at render time rather than baking per-colour textures, so the
            // mapping JSON carries the DyeColor diffuse colour and we multiply it against
            // every sampled texel. Non-banner entries default to {@code ColorMath.WHITE},
            // leaving the normal biome-tint path intact.
            //
            // The {@link Block.Entity} is attached directly to the {@link Block} at
            // {@code PipelineRendererContext} construction time, so the renderer reads it
            // straight off the block - no sidecar lookup through
            // {@link RendererContext#findBlockEntityEntry} is needed.
            Block.Entity be = block.getEntity().orElse(null);
            boolean entityTinted = be != null && be.tintArgb() != ColorMath.WHITE;
            int tint = entityTinted
                ? be.tintArgb()
                : resolveBlockTint(this.context, block, options);
            // Only faces carrying a {@code "tintindex"} (>= 0) receive the colour, exactly as
            // vanilla tints; faces with {@code tintindex = -1} render at their raw texture colour.
            // This holds for both the banner dye ({@link Block.Entity} tint - pole + bar stay
            // wood-brown) and biome tints: grass_block's {@code #side} dirt faces are tintindex -1
            // and must stay brown while only its {@code #top} + {@code #overlay} (tintindex 0) go
            // green. Leaves / grass / cross plants carry tintindex 0 on every face, so they tint
            // uniformly regardless.
            int untintedTint = ColorMath.WHITE;

            // Fall back to the block's tooling-derived default blockstate key when the caller
            // supplies no explicit variant, so blocks with per-state models
            // ({@code sweet_berry_bush}, doors, {@code furnace}, glazed terracotta, crops) render
            // their canonical default rather than whichever model registered first. Property-less
            // blocks have an empty default key, which resolves to the raw model pose. This replaces
            // the harness {@code .variant} sidecar the parity test used to consume.
            String effectiveVariant = options.getVariant().isEmpty() ? block.getDefaultStateKey() : options.getVariant();

            // Block geometry is assembled through a GeometryLayer stack - primary model, then additive
            // block-entity geometry, then merged block-entity parts - for uniformity with the other
            // renderers and so callers can splice layers via BlockOptions.layerDecorator. The shared
            // whole-mesh steps (multi-block recenter / rotation, empty-fallback rebuild, inventory
            // relight) run on the assembled sink afterwards.
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
            LayerStack<GeometryLayer> stack = new LayerStack<>();

            stack.append(BlockOptions.Slot.PRIMARY, sink -> {
                // Bone-format block entities (chest) carry a relative bone/cube tree rather than
                // pre-flattened block elements: build hierarchically via buildFromBones and apply
                // the presentation transform (entity flip + inventory transform + inventory yaw)
                // that vanilla's BlockEntityRenderer poses around the mesh - the render-time
                // counterpart of the former BlockModelConverter tooling bake. This replaces the
                // whole primary model (a non-additive entity's geometry IS the primary geometry).
                if (be != null && be.boneModel().isPresent()) {
                    sink.addAll(buildFromBoneEntity(be, tint));
                    return;
                }
                if (block.getMultipart().isPresent()) {
                    sink.addAll(assembleMultipart(block.getMultipart().get(), effectiveVariant, tint, untintedTint));
                    return;
                }
                // Resolve the blockstate variant BEFORE building geometry so its model id can override
                // Block#getModel() (sweet_berry_bush age stages, doors). The variant key is the
                // caller's when supplied, else the block's default state key; property-less blocks fall
                // through to the raw model pose. TILE_ENTITY blocks point the variant at an empty
                // template, so the non-empty-elements check keeps the geometry-bearing BE model - while
                // still letting a BE inject a geometry variant for a mesh-varying state (hanging sign).
                Block.Variant variant = resolveVariant(block, effectiveVariant);
                ModelData modelToUse = block.getModel();
                if (variant != null) {
                    ModelData variantModel = variant.model();
                    if (!variantModel.getElements().isEmpty())
                        modelToUse = variantModel;
                }
                ConcurrentList<VisibleTriangle> primary = buildFromBlockElements(modelToUse, variant, tint, untintedTint);
                if (variant != null && variant.hasRotation())
                    primary = applyRotation(primary, buildVariantRotation(variant));
                sink.addAll(primary);
            });

            // Atlas-time composition: merge Block.Entity parts into the primary geometry (bed foot onto
            // head, decorated_pot sides onto base, banner flag onto post). Gated on mergeParts - scene
            // callers pass false to render one variant at a time. Additive entities (bell body) overlay
            // the primary model; non-additive entity geometry IS the primary model already.
            if (be != null && options.isMergeParts()) {
                if (be.additive())
                    stack.append(BlockOptions.Slot.ADDITIVE_ENTITY, sink -> sink.addAll(buildFromAdditiveEntity(be, tint, untintedTint)));
                if (!be.parts().isEmpty())
                    stack.append(BlockOptions.Slot.PARTS, sink -> sink.addAll(buildFromEntityParts(be, tint, untintedTint)));
            }

            for (GeometryLayer layer : options.getLayerDecorator().apply(stack).ordered())
                layer.contribute(triangles);

            // Block entity multi-block models (beds) need recentering + rotation + scaling
            // since they extend beyond the standard 0-16 single-block bounds. Bone-format BEs
            // always run recenterAndFit: their model geometry (empty elements) can't be measured
            // by the loader's element-bbox multiBlock check, and recenterAndFit self-gates on
            // extent > 1.4 blocks, so it is a no-op for the block-sized families (chest, sign,
            // shulker, ...) and only recentres a tall/wide statue (copper_golem_statue, whose model
            // is authored X-centred at 0 and Y up to ~24px, off the single-block frame).
            boolean recenterFit = be != null && (be.multiBlock() || be.boneModel().isPresent());
            if (be != null && (recenterFit || be.iconRotation() != 0)) {
                if (be.iconRotation() != 0)
                    triangles = applyRotation(triangles, Matrix4f.createRotationY(
                        (float) Math.toRadians(be.iconRotation())));
                if (recenterFit)
                    triangles = recenterAndFit(triangles);
            }

            // Fallback: when the block's registered model produces no faces (variant- or
            // multipart-gated blocks where every apply has a {@code when} clause), rebuild
            // using the first blockstate apply regardless of conditions. Fixes shelves,
            // chiseled_bookshelf, sniffer_egg, stem_growth, mushroom_stem, flowerbed_*,
            // pitcher_crop_top_stage_*, redstone_dust, coral_fan, brewing_stand_bottle2, etc.
            if (triangles.isEmpty())
                triangles = tryFirstBlockstateApply(block, tint, untintedTint);

            // Replace BlockGeometryKit's cardinal-bucket shading (1.0/0.8/0.6/0.5 lookup) with
            // vanilla's Lighting.ITEMS_3D Lambertian on the post-display.gui normal. Vanilla
            // 26.1 dropped per-face cardinal multiplication from the GUI inventory render path
            // entirely - the shader's only lighting input is two directional dot products. The
            // resulting shade is continuous in the surface normal so face-rotated geometry
            // (stairs corners, slab edges, fence posts) gets per-quad lighting that matches
            // the harness PNGs instead of bucketing to the closest cardinal's pre-baked value.
            //
            // Plain block models (no {@link Block.Entity}) cull back faces like vanilla's block
            // render type ({@code RenderType.cutout}/{@code solid}/... all bind CULL): a
            // zero-thickness {@code block/cross} element declares both faces (north+south) and the
            // GPU keeps only the camera-facing one. {@link BlockGeometryKit} marks every
            // zero-thickness face two-sided ({@code cullBackFaces=false}); without this the
            // away-facing polygon's MIRRORED-UV cutout texels draw extra silhouette pixels vanilla
            // never shows (cobweb +19797 java-only px). Block-ENTITY surfaces (signs, banner cloth,
            // hanging-sign chains) are genuinely vanilla-no-cull ({@code entityCutoutNoCull}) and
            // keep their two-sided faces + camera-facing flip, so the cull is gated on the absence
            // of a {@link Block.Entity}.
            boolean cullBlockModelFaces = be == null;
            triangles = Shading.relightForItems3d(triangles, guiRotation, cullBlockModelFaces);

            int ssaa = Math.max(1, options.getSupersample());
            ConcurrentList<VisibleTriangle> rasterTriangles = triangles;
            return FinalizeStage.run(options.getOutputSize(), options.getOutputSize(), ssaa, options.isAntiAlias(), false,
                (target, mask) -> engine.rasterize(rasterTriangles, target),
                (buffer, mask) -> Frames.staticFrame(buffer));
        }

        /**
         * Assembles geometry from all matching parts of a multipart blockstate. Evaluates
         * each part's condition against the variant properties and builds triangles for every
         * matching model, applying per-part rotation where specified.
         */
        private @NotNull ConcurrentList<VisibleTriangle> assembleMultipart(@NotNull Block.Multipart multipart, @NotNull String variantKey, int tint, int untintedTint) {
            ConcurrentMap<String, String> properties = parseProperties(variantKey);
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
            RasterEngine raster = new RasterEngine(this.context);

            for (Block.Multipart.Part part : multipart.parts()) {
                if (!matchesCondition(part.when(), properties)) continue;

                Block.Variant apply = part.apply();
                // The variant carries its baked model (resolved from the full model set at
                // context construction); element-less means the apply's model id didn't resolve.
                ModelData partModel = apply.model();
                if (partModel.getElements().isEmpty()) continue;

                // Build triangles for this part's model
                ConcurrentMap<String, PixelBuffer> faceTextures = Textures.loadElementFaceTextures(
                    partModel.getElements(), partModel.getTextures(),
                    id -> Optional.of(raster.textures().resolveTextureAtTick(id, 0)));

                ConcurrentList<VisibleTriangle> partTriangles = apply.uvlock()
                    ? BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint, apply.x(), apply.y(), true)
                    : BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint);

                // Apply per-part rotation if specified
                if (apply.hasRotation())
                    partTriangles = applyRotation(partTriangles, buildVariantRotation(apply));

                triangles.addAll(partTriangles);
            }

            return triangles;
        }

        /**
         * Applies a rotation matrix to all triangles in a list, transforming vertex positions
         * and surface normals. Preserves each triangle's {@code cullBackFaces} and {@code emissive}
         * traits while resetting {@code translucent} / {@code glinted} to {@code false} - block
         * geometry carries neither.
         */
        private static @NotNull ConcurrentList<VisibleTriangle> applyRotation(@NotNull ConcurrentList<VisibleTriangle> triangles, @NotNull Matrix4f rotation) {
            ConcurrentList<VisibleTriangle> rotated = Concurrent.newList();

            for (VisibleTriangle tri : triangles) {
                rotated.add(new VisibleTriangle(
                    tri.position0().transform(rotation),
                    tri.position1().transform(rotation),
                    tri.position2().transform(rotation),
                    tri.uv0(), tri.uv1(), tri.uv2(),
                    tri.texture(), tri.tintArgb(),
                    tri.normal().transformNormal(rotation),
                    tri.shading(), new SurfaceTraits(tri.traits().cullBackFaces(), tri.traits().emissive(), false, false)
                ));
            }

            return rotated;
        }

        /**
         * Parses a variant properties string ({@code "facing=south,lit=false"}) into a map.
         */
        private static @NotNull ConcurrentMap<String, String> parseProperties(@NotNull String variant) {
            ConcurrentMap<String, String> result = Concurrent.newMap();
            if (variant.isBlank()) return result;

            for (String pair : variant.split(",")) {
                int eq = pair.indexOf('=');

                if (eq > 0)
                    result.put(pair.substring(0, eq), pair.substring(eq + 1));
            }

            return result;
        }

        /**
         * Evaluates a multipart condition against blockstate properties. Supports simple
         * property matching, pipe-delimited multi-value OR ({@code "side|up"}), and compound
         * AND/OR operators.
         */
        private static boolean matchesCondition(@Nullable JsonObject when, @NotNull ConcurrentMap<String, String> properties) {
            if (when == null) return true;

            if (when.has("AND")) {
                JsonArray conditions = when.getAsJsonArray("AND");

                for (JsonElement el : conditions) {
                    if (!matchesCondition(el.getAsJsonObject(), properties)) return false;
                }

                return true;
            }
            if (when.has("OR")) {
                JsonArray conditions = when.getAsJsonArray("OR");

                for (JsonElement el : conditions) {
                    if (matchesCondition(el.getAsJsonObject(), properties))
                        return true;
                }

                return false;
            }

            // Simple property matching
            for (Map.Entry<String, JsonElement> entry : when.entrySet()) {
                String required = entry.getValue().getAsString();
                String actual = properties.getOrDefault(entry.getKey(), "");

                if (required.contains("|")) {
                    if (!Arrays.asList(required.split("\\|")).contains(actual))
                        return false;
                } else {
                    if (!required.equals(actual))
                        return false;
                }
            }
            return true;
        }

        /**
         * Builds triangles from all elements in a multi-element block model. Walks every
         * element's face texture references, dereferences {@code #variable} chains against
         * the model's texture bindings, and builds geometry via
         * {@link BlockGeometryKit#buildFromElements}. Accepts the model directly (rather than
         * a {@link Block}) so callers can pass a variant-resolved model that differs from the
         * block's primary {@link Block#getModel()} - e.g. {@code sweet_berry_bush_stage0} for
         * an {@code age=0} render.
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildFromBlockElements(@NotNull ModelData model, @Nullable Block.Variant variant, int tint, int untintedTint) {
            RasterEngine raster = new RasterEngine(this.context);
            ConcurrentMap<String, PixelBuffer> faceTextures = Textures.loadElementFaceTextures(
                model.getElements(), model.getTextures(),
                id -> Optional.of(raster.textures().resolveTextureAtTick(id, 0)));

            // uvlock counter-rotates the up/down-face UVs against the variant Y rotation so the
            // texture stays world-aligned (the position rotation is applied separately by the
            // caller via applyRotation). Non-uvlock variants fall through to the plain build.
            if (variant != null && variant.uvlock())
                return BlockGeometryKit.buildFromElements(model.getElements(), faceTextures, tint, untintedTint, variant.x(), variant.y(), true);
            return BlockGeometryKit.buildFromElements(model.getElements(), faceTextures, tint, untintedTint);
        }

        /**
         * Builds triangles for a bone-format block entity (chest) from its relative bone/cube tree
         * via {@link BlockGeometryKit#buildFromBones}, applying the presentation transform that
         * reproduces vanilla's {@code BlockEntityRenderer} pose (the render-time counterpart of the
         * former {@code BlockModelConverter} tooling bake). The single entity texture is resolved at
         * frame 0 and sampled by every cube face.
         *
         * @param entity the bone-format block entity (its {@link Block.Entity#boneModel()} must be present)
         * @param tint the ARGB tint applied to every face ({@link ColorMath#WHITE} for untinted chests)
         * @return the composed block-frame triangle list
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildFromBoneEntity(@NotNull Block.Entity entity, int tint) {
            Block.Entity.BoneModel bone = entity.boneModel().orElseThrow();
            RasterEngine raster = new RasterEngine(this.context);
            PixelBuffer texture = raster.textures().resolveTextureAtTick(entity.textureId(), 0);
            return BlockGeometryKit.buildFromBones(bone.model(), texture, tint, blockEntityPresentation(bone));
        }

        /**
         * Builds the {@code [0, 16]}-space presentation transform for a bone-format block entity -
         * the render-time reconstruction of the affine chain the former
         * {@code BlockModelConverter.CubeTransform} baked into block elements (minus the bone chain,
         * which {@link BlockGeometryKit#buildFromBones} composes). Column-vector order matches
         * vanilla's {@code BlockEntityRenderer}: the entity-render {@code scale(-1, -1, 1)} flip (or
         * a decomposed {@code inventory_transform} of {@code scale -> Rx(pitch) -> translate})
         * applies first, then the inventory yaw about block centre {@code (8, 8, 8)}.
         *
         * @param bone the block entity's bone geometry + presentation metadata
         * @return the presentation matrix in the {@code [0, 16]} block-authoring frame
         */
        private static @NotNull Matrix4f blockEntityPresentation(@NotNull Block.Entity.BoneModel bone) {
            float[] inv = bone.inventoryTransform();
            Matrix4f pre;
            if (inv != null) {
                // scale(invScale) -> Rx(pitch) -> translate(tx, ty, tz), matching vanilla's
                // translate * rotate * scale composition (CubeTransform.applyChain).
                float invScale = inv.length > 6 && inv[6] != 0f ? inv[6] : 1f;
                float pitch = (float) Math.toRadians(inv[3]);
                pre = Matrix4f.createTranslation(inv[0], inv[1], inv[2])
                    .multiply(Matrix4f.createRotationX(pitch))
                    .multiply(Matrix4f.createScale(invScale, invScale, invScale));
            } else {
                // No inventory transform: vanilla's entity-render flip scale(-1, -1, 1). The X
                // negation is gated on entityFlip (read from the item icon's display.gui roll). The
                // Y negation maps the bones' source frame to block Y-up - needed only for Y-DOWN
                // (entity-space) sources; a Y-UP source (chest) is already block-Y-up, so its Y stays
                // positive (matching the former element bake's net orientation).
                float sx = bone.entityFlip() ? -1f : 1f;
                float sy = bone.sourceYUp() ? 1f : -1f;
                pre = Matrix4f.createScale(sx, sy, 1f);
            }

            // Inventory yaw about block centre (8, 8, 8) - the chest's +180 that faces the model
            // under the standard [30, 225, 0] iso pose. All current block-entity yaws are 180
            // (symmetric, so the createRotationY sign is immaterial); a future non-180 value would
            // need its rotation sense checked against CubeTransform.applyChain.
            float yaw = bone.inventoryYRotation();
            if (yaw != 0f) {
                Matrix4f yawAboutCentre = Matrix4f.createTranslation(8f, 8f, 8f)
                    .multiply(Matrix4f.createRotationY((float) Math.toRadians(yaw)))
                    .multiply(Matrix4f.createTranslation(-8f, -8f, -8f));
                pre = yawAboutCentre.multiply(pre);
            }
            return pre;
        }

        /**
         * Builds triangles for an {@linkplain Block.Entity#additive() additive} entity's primary
         * model and binds its {@link Block.Entity#textureId()} to the {@code "#entity"} face
         * variable. Used by bells (and any future overlay-style block entity) where the entity
         * geometry merges on top of an existing blockstate-resolved primary model rather than
         * replacing it.
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildFromAdditiveEntity(@NotNull Block.Entity entity, int tint, int untintedTint) {
            RasterEngine raster = new RasterEngine(this.context);
            ConcurrentMap<String, String> variables = Concurrent.newMap();
            variables.put("entity", entity.textureId());
            ConcurrentMap<String, PixelBuffer> faceTextures = Textures.loadElementFaceTextures(
                entity.model().getElements(), variables,
                id -> Optional.of(raster.textures().resolveTextureAtTick(id, 0)));
            return BlockGeometryKit.buildFromElements(entity.model().getElements(), faceTextures, tint, untintedTint);
        }

        /**
         * Builds triangles for every {@link Block.Entity.Part part} attached to a block-entity
         * block and translates them by each part's offset. Returns the combined triangle list
         * ready to concatenate with the primary geometry. Called only when
         * {@link BlockOptions#isMergeParts()} is {@code true}.
         * <p>
         * Translating the output triangles (rather than rewriting the element's from/to and
         * rotation.origin up-front) is safe because rotation composes with translation:
         * rotating around origin O then translating by D gives the same result as rotating
         * around origin O+D after the whole element has been translated by D. That means the
         * element's rotated-cube corners land at the correct final positions either way.
         * <p>
         * This is the atlas-time composition path that used to live in
         * {@link BlockModelLoader}. Moving it to render time
         * lets scene callers skip the merge for a per-variant-geometry render.
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildFromEntityParts(@NotNull Block.Entity entity, int tint, int untintedTint) {
            ConcurrentList<VisibleTriangle> combined = Concurrent.newList();
            RasterEngine raster = new RasterEngine(this.context);

            for (Block.Entity.Part part : entity.parts()) {
                // Resolve the part's face textures. {@code "#entity"} in element face refs
                // binds to the part's own texture id (which may differ from the primary -
                // decorated_pot sides use {@code entity/decorated_pot/decorated_pot_side}
                // while the base uses {@code ..._base}).
                ConcurrentMap<String, String> variables = Concurrent.newMap();
                variables.put("entity", part.texture());
                ConcurrentMap<String, PixelBuffer> faceTextures = Textures.loadElementFaceTextures(
                    part.model().getElements(), variables,
                    id -> Optional.of(raster.textures().resolveTextureAtTick(id, 0)));

                ConcurrentList<VisibleTriangle> partTriangles =
                    BlockGeometryKit.buildFromElements(part.model().getElements(), faceTextures, tint, untintedTint);

                // Apply the part's offset to every vertex. Offset is in model units (0..16);
                // triangle vertex positions are in block units (0..1) post-GeometryKit, so
                // divide by 16.
                float dx = part.offset()[0] / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK;
                float dy = part.offset()[1] / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK;
                float dz = part.offset()[2] / BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK;
                if (dx != 0f || dy != 0f || dz != 0f) {
                    ConcurrentList<VisibleTriangle> shifted = Concurrent.newList();
                    for (VisibleTriangle t : partTriangles) {
                        shifted.add(new VisibleTriangle(
                            new Vector3f(t.position0().x() + dx, t.position0().y() + dy, t.position0().z() + dz),
                            new Vector3f(t.position1().x() + dx, t.position1().y() + dy, t.position1().z() + dz),
                            new Vector3f(t.position2().x() + dx, t.position2().y() + dy, t.position2().z() + dz),
                            t.uv0(), t.uv1(), t.uv2(),
                            t.texture(), t.tintArgb(), t.normal(), t.shading(), new SurfaceTraits(t.traits().cullBackFaces(), t.traits().emissive(), false, false)
                        ));
                    }
                    partTriangles = shifted;
                }

                combined.addAll(partTriangles);
            }

            return combined;
        }

        /**
         * Builds triangles from the first variant or multipart apply of a block's blockstate,
         * ignoring any {@code when} condition. Acts as a default render for blocks whose every
         * blockstate apply is gated behind property conditions (shelves, chiseled_bookshelf,
         * redstone_dust, flowerbed_*) or whose registered template model carries unresolved
         * {@code #var} face refs (sniffer_egg, stem_growth, mushroom_stem).
         * <p>
         * Returns an empty list when the block has no blockstate apply or when the referenced
         * model cannot be resolved in the block index. Per-apply rotation is preserved so the
         * rendered block faces the apply's intended direction.
         */
        private @NotNull ConcurrentList<VisibleTriangle> tryFirstBlockstateApply(@NotNull Block block, int tint, int untintedTint) {
            Block.Variant first = null;
            if (block.getMultipart().isPresent()) {
                ConcurrentList<Block.Multipart.Part> parts = block.getMultipart().get().parts();

                if (!parts.isEmpty())
                    first = parts.getFirst().apply();
            } else if (!block.getVariants().isEmpty())
                first = block.getVariants().values().iterator().next();

            if (first == null)
                return Concurrent.newList();

            ModelData partModel = first.model();

            if (partModel.getElements().isEmpty())
                return Concurrent.newList();

            RasterEngine raster = new RasterEngine(this.context);
            ConcurrentMap<String, PixelBuffer> faceTextures = Textures.loadElementFaceTextures(
                partModel.getElements(), partModel.getTextures(),
                id -> Optional.of(raster.textures().resolveTextureAtTick(id, 0)));

            ConcurrentList<VisibleTriangle> triangles = first.uvlock()
                ? BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint, first.x(), first.y(), true)
                : BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint);

            if (first.hasRotation())
                triangles = applyRotation(triangles, buildVariantRotation(first));

            return triangles;
        }

        /**
         * Builds a rotation matrix from a blockstate variant's X and Y rotation values,
         * matching vanilla's {@code BlockModelDefinition} variant baking: both angles are
         * negated because blockstate rotation is specified in the opposite sense from JOML's
         * (and this codebase's) right-handed rotation matrices. Applied to vertex positions
         * to pre-transform the geometry before the gui display transform.
         */
        private static @NotNull Matrix4f buildVariantRotation(@NotNull Block.Variant variant) {
            // Vanilla blockstate variant rotation applies Y first, then X, to a vertex - the
            // composite R_x * R_y. Built with the fluent rotate path ({@code this * R(q)},
            // post-multiply, bit-identical to vanilla's {@code PoseStack.mulPose}) rather than
            // {@code createRotationX(...).multiply(...)}, whose full matrix-matrix multiply drifts
            // 1-4 ULPs per entry vs vanilla (see {@link Matrix4f} fluent-vs-multiply note).
            // Applying X then Y under post-multiply yields IDENTITY * R_x * R_y = R_x * R_y.
            Matrix4f result = Matrix4f.IDENTITY;

            if (variant.x() != 0)
                result = result.rotate(Quaternionf.rotationXYZ((float) Math.toRadians(-variant.x()), 0f, 0f));

            if (variant.y() != 0)
                result = result.rotate(Quaternionf.rotationXYZ(0f, (float) Math.toRadians(-variant.y()), 0f));

            return result;
        }

        /**
         * Looks up the blockstate variant for a given property key. Exact-match only: an
         * empty {@code variantKey} or a key that isn't present in the blockstate returns
         * {@code null}, in which case the caller renders the raw model pose - which for
         * oriented blocks matches what vanilla inventory shows, since vanilla's inventory
         * pipeline never consults the blockstate.
         */
        private static @Nullable Block.Variant resolveVariant(@NotNull Block block, @NotNull String variantKey) {
            // A property-less block maps to its unconditional {@code ""} blockstate variant, whose
            // model is authoritative and need NOT equal {@link Block#getModel()} (the by-id
            // {@code block/<id>} guess). mud_bricks points {@code ""} at
            // {@code block/mud_bricks_north_west_mirrored} (north/west faces UV-flipped) where
            // {@code getModel()} is the plain {@code block/mud_bricks} cube_all - falling through to
            // {@code getModel()} dropped the mirror. The caller only swaps in the variant's geometry
            // when it carries real elements, so an empty particle-only template (TILE_ENTITY blocks
            // whose mesh comes from the block-entity model) still falls back to the BE model.
            if (variantKey.isEmpty()) return block.getVariants().get("");
            // Exact key hit (caller knows the precise variant). Fast path.
            Block.Variant exact = block.getVariants().get(variantKey);
            if (exact != null) return exact;
            // Partial-superset match: the caller supplied a fully-qualified blockstate
            // (e.g. `facing=north,half=lower,hinge=left,open=false,powered=false` from the
            // harness's defaultBlockState dump) but the JSON variant keys only list the
            // properties that actually affect the model (`facing/half/hinge/open` for doors,
            // omitting `powered`). Walk the variants map and pick the entry whose props are
            // a SUBSET of the caller's props. Returns the variant whose conditions are all
            // satisfied by the caller's blockstate.
            // Most-specific subset wins: among the variants whose props are all satisfied by the
            // caller's blockstate, pick the one matching the most properties. This lets a
            // geometry-bearing {@code attached=true} variant (injected for the ceiling hanging
            // sign) beat the unconditional {@code ""} catch-all that also subset-matches every
            // state; for blocks with equal-specificity variants the first encountered still wins.
            ConcurrentMap<String, String> callerProps = parseProperties(variantKey);
            Block.Variant best = null;
            int bestSpecificity = -1;
            for (Map.Entry<String, Block.Variant> entry : block.getVariants().entrySet()) {
                ConcurrentMap<String, String> variantProps = parseProperties(entry.getKey());
                if (isSubsetMatch(variantProps, callerProps) && variantProps.size() > bestSpecificity) {
                    best = entry.getValue();
                    bestSpecificity = variantProps.size();
                }
            }
            return best;
        }

        /** Returns true when every entry in {@code subset} appears with the same value in {@code superset}. */
        private static boolean isSubsetMatch(@NotNull ConcurrentMap<String, String> subset, @NotNull ConcurrentMap<String, String> superset) {
            for (Map.Entry<String, String> e : subset.entrySet()) {
                String supersetVal = superset.get(e.getKey());
                if (supersetVal == null || !supersetVal.equals(e.getValue())) return false;
            }
            return true;
        }

        /**
         * Recenters and scales a triangle list so all geometry fits within the standard
         * 1.4 unit extent. Used for multi-block entity models that extend beyond the
         * standard 0-16 single-block bounds.
         * <p>
         * Applies two distinct behaviours depending on how far the geometry overflows:
         * <ul>
         * <li><b>Horizontal multi-block (beds):</b> extent &gt; 1.4 - shrinks uniformly to
         *     1.4 and recenters around the bbox midpoint so both halves fit one tile.</li>
         * <li><b>Slightly tall single-block (decorated_pot rim y=17..20):</b> extent just
         *     above 1.0 - leaves scale at 1 and skips recentering, so the element keeps
         *     its authored Y levels and the rim naturally extends above the block top
         *     line just like vanilla's inventory icon. Previously the pot got scaled up
         *     1.12× and shifted down, which stretched the wall→rim gap and broke
         *     element-to-element alignment.</li>
         * </ul>
         */
        private static @NotNull ConcurrentList<VisibleTriangle> recenterAndFit(@NotNull ConcurrentList<VisibleTriangle> triangles) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (VisibleTriangle t : triangles) {
                for (Vector3f v : new Vector3f[]{ t.position0(), t.position1(), t.position2() }) {
                    minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
                    minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
                    minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
                }
            }
            float extent = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ);
            if (extent <= 1.4f) return triangles;
            float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
            float scale = 1.4f / extent;

            ConcurrentList<VisibleTriangle> result = Concurrent.newList();
            for (VisibleTriangle t : triangles) {
                result.add(new VisibleTriangle(
                    new Vector3f((t.position0().x() - cx) * scale, (t.position0().y() - cy) * scale, (t.position0().z() - cz) * scale),
                    new Vector3f((t.position1().x() - cx) * scale, (t.position1().y() - cy) * scale, (t.position1().z() - cz) * scale),
                    new Vector3f((t.position2().x() - cx) * scale, (t.position2().y() - cy) * scale, (t.position2().z() - cz) * scale),
                    t.uv0(), t.uv1(), t.uv2(),
                    t.texture(), t.tintArgb(), t.normal(), t.shading(), new SurfaceTraits(t.traits().cullBackFaces(), t.traits().emissive(), false, false)
                ));
            }
            return result;
        }

    }

    /**
     * Single-face 2D block renderer. Outputs a flat textured quad for one of the six block
     * faces specified by {@link BlockOptions#getFace()}, applying any biome tint via a
     * {@link BlendMode#MULTIPLY} blit.
     */
    @RequiredArgsConstructor
    public static final class BlockFace2D implements Renderer<BlockOptions> {

        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull BlockOptions options) {
            Block block = requireBlock(this.context, options.getBlockId());
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer buffer = engine.createBuffer(options.getOutputSize(), options.getOutputSize());

            String textureId = resolveTextureRef(block, options.getFace().direction());
            PixelBuffer face = engine.textures().resolveTexture(textureId);
            int tint = resolveBlockTint(this.context, block, options);
            PixelBuffer tinted = ColorMath.tint(face, tint);
            int size = options.getOutputSize();
            buffer.blitScaled(tinted, 0, 0, size, size);

            return Frames.staticFrame(buffer);
        }

    }

}
