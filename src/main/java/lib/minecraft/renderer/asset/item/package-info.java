/**
 * The item model dispatch tree as parsed - the {@code assets/<ns>/items/*.json} selection trees
 * (26.1+) as immutable record nodes, render-walked to the branch that draws.
 *
 * <p>{@link lib.minecraft.renderer.asset.item.ItemModelTree ItemModelTree} pairs an item id with
 * the root {@link lib.minecraft.renderer.asset.item.ItemModelNode ItemModelNode} - the sealed
 * tree of {@code Model} leaves, {@code Condition} / {@code Select} / {@code RangeDispatch} dispatch
 * nodes, {@code Composite} concatenation, a {@code Special} (block-entity / hardcoded render kind
 * carrying a {@link lib.minecraft.renderer.asset.item.ItemModelNode.SpecialTransform SpecialTransform}), and
 * the {@code Bundle} / {@code Empty} sentinels.
 * {@link lib.minecraft.renderer.asset.item.ItemModelNode#resolve(lib.minecraft.renderer.request.ItemModelContext)
 * ItemModelNode.resolve} walks the single branch a caller context selects; the neutral {@code gui}
 * context resolves every vanilla tree to its fallback. The trees are read by
 * {@link lib.minecraft.renderer.content.pack.ItemModelTreeLoader ItemModelTreeLoader} through
 * {@link lib.minecraft.renderer.content.json.ItemModelNodeDeserializer ItemModelNodeDeserializer}.
 *
 * <p>A type that is neither the tree nor one of its nodes does not belong here, which is why the
 * context a walk reads is not: it is
 * {@link lib.minecraft.renderer.request.ItemModelContext ItemModelContext}, a value the caller supplies
 * with its item options. {@link lib.minecraft.renderer.vanilla.SunAngle SunAngle} is the vanilla day
 * curve behind its {@code minecraft:time} input - the eased sun angle a clock face dispatches on,
 * which is not a linear day fraction.
 */
package lib.minecraft.renderer.asset.item;
