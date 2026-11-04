package lib.minecraft.renderer.guard;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.store.ParityJson;
import lib.minecraft.renderer.store.ParityStore;
import lib.minecraft.renderer.store.Pins;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Each file's raw SHA-256 is compared against the digest {@code manifest.tooling-tables} holds for
 * it in its {@code files} member - the manifest the tooling flows' capture writes over these same
 * bytes - so the fast suite fails on a table the store has not been told about without a single flow
 * having run. Any change, intentional (MC version bump, tooling change) or accidental (a regression
 * in the generator), forces a review. This is byte-stability only, NOT a parity or value-parity
 * gate: a file can be byte-stable and wrong, and the render sweeps catch that.
 * <p>
 * <b>The covered set is discovered, not listed.</b> Every {@code .json} at the top of the directory
 * is one of the eleven, so a table added or dropped by a flow shows up as a name in the directory and
 * not in the manifest, or the reverse - where a hardcoded roster would have gone quietly out of date.
 * The two populations are found by different walks, a top-level listing here and the manifest's own
 * recursive one, so a table written into a sub-directory is a disagreement rather than a match.
 * <p>
 * <b>Raw bytes, so no library's formatting rides in the digest.</b> {@code .gitattributes} checks
 * every text file out LF, so the raw form cannot drift on line endings, and a dependency bump that
 * moves no table byte moves no digest here.
 * <p>
 * Regeneration workflow: regenerate the JSON via the matching {@code ./gradlew} {@code <task>}
 * tooling task, <b>commit the regenerated tables</b>, then
 * {@code ./gradlew parityCapture -Partifacts=manifest.tooling-tables},
 * {@code ./gradlew parityCompare -Partifacts=manifest.tooling-tables} and
 * {@code ./gradlew parityPromote -Partifacts=manifest.tooling-tables -Preason=<why>}. Two of those
 * four steps are the ones a shorter recipe drops and the promotion refuses without. The commit:
 * a capture from an uncommitted tree records {@code asset_dirty}, and a baseline whose capture
 * cannot be shown to have run on a committed tree is re-derivable from no commit. The compare: a
 * capture erases the working root's run directory, so a promotion following one directly has no
 * comparison to apply. No value is transcribed by hand.
 */
@DisplayName("bundled JSON resources match the digests the tooling-tables manifest holds")
class BundledResourceShaTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The manifest whose per-table digests this test reads. */
    private static final @NotNull String MANIFEST = "manifest.tooling-tables";

    private static final @NotNull Path RESOURCES = Path.of("src/main/resources/lib/minecraft/renderer");

    @Test
    @DisplayName("raw SHA-256 of each resource equals the digest the manifest holds for it")
    void resourcesMatchTheManifest() {
        Map<String, String> observed = observedDigests();
        Map<String, String> pinned = manifestDigests();

        assertThat("the manifest's covered set and the tables actually shipped must be the same names; "
                + "a table a flow added or dropped is a review, not a silent narrowing",
            List.copyOf(pinned.keySet()), equalTo(List.copyOf(observed.keySet())));

        Map<String, Set<String>> registered = registeredDigests();
        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, String> table : observed.entrySet()) {
            Set<String> declared = registered.get(table.getKey());
            if (declared != null) {
                // A move somebody wrote down, checked against the value they wrote rather than
                // against the digest it is on its way to replacing.
                if (!declared.contains(table.getValue()))
                    drift.add(table.getKey() + ": registered to move to " + declared
                        + " but actual " + table.getValue());
                continue;
            }
            String expected = pinned.get(table.getKey());
            if (!expected.equals(table.getValue()))
                drift.add(table.getKey() + ": pinned " + expected + " but actual " + table.getValue()
                    + " (register it with `./gradlew parityExpect -Partifact=" + MANIFEST + " -Pkey="
                    + table.getKey() + " -Pto=" + table.getValue() + " -Preason=<why>` if the move is intended)");
        }
        assertThat("bundled JSON drifted from the digests the parity store holds. If intentional, "
                + "re-baseline it: " + Pins.rebaselineCommand(MANIFEST)
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
     * table is checked against THAT value instead of against the manifest.
     *
     * <p>It is exactly as strong a guard and not a suppression. A registration naming the wrong
     * digest fails here the way the manifest would, the value has to have been written down by
     * somebody, and the expected-diff lives under the gitignored working root - so it cannot be
     * committed, and the next phase's {@code parityExpect -PexpectEmpty} clears it.
     *
     * @return the registered digests per table file name, empty where nothing is registered
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
                if (!MANIFEST.equals(row.get("artifact").getAsString())) continue;
                registered.computeIfAbsent(row.get("key").getAsString(), key -> new TreeSet<>())
                    .add(row.get("to").getAsString());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return registered;
    }

    /**
     * Returns the digest {@code manifest.tooling-tables} holds for each table it covers.
     *
     * @return table path, relative to the resource directory, to lowercase hex digest, in path order
     */
    private static @NotNull Map<String, String> manifestDigests() {
        Map<String, String> pinned = new TreeMap<>();
        for (JsonElement element : ParityStore.read(MANIFEST).getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject();
            pinned.put(file.get("path").getAsString(), file.get("sha256").getAsString());
        }
        return pinned;
    }

    /**
     * Returns the raw digest of every shipped table, discovered from the directory's top level.
     *
     * @return table file name to lowercase hex digest, in name order
     */
    private static @NotNull Map<String, String> observedDigests() {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> files = Files.list(RESOURCES)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList())
                found.put(file.getFileName().toString(), ParityJson.sha256(Files.readAllBytes(file)));
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read the shipped tables under " + RESOURCES, ex);
        }
        return found;
    }

}
