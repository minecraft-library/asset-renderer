package lib.minecraft.renderer.option;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.AtlasRenderer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The atlas sidecar schema, typed - the one statement of what {@code atlas.json} holds, shared by
 * the {@link AtlasRenderer} that builds it and every reader that walks an atlas afterwards.
 *
 * <p>{@link #parse} reads a sidecar and {@link #toJson} builds the node a caller writes, so neither
 * side spells the schema out a second time. Tiles are kept in grid-layout order so streaming
 * consumers walk JSON + PNG in lockstep. The {@code build/atlas/} output stays scratch - never a
 * bundled resource.
 *
 *
 * <p><b>Parity.</b> Reaches the atlas alone, which this store holds no artifact for.
 *
 * @param tileSize the per-tile edge length in pixels
 * @param columns the grid column count
 * @param count the tile count (== {@code tiles.size()})
 * @param tiles the tiles in grid order
 */
@Parity(as = AtlasRenderer.class, mode = Mode.SUPPRESS)
@Parity(subject = Subject.ATLAS)
public record AtlasSidecar(int tileSize, int columns, int count, @NotNull List<AtlasTile> tiles) {

    /**
     * Parses a sidecar from its JSON root (the read side - diagnose, atlas verify).
     *
     * @param root the parsed sidecar JSON
     * @return the typed sidecar
     * @throws IllegalArgumentException when a tile's kind or source names no constant
     */
    public static @NotNull AtlasSidecar parse(@NotNull JsonTree root) {
        List<AtlasTile> tiles = new ArrayList<>();
        Optional<JsonTree> array = root.find("tiles");
        if (array.isPresent())
            for (JsonTree tile : array.get().elements().toList())
                tiles.add(parseTile(tile));

        return new AtlasSidecar(
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
    private static @NotNull AtlasTile parseTile(@NotNull JsonTree row) {
        String kindToken = row.getString("kind", "");
        String sourceToken = row.getString("source", "");
        AtlasTile.Kind kind = AtlasTile.Kind.fromJsonName(kindToken)
            .orElseThrow(() -> unknownToken("AtlasTile.Kind", kindToken));
        AtlasTile.Source source = AtlasTile.Source.fromJsonName(sourceToken)
            .orElseThrow(() -> unknownToken("AtlasTile.Source", sourceToken));

        return new AtlasTile(
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
        for (AtlasTile tile : this.tiles)
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
