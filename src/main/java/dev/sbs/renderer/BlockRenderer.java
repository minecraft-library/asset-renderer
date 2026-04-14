package dev.sbs.renderer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.sbs.renderer.engine.IsometricEngine;
import dev.sbs.renderer.engine.RasterEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.geometry.Biome;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.geometry.VisibleTriangle;
import dev.sbs.renderer.kit.EntityGeometryKit;
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
 * <li>{@link Isometric3D} delegates to {@link IsometricEngine} for full cube rendering.</li>
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

        return new IsometricEngine(context).sampleBiomeTint(target, options.getBiome());
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
            IsometricEngine engine = new IsometricEngine(this.context);
            int tint = resolveBlockTint(this.context, block, options);

            ConcurrentList<VisibleTriangle> triangles;

            if (block.getMultipart().isPresent()) {
                triangles = assembleMultipart(block.getMultipart().get(), options, tint);
            } else {
                triangles = buildFromBlockElements(block, tint);

                // Apply blockstate variant rotation to the geometry, not the camera.
                String variantKey = options.getVariant();
                Block.Variant variant = block.getVariants().get(variantKey);
                if (variant == null && !variantKey.isEmpty())
                    variant = block.getVariants().get("");
                if (variant != null && variant.hasRotation())
                    triangles = applyRotation(triangles, buildVariantRotation(variant));
            }

            // Fallback to entity model when block has no renderable geometry
            if (triangles.isEmpty())
                triangles = tryBuildFromEntityModel(block);

            float guiYawDelta = guiYawDelta(block);

            int ssaa = Math.max(1, options.getSupersample());
            int hiRes = options.getOutputSize() * ssaa;
            PixelBuffer buffer = PixelBuffer.create(hiRes, hiRes);
            engine.rasterize(triangles, buffer, PerspectiveParams.NONE,
                options.getPitch(), options.getYaw() + guiYawDelta, options.getRoll());

            if (options.isAntiAlias())
                buffer.applyFxaa();

            if (ssaa > 1) {
                PixelBuffer output = PixelBuffer.create(options.getOutputSize(), options.getOutputSize());
                output.blitScaled(buffer, 0, 0, options.getOutputSize(), options.getOutputSize());
                return RenderEngine.staticFrame(output);
            }

            return RenderEngine.staticFrame(buffer);
        }

        /**
         * Assembles geometry from all matching parts of a multipart blockstate. Evaluates
         * each part's condition against the variant properties and builds triangles for every
         * matching model, applying per-part rotation where specified.
         */
        private @NotNull ConcurrentList<VisibleTriangle> assembleMultipart(@NotNull Block.Multipart multipart, @NotNull BlockOptions options, int tint) {
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
                    GeometryKit.buildFromElements(partModel.getElements(), faceTextures, tint);

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
                    tri.shading(), tri.renderPriority(), tri.cullBackFaces()
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
        private @NotNull ConcurrentList<VisibleTriangle> buildFromBlockElements(@NotNull Block block, int tint) {
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

            return GeometryKit.buildFromElements(block.getModel().getElements(), faceTextures, tint);
        }

        /**
         * Attempts to build triangles from an entity model mapping when the block has no
         * renderable elements. Returns an empty list if no mapping exists, the entity model
         * is missing, or the entity texture cannot be resolved.
         */
        private @NotNull ConcurrentList<VisibleTriangle> tryBuildFromEntityModel(@NotNull Block block) {
            if (block.getEntityMapping().isEmpty()) return Concurrent.newList();
            Block.EntityMapping mapping = block.getEntityMapping().get();

            return this.context.findEntity(mapping.model())
                .map(value -> this.context.resolveTexture(mapping.texture())
                    .map(pixelBuffer -> EntityGeometryKit.buildTriangles(
                                 value.getModel(),
                                 pixelBuffer
                             )
                             .triangles()
                    )
                    .orElseGet(Concurrent::newList)
                )
                .orElseGet(Concurrent::newList);
        }

        /**
         * Builds a rotation matrix from a blockstate variant's X and Y rotation values.
         * Variant angles are specified in Minecraft's left-handed convention (CW from above
         * for Y), so the Y angle is negated to match our right-handed rotation matrices.
         * Applied to vertex positions to pre-transform the geometry before camera projection.
         */
        private static @NotNull Matrix4f buildVariantRotation(@NotNull Block.Variant variant) {
            Matrix4f result = Matrix4f.IDENTITY;

            if (variant.y() != 0)
                result = result.multiply(Matrix4f.createRotationY((float) Math.toRadians(-variant.y())));

            if (variant.x() != 0)
                result = result.multiply(Matrix4f.createRotationX((float) Math.toRadians(variant.x())));

            return result;
        }

        /**
         * Returns the model-rotation yaw delta (in degrees, right-handed) needed to compensate
         * for a non-standard {@code display.gui.rotation} on the block model. Most blocks
         * inherit the standard {@code [30, 225, 0]} from the {@code block.json} parent; blocks
         * like stairs override this (e.g. {@code [30, 135, 0]}). The delta is negated for the
         * left-handed to right-handed conversion.
         */
        private static float guiYawDelta(@NotNull Block block) {
            ModelTransform gui = block.getModel().getDisplay().get("gui");
            if (gui == null) return 0f;
            float guiYaw = gui.getRotationY();
            if (guiYaw == 0f || guiYaw == 225f) return 0f;
            return -(guiYaw - 225f);
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
