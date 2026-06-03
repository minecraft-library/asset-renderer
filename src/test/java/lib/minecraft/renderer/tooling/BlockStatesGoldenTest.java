package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Golden-reference guard for {@code block_states.json}.
 * <p>
 * The fast test hashes the tooling-generated JSON in canonical form (Gson-parsed then compactly
 * re-serialized, so line-ending drift does not break the check) and compares against the
 * committed {@code block_states.sha256} fixture. Drift - intentional (MC version bump) or
 * accidental (regression in {@link ToolingBlockStates.Parser}) - forces a review.
 * <p>
 * The {@code slow}-tagged test cross-checks the snapshot against a live pipeline: each block's
 * {@code variants} list must equal the runtime {@code block.getVariants().keySet()}, and a
 * non-empty {@code default} must subset-resolve to one of those variants. This catches the
 * snapshot drifting away from the live blockstate parse.
 * <p>
 * Regeneration workflow: run {@code ./gradlew :asset-renderer:blockStates}, re-run this test,
 * and if the change is intentional paste the printed actual SHA into the fixture and commit both.
 */
@DisplayName("block_states.json matches the committed golden SHA-256")
class BlockStatesGoldenTest {

    private static final Path JSON_PATH = Path.of("src/main/resources/lib/minecraft/renderer/block_states.json");
    private static final Path SHA_PATH = Path.of("src/test/resources/lib/minecraft/renderer/block_states.sha256");

    @Test
    @DisplayName("canonical SHA-256 equals fixture")
    void goldenChecksumMatches() throws IOException, NoSuchAlgorithmException {
        String actual = canonicalSha256();
        String expected = Files.readString(SHA_PATH).trim();
        assertThat(
            "block_states.json canonical SHA-256 drifted from the fixture. "
                + "If this change is intentional, update "
                + SHA_PATH + " with the actual value below and commit. Actual: " + actual,
            actual, equalTo(expected)
        );
    }

    @Test
    @Tag("slow")
    @DisplayName("snapshot variants + default agree with a live pipeline")
    void crossCheckAgainstLivePipeline() throws IOException {
        Pipeline.Result result = Pipeline.run(PipelineOptions.builder()
            .version("26.1")
            .cacheRoot(new File("cache/it"))
            .build());
        PipelineRendererContext context = PipelineRendererContext.of(result);

        String raw = Files.readString(JSON_PATH, StandardCharsets.UTF_8);
        JsonObject blocks = new Gson().fromJson(raw, JsonObject.class).getAsJsonObject("blocks");

        List<String> mismatches = new ArrayList<>();
        for (String blockId : blocks.keySet()) {
            Block block = context.findBlock(blockId).orElse(null);
            if (block == null) continue;

            Set<String> snapshotVariants = new TreeSet<>();
            blocks.getAsJsonObject(blockId).getAsJsonArray("variants").forEach(e -> snapshotVariants.add(e.getAsString()));
            Set<String> runtimeVariants = new TreeSet<>(block.getVariants().keySet());
            if (!snapshotVariants.equals(runtimeVariants))
                mismatches.add(blockId + ": variants " + snapshotVariants + " != runtime " + runtimeVariants);

            String defaultKey = blocks.getAsJsonObject(blockId).get("default").getAsString();
            if (!defaultKey.isEmpty() && !runtimeVariants.isEmpty() && !subsetResolves(defaultKey, runtimeVariants))
                mismatches.add(blockId + ": default '" + defaultKey + "' resolves to no variant in " + runtimeVariants);
        }

        assertThat("block_states.json drifted from the live pipeline:\n" + String.join("\n", mismatches),
            mismatches, is(empty()));
    }

    /**
     * Returns {@code true} when some variant key's {@code property=value} pairs are all present in
     * the (fully-qualified) default key - mirroring {@code BlockRenderer.resolveVariant}'s
     * superset match.
     */
    private static boolean subsetResolves(@NotNull String defaultKey, @NotNull Set<String> variantKeys) {
        Set<String> defaultPairs = new HashSet<>(Arrays.asList(defaultKey.split(",")));
        for (String variantKey : variantKeys) {
            if (variantKey.isEmpty()) return true;
            if (defaultPairs.containsAll(Arrays.asList(variantKey.split(",")))) return true;
        }
        return false;
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
            throw new IllegalStateException("Run ./gradlew :asset-renderer:blockStates to generate " + JSON_PATH);
    }

}
