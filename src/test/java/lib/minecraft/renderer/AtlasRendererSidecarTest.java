package lib.minecraft.renderer;

import dev.simplified.gson.JsonTree;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.call.result.AtlasResult;
import lib.minecraft.renderer.call.result.Substitution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link AtlasResult.Sidecar} typed schema - {@code parse(toJson(x)) == x} under record structural
 * equality, the concrete JSON shape and member order against a mini fixture, a tile's stand-ins written
 * as its last member and the skipped subjects as the sheet's, a file written before either member
 * existed, the {@code empty} source token, and the failure a row naming no {@link AtlasResult.Tile.Kind},
 * {@link AtlasResult.Tile.Source}, {@link Substitution.Kind} or {@link Possible.State} constant raises.
 */
@DisplayName("AtlasResult.Sidecar typed round-trip and schema shape")
class AtlasRendererSidecarTest {

    private static final AtlasResult.Sidecar FIXTURE = new AtlasResult.Sidecar(64, 2, 3, List.of(
        new AtlasResult.Tile("minecraft:stone", AtlasResult.Tile.Kind.BLOCK, AtlasResult.Tile.Source.BLOCK_MODEL, 0, 0, 0, 0, 64, 64, List.of()),
        new AtlasResult.Tile("minecraft:oak_sign", AtlasResult.Tile.Kind.BLOCK, AtlasResult.Tile.Source.BLOCK_ENTITY, 1, 0, 64, 0, 64, 64, List.of()),
        new AtlasResult.Tile("minecraft:apple", AtlasResult.Tile.Kind.ITEM, AtlasResult.Tile.Source.ITEM_MODEL, 0, 1, 0, 64, 64, 64, List.of())), List.of());

