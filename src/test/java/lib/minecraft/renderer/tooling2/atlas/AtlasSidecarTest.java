package lib.minecraft.renderer.tooling2.atlas;

import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@link AtlasSidecar} typed schema: {@code parse(toJson(x)) == x} (record structural
 * equality) and the concrete JSON shape / member order against a mini fixture (09 SS8).
 */
@DisplayName("AtlasSidecar: typed sidecar round-trips and pins the schema shape")
class AtlasSidecarTest {

    private static final AtlasSidecar FIXTURE = new AtlasSidecar(64, 2, 3, List.of(
        new AtlasSidecar.Tile("minecraft:stone", "block", "block_model", 0, 0, 0, 0, 64, 64),
        new AtlasSidecar.Tile("minecraft:oak_sign", "block", "tile_entity", 1, 0, 64, 0, 64, 64),
        new AtlasSidecar.Tile("minecraft:apple", "item", "item_model", 0, 1, 0, 64, 64, 64)));

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
        AtlasSidecar parsed = AtlasSidecar.parse(JsonNode.parse(raw.getBytes(StandardCharsets.UTF_8)));
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

}
