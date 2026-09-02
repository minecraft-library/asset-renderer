package lib.minecraft.renderer.pipeline.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.parity.ParityJson;
import lib.minecraft.renderer.parity.ParityStore;
import lib.minecraft.renderer.parity.Pins;
import lib.minecraft.renderer.parity.SelfCapture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Single golden-reference guard for every bundled JSON resource under
 * {@code src/main/resources/lib/minecraft/renderer/}: the block snapshots
 * ({@code block_models}, {@code block_geometry}, {@code block_defaults}, {@code block_tints},
 * {@code block_items}) plus the colormap, entity, and potion / glint tables ({@code color_maps},
 * {@code entity_geometry}, {@code entity_models}, {@code entity_poses}, {@code potion_colors},
 * {@code glint_items}). All eleven are covered; the criterion is that a native reader consumes the file directly, which
 * {@code BlockItemsLoader} does.
 * <p>
 * It guards the resources that the native readers consume directly. {@code block_geometry} carries
 * its own byte-lock, separate from {@code block_models}, since it holds the geometry rather than
 * having it inlined.
 * <p>
 * Each file is hashed in <b>table-canonical</b> form - Gson-parsed then compactly re-serialized, so
 * whitespace and line-ending drift does not break the check - and compared against the digest
 * pinned in {@code digest.shipped-tables}. Any change, intentional (MC version bump, tooling
 * change) or accidental (a regression in the generator), forces a review. This is byte-stability
 * only, NOT a parity or value-parity gate: a file can be byte-stable and wrong, and the render
 * sweeps catch that.
 * <p>
 * <b>The covered set is discovered, not listed.</b> Every {@code .json} beside the eleven is one of
 * them, so a table added or dropped by a flow shows up as a name in the directory and not in the
 * pin, or the reverse - where a hardcoded roster would have gone quietly out of date.
 * <p>
 * <b>table-canonical is Gson's form and the version rides in provenance.</b> The digest encodes
 * Gson's number formatting, so a dependency bump moves all eleven at once; without the recorded
 * version that reads as eleven simultaneous regressions. It is not {@code store-canonical}, which is
 * pretty-printed and recursively key-sorted, and a digest taken under one form says nothing under
 * the other.
 * <p>
 * Regeneration workflow: regenerate the JSON via the matching {@code ./gradlew} {@code <task>}
 * tooling task - each table's own is pinned beside its digest as {@code regen} - <b>commit the
 * regenerated tables</b>, then {@code ./gradlew parityCapture -Partifacts=digest.shipped-tables},
 * {@code ./gradlew parityCompare -Partifacts=digest.shipped-tables} and
 * {@code ./gradlew parityPromote -Partifacts=digest.shipped-tables -Preason=<why>}. Two of those
 * four steps are the ones a shorter recipe drops and the promotion refuses without. The commit:
 * a capture from an uncommitted tree records {@code asset_dirty}, and a baseline whose capture
 * cannot be shown to have run on a committed tree is re-derivable from no commit. The compare: a
 * capture erases the working root's run directory, so a promotion following one directly has no
 * comparison to apply. No value is transcribed by hand.
 */
