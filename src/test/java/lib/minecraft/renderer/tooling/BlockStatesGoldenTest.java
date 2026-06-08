package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Live-pipeline cross-check for {@code block_states.json}.
 * <p>
 * The byte-level integrity fixture ({@code block_states.sha256}) is asserted alongside the other
 * bundled JSON in {@code JsonResourceShaTest}. This {@code slow}-tagged test cross-checks the
 * committed snapshot against a live pipeline: every snapshot {@code variants} entry must be present
 * in the runtime {@code block.getVariants().keySet()}, and a non-empty {@code default} must
 * subset-resolve to one of those variants. This catches the snapshot drifting away from the live
 * blockstate parse.
 * <p>
 * Regeneration workflow: run {@code ./gradlew :asset-renderer:blockStates} to refresh the snapshot,
 * then update {@code block_states.sha256} per {@code JsonResourceShaTest}.
 */
@DisplayName("block_states.json agrees with the live pipeline")
class BlockStatesGoldenTest {

    private static final Path JSON_PATH = Path.of("src/main/resources/lib/minecraft/renderer/block_states.json");

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
            // Runtime variants are a superset of the block-state snapshot: a block-entity block
            // (e.g. a hanging sign) exposes a synthetic {@code attached=true} variant sourced from
            // the block-model / block-entity path that the {@code createBlockStateDefinition} walk
            // behind block_states.json does not - and must not - emit. The snapshot must still be a
            // subset; a snapshot variant absent at runtime is genuine drift.
            if (!runtimeVariants.containsAll(snapshotVariants))
                mismatches.add(blockId + ": snapshot variants " + snapshotVariants + " not all present in runtime " + runtimeVariants);

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

    @BeforeAll
    static void ensureGeneratedJsonExists() {
        if (!Files.exists(JSON_PATH))
            throw new IllegalStateException("Run ./gradlew :asset-renderer:blockStates to generate " + JSON_PATH);
    }

}
