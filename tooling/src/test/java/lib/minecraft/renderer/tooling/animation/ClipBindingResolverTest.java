package lib.minecraft.renderer.tooling.animation;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clip-binding resolution against the real client jar.
 *
 * <p>The load-bearing assertion is that nothing goes unresolved. A model plays a clip through a
 * field, and a field the constructor walk missed is reported rather than silently dropped, so a
 * clean diagnostics scope over the whole roster is what says both constructor shapes are covered.
 *
 * <p>Tagged {@code slow}: the walk runs against the downloaded client jar.
 */
@Tag("slow")
@DisplayName("clip bindings resolve for every model that plays one")
class ClipBindingResolverTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The shipped table, relative to the renderer root every Test task runs at. */
    private static final @NotNull Path SHIPPED_MODELS =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json");

    private static ClassNodeCache cache;
    private static Diagnostics diagnostics;
    private static Map<String, List<ClipBinding>> byModel;

    @BeforeAll
    static void resolve() {
        cache = ClassNodeCache.open(ClientAcquisition.downloadJarToCache(ClientOptions.defaults()));
        diagnostics = Diagnostics.root("animation", Diagnostics.Output.NONE, null);
        byModel = new TreeMap<>();
        for (String model : rosterClasses()) {
            List<ClipBinding> bindings = ClipBindingResolver.resolve(cache, model, diagnostics);
            if (!bindings.isEmpty()) byModel.put(simpleName(model), bindings);
        }
    }

    @AfterAll
    static void close() {
        if (cache != null) cache.close();
    }

    @Test
    @DisplayName("no model plays a clip through a field the constructor walk missed")
    void nothingUnresolved() {
        assertEquals(0, diagnostics.count(Diagnostics.Severity.WARN),
            () -> "unresolved play sites: " + diagnostics.entries());
        assertEquals(0, diagnostics.count(Diagnostics.Severity.ERROR),
            () -> "errors: " + diagnostics.entries());
    }

    @Test
    @DisplayName("a clip named inline by the constructor resolves")
    void inlineConstantsResolve() {
        List<String> warden = byModel.getOrDefault("WardenModel", List.of()).stream()
            .map(ClipBinding::clip).toList();
        assertEquals(List.of(
            "WardenAnimation#WARDEN_ATTACK", "WardenAnimation#WARDEN_SONIC_BOOM",
            "WardenAnimation#WARDEN_DIG", "WardenAnimation#WARDEN_EMERGE",
            "WardenAnimation#WARDEN_ROAR", "WardenAnimation#WARDEN_SNIFF"), warden);
        assertTrue(byModel.get("WardenModel").stream().allMatch(b -> b.gate() == ClipBinding.Gate.STATE),
            "every warden clip sits behind an animation state");
    }

    @Test
    @DisplayName("a clip passed into an abstract base as a parameter resolves to the subclass's own set")
    void parameterPassedConstantsResolve() {
        // CamelModel takes its six clips as constructor parameters so one base can serve the adult
        // and the baby; the constants are named at each subclass's super call, never in the base.
        List<String> camel = byModel.getOrDefault("AdultCamelModel", List.of()).stream()
            .map(ClipBinding::clip).toList();
        assertTrue(camel.stream().allMatch(clip -> clip.startsWith("CamelAnimation#")),
            () -> "every adult camel clip should come from CamelAnimation, got " + camel);
        assertTrue(camel.size() >= 5, () -> "expected the camel's clip set, got " + camel);
    }

    @Test
    @DisplayName("the three gates are told apart by the call form")
    void gatesAreDistinguished() {
        Map<ClipBinding.Gate, Long> byGate = byModel.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.groupingBy(ClipBinding::gate, Collectors.counting()));
        assertTrue(byGate.getOrDefault(ClipBinding.Gate.WALK, 0L) > 0, "the corpus declares walk-driven clips");
        assertTrue(byGate.getOrDefault(ClipBinding.Gate.STATE, 0L) > 0, "the corpus declares state-gated clips");
        assertTrue(byGate.getOrDefault(ClipBinding.Gate.STATIC, 0L) > 0, "the corpus declares an unconditional clip");

        // Almost every clip is behind an animation state, which is why a resting render moves so
        // little: nothing offline starts one.
        long state = byGate.getOrDefault(ClipBinding.Gate.STATE, 0L);
        long total = byGate.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(state * 2 > total, "state-gated clips are expected to be the majority, got " + byGate);
    }

    @Test
    @DisplayName("every resolved clip names a clip the definition tables actually declare")
    void everyBindingNamesARealClip() {
        Set<String> declared = KeyframeDefinitionParser.parseAll(cache, diagnostics).stream()
            .map(KeyframeClip::coordinate).collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> dangling = byModel.values().stream()
            .flatMap(List::stream).map(ClipBinding::clip).distinct()
            .filter(clip -> !declared.contains(clip)).toList();
        assertEquals(List.of(), dangling, "bindings naming a clip no table declares");
    }

    @Test
    @DisplayName("the clips no model plays are named, because shipping them is a decision")
    void deadClipsAreNamed() {
        Set<String> declared = KeyframeDefinitionParser.parseAll(cache, diagnostics).stream()
            .map(KeyframeClip::coordinate).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> played = byModel.values().stream()
            .flatMap(List::stream).map(ClipBinding::clip).collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> dead = declared.stream().filter(clip -> !played.contains(clip)).sorted().toList();
        // Two of the seventy-four are declared and played by nothing. They are shipped rather than
        // dropped, because a table that silently loses a clip is indistinguishable from a walk that
        // failed to find one - and the lower-case field name on the axolotl's is vanilla's own.
        assertEquals(List.of("BabyAxolotlAnimation#baby_axolotl_dash", "SnifferAnimation#SNIFFER_BABY_FALL"), dead,
            "clips declared by a table that no model in the roster plays");
        assertEquals(72, played.size(), "clips reachable from the roster");
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull List<String> rosterClasses() {
        JsonObject models = GSON.fromJson(read(SHIPPED_MODELS), JsonElement.class)
            .getAsJsonObject().getAsJsonObject("models");
        Set<String> names = new LinkedHashSet<>();
        collectGeometry(models, names);
        List<String> internal = new ArrayList<>();
        for (String name : names) {
            String simple = name.split("#")[0].split("@")[0];
            String found = locate(simple);
            if (found != null) internal.add(found);
        }
        return internal;
    }

    private static void collectGeometry(@NotNull JsonElement element, @NotNull Set<String> out) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
                if ("geometry".equals(member.getKey()) && member.getValue().isJsonPrimitive())
                    out.add(member.getValue().getAsString());
                else collectGeometry(member.getValue(), out);
            }
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectGeometry(child, out));
        }
    }

    /** Finds a model class by simple name anywhere under the client model package. */
    private static String locate(@NotNull String simple) {
        for (String entry : cache.list("net/minecraft/client/model/", ".class")) {
            String internal = entry.substring(0, entry.length() - ".class".length());
            if (simple.equals(simpleName(internal))) return internal;
        }
        return null;
    }

    private static @NotNull String simpleName(@NotNull String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    private static @NotNull Reader read(@NotNull Path path) {
        try {
            return Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("no shipped table at " + path.toAbsolutePath(), error);
        }
    }

}
