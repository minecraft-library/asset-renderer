package dev.sbs.renderer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.kit.AnimationKit;
import dev.sbs.renderer.asset.Block;
import dev.sbs.renderer.asset.pack.ColorMap;
import dev.sbs.renderer.asset.Entity;
import dev.sbs.renderer.asset.Item;
import dev.sbs.renderer.asset.pack.TexturePack;
import dev.sbs.renderer.asset.pack.AnimationData;
import dev.sbs.renderer.options.AtlasOptions;
import dev.sbs.renderer.options.BlockOptions;
import dev.sbs.renderer.options.GridOptions;
import dev.sbs.renderer.options.ItemOptions;
import dev.sbs.renderer.pipeline.PipelineRendererContext;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.Optional;

/**
 * Renders every block and item model exposed by a {@link RendererContext} into a single grid
 * atlas image and a sidecar JSON describing each tile's coordinates.
 * <p>
 * Implements the same {@link Renderer Renderer&lt;O&gt;} contract as the other top-level
 * renderers: a constructor takes a {@link RendererContext}, the cached {@link BlockRenderer}
 * and {@link ItemRenderer} are stored as final fields, and {@link #render(AtlasOptions)}
 * returns a single {@link ImageData}. Callers that also need the per-tile metadata for the
 * sidecar JSON should call {@link #renderAtlas(AtlasOptions)} instead, which returns the
 * full {@link AtlasResult}.
 * <p>
 * Models that fail to render (templates without textures, models that reference textures the
 * pack stack does not provide, etc.) are skipped with a warning printed to stderr - one
 * misbehaving model never aborts the run. Both per-tile failure warnings and per-100-tile
 * progress logs are gated on {@link AtlasOptions#isProgressLogging()}.
 */
public final class AtlasRenderer implements Renderer<AtlasOptions> {

    /** Tile-count interval between {@code stdout} progress lines when {@link AtlasOptions#isProgressLogging()} is set. */
    private static final int PROGRESS_LOG_INTERVAL = 100;

    private final @NotNull RendererContext context;
    private final @NotNull BlockRenderer blockRenderer;
    private final @NotNull ItemRenderer itemRenderer;
    private final @NotNull GridRenderer gridRenderer;

    public AtlasRenderer(@NotNull RendererContext context) {
        this.context = context;
        this.blockRenderer = new BlockRenderer(context);
        this.itemRenderer = new ItemRenderer(context);
        this.gridRenderer = new GridRenderer();
    }

    /**
     * Renders the atlas and returns just the composed {@link ImageData}, satisfying the
     * {@link Renderer} contract. Callers that also need the per-tile metadata or the JSON
     * sidecar should call {@link #renderAtlas(AtlasOptions)} instead.
     *
     * @param options the atlas options
     * @return the composed atlas image
     */
    @Override
    public @NotNull ImageData render(@NotNull AtlasOptions options) {
        return renderAtlas(options).image();
    }

    /**
     * Renders the atlas and returns the full result: the composed image, the per-tile metadata
     * list, and a pre-serialised sidecar JSON string.
     *
     * @param options the atlas options
     * @return the atlas result
     */
    public @NotNull AtlasResult renderAtlas(@NotNull AtlasOptions options) {
        BlockRenderer blocks = this.blockRenderer;
        ItemRenderer items = this.itemRenderer;

        if (!options.isAnimated()) {
            RendererContext staticContext = new StaticTextureContext(this.context);
            blocks = new BlockRenderer(staticContext);
            items = new ItemRenderer(staticContext);
        }

        ConcurrentList<TileSpec> tiles = Concurrent.newList();
        if (options.getSource() != AtlasOptions.Source.ITEM)
            tiles.addAll(renderBlocks(options, blocks));
        if (options.getSource() != AtlasOptions.Source.BLOCK)
            tiles.addAll(renderItems(options, items));

        if (tiles.isEmpty())
            throw new RendererException("Atlas render produced zero tiles - nothing to compose");

        ImageData image = composeAtlas(tiles, options);
        String sidecarJson = buildSidecarJson(tiles, options.getColumns(), options.getTileSize());
        return new AtlasResult(image, tiles, sidecarJson);
    }

