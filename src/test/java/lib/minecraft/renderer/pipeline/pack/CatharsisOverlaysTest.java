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
 * Coverage of {@link CatharsisOverlays} - the precedence a root {@code config.catharsis.json} takes
 * over the mcmeta {@code catharsis:pack/v1.config} it replaces, and the declaration order the
 * {@code fabric:overlays} listing keeps so the last-declared active overlay wins an overlapping path.
 */
class CatharsisOverlaysTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();
    private static final @NotNull CatharsisTarget TARGET = new CatharsisTarget(84, "26.1");

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
    @DisplayName("a config.catharsis.json that declares no option falls back to the mcmeta config")
    void emptyConfigFileFallsBackToMcmeta() {
        // Matching Catharsis (options?.takeUnless(isEmpty) ?: meta.config): the root file wins only when
        // it contributes an option default; a present-but-empty file must not shadow the mcmeta config.
        JsonTree emptyFile = JsonTree.wrap(GSON.fromJson("[]", JsonElement.class));
        JsonTree mcmeta = obj("{\"catharsis:pack/v1\":{\"config\":[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true}]}}");

        CatharsisConfig resolved = CatharsisOverlays.loadConfig(Optional.of(emptyFile), mcmeta);
        assertThat(resolved.matches("block.ore", Optional.empty()), is(true)); // mcmeta default true, not the empty file
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
    @DisplayName("activeOverlayDirectories keeps entry order so the last-declared active overlay wins")
    void activeOverlaysEntryOrderLastWins() {
        // fabric:overlays entries are applied in listed order (Fabric appends entries forward; vanilla
        // CompositePackResources then reverses once so the last entry is highest priority). The returned
        // list must keep declaration order - the pack layer stacks it base-first, last root winning, so
        // the later entry (theme_dark) outranks the earlier (block_ore) on an overlapping path.
        JsonTree mcmeta = obj("{\"fabric:overlays\":{\"entries\":["
            + "{\"directory\":\"block_ore\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}},"
            + "{\"directory\":\"theme_dark\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"theme.dark\",\"value\":\"on\"}}]}}");
        CatharsisConfig config = config("[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true},"
            + "{\"type\":\"boolean\",\"id\":\"theme.dark\",\"default\":true}]");

        assertThat(CatharsisOverlays.activeOverlayDirectories(mcmeta, config, TARGET), contains("block_ore", "theme_dark"));
    }

    @Test
    @DisplayName("no fabric:overlays block yields no active directories")
    void noOverlays() {
        assertThat(CatharsisOverlays.activeOverlayDirectories(obj("{\"pack\":{}}"), CatharsisConfig.EMPTY, TARGET), is(empty()));
    }

    private static @NotNull CatharsisConfig config(@NotNull String json) {
        return CatharsisConfig.parse(JsonTree.wrap(GSON.fromJson(json, JsonElement.class)));
    }

    private static @NotNull JsonTree obj(@NotNull String json) {
        return JsonTree.wrap(GSON.fromJson(json, JsonObject.class));
    }

}
