package lib.minecraft.renderer.dump;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.dump.PackSourceParity.Comparison;
import lib.minecraft.renderer.dump.PackSourceParity.Supplied;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link PackSourceParity}'s pure halves: mapping a pack run's requested packs to files and
 * to the client's ids, collapsing one resource's files to a stack, and sorting each disagreement
 * between two sides' stacks into its category.
 */
@DisplayName("PackSourceParity stack comparison")
class PackSourceParityTest {

    private static final Supplied VANILLA_A = new Supplied("vanilla", "a");

    private static final Supplied PACK_B = new Supplied("file/p", "b");

    private static final Supplied PACK_C = new Supplied("file/p", "c");

    private static final Supplied OTHER_D = new Supplied("file/q", "d");

    @Test
    @DisplayName("a dump's requested packs map to files in its folder in request order, labelled after vanilla")
    void requestedPacksMapInOrder() {
        JsonObject dump = JsonParser.parseString(
            "{\"pack_directory\": \"packs\", \"requested\": [\"file/p\", \"file/q.zip\"]}").getAsJsonObject();

        assertThat(PackSourceParity.packFiles(dump), contains(Path.of("packs", "p").toFile(), Path.of("packs", "q.zip").toFile()));
        assertThat(PackSourceParity.labels(dump), contains("vanilla", "file/p", "file/q.zip"));
    }

    @Test
    @DisplayName("a pack's later file stands in for its earlier one, and packs keep their order")
    void collapseKeepsEachPacksLastFile() {
        assertThat("an overlay root's file replaces the base root's within one pack",
            PackSourceParity.collapse(List.of(VANILLA_A, PACK_B, PACK_C)), contains(VANILLA_A, PACK_C));
        assertThat(PackSourceParity.collapse(List.of(VANILLA_A, PACK_B, OTHER_D)), contains(VANILLA_A, PACK_B, OTHER_D));
        assertThat(PackSourceParity.collapse(List.of()), is(empty()));
    }

    @Test
    @DisplayName("identical stacks agree")
    void identicalAgrees() {
        Map<String, List<Supplied>> stacks = Map.of("minecraft:textures/x.png", List.of(VANILLA_A, PACK_B));
        assertThat(PackSourceParity.compare(stacks, stacks).agrees(), is(true));
    }

    @Test
    @DisplayName("each disagreement lands in exactly one category")
    void categories() {
        Map<String, List<Supplied>> client = Map.of(
            "minecraft:textures/client_only.png", List.of(VANILLA_A),
            "minecraft:textures/order.png", List.of(VANILLA_A, PACK_B),
            "minecraft:textures/bytes.png", List.of(VANILLA_A, PACK_B),
            "minecraft:textures/same.png", List.of(VANILLA_A));
        Map<String, List<Supplied>> renderer = Map.of(
            "minecraft:textures/renderer_only.png", List.of(PACK_B),
            "minecraft:textures/order.png", List.of(PACK_B),
            "minecraft:textures/bytes.png", List.of(VANILLA_A, PACK_C),
            "minecraft:textures/same.png", List.of(VANILLA_A));

        Comparison comparison = PackSourceParity.compare(client, renderer);

        assertThat(comparison.onlyClient().keySet(), contains("minecraft:textures/client_only.png"));
        assertThat(comparison.onlyRenderer().keySet(), contains("minecraft:textures/renderer_only.png"));
        assertThat("a pack missing from one side's stack is an order difference, not a bytes one",
            comparison.order().keySet(), contains("minecraft:textures/order.png"));
        assertThat(comparison.bytes().keySet(), contains("minecraft:textures/bytes.png"));
        assertThat(comparison.agrees(), is(false));
    }

}
