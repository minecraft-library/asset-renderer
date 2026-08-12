package lib.minecraft.renderer.asset.pack.item;

import lib.minecraft.renderer.pipeline.dump.PipelineParityDump;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Package-private reach-through to the two item-node payloads that carry no public type, for the
 * parity dump.
 * <p>
 * {@code Select} and {@code RangeDispatch} are members of a public interface, so they are implicitly
 * public - but {@code Select.Case} and {@code RangeDispatch.Entry} nest one level deeper, inside
 * records, where the implicit-public rule does not reach. They default to package-private, and a
 * serializer in another package cannot name their type or call their accessors.
 * <p>
 * This bridge lives in the test source set, in their package, and hands their contents back as public
 * types. Widening the records themselves would be the tidier fix and provably moves no pixel; this
 * workaround is what lets the dump read them without touching production source.
 *
 * @see PipelineParityDump
 */
public final class ItemNodeAccess {

    private ItemNodeAccess() {}

    /**
     * Returns a select node's cases as their two public halves, in list order.
     *
     * @param select the node whose cases to read
     * @return the case keys and branch model of each case, in order
     */
    public static @NotNull List<Case> cases(ItemModelNode.@NotNull Select select) {
        return select.cases().stream().map(entry -> new Case(entry.when(), entry.model())).toList();
    }

    /**
     * Returns a range-dispatch node's entries as their two public halves, in list order.
     *
     * @param dispatch the node whose entries to read
     * @return the threshold and branch model of each entry, in order
     */
    public static @NotNull List<Entry> entries(ItemModelNode.@NotNull RangeDispatch dispatch) {
        return dispatch.entries().stream().map(entry -> new Entry(entry.threshold(), entry.model())).toList();
    }

    /**
     * One select case, in publicly-nameable form.
     *
     * @param when the case keys this branch matches
     * @param model the branch model
     */
    public record Case(@NotNull List<String> when, @NotNull ItemModelNode model) {}

    /**
     * One range-dispatch entry, in publicly-nameable form.
     *
     * @param threshold the lower bound (inclusive) on the scaled property value
     * @param model the branch model
     */
    public record Entry(float threshold, @NotNull ItemModelNode model) {}

}
