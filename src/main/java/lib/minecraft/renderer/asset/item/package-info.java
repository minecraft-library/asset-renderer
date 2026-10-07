/**
 * The item model dispatch tree as parsed - the {@code assets/<ns>/items/*.json} selection trees
 * (26.1+) as immutable record nodes, render-walked to the branch that draws.
 *
 * <p>{@link lib.minecraft.renderer.asset.item.ItemModelTree ItemModelTree} pairs an item id with
 * the root {@link lib.minecraft.renderer.asset.item.ItemModelNode ItemModelNode} - the sealed
 * tree of {@code Model} leaves, {@code Condition} / {@code Select} / {@code RangeDispatch} dispatch
 * nodes, {@code Composite} concatenation, a {@code Special} (block-entity / hardcoded render kind
 * carrying a {@link lib.minecraft.renderer.asset.item.ItemModelNode.SpecialTransform SpecialTransform}), the
 * {@code Bundle} / {@code Empty} nodes and the {@code Absent} sentinel for a fallback a definition does
 * not declare. The component tests a tree carries are decoded onto it at load, as vanilla decodes
 * them: a component condition's
 * {@link lib.minecraft.renderer.asset.item.ItemModelNode.ComponentPredicate ComponentPredicate}, and a
 * component select's case values as the keys
 * {@link lib.minecraft.renderer.asset.item.ItemModelNode.SelectComponent SelectComponent} reduces them
 * to. A caller context walks a tree to the single branch it selects, through
 * {@link lib.minecraft.renderer.request.ItemModelContext#resolve(lib.minecraft.renderer.asset.item.ItemModelTree)
 * ItemModelContext.resolve}; the neutral {@code gui} context resolves every vanilla tree to its
 * fallback. The trees are read by
 * {@link lib.minecraft.renderer.content.pack.ItemModelTreeLoader ItemModelTreeLoader} through
 * {@link lib.minecraft.renderer.content.json.ItemModelNodeDeserializer ItemModelNodeDeserializer}.
 *
 * <p>A type that is neither the tree nor one of its nodes does not belong here, which is why the
 * context that walks one is not: it is
 * {@link lib.minecraft.renderer.request.ItemModelContext ItemModelContext}, a value the caller supplies
 * with its item options. {@link lib.minecraft.renderer.vanilla.SunAngle SunAngle} is the vanilla day
 * curve behind its {@code minecraft:time} input - the eased sun angle a clock face dispatches on,
 * which is not a linear day fraction.
 */
package lib.minecraft.renderer.asset.item;
