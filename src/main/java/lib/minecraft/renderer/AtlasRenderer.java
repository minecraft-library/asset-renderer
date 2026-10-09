package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.call.request.AtlasOptions;
import lib.minecraft.renderer.call.request.FluidOptions;
import lib.minecraft.renderer.call.request.GridOptions;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PortalOptions;
import lib.minecraft.renderer.call.result.AtlasResult;
import lib.minecraft.renderer.call.result.GridResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Renders every block and item model exposed by a {@link RendererContext} into a single grid
 * atlas image and a {@link AtlasResult.Sidecar sidecar} describing each tile's coordinates.
 * <p>
 * Implements the same {@link Renderer Renderer&lt;O&gt;} contract as the other top-level
 * renderers: a constructor takes a {@link RendererContext}, the cached {@link ItemRenderer},
 * {@link FluidRenderer} and {@link PortalRenderer} are stored as final fields, and
 * {@link #render(AtlasOptions)} answers an {@link AtlasResult} - the composed grid and the sidecar
 * placing every tile in it, each tile carrying the stand-ins its render drew.
 * <p>
 * The atlas is a static sheet. Its sub-renderers draw over a view of the context whose texture source
 * answers each texture's frame at tick 0, so an animated texture shows its first frame.
 * <p>
 * A tile whose render throws a {@link RendererException} is left out of the grid and recorded as a
 * {@link AtlasResult.Skipped Skipped} row in the sidecar, with a warning on stderr when progress logging
 * is on, so one unexpected failure never aborts the run. A missing asset is not such a failure - it
 * draws its missing picture, as the paragraph below says - so the catch guards the batch against what
 * nothing foresees, an id the context lists and its lookup then answers absent among it. Both per-tile
 * failure warnings and per-100-tile progress logs are gated on {@link AtlasOptions#isProgressLogging()};
 * the skipped rows are not.
 * <p>
 * A subject the pack stack cannot fully supply is not one of those. A texture no pack supplies or that
 * cannot be read draws the checkerboard, as it does in every render - a fluid's and a portal's
 * included - and so does a face whose {@code #variable} chain resolves to no texture, which the model
 * walk looks up by its raw reference; the tile is kept, so the sheet shows what is broken.
 * <p>
 * A registered id that draws nothing, such as air or cave_air, is not missing anything: the context
 * lists it beside the ids that draw, and its tile is transparent and labelled
 * {@link AtlasResult.Tile.Source#EMPTY EMPTY}.
 *
 * <p>What it reads and never hands back is its own, so it nests here. {@link #FLUID_BLOCK_IDS} and
 * {@link #PORTAL_BLOCK_IDS} name the block ids whose vanilla model draws a blank tile, and which the
 * block pass hands to the fluid or the portal renderer instead; the known ids are laid down in the
 * order the context answers them, related subjects next to each other. What it hands back - the
 * sidecar and its rows - nests in {@link AtlasResult}, the one result that names them.
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

    /** Shared render context supplying the block and item indices the passes walk. */
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
     * per-source sub-renderers (item, fluid, portal) over a view of it whose texture source samples
     * tick 0, and the grid compositor, so a batch run reuses one set across every tile.
     *
     * @param context the render context supplying block / item indices and texture lookups
     */
    public AtlasRenderer(@NotNull RendererContext context) {
        RendererContext staticContext = context.withTextures(textureId ->
            Flipbook.atTick(context.resolveTexture(textureId), context.findFlipbook(textureId), 0));
        this.context = context;
        this.itemRenderer = new ItemRenderer(staticContext);
        this.fluidRenderer = new FluidRenderer(staticContext);
        this.portalRenderer = new PortalRenderer(staticContext);
        this.gridRenderer = new GridRenderer();
    }

    /**
     * Renders the atlas and answers the full result: the composed image and the
     * {@link AtlasResult.Sidecar sidecar} placing every tile in it, each tile carrying the stand-ins its
     * render drew.
     * <p>
     * A block or item pass is skipped entirely when {@link AtlasOptions#getSource()} pins the source to
     * the other kind.
     *
     * @param options the atlas options
     * @return the composed atlas image paired with the sidecar describing its tiles and the subjects it
     *     left out
     * @throws RenderException when the render produces zero tiles (nothing to compose)
     */
    @Override
    public @NotNull AtlasResult render(@NotNull AtlasOptions options) {
        ConcurrentList<Outcome> outcomes = Concurrent.newList();
        if (options.getSource() != AtlasOptions.Scope.ITEM)
            outcomes.addAll(renderBlocks(options));
        if (options.getSource() != AtlasOptions.Scope.BLOCK)
            outcomes.addAll(renderItems(options));

        // Both lists keep the passes' order, block pass first, so the skipped rows are as
        // deterministic as the tiles.
        ConcurrentList<RenderedTile> tiles = Concurrent.newList();
        ConcurrentList<AtlasResult.Skipped> skipped = Concurrent.newList();
        for (Outcome outcome : outcomes) {
            switch (outcome) {
                case RenderedTile tile -> tiles.add(tile);
                case Refused refused -> skipped.add(refused.row());
            }
        }

        if (tiles.isEmpty())
            throw new RenderException("Atlas render produced zero tiles - nothing to compose");

        GridResult grid = composeAtlas(tiles, options);
        return new AtlasResult(grid.image(), buildSidecar(tiles, grid, skipped, options.getColumns(), options.getTileSize()));
    }

    /**
     * Iterates every block id the context knows about, in the order the context answers them, and
     * renders each as its slot icon, the {@link ItemOptions.Type#GUI_ICON} render that draws a
     * block-backed id through {@link BlockRenderer.Isometric3D}, except
     * {@link #FLUID_BLOCK_IDS} which dispatch to {@link FluidRenderer.FluidFace2D} and
     * {@link #PORTAL_BLOCK_IDS} to {@link PortalRenderer}. Block ids the item index
     * knows ({@link #hasItemEntry}) are skipped here - the item pass owns that icon, so a second
     * tile would only duplicate it. Failures are caught per tile, each answering a skipped row, and
     * logged when {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<Outcome> renderBlocks(@NotNull AtlasOptions options) {
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
        ConcurrentList<Outcome> outcomes = blockIds.parallelStream()
            .filter(blockId -> options.getFilter().map(f -> f.test(blockId)).orElse(true))
            .filter(blockId -> !hasItemEntry(blockId))
            .map(blockId -> renderBlockTile(blockId, options, completed))
            .collect(Concurrent.toWideList());

        if (options.isProgressLogging())
            System.out.printf("Block render pass complete: %d tiles%n", completed.get());
        return outcomes;
    }

    /**
     * Renders a single block tile, dispatching fluid and portal ids to their dedicated renderers and
     * every other id through the {@link ItemOptions.Type#GUI_ICON} render, so a tile is the slot icon,
     * its faces tinted by the item definition rather than by a biome. The item options name the tile
     * size and nothing else, which the slot icon's block branch carries onto the same isometric block
     * options a plain block render would build. Answers a skipped row on {@link RendererException} so
     * one unexpected failure never aborts the atlas batch. Increments the shared completed-tile counter
     * and logs per-{@link #PROGRESS_LOG_INTERVAL} progress - log ordering is non-deterministic
     * under parallel dispatch but counts are accurate.
     */
    private @NotNull Outcome renderBlockTile(
        @NotNull String blockId,
        @NotNull AtlasOptions options,
        @NotNull AtomicInteger completed
    ) {
        try {
            RenderResult result;
            AtlasResult.Tile.Source source;
            if (FLUID_BLOCK_IDS.contains(blockId)) {
                result = this.fluidRenderer.render(fluidOptionsFor(blockId, options.getTileSize()));
                source = AtlasResult.Tile.Source.FLUID;
            } else if (PORTAL_BLOCK_IDS.contains(blockId)) {
                result = this.portalRenderer.render(portalOptionsFor(blockId, options.getTileSize()));
                source = AtlasResult.Tile.Source.PORTAL;
            } else {
                ItemOptions iconOptions = ItemOptions.builder()
                    .itemId(blockId)
                    .type(ItemOptions.Type.GUI_ICON)
                    .output(OutputOptions.builder().canvasSize(options.getTileSize()).build())
                    .build();
                result = this.itemRenderer.render(iconOptions);
                source = classifyBlockSource(blockId);
            }
            int now = completed.incrementAndGet();
            if (options.isProgressLogging() && now % PROGRESS_LOG_INTERVAL == 0)
                System.out.printf("  rendered %d block tiles...%n", now);
            return new RenderedTile(blockId, AtlasResult.Tile.Kind.BLOCK, source, result);
        } catch (RendererException ex) {
            if (options.isProgressLogging())
                System.err.printf("  skipped block '%s': %s%n", blockId, ex.getMessage());
            return Refused.of(blockId, AtlasResult.Tile.Kind.BLOCK, ex);
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
     * Classifies a block tile by what the context's lookup answers for it: a present block by its
     * registration origin, the source flag the {@link RendererContext} stores on the {@link Block}
     * itself, and a block the index knows as drawing nothing as
     * {@link AtlasResult.Tile.Source#EMPTY EMPTY}. The fluid and portal ids, which the context does not
     * know at all, are dispatched before this call.
     *
     * @param blockId the block id the pass walked
     * @return the tile's source label
     * @throws RenderException when the id is among the context's known block ids and its lookup answers
     *     absent, which the tile's catch turns into a skipped row
     */
    private @NotNull AtlasResult.Tile.Source classifyBlockSource(@NotNull String blockId) {
        Possible<Block> block = this.context.findBlock(blockId);
        return switch (block.getState()) {
            case PRESENT -> switch (block.get().source()) {
                case TILE_ENTITY -> AtlasResult.Tile.Source.BLOCK_ENTITY;
                case BLOCKSTATE_ONLY -> AtlasResult.Tile.Source.BLOCKSTATE_ONLY;
                case PRIMARY -> AtlasResult.Tile.Source.BLOCK_MODEL;
            };
            case EMPTY -> AtlasResult.Tile.Source.EMPTY;
            case ABSENT -> throw listedAbsent(blockId);
        };
    }

    /**
     * Classifies an item tile by what the context's lookup answers for it: a present item as
     * {@link AtlasResult.Tile.Source#ITEM_MODEL ITEM_MODEL}, and an item the index knows as drawing
     * nothing as {@link AtlasResult.Tile.Source#EMPTY EMPTY}.
     *
     * @param itemId the item id the pass walked
     * @return the tile's source label
     * @throws RenderException when the id is among the context's known item ids and its lookup answers
     *     absent, which the tile's catch turns into a skipped row
     */
    private @NotNull AtlasResult.Tile.Source classifyItemSource(@NotNull String itemId) {
        return switch (this.context.findItem(itemId).getState()) {
            case PRESENT -> AtlasResult.Tile.Source.ITEM_MODEL;
            case EMPTY -> AtlasResult.Tile.Source.EMPTY;
            case ABSENT -> throw listedAbsent(itemId);
        };
    }

    /**
     * Builds the refusal for an id the pass walked because the context listed it, and whose lookup then
     * answered absent - a context whose known ids and lookups disagree. It is a
     * {@link RendererException}, so the tile's own catch turns it into a skipped row and the batch runs
     * on.
     *
     * @param id the listed id
     * @return the refusal to throw
     */
    private static @NotNull RenderException listedAbsent(@NotNull String id) {
        return new RenderException("Atlas id '%s' is among the context's known ids but its lookup answers absent", id);
    }

    /**
     * Whether the item pass draws an id's slot icon: an id the item index knows, whether its item
     * draws or is a registered item that draws nothing, such as air. A block-item whose vanilla model
     * is the block model ships no {@code models/item/*.json} and is absent from the item index, so the
     * block pass draws it; an id the index knows is drawn once, by the item pass, through the same
     * {@link ItemOptions.Type#GUI_ICON} render - which itself sends an item model built from a block
     * parent's elements to the block branch.
     *
     * @param id the block id being considered for the block pass
     * @return whether the id already renders its slot icon through the item pass
     */
    private boolean hasItemEntry(@NotNull String id) {
        return !this.context.findItem(id).isAbsent();
    }

    /**
     * Iterates every item id the context knows about, in the order the context answers them, and
     * renders each as its slot icon, the faithful {@link ItemOptions.Type#GUI_ICON} render. Failures are
     * caught per tile, each answering a skipped row, and logged when
     * {@link AtlasOptions#isProgressLogging()} is set.
     */
    private @NotNull ConcurrentList<Outcome> renderItems(@NotNull AtlasOptions options) {
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
        ConcurrentList<Outcome> outcomes = this.context.knownItemIds().parallelStream()
            .filter(itemId -> options.getFilter().map(f -> f.test(itemId)).orElse(true))
            .filter(itemId -> !this.context.findBlockEntityEntry(itemId)
                .map(be -> !be.additive()).orElse(false))
            .map(itemId -> renderItemTile(itemId, options, completed))
            .collect(Concurrent.toWideList());

        if (options.isProgressLogging())
            System.out.printf("Item render pass complete: %d tiles%n", completed.get());
        return outcomes;
    }

    /**
     * Renders a single item tile as its slot icon, the faithful {@link ItemOptions.Type#GUI_ICON}
     * render. Answers a skipped row on {@link RendererException} so one unexpected failure never
     * aborts the atlas batch. Increments the shared completed-tile counter and logs per-
     * {@link #PROGRESS_LOG_INTERVAL} progress - log ordering is non-deterministic under parallel
     * dispatch but counts are accurate.
     */
    private @NotNull Outcome renderItemTile(
        @NotNull String itemId,
        @NotNull AtlasOptions options,
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
            RenderResult result = this.itemRenderer.render(itemOptions);
            AtlasResult.Tile.Source source = classifyItemSource(itemId);
            int now = completed.incrementAndGet();
            if (options.isProgressLogging() && now % PROGRESS_LOG_INTERVAL == 0)
                System.out.printf("  rendered %d item tiles...%n", now);
            return new RenderedTile(itemId, AtlasResult.Tile.Kind.ITEM, source, result);
        } catch (RendererException ex) {
            if (options.isProgressLogging())
                System.err.printf("  skipped item '%s': %s%n", itemId, ex.getMessage());
            return Refused.of(itemId, AtlasResult.Tile.Kind.ITEM, ex);
        }
    }

    /**
     * Composes the rendered tiles into one grid through {@link GridRenderer}, answering the grid's
     * result, whose cells keep tile order.
     */
    private @NotNull GridResult composeAtlas(@NotNull ConcurrentList<RenderedTile> tiles, @NotNull AtlasOptions options) {
        int columns = options.getColumns();
        int tileSize = options.getTileSize();
        int rows = (tiles.size() + columns - 1) / columns;

        ConcurrentList<GridOptions.GridTile> gridTiles = IntStream.range(0, tiles.size())
            .mapToObj(i -> new GridOptions.GridTile(i % columns, i / columns, tiles.get(i).result()))
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
     * Builds the sidecar row for every tile, reading each one's cell, and the stand-ins its render drew,
     * off the grid's result, which keeps tile order. Rows are emitted in the same order the tiles were
     * laid into the grid so a streaming consumer can walk the sidecar and the PNG in lockstep; the
     * skipped subjects follow, holding no cell and not counted.
     */
    private static @NotNull AtlasResult.Sidecar buildSidecar(
        @NotNull ConcurrentList<RenderedTile> tiles,
        @NotNull GridResult grid,
        @NotNull ConcurrentList<AtlasResult.Skipped> skipped,
        int columns,
        int tileSize
    ) {
        return new AtlasResult.Sidecar(
            tileSize,
            columns,
            tiles.size(),
            IntStream.range(0, tiles.size())
                .mapToObj(i -> {
                    RenderedTile tile = tiles.get(i);
                    GridResult.Cell cell = grid.cells().get(i);

                    return new AtlasResult.Tile(
                        tile.id(),
                        tile.kind(),
                        tile.source(),
                        cell.col(),
                        cell.row(),
                        cell.x(),
                        cell.y(),
                        cell.width(),
                        cell.height(),
                        cell.result().substitutions()
                    );
                })
                .collect(Concurrent.toList()),
            skipped
        );
    }

    /**
     * What a pass answers for one subject: the tile it rendered, or the refusal that left the subject
     * out of the grid.
     */
    private sealed interface Outcome permits RenderedTile, Refused {}

    /**
     * Everything a render pass knows about one tile before the grid layout assigns it a position:
     * the subject it was rendered from, how that subject was classified, and its render. It lives only
     * for the length of a render and is never serialised - the {@link AtlasResult.Tile} row carrying
     * the grid coordinates is built once the layout is known.
     *
     * @param id the namespaced block or item id this tile was rendered from
     * @param kind whether the tile holds a block or an item
     * @param source the pipeline path that produced the tile
     * @param result the tile's render, its image and the stand-ins drawn in it
     */
    private record RenderedTile(
        @NotNull String id,
        @NotNull AtlasResult.Tile.Kind kind,
        @NotNull AtlasResult.Tile.Source source,
        @NotNull RenderResult result
    ) implements Outcome {}

    /**
     * A subject whose tile the per-tile catch dropped, carried to the sidecar as its skipped row. A
     * skipped tile's partial stand-ins go with it, as it drew nothing the sheet keeps.
     *
     * @param row the sidecar row naming the subject, the pass that met it and the refusal's message
     */
    private record Refused(@NotNull AtlasResult.Skipped row) implements Outcome {

        /**
         * Builds the outcome for a subject whose tile threw, its reason the refusal's message - or the
         * refusal's type where a subclass answers no message.
         *
         * @param id the subject the pass met
         * @param kind the pass that met it
         * @param refusal what the tile threw
         * @return the outcome
         */
        static @NotNull Refused of(@NotNull String id, @NotNull AtlasResult.Tile.Kind kind, @NotNull RendererException refusal) {
            String reason = Objects.requireNonNullElse(refusal.getMessage(), refusal.getClass().getSimpleName());
            return new Refused(new AtlasResult.Skipped(id, kind, reason));
        }

    }

}
