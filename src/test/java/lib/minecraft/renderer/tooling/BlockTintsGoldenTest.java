package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
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
 * Golden-reference guard for {@code block_tints.json}.
 * <p>
 * The fast test hashes the tooling-generated JSON in canonical form (Gson-parsed then compactly
 * re-serialized, so line-ending drift does not break the check) and compares against the committed
 * {@code block_tints.sha256} fixture. Drift - intentional (MC version bump) or accidental
 * (regression in {@link ToolingBlockTints.Parser}, e.g. flipping the {@code constant(inHand,
 * inWorld)} pick or losing the deterministic {@code Concurrent.newLinkedMap} entry order) - forces
 * a review. Mirrors {@link BlockStatesGoldenTest}.
 * <p>
 * Regeneration workflow: run {@code ./gradlew :asset-renderer:blockTints}, re-run this test, and if
 * the change is intentional paste the printed actual SHA into the fixture and commit both.
 */
@DisplayName("block_tints.json matches the committed golden SHA-256")
class BlockTintsGoldenTest {

    private static final Path JSON_PATH = Path.of("src/main/resources/lib/minecraft/renderer/block_tints.json");
    private static final Path SHA_PATH = Path.of("src/test/resources/lib/minecraft/renderer/block_tints.sha256");

    @Test
    @DisplayName("canonical SHA-256 equals fixture")
    void goldenChecksumMatches() throws IOException, NoSuchAlgorithmException {
        String actual = canonicalSha256();
        String expected = Files.readString(SHA_PATH).trim();
        assertThat(
            "block_tints.json canonical SHA-256 drifted from the fixture. "
                + "If this change is intentional, update "
                + SHA_PATH + " with the actual value below and commit. Actual: " + actual,
            actual, equalTo(expected)
        );
    }

    /**
     * Reads the generated JSON, reparses it with Gson to normalise whitespace and line endings,
     * and returns the SHA-256 of the compact form as a lowercase hex string.
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

    @BeforeAll
    static void ensureGeneratedJsonExists() {
        if (!Files.exists(JSON_PATH))
            throw new IllegalStateException("Run ./gradlew :asset-renderer:blockTints to generate " + JSON_PATH);
    }

}
