package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.option.AtlasOptions;
import lib.minecraft.renderer.option.AtlasSidecar;
import lib.minecraft.renderer.option.AtlasTile;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.FluidOptions;
import lib.minecraft.renderer.option.GridOptions;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.option.PortalOptions;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Renders every block and item model exposed by a {@link RendererContext} into a single grid
 * atlas image and an {@link AtlasSidecar} describing each tile's coordinates.
 * <p>
 * Implements the same {@link Renderer Renderer&lt;O&gt;} contract as the other top-level
 * renderers: a constructor takes a {@link RendererContext}, the cached {@link BlockRenderer}
 * and {@link ItemRenderer} are stored as final fields, and {@link #render(AtlasOptions)}
 * returns a single {@link ImageData}. Callers that also need the tile coordinates should call
 * {@link #renderAtlas(AtlasOptions)} instead, which returns the full {@link AtlasResult}.
 * <p>
 * Models that fail to render (templates without textures, models that reference textures the
 * pack stack does not provide, etc.) are skipped with a warning printed to stderr - one
 * misbehaving model never aborts the run. Both per-tile failure warnings and per-100-tile
 * progress logs are gated on {@link AtlasOptions#isProgressLogging()}.
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

    /** Shared render context supplying block / item indices and texture / pack lookups. */
    private final @NotNull RendererContext context;
    /** Cached renderer for plain block tiles (isometric 3D pass). */
    private final @NotNull BlockRenderer blockRenderer;
    /** Cached renderer for item tiles (GUI 2D pass). */
    private final @NotNull ItemRenderer itemRenderer;
    /** Cached renderer for the {@link #FLUID_BLOCK_IDS} tiles (water, lava). */
    private final @NotNull FluidRenderer fluidRenderer;
    /** Cached renderer for the {@link #PORTAL_BLOCK_IDS} tiles (end_portal, end_gateway). */
    private final @NotNull PortalRenderer portalRenderer;
    /** Cached grid compositor that lays the rendered tiles out into the final atlas image. */
    private final @NotNull GridRenderer gridRenderer;

    /**
     * Constructs a new {@code AtlasRenderer} bound to the given context, eagerly instantiating the
     * per-source sub-renderers (block, item, fluid, portal) and the grid compositor so a batch run
     * reuses one set across every tile.
     *
     * @param context the render context supplying block / item indices and texture lookups
     */
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
     * {@link AtlasSidecar} placing every tile in it.
     * <p>
     * When {@link AtlasOptions#isAnimated()} is unset, the four block-pass sub-renderers are
     * re-created against a {@link StaticTextureContext} so every animated texture flattens to its
     * frame 0 and the whole atlas stays a single static frame. A block or item pass is skipped
     * entirely when {@link AtlasOptions#getSource()} pins the source to the other kind.
     *
     * @param options the atlas options
     * @return the composed atlas image paired with the sidecar describing its tiles
     * @throws RenderException when the render produces zero tiles (nothing to compose)
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

        ConcurrentList<RenderedTile> tiles = Concurrent.newList();
        if (options.getSource() != AtlasOptions.Source.ITEM)
            tiles.addAll(renderBlocks(options, blocks, fluids, portals));
        if (options.getSource() != AtlasOptions.Source.BLOCK)
            tiles.addAll(renderItems(options, items));

        if (tiles.isEmpty())
            throw new RenderException("Atlas render produced zero tiles - nothing to compose");

        ImageData image = composeAtlas(tiles, options);
        AtlasSidecar sidecar = buildSidecar(tiles, options.getColumns(), options.getTileSize());
        return new AtlasResult(image, sidecar);
    }

    /**
     * Iterates every block id the context knows about (sorted for deterministic output) and
     * renders each via {@link BlockRenderer.Isometric3D}, except {@link #FLUID_BLOCK_IDS} which
     * dispatch to {@link FluidRenderer.FluidFace2D}. Block ids whose faithful inventory icon is a
     * flat item sprite ({@link #hasFlatItemIcon}) are skipped here - the item pass owns that icon,
     * so an isometric 3D tile the inventory never shows would only duplicate it. Failures are caught
     * per-tile and logged when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<RenderedTile> renderBlocks(@NotNull AtlasOptions options, @NotNull BlockRenderer renderer, @NotNull FluidRenderer fluids, @NotNull PortalRenderer portals) {
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
            .filter(blockId -> !hasFlatItemIcon(blockId))
            .map(blockId -> renderBlockTile(blockId, options, renderer, fluids, portals, completed))
            .flatMap(Optional::stream)
            .collect(Concurrent.toWideList());

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
    private @NotNull Optional<RenderedTile> renderBlockTile(
        @NotNull String blockId,
        @NotNull AtlasOptions options,
        @NotNull BlockRenderer renderer,
        @NotNull FluidRenderer fluids,
        @NotNull PortalRenderer portals,
        @NotNull AtomicInteger completed
    ) {
        try {
            ImageData image;
            AtlasTile.Source source;
            if (FLUID_BLOCK_IDS.contains(blockId)) {
                image = fluids.render(fluidOptionsFor(blockId, options.getTileSize()));
                source = AtlasTile.Source.FLUID;
            } else if (PORTAL_BLOCK_IDS.contains(blockId)) {
                image = portals.render(portalOptionsFor(blockId, options.getTileSize()));
                source = AtlasTile.Source.PORTAL;
            } else {
                BlockOptions blockOptions = BlockOptions.builder()
                    .blockId(blockId)
                    .type(BlockOptions.Type.ISOMETRIC_3D)
                    .output(OutputOptions.builder().canvasSize(options.getTileSize()).build())
                    .build();
                image = renderer.render(blockOptions);
                source = classifyBlockSource(blockId);
            }
            int now = completed.incrementAndGet();
            if (options.isProgressLogging() && now % PROGRESS_LOG_INTERVAL == 0)
                System.out.printf("  rendered %d block tiles...%n", now);
            return Optional.of(new RenderedTile(blockId, AtlasTile.Kind.BLOCK, source, image));
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
     * {@link AtlasTile.Source#BLOCK_MODEL} if the block is missing from the context (which would
     * mean we just rendered it from a synthetic id like one of {@code PORTAL_BLOCK_IDS} - those
     * paths are handled before this call).
     */
    private @NotNull AtlasTile.Source classifyBlockSource(@NotNull String blockId) {
        return this.context.findBlock(blockId)
            .map(block -> switch (block.source()) {
                case TILE_ENTITY -> AtlasTile.Source.TILE_ENTITY;
                case BLOCKSTATE_ONLY -> AtlasTile.Source.BLOCKSTATE_ONLY;
                case PRIMARY -> AtlasTile.Source.BLOCK_MODEL;
            })
            .orElse(AtlasTile.Source.BLOCK_MODEL);
    }

    /**
     * Whether an id's faithful inventory icon is a flat item sprite - i.e. it carries an item-index
     * entry. Every item-index entry is a flat {@code item/generated} sprite by construction (a
     * block-item whose vanilla model is the block model ships no {@code models/item/*.json} and is
     * absent here; block-entities are filtered out upstream), so for such an id the item pass renders
     * the vanilla inventory icon and the isometric block pass skips it to avoid a redundant 3D tile.
     * This is the same item-vs-block signal the {@link ItemOptions.Type#GUI_ICON} render mode routes
     * on, applied here as a de-duplication of the atlas block pass.
     *
     * @param id the block id being considered for the block pass
     * @return whether the id already renders its faithful icon through the item pass
     */
    private boolean hasFlatItemIcon(@NotNull String id) {
        return this.context.findItem(id).isPresent();
    }

    /**
     * Iterates every item id the context knows about (sorted for deterministic output) and renders
     * each via the faithful {@link ItemOptions.Type#GUI_ICON} path. Every atlas item id is a flat
     * sprite by construction, so this is byte-identical to {@link ItemRenderer.Gui2D} today; the
     * faithful mode is used so a future 3D item entry would still render its inventory icon. Failures
     * are caught per-tile and logged when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<RenderedTile> renderItems(@NotNull AtlasOptions options, @NotNull ItemRenderer renderer) {
        // Tile-entity items (beds, chests, banners, shulkers, signs, skulls, conduit,
        // decorated_pot, copper golem statues) already render through the block pass as
        // AtlasTile.Source.TILE_ENTITY tiles - their vanilla item models have neither elements
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
     * Renders a single item tile through the faithful {@link ItemOptions.Type#GUI_ICON} path -
     * byte-identical to {@link ItemRenderer.Gui2D} for every atlas item id, which is a flat sprite by
     * construction. Returns {@link Optional#empty()} on {@link RendererException} so one failing item
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
            .build();
        try {
            ImageData image = renderer.render(itemOptions);
            int now = completed.incrementAndGet();
            if (options.isProgressLogging() && now % PROGRESS_LOG_INTERVAL == 0)
                System.out.printf("  rendered %d item tiles...%n", now);
            return Optional.of(new RenderedTile(itemId, AtlasTile.Kind.ITEM, AtlasTile.Source.ITEM_MODEL, image));
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
    private static @NotNull AtlasSidecar buildSidecar(@NotNull ConcurrentList<RenderedTile> tiles, int columns, int tileSize) {
        return new AtlasSidecar(
            tileSize,
            columns,
            tiles.size(),
            IntStream.range(0, tiles.size())
                .mapToObj(i -> {
                    RenderedTile tile = tiles.get(i);
                    int col = i % columns;
                    int row = i / columns;

                    return new AtlasTile(
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
     * {@link AtlasTile} row carrying the grid coordinates is built once the layout is known.
     *
     * @param id the namespaced block or item id this tile was rendered from
     * @param kind whether the tile holds a block or an item
     * @param source the pipeline path that produced the tile
     * @param image the rendered tile image ready for grid composition
     */
    private record RenderedTile(
        @NotNull String id,
        @NotNull AtlasTile.Kind kind,
        @NotNull AtlasTile.Source source,
        @NotNull ImageData image
    ) {}

    /**
     * The full output of an atlas render: the composed grid image and the sidecar placing every
     * tile in it.
     *
     * @param image the composed atlas grid image
     * @param sidecar the grid layout, one row per tile in the order the tiles were laid down
     */
    public record AtlasResult(@NotNull ImageData image, @NotNull AtlasSidecar sidecar) {}

    /**
     * A context wrapper that flattens animated textures to their first frame for the static atlas.
     * Implemented as a {@link RendererContext.Forwarding} view: every lookup reaches the wrapped context
     * except the {@link #resolveTexture} frame-0 override and the explicit empty pins below, each of
     * which preserves the static atlas's prior behaviour.
     *
     * @param delegate the wrapped context every non-overridden method forwards to
     */
    private record StaticTextureContext(@NotNull RendererContext delegate) implements RendererContext.Forwarding {

        /**
         * Resolves a texture, flattening animation strips to frame 0 via
         * {@link RendererContext#resolveTextureAtTick} on the DELEGATE - animated ids sample frame 0,
         * static ids return the raw strip unchanged. Reading it off the delegate rather than off
         * {@code this} is what keeps the override from recursing into itself.
         *
         * @param textureId the namespaced texture id to resolve
         * @return the frame-0 buffer for animated textures, or the raw buffer for static ones,
         *         empty when the delegate cannot resolve the id
         */
        @Override
        public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
            return this.delegate.resolveTextureAtTick(textureId, 0);
        }

        // Pinned empty (load-bearing): forwarding re-animates the atlas; every texture must read static.
        // This is the sole surviving pin - the tint / override / gui-scaling lookups now forward to the
        // delegate so static-atlas sprites see potion tints and pack color.properties.
        @Override
        public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) {
            return Optional.empty();
        }

    }

}
