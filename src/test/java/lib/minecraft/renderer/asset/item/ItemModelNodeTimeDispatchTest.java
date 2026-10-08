package lib.minecraft.renderer.asset.item;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static lib.minecraft.renderer.fixture.ItemModelFixtures.timeDispatch;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The {@link ItemModelNode.RangeDispatch#timeSteps()} count a caller's "animate this item" request
 * derives its frames from - how many faces one {@code minecraft:time} dispatch steps through over a
 * day, and nothing for a dispatch on another property or a table too short to sweep. Which dispatch
 * a tree is counted by is the walk's, pinned with the context that walks it.
 */
@DisplayName("ItemModelNode time dispatch steps")
class ItemModelNodeTimeDispatchTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    /** Parses a {@code range_dispatch} node, as it would sit under a definition's {@code model} member. */
    private static ItemModelNode.RangeDispatch range(String json) {
        JsonObject node = JsonParser.parseString(json).getAsJsonObject();
        return (ItemModelNode.RangeDispatch) GSON.fromJson(node, ItemModelNode.class);
    }

    @Test
    @DisplayName("counts a clock's faces, one short of its threshold table")
    void countsClockFaces() {
        // The 65th entry wraps the table back onto the first face, so it repeats a step, not adds one.
        assertThat(range(timeDispatch("minecraft:time", 64)).timeSteps(), is(OptionalInt.of(64)));
    }

    @Test
    @DisplayName("ignores a compass, whose needle a bearing turns rather than the clock")
    void ignoresCompass() {
        assertThat(range(timeDispatch("minecraft:compass", 32)).timeSteps(), is(OptionalInt.empty()));
    }

    @Test
    @DisplayName("ignores a table too short to sweep, rather than deriving a one-frame animation")
    void ignoresSingleStepTable() {
        assertThat(range("{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\",\"scale\":1.0,"
            + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/only\"}}]}")
            .timeSteps(), is(OptionalInt.empty()));
        // One face and its wrap entry: a single step, the shortest table that would count one frame.
        assertThat(range(timeDispatch("minecraft:time", 1)).timeSteps(), is(OptionalInt.empty()));
    }

    @Test
    @DisplayName("accepts the unqualified property id as well as the namespaced one")
    void acceptsUnqualifiedProperty() {
        assertThat(range(timeDispatch("time", 16)).timeSteps(), is(OptionalInt.of(16)));
    }

    @Test
    @DisplayName("reads the property namespace-exact, so a mod's time is not vanilla's")
    void ignoresAModsTimeProperty() {
        assertThat(range(timeDispatch("hplus:time", 16)).timeSteps(), is(OptionalInt.empty()));
    }

}