@DisplayName("bundled JSON resources match the digests pinned in the parity store")
class BundledResourceShaTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The digest-set this test both writes and reads. */
    private static final @NotNull String ARTIFACT = "digest.shipped-tables";

    /** The canonical form the eleven digests are taken over, recorded per entry beside each. */
    private static final @NotNull String FORM = "table-canonical";

    private static final @NotNull Path RESOURCES = Path.of("src/main/resources/lib/minecraft/renderer");

    /**
     * The tooling flow that rewrites each table.
     *
     * <p>Pinned beside the digest because the three places that record it today can disagree: each
     * shipped JSON's own {@code "//"} line, this class's javadoc, and a loader test's exception
     * string. Only the first is machine-readable, and it lives inside the file the regen rewrites.
     * {@code blockModels} writes two files and {@code entityModels} three.
     */
    private static final @NotNull Map<String, String> REGEN = Map.ofEntries(
        Map.entry("block_defaults", "blockDefaults"),
        Map.entry("block_geometry", "blockModels"),
        Map.entry("block_items", "blockItems"),
        Map.entry("block_models", "blockModels"),
        Map.entry("block_tints", "blockTints"),
        Map.entry("color_maps", "colorMaps"),
        Map.entry("entity_geometry", "entityModels"),
        Map.entry("entity_models", "entityModels"),
        Map.entry("entity_poses", "entityModels"),
        Map.entry("glint_items", "glintItems"),
        Map.entry("potion_colors", "potionColors"));

    @Test
    @DisplayName("table-canonical SHA-256 of each resource equals its pin")
    void resourcesMatchTheirPins() {
        Map<String, String> observed = observedDigests();
        SelfCapture.write(ARTIFACT, entries(observed), List.of("gson=" + gsonVersion()));

        SelfCapture.requireBaseline(ARTIFACT);

        assertThat("the pinned covered set and the tables actually shipped must be the same names; "
                + "a table a flow added or dropped is a review, not a silent narrowing",
            Pins.keys(ARTIFACT), equalTo(List.copyOf(observed.keySet())));

        Map<String, Set<String>> registered = registeredDigests();
        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, String> table : observed.entrySet()) {
            Set<String> declared = registered.get(table.getKey());
            if (declared != null) {
                // A move somebody wrote down, checked against the value they wrote rather than
                // against the pin it is on its way to replacing.
                if (!declared.contains(table.getValue()))
                    drift.add(table.getKey() + ".json: registered to move to " + declared
                        + " but actual " + table.getValue());
                continue;
            }
            String expected = Pins.digest(ARTIFACT, table.getKey());
            if (!expected.equals(table.getValue()))
                drift.add(table.getKey() + ".json: pinned " + expected + " but actual " + table.getValue()
                    + " (register it with `./gradlew parityExpect -Partifact=" + ARTIFACT + " -Pkey="
                    + table.getKey() + " -Pto=" + table.getValue() + " -Preason=<why>` if the move is intended)");
        }
        assertThat("bundled JSON drifted from the digests pinned in the parity store. If intentional, "
                + "re-baseline it: " + Pins.rebaselineCommand(ARTIFACT)
                + "\n" + String.join("\n", drift),
            drift, is(empty()));
    }

    /**
     * The digests each table is registered to move to, out of the working root's expected-diff.
     *
     * <p>A phase that regenerates a table is red here from its first edit until the promote, which is
     * a whole phase of the suite reporting a regression nobody is going to look at - and a standing
     * red is what hides the next real one. A registration is what turns that move into a declared
     * one: it names the table, the digest it must land on, and the reason it moved. So a registered
     * table is checked against THAT value instead of against the pin.
     *
     * <p>It is exactly as strong a guard and not a suppression. A registration naming the wrong
     * digest fails here the way the pin would, the value has to have been written down by somebody,
     * and the manifest lives under the gitignored working root - so it cannot be committed, and a
     * capture of a later phase clears it.
     *
     * @return the registered digests per table, empty where nothing is registered
     */
    private static @NotNull Map<String, Set<String>> registeredDigests() {
        Path manifest = ParityStore.WORKING.resolve(ParityStore.RUN_DIR).resolve("expected-diff.json");
        if (!Files.isRegularFile(manifest)) return Map.of();
        Map<String, Set<String>> registered = new TreeMap<>();
        try {
            JsonObject payload = GSON.fromJson(Files.readString(manifest, StandardCharsets.UTF_8),
                JsonObject.class);
            if (payload == null || !payload.has("movers")) return Map.of();
            for (JsonElement element : payload.getAsJsonArray("movers")) {
                JsonObject row = element.getAsJsonObject();
                if (!ARTIFACT.equals(row.get("artifact").getAsString())) continue;
                registered.computeIfAbsent(row.get("key").getAsString(), key -> new TreeSet<>())
                    .add(row.get("to").getAsString());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return registered;
    }

    /**
     * Returns the table-canonical digest of every shipped table, discovered from the directory.
     *
     * @return table basename to lowercase hex digest, in name order
     */
    private static @NotNull Map<String, String> observedDigests() {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> files = Files.list(RESOURCES)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString().replaceFirst("\\.json$", "");
                found.put(name, canonicalSha256(file));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read the shipped tables under " + RESOURCES, ex);
        }
        return found;
    }

    /**
     * Returns the digest-set payload: one entry per table, carrying the form it was taken under and
     * the flow that rewrites it.
     *
     * @param observed table basename to digest
     * @return the payload entries, keyed by table basename
     */
    private static @NotNull Map<String, JsonObject> entries(@NotNull Map<String, String> observed) {
        Map<String, JsonObject> payload = new TreeMap<>();
        observed.forEach((name, digest) -> {
            String regen = REGEN.get(name);
            if (regen == null)
                throw new IllegalStateException(name + ".json is shipped but no flow is recorded as "
                    + "rewriting it; add it to REGEN so the pin can say how to regenerate it");
            JsonObject entry = new JsonObject();
            entry.addProperty("form", FORM);
            entry.addProperty("regen", regen);
            entry.addProperty("sha256", digest);
            payload.put(name, entry);
        });
        return payload;
    }

    /**
     * Returns the Gson version whose number formatting {@link #FORM} encodes.
     *
     * <p>Read off the runtime rather than off the version catalog, which pins 2.11.0 while conflict
     * resolution selects a later one - a recorded version that is not the one that formatted the
     * bytes is worse than none.
     *
     * @return the resolved version, or {@code unknown} when the jar carries neither record
     */
    private static @NotNull String gsonVersion() {
        try (InputStream stream = Gson.class.getResourceAsStream(
            "/META-INF/maven/com.google.code.gson/gson/pom.properties")) {
            if (stream != null) {
                Properties properties = new Properties();
                properties.load(stream);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank()) return version.trim();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        String implementation = Gson.class.getPackage().getImplementationVersion();
        return implementation == null ? "unknown" : implementation;
    }

    /**
     * Reads the JSON, reparses it with Gson to normalise whitespace and line endings, and returns
     * the SHA-256 of the compact form as a lowercase hex string.
     *
     * @param jsonPath the JSON file to hash
     * @return the lowercase hex SHA-256 of the canonical form
     * @throws IOException if the file cannot be read
     */
    private static @NotNull String canonicalSha256(@NotNull Path jsonPath) throws IOException {
        String raw = Files.readString(jsonPath, StandardCharsets.UTF_8);
        JsonElement tree = GSON.fromJson(raw, JsonElement.class);
        return ParityJson.sha256(GSON.toJson(tree).getBytes(StandardCharsets.UTF_8));
    }

}
