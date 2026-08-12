package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
 * Live-pipeline cross-check for {@code block_defaults.json}.
 * <p>
 * The byte-level integrity digest is pinned alongside the other bundled JSON in
 * {@code digest.shipped-tables} and asserted by {@code BundledResourceShaTest}. This {@code slow}-tagged test cross-checks the
 * committed snapshot against a live pipeline: each non-empty {@code default} key must subset-resolve
 * to one of the block's runtime {@code block.variants().keySet()} variants. This catches the
 * ASM-derived default drifting away from the live blockstate parse.
 * <p>
 * The snapshot stores each block's default state as a structured {@code {prop:"val"}} object (an
 * empty {@code {}} for a no-property block); this test reconstructs the comma-joined key from that
 * object exactly as {@code BlockDefaultsLoader} does at load.
 * <p>
 * Regeneration workflow: run {@code ./gradlew blockDefaults} to refresh the snapshot,
 * then re-pin its digest per {@code BundledResourceShaTest}. No digest is transcribed by hand.
 */
@DisplayName("block_defaults.json agrees with the live pipeline")
class BlockDefaultsLivePipelineTest {

    private static final Path JSON_PATH = Path.of("src/main/resources/lib/minecraft/renderer/block_defaults.json");
    private static final Gson GSON = GsonSettings.defaults().create();

    @BeforeAll
    static void ensureGeneratedJsonExists() {
        if (!Files.exists(JSON_PATH))
            throw new IllegalStateException("Run ./gradlew blockDefaults to generate " + JSON_PATH);
    }

    @Test
    @Tag("slow")
    @DisplayName("each default resolves to a live-pipeline variant")
    void crossCheckAgainstLivePipeline() throws IOException {
        // Acquired here rather than through the extension because this class carries its slow tag on the
        // one method, so a class-level extension would boot the pipeline for the fast suite too.
        PipelineRendererContext context = ClientAssetsExtension.context();

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
     * Reconstructs the comma-joined {@code prop=val} default-state key from the structured
     * {@code {prop:"val"}} object (properties are stored sorted), mirroring {@code BlockDefaultsLoader}.
     *
     * @param properties one block's stored default state
     * @return the joined key, empty for a no-property block
     */
    private static @NotNull String joinDefaultKey(@NotNull JsonObject properties) {
        StringBuilder key = new StringBuilder();
        for (Map.Entry<String, JsonElement> property : properties.entrySet()) {
            if (!key.isEmpty()) key.append(',');
            key.append(property.getKey()).append('=').append(property.getValue().getAsString());
        }
        return key.toString();
    }

    /**
     * Applies the superset match a rendered block resolves its variant by. That matcher is private
     * on a nested class of the block renderer and cannot be called from here, so the arithmetic is
     * necessarily restated rather than shared.
     *
     * @param defaultKey the fully-qualified default-state key
     * @param variantKeys the block's runtime variant keys
     * @return whether some variant key's {@code property=value} pairs are all present in the default key
     */
    private static boolean subsetResolves(@NotNull String defaultKey, @NotNull Set<String> variantKeys) {
        Set<String> defaultPairs = new HashSet<>(Arrays.asList(defaultKey.split(",")));
        for (String variantKey : variantKeys) {
            if (variantKey.isEmpty()) return true;
            if (defaultPairs.containsAll(Arrays.asList(variantKey.split(",")))) return true;
        }
        return false;
    }

}
