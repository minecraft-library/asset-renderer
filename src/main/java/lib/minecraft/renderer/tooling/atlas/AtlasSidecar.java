package lib.minecraft.renderer.tooling.atlas;

import dev.simplified.gson.node.JsonTree;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The atlas sidecar schema, typed. The schema is otherwise implicit across
 * {@code AtlasRenderer.buildSidecarJson} (the write side) and three consumption sites in
 * {@code ToolingAtlasDiagnose} with no shared type; this record makes it one thing.
 *
 * <p>{@link #parse} reads a sidecar and {@link #toJson} writes one; {@code AtlasRenderer} still
 * emits the raw JSON natively. Tiles are kept in grid-layout order so streaming consumers walk
 * JSON + PNG in lockstep. The {@code build/atlas/} output stays scratch - never a bundled
 * resource.
 *
 * @param tileSize the per-tile edge length in pixels
 * @param columns the grid column count
 * @param count the tile count (== {@code tiles.size()})
 * @param tiles the tiles in grid order
 */
public record AtlasSidecar(int tileSize, int columns, int count, @NotNull List<Tile> tiles) {

    /**
     * One atlas tile: its id, kind / source classification, and grid + pixel coordinates. The
     * x/y-vs-col/row redundancy is kept because external consumers walk it.
     *
     * @param id the tile's subject id (namespaced block / item id)
     * @param kind the tile kind ({@code block} | {@code item})
     * @param source the registration source ({@code block_model} | {@code blockstate_only} | {@code tile_entity} | {@code item_model} | {@code fluid} | {@code portal})
     * @param col the grid column
     * @param row the grid row
     * @param x the pixel x (== {@code col * tileSize})
     * @param y the pixel y (== {@code row * tileSize})
     * @param width the tile pixel width
     * @param height the tile pixel height
     */
    public record Tile(
        @NotNull String id,
        @NotNull String kind,
        @NotNull String source,
        int col,
        int row,
        int x,
        int y,
        int width,
        int height
    ) {}

    /**
     * Parses a sidecar from its JSON root (the read side - diagnose, atlas verify).
     *
     * @param root the parsed sidecar JSON
     * @return the typed sidecar
     */
    public static @NotNull AtlasSidecar parse(@NotNull JsonTree root) {
        int tileSize = root.getInt("tileSize", 0);
        int columns = root.getInt("columns", 0);
        int count = root.getInt("count", 0);
        List<Tile> tiles = new ArrayList<>();
        JsonTree array = root.get("tiles");
        if (array != null)
            for (JsonTree tile : array.elements())
                tiles.add(new Tile(
                    tile.getString("id", ""),
                    tile.getString("kind", ""),
                    tile.getString("source", ""),
                    tile.getInt("col", 0),
                    tile.getInt("row", 0),
                    tile.getInt("x", 0),
                    tile.getInt("y", 0),
                    tile.getInt("width", 0),
                    tile.getInt("height", 0)));
        return new AtlasSidecar(tileSize, columns, count, tiles);
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
                .put("kind", tile.kind())
                .put("source", tile.source())
                .putInt("col", tile.col())
                .putInt("row", tile.row())
                .putInt("x", tile.x())
                .putInt("y", tile.y())
                .putInt("width", tile.width())
                .putInt("height", tile.height()));
        return root;
    }

}
