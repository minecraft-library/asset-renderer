package lib.minecraft.renderer.call.result;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.KeyField;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.JsonTree;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.FluidRenderer;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.PortalRenderer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.content.index.BlockModelLoader;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * The atlas's result: the composed grid, and the sidecar placing every tile in it. Its stand-ins are its
 * tiles'.
 * <p>
 * {@link Sidecar} is the typed {@code atlas.json} schema, parsed and written by one type so neither side
 * spells it out twice, and {@link Tile} is one row of it: the subject a tile was rendered from, its kind
 * and source path, where it sits in the grid, and the stand-ins its render drew. A row carries
 * coordinates and never pixels.
 *
 * @param image the composed atlas grid image
 * @param sidecar the grid layout, one row per tile in the order the tiles were laid down
 */
@Parity(subject = Subject.ATLAS)
public record AtlasResult(@NotNull ImageData image, @NotNull Sidecar sidecar) implements RenderResult {

    /** {@inheritDoc} */
    @Override
    public @NotNull ConcurrentList<Substitution> substitutions() {
        return PlainResult.distinctSorted(this.sidecar.tiles()
            .stream()
            .flatMap(tile -> tile.substitutions().stream()));
    }

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
         * @throws IllegalArgumentException when a tile's kind or source names no constant, or one of its
         *     stand-ins names an unknown kind or state
         */
        public static @NotNull Sidecar parse(@NotNull JsonTree root) {
            ConcurrentList<Tile> tiles = root.find("tiles")
                .map(array -> array.elements()
                    .map(Tile::parse)
                    .collect(Concurrent.toWideUnmodifiableList()))
                .orElseGet(Concurrent::newUnmodifiableList);

            return new Sidecar(
                root.getInt("tileSize", 0),
                root.getInt("columns", 0),
                root.getInt("count", 0),
                tiles);
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
                array.add(tile.toJson());
            return root;
        }

    }

    /**
     * One row of a {@link Sidecar}: the subject a tile was rendered from, how that tile was
     * classified, where it sits in the composed grid, and the stand-ins its render drew.
     *
     * <p>A row carries coordinates and no pixels - the grid position only exists once every tile has
     * been laid out, and the composed atlas image is what holds the pixels. The x/y-vs-col/row
     * redundancy is kept because external consumers walk it. A row's stand-ins are written as a last
     * {@code substitutions} member only where there are any, so a tile that drew none reads as it would
     * with no such member at all.
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
     * @param substitutions the stand-ins the tile's render drew, distinct and sorted, empty where it drew
     *     none
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
        int height,
        @NotNull List<Substitution> substitutions
    ) {

        /**
         * Reads one tile row, resolving its lowercase kind and source tokens back to their constants. An
         * absent {@code substitutions} member reads as no stand-in.
         *
         * @param row the tile object from the sidecar's {@code tiles} array
         * @return the typed tile
         * @throws IllegalArgumentException when the row's kind or source names no constant, or one of its
         *     stand-ins names an unknown kind or state
         */
        public static @NotNull Tile parse(@NotNull JsonTree row) {
            String kindToken = row.getString("kind", "");
            String sourceToken = row.getString("source", "");
            Kind kind = Kind.findByJsonName(kindToken)
                .orElseThrow(() -> unknownToken("Tile.Kind", kindToken));
            Source source = Source.findByJsonName(sourceToken)
                .orElseThrow(() -> unknownToken("Tile.Source", sourceToken));
            ConcurrentList<Substitution> substitutions = row.find("substitutions")
                .map(array -> array.elements()
                    .map(Substitution::parse)
                    .collect(Concurrent.toWideUnmodifiableList()))
                .orElseGet(Concurrent::newUnmodifiableList);

            return new Tile(
                row.getString("id", ""),
                kind,
                source,
                row.getInt("col", 0),
                row.getInt("row", 0),
                row.getInt("x", 0),
                row.getInt("y", 0),
                row.getInt("width", 0),
                row.getInt("height", 0),
                substitutions);
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
         * Copies this tile to another grid cell, as a re-packed sheet places it.
         *
         * @param col the grid column the copy occupies
         * @param row the grid row the copy occupies
         * @param tileSize the re-packed sheet's per-tile edge length in pixels
         * @return the same tile, its stand-ins included, at the cell's pixel edges and at its own size
         */
        public @NotNull Tile at(int col, int row, int tileSize) {
            return new Tile(this.id, this.kind, this.source, col, row, col * tileSize, row * tileSize,
                this.width, this.height, this.substitutions);
        }

        /**
         * Builds this tile's row, the one row serialiser every writer shares: the subject, its kind and
         * source, its cell and pixel rectangle, then its stand-ins where it drew any.
         *
         * @return the tile object
         */
        public @NotNull JsonTree toJson() {
            JsonTree row = JsonTree.object()
                .put("id", this.id)
                .put("kind", this.kind.jsonName())
                .put("source", this.source.jsonName())
                .putInt("col", this.col)
                .putInt("row", this.row)
                .putInt("x", this.x)
                .putInt("y", this.y)
                .putInt("width", this.width)
                .putInt("height", this.height);

            if (!this.substitutions.isEmpty()) {
                JsonTree array = row.childArray("substitutions");
                for (Substitution substitution : this.substitutions)
                    array.add(substitution.toJson());
            }

            return row;
        }

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
