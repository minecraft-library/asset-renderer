package lib.minecraft.renderer;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.JsonTree;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.content.index.BlockModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.request.AtlasOptions;
import lib.minecraft.renderer.request.FluidOptions;
import lib.minecraft.renderer.request.GridOptions;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.request.OutputOptions;
import lib.minecraft.renderer.request.PortalOptions;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Renders every block and item model exposed by a {@link RendererContext} into a single grid
 * atlas image and a {@link Sidecar} describing each tile's coordinates.
 * <p>
 * Implements the same {@link Renderer Renderer&lt;O&gt;} contract as the other top-level
 * renderers: a constructor takes a {@link RendererContext}, the cached {@link ItemRenderer},
 * {@link FluidRenderer} and {@link PortalRenderer} are stored as final fields, and
 * {@link #render(AtlasOptions)} returns a single {@link ImageData}. Callers that also need the tile coordinates should call
 * {@link #renderAtlas(AtlasOptions)} instead, which returns the full {@link Result}.
 * <p>
 * Models that fail to render are skipped with a warning printed to stderr - one misbehaving model
 * never aborts the run. Both per-tile failure warnings and per-100-tile progress logs are gated on
 * {@link AtlasOptions#isProgressLogging()}.
 * <p>
 * A subject whose texture the pack stack does not supply is one of those, because
 * {@link AtlasOptions#isSubstituteMissing()} is off here where a single render leaves it on: the
 * lookup raises, the per-tile catch drops the tile, and the sheet is smaller by one. Turning it on
 * keeps the tile and draws the checkerboard instead, which is the view for auditing what a pack is
 * missing rather than for looking a subject up.
 * <p>
 * So is a subject whose model names a face whose {@code #variable} chain resolves to no texture: the
 * model walk looks that face up by its raw reference, which no pack supplies, so with the flag off the
 * tile is dropped, and with it on the face draws the checkerboard, as vanilla draws its missing sprite
 * there.
 *
 * <p>What it reads and emits is its own, so it nests here. {@link #FLUID_BLOCK_IDS} and
 * {@link #PORTAL_BLOCK_IDS} name the block ids whose vanilla model draws a blank tile, and which the
 * block pass hands to the fluid or the portal renderer instead; the known ids are laid down in the
 * order the context answers them, related subjects next to each other. {@link Result} is
 * the whole output - the composed grid image and the sidecar placing every tile in it.
 * {@link Sidecar} is the typed {@code atlas.json} schema, parsed and written by one type so neither
 * side spells it out twice, and {@link Tile} is one row of it: the subject a tile was rendered from,
 * its kind and source path, and where it sits in the grid. A row carries coordinates and never
 * pixels.
 *
 * <p><b>Parity.</b> Reaches the atlas alone, which this store holds no artifact for. What measured
 * that is the claim above rather than this paragraph, so the two cannot come to disagree.
 */
@Parity(claim = "atlas-unhashable", mode = Mode.SUPPRESS, subject = Subject.ATLAS)
@Parity(subject = Subject.ATLAS)
public final class AtlasRenderer implements Renderer<AtlasOptions> {

    /**
     * Tile-count interval between {@code stdout} progress lines when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private static final int PROGRESS_LOG_INTERVAL = 100;

    /**
     * Block ids that render through {@code FluidRenderer} instead of {@code BlockRenderer}.
     * Vanilla {@code block/water.json} + {@code block/lava.json} carry only a {@code particle}
     * texture - they have no elements, so the standard block-model path produces a blank tile.
     * The atlas dispatches these ids to the flat fluid face so each fluid emits a still-texture icon.
     */
    private static final @NotNull Set<String> FLUID_BLOCK_IDS = Set.of(
        "minecraft:water", "minecraft:lava"
    );

    /**
     * Block ids that render through {@code PortalRenderer} instead of {@code BlockRenderer}.
     * Vanilla {@code block/end_portal.json} carries only a {@code particle} texture and
     * {@code end_gateway} has no block-model file at all, so the standard block-model path
     * produces a blank tile for both. The atlas dispatches these ids to the flat portal face so
     * each portal emits a baked parallax star-field tile.
     */
    private static final @NotNull Set<String> PORTAL_BLOCK_IDS = Set.of(
        "minecraft:end_portal", "minecraft:end_gateway"
    );

    /** Shared render context supplying block / item indices and texture / pack lookups. */
    private final @NotNull RendererContext context;
    /** Cached renderer for every item tile and every plain block tile, both drawn as the slot icon. */
    private final @NotNull ItemRenderer itemRenderer;
    /** Cached renderer for the {@link #FLUID_BLOCK_IDS} tiles (water, lava). */
    private final @NotNull FluidRenderer fluidRenderer;
    /** Cached renderer for the {@link #PORTAL_BLOCK_IDS} tiles (end_portal, end_gateway). */
    private final @NotNull PortalRenderer portalRenderer;
    /** Cached grid compositor that lays the rendered tiles out into the final atlas image. */
    private final @NotNull GridRenderer gridRenderer;

    /**
     * Constructs a new {@code AtlasRenderer} bound to the given context, eagerly instantiating the
     * per-source sub-renderers (item, fluid, portal) and the grid compositor so a batch run reuses one
     * set across every tile.
     *
     * @param context the render context supplying block / item indices and texture lookups
     */
    public AtlasRenderer(@NotNull RendererContext context) {
        this.context = context;
        this.itemRenderer = new ItemRenderer(context);
        this.fluidRenderer = new FluidRenderer(context);
        this.portalRenderer = new PortalRenderer(context);
        this.gridRenderer = new GridRenderer();
    }

    /**
     * Renders the atlas and returns just the composed {@link ImageData}, satisfying the
     * {@link Renderer} contract. Callers that also need the sidecar should call
     * {@link #renderAtlas(AtlasOptions)} instead.
     *
     * @param options the atlas options
     * @return the composed atlas image
     */
    @Override
    public @NotNull ImageData render(@NotNull AtlasOptions options) {
        return renderAtlas(options).image();
    }

    /**
     * Renders the atlas and returns the full result: the composed image and the
     * {@link Sidecar} placing every tile in it.
     * <p>
     * When {@link AtlasOptions#isAnimated()} is unset, the three sub-renderers are re-created against
     * a context whose texture source samples frame 0, so every animated texture flattens to a single still and the whole atlas stays one static frame. A block or item pass is
     * skipped entirely when {@link AtlasOptions#getSource()} pins the source to the other kind.
     *
     * @param options the atlas options
     * @return the composed atlas image paired with the sidecar describing its tiles
     * @throws RenderException when the render produces zero tiles (nothing to compose)
     */
    public @NotNull Result renderAtlas(@NotNull AtlasOptions options) {
        ItemRenderer items = this.itemRenderer;
        FluidRenderer fluids = this.fluidRenderer;
        PortalRenderer portals = this.portalRenderer;

        if (!options.isAnimated()) {
            RendererContext staticContext = this.context.withTextures(textureId ->
                Flipbook.atTick(this.context.resolveTexture(textureId), this.context.findFlipbook(textureId), 0));
            items = new ItemRenderer(staticContext);
            fluids = new FluidRenderer(staticContext);
            portals = new PortalRenderer(staticContext);
        }

        ConcurrentList<RenderedTile> tiles = Concurrent.newList();
        if (options.getSource() != AtlasOptions.Scope.ITEM)
            tiles.addAll(renderBlocks(options, items, fluids, portals));
        if (options.getSource() != AtlasOptions.Scope.BLOCK)
            tiles.addAll(renderItems(options, items));

        if (tiles.isEmpty())
            throw new RenderException("Atlas render produced zero tiles - nothing to compose");

        ImageData image = composeAtlas(tiles, options);
        Sidecar sidecar = buildSidecar(tiles, options.getColumns(), options.getTileSize());
        return new Result(image, sidecar);
    }

    /**
     * Iterates every block id the context knows about (sorted for deterministic output) and
     * renders each as its slot icon, the {@link ItemOptions.Type#GUI_ICON} render that draws a
     * block-backed id through {@link BlockRenderer.Isometric3D}, except
     * {@link #FLUID_BLOCK_IDS} which dispatch to {@link FluidRenderer.FluidFace2D} and
     * {@link #PORTAL_BLOCK_IDS} to {@link PortalRenderer}. Block ids the item index
     * carries ({@link #hasItemEntry}) are skipped here - the item pass owns that icon, so a second
     * tile would only duplicate it. Failures are caught per-tile and logged when
     * {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<RenderedTile> renderBlocks(@NotNull AtlasOptions options, @NotNull ItemRenderer renderer, @NotNull FluidRenderer fluids, @NotNull PortalRenderer portals) {
        // end_gateway has no block-model file, and water/lava carry an empty (particle-only) model
        // so the structural empty-model filter drops them from {@code knownBlockIds()}. Both render
        // through dedicated renderers (portal / fluid) off their textures, not the block index, so
        // add them to the iteration set explicitly to keep their tiles in the atlas.
        LinkedHashSet<String> blockIds = new LinkedHashSet<>(this.context.knownBlockIds());
        blockIds.addAll(PORTAL_BLOCK_IDS);
        blockIds.addAll(FLUID_BLOCK_IDS);

        // Parallel dispatch across independent block renders. parallelStream preserves encounter
        // order through the terminal collector, so composeAtlas + buildSidecar still walk tiles in
        // the same order a serial loop would produce. Each render owns its own PixelBuffer and
        // reads from shared ConcurrentMap caches, so there is no aliasing.
        AtomicInteger completed = new AtomicInteger();
        ConcurrentList<RenderedTile> tiles = blockIds.parallelStream()
            .filter(blockId -> options.getFilter().map(f -> f.test(blockId)).orElse(true))
            .filter(blockId -> !hasItemEntry(blockId))
            .map(blockId -> renderBlockTile(blockId, options, renderer, fluids, portals, completed))
            .flatMap(Optional::stream)
            .collect(Concurrent.toWideList());

        if (options.isProgressLogging())
            System.out.printf("Block render pass complete: %d tiles%n", tiles.size());
        return tiles;
    }

    /**
     * Renders a single block tile, dispatching fluid and portal ids to their dedicated renderers and
     * every other id through the {@link ItemOptions.Type#GUI_ICON} render, so a tile is the slot icon,
     * its faces tinted by the item definition rather than by a biome. The item options name the tile
     * size, the substitution flag and nothing else, which the slot icon's block branch carries onto
     * the same isometric block options a plain block render would build. Returns
     * {@link Optional#empty()} on {@link RendererException} so one failing model never aborts the
     * atlas batch. Increments the shared completed-tile counter and
     * logs per-{@link #PROGRESS_LOG_INTERVAL} progress - log ordering is non-deterministic
     * under parallel dispatch but counts are accurate.
     */
    private @NotNull Optional<RenderedTile> renderBlockTile(
        @NotNull String blockId,
        @NotNull AtlasOptions options,
        @NotNull ItemRenderer renderer,
        @NotNull FluidRenderer fluids,
        @NotNull PortalRenderer portals,
        @NotNull AtomicInteger completed
    ) {
        try {
            ImageData image;
            Tile.Source source;
            if (FLUID_BLOCK_IDS.contains(blockId)) {
                image = fluids.render(fluidOptionsFor(blockId, options.getTileSize()));
                source = Tile.Source.FLUID;
            } else if (PORTAL_BLOCK_IDS.contains(blockId)) {
                image = portals.render(portalOptionsFor(blockId, options.getTileSize()));
                source = Tile.Source.PORTAL;
            } else {
                ItemOptions iconOptions = ItemOptions.builder()
                    .itemId(blockId)
                    .type(ItemOptions.Type.GUI_ICON)
                    .output(OutputOptions.builder().canvasSize(options.getTileSize()).build())
                    .substituteMissing(options.isSubstituteMissing())
                    .build();
                image = renderer.render(iconOptions);
                source = classifyBlockSource(blockId);
            }
            int now = completed.incrementAndGet();
            if (options.isProgressLogging() && now % PROGRESS_LOG_INTERVAL == 0)
                System.out.printf("  rendered %d block tiles...%n", now);
            return Optional.of(new RenderedTile(blockId, Tile.Kind.BLOCK, source, image));
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
            .output(OutputOptions.builder().canvasSize(tileSize).build())
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
            .output(OutputOptions.builder().canvasSize(tileSize).build())
            .build();
    }

    /**
     * Classifies a block tile by its registration origin, reading the source flag the
     * {@link RendererContext} stores on the {@link Block} itself. Falls back to
     * {@link Tile.Source#BLOCK_MODEL} if the block is missing from the context (which would
     * mean we just rendered it from a synthetic id like one of
     * {@link #PORTAL_BLOCK_IDS} - those
     * paths are handled before this call).
     */
    private @NotNull Tile.Source classifyBlockSource(@NotNull String blockId) {
        return this.context.findBlock(blockId)
            .map(block -> switch (block.source()) {
                case TILE_ENTITY -> Tile.Source.BLOCK_ENTITY;
                case BLOCKSTATE_ONLY -> Tile.Source.BLOCKSTATE_ONLY;
                case PRIMARY -> Tile.Source.BLOCK_MODEL;
            })
            .orElse(Tile.Source.BLOCK_MODEL);
    }

    /**
     * Whether an id carries an item-index entry, so the item pass draws its slot icon. A block-item
     * whose vanilla model is the block model ships no {@code models/item/*.json} and is absent from
     * the item index, so the block pass draws it; an id the index carries is drawn once, by the item
     * pass, through the same {@link ItemOptions.Type#GUI_ICON} render - which itself sends an item
     * model built from a block parent's elements to the block branch.
     *
     * @param id the block id being considered for the block pass
     * @return whether the id already renders its slot icon through the item pass
     */
    private boolean hasItemEntry(@NotNull String id) {
        return this.context.findItem(id).isPresent();
    }

    /**
     * Iterates every item id the context knows about (sorted for deterministic output) and renders
     * each as its slot icon, the faithful {@link ItemOptions.Type#GUI_ICON} render. Failures are
     * caught per-tile and logged when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<RenderedTile> renderItems(@NotNull AtlasOptions options, @NotNull ItemRenderer renderer) {
        // Tile-entity items (beds, chests, banners, shulkers, signs, skulls, conduit,
        // decorated_pot, copper golem statues) already render through the block pass as
        // Tile.Source.BLOCK_ENTITY tiles - their vanilla item models have neither elements
        // nor layer0 and would produce blank icons if rendered here. Skip them (the filter below
        // drops non-additive block-entity ids) so the atlas emits exactly one tile per TE id.
        //
        // Additive entries (bell-overlay attached to bell_floor / bell_wall / bell_between_walls)
        // keep their item tile because the underlying item/<id>.json carries a real layer0 icon -
        // the entity overlay only enriches the block-tile render, not the inventory icon.
        AtomicInteger completed = new AtomicInteger();
        ConcurrentList<RenderedTile> tiles = this.context.knownItemIds().parallelStream()
            .filter(itemId -> options.getFilter().map(f -> f.test(itemId)).orElse(true))
            .filter(itemId -> !this.context.findBlockEntityEntry(itemId)
                .map(be -> !be.additive()).orElse(false))
            .map(itemId -> renderItemTile(itemId, options, renderer, completed))
            .flatMap(Optional::stream)
            .collect(Concurrent.toWideList());

        if (options.isProgressLogging())
            System.out.printf("Item render pass complete: %d tiles%n", tiles.size());
        return tiles;
    }

    /**
     * Renders a single item tile as its slot icon, the faithful {@link ItemOptions.Type#GUI_ICON}
     * render. Returns {@link Optional#empty()} on {@link RendererException} so one failing item
     * never aborts the atlas batch. Increments the shared completed-tile counter and logs per-
     * {@link #PROGRESS_LOG_INTERVAL} progress - log ordering is non-deterministic under parallel
     * dispatch but counts are accurate.
     */
    private @NotNull Optional<RenderedTile> renderItemTile(
        @NotNull String itemId,
        @NotNull AtlasOptions options,
        @NotNull ItemRenderer renderer,
        @NotNull AtomicInteger completed
    ) {
        // animateGlint(false): intrinsically-foil items (enchanted_book, nether_star, ...) render
        // their glint as a single static frame-0 here. An animated glint tile would force the
        // whole atlas onto GridRenderer's animated path (FrameCompositor), turning an otherwise-static
        // atlas into an animated one; the atlas wants exactly one frame per tile.
        ItemOptions itemOptions = ItemOptions.builder()
            .itemId(itemId)
            .type(ItemOptions.Type.GUI_ICON)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(options.getTileSize()).build())
            .animateGlint(false)
            .substituteMissing(options.isSubstituteMissing())
            .build();
        try {
            ImageData image = renderer.render(itemOptions);
            int now = completed.incrementAndGet();
            if (options.isProgressLogging() && now % PROGRESS_LOG_INTERVAL == 0)
                System.out.printf("  rendered %d item tiles...%n", now);
            return Optional.of(new RenderedTile(itemId, Tile.Kind.ITEM, Tile.Source.ITEM_MODEL, image));
        } catch (RendererException ex) {
            if (options.isProgressLogging())
                System.err.printf("  skipped item '%s': %s%n", itemId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Composes the rendered tiles into a single grid image via {@link GridRenderer}.
     */
    private @NotNull ImageData composeAtlas(@NotNull ConcurrentList<RenderedTile> tiles, @NotNull AtlasOptions options) {
        int columns = options.getColumns();
        int tileSize = options.getTileSize();
        int rows = (tiles.size() + columns - 1) / columns;

        ConcurrentList<GridOptions.GridTile> gridTiles = IntStream.range(0, tiles.size())
            .mapToObj(i -> new GridOptions.GridTile(i % columns, i / columns, tiles.get(i).image()))
            .collect(Concurrent.toWideUnmodifiableList());

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
     * Builds the sidecar row for every tile, reading each one's grid position off the index the
     * compositor laid it down at. Rows are emitted in the same order the tiles were laid into the
     * grid so a streaming consumer can walk the sidecar and the PNG in lockstep.
     */
    private static @NotNull Sidecar buildSidecar(@NotNull ConcurrentList<RenderedTile> tiles, int columns, int tileSize) {
        return new Sidecar(
            tileSize,
            columns,
            tiles.size(),
            IntStream.range(0, tiles.size())
                .mapToObj(i -> {
                    RenderedTile tile = tiles.get(i);
                    int col = i % columns;
                    int row = i / columns;

                    return new Tile(
                        tile.id(),
                        tile.kind(),
                        tile.source(),
                        col,
                        row,
                        col * tileSize,
                        row * tileSize,
                        tileSize,
                        tileSize
                    );
                })
                .collect(Concurrent.toList())
        );
    }

    /**
     * Everything a render pass knows about one tile before the grid layout assigns it a position:
     * the subject it was rendered from, how that subject was classified, and the pixels the
     * compositor lays down. It lives only for the length of a render and is never serialised - the
     * {@link Tile} row carrying the grid coordinates is built once the layout is known.
     *
     * @param id the namespaced block or item id this tile was rendered from
     * @param kind whether the tile holds a block or an item
     * @param source the pipeline path that produced the tile
     * @param image the rendered tile image ready for grid composition
     */
    private record RenderedTile(
        @NotNull String id,
        @NotNull Tile.Kind kind,
        @NotNull Tile.Source source,
        @NotNull ImageData image
    ) {}

    /**
     * The full output of an atlas render: the composed grid image and the sidecar placing every
     * tile in it.
     *
     * @param image the composed atlas grid image
     * @param sidecar the grid layout, one row per tile in the order the tiles were laid down
     */
    public record Result(@NotNull ImageData image, @NotNull Sidecar sidecar) {}

    /**
     * The atlas sidecar schema, typed - the one statement of what {@code atlas.json} holds, shared by
     * the {@link AtlasRenderer} that builds it and every reader that walks an atlas afterwards.
     *
     * <p>{@link #parse} reads a sidecar and {@link #toJson} builds the node a caller writes, so neither
     * side spells the schema out a second time. Tiles are kept in grid-layout order so streaming
     * consumers walk JSON + PNG in lockstep. The {@code build/atlas/} output stays scratch - never a
     * bundled resource.
     *
     * @param tileSize the per-tile edge length in pixels
     * @param columns the grid column count
     * @param count the tile count (== {@code tiles.size()})
     * @param tiles the tiles in grid order
     */
    public record Sidecar(int tileSize, int columns, int count, @NotNull List<Tile> tiles) {

        /**
         * Parses a sidecar from its JSON root (the read side - diagnose, atlas verify).
         *
         * @param root the parsed sidecar JSON
         * @return the typed sidecar
         * @throws IllegalArgumentException when a tile's kind or source names no constant
         */
        public static @NotNull Sidecar parse(@NotNull JsonTree root) {
            ConcurrentList<Tile> tiles = root.find("tiles")
                .map(array -> array.elements()
                    .map(Sidecar::parseTile)
                    .collect(Concurrent.toWideUnmodifiableList()))
                .orElseGet(Concurrent::newUnmodifiableList);

            return new Sidecar(
                root.getInt("tileSize", 0),
                root.getInt("columns", 0),
                root.getInt("count", 0),
                tiles);
        }

        /**
         * Reads one tile row, resolving its lowercase kind and source tokens back to their constants.
         *
         * @param row the tile object from the sidecar's {@code tiles} array
         * @return the typed tile
         * @throws IllegalArgumentException when the row's kind or source names no constant
         */
        private static @NotNull Tile parseTile(@NotNull JsonTree row) {
            String kindToken = row.getString("kind", "");
            String sourceToken = row.getString("source", "");
            Tile.Kind kind = Tile.Kind.findByJsonName(kindToken)
                .orElseThrow(() -> unknownToken("Tile.Kind", kindToken));
            Tile.Source source = Tile.Source.findByJsonName(sourceToken)
                .orElseThrow(() -> unknownToken("Tile.Source", sourceToken));

            return new Tile(
                row.getString("id", ""),
                kind,
                source,
                row.getInt("col", 0),
                row.getInt("row", 0),
                row.getInt("x", 0),
                row.getInt("y", 0),
                row.getInt("width", 0),
                row.getInt("height", 0));
        }

        /**
         * Builds the failure for a token no constant of the named enum answers to.
         *
         * @param enumName the enum the token was resolved against
         * @param token the unrecognised token
         * @return the failure to throw
         */
        private static @NotNull IllegalArgumentException unknownToken(@NotNull String enumName, @NotNull String token) {
            return new IllegalArgumentException(String.format("Unknown %s token '%s'", enumName, token));
        }

        /**
         * Serialises this sidecar to a JSON node (the write side - grid-layout tile order preserved).
         *
         * @return the sidecar JSON node
         */
        public @NotNull JsonTree toJson() {
            JsonTree root = JsonTree.object()
                .putInt("tileSize", this.tileSize)
                .putInt("columns", this.columns)
                .putInt("count", this.count);
            JsonTree array = root.childArray("tiles");
            for (Tile tile : this.tiles)
                array.add(JsonTree.object()
                    .put("id", tile.id())
                    .put("kind", tile.kind().jsonName())
                    .put("source", tile.source().jsonName())
                    .putInt("col", tile.col())
                    .putInt("row", tile.row())
                    .putInt("x", tile.x())
                    .putInt("y", tile.y())
                    .putInt("width", tile.width())
                    .putInt("height", tile.height()));
            return root;
        }

    }

    /**
     * One row of a {@link Sidecar}: the subject a tile was rendered from, how that tile was
     * classified, and where it sits in the composed grid.
     *
     * <p>A row carries coordinates and no pixels - the grid position only exists once every tile has
     * been laid out, and the composed atlas image is what holds the pixels. The x/y-vs-col/row
     * redundancy is kept because external consumers walk it.
     *
     * @param id the namespaced block or item id the tile was rendered from
     * @param kind whether the tile holds a block or an item
     * @param source the pipeline path that produced the tile
     * @param col the grid column the tile occupies
     * @param row the grid row the tile occupies
     * @param x the tile's left pixel edge in the atlas ({@code col * tileSize})
     * @param y the tile's top pixel edge in the atlas ({@code row * tileSize})
     * @param width the tile's pixel width
     * @param height the tile's pixel height
     */
    public record Tile(
        @NotNull String id,
        @NotNull Kind kind,
        @NotNull Source source,
        int col,
        int row,
        int x,
        int y,
        int width,
        int height
    ) {

        /**
         * Kind tag emitted alongside each tile in the sidecar JSON. Serialised via {@link #jsonName}
         * so the on-disk format stays lowercase ({@code "block"} / {@code "item"}).
         */
        @EnumLookup
        @Getter(style = NamingStyle.FLUENT)
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
             * Lowercase kind name used in the sidecar JSON schema, derived once at class-load time from
             * {@link #name()}.
             */
            @KeyField
            private final @NotNull String jsonName = this.name().toLowerCase(Locale.ROOT);

        }

        /**
         * Registration source tag emitted alongside {@link Kind} so diagnostics can filter tiles
         * by the pipeline path that produced them.
         * <ul>
         * <li>{@link #BLOCK_MODEL} - primary {@code blockModels} iteration (plain blocks whose
         *     geometry is fully described by {@code block.json}).</li>
         * <li>{@link #BLOCKSTATE_ONLY} - blocks resolved via blockstate when no block-model file
         *     matches the id (fences, walls, small_dripleaf, etc.).</li>
         * <li>{@link #BLOCK_ENTITY} - blocks whose geometry comes from a {@link Block.BlockEntity} -
         *     vanilla {@code BlockEntityRenderer} geometry baked into block model elements by
         *     {@link BlockModelLoader} (beds, chests, banners, shulkers, signs, skulls, conduit,
         *     decorated_pot, etc.).</li>
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
        @EnumLookup
        @Getter(style = NamingStyle.FLUENT)
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
             * Block carrying a {@link Block.BlockEntity} - tile-entity geometry baked into block elements.
             */
            BLOCK_ENTITY,
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
             * Lowercase source name used in the sidecar JSON schema, derived once at class-load time
             * from {@link #name()}.
             */
            @KeyField
            private final @NotNull String jsonName = this.name().toLowerCase(Locale.ROOT);

        }

    }

}
