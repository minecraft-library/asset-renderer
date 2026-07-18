/**
 * The item-definition dispatch-tree layer - the {@code assets/<ns>/items/*.json} selection trees
 * (26.1+) parsed into immutable id-carrying record nodes at pipeline time and evaluated at render
 * time against a neutral {@link lib.minecraft.renderer.option.ItemModelContext
 * ItemModelContext}. A single resolve pass selects the branch actually rendered.
 *
 * <p><b>Node model.</b> {@link lib.minecraft.renderer.asset.pack.item.ItemModelNode ItemModelNode}
 * is the sealed tree: {@code Model} leaves, the {@code Condition}/{@code Select}/{@code RangeDispatch}
 * dispatch nodes, {@code Composite} concatenation, {@code Special} (block-entity / hardcoded render
 * kinds carrying a {@link lib.minecraft.renderer.asset.pack.item.SpecialTransform SpecialTransform}),
 * and the {@code Bundle} slot marker.
 * {@link lib.minecraft.renderer.pipeline.pack.item.ItemModelParser ItemModelParser} builds the tree
 * (depth-capped against pathological pack nesting) and
 * {@link lib.minecraft.renderer.asset.pack.item.ItemModelNode ItemModelNode}'s resolve pass evaluates it,
 * taking the fallback / {@code on_false} / no-case-match branch for any unknown or unevaluable
 * property - which is the Catharsis degradation contract.
 *
 * <p><b>Resolution.</b> With the neutral {@code gui} context every vanilla item resolves to its
 * fallback branch (bow &rarr; {@code item/bow}, leather_boots &rarr; fallback + dye), giving the
 * derived block-item projection and tint list; items with no same-named {@code models/item/*.json}
 * (clock, compass) gain an index entry. Caller-suppliable non-neutral contexts (trim material, dye,
 * time) are opt-in and re-evaluate the same tree at render time.
 */
package lib.minecraft.renderer.pipeline.pack.item;
