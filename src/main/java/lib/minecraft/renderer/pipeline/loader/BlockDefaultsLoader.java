package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.pipeline.load.BlockRendererOverrides;
import lib.minecraft.renderer.json.JsonNode;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.ResourceDocument;
import lib.minecraft.renderer.pipeline.load.BundledResource;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The reader for per-block default states.
 *
 * <p>{@code block_defaults.json} carries the structured shape: {@code blocks{}} maps
 * each block id to a {@code {property:"value"}} object (an empty {@code {}} means "resolved, declares
 * no properties"), and {@code unresolved[]} lists the block ids whose default state could not be
 * ASM-resolved. This reader flattens each block's structured state into the canonical
 * property-sorted {@code "prop=val,prop=val"} key the runtime consumes (empty {@code {}} to the empty
 * string), and omits every {@code unresolved} id. The empty-vs-absent distinction is first-class in
 * the structured source but collapses to the same runtime key here.
 */
public final class BlockDefaultsLoader {

    private static final @NotNull String RESOURCE_NAME = "block_defaults.json";

    private BlockDefaultsLoader() {}

    /**
     * Reads the per-block default-state key map natively from {@code block_defaults.json}, with no pack
     * override channel applied.
     *
     * @param diagnostics the scope envelope warnings are recorded to
     * @return block id to its canonical default-state key (e.g. {@code "facing=north,lit=false"}),
     *     wrapped unmodifiable; {@code unresolved} ids are absent
     * @throws PipelineException if the resource is missing or has no {@code blocks} object
     */
    public static @NotNull ConcurrentMap<String, String> load(@NotNull Diagnostics diagnostics) {
        return load(diagnostics, BlockRendererOverrides.EMPTY);
    }

    /**
     * Reads the per-block default-state key map from {@code block_defaults.json}, then overlays the
     * pack-supplied {@code renderer/block_defaults.json} override channel: each
     * pack {@code blocks} entry replaces the classpath default state of the same id and resolves it (so
     * a pack can change a default state key that a vanilla-format pack cannot). This is the only way
     * any pack can override an ASM-derived default state.
     *
     * @param diagnostics the scope envelope warnings are recorded to
     * @param overrides the gathered pack override channel; {@link BlockRendererOverrides#EMPTY} for a
     *     vanilla-only stack, which leaves the result byte-identical to the classpath snapshot
     * @return block id to its canonical default-state key, wrapped unmodifiable; {@code unresolved} ids
     *     are absent unless a pack override resolves them
     * @throws PipelineException if the resource is missing or has no {@code blocks} object
     */
    public static @NotNull ConcurrentMap<String, String> load(@NotNull Diagnostics diagnostics, @NotNull BlockRendererOverrides overrides) {
        ResourceDocument document = BundledResource.read(RESOURCE_NAME, BundledResource.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        DefaultsDoc doc = document.as(DefaultsDoc.class);
        if (doc.blocks() == null)
            throw new PipelineException("Block-defaults resource '%s' has no 'blocks' object", RESOURCE_NAME);

        Set<String> unresolved = doc.unresolved() == null ? new HashSet<>() : new HashSet<>(doc.unresolved());
        // Mutable copy of the base blocks map (JSON order preserved); the pack override channel overlays
        // later-wins per block id (replacing in place, appending a new id last - matching the source order).
        LinkedHashMap<String, Map<String, String>> blocks = new LinkedHashMap<>(doc.blocks());

        // Overlay the pack override channel per block id (later-wins) and resolve each overridden id -
        // a pack supplying a default state removes any classpath "unresolved" mark for it. A non-object
        // entry (e.g. the author wrote the flat "facing=east" runtime form instead of {"facing":"east"})
        // is skipped with a warning rather than silently dropped.
        JsonNode defaultOverrides = overrides.defaults();
        for (Map.Entry<String, JsonNode> override : defaultOverrides.members()) {
            String blockId = override.getKey();
            if (!override.getValue().isObject()) {
                System.err.printf("renderer/block_defaults.json override for '%s' is not a {property:value} object; ignored%n", blockId);
                continue;
            }
            blocks.put(blockId, toStringMap(blockId, override.getValue()));
            unresolved.remove(blockId);
        }

        HashMap<String, String> defaults = new HashMap<>();
        for (String blockId : blocks.keySet()) {
            if (unresolved.contains(blockId)) continue;
            defaults.put(blockId, joinProperties(blocks.get(blockId)));
        }
        return Concurrent.adoptMap(defaults).toUnmodifiable();
    }

    /**
     * Converts a pack override's {@code {property:value}} object into a {@code property -> value} map,
     * failing with a clear block-attributed message when a value is not a scalar (a malformed pack
     * override entry) rather than a raw {@code IllegalStateException}.
     *
     * @param blockId the block id the override belongs to, for error attribution
     * @param state the override's {@code {property:value}} object
     * @return the property-to-value map
     * @throws PipelineException if a property value is not a JSON primitive
     */
    private static @NotNull Map<String, String> toStringMap(@NotNull String blockId, @NotNull JsonNode state) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : state.members()) {
            JsonNode value = entry.getValue();
            if (!value.isPrimitive())
                throw new PipelineException("Block-defaults override for '%s' property '%s' is not a scalar value", blockId, entry.getKey());
            map.put(entry.getKey(), value.stringValue().orElseThrow());
        }
        return map;
    }

    /**
     * Joins a structured default-state map into the legacy {@code prop=val,prop=val} key,
     * property-name-sorted; an empty map joins to the empty string.
     *
     * @param state the {@code property -> value} default state
     * @return the comma-joined property key
     */
    private static @NotNull String joinProperties(@NotNull Map<String, String> state) {
        List<String> properties = new ArrayList<>(state.keySet());
        properties.sort(null);
        StringJoiner joiner = new StringJoiner(",");
        for (String property : properties)
            joiner.add(property + "=" + state.get(property));
        return joiner.toString();
    }

    /**
     * The {@code block_defaults.json} payload: {@code blocks} maps each block id to its structured
     * {@code {property:value}} default state (an empty map means "resolved, declares no properties"),
     * and {@code unresolved} lists the block ids whose default state could not be ASM-resolved.
     *
     * @param blocks the per-block structured default states, in on-disk order
     * @param unresolved the block ids whose default state is unresolved (omitted from the runtime map)
     */
    record DefaultsDoc(@NotNull Map<String, Map<String, String>> blocks, @Nullable List<String> unresolved) {}
}
