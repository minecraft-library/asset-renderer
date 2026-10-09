/**
 * The records one run decodes - the domain definitions the
 * {@link lib.minecraft.renderer.content content} loaders build out of the vanilla client and the
 * caller's packs, and a {@link lib.minecraft.renderer.content.index.RendererContext RendererContext} hands a
 * renderer.
 *
 * <p>At this level sit the four a renderer or the context holds directly.
 * {@link lib.minecraft.renderer.asset.Block Block} is a parsed block: its resolved model, texture
 * bindings, blockstate variants or multipart, tags, tint binding, block-entity override and default
 * state. {@link lib.minecraft.renderer.asset.Item Item} is a parsed item: its resolved model, texture
 * bindings, durability, per-layer tints and intrinsic glint.
 * {@link lib.minecraft.renderer.asset.Entity Entity} is a parsed entity: its bone tree, texture
 * reference, overlays, the appearance axes and layers a render selects among, its style catalog and its
 * pose. {@link lib.minecraft.renderer.asset.ColorMap ColorMap} is one 256x256 biome colormap as raw ARGB
 * pixels.
 *
 * <p>The packages under this one hold the rest of what a decode produces, each under a charter of its
 * own:
 * <ul>
 *   <li>{@link lib.minecraft.renderer.asset.model model} - the block and item model schema as parsed,
 *       the model / element / face / transform types {@code Block} and {@code Item} both hold.</li>
 *   <li>{@link lib.minecraft.renderer.asset.mesh mesh} - an entity's bone tree as parsed.</li>
 *   <li>{@link lib.minecraft.renderer.asset.pack pack} - descriptors of the packs a run resolved,
 *       holding no file handle ({@code ResourcePack}, {@code MCMeta}, {@code PackRoot},
 *       {@code PackCapability}, {@code FormatRange}, ...).</li>
 *   <li>{@link lib.minecraft.renderer.asset.item item} - the {@code items/*.json} dispatch trees
 *       ({@code ItemModelTree} / {@code ItemModelNode}).</li>
 *   <li>{@link lib.minecraft.renderer.asset.rule rule} - the OptiFine CIT / CTM rule family and
 *       {@code color.properties}, with the value predicates its rules match with in
 *       {@link lib.minecraft.renderer.asset.rule.filter rule.filter}.</li>
 *   <li>{@link lib.minecraft.renderer.asset.equipment equipment} - what a wearer is dressed in: the
 *       {@code equipment/*.json} model a pack declares ({@code EquipmentModel}) and the worn shell an
 *       entity carries ({@code Shell}).</li>
 *   <li>{@link lib.minecraft.renderer.asset.pose pose} - the pose rows a run reads off the shipped
 *       table.</li>
 * </ul>
 *
 * <p>A type no decode produces does not belong here, and neither does one that names a stage. A
 * caller's request is not asset data: the {@code *Options} bags a renderer takes live in
 * {@link lib.minecraft.renderer.call.request request}, and a pure math primitive lives in
 * {@link lib.minecraft.renderer.engine.math engine.math}. Nor is a fact true before any run starts: a dye palette, an
 * armor slot, an appearance axis is vanilla vocabulary whichever side supplies it, held in
 * {@link lib.minecraft.renderer.vanilla vanilla}, and both a request and a decoded record point at it.
 *
 * <p><b>Parity.</b> These are the records the content layer builds and the renderers consume, and the
 * dump's sections are a projection of exactly those records - so a change here is visible on both
 * sides at once. That leaves this package family no blindness to claim.
 */
@Parity(claim = "asset-layer")
package lib.minecraft.renderer.asset;

import lib.minecraft.renderer.parity.Parity;
