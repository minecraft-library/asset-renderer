package lib.minecraft.renderer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.pack.AnimationData;
import lib.minecraft.renderer.asset.pack.ColorMap;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.kit.AnimationKit;
import lib.minecraft.renderer.options.AtlasOptions;
import lib.minecraft.renderer.options.BlockOptions;
import lib.minecraft.renderer.options.FluidOptions;
import lib.minecraft.renderer.options.GridOptions;
import lib.minecraft.renderer.options.ItemOptions;
import lib.minecraft.renderer.options.PortalOptions;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Tile-count interval between {@code stdout} progress lines when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private static final int PROGRESS_LOG_INTERVAL = 100;

    /**
     * Block ids that render through {@link FluidRenderer} instead of {@link BlockRenderer}.
     * Vanilla {@code block/water.json} + {@code block/lava.json} carry only a {@code particle}
     * texture - they have no elements, so the standard block-model path produces a blank tile.
     * The atlas intercepts these ids in {@link #renderBlocks} and dispatches to
     * {@link FluidRenderer.FluidFace2D} so each fluid emits a flat still-texture icon.
     */
    private static final Set<String> FLUID_BLOCK_IDS = Set.of(
        "minecraft:water", "minecraft:lava"
    );

    /**
     * Block ids that render through {@link PortalRenderer} instead of {@link BlockRenderer}.
     * Vanilla {@code block/end_portal.json} carries only a {@code particle} texture and
     * {@code end_gateway} has no block-model file at all, so the standard block-model path
     * produces a blank tile for both. The atlas intercepts these ids in {@link #renderBlocks}
     * and dispatches to {@link PortalRenderer.PortalFace2D} so each portal emits a baked
     * parallax star-field tile.
     */
    private static final Set<String> PORTAL_BLOCK_IDS = Set.of(
        "minecraft:end_portal", "minecraft:end_gateway"
    );

    private final @NotNull RendererContext context;
    private final @NotNull BlockRenderer blockRenderer;
    private final @NotNull ItemRenderer itemRenderer;
    private final @NotNull FluidRenderer fluidRenderer;
    private final @NotNull PortalRenderer portalRenderer;
    private final @NotNull GridRenderer gridRenderer;

    public AtlasRenderer(@NotNull RendererContext context) {
        this.context = context;
        this.blockRenderer = new BlockRenderer(context);
        this.itemRenderer = new ItemRenderer(context);
        this.fluidRenderer = new FluidRenderer(context);
        this.portalRenderer = new PortalRenderer(context);
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
        FluidRenderer fluids = this.fluidRenderer;
        PortalRenderer portals = this.portalRenderer;

        if (!options.isAnimated()) {
            RendererContext staticContext = new StaticTextureContext(this.context);
            blocks = new BlockRenderer(staticContext);
            items = new ItemRenderer(staticContext);
            fluids = new FluidRenderer(staticContext);
            portals = new PortalRenderer(staticContext);
        }

        ConcurrentList<TileSpec> tiles = Concurrent.newList();
        if (options.getSource() != AtlasOptions.Source.ITEM)
            tiles.addAll(renderBlocks(options, blocks, fluids, portals));
        if (options.getSource() != AtlasOptions.Source.BLOCK)
            tiles.addAll(renderItems(options, items));

        if (tiles.isEmpty())
            throw new RenderException("Atlas render produced zero tiles - nothing to compose");

        ImageData image = composeAtlas(tiles, options);
        String sidecarJson = buildSidecarJson(tiles, options.getColumns(), options.getTileSize());
        return new AtlasResult(image, tiles, sidecarJson);
    }

    /**
     * Iterates every block id the context knows about (sorted for deterministic output) and
     * renders each via {@link BlockRenderer.Isometric3D}, except {@link #FLUID_BLOCK_IDS} which
     * dispatch to {@link FluidRenderer.FluidFace2D}. Failures are caught per-tile and logged
     * when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<TileSpec> renderBlocks(@NotNull AtlasOptions options, @NotNull BlockRenderer renderer, @NotNull FluidRenderer fluids, @NotNull PortalRenderer portals) {
        // end_gateway has no block-model file, and water/lava carry an empty (particle-only) model
        // so the structural empty-model filter drops them from {@code knownBlockIds()}. Both render
        // through dedicated renderers (portal / fluid) off their textures, not the block index, so
        // add them to the iteration set explicitly to keep their tiles in the atlas.
        LinkedHashSet<String> blockIds = new LinkedHashSet<>(this.context.knownBlockIds());
        blockIds.addAll(PORTAL_BLOCK_IDS);
        blockIds.addAll(FLUID_BLOCK_IDS);

        // Parallel dispatch across independent block renders. parallelStream preserves encounter
        // order through the terminal toList() collector, so composeAtlas + the sidecar JSON still
        // walk tiles in the same order a serial loop would produce. Each render owns its own
        // PixelBuffer and reads from shared ConcurrentMap caches, so there is no aliasing.
        AtomicInteger completed = new AtomicInteger();
        List<TileSpec> orderedTiles = blockIds.parallelStream()
            .filter(blockId -> options.getFilter().map(f -> f.test(blockId)).orElse(true))
            .map(blockId -> renderBlockTile(blockId, options, renderer, fluids, portals, completed))
            .flatMap(Optional::stream)
            .toList();

        ConcurrentList<TileSpec> tiles = Concurrent.newList();
        tiles.addAll(orderedTiles);

        if (options.isProgressLogging())
            System.out.printf("Block render pass complete: %d tiles%n", tiles.size());
        return tiles;
    }

    /**
     * Renders a single block tile, dispatching fluid and portal ids to their dedicated
     * renderers. Returns {@link Optional#empty()} on {@link RendererException} so one failing
     * model never aborts the atlas batch. Increments the shared completed-tile counter and
     * logs per-{@link #PROGRESS_LOG_INTERVAL} progress - log ordering is non-deterministic
     * under parallel dispatch but counts are accurate.
     */
    private @NotNull Optional<TileSpec> renderBlockTile(
        @NotNull String blockId,
        @NotNull AtlasOptions options,
        @NotNull BlockRenderer renderer,
        @NotNull FluidRenderer fluids,
        @NotNull PortalRenderer portals,
        @NotNull AtomicInteger completed
    ) {
        try {
            ImageData image;
            TileSpec.Source source;
            if (FLUID_BLOCK_IDS.contains(blockId)) {
                image = fluids.render(fluidOptionsFor(blockId, options.getTileSize()));
                source = TileSpec.Source.FLUID;
            } else if (PORTAL_BLOCK_IDS.contains(blockId)) {
                image = portals.render(portalOptionsFor(blockId, options.getTileSize()));
                source = TileSpec.Source.PORTAL;
            } else {
                BlockOptions blockOptions = BlockOptions.builder()
                    .blockId(blockId)
                    .type(BlockOptions.Type.ISOMETRIC_3D)
                    .outputSize(options.getTileSize())
                    .build();
                image = renderer.render(blockOptions);
                source = classifyBlockSource(blockId);
            }
            int now = completed.incrementAndGet();
            if (options.isProgressLogging() && now % PROGRESS_LOG_INTERVAL == 0)
                System.out.printf("  rendered %d block tiles...%n", now);
            return Optional.of(new TileSpec(blockId, TileSpec.Kind.BLOCK, source, image));
        } catch (RendererException ex) {
            if (options.isProgressLogging())
                System.err.printf("  skipped block '%s': %s%n", blockId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Builds the {@link FluidOptions} used for atlas fluid tiles. Fixes the render type to
     * {@link FluidOptions.Type#FLUID_FACE_2D} so each fluid emits a flat tinted still-texture
     * icon sized to the atlas tile. The isometric 3D path is deliberately not used here - it
     * carries the full cube/flow/slope pipeline that is out of scope for a static atlas tile.
     */
    private static @NotNull FluidOptions fluidOptionsFor(@NotNull String blockId, int tileSize) {
        FluidOptions.Fluid fluid = blockId.equals("minecraft:lava")
            ? FluidOptions.Fluid.LAVA
            : FluidOptions.Fluid.WATER;
        return FluidOptions.builder()
            .fluid(fluid)
            .type(FluidOptions.Type.FLUID_FACE_2D)
            .outputSize(tileSize)
            .build();
    }

    /**
     * Builds the {@link PortalOptions} used for atlas portal tiles. Fixes the render type to
     * {@link PortalOptions.Type#PORTAL_FACE_2D} so each portal emits a flat baked parallax
     * sprite sized to the atlas tile. The isometric 3D path stays out of the atlas - it carries
     * the full slab / cube geometry that callers outside the atlas (3D debug dumps, tooling)
     * can request separately via {@link PortalRenderer}.
     */
    private static @NotNull PortalOptions portalOptionsFor(@NotNull String blockId, int tileSize) {
        PortalOptions.Portal portal = blockId.equals("minecraft:end_gateway")
            ? PortalOptions.Portal.END_GATEWAY
            : PortalOptions.Portal.END_PORTAL;
        return PortalOptions.builder()
            .portal(portal)
            .type(PortalOptions.Type.PORTAL_FACE_2D)
            .outputSize(tileSize)
            .build();
    }

    /**
     * Classifies a block tile by its registration origin, reading the source flag the
     * {@link RendererContext} stores on the {@link Block} itself. Falls back to
     * {@link TileSpec.Source#BLOCK_MODEL} if the block is missing from the context (which would
     * mean we just rendered it from a synthetic id like one of {@code PORTAL_BLOCK_IDS} - those
     * paths are handled before this call).
     */
    private @NotNull TileSpec.Source classifyBlockSource(@NotNull String blockId) {
        return this.context.findBlock(blockId)
            .map(block -> switch (block.getSource()) {
                case TILE_ENTITY -> TileSpec.Source.TILE_ENTITY;
                case BLOCKSTATE_ONLY -> TileSpec.Source.BLOCKSTATE_ONLY;
                case PRIMARY -> TileSpec.Source.BLOCK_MODEL;
            })
            .orElse(TileSpec.Source.BLOCK_MODEL);
    }

    /**
     * Iterates every item id the context knows about (sorted for deterministic output) and
     * renders each via {@link ItemRenderer.Gui2D}. Failures are caught per-tile and logged
     * when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<TileSpec> renderItems(@NotNull AtlasOptions options, @NotNull ItemRenderer renderer) {
        // Tile-entity items (beds, chests, banners, shulkers, signs, skulls, conduit,
        // decorated_pot, copper golem statues) render through the block pass as
        // {@link TileSpec.Source#TILE_ENTITY} tiles - their vanilla item models have
        // neither elements nor layer0 and would produce blank icons if we rendered them
        // here. Skip so the atlas emits exactly one tile per TE id.
        // <p>
        // Additive entries (bell-overlay attached to bell_floor / bell_wall / bell_between_walls)
        // keep their item tile because the underlying {@code item/<id>.json} carries a real
        // {@code layer0} icon - the entity overlay only enriches the block-tile render, not
        // the inventory icon.
        AtomicInteger completed = new AtomicInteger();
        List<TileSpec> orderedTiles = this.context.knownItemIds().parallelStream()
            .filter(itemId -> options.getFilter().map(f -> f.test(itemId)).orElse(true))
            .filter(itemId -> !this.context.findBlockEntityEntry(itemId)
                .map(be -> !be.additive()).orElse(false))
            .map(itemId -> renderItemTile(itemId, options, renderer, completed))
            .flatMap(Optional::stream)
            .toList();

        ConcurrentList<TileSpec> tiles = Concurrent.newList();
        tiles.addAll(orderedTiles);

        if (options.isProgressLogging())
            System.out.printf("Item render pass complete: %d tiles%n", tiles.size());
        return tiles;
    }

    /**
     * Renders a single item tile through {@link ItemRenderer.Gui2D}. Returns
     * {@link Optional#empty()} on {@link RendererException} so one failing item never aborts
     * the atlas batch. Increments the shared completed-tile counter and logs per-
     * {@link #PROGRESS_LOG_INTERVAL} progress - log ordering is non-deterministic under
     * parallel dispatch but counts are accurate.
     */
    private @NotNull Optional<TileSpec> renderItemTile(
        @NotNull String itemId,
        @NotNull AtlasOptions options,
        @NotNull ItemRenderer renderer,
        @NotNull AtomicInteger completed
    ) {
        ItemOptions itemOptions = ItemOptions.builder()
            .itemId(itemId)
            .type(ItemOptions.Type.GUI_2D)
            .outputSize(options.getTileSize())
            .build();
        try {
            ImageData image = renderer.render(itemOptions);
            int now = completed.incrementAndGet();
            if (options.isProgressLogging() && now % PROGRESS_LOG_INTERVAL == 0)
                System.out.printf("  rendered %d item tiles...%n", now);
            return Optional.of(new TileSpec(itemId, TileSpec.Kind.ITEM, TileSpec.Source.ITEM_MODEL, image));
        } catch (RendererException ex) {
            if (options.isProgressLogging())
                System.err.printf("  skipped item '%s': %s%n", itemId, ex.getMessage());
            return Optional.empty();
        }
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
            .background(options.getBackground())
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

            /**
             * A block tile rendered via {@link BlockRenderer}.
             */
            BLOCK,
            /**
             * An item tile rendered via {@link ItemRenderer}.
             */
            ITEM;

            /**
             * The lowercase kind name used in the sidecar JSON schema.
             */
            public @NotNull String jsonName() {
                return this.name().toLowerCase(Locale.ROOT);
            }

        }

        /**
         * Registration source tag emitted alongside {@link Kind} so diagnostics can filter tiles
         * by the pipeline path that produced them.
         * <ul>
         * <li>{@link #BLOCK_MODEL} - primary {@code blockModels} iteration (plain blocks whose
         *     geometry is fully described by {@code block.json}).</li>
         * <li>{@link #BLOCKSTATE_ONLY} - blocks resolved via blockstate when no block-model file
         *     matches the id (fences, walls, small_dripleaf, etc.).</li>
         * <li>{@link #TILE_ENTITY} - blocks whose geometry comes from a {@link Block.Entity} -
         *     vanilla {@code BlockEntityRenderer} geometry baked into block model elements by
         *     {@link BlockModelLoader} (beds, chests, banners,
         *     shulkers, signs, skulls, conduit, decorated_pot, etc.).</li>
         * <li>{@link #FLUID} - block rendered through {@link FluidRenderer} from the still fluid
         *     texture (water, lava). Vanilla {@code block/water.json} and {@code block/lava.json}
         *     carry no elements, so the fluid renderer supplies the atlas tile instead.</li>
         * <li>{@link #PORTAL} - block rendered through {@link PortalRenderer} via a CPU-baked
         *     parallax star-field (end_portal, end_gateway). Vanilla ships only a
         *     particle-texture block model for end_portal and no block model at all for
         *     end_gateway, so the portal renderer supplies the atlas tile instead.</li>
         * <li>{@link #ITEM_MODEL} - primary {@code itemModels} iteration.</li>
         * </ul>
         */
        public enum Source {

            /**
             * Primary {@code blockModels} iteration.
             */
            BLOCK_MODEL,
            /**
             * Transient block resolved via blockstate only (fence, wall, small_dripleaf, etc.).
             */
            BLOCKSTATE_ONLY,
            /**
             * Block carrying a {@link Block.Entity} - tile-entity geometry baked into block elements.
             */
            TILE_ENTITY,
            /**
             * Block rendered via {@link FluidRenderer.FluidFace2D} (water, lava).
             */
            FLUID,
            /**
             * Block rendered via {@link PortalRenderer.PortalFace2D} (end_portal, end_gateway).
             */
            PORTAL,
            /**
             * Primary {@code itemModels} iteration.
             */
            ITEM_MODEL;

            /**
             * The lowercase source name used in the sidecar JSON schema.
             */
            public @NotNull String jsonName() {
                return this.name().toLowerCase(Locale.ROOT);
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
     * frame 0 from animation strips) and {@link #findAnimation} (which returns empty so
     * downstream renderers treat every texture as static).
     */
    private record StaticTextureContext(@NotNull RendererContext delegate) implements RendererContext {

        @Override
        public @NotNull Optional<TexturePack> findPack(@NotNull String id) {
            return this.delegate.findPack(id);
        }

        @Override
        public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
            Optional<PixelBuffer> strip = this.delegate.resolveTexture(textureId);
            if (strip.isEmpty()) return strip;

            Optional<AnimationData> animation = this.delegate.findAnimation(textureId);
            return animation.map(animationData -> AnimationKit.sampleFrame(
                strip.get(),
                animationData,
                0)
            ).or(() -> strip);
        }

        @Override
        public @NotNull Optional<ColorMap> findColorMap(ColorMap.@NotNull Type type) {
            return this.delegate.findColorMap(type);
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
        public @NotNull Optional<Block.Entity> findBlockEntityEntry(@NotNull String blockId) {
            return this.delegate.findBlockEntityEntry(blockId);
        }

    }

}
