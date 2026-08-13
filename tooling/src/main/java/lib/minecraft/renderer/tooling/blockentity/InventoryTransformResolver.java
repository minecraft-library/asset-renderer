package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
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

    /** The session every per-renderer policy consultation is framed on. */
    private final @NotNull ToolingSession session;

    InventoryTransformResolver(@NotNull ToolingSession session) {
        this.session = session;
        this.walker = new TransformWalker(session.cache());
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
        // the renderer in play is the anchor class, which is the dimension this row is keyed on;
        // a renderer the roster names no entry for answers None, which is "no GUI transform"
        Navigation navigation = BlockTransformPolicies.RENDERER_ENTRY_METHODS.navigate(
            AsmContext.keyless(this.session, rendererClass, this.session.diagnostics()));
        if (navigation instanceof Navigation.None) return null;
        if (!(navigation instanceof Navigation.At entry))
            throw new ToolingException("Policy '%s.%s' answers '%s' where a bytecode coordinate was required",
                BlockTransformPolicies.class.getSimpleName(), BlockTransformPolicies.RENDERER_ENTRY_METHODS,
                navigation.getClass().getSimpleName());

        String attachment = BlockTransformPolicies.signAttachment(splitId);
        String key = rendererClass + "|" + attachment;
        float[] tuple = this.memo.computeIfAbsent(key, ignored -> this.walker.decompose(entry, attachment));
        if (tuple == null) return null;
        JsonTree array = JsonTree.array();
        for (float value : tuple) array.add(value);
        return array;
    }

}
