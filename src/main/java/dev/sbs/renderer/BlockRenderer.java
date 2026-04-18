package dev.sbs.renderer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.sbs.renderer.geometry.ModelGrid;
import dev.sbs.renderer.engine.IsometricEngine;
import dev.sbs.renderer.engine.RasterEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.geometry.Biome;
import dev.sbs.renderer.geometry.EulerRotation;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.pipeline.PipelineRendererContext;
import dev.sbs.renderer.kit.GeometryKit;
import dev.sbs.renderer.asset.Block;
import dev.sbs.renderer.asset.model.BlockModelData;
import dev.sbs.renderer.asset.model.ModelElement;
import dev.sbs.renderer.asset.model.ModelFace;
import dev.sbs.renderer.asset.model.ModelTransform;
import dev.sbs.renderer.options.BlockOptions;
import dev.sbs.renderer.tensor.Matrix4f;
import dev.sbs.renderer.tensor.Vector3f;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelBufferPool;
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
 * <li>{@link Isometric3D} uses an {@link IsometricEngine} whose camera transform is the
 * block's own {@code display.gui} rotation (via {@link IsometricEngine#withGuiPose}),
 * matching vanilla's {@code PoseStack.mulPose(Quaternionf.rotationXYZ(gui.rotation))}
 * exactly so stairs, slabs, fence gates, and other blocks that override the standard
 * {@code [30, 225, 0]} pose render vanilla-accurate without any yaw-delta fixup.</li>
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
     * {@link RendererException} when the block is missing.
     */
    static @NotNull Block requireBlock(@NotNull RendererContext context, @NotNull String blockId) {
        return context.findBlock(blockId).orElseThrow(() -> new RendererException("No block registered for id '%s'", blockId));
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
     * rendered using their full element list via {@link GeometryKit#buildFromElements}; single-
     * element blocks use the fast unit-cube path. Biome tint is applied per face via the shared
     * {@link BlockRenderer#resolveBlockTint(RendererContext, Block, BlockOptions)} helper.
     */
    @RequiredArgsConstructor
    public static final class Isometric3D implements Renderer<BlockOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull BlockOptions options) {
            Block block = requireBlock(this.context, options.getBlockId());
            // The block's own display.gui rotation (stairs use [30, 135, 0] instead of the
            // default [30, 225, 0], etc.) lives in the engine's camera transform; the user's
            // pitch/yaw/roll go through the standard rasterize path. This matches vanilla's
            // PoseStack.mulPose(Quaternionf.rotationXYZ(gui.rotation)) exactly.
            IsometricEngine engine = engineForBlockIcon(this.context, block);

            // Block-entity mappings may supply a per-entry tint that overrides the block's
            // biome / constant tint. Used for banners: vanilla resolves DyeColor via
            // BlockColors at render time rather than baking per-colour textures, so the
            // mapping JSON carries the DyeColor diffuse colour and we multiply it against
            // every sampled texel. Non-banner entries default to {@code ColorMath.WHITE},
            // leaving the normal biome-tint path intact.
            //
            // The {@link Block.Entity} is attached directly to the {@link Block} at
            // {@link dev.sbs.renderer.pipeline.PipelineRendererContext} construction time,
            // so the renderer reads it straight off the block - no sidecar lookup through
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

            ConcurrentList<VisibleTriangle> triangles;

            if (block.getMultipart().isPresent()) {
                triangles = assembleMultipart(block.getMultipart().get(), options, tint, untintedTint);
            } else {
                triangles = buildFromBlockElements(block, tint, untintedTint);

                // Apply blockstate variant rotation (x/y) to the geometry. When the caller
                // didn't specify a variant we intentionally skip variant resolution so the
                // block renders in its raw model pose - matching vanilla inventory, which
                // consults the item model + display.gui transform and never the blockstate.
                // Callers that want a specific orientation pass {@code "facing=east,..."}
                // explicitly on {@link BlockOptions#getVariant()}.
                Block.Variant variant = resolveVariant(block, options.getVariant());
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
        private @NotNull ConcurrentList<VisibleTriangle> assembleMultipart(@NotNull Block.Multipart multipart, @NotNull BlockOptions options, int tint, int untintedTint) {
            ConcurrentMap<String, String> properties = parseProperties(options.getVariant());
            ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
            RasterEngine raster = new RasterEngine(this.context);

            for (Block.Multipart.Part part : multipart.parts()) {
                if (!matchesCondition(part.when(), properties)) continue;

                Block.Variant apply = part.apply();
                // Convert model id (minecraft:block/brewing_stand) to block id (minecraft:brewing_stand)
                String partBlockId = apply.modelId().replace(":block/", ":");
                BlockModelData partModel = this.context.findBlock(partBlockId)
                    .map(Block::getModel)
                    .orElse(null);
                if (partModel == null) continue;

                // Build triangles for this part's model
                ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
                ConcurrentMap<String, String> variables = partModel.getTextures();
                for (ModelElement element : partModel.getElements()) {
                    for (Map.Entry<String, ModelFace> faceEntry : element.getFaces().entrySet()) {
                        String ref = faceEntry.getValue().getTexture();
                        if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                        String resolvedId = TextureEngine.dereferenceVariable(ref, variables);
                        if (resolvedId.startsWith("#")) continue;
                        faceTextures.put(ref, raster.resolveTexture(resolvedId));
                    }
                }

                ConcurrentList<VisibleTriangle> partTriangles =
                    GeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint);

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
                    tri.shading(), tri.cullBackFaces()
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
         * {@link GeometryKit#buildFromElements}.
         */
        private @NotNull ConcurrentList<VisibleTriangle> buildFromBlockElements(@NotNull Block block, int tint, int untintedTint) {
            RasterEngine raster = new RasterEngine(this.context);
            ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
            ConcurrentMap<String, String> variables = block.getModel().getTextures();

            for (ModelElement element : block.getModel().getElements()) {
                for (ModelFace face : element.getFaces().values()) {
                    String ref = face.getTexture();
                    if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                    String resolvedId = TextureEngine.dereferenceVariable(ref, variables);
                    if (resolvedId.startsWith("#")) continue;
                    faceTextures.put(ref, raster.resolveTexture(resolvedId));
                }
            }

            return GeometryKit.buildFromElements(block.getModel().getElements(), faceTextures, tint, untintedTint);
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
         * {@link dev.sbs.renderer.pipeline.loader.BlockEntityLoader}. Moving it to render time
         * lets scene callers skip the merge for a per-variant-geometry render.
         */
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
                    String resolvedId = TextureEngine.dereferenceVariable(ref, variables);
                    if (resolvedId.startsWith("#")) continue;
                    faceTextures.put(ref, raster.resolveTexture(resolvedId));
                }
            }
            return GeometryKit.buildFromElements(entity.model().getElements(), faceTextures, tint, untintedTint);
        }

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
                        String resolvedId = TextureEngine.dereferenceVariable(ref, variables);
                        if (resolvedId.startsWith("#")) continue;
                        faceTextures.put(ref, raster.resolveTexture(resolvedId));
                    }
                }

                ConcurrentList<VisibleTriangle> partTriangles =
                    GeometryKit.buildFromElements(part.model().getElements(), faceTextures, tint, untintedTint);

                // Apply the part's offset to every vertex. Offset is in model units (0..16);
                // triangle vertex positions are in block units (0..1) post-GeometryKit, so
                // divide by 16.
                float dx = part.offset()[0] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK;
                float dy = part.offset()[1] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK;
                float dz = part.offset()[2] / ModelGrid.VANILLA_PIXEL_UNITS_PER_BLOCK;
                if (dx != 0f || dy != 0f || dz != 0f) {
                    ConcurrentList<VisibleTriangle> shifted = Concurrent.newList();
                    for (VisibleTriangle t : partTriangles) {
                        shifted.add(new VisibleTriangle(
                            new Vector3f(t.position0().x() + dx, t.position0().y() + dy, t.position0().z() + dz),
                            new Vector3f(t.position1().x() + dx, t.position1().y() + dy, t.position1().z() + dz),
                            new Vector3f(t.position2().x() + dx, t.position2().y() + dy, t.position2().z() + dz),
                            t.uv0(), t.uv1(), t.uv2(),
                            t.texture(), t.tintArgb(), t.normal(), t.shading(), t.cullBackFaces()
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
                    first = parts.get(0).apply();
            } else if (!block.getVariants().isEmpty()) {
                first = block.getVariants().values().iterator().next();
            }
            if (first == null) return Concurrent.newList();

            String partBlockId = first.modelId().replace(":block/", ":");
            BlockModelData partModel = this.context.findBlock(partBlockId)
                .map(Block::getModel)
                .orElse(null);
            if (partModel == null || partModel.getElements().isEmpty()) return Concurrent.newList();

            RasterEngine raster = new RasterEngine(this.context);
            ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
            ConcurrentMap<String, String> variables = partModel.getTextures();
            for (ModelElement element : partModel.getElements()) {
                for (Map.Entry<String, ModelFace> faceEntry : element.getFaces().entrySet()) {
                    String ref = faceEntry.getValue().getTexture();
                    if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                    String resolvedId = TextureEngine.dereferenceVariable(ref, variables);
                    if (resolvedId.startsWith("#")) continue;
                    faceTextures.put(ref, raster.resolveTexture(resolvedId));
                }
            }

            ConcurrentList<VisibleTriangle> triangles =
                GeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint, untintedTint);

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
            Matrix4f result = Matrix4f.IDENTITY;

            if (variant.y() != 0)
                result = result.multiply(Matrix4f.createRotationY((float) Math.toRadians(-variant.y())));

            if (variant.x() != 0)
                result = result.multiply(Matrix4f.createRotationX((float) Math.toRadians(-variant.x())));

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
            return block.getVariants().get(variantKey);
        }

        /**
         * Returns an {@link IsometricEngine} whose camera reflects the block's own
         * {@code display.gui} rotation. Falls back to the standard {@code [30, 225, 0]} from
         * {@code block/block.json} when the block doesn't supply its own gui transform, matching
         * vanilla's inheritance behaviour - stairs ship {@code [30, 135, 0]}, slabs and fence
         * gates override too.
         */
        /**
         * Recenters and scales a triangle list so all geometry fits within the standard
         * 1.4 unit extent. Used for multi-block entity models that extend beyond the
         * standard 0-16 single-block bounds.
         * <p>
         * Applies two distinct behaviours depending on how far the geometry overflows:
         * <ul>
         * <li><b>Horizontal multi-block (beds):</b> extent &gt; 1.4 — shrinks uniformly to
         *     1.4 and recenters around the bbox midpoint so both halves fit one tile.</li>
         * <li><b>Slightly tall single-block (decorated_pot rim y=17..20):</b> extent just
         *     above 1.0 — leaves scale at 1 and skips recentering, so the element keeps
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
                    t.texture(), t.tintArgb(), t.normal(), t.shading(), t.cullBackFaces()
                ));
            }
            return result;
        }

        private static @NotNull IsometricEngine engineForBlockIcon(@NotNull RendererContext context, @NotNull Block block) {
            ModelTransform gui = block.getModel().getDisplay().get("gui");
            EulerRotation rotation = gui != null ? gui.getRotation() : EulerRotation.STANDARD_ISO_BLOCK;
            return IsometricEngine.withGuiPose(context, rotation);
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
