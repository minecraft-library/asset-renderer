package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code inventory.transform} contributor: drives {@link TransformWalker} on the split's
 * renderer at its GUI transform-building entry point and emits the decomposed float tuple. The
 * three raw-pose renderers (chest / bell / copper_golem_statue) have no such entry point and emit
 * no transform; the standing sign seeds its attachment so {@code sign} and {@code wall_sign}
 * decompose distinctly.
 *
 * <p>Poison-on-unknown emits nothing rather than garbage. Results are memoised per (renderer,
 * attachment) so the family's shared splits decompose once.
 */
final class InventoryTransformResolver {

    private final @NotNull TransformWalker walker;

    /**
     * (renderer + '|' + attachment) -> decomposed tuple, filled by {@link Map#computeIfAbsent},
     * which records no mapping when the walk yields nothing - so an absent key means "not yet
     * computed" and never "no transform", and a renderer the walker declines re-runs the whole
     * symbolic execution once per split that asks.
     */
    private final @NotNull Map<String, float[]> memo = new HashMap<>();

    InventoryTransformResolver(@NotNull ClassNodeCache cache) {
        this.walker = new TransformWalker(cache);
    }

    /**
     * The split's {@code inventory.transform} array, or {@code null} when the renderer builds no
     * GUI transform or the walker cannot reduce it.
     *
     * @param rendererClass the subject renderer's JVM internal name
     * @param splitId the models key
     * @return the transform float array node, or {@code null}
     */
    @Nullable JsonTree resolve(@NotNull String rendererClass, @NotNull String splitId) {
        String entry = BlockTransformPolicies.rendererEntry(rendererClass);
        if (entry == null) return null;

        String attachment = BlockTransformPolicies.signAttachment(splitId);
        String key = rendererClass + "|" + attachment;
        float[] tuple = this.memo.computeIfAbsent(key, ignored -> this.walker.decompose(rendererClass, entry, attachment));
        if (tuple == null) return null;
        JsonTree array = JsonTree.array();
        for (float value : tuple) array.add(value);
        return array;
    }

}