    /**
     * Iterates every block id the context knows about (sorted for deterministic output) and
     * renders each via {@link BlockRenderer.Isometric3D}. Failures are caught per-tile and
     * logged when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<TileSpec> renderBlocks(@NotNull AtlasOptions options, @NotNull BlockRenderer renderer) {
        ConcurrentList<TileSpec> tiles = Concurrent.newList();
        int count = 0;
        java.util.Set<String> blockstateOnly = this.context instanceof PipelineRendererContext prc
            ? prc.getBlockstateOnlyIds()
            : java.util.Set.of();

        for (String blockId : this.context.knownBlockIds()) {
            if (options.getFilter().map(f -> !f.test(blockId)).orElse(false)) continue;

            BlockOptions blockOptions = BlockOptions.builder()
                .blockId(blockId)
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .outputSize(options.getTileSize())
                .build();
            try {
                ImageData image = renderer.render(blockOptions);
                tiles.add(new TileSpec(blockId, TileSpec.Kind.BLOCK, classifyBlockSource(blockId, blockstateOnly), image));
                count++;
                if (options.isProgressLogging() && count % PROGRESS_LOG_INTERVAL == 0)
                    System.out.printf("  rendered %d block tiles...%n", count);
            } catch (RendererException ex) {
                if (options.isProgressLogging())
                    System.err.printf("  skipped block '%s': %s%n", blockId, ex.getMessage());
            }
        }

        if (options.isProgressLogging())
            System.out.printf("Block render pass complete: %d tiles%n", tiles.size());
        return tiles;
    }

    /**
     * Classifies a block tile by its registration origin. Blockstate-only ids (Task 10) win first;
     * everything else came from the primary block-model iteration (including block entities whose
     * geometry is now baked into block model elements via {@code BlockEntityLoader}).
     */
    private @NotNull TileSpec.Source classifyBlockSource(@NotNull String blockId, @NotNull java.util.Set<String> blockstateOnly) {
        return blockstateOnly.contains(blockId) ? TileSpec.Source.BLOCKSTATE_ONLY : TileSpec.Source.BLOCK_MODEL;
    }

    /**
     * Iterates every item id the context knows about (sorted for deterministic output) and
     * renders each via {@link ItemRenderer.Gui2D}. Failures are caught per-tile and logged
     * when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<TileSpec> renderItems(@NotNull AtlasOptions options, @NotNull ItemRenderer renderer) {
        ConcurrentList<TileSpec> tiles = Concurrent.newList();
        int count = 0;

        for (String itemId : this.context.knownItemIds()) {
            if (options.getFilter().map(f -> !f.test(itemId)).orElse(false)) continue;

            ItemOptions itemOptions = ItemOptions.builder()
                .itemId(itemId)
                .type(ItemOptions.Type.GUI_2D)
                .outputSize(options.getTileSize())
                .build();

            try {
                ImageData image = renderer.render(itemOptions);
                tiles.add(new TileSpec(itemId, TileSpec.Kind.ITEM, TileSpec.Source.ITEM_MODEL, image));
                count++;
                if (options.isProgressLogging() && count % PROGRESS_LOG_INTERVAL == 0)
                    System.out.printf("  rendered %d item tiles...%n", count);
            } catch (RendererException ex) {
                if (options.isProgressLogging())
                    System.err.printf("  skipped item '%s': %s%n", itemId, ex.getMessage());
            }
        }

        if (options.isProgressLogging())
            System.out.printf("Item render pass complete: %d tiles%n", tiles.size());
        return tiles;
    }

    /**
     * Composes the rendered tiles into a single grid image via {@link GridRenderer}.
     */
    private @NotNull ImageData composeAtlas(@NotNull ConcurrentList<TileSpec> tiles, @NotNull AtlasOptions options) {
        int columns = options.getColumns();
        int tileSize = options.getTileSize();
        int rows = (tiles.size() + columns - 1) / columns;

        ConcurrentList<GridOptions.GridTile> gridTiles = Concurrent.newList();
        for (int i = 0; i < tiles.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            gridTiles.add(new GridOptions.GridTile(col, row, tiles.get(i).image()));
        }

        GridOptions gridOptions = GridOptions.builder()
            .tiles(gridTiles)
            .cellSize(tileSize)
            .columns(columns)
            .rows(rows)
            .backgroundArgb(options.getBackgroundArgb())
            .build();

        return this.gridRenderer.render(gridOptions);
    }

    /**
     * Serialises the per-tile metadata into the bundled JSON sidecar format. Tiles are emitted
     * in the same order they were laid into the grid so a streaming consumer can walk the JSON
     * and the PNG in lockstep.
     */
    private static @NotNull String buildSidecarJson(@NotNull ConcurrentList<TileSpec> tiles, int columns, int tileSize) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject root = new JsonObject();
        root.addProperty("tileSize", tileSize);
        root.addProperty("columns", columns);
        root.addProperty("count", tiles.size());