    @Test
    @DisplayName("parse(toJson(x)) reproduces x exactly")
    void roundTripsThroughJson() {
        AtlasResult.Sidecar reparsed = AtlasResult.Sidecar.parse(FIXTURE.toJson());
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
        AtlasResult.Sidecar parsed = AtlasResult.Sidecar.parse(JsonTree.parse(raw.getBytes(StandardCharsets.UTF_8)));
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
    @DisplayName("a tile's stand-ins are written as its last member and read back as they were")
    void roundTripsATileWithStandIns() {
        AtlasResult.Tile tile = new AtlasResult.Tile("minecraft:apple", AtlasResult.Tile.Kind.ITEM, AtlasResult.Tile.Source.ITEM_MODEL,
            0, 0, 0, 0, 64, 64, List.of(
                Substitution.texture("minecraft:item/apple", Possible.State.ABSENT),
                Substitution.leafModel("minecraft:item/apple_core", "minecraft:apple")));
        AtlasResult.Sidecar sidecar = new AtlasResult.Sidecar(64, 1, 1, List.of(tile), List.of());

        String expected = "{\"tileSize\":64,\"columns\":1,\"count\":1,\"tiles\":["
            + "{\"id\":\"minecraft:apple\",\"kind\":\"item\",\"source\":\"item_model\",\"col\":0,\"row\":0,\"x\":0,\"y\":0,\"width\":64,\"height\":64,"
            + "\"substitutions\":["
            + "{\"kind\":\"texture\",\"state\":\"absent\",\"id\":\"minecraft:item/apple\"},"
            + "{\"kind\":\"leaf_model\",\"state\":\"absent\",\"id\":\"minecraft:item/apple_core\",\"namedBy\":\"minecraft:apple\"}]}]}";
        assertEquals(expected, sidecar.toJson().toGson().toString());
        assertEquals(sidecar, AtlasResult.Sidecar.parse(sidecar.toJson()));
    }

    @Test
    @DisplayName("a file written before stand-ins and skips were recorded reads as a sheet with neither")
    void readsAFileWithoutTheNewerMembers() {
        AtlasResult.Sidecar parsed = parse(
            "{ \"id\": \"minecraft:stone\", \"kind\": \"block\", \"source\": \"block_model\", \"col\": 0, \"row\": 0, \"x\": 0, \"y\": 0, \"width\": 64, \"height\": 64 }");

        assertEquals(List.of(), parsed.skipped(), "no skipped member reads as no skipped subject");
        assertEquals(List.of(), parsed.tiles().getFirst().substitutions(), "no substitutions member reads as no stand-in");
        assertEquals(new AtlasResult.Sidecar(64, 1, 1, List.of(FIXTURE.tiles().getFirst()), List.of()), parsed);
    }

    @Test
    @DisplayName("a tile whose subject draws nothing reads and writes the empty source token")
    void readsAndWritesTheEmptySource() {
        AtlasResult.Tile air = new AtlasResult.Tile("minecraft:cave_air", AtlasResult.Tile.Kind.BLOCK, AtlasResult.Tile.Source.EMPTY,
            0, 0, 0, 0, 64, 64, List.of());

        assertEquals(new AtlasResult.Sidecar(64, 1, 1, List.of(air), List.of()),
            parse("{ \"id\": \"minecraft:cave_air\", \"kind\": \"block\", \"source\": \"empty\", \"col\": 0, \"row\": 0, \"x\": 0, \"y\": 0, \"width\": 64, \"height\": 64 }"));
        assertEquals("{\"id\":\"minecraft:cave_air\",\"kind\":\"block\",\"source\":\"empty\",\"col\":0,\"row\":0,\"x\":0,\"y\":0,\"width\":64,\"height\":64}",
            air.toJson().toGson().toString());
    }

    @Test
    @DisplayName("the skipped subjects are written as the sheet's last member, in their order, and read back as they were")
    void roundTripsSkippedSubjects() {
        AtlasResult.Sidecar sidecar = new AtlasResult.Sidecar(64, 1, 1, List.of(FIXTURE.tiles().getFirst()), List.of(
            new AtlasResult.Skipped("minecraft:piston_head", AtlasResult.Tile.Kind.BLOCK, "Refused 'minecraft:piston_head'"),
            new AtlasResult.Skipped("minecraft:apple", AtlasResult.Tile.Kind.ITEM, "Refused 'minecraft:apple'")));

        String expected = "{\"tileSize\":64,\"columns\":1,\"count\":1,\"tiles\":["
            + "{\"id\":\"minecraft:stone\",\"kind\":\"block\",\"source\":\"block_model\",\"col\":0,\"row\":0,\"x\":0,\"y\":0,\"width\":64,\"height\":64}],"
            + "\"skipped\":["
            + "{\"id\":\"minecraft:piston_head\",\"kind\":\"block\",\"reason\":\"Refused 'minecraft:piston_head'\"},"
            + "{\"id\":\"minecraft:apple\",\"kind\":\"item\",\"reason\":\"Refused 'minecraft:apple'\"}]}";
        assertEquals(expected, sidecar.toJson().toGson().toString());
        assertEquals(sidecar, AtlasResult.Sidecar.parse(sidecar.toJson()));
    }

    @Test
    @DisplayName("a stand-in or a skipped row naming no constant fails loudly, naming the token")
    void rejectsUnknownStandInAndSkippedTokens() {
        IllegalArgumentException badKind = assertThrows(IllegalArgumentException.class,
            () -> parse("{ \"id\": \"minecraft:stone\", \"kind\": \"block\", \"source\": \"block_model\", "
                + "\"substitutions\": [ { \"kind\": \"model\", \"state\": \"absent\", \"id\": \"minecraft:block/stone\" } ] }"));
        assertTrue(badKind.getMessage().contains("'model'"),
            "the failure names the offending token, was: " + badKind.getMessage());

        IllegalArgumentException badState = assertThrows(IllegalArgumentException.class,
            () -> parse("{ \"id\": \"minecraft:stone\", \"kind\": \"block\", \"source\": \"block_model\", "
                + "\"substitutions\": [ { \"kind\": \"texture\", \"state\": \"missing\", \"id\": \"minecraft:block/stone\" } ] }"));
        assertTrue(badState.getMessage().contains("'missing'"),
            "the failure names the offending token, was: " + badState.getMessage());

        String raw = "{ \"tileSize\": 64, \"columns\": 1, \"count\": 0, \"tiles\": [], "
            + "\"skipped\": [ { \"id\": \"minecraft:stone\", \"kind\": \"sprite\", \"reason\": \"Refused\" } ] }";
        IllegalArgumentException badSkip = assertThrows(IllegalArgumentException.class,
            () -> AtlasResult.Sidecar.parse(JsonTree.parse(raw.getBytes(StandardCharsets.UTF_8))));
        assertTrue(badSkip.getMessage().contains("'sprite'"),
            "the failure names the offending token, was: " + badSkip.getMessage());
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
    private static AtlasResult.Sidecar parse(String tile) {
        String raw = "{ \"tileSize\": 64, \"columns\": 1, \"count\": 1, \"tiles\": [ " + tile + " ] }";
        return AtlasResult.Sidecar.parse(JsonTree.parse(raw.getBytes(StandardCharsets.UTF_8)));
    }

}
