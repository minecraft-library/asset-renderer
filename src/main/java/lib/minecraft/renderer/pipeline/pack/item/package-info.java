/**
 * The item-definition dispatch-tree layer - the {@code assets/<ns>/items/*.json} selection trees
 * (26.1+) parsed into immutable id-carrying record nodes at pipeline time and evaluated at render
 * time against a neutral {@link lib.minecraft.renderer.pipeline.pack.item.ItemModelContext
 * ItemModelContext} (05-models.md §3). Supersedes the former {@code ItemDefinitionLoader}'s two
 * partial projections (root block-model + fallback-descent tints) with a single walker that resolves
 * the branch actually rendered.
 *
 * <p><b>Node model.</b> {@link lib.minecraft.renderer.pipeline.pack.item.ItemModelNode ItemModelNode}
 * is the sealed tree: {@code Model} leaves, the {@code Condition}/{@code Select}/{@code RangeDispatch}
 * dispatch nodes, {@code Composite} concatenation, {@code Special} (block-entity / hardcoded render
 * kinds carrying a {@link lib.minecraft.renderer.pipeline.pack.item.SpecialTransform SpecialTransform}),
 * and the {@code Bundle} slot marker.
 * {@link lib.minecraft.renderer.pipeline.pack.item.ItemModelParser ItemModelParser} builds the tree
 * (depth-capped against pathological pack nesting);
 * {@link lib.minecraft.renderer.pipeline.pack.item.ItemModelWalker ItemModelWalker} evaluates it,
 * taking the fallback / {@code on_false} / no-case-match branch for any unknown or unevaluable
 * property - which is the Catharsis degradation contract (03-rules.md §6.2).
 *
 * <p><b>Parity.</b> With the neutral {@code gui} context every vanilla item resolves to the same
 * branch the former loader's fallback-descent reached (bow &rarr; {@code item/bow}, leather_boots
 * &rarr; fallback + dye), so the derived block-item projection and tint list are byte-identical; the
 * only new output is items with no same-named {@code models/item/*.json} (clock, compass), which gain
 * an index entry. Caller-suppliable non-neutral contexts (trim material, dye, time) are opt-in and
 * re-evaluate the same tree at render time.
 */
package lib.minecraft.renderer.pipeline.pack.item;
