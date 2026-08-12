package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link CatharsisCondition} - what a {@code catharsis:config} option resolves to against
 * a pack's boolean and dropdown defaults, what a {@code catharsis:version} entry resolves to against
 * the renderer's pack format and Minecraft version, and the degradation to false that keeps an
 * unrecognised condition namespace inert rather than fatal.
 */
class CatharsisConditionTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();
    private static final @NotNull CatharsisTarget TARGET = new CatharsisTarget(84, "26.1");

    @Test
    @DisplayName("catharsis:config boolean: absent value is 'on when default true'")
    void booleanAbsentValue() {
        CatharsisConfig on = config("[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true}]");
        CatharsisConfig off = config("[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":false}]");
        String condition = "{\"condition\":\"catharsis:config\",\"pack\":\"p\",\"id\":\"block.ore\"}";
        assertThat(holds(condition, on), is(true));
        assertThat(holds(condition, off), is(false));
    }

    @Test
    @DisplayName("catharsis:config boolean: present on/off value compares against the boolean default")
    void booleanPresentValue() {
        CatharsisConfig on = config("[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true}]");
        assertThat(holds("{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}", on), is(true));
        assertThat(holds("{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"off\"}", on), is(false));

        CatharsisConfig off = config("[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":false}]");
        assertThat(holds("{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}", off), is(false));
        assertThat(holds("{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"off\"}", off), is(true));
    }

    @Test
    @DisplayName("catharsis:config dropdown: string-equality with the option marked default")
    void dropdownStringEquality() {
        CatharsisConfig config = config("[{\"type\":\"dropdown\",\"id\":\"menu.filler\",\"options\":["
            + "{\"value\":\"off\"},{\"value\":\"on\",\"default\":true},{\"value\":\"hidden\"}]}]");
        assertThat(holds("{\"condition\":\"catharsis:config\",\"id\":\"menu.filler\",\"value\":\"on\"}", config), is(true));
        assertThat(holds("{\"condition\":\"catharsis:config\",\"id\":\"menu.filler\",\"value\":\"hidden\"}", config), is(false));
        assertThat(holds("{\"condition\":\"catharsis:config\",\"id\":\"menu.filler\",\"value\":\"off\"}", config), is(false));
    }

    @Test
    @DisplayName("catharsis:config for an undeclared option is false")
    void undeclaredOptionFalse() {
        CatharsisConfig config = config("[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true}]");
        assertThat(holds("{\"condition\":\"catharsis:config\",\"id\":\"nonexistent\",\"value\":\"on\"}", config), is(false));
    }

    @Test
    @DisplayName("catharsis:version PACK_FORMAT checks the inclusive range against the target format")
    void versionPackFormat() {
        String condition = "{\"condition\":\"catharsis:version\",\"type\":\"PACK_FORMAT\","
            + "\"packFormatRange\":{\"min_inclusive\":69,\"max_inclusive\":255}}";
        assertThat(holds(condition, CatharsisConfig.EMPTY, TARGET), is(true));    // target 84 in [69,255]
        assertThat(holds(condition, CatharsisConfig.EMPTY, new CatharsisTarget(50, "26.1")), is(false));
        assertThat(holds(condition, CatharsisConfig.EMPTY, new CatharsisTarget(300, "26.1")), is(false));
    }

    @Test
    @DisplayName("catharsis:version MINECRAFT evaluates comparator predicates against the target version")
    void versionMinecraft() {
        assertThat(versionHolds(">=26.1"), is(true));
        assertThat(versionHolds("<=26.1"), is(true));
        assertThat(versionHolds("26.1"), is(true));
        assertThat(versionHolds(">26.1"), is(false));
        assertThat(versionHolds(">=26.2"), is(false));
        assertThat(versionHolds("<26.2"), is(true));
        assertThat(versionHolds(">=26.0 <27.0"), is(true));
    }

    @Test
    @DisplayName("an unknown condition namespace evaluates false (overlay inert, never errors)")
    void unknownNamespaceFalse() {
        assertThat(holds("{\"condition\":\"fabric:all\"}", CatharsisConfig.EMPTY), is(false));
        assertThat(holds("{\"condition\":\"minecraft:whatever\",\"id\":\"x\"}", CatharsisConfig.EMPTY), is(false));
        assertThat(holds("{}", CatharsisConfig.EMPTY), is(false));
    }

    private static boolean versionHolds(@NotNull String predicate) {
        return holds("{\"condition\":\"catharsis:version\",\"type\":\"MINECRAFT\",\"minecraftPredicate\":\"" + predicate + "\"}", CatharsisConfig.EMPTY);
    }

    private static boolean holds(@NotNull String conditionJson, @NotNull CatharsisConfig config) {
        return holds(conditionJson, config, TARGET);
    }

    private static boolean holds(@NotNull String conditionJson, @NotNull CatharsisConfig config, @NotNull CatharsisTarget target) {
        return CatharsisCondition.parse(obj(conditionJson)).holds(config, target);
    }

    private static @NotNull CatharsisConfig config(@NotNull String json) {
        return CatharsisConfig.parse(JsonTree.wrap(GSON.fromJson(json, JsonElement.class)));
    }

    private static @NotNull JsonTree obj(@NotNull String json) {
        return JsonTree.wrap(GSON.fromJson(json, JsonObject.class));
    }

}
