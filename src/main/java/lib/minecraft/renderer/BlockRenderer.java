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
import dev.simplified.image.pixel.PixelBufferPool;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.BlockModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.engine.RasterEngine;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.TextureEngine;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.geometry.Biome;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.kit.BlockGeometryKit;
import lib.minecraft.renderer.options.BlockOptions;
import lib.minecraft.renderer.pipeline.loader.BlockEntityLoader;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;

/**
 * Renders a {@link Block} as either a full 3D isometric tile or a single flat face by
 * dispatching to one of two sub-renderers based on {@link BlockOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;BlockOptions&gt;}:
 * <ul>
 * <li>{@link Isometric3D} uses an {@link IsometricEngine} fixed to the standard
 * {@code [30, 225, 0]} block-icon pose (via {@link IsometricEngine#forBlockIcon}). The
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

    private final @NotNull Isometric3D isometric3D;
    private final @NotNull BlockFace2D blockFace2D;

    public BlockRenderer(@NotNull RendererContext context) {
        this.isometric3D = new Isometric3D(context);
        this.blockFace2D = new BlockFace2D(context);
    }

    @Override
    public @NotNull ImageData render(@NotNull BlockOptions options) {
        return switch (options.getType()) {
            case ISOMETRIC_3D -> this.isometric3D.render(options);
            case BLOCK_FACE_2D -> this.blockFace2D.render(options);
        };
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
     * {@link Biome.TintTarget}. Returns opaque white for {@code NONE}, the block's hardcoded
     * constant for {@code CONSTANT}, or a colormap sample for {@code GRASS} / {@code FOLIAGE} /
     * {@code DRY_FOLIAGE}.
     */
    static int resolveBlockTint(@NotNull RendererContext context, @NotNull Block block, @NotNull BlockOptions options) {
        Biome.TintTarget target = block.getTint().target();

        if (target == Biome.TintTarget.NONE)
            return ColorMath.WHITE;

        if (target == Biome.TintTarget.CONSTANT)
            return block.getTint().constant().orElse(ColorMath.WHITE);

        return new TextureEngine(context).sampleBiomeTint(target, options.getBiome());
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
     * Full 3D isometric block tile renderer. Multi-element blocks (chests, doors, pistons) are
     * rendered using their full element list via {@link BlockGeometryKit#buildFromElements}; single-
     * element blocks use the fast unit-cube path. Biome tint is applied per face via the shared
     * {@link BlockRenderer#resolveBlockTint(RendererContext, Block, BlockOptions)} helper.
     */
    @RequiredArgsConstructor
    public static final class Isometric3D implements Renderer<BlockOptions> {

        private final @NotNull RendererContext context;

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
            EulerRotation guiRotation = EulerRotation.STANDARD_ISO_BLOCK;
            IsometricEngine engine = IsometricEngine.forBlockIcon(this.context);

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
            // When the block's tint comes from a {@link Block.Entity} (banner dye colour), only
            // faces carrying {@code "tintindex": 0} should receive the dye - untinted faces
            // (banner pole + bar) stay wood-brown via {@link ColorMath#WHITE}. Biome-tinted
            // and untinted blocks keep uniform tinting (both slots equal) so grass_block and
            // friends render unchanged.
            int untintedTint = entityTinted ? ColorMath.WHITE : tint;

            // Fall back to the block's tooling-derived default blockstate key when the caller
            // supplies no explicit variant, so blocks with per-state models
            // ({@code sweet_berry_bush}, doors, {@code furnace}, glazed terracotta, crops) render
            // their canonical default rather than whichever model registered first. Property-less
            // blocks have an empty default key, which resolves to the raw model pose. This replaces
            // the harness {@code .variant} sidecar the parity test used to consume.
            String effectiveVariant = options.getVariant().isEmpty() ? block.getDefaultStateKey() : options.getVariant();

            ConcurrentList<VisibleTriangle> triangles;

            if (block.getMultipart().isPresent()) {
                triangles = assembleMultipart(block.getMultipart().get(), effectiveVariant, tint, untintedTint);
            } else {
                // Resolve the blockstate variant BEFORE building geometry so its model id can
                // override {@link Block#getModel()}. Blocks like {@code sweet_berry_bush} have
                // per-stage models ({@code sweet_berry_bush_stage0..3}) keyed off the {@code age}
                // property; without this hop, {@code block.getModel()} returns whichever stage
                // happened to register first (effectively non-deterministic for blockstate-only
                // ids), producing a mature-bush render for an {@code age=0} request. The variant
                // key is the caller's when supplied, otherwise the block's default state key
                // (see {@code effectiveVariant} above); only property-less blocks fall through to
                // {@code block.getModel()}'s raw pose.
                //
                // {@link Block.Source#TILE_ENTITY} blocks (chests, banners, beds, skulls, ...)
                // are an exception: their variant's modelId points to an empty template (e.g.
                // {@code block/skull}, {@code block/banner}) and the geometry-bearing model is
                // the BE-derived one already attached as {@link Block#getModel()}. For these
                // blocks we keep the BE model and only let the variant carry rotation. The
                // model lookup is also skipped when the resolved model would be element-less,
                // which guards against pack-level surprises for non-TILE_ENTITY blocks too.
                Block.Variant variant = resolveVariant(block, effectiveVariant);
                BlockModelData modelToUse = block.getModel();
                if (variant != null && block.getSource() != Block.Source.TILE_ENTITY) {
                    BlockModelData variantModel = variant.model();
                    if (!variantModel.getElements().isEmpty())
                        modelToUse = variantModel;
                }
                triangles = buildFromBlockElements(modelToUse, variant, tint, untintedTint);
                if (variant != null && variant.hasRotation())
                    triangles = applyRotation(triangles, buildVariantRotation(variant));
            }

            // Atlas-time composition: merge {@link Block.Entity.Part parts} into the primary
            // geometry (bed foot onto bed head, decorated_pot sides onto its base, banner flag
            // onto its post). Gated on {@link BlockOptions#isMergeParts()} - scene callers pass
            // {@code false} to render one variant's geometry at a time.
            if (be != null && options.isMergeParts()) {
                // Additive entities (bell body) leave the primary block.json model in place
                // and overlay the entity geometry. The entity's own model is appended here
                // alongside its parts; non-additive entries skip this step because their
                // entity geometry IS the primary model already (chests, beds, banners).
                if (be.additive())
                    triangles.addAll(buildFromAdditiveEntity(be, tint, untintedTint));
                if (!be.parts().isEmpty())
                    triangles.addAll(buildFromEntityParts(be, tint, untintedTint));
            }

            // Block entity multi-block models (beds) need recentering + rotation + scaling
            // since they extend beyond the standard 0-16 single-block bounds.
            if (be != null && (be.multiBlock() || be.iconRotation() != 0)) {
                if (be.iconRotation() != 0)
                    triangles = applyRotation(triangles, Matrix4f.createRotationY(
                        (float) Math.toRadians(be.iconRotation())));
                if (be.multiBlock())
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
            triangles = relightForItems3d(triangles, guiRotation);

            int ssaa = Math.max(1, options.getSupersample());
            if (ssaa > 1) {
                int hiRes = options.getOutputSize() * ssaa;
                try (PixelBufferPool.Lease lease = PixelBufferPool.acquire(hiRes, hiRes)) {
                    PixelBuffer buffer = lease.buffer();
                    engine.rasterize(triangles, buffer, PerspectiveParams.ISOMETRIC_BLOCK, options.getRotation());
                    if (options.isAntiAlias()) buffer.applyFxaa();
                    PixelBuffer output = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
                    output.blitScaled(buffer, 0, 0, options.getOutputSize(), options.getOutputSize());
                    return RenderEngine.staticFrame(output);
                }
            }

            PixelBuffer buffer = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
            engine.rasterize(triangles, buffer, PerspectiveParams.ISOMETRIC_BLOCK, options.getRotation());
            if (options.isAntiAlias()) buffer.applyFxaa();
            return RenderEngine.staticFrame(buffer);
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
                BlockModelData partModel = apply.model();
                if (partModel.getElements().isEmpty()) continue;

                // Build triangles for this part's model
                ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
                ConcurrentMap<String, String> variables = partModel.getTextures();
                for (ModelElement element : partModel.getElements()) {
                    for (Map.Entry<String, ModelFace> faceEntry : element.getFaces().entrySet()) {
                        String ref = faceEntry.getValue().getTexture();
                        if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                        String resolvedId = TextureEngine.resolveTextureReference(ref, variables);
                        if (resolvedId.startsWith("#")) continue;
                        faceTextures.put(ref, raster.resolveTexture(resolvedId));
                    }
                }

                ConcurrentList<VisibleTriangle> partTriangles = apply.uvlock()
                    ? BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint, apply.y(), true)
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
         * and surface normals.
         */
        private static @NotNull ConcurrentList<VisibleTriangle> applyRotation(@NotNull ConcurrentList<VisibleTriangle> triangles, @NotNull Matrix4f rotation) {
            ConcurrentList<VisibleTriangle> rotated = Concurrent.newList();

            for (VisibleTriangle tri : triangles) {
                rotated.add(new VisibleTriangle(
                    Vector3f.transform(tri.position0(), rotation),
                    Vector3f.transform(tri.position1(), rotation),
                    Vector3f.transform(tri.position2(), rotation),
                    tri.uv0(), tri.uv1(), tri.uv2(),
                    tri.texture(), tri.tintArgb(),
                    Vector3f.transformNormal(tri.normal(), rotation),
                    tri.shading(), tri.cullBackFaces(), tri.emissive()
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
        private @NotNull ConcurrentList<VisibleTriangle> buildFromBlockElements(@NotNull BlockModelData model, @Nullable Block.Variant variant, int tint, int untintedTint) {
            RasterEngine raster = new RasterEngine(this.context);
            ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
            ConcurrentMap<String, String> variables = model.getTextures();

            for (ModelElement element : model.getElements()) {
                for (ModelFace face : element.getFaces().values()) {
                    String ref = face.getTexture();
                    if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                    String resolvedId = TextureEngine.resolveTextureReference(ref, variables);
                    if (resolvedId.startsWith("#")) continue;
                    faceTextures.put(ref, raster.resolveTexture(resolvedId));
                }
            }

            // uvlock counter-rotates the up/down-face UVs against the variant Y rotation so the
            // texture stays world-aligned (the position rotation is applied separately by the
            // caller via applyRotation). Non-uvlock variants fall through to the plain build.
            if (variant != null && variant.uvlock())
                return BlockGeometryKit.buildFromElements(model.getElements(), faceTextures, tint, untintedTint, variant.y(), true);
            return BlockGeometryKit.buildFromElements(model.getElements(), faceTextures, tint, untintedTint);
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
            ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
            ConcurrentMap<String, String> variables = Concurrent.newMap();
            variables.put("entity", entity.textureId());
            for (ModelElement element : entity.model().getElements()) {
                for (ModelFace face : element.getFaces().values()) {
                    String ref = face.getTexture();
                    if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                    String resolvedId = TextureEngine.resolveTextureReference(ref, variables);
                    if (resolvedId.startsWith("#")) continue;
                    faceTextures.put(ref, raster.resolveTexture(resolvedId));
                }
            }
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
         * {@link BlockEntityLoader}. Moving it to render time
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
                ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
                ConcurrentMap<String, String> variables = Concurrent.newMap();
                variables.put("entity", part.texture());
                for (ModelElement element : part.model().getElements()) {
                    for (ModelFace face : element.getFaces().values()) {
                        String ref = face.getTexture();
                        if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                        String resolvedId = TextureEngine.resolveTextureReference(ref, variables);
                        if (resolvedId.startsWith("#")) continue;
                        faceTextures.put(ref, raster.resolveTexture(resolvedId));
                    }
                }

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
                            t.texture(), t.tintArgb(), t.normal(), t.shading(), t.cullBackFaces(), t.emissive()
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

            BlockModelData partModel = first.model();

            if (partModel.getElements().isEmpty())
                return Concurrent.newList();

            RasterEngine raster = new RasterEngine(this.context);
            ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
            ConcurrentMap<String, String> variables = partModel.getTextures();
            for (ModelElement element : partModel.getElements()) {
                for (Map.Entry<String, ModelFace> faceEntry : element.getFaces().entrySet()) {
                    String ref = faceEntry.getValue().getTexture();
                    if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                    String resolvedId = TextureEngine.resolveTextureReference(ref, variables);
                    if (resolvedId.startsWith("#")) continue;
                    faceTextures.put(ref, raster.resolveTexture(resolvedId));
                }
            }

            ConcurrentList<VisibleTriangle> triangles = first.uvlock()
                ? BlockGeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint, first.y(), true)
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
            // Column-vector iteration: pre-multiply each rotation so the most-recent factor is
            // leftmost and applies last to a vertex. Vanilla blockstate variant rotations apply
            // Y first, then X, both as PoseStack post-mul steps.
            Matrix4f result = Matrix4f.IDENTITY;

            if (variant.y() != 0)
                result = Matrix4f.createRotationY((float) Math.toRadians(-variant.y())).multiply(result);

            if (variant.x() != 0)
                result = Matrix4f.createRotationX((float) Math.toRadians(-variant.x())).multiply(result);

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
            if (variantKey.isEmpty()) return null;
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
            ConcurrentMap<String, String> callerProps = parseProperties(variantKey);
            for (Map.Entry<String, Block.Variant> entry : block.getVariants().entrySet()) {
                ConcurrentMap<String, String> variantProps = parseProperties(entry.getKey());
                if (isSubsetMatch(variantProps, callerProps)) return entry.getValue();
            }
            return null;
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
                    t.texture(), t.tintArgb(), t.normal(), t.shading(), t.cullBackFaces(), t.emissive()
                ));
            }
            return result;
        }

        /**
         * Re-shades every triangle with vanilla's {@code Lighting.ITEMS_3D} Lambertian based on
         * the triangle's normal rotated through the block's {@code display.gui} pose and the
         * GUI PoseStack's Y-flip ({@code scale(W, -H, W)}). Replaces the cardinal-bucket
         * shading {@link BlockGeometryKit} bakes at quad-emit time.
         * <p>
         * The transform chain mirrors vanilla's render path exactly: vanilla submits each
         * quad's normal via {@code pose.transformNormal(quadNormal)}, where {@code pose} =
         * {@code translate(W/2,H/2,0) × scale(W,-H,W) × Q_{display.gui}}. Translation doesn't
         * affect direction; the upper-3x3 of the scale is {@code diag(1, -1, 1)} (up to magnitude,
         * which renormalises out for direction vectors); the gui rotation is a pure rotation.
         * So the per-vertex normal handed to the fragment shader is
         * {@code S(1,-1,1) × R_{gui} × n_model}, and that's what
         * {@link RenderEngine#computeBlockItems3dLighting} expects.
         */
        private static @NotNull ConcurrentList<VisibleTriangle> relightForItems3d(
            @NotNull ConcurrentList<VisibleTriangle> triangles,
            @NotNull EulerRotation guiRotation
        ) {
            Matrix4f normalTransform = Matrix4f.IDENTITY
                .scale(1f, -1f, 1f)
                .rotate(Quaternionf.rotationXYZ(
                    guiRotation.pitchRadians(),
                    guiRotation.yawRadians(),
                    guiRotation.rollRadians()
                ));
            ConcurrentList<VisibleTriangle> out = Concurrent.newList();
            for (VisibleTriangle t : triangles) {
                Vector3f renderNormal = Vector3f.normalize(Vector3f.transformNormal(t.normal(), normalTransform));
                // Match vanilla's vertex-stream byte-packed normal: the shader receives the
                // normal after a signed-byte SNORM round-trip ({@code (int)(c * 127.0F) / 127.0F},
                // truncated toward zero). For the LEFT face of a default iso pose, this maps
                // unit (-0.7071, 0.3536, 0.6124) -> (-0.7008, 0.3465, 0.6063), magnitude 0.9894;
                // the resulting Lambertian shade drops from 0.6505 to 0.6490, matching vanilla's
                // empirical 0.647 within precision. Without this step every block shows the
                // visible-LEFT face's texels rounded ~1 LSB high.
                Vector3f packedNormal = packAsSnormByte(renderNormal);
                float shading = RenderEngine.computeBlockItems3dLighting(packedNormal);
                out.add(new VisibleTriangle(
                    t.position0(), t.position1(), t.position2(),
                    t.uv0(), t.uv1(), t.uv2(),
                    t.texture(), t.tintArgb(), t.normal(),
                    shading, t.cullBackFaces(), t.emissive()
                ));
            }
            return out;
        }

        /**
         * Replicates vanilla's {@code BufferBuilder.normalIntValue} byte-packing followed by
         * the shader's SNORM unpacking. Each component {@code c} is mapped to
         * {@code (int)(clamp(c, -1, 1) * 127.0F) / 127.0F}, with the integer cast truncating
         * toward zero (so {@code 0.6124 -> 77/127 = 0.6063}, not {@code 78/127 = 0.6142}).
         * The result is not unit length - vanilla's shader doesn't renormalize either.
         */
        private static @NotNull Vector3f packAsSnormByte(@NotNull Vector3f n) {
            return new Vector3f(
                ((int) (Math.clamp(n.x(), -1f, 1f) * 127.0f)) / 127.0f,
                ((int) (Math.clamp(n.y(), -1f, 1f) * 127.0f)) / 127.0f,
                ((int) (Math.clamp(n.z(), -1f, 1f) * 127.0f)) / 127.0f
            );
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

        @Override
        public @NotNull ImageData render(@NotNull BlockOptions options) {
            Block block = requireBlock(this.context, options.getBlockId());
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer buffer = engine.createBuffer(options.getOutputSize(), options.getOutputSize());

            String textureId = resolveTextureRef(block, options.getFace().direction());
            PixelBuffer face = engine.resolveTexture(textureId);
            int tint = resolveBlockTint(this.context, block, options);
            PixelBuffer tinted = ColorMath.tint(face, tint);
            int size = options.getOutputSize();
            buffer.blitScaled(tinted, 0, 0, size, size);

            return RenderEngine.staticFrame(buffer);
        }

    }

}
