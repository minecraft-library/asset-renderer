package lib.minecraft.renderer.pipeline.load.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.ResourceDocument;
import lib.minecraft.renderer.pipeline.load.BundledResources;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The native reader for per-block default states, replacing
 * {@code BlockStateLoader.loadDefaultStateKeys}'s comma-joined-string parse.
 *
 * <p>{@code block_defaults.json} carries the structured shape (decision 24): {@code blocks{}} maps
 * each block id to a {@code {property:"value"}} object (an empty {@code {}} means "resolved, declares
 * no properties"), and {@code unresolved[]} lists the block ids whose default state could not be
 * ASM-resolved. This reader flattens each block's structured state into the canonical
 * property-sorted {@code "prop=val,prop=val"} key the runtime consumes (empty {@code {}} to the empty
 * string), and omits every {@code unresolved} id - so its output is byte-identical to the legacy
 * comma-joined map. The empty-vs-absent distinction the legacy string form conflated is first-class
 * in the source but collapses back to the same runtime key here.
 */
public final class BlockDefaultsReader {

    private static final @NotNull String RESOURCE_NAME = "block_defaults.json";

    private BlockDefaultsReader() {}

    /**
     * Reads the per-block default-state key map natively from {@code block_defaults.json}.
     *
     * @param diagnostics the scope envelope warnings are recorded to
     * @return block id to its canonical default-state key (e.g. {@code "facing=north,lit=false"}),
     *     wrapped unmodifiable; {@code unresolved} ids are absent
     * @throws PipelineException if the resource is missing or has no {@code blocks} object
     */
    public static @NotNull ConcurrentMap<String, String> load(@NotNull Diagnostics diagnostics) {
        ResourceDocument document = BundledResources.read(RESOURCE_NAME, BundledResources.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        JsonObject root = document.payload().toGson().getAsJsonObject();
        if (!root.has("blocks"))
            throw new PipelineException("Block-defaults resource '%s' has no 'blocks' object", RESOURCE_NAME);

        Set<String> unresolved = new HashSet<>();
        if (root.has("unresolved"))
            for (JsonElement element : root.getAsJsonArray("unresolved")) unresolved.add(element.getAsString());

        JsonObject blocks = root.getAsJsonObject("blocks");
        HashMap<String, String> defaults = new HashMap<>();
        for (String blockId : blocks.keySet()) {
            if (unresolved.contains(blockId)) continue;
            defaults.put(blockId, joinProperties(blocks.getAsJsonObject(blockId)));
        }
        return Concurrent.adoptMap(defaults).toUnmodifiable();
    }

    /**
     * Joins a structured default-state object into the legacy {@code prop=val,prop=val} key,
     * property-name-sorted; an empty object joins to the empty string.
     *
     * @param state the structured {@code {property:value}} default state
     * @return the comma-joined property key
     */
    private static @NotNull String joinProperties(@NotNull JsonObject state) {
        List<String> properties = new ArrayList<>(state.keySet());
        properties.sort(null);
        StringJoiner joiner = new StringJoiner(",");
        for (String property : properties)
            joiner.add(property + "=" + state.get(property).getAsString());
        return joiner.toString();
    }
}