        JsonArray tilesJson = new JsonArray();
        for (int i = 0; i < tiles.size(); i++) {
            TileSpec tile = tiles.get(i);
            int col = i % columns;
            int row = i / columns;
            JsonObject entry = new JsonObject();
            entry.addProperty("id", tile.id());
            entry.addProperty("kind", tile.kind().jsonName());
            entry.addProperty("source", tile.source().jsonName());
            entry.addProperty("col", col);
            entry.addProperty("row", row);
            entry.addProperty("x", col * tileSize);
            entry.addProperty("y", row * tileSize);
            entry.addProperty("width", tileSize);
            entry.addProperty("height", tileSize);
            tilesJson.add(entry);
        }
        root.add("tiles", tilesJson);
        return gson.toJson(root) + System.lineSeparator();
    }

    /**
     * A single rendered tile carrying the entity id, {@link Kind} tag, the registration
     * {@link Source} tag, and the rendered image data ready for grid composition.
     */
    public record TileSpec(@NotNull String id, @NotNull Kind kind, @NotNull Source source, @NotNull ImageData image) {

        /**
         * Kind tag emitted alongside each tile in the sidecar JSON. Serialised via
         * {@link #jsonName()} so the on-disk format stays lowercase ({@code "block"} /
         * {@code "item"}) across the enum refactor.
         */
        public enum Kind {

            /** A block tile rendered via {@link BlockRenderer}. */
            BLOCK,
            /** An item tile rendered via {@link ItemRenderer}. */
            ITEM;

            /** The lowercase kind name used in the sidecar JSON schema. */
            public @NotNull String jsonName() {
                return this.name().toLowerCase(java.util.Locale.ROOT);
            }

        }

        /**
         * Registration source tag emitted alongside {@link Kind} so diagnostics can filter tiles
         * by the pipeline path that produced them. {@link #BLOCK_MODEL} is the primary
         * {@code blockModels} iteration (including block entities whose geometry is baked into
         * block model elements via {@code BlockEntityLoader}); {@link #BLOCKSTATE_ONLY}
         * covers Task 10 blocks resolved via their blockstate when no block-model file matches
         * the id; {@link #ITEM_MODEL} is every item tile.
         */
        public enum Source {

            /** Primary {@code blockModels} iteration. */
            BLOCK_MODEL,
            /** Task 10 - transient block resolved via blockstate only (fence, wall, small_dripleaf, etc.). */
            BLOCKSTATE_ONLY,
            /** Primary {@code itemModels} iteration. */
            ITEM_MODEL;

            /** The lowercase source name used in the sidecar JSON schema. */
            public @NotNull String jsonName() {
                return this.name().toLowerCase(java.util.Locale.ROOT);
            }

        }

    }

    /**
     * The full output of an atlas render: the composed grid image, the per-tile metadata list,
     * and a pre-serialised sidecar JSON string ready to write alongside the PNG.
     */
    public record AtlasResult(
        @NotNull ImageData image,
        @NotNull ConcurrentList<TileSpec> tiles,
        @NotNull String sidecarJson
    ) {}

    /**
     * A context wrapper that flattens animated textures to their first frame. Delegates
     * every method to the wrapped context except {@link #resolveTexture} (which extracts
     * frame 0 from animation strips) and {@link #animationFor} (which returns empty so
     * downstream renderers treat every texture as static).
     */
    private record StaticTextureContext(@NotNull RendererContext delegate) implements RendererContext {

        @Override
        public @NotNull ConcurrentList<TexturePack> activePacks() {
            return this.delegate.activePacks();
        }

        @Override
        public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
            Optional<PixelBuffer> strip = this.delegate.resolveTexture(textureId);
            if (strip.isEmpty()) return strip;

            Optional<AnimationData> animation = this.delegate.animationFor(textureId);
            if (animation.isEmpty()) return strip;

            return Optional.of(AnimationKit.sampleFrame(strip.get(), animation.get(), 0));
        }

        @Override
        public @NotNull Optional<ColorMap> colorMap(ColorMap.@NotNull Type type) {
            return this.delegate.colorMap(type);
        }

        @Override
        public @NotNull Optional<Block> findBlock(@NotNull String id) {
            return this.delegate.findBlock(id);
        }

        @Override
        public @NotNull Optional<Item> findItem(@NotNull String id) {
            return this.delegate.findItem(id);
        }

        @Override
        public @NotNull Optional<Entity> findEntity(@NotNull String id) {
            return this.delegate.findEntity(id);
        }

        @Override
        public @NotNull ConcurrentList<String> knownBlockIds() {
            return this.delegate.knownBlockIds();
        }

        @Override
        public @NotNull ConcurrentList<String> knownItemIds() {
            return this.delegate.knownItemIds();
        }

        @Override
        public @NotNull Optional<dev.sbs.renderer.pipeline.loader.BlockEntityLoader.BlockEntityEntry> findBlockEntityEntry(@NotNull String blockId) {
            return this.delegate.findBlockEntityEntry(blockId);
        }

    }

}
