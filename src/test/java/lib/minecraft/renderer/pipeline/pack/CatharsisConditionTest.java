package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the Catharsis overlay-activation evaluator: {@code catharsis:config} against
 * boolean and dropdown defaults, {@code catharsis:version} against the pack format and Minecraft
 * version, unknown-namespace degradation to false, the {@code config.catharsis.json}-overrides-mcmeta
 * precedence, and {@link CatharsisOverlays#activeOverlayDirectories} over a {@code fabric:overlays} block.
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
        assertThat(holds(condition, CatharsisConfig.EMPTY), is(true));            // target 84 in [69,255]
        assertThat(CatharsisCondition.parse(obj(condition)).holds(CatharsisConfig.EMPTY, new CatharsisTarget(50, "26.1")), is(false));
        assertThat(CatharsisCondition.parse(obj(condition)).holds(CatharsisConfig.EMPTY, new CatharsisTarget(300, "26.1")), is(false));
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

    @Test
    @DisplayName("config.catharsis.json fully overrides the mcmeta catharsis:pack/v1.config")
    void configFileOverridesMcmeta() {
        JsonTree configFile = JsonTree.wrap(GSON.fromJson("[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true}]", JsonElement.class));
        JsonTree mcmeta = obj("{\"catharsis:pack/v1\":{\"config\":[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":false}]}}");

        CatharsisConfig withFile = CatharsisOverlays.loadConfig(Optional.of(configFile), mcmeta);
        assertThat(withFile.matches("block.ore", Optional.empty()), is(true));   // file wins: default true

        CatharsisConfig fromMcmeta = CatharsisOverlays.loadConfig(Optional.empty(), mcmeta);
        assertThat(fromMcmeta.matches("block.ore", Optional.empty()), is(false)); // falls back to mcmeta: default false
    }

    @Test
    @DisplayName("activeOverlayDirectories keeps entry order and skips failing / condition-less entries")
    void activeOverlays() {
        JsonTree mcmeta = obj("{\"fabric:overlays\":{\"entries\":["
            + "{\"directory\":\"block_ore\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}},"
            + "{\"directory\":\"theme_dark\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"theme.dark\",\"value\":\"on\"}},"
            + "{\"directory\":\"always_off\",\"condition\":{\"condition\":\"fabric:all\"}},"
            + "{\"directory\":\"no_condition\"}]}}");
        CatharsisConfig config = config("[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true},"
            + "{\"type\":\"boolean\",\"id\":\"theme.dark\",\"default\":false}]");

        List<String> active = CatharsisOverlays.activeOverlayDirectories(mcmeta, config, TARGET);
        assertThat(active, contains("block_ore"));
    }

    @Test
    @DisplayName("no fabric:overlays block yields no active directories")
    void noOverlays() {
        assertThat(CatharsisOverlays.activeOverlayDirectories(obj("{\"pack\":{}}"), CatharsisConfig.EMPTY, TARGET), is(empty()));
    }

    private static boolean versionHolds(@NotNull String predicate) {
        return holds("{\"condition\":\"catharsis:version\",\"type\":\"MINECRAFT\",\"minecraftPredicate\":\"" + predicate + "\"}", CatharsisConfig.EMPTY);
    }

    private static boolean holds(@NotNull String conditionJson, @NotNull CatharsisConfig config) {
        return CatharsisCondition.parse(obj(conditionJson)).holds(config, TARGET);
    }

    private static @NotNull CatharsisConfig config(@NotNull String json) {
        return CatharsisConfig.parse(JsonTree.wrap(GSON.fromJson(json, JsonElement.class)));
    }

    private static @NotNull JsonTree obj(@NotNull String json) {
        return JsonTree.wrap(GSON.fromJson(json, JsonObject.class));
    }

}
