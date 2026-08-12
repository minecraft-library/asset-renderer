package lib.minecraft.renderer.pipeline.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.parity.ParityJson;
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
import java.util.TreeMap;
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
 * {@code entity_geometry}, {@code entity_models}, {@code potion_colors}, {@code glint_items}).
 * All ten are covered; the criterion is that a native reader consumes the file directly, which
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
 * <b>The covered set is discovered, not listed.</b> Every {@code .json} beside the ten is one of
 * them, so a table added or dropped by a flow shows up as a name in the directory and not in the
 * pin, or the reverse - where a hardcoded roster would have gone quietly out of date.
 * <p>
 * <b>table-canonical is Gson's form and the version rides in provenance.</b> The digest encodes
 * Gson's number formatting, so a dependency bump moves all ten at once; without the recorded
 * version that reads as ten simultaneous regressions. It is not {@code store-canonical}, which is
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

    /** The canonical form the ten digests are taken over, recorded per entry beside each. */
    private static final @NotNull String FORM = "table-canonical";

    private static final @NotNull Path RESOURCES = Path.of("src/main/resources/lib/minecraft/renderer");

    /**
     * The tooling flow that rewrites each table.
     *
     * <p>Pinned beside the digest because the three places that record it today can disagree: each
     * shipped JSON's own {@code "//"} line, this class's javadoc, and a loader test's exception
     * string. Only the first is machine-readable, and it lives inside the file the regen rewrites.
     * Two flows write two files each.
     */
    private static final @NotNull Map<String, String> REGEN = Map.of(
        "block_defaults", "blockDefaults",
        "block_geometry", "blockModels",
        "block_items", "blockItems",
        "block_models", "blockModels",
        "block_tints", "blockTints",
        "color_maps", "colorMaps",
        "entity_geometry", "entityModels",
        "entity_models", "entityModels",
        "glint_items", "glintItems",
        "potion_colors", "potionColors");

    @Test
    @DisplayName("table-canonical SHA-256 of each resource equals its pin")
    void resourcesMatchTheirPins() {
        Map<String, String> observed = observedDigests();
        SelfCapture.write(ARTIFACT, entries(observed), List.of("gson=" + gsonVersion()));

        SelfCapture.requireBaseline(ARTIFACT);

        assertThat("the pinned covered set and the tables actually shipped must be the same names; "
                + "a table a flow added or dropped is a review, not a silent narrowing",
            Pins.keys(ARTIFACT), equalTo(List.copyOf(observed.keySet())));

        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, String> table : observed.entrySet()) {
            String expected = Pins.digest(ARTIFACT, table.getKey());
            if (!expected.equals(table.getValue()))
                drift.add(table.getKey() + ".json: pinned " + expected + " but actual " + table.getValue());
        }
        assertThat("bundled JSON drifted from the digests pinned in the parity store. If intentional, "
                + "re-baseline it: " + Pins.rebaselineCommand(ARTIFACT)
                + "\n" + String.join("\n", drift),
            drift, is(empty()));
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
