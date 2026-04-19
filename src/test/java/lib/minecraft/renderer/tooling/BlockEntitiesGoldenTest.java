package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Golden-reference checksum guard for {@code block_entities.json}.
 * <p>
 * Hashes the tooling-generated JSON in a canonical form (Gson-parsed then re-serialized
 * without pretty-printing, so Windows/Linux line-ending drift does not break the check)
 * and compares against the committed {@code block_entities.sha256} fixture.
 * <p>
 * When the parser's output changes - either intentionally (MC version bump, new source
 * entry) or accidentally (regression in {@link ToolingBlockEntities.Parser}) - this test
 * flags the drift. Regeneration workflow:
 * <ol>
 *   <li>Run {@code ./gradlew :asset-renderer:blockEntities} to regenerate the JSON.</li>
 *   <li>Re-run this test; it fails with both the expected and actual SHA in the message.</li>
 *   <li>If the change was intentional, paste the actual SHA into
 *       {@code src/test/resources/lib/minecraft/renderer/block_entities.sha256} and commit both.</li>
 *   <li>If the change was unintentional, investigate the parser diff.</li>
 * </ol>
 */
@DisplayName("block_entities.json matches the committed golden SHA-256")
class BlockEntitiesGoldenTest {

    private static final Path JSON_PATH = Path.of("src/main/resources/lib/minecraft/renderer/block_entities.json");
    private static final Path SHA_PATH = Path.of("src/test/resources/lib/minecraft/renderer/block_entities.sha256");

    @Test
    @DisplayName("canonical SHA-256 equals fixture")
    void goldenChecksumMatches() throws IOException, NoSuchAlgorithmException {
        String actual = canonicalSha256();
        String expected = Files.readString(SHA_PATH).trim();
        assertThat(
            "block_entities.json canonical SHA-256 drifted from the fixture. "
                + "If this change is intentional, update "
                + SHA_PATH + " with the actual value below and commit. Actual: " + actual,
            actual, equalTo(expected)
        );
    }

    /**
     * Reads the generated JSON, reparses it with Gson to normalise whitespace and line
     * endings, and returns the SHA-256 of the compact form as a lowercase hex string.
     */
    private static @NotNull String canonicalSha256() throws IOException, NoSuchAlgorithmException {
        String raw = Files.readString(JSON_PATH, StandardCharsets.UTF_8);
        JsonObject tree = new Gson().fromJson(raw, JsonObject.class);
        String canonical = new Gson().toJson(tree);
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest)
            hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

}
