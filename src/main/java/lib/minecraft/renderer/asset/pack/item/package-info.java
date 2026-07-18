/**
 * The item-definition dispatch-tree DTOs - the {@code assets/<ns>/items/*.json} selection trees
 * (26.1+) as immutable record nodes, render-walked to the branch that draws.
 *
 * <p>{@link lib.minecraft.renderer.asset.pack.item.ItemModelTree ItemModelTree} pairs an item id with
 * the root {@link lib.minecraft.renderer.asset.pack.item.ItemModelNode ItemModelNode} - the sealed
 * tree of {@code Model} leaves, {@code Condition} / {@code Select} / {@code RangeDispatch} dispatch
 * nodes, {@code Composite} concatenation, a {@code Special} (block-entity / hardcoded render kind
 * carrying a {@link lib.minecraft.renderer.asset.pack.item.SpecialTransform SpecialTransform}), and
 * the {@code Bundle} / {@code Empty} sentinels.
 * {@link lib.minecraft.renderer.asset.pack.item.ItemModelNode#resolve(lib.minecraft.renderer.option.ItemModelContext)
 * ItemModelNode.resolve} walks the single branch a caller context selects; the neutral {@code gui}
 * context resolves every vanilla tree to its fallback. The pipeline-side parser / loader that build
 * these stay in {@link lib.minecraft.renderer.pipeline.pack.item pipeline.pack.item}.
 */
package lib.minecraft.renderer.asset.pack.item;
