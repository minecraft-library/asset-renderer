package lib.minecraft.renderer.pipeline.pack.item;

import lib.minecraft.renderer.asset.Item.LayerTint;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Evaluates a parsed {@link ItemModelNode} tree against an {@link ItemModelContext}, resolving the
 * single branch that renders. One structural pass: a {@code condition} takes the
 * branch its boolean property selects (unknown &rarr; {@code on_false}); a {@code select} takes the
 * first case whose key matches the context (no match or unevaluable property &rarr; {@code fallback});
 * a {@code range_dispatch} takes the highest threshold {@code <=} the scaled value (none &rarr;
 * {@code fallback}); a {@code composite} takes its first non-empty child (concatenation of multiple
 * leaves is deferred); a {@code model} / {@code special} is a leaf.
 *
 * <p>The neutral {@link ItemModelContext#gui()} context resolves every vanilla tree to its fallback
 * branch, giving the derived model id and tint list.
 */
@UtilityClass
public class ItemModelWalker {

    /**
     * Resolves a tree against a context.
     *
     * @param tree the parsed item-definition tree
     * @param context the evaluation context
     * @return the resolved branch
     */
    public static @NotNull Resolution resolve(@NotNull ItemModelTree tree, @NotNull ItemModelContext context) {
        return resolve(tree.root(), context);
    }

    /**
     * Resolves a node against a context.
     *
     * @param node the node to evaluate
     * @param context the evaluation context
     * @return the resolved branch, {@link Resolution#NOTHING} when the branch renders nothing
     */
    public static @NotNull Resolution resolve(@NotNull ItemModelNode node, @NotNull ItemModelContext context) {
        return switch (node) {
            case ItemModelNode.Model model -> new Resolution(Optional.of(model.model()), model.tints(), Optional.empty());
            case ItemModelNode.Condition condition ->
                resolve(context.conditionValue(condition.property()) ? condition.onTrue() : condition.onFalse(), context);
            case ItemModelNode.Select select -> resolveSelect(select, context);
            case ItemModelNode.RangeDispatch range -> resolveRange(range, context);
            case ItemModelNode.Composite composite -> resolveComposite(composite, context);
            case ItemModelNode.Special special -> new Resolution(Optional.empty(), List.of(), Optional.of(special));
            case ItemModelNode.Bundle ignored -> Resolution.NOTHING;
            case ItemModelNode.Empty ignored -> Resolution.NOTHING;
        };
    }

    private static @NotNull Resolution resolveSelect(@NotNull ItemModelNode.Select select, @NotNull ItemModelContext context) {
        Optional<String> key = context.selectValue(select.property());
        if (key.isPresent()) {
            for (ItemModelNode.Select.Case option : select.cases())
                if (option.when().contains(key.get())) return resolve(option.model(), context);
        }
        return resolve(select.fallback(), context);
    }

    private static @NotNull Resolution resolveRange(@NotNull ItemModelNode.RangeDispatch range, @NotNull ItemModelContext context) {
        float scaled = range.scale() * context.rangeValue(range.property());
        ItemModelNode.RangeDispatch.Entry best = null;
        for (ItemModelNode.RangeDispatch.Entry entry : range.entries())
            if (entry.threshold() <= scaled && (best == null || entry.threshold() > best.threshold())) best = entry;
        return best != null ? resolve(best.model(), context) : resolve(range.fallback(), context);
    }

    private static @NotNull Resolution resolveComposite(@NotNull ItemModelNode.Composite composite, @NotNull ItemModelContext context) {
        for (ItemModelNode child : composite.models()) {
            Resolution resolution = resolve(child, context);
            if (!resolution.isEmpty()) return resolution;
        }
        return Resolution.NOTHING;
    }

    /**
     * The resolved branch: the primary model leaf id (if the branch is a plain model), the per-layer
     * tints from that branch, and the special leaf (if the branch is a hardcoded-render kind). At most
     * one of {@link #modelId()} / {@link #special()} is present; both empty means the branch renders
     * nothing.
     *
     * @param modelId the resolved plain-model id, or empty for a special / nothing branch
     * @param tints the per-layer tints from the resolved model branch, empty when untinted
     * @param special the resolved special leaf, or empty for a plain-model / nothing branch
     */
    public record Resolution(
        @NotNull Optional<String> modelId,
        @NotNull List<LayerTint> tints,
        @NotNull Optional<ItemModelNode.Special> special
    ) {

        /** The empty resolution - a branch that renders nothing. */
        public static final @NotNull Resolution NOTHING = new Resolution(Optional.empty(), List.of(), Optional.empty());

        /**
         * Whether this resolution renders nothing (neither a model nor a special leaf).
         *
         * @return whether both the model and special leaves are absent
         */
        public boolean isEmpty() {
            return this.modelId.isEmpty() && this.special.isEmpty();
        }

    }

}
