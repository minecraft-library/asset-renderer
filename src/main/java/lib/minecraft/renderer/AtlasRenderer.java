package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.pixel.PixelBuffer;
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
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntFunction;
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
 * The atlas hands {@link GridRenderer} one tile for every subject the passes walk, in pass order, the
 * tile's content that subject's render - so the grid renders each tile on its own workers, draws it into
 * its cell and lets it go, and the sheet never holds every tile at once. A refused subject draws nothing
 * where it stands; once a sheet has met a refusal it is laid down again over the subjects still standing,
 * so every kept tile sits where a sheet without the refused subjects puts it. A bake that meets no
 * refusal - every bake over a context whose lists and lookups agree and whose packs read - lays down one
 * sheet; a sheet on which a refusal first appears is rendered whole before it is laid down again.
 * <p>
 * {@link #write(AtlasOptions, Path)} renders the same sheet straight into a PNG file a band of tile rows
 * at a time, so a caller who wants the file never has the sheet held whole.
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
 * the skipped rows are not. Each line is printed once for its subject however many sheets a bake lays
 * down, and the two pass lines once the last sheet is done.
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

    /**
     * The tile rows each band of a written sheet holds, before a checkerboard background raises it - four
     * rows of the default sheet are 64 tiles to render beside each other and a 4 MiB band.
     */
    private static final int BAND_ROWS = 4;

    /** What a refused subject's tile answers the grid: one transparent pixel, which draws nothing, so its cell keeps the background. */
    private static final @NotNull RenderResult REFUSED = RenderResult.of(Timeline.empty());

    /** Shared render context supplying the block and item indices the passes walk. */
    private final @NotNull RendererContext context;
    /** Cached renderer for every item tile and every plain block tile, both drawn as the slot icon. */
    private final @NotNull ItemRenderer itemRenderer;
    /** Cached renderer for the {@link #FLUID_BLOCK_IDS} tiles (water, lava). */
    private final @NotNull FluidRenderer fluidRenderer;
    /** Cached renderer for the {@link #PORTAL_BLOCK_IDS} tiles (end_portal, end_gateway). */
    private final @NotNull PortalRenderer portalRenderer;
    /** Cached grid compositor that renders every tile into its cell of the final atlas image. */
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
     * @throws RenderException when the column count or the tile size is not positive, or when the render
     *     produces zero tiles (nothing to compose)
     */
    @Override
    public @NotNull AtlasResult render(@NotNull AtlasOptions options) {
        if (options.getColumns() < 1 || options.getTileSize() < 1)
            throw new RenderException("Atlas columns and tile size must be positive, were '%s' and '%s'", options.getColumns(), options.getTileSize());

        Bake bake = new Bake(options, candidates(options));
        while (true) {
            List<Integer> standing = bake.standing();
            if (standing.isEmpty()) {
                bake.logPasses();
                throw new RenderException("Atlas render produced zero tiles - nothing to compose");
            }

            Optional<AtlasResult> atlas = layDown(bake, standing);
            if (atlas.isPresent()) {
                bake.logPasses();
                return atlas.get();
            }
        }
    }

    /**
     * Lays one sheet down over the subjects still standing, each one's tile in the cell its rank among
     * them gives it.
     *
     * @param bake the bake's state
     * @param standing the candidates still standing, in pass order
     * @return the atlas, or empty when the sheet met a refusal and must be laid down again
     */
    private @NotNull Optional<AtlasResult> layDown(@NotNull Bake bake, @NotNull List<Integer> standing) {
        AtlasOptions options = bake.options;
        int columns = options.getColumns();
        int rows = (standing.size() + columns - 1) / columns;
        ConcurrentList<GridOptions.GridTile> tiles = IntStream.range(0, standing.size())
            .mapToObj(i -> new GridOptions.GridTile(i % columns, i / columns, () -> renderTile(bake, standing.get(i))))
            .collect(Concurrent.toWideUnmodifiableList());

        GridResult grid = this.gridRenderer.render(GridOptions.builder()
            .tiles(tiles)
            .cellSize(options.getTileSize())
            .columns(columns)
            .rows(rows)
            .background(options.getBackground())
            .recycleTiles(true)
            .build());

        // Every tile's content renders afresh and the bake keeps only what its subject settled as, so
        // the grid may lend each tile's array to the next: GridOptions.recycleTiles.
        // A sheet that met a refusal has a refused subject's empty cell among its tiles; it is dropped
        // here, before the next sheet is allocated.
        if (bake.refusedAny(standing))
            return Optional.empty();

        return Optional.of(new AtlasResult(grid.image(), bake.sidecar(standing, grid.cells())));
    }

    /**
     * Renders the atlas straight into a PNG file and answers the sidecar placing every tile in it - the
     * sheet {@link #render} answers, encoded as the image library's PNG writer encodes it, without the
     * whole sheet ever being held.
     * <p>
     * The sheet is composed a band of tile rows at a time, as the PNG writer reaches each band, and each
     * band is let go once its rows are encoded, so the heap holds one band and the tiles rendering into
     * it rather than the sheet. A sheet that meets a refusal is written whole and then written again over
     * the subjects still standing, as {@link #render} lays it down again, so the file is moved into place
     * only once a sheet meets none. Until then it is written beside {@code file}, under the same name with
     * {@code .partial} appended, and nothing is left there however the write ends.
     *
     * @param options the atlas options
     * @param file the PNG file to write, replaced when it exists
     * @return the sidecar placing every tile the file holds
     * @throws RenderException when the column count or the tile size is not positive, or when the render
     *     produces zero tiles
     * @throws IOException when the file cannot be written
     */
    public @NotNull AtlasResult.Sidecar write(@NotNull AtlasOptions options, @NotNull Path file) throws IOException {
        if (options.getColumns() < 1 || options.getTileSize() < 1)
            throw new RenderException("Atlas columns and tile size must be positive, were '%s' and '%s'", options.getColumns(), options.getTileSize());

        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        Bake bake = new Bake(options, candidates(options));

        try {
            while (true) {
                List<Integer> standing = bake.standing();
                if (standing.isEmpty()) {
                    bake.logPasses();
                    throw new RenderException("Atlas render produced zero tiles - nothing to compose");
                }

                Optional<AtlasResult.Sidecar> sidecar = writeDown(bake, standing, partial);
                if (sidecar.isPresent()) {
                    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
                    bake.logPasses();
                    return sidecar.get();
                }
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /**
     * Writes one sheet over the subjects still standing into a PNG file, each tile in the cell its rank
     * among them gives it, a band of tile rows at a time.
     * <p>
     * Each band is a grid of its own over the tiles in its rows, filled with the atlas background and
     * drawn as {@link #layDown} draws the whole sheet; each band's cells are moved down by the rows above
     * it, so the sidecar places every tile where the whole sheet does.
     *
     * @param bake the bake's state
     * @param standing the candidates still standing, in pass order
     * @param file the PNG file to write, replaced when it exists
     * @return the sidecar, or empty when the sheet met a refusal and must be written again
     * @throws IOException when the file cannot be written
     */
    private @NotNull Optional<AtlasResult.Sidecar> writeDown(@NotNull Bake bake, @NotNull List<Integer> standing, @NotNull Path file) throws IOException {
        AtlasOptions options = bake.options;
        int columns = options.getColumns();
        int tileSize = options.getTileSize();
        int rows = (standing.size() + columns - 1) / columns;
        int bandRows = bandRows(options.getBackground(), tileSize);
        GridResult.Cell[] cells = new GridResult.Cell[standing.size()];

        writePng(new BandedSheet(columns * tileSize, rows * tileSize, bandRows * tileSize, band -> {
            int firstRow = band * bandRows;
            int lastRow = Math.min(rows, firstRow + bandRows);
            int first = firstRow * columns;
            int end = Math.min(standing.size(), lastRow * columns);
            ConcurrentList<GridOptions.GridTile> tiles = IntStream.range(first, end)
                .mapToObj(i -> new GridOptions.GridTile(i % columns, i / columns - firstRow, () -> renderTile(bake, standing.get(i))))
                .collect(Concurrent.toWideUnmodifiableList());

            GridResult grid = this.gridRenderer.render(GridOptions.builder()
                .tiles(tiles)
                .cellSize(tileSize)
                .columns(columns)
                .rows(lastRow - firstRow)
                .background(options.getBackground())
                .recycleTiles(true)
                .build());

            for (GridResult.Cell cell : grid.cells())
                cells[first + cell.index()] = new GridResult.Cell(first + cell.index(), cell.col(), cell.row() + firstRow,
                    cell.x(), cell.y() + firstRow * tileSize, cell.width(), cell.height(), cell.substitutions());

            return grid.image().toPixelBuffer();
        }), file);

        if (bake.refusedAny(standing))
            return Optional.empty();

        return Optional.of(bake.sidecar(standing, Concurrent.newList(Arrays.asList(cells))));
    }

    /**
     * Answers how many tile rows each band of a written sheet holds - {@link #BAND_ROWS}, raised on a
     * checkerboard background to a multiple of the rows that bring a band's top to a row where the
     * checkerboard starts a pair of its cells, so each band fills the background the whole sheet does.
     *
     * @param background the atlas background
     * @param tileSize the tile edge in pixels
     * @return the tile rows per band
     */
    static int bandRows(@NotNull Background background, int tileSize) {
        if (!(background instanceof Background.Checkerboard board))
            return BAND_ROWS;

        int period = 2 * Math.max(1, board.cellSize());
        int a = tileSize;
        int b = period;
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }

        int step = period / a;
        return (BAND_ROWS + step - 1) / step * step;
    }

    /**
     * Encodes an image into a PNG file through the PNG writer the image library encodes with, at its
     * default parameters, so the same pixels encode to the same bytes either way.
     *
     * @param image the image to encode
     * @param file the file to write, replaced when it exists
     * @throws IOException when the file cannot be written
     */
    private static void writePng(@NotNull RenderedImage image, @NotNull Path file) throws IOException {
        Files.deleteIfExists(file);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();

        try (ImageOutputStream output = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
    }

    /**
     * Lists every subject the passes walk, the block pass first: each block id the context knows
     * about, in the order the context answers them, then the portal and fluid ids, that the filter keeps
     * and whose slot icon the item pass does not own; then each item id the context knows about, in the
     * order the context answers them, that the filter keeps and whose icon the block pass does not
     * already draw. A pass the options scope out lists nothing.
     *
     * @param options the atlas options
     * @return the candidates, in pass order
     */
    private @NotNull List<Candidate> candidates(@NotNull AtlasOptions options) {
        List<Candidate> candidates = new ArrayList<>();
        if (options.getSource() != AtlasOptions.Scope.ITEM) {
            // end_gateway has no block-model file, and water/lava carry an empty (particle-only) model
            // so the structural empty-model filter drops them from {@code knownBlockIds()}. Both render
            // through dedicated renderers (portal / fluid) off their textures, not the block index, so
            // add them to the iteration set explicitly to keep their tiles in the atlas.
            LinkedHashSet<String> blockIds = new LinkedHashSet<>(this.context.knownBlockIds());
            blockIds.addAll(PORTAL_BLOCK_IDS);
            blockIds.addAll(FLUID_BLOCK_IDS);
            blockIds.stream()
                .filter(blockId -> options.getFilter().map(f -> f.test(blockId)).orElse(true))
                .filter(blockId -> !hasItemEntry(blockId))
                .forEach(blockId -> candidates.add(new Candidate(blockId, AtlasResult.Tile.Kind.BLOCK)));
        }
        if (options.getSource() != AtlasOptions.Scope.BLOCK) {
            // Tile-entity items (beds, chests, banners, shulkers, signs, skulls, conduit,
            // decorated_pot, copper golem statues) already render through the block pass as
            // Tile.Source.BLOCK_ENTITY tiles - their vanilla item models have neither elements
            // nor layer0 and would produce blank icons if rendered here. Skip them (the filter below
            // drops non-additive block-entity ids) so the atlas emits exactly one tile per TE id.
            //
            // Additive entries (bell-overlay attached to bell_floor / bell_wall / bell_between_walls)
            // keep their item tile because the underlying item/<id>.json carries a real layer0 icon -
            // the entity overlay only enriches the block-tile render, not the inventory icon.
            this.context.knownItemIds().stream()
                .filter(itemId -> options.getFilter().map(f -> f.test(itemId)).orElse(true))
                .filter(itemId -> !this.context.findBlockEntityEntry(itemId)
                    .map(be -> !be.additive()).orElse(false))
                .forEach(itemId -> candidates.add(new Candidate(itemId, AtlasResult.Tile.Kind.ITEM)));
        }
        return List.copyOf(candidates);
    }

    /**
     * Renders one candidate's tile on the grid worker that draws it, and records it as kept, or as refused
     * on a {@link RendererException} - caught here, on the thread that rendered it, so one unexpected
     * failure never aborts the atlas batch.
     *
     * @param bake the bake's state
     * @param index the candidate's index
     * @return the tile's render, or {@link #REFUSED} for a refused subject
     */
    private @NotNull RenderResult renderTile(@NotNull Bake bake, int index) {
        Candidate candidate = bake.candidates.get(index);
        try {
            Drawn drawn = switch (candidate.kind()) {
                case BLOCK -> drawBlock(candidate.id(), bake.options);
                case ITEM -> drawItem(candidate.id(), bake.options);
            };
            bake.keep(index, drawn.source());
            return drawn.result();
        } catch (RendererException ex) {
            bake.refuse(index, ex);
            return REFUSED;
        }
    }

    /**
     * Renders a single block tile, dispatching fluid and portal ids to their dedicated renderers and
     * every other id through the {@link ItemOptions.Type#GUI_ICON} render that draws a block-backed id
     * through {@link BlockRenderer.Isometric3D}, so a tile is the slot icon, its faces tinted by the item
     * definition rather than by a biome. The item options name the tile size and nothing else, which the
     * slot icon's block branch carries onto the same isometric block options a plain block render would
     * build. {@link #FLUID_BLOCK_IDS} dispatch to {@link FluidRenderer.FluidFace2D} and
     * {@link #PORTAL_BLOCK_IDS} to {@link PortalRenderer}.
     *
     * @param blockId the block id the pass walked
     * @param options the atlas options
     * @return the tile's render and its source label
     * @throws RendererException when the render or the classification refuses the subject
     */
    private @NotNull Drawn drawBlock(@NotNull String blockId, @NotNull AtlasOptions options) {
        if (FLUID_BLOCK_IDS.contains(blockId))
            return new Drawn(this.fluidRenderer.render(fluidOptionsFor(blockId, options.getTileSize())), AtlasResult.Tile.Source.FLUID);
        if (PORTAL_BLOCK_IDS.contains(blockId))
            return new Drawn(this.portalRenderer.render(portalOptionsFor(blockId, options.getTileSize())), AtlasResult.Tile.Source.PORTAL);

        ItemOptions iconOptions = ItemOptions.builder()
            .itemId(blockId)
            .type(ItemOptions.Type.GUI_ICON)
            .output(OutputOptions.builder().canvasSize(options.getTileSize()).build())
            .build();
        RenderResult result = this.itemRenderer.render(iconOptions);
        return new Drawn(result, classifyBlockSource(blockId));
    }

    /**
     * Renders a single item tile as its slot icon, the faithful {@link ItemOptions.Type#GUI_ICON}
     * render.
     *
     * @param itemId the item id the pass walked
     * @param options the atlas options
     * @return the tile's render and its source label
     * @throws RendererException when the render or the classification refuses the subject
     */
    private @NotNull Drawn drawItem(@NotNull String itemId, @NotNull AtlasOptions options) {
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
        RenderResult result = this.itemRenderer.render(itemOptions);
        return new Drawn(result, classifyItemSource(itemId));
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
     * One subject a pass walks: its id, and the pass that walks it.
     *
     * @param id the namespaced block or item id
     * @param kind the pass that walks it
     */
    private record Candidate(@NotNull String id, @NotNull AtlasResult.Tile.Kind kind) {}

    /**
     * A tile's render and the pipeline path that produced it.
     *
     * @param result the tile's render, its image and the stand-ins drawn in it
     * @param source the pipeline path that produced the tile
     */
    private record Drawn(@NotNull RenderResult result, @NotNull AtlasResult.Tile.Source source) {}

    /** What a candidate's tile settled as: kept with its source label, or refused with its sidecar row. */
    private sealed interface Settled permits Kept, Refused {}

    /**
     * A candidate whose tile rendered and classified.
     *
     * @param source the pipeline path that produced the tile
     */
    private record Kept(@NotNull AtlasResult.Tile.Source source) implements Settled {}

    /**
     * A candidate whose tile the per-tile catch dropped, carried to the sidecar as its skipped row. A
     * skipped tile's partial stand-ins go with it, as it drew nothing the sheet keeps.
     *
     * @param row the sidecar row naming the subject, the pass that met it and the refusal's message
     */
    private record Refused(@NotNull AtlasResult.Skipped row) implements Settled {}

    /**
     * One bake's state, shared by the grid workers that render its tiles: the candidates, what each
     * settled as, and the counts behind the progress lines. A candidate's first settling is what prints
     * its line, so a subject drawn on more than one sheet logs once; a refusal replaces a keep, and a kept
     * tile drawn again keeps its first settling.
     */
    private static final class Bake {

        /** The atlas options. */
        private final @NotNull AtlasOptions options;

        /** Every subject the passes walk, in pass order. */
        private final @NotNull List<Candidate> candidates;

        /** What each candidate settled as, by candidate index, {@code null} before its tile renders. */
        private final @NotNull AtomicReferenceArray<Settled> settled;

        /** Block tiles kept so far, counted for the progress lines. */
        private final @NotNull AtomicInteger blocksKept = new AtomicInteger();

        /** Item tiles kept so far, counted for the progress lines. */
        private final @NotNull AtomicInteger itemsKept = new AtomicInteger();

        private Bake(@NotNull AtlasOptions options, @NotNull List<Candidate> candidates) {
            this.options = options;
            this.candidates = candidates;
            this.settled = new AtomicReferenceArray<>(candidates.size());
        }

        /**
         * Lists the candidates no sheet has refused, in pass order.
         *
         * @return the standing candidates' indices
         */
        @NotNull List<Integer> standing() {
            return IntStream.range(0, this.candidates.size())
                .filter(index -> !(this.settled.get(index) instanceof Refused))
                .boxed()
                .toList();
        }

        /**
         * Records a candidate's tile as kept, printing the pass's progress line on every hundredth first
         * keep.
         *
         * @param index the candidate's index
         * @param source the tile's source label
         */
        void keep(int index, @NotNull AtlasResult.Tile.Source source) {
            if (!this.settled.compareAndSet(index, null, new Kept(source))) return;

            boolean block = this.candidates.get(index).kind() == AtlasResult.Tile.Kind.BLOCK;
            int now = (block ? this.blocksKept : this.itemsKept).incrementAndGet();
            if (this.options.isProgressLogging() && now % PROGRESS_LOG_INTERVAL == 0)
                System.out.printf(block ? "  rendered %d block tiles...%n" : "  rendered %d item tiles...%n", now);
        }

        /**
         * Records a candidate's tile as refused, its reason the refusal's message - or the refusal's type
         * where a subclass answers no message - and prints the warning the first time it is refused.
         *
         * @param index the candidate's index
         * @param refusal what the tile threw
         */
        void refuse(int index, @NotNull RendererException refusal) {
            Candidate candidate = this.candidates.get(index);
            String reason = Objects.requireNonNullElse(refusal.getMessage(), refusal.getClass().getSimpleName());
            Settled prior = this.settled.getAndSet(index, new Refused(new AtlasResult.Skipped(candidate.id(), candidate.kind(), reason)));
            if (prior instanceof Refused || !this.options.isProgressLogging()) return;

            boolean block = candidate.kind() == AtlasResult.Tile.Kind.BLOCK;
            System.err.printf(block ? "  skipped block '%s': %s%n" : "  skipped item '%s': %s%n", candidate.id(), refusal.getMessage());
        }

        /**
         * Answers whether any of the given candidates has been refused.
         *
         * @param standing the candidates a sheet laid down
         * @return whether the sheet met a refusal
         */
        boolean refusedAny(@NotNull List<Integer> standing) {
            return standing.stream().anyMatch(index -> this.settled.get(index) instanceof Refused);
        }

        /**
         * Builds the sidecar: a row for every tile, reading each one's cell, and the stand-ins its render
         * drew, off the grid's cells, which keep tile order. Rows are emitted in the same order the tiles
         * were laid into the grid so a streaming consumer can walk the sidecar and the PNG in lockstep;
         * the skipped subjects follow, block pass first and each pass in the order it met them, holding
         * no cell and not counted.
         *
         * @param standing the candidates the sheet laid down, in tile order
         * @param cells the grid's cells, in tile order
         * @return the sidecar
         */
        @NotNull AtlasResult.Sidecar sidecar(@NotNull List<Integer> standing, @NotNull ConcurrentList<GridResult.Cell> cells) {
            ConcurrentList<AtlasResult.Tile> tiles = IntStream.range(0, standing.size())
                .mapToObj(i -> {
                    Candidate candidate = this.candidates.get(standing.get(i));
                    GridResult.Cell cell = cells.get(i);
                    if (!(this.settled.get(standing.get(i)) instanceof Kept kept))
                        throw new IllegalStateException("Atlas tile '%s' was laid down without settling as kept".formatted(candidate.id()));

                    return new AtlasResult.Tile(
                        candidate.id(),
                        candidate.kind(),
                        kept.source(),
                        cell.col(),
                        cell.row(),
                        cell.x(),
                        cell.y(),
                        cell.width(),
                        cell.height(),
                        cell.substitutions()
                    );
                })
                .collect(Concurrent.toList());
            ConcurrentList<AtlasResult.Skipped> skipped = IntStream.range(0, this.candidates.size())
                .mapToObj(this.settled::get)
                .filter(Refused.class::isInstance)
                .map(settling -> ((Refused) settling).row())
                .collect(Concurrent.toList());

            return new AtlasResult.Sidecar(this.options.getTileSize(), this.options.getColumns(), standing.size(), tiles, skipped);
        }

        /**
         * Prints each pass's closing line with the tiles it kept, for the passes the options scope in.
         */
        void logPasses() {
            if (!this.options.isProgressLogging()) return;

            if (this.options.getSource() != AtlasOptions.Scope.ITEM)
                System.out.printf("Block render pass complete: %d tiles%n", kept(AtlasResult.Tile.Kind.BLOCK));
            if (this.options.getSource() != AtlasOptions.Scope.BLOCK)
                System.out.printf("Item render pass complete: %d tiles%n", kept(AtlasResult.Tile.Kind.ITEM));
        }

        /**
         * Counts the candidates of one pass whose tile is kept.
         *
         * @param kind the pass
         * @return the kept count
         */
        private long kept(@NotNull AtlasResult.Tile.Kind kind) {
            return IntStream.range(0, this.candidates.size())
                .filter(index -> this.candidates.get(index).kind() == kind && this.settled.get(index) instanceof Kept)
                .count();
        }

    }

    /**
     * A written sheet as the PNG writer reads it - one image tile per band, each band composed when the
     * writer first reads a row of it and let go when it reads the next band's. The writer reads the rows
     * in order, top to bottom, so each band is composed once.
     * <p>
     * It declares the colour model and the sample model of the image the image library hands its PNG
     * writer - packed 8-bit ARGB, not premultiplied - so the writer encodes the same pixels to the same
     * bytes.
     */
    private static final class BandedSheet implements RenderedImage {

        /** The ARGB bit masks of the packed sample model, as the image library lays its images out. */
        private static final int @NotNull [] MASKS = { 0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000 };

        /** The sheet width in pixels. */
        private final int width;

        /** The sheet height in pixels. */
        private final int height;

        /** The height of every band but the last, in pixels. */
        private final int bandHeight;

        /** Composes a band, by index from the top, into a buffer the sheet's width and the band's height. */
        private final @NotNull IntFunction<PixelBuffer> composeBand;

        /** The index of the band {@link #raster} holds, or {@code -1} before the first. */
        private int band = -1;

        /** The current band's pixels, placed at the band's rows of the sheet. */
        private @Nullable Raster raster;

        /**
         * Constructs a new {@code BandedSheet} of the given size, composed a band at a time.
         *
         * @param width the sheet width in pixels
         * @param height the sheet height in pixels
         * @param bandHeight the height of every band but the last, in pixels
         * @param composeBand composes a band, by index from the top
         */
        BandedSheet(int width, int height, int bandHeight, @NotNull IntFunction<PixelBuffer> composeBand) {
            this.width = width;
            this.height = height;
            this.bandHeight = bandHeight;
            this.composeBand = composeBand;
        }

        /**
         * Returns the band holding a row of the sheet, composing it and letting the previous band go when
         * the row is in a band not yet composed.
         *
         * @param y the sheet row
         * @return the band's pixels, placed at its rows of the sheet
         */
        private @NotNull Raster bandAt(int y) {
            int wanted = y / this.bandHeight;
            Raster current = this.raster;
            if (current != null && wanted == this.band)
                return current;

            this.raster = null;
            int top = wanted * this.bandHeight;
            int rows = Math.min(this.bandHeight, this.height - top);
            PixelBuffer pixels = this.composeBand.apply(wanted);
            Raster composed = Raster.createPackedRaster(new DataBufferInt(pixels.data(), this.width * rows),
                this.width, rows, this.width, MASKS, new Point(0, top));
            this.raster = composed;
            this.band = wanted;
            return composed;
        }

        @Override
        public @NotNull Raster getData(@NotNull Rectangle rect) {
            Raster current = bandAt(rect.y);
            if (rect.y + rect.height > current.getMinY() + current.getHeight())
                throw new IllegalArgumentException("A banded sheet answers rows within one band, asked for %d rows from %d".formatted(rect.height, rect.y));

            return current.createChild(rect.x, rect.y, rect.width, rect.height, rect.x, rect.y, null);
        }

        @Override
        public @NotNull Raster getTile(int tileX, int tileY) {
            return bandAt(tileY * this.bandHeight);
        }

        @Override
        public @NotNull Raster getData() {
            throw new UnsupportedOperationException("A banded sheet never holds the whole sheet");
        }

        @Override
        public @NotNull WritableRaster copyData(@Nullable WritableRaster raster) {
            throw new UnsupportedOperationException("A banded sheet never holds the whole sheet");
        }

        @Override
        public @NotNull ColorModel getColorModel() {
            return ColorModel.getRGBdefault();
        }

        @Override
        public @NotNull SampleModel getSampleModel() {
            return new SinglePixelPackedSampleModel(DataBuffer.TYPE_INT, this.width, this.bandHeight, MASKS);
        }

        @Override
        public int getWidth() {
            return this.width;
        }

        @Override
        public int getHeight() {
            return this.height;
        }

        @Override
        public int getMinX() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }

        @Override
        public int getNumXTiles() {
            return 1;
        }

        @Override
        public int getNumYTiles() {
            return (this.height + this.bandHeight - 1) / this.bandHeight;
        }

        @Override
        public int getMinTileX() {
            return 0;
        }

        @Override
        public int getMinTileY() {
            return 0;
        }

        @Override
        public int getTileWidth() {
            return this.width;
        }

        @Override
        public int getTileHeight() {
            return this.bandHeight;
        }

        @Override
        public int getTileGridXOffset() {
            return 0;
        }

        @Override
        public int getTileGridYOffset() {
            return 0;
        }

        @Override
        public @Nullable Vector<RenderedImage> getSources() {
            return null;
        }

        @Override
        public @NotNull Object getProperty(@NotNull String name) {
            return Image.UndefinedProperty;
        }

        @Override
        public String @Nullable [] getPropertyNames() {
            return null;
        }

    }

}
