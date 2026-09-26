package lib.minecraft.renderer.asset.item;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The {@link ItemModelNode#timeDispatchSteps()} search a caller's "animate this item" request derives
 * its frame count from - how many faces a {@code minecraft:time} dispatch steps through wherever in the
 * tree it sits, and nothing for a dispatch on another property or a table too short to sweep.
 */
@DisplayName("ItemModelNode time dispatch search")
class ItemModelNodeTimeDispatchTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    /** Parses the {@code model} member of an item definition into its dispatch tree. */
    private static ItemModelNode parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return GSON.fromJson(root.getAsJsonObject("model"), ItemModelNode.class);
    }

    /** A {@code range_dispatch} over {@code faces} models, plus the wrap entry vanilla's tables carry. */
    private static String timeDispatch(String property, int faces) {
        StringBuilder entries = new StringBuilder();
        for (int entry = 0; entry <= faces; entry++) {
            if (entry > 0) entries.append(',');
            entries.append("{\"threshold\":%s,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/face_%02d\"}}"
                .formatted(entry == 0 ? "0.0" : (entry - 0.5f), entry % faces));
        }
        return "{\"type\":\"minecraft:range_dispatch\",\"property\":\"%s\",\"scale\":%s.0,\"entries\":[%s]}"
            .formatted(property, faces, entries);
    }

    @Test
    @DisplayName("counts a clock's faces, one short of its threshold table")
    void countsClockFaces() {
        // The 65th entry wraps the table back onto the first face, so it repeats a step, not adds one.
        assertThat(parse("{\"model\":" + timeDispatch("minecraft:time", 64) + "}").timeDispatchSteps(),
            is(OptionalInt.of(64)));
    }

    @Test
    @DisplayName("finds a dispatch nested behind a condition and a composite")
    void findsNestedDispatch() {
        String tree = "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:broken\","
            + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/broken\"},"
            + "\"on_false\":{\"type\":\"minecraft:composite\",\"models\":["
            + "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/base\"},"
            + timeDispatch("minecraft:time", 8) + "]}}}";
        assertThat(parse(tree).timeDispatchSteps(), is(OptionalInt.of(8)));
    }

    @Test
    @DisplayName("ignores a compass, whose needle a bearing turns rather than the clock")
    void ignoresCompass() {
        assertThat(parse("{\"model\":" + timeDispatch("minecraft:compass", 32) + "}").timeDispatchSteps(),
            is(OptionalInt.empty()));
    }

    @Test
    @DisplayName("finds nothing to animate in a plain model")
    void ignoresPlainModel() {
        assertThat(parse("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/diamond_sword\"}}")
            .timeDispatchSteps(), is(OptionalInt.empty()));
    }

    @Test
    @DisplayName("ignores a table too short to sweep, rather than deriving a one-frame animation")
    void ignoresSingleStepTable() {
        assertThat(parse("{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\",\"scale\":1.0,"
            + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/only\"}}]}}")
            .timeDispatchSteps(), is(OptionalInt.empty()));
    }

    @Test
    @DisplayName("accepts the unqualified property id as well as the namespaced one")
    void acceptsUnqualifiedProperty() {
        assertThat(parse("{\"model\":" + timeDispatch("time", 16) + "}").timeDispatchSteps(),
            is(OptionalInt.of(16)));
    }

}
