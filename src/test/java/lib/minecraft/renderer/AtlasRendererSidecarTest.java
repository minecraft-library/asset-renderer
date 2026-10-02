package lib.minecraft.renderer;

import dev.simplified.gson.JsonTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link AtlasRenderer.Sidecar} typed schema - {@code parse(toJson(x)) == x} under record structural
 * equality, the concrete JSON shape and member order against a mini fixture, and the failure a row
 * naming no {@link AtlasRenderer.Tile.Kind} or {@link AtlasRenderer.Tile.Source} constant raises.
 */
@DisplayName("AtlasRenderer.Sidecar typed round-trip and schema shape")
class AtlasRendererSidecarTest {

    private static final AtlasRenderer.Sidecar FIXTURE = new AtlasRenderer.Sidecar(64, 2, 3, List.of(
        new AtlasRenderer.Tile("minecraft:stone", AtlasRenderer.Tile.Kind.BLOCK, AtlasRenderer.Tile.Source.BLOCK_MODEL, 0, 0, 0, 0, 64, 64),
        new AtlasRenderer.Tile("minecraft:oak_sign", AtlasRenderer.Tile.Kind.BLOCK, AtlasRenderer.Tile.Source.BLOCK_ENTITY, 1, 0, 64, 0, 64, 64),
        new AtlasRenderer.Tile("minecraft:apple", AtlasRenderer.Tile.Kind.ITEM, AtlasRenderer.Tile.Source.ITEM_MODEL, 0, 1, 0, 64, 64, 64)));

    @Test
    @DisplayName("parse(toJson(x)) reproduces x exactly")
    void roundTripsThroughJson() {
        AtlasRenderer.Sidecar reparsed = AtlasRenderer.Sidecar.parse(FIXTURE.toJson());
        assertEquals(FIXTURE, reparsed);
    }

    @Test
    @DisplayName("parsing a raw sidecar reproduces the fixture (parse side reads the renderer shape)")
    void parsesRawRendererShape() {
        String raw = """
            { "tileSize": 64, "columns": 2, "count": 3, "tiles": [
              { "id": "minecraft:stone", "kind": "block", "source": "block_model", "col": 0, "row": 0, "x": 0, "y": 0, "width": 64, "height": 64 },
              { "id": "minecraft:oak_sign", "kind": "block", "source": "block_entity", "col": 1, "row": 0, "x": 64, "y": 0, "width": 64, "height": 64 },
              { "id": "minecraft:apple", "kind": "item", "source": "item_model", "col": 0, "row": 1, "x": 0, "y": 64, "width": 64, "height": 64 } ] }
            """;
        AtlasRenderer.Sidecar parsed = AtlasRenderer.Sidecar.parse(JsonTree.parse(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(FIXTURE, parsed);
    }

    @Test
    @DisplayName("toJson emits members in the grid-order schema shape")
    void emitsSchemaShape() {
        String json = FIXTURE.toJson().toGson().toString();
        String expected = "{\"tileSize\":64,\"columns\":2,\"count\":3,\"tiles\":["
            + "{\"id\":\"minecraft:stone\",\"kind\":\"block\",\"source\":\"block_model\",\"col\":0,\"row\":0,\"x\":0,\"y\":0,\"width\":64,\"height\":64},"
            + "{\"id\":\"minecraft:oak_sign\",\"kind\":\"block\",\"source\":\"block_entity\",\"col\":1,\"row\":0,\"x\":64,\"y\":0,\"width\":64,\"height\":64},"
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
    private static AtlasRenderer.Sidecar parse(String tile) {
        String raw = "{ \"tileSize\": 64, \"columns\": 1, \"count\": 1, \"tiles\": [ " + tile + " ] }";
        return AtlasRenderer.Sidecar.parse(JsonTree.parse(raw.getBytes(StandardCharsets.UTF_8)));
    }

}
