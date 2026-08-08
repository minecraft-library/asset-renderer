package lib.minecraft.renderer.option;

import dev.simplified.gson.JsonTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link AtlasSidecar} typed schema: {@code parse(toJson(x)) == x} (record structural
 * equality), the concrete JSON shape / member order against a mini fixture, and the failure a row
 * naming no {@link AtlasTile.Kind} or {@link AtlasTile.Source} constant raises.
 */
@DisplayName("AtlasSidecar: typed sidecar round-trips and pins the schema shape")
class AtlasSidecarTest {

    private static final AtlasSidecar FIXTURE = new AtlasSidecar(64, 2, 3, List.of(
        new AtlasTile("minecraft:stone", AtlasTile.Kind.BLOCK, AtlasTile.Source.BLOCK_MODEL, 0, 0, 0, 0, 64, 64),
        new AtlasTile("minecraft:oak_sign", AtlasTile.Kind.BLOCK, AtlasTile.Source.TILE_ENTITY, 1, 0, 64, 0, 64, 64),
        new AtlasTile("minecraft:apple", AtlasTile.Kind.ITEM, AtlasTile.Source.ITEM_MODEL, 0, 1, 0, 64, 64, 64)));

    @Test
    @DisplayName("parse(toJson(x)) reproduces x exactly")
    void roundTripsThroughJson() {
        AtlasSidecar reparsed = AtlasSidecar.parse(FIXTURE.toJson());
        assertEquals(FIXTURE, reparsed);
    }

    @Test
    @DisplayName("parsing a raw sidecar reproduces the fixture (parse side reads the renderer shape)")
    void parsesRawRendererShape() {
        String raw = """
            { "tileSize": 64, "columns": 2, "count": 3, "tiles": [
              { "id": "minecraft:stone", "kind": "block", "source": "block_model", "col": 0, "row": 0, "x": 0, "y": 0, "width": 64, "height": 64 },
              { "id": "minecraft:oak_sign", "kind": "block", "source": "tile_entity", "col": 1, "row": 0, "x": 64, "y": 0, "width": 64, "height": 64 },
              { "id": "minecraft:apple", "kind": "item", "source": "item_model", "col": 0, "row": 1, "x": 0, "y": 64, "width": 64, "height": 64 } ] }
            """;
        AtlasSidecar parsed = AtlasSidecar.parse(JsonTree.parse(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(FIXTURE, parsed);
    }

    @Test
    @DisplayName("toJson emits members in the grid-order schema shape")
    void emitsSchemaShape() {
        String json = FIXTURE.toJson().toGson().toString();
        String expected = "{\"tileSize\":64,\"columns\":2,\"count\":3,\"tiles\":["
            + "{\"id\":\"minecraft:stone\",\"kind\":\"block\",\"source\":\"block_model\",\"col\":0,\"row\":0,\"x\":0,\"y\":0,\"width\":64,\"height\":64},"
            + "{\"id\":\"minecraft:oak_sign\",\"kind\":\"block\",\"source\":\"tile_entity\",\"col\":1,\"row\":0,\"x\":64,\"y\":0,\"width\":64,\"height\":64},"
            + "{\"id\":\"minecraft:apple\",\"kind\":\"item\",\"source\":\"item_model\",\"col\":0,\"row\":1,\"x\":0,\"y\":64,\"width\":64,\"height\":64}]}";
        assertEquals(expected, json);
    }

    @Test
    @DisplayName("a row naming no kind or source constant fails loudly, naming the token")
    void rejectsUnknownTokens() {
        IllegalArgumentException badKind = assertThrows(IllegalArgumentException.class,
            () -> parse("{ \"id\": \"minecraft:stone\", \"kind\": \"sprite\", \"source\": \"block_model\" }"));
        assertTrue(badKind.getMessage().contains("'sprite'"),
            "the failure names the offending token, was: " + badKind.getMessage());

        IllegalArgumentException badSource = assertThrows(IllegalArgumentException.class,
            () -> parse("{ \"id\": \"minecraft:stone\", \"kind\": \"block\", \"source\": \"blockstate\" }"));
        assertTrue(badSource.getMessage().contains("'blockstate'"),
            "the failure names the offending token, was: " + badSource.getMessage());
    }

    @Test
    @DisplayName("a row carrying no kind member fails rather than defaulting to a constant")
    void rejectsAbsentKind() {
        assertThrows(IllegalArgumentException.class,
            () -> parse("{ \"id\": \"minecraft:stone\", \"source\": \"block_model\" }"));
    }

    /**
     * Parses a one-row sidecar built around the supplied tile object.
     *
     * @param tile the tile object literal to wrap
     * @return the parsed sidecar
     */
    private static AtlasSidecar parse(String tile) {
        String raw = "{ \"tileSize\": 64, \"columns\": 1, \"count\": 1, \"tiles\": [ " + tile + " ] }";
        return AtlasSidecar.parse(JsonTree.parse(raw.getBytes(StandardCharsets.UTF_8)));
    }

}
