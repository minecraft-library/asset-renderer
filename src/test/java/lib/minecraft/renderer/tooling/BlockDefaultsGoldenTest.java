package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Live-pipeline cross-check for {@code v2/block_defaults.json}.
 * <p>
 * The byte-level integrity fixture ({@code v2/block_defaults.sha256}) is asserted alongside the other
 * bundled v2 JSON in {@code V2JsonResourceShaTest}. This {@code slow}-tagged test cross-checks the
 * committed snapshot against a live pipeline: each non-empty {@code default} key must subset-resolve
 * to one of the block's runtime {@code block.getVariants().keySet()} variants. This catches the
 * ASM-derived default drifting away from the live blockstate parse.
 * <p>
 * The v2 snapshot stores each block's default state as a structured {@code {prop:"val"}} object (an
 * empty {@code {}} for a no-property block); this test reconstructs the comma-joined key from that
 * object exactly as {@code BlockDefaultsReader} does at load.
 * <p>
 * Regeneration workflow: run {@code ./gradlew :asset-renderer:blockDefaults2} to refresh the snapshot,
 * then update {@code v2/block_defaults.sha256} per {@code V2JsonResourceShaTest}.
 */
@DisplayName("v2/block_defaults.json agrees with the live pipeline")
class BlockDefaultsGoldenTest {

    private static final Path JSON_PATH = Path.of("src/main/resources/lib/minecraft/renderer/v2/block_defaults.json");
    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @Tag("slow")
    @DisplayName("each default resolves to a live-pipeline variant")
    void crossCheckAgainstLivePipeline() throws IOException {
        Pipeline.Result result = Pipeline.run(PipelineOptions.builder()
            .version("26.1")
            .cacheRoot(new File("cache/it"))
            .build());
        PipelineRendererContext context = PipelineRendererContext.of(result);

        String raw = Files.readString(JSON_PATH, StandardCharsets.UTF_8);
        JsonObject blocks = GSON.fromJson(raw, JsonObject.class).getAsJsonObject("blocks");

        List<String> mismatches = new ArrayList<>();
        for (String blockId : blocks.keySet()) {
            Block block = context.findBlock(blockId).orElse(null);
            if (block == null) continue;

            Set<String> runtimeVariants = new TreeSet<>(block.variants().keySet());
            String defaultKey = joinDefaultKey(blocks.getAsJsonObject(blockId));
            if (!defaultKey.isEmpty() && !runtimeVariants.isEmpty() && !subsetResolves(defaultKey, runtimeVariants))
                mismatches.add(blockId + ": default '" + defaultKey + "' resolves to no variant in " + runtimeVariants);
        }

        assertThat("block_defaults.json drifted from the live pipeline:\n" + String.join("\n", mismatches),
            mismatches, is(empty()));
    }

    /**
     * Reconstructs the comma-joined {@code prop=val} default-state key from the v2 structured
     * {@code {prop:"val"}} object (properties are stored sorted), mirroring {@code BlockDefaultsReader}.
     * An empty object yields the empty key (a no-property block).
     */
    private static @NotNull String joinDefaultKey(@NotNull JsonObject properties) {
        StringBuilder key = new StringBuilder();
        for (Map.Entry<String, JsonElement> property : properties.entrySet()) {
            if (key.length() > 0) key.append(',');
            key.append(property.getKey()).append('=').append(property.getValue().getAsString());
        }
        return key.toString();
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
            throw new IllegalStateException("Run ./gradlew :asset-renderer:blockDefaults2 to generate " + JSON_PATH);
    }

}
