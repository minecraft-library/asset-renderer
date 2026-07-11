package lib.minecraft.renderer.pipeline.load;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Single golden-reference guard for every bundled v2 JSON resource under
 * {@code src/main/resources/lib/minecraft/renderer/v2/}: the block snapshots
 * ({@code block_models}, {@code block_geometry}, {@code block_defaults}, {@code block_tints}) plus the
 * colormap, entity, and potion / glint tables ({@code color_maps}, {@code entity_geometry},
 * {@code entity_models}, {@code potion_colors}, {@code glint_items}).
 * <p>
 * This is the pipeline-side successor to the retired legacy {@code JsonResourceShaTest}: the
 * byte-stability gate transferred from the eight legacy files to the nine v2 files in the same change
 * that retired the tooling2 bridge ({@code block_geometry} gains its own byte-lock, which it lacked
 * legacy-side where geometry was inlined into {@code block_models}). It now guards CONSUMER inputs -
 * the v2 resources the native readers read - rather than legacy tooling output.
 * <p>
 * Each file is hashed in canonical form (Gson-parsed then compactly re-serialized, so whitespace
 * and line-ending drift does not break the check) and compared against its committed
 * {@code *.sha256} fixture. Any change - intentional (MC version bump, tooling change) or
 * accidental (a regression in the generator) - forces a review. This is byte-stability only, NOT a
 * parity or value-parity gate (a v2 file can be byte-stable and wrong; the render sweeps and the
 * value-parity ledger catch that).
 * <p>
 * Regeneration workflow: regenerate the JSON via the matching {@code ./gradlew} {@code <task>2}
 * tooling task, re-run this test, read the printed actual SHA-256, paste it into the matching fixture
 * under {@code src/test/resources/lib/minecraft/renderer/v2/}, and commit both.
 */
@DisplayName("bundled v2 JSON resources match their committed SHA-256 fixtures")
class V2JsonResourceShaTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    static final @NotNull Path RESOURCES = Path.of("src/main/resources/lib/minecraft/renderer/v2");
    static final @NotNull Path FIXTURES = Path.of("src/test/resources/lib/minecraft/renderer/v2");

    static final @NotNull List<String> COVERED = List.of(
        "block_models", "block_geometry", "block_defaults", "block_tints",
        "color_maps", "entity_geometry", "entity_models", "potion_colors", "glint_items"
    );

    @Test
    @DisplayName("canonical SHA-256 of each v2 resource equals its fixture")
    void resourcesMatchFixtures() throws IOException, NoSuchAlgorithmException {
        List<String> drift = new ArrayList<>();
        for (String name : COVERED) {
            String actual = canonicalSha256(RESOURCES.resolve(name + ".json"));
            Path fixture = FIXTURES.resolve(name + ".sha256");
            String expected = Files.exists(fixture) ? Files.readString(fixture).trim() : "<missing fixture>";
            if (!actual.equals(expected))
                drift.add(name + ".json: fixture " + expected + " but actual " + actual);
        }
        assertThat("bundled v2 JSON drifted from the committed SHA-256 fixtures. If intentional, "
                + "paste each actual value into the matching .sha256 and commit:\n" + String.join("\n", drift),
            drift, is(empty()));
    }

    /**
     * Reads the JSON, reparses it with Gson to normalise whitespace and line endings, and returns
     * the SHA-256 of the compact form as a lowercase hex string.
     *
     * @param jsonPath the JSON file to hash
     * @return the lowercase hex SHA-256 of the canonical form
     */
    static @NotNull String canonicalSha256(@NotNull Path jsonPath) throws IOException, NoSuchAlgorithmException {
        String raw = Files.readString(jsonPath, StandardCharsets.UTF_8);
        JsonElement tree = GSON.fromJson(raw, JsonElement.class);
        String canonical = GSON.toJson(tree);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest)
            hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

}
