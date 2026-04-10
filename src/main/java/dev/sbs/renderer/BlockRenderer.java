package dev.sbs.renderer;

import dev.sbs.renderer.biome.BiomeTintTarget;
import dev.sbs.renderer.draw.BlendMode;
import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.ColorKit;
import dev.sbs.renderer.draw.GeometryKit;
import dev.sbs.renderer.engine.IsometricEngine;
import dev.sbs.renderer.engine.PerspectiveParams;
import dev.sbs.renderer.engine.RasterEngine;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.VisibleTriangle;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.model.Block;
import dev.sbs.renderer.options.BlockOptions;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

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
        return context.findBlock(blockId)
            .orElseThrow(() -> new RendererException("No block registered for id '%s'", blockId));
    }

    /**
     * Resolves the ARGB tint applied to a block's faces based on its
     * {@link BiomeTintTarget}. Returns opaque white for {@code NONE}, the block's hardcoded
     * constant for {@code CONSTANT}, or a colormap sample for {@code GRASS} / {@code FOLIAGE} /
     * {@code DRY_FOLIAGE}.
     */
    static int resolveBlockTint(
        @NotNull RendererContext context,
        @NotNull Block block,
        @NotNull BlockOptions options
    ) {
        BiomeTintTarget target = block.getTintTarget();
        if (target == BiomeTintTarget.NONE) return ColorKit.WHITE;
        if (target == BiomeTintTarget.CONSTANT) return block.getTintConstant().orElse(ColorKit.WHITE);

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
     * Full 3D isometric block tile renderer. Produces a single-frame static image by building a
     * unit cube from the six face textures of the target block and rasterizing through
     * {@link IsometricEngine}. Biome tint is applied per face via the shared
     * {@link BlockRenderer#resolveBlockTint(RendererContext, Block, BlockOptions)} helper.
     */
    @RequiredArgsConstructor
    public static final class Isometric3D implements Renderer<BlockOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull BlockOptions options) {
            Block block = requireBlock(this.context, options.getBlockId());
            IsometricEngine engine = new IsometricEngine(this.context);
            Canvas canvas = Canvas.of(options.getOutputSize(), options.getOutputSize());

            PixelBuffer[] faces = resolveFaces(block);
            int tint = resolveBlockTint(this.context, block, options);
            ConcurrentList<VisibleTriangle> triangles = GeometryKit.unitCube(faces, tint);
            engine.rasterize(triangles, canvas, PerspectiveParams.NONE,
                options.getPitch(), options.getYaw(), options.getRoll());

            if (options.isAntiAlias())
                canvas.getBuffer().applyFxaa();

            return RenderEngine.staticFrame(canvas);
        }

        /**
         * Resolves all six cube face textures from the block's textures map. Each direction key
         * ({@code down}, {@code up}, {@code north}, {@code south}, {@code west}, {@code east})
         * is looked up via {@link BlockRenderer#resolveTextureRef(Block, String)} and loaded
         * through the active pack stack.
         */
        private @NotNull PixelBuffer @NotNull [] resolveFaces(@NotNull Block block) {
            RasterEngine engine = new RasterEngine(this.context);
            PixelBuffer[] faces = new PixelBuffer[6];
            String[] directionKeys = { "down", "up", "north", "south", "west", "east" };

            for (int i = 0; i < 6; i++) {
                String textureRef = resolveTextureRef(block, directionKeys[i]);
                if (textureRef.isBlank())
                    throw new RendererException("Block '%s' has no texture for face '%s'", block.getId(), directionKeys[i]);
                faces[i] = engine.resolveTexture(textureRef);
            }
            return faces;
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
            Canvas canvas = engine.createCanvas(options.getOutputSize(), options.getOutputSize());

            String textureId = resolveFaceTextureId(block, options.getFace());
            PixelBuffer face = engine.resolveTexture(textureId);
            int tint = resolveBlockTint(this.context, block, options);
            canvas.blitTinted(face, 0, 0, tint, BlendMode.MULTIPLY);

            return RenderEngine.staticFrame(canvas);
        }

        /**
         * Maps a {@link BlockOptions.Face} enum value to the corresponding lowercase direction
         * key in the block's texture map, then reuses
         * {@link BlockRenderer#resolveTextureRef(Block, String)} for the fallback chain.
         */
        private static @NotNull String resolveFaceTextureId(@NotNull Block block, @NotNull BlockOptions.Face face) {
            String key = switch (face) {
                case DOWN -> "down";
                case UP -> "up";
                case NORTH -> "north";
                case SOUTH -> "south";
                case WEST -> "west";
                case EAST -> "east";
            };
            return resolveTextureRef(block, key);
        }

    }

}
