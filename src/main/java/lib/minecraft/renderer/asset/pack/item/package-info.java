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
 * {@link lib.minecraft.renderer.asset.pack.item.ItemModelNode#resolve(lib.minecraft.renderer.asset.pack.item.ItemModelContext)
 * ItemModelNode.resolve} walks the single branch a caller context selects; the neutral {@code gui}
 * context resolves every vanilla tree to its fallback. The pipeline-side parser / loader that build
 * these stay in {@link lib.minecraft.renderer.pipeline.pack.item pipeline.pack.item}.
 *
 * <p>That context is {@link lib.minecraft.renderer.asset.pack.item.ItemModelContext ItemModelContext},
 * held here rather than with the options bag that carries one because the nodes evaluate against it
 * and nothing else does. {@link lib.minecraft.renderer.asset.pack.item.SunAngle SunAngle} is the
 * vanilla day curve behind its {@code minecraft:time} input - the eased sun angle a clock face
 * dispatches on, which is not a linear day fraction.
 */
package lib.minecraft.renderer.asset.pack.item;
