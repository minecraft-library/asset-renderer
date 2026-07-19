package lib.minecraft.renderer.pipeline.util;

import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.exception.PipelineException;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.pipeline.loader.BlockDefaultsLoader;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * The block-entity geometry override channel: the merged {@code renderer/*.json}
 * files each pack may ship at its ROOT (outside {@code assets/}, mirroring the classpath layout
 * {@code lib/minecraft/renderer/} so a vanilla client ignores them) to deliberately replace
 * classpath-bundled block-entity geometry.
 *
 * <p>{@link #gather(PackStack, Diagnostics)} walks the stack ascending and overlays each pack's
 * {@code renderer/block_models.json} / {@code renderer/block_geometry.json} /
 * {@code renderer/block_defaults.json} onto three accumulators, keyed by top-level entry (model id,
 * geometry coordinate, block id) - the per-block-id granularity that lets a pack replace one chest
 * without shipping the whole ~180-block snapshot. A higher-priority pack's entry wins on key
 * collision. Every file is validated through {@link ResourceDocument#open} (the internal format-2
 * envelope), so a format mismatch or unparseable payload rejects LOUDLY with pack attribution rather
 * than silently degrading: the channel is a deliberate opt-in feature file, not bulk pack content. A
 * structurally-broken entry that passes the envelope (e.g. a model missing its geometry coordinate, a
 * default-state whose property value is not a scalar) is rejected by the reader with entry / model-id
 * attribution.
 *
 * <p>The three accumulators are the OVERLAY over the classpath base: {@link BlockModelReader} and
 * {@link BlockDefaultsLoader} read their bundled snapshot first, then apply these per-entry. A
 * vanilla-only stack ships none of these files, so the gathered overrides are empty and the readers
 * produce byte-identical output.
 *
 * @param models model-id to block-entity model entry, overlaying {@code block_models.json}'s {@code models}
 * @param geometries geometry coordinate to bone tree, overlaying {@code block_geometry.json}'s {@code geometries}
 * @param defaults block id to structured default-state object, overlaying {@code block_defaults.json}'s {@code blocks}
 */
public record BlockRendererOverrides(
    @NotNull JsonTree models,
    @NotNull JsonTree geometries,
    @NotNull JsonTree defaults
) {

    /** Pack-root path of the block-model catalog override, mirroring the classpath {@code block_models.json}. */
    private static final @NotNull String MODELS_PATH = "renderer/block_models.json";

    /** Pack-root path of the geometry override, mirroring the classpath {@code block_geometry.json}. */
    private static final @NotNull String GEOMETRY_PATH = "renderer/block_geometry.json";

    /** Pack-root path of the default-state override, mirroring the classpath {@code block_defaults.json}. */
    private static final @NotNull String DEFAULTS_PATH = "renderer/block_defaults.json";

    /**
     * The empty override set - the classpath snapshot stands alone. Returned for a vanilla-only stack
     * and used by the no-override reader overloads to keep their output byte-identical.
     */
    public static final @NotNull BlockRendererOverrides EMPTY =
        new BlockRendererOverrides(JsonTree.object(), JsonTree.object(), JsonTree.object());

    /**
     * Gathers the block-entity geometry overrides across the whole pack stack.
     *
     * @param stack the resolved pack stack
     * @param diagnostics the scope envelope warnings are recorded to
     * @return the merged overlay accumulators, {@link #EMPTY} when no pack ships a {@code renderer/*.json}
     * @throws PipelineException if a pack's override file fails format-2 envelope validation
     */
    public static @NotNull BlockRendererOverrides gather(@NotNull PackStack stack, @NotNull Diagnostics diagnostics) {
        JsonTree models = JsonTree.object();
        JsonTree geometries = JsonTree.object();
        JsonTree defaults = JsonTree.object();
        for (ResourcePack pack : stack.ascending()) {
            overlay(pack, MODELS_PATH, "models", models, diagnostics);
            overlay(pack, GEOMETRY_PATH, "geometries", geometries, diagnostics);
            overlay(pack, DEFAULTS_PATH, "blocks", defaults, diagnostics);
        }
        return new BlockRendererOverrides(models, geometries, defaults);
    }

    /**
     * Whether no pack contributed any override entry - the fast path where the readers skip the merge.
     *
     * @return {@code true} when all three accumulators are empty
     */
    public boolean isEmpty() {
        return this.models.size() == 0 && this.geometries.size() == 0 && this.defaults.size() == 0;
    }

    /**
     * Reads one pack's override file (base root first, later overlays winning within the pack),
     * validates it through the format-2 envelope, and overlays its named sub-object's entries onto the
     * accumulator (later pack wins per key). A file whose named sub-object is absent is skipped with a
     * warning rather than treated as an error - a pack may ship any subset of the three files.
     */
    private static void overlay(@NotNull ResourcePack pack, @NotNull String path, @NotNull String key,
                                @NotNull JsonTree accumulator, @NotNull Diagnostics diagnostics) {
        Optional<byte[]> bytes = readAcrossRoots(pack, path);
        if (bytes.isEmpty()) return;

        ResourceDocument document;
        try {
            document = ResourceDocument.open(bytes.get(), diagnostics.child(pack.id() + "/" + path));
        } catch (PipelineException ex) {
            // A deliberate opt-in feature file: reject loudly with pack attribution rather than
            // silently ignoring a broken override the author expects to take effect.
            throw new PipelineException(ex, "Pack '%s' renderer override '%s' failed format-2 envelope validation", pack.id(), path);
        }

        Optional<JsonTree> sub = document.payload().findObject(key);
        if (sub.isEmpty()) {
            System.err.printf("Pack '%s' renderer override '%s' has no '%s' object; ignored%n", pack.id(), path, key);
            return;
        }
        for (Map.Entry<String, JsonTree> entry : sub.get().members().toList())
            accumulator.put(entry.getKey(), entry.getValue().deepCopy());
    }

    /**
     * Reads a pack-root override file across the pack's roots base-first, keeping the last existing
     * copy so an overlay root can supersede the base. Container-agnostic ({@code bytes} works for the
     * materialized directory as well as zip / CATS sources).
     */
    private static @NotNull Optional<byte[]> readAcrossRoots(@NotNull ResourcePack pack, @NotNull String path) {
        byte[] winning = null;
        for (PackRoot root : pack.roots()) {
            Optional<byte[]> candidate = pack.container().bytes(root.prefix() + path);
            if (candidate.isPresent()) winning = candidate.get();
        }
        return Optional.ofNullable(winning);
    }

}
