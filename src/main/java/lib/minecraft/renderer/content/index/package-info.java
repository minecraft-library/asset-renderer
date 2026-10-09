/**
 * The renderer-index assembly layer - the cross-loader joins that fold the parsed asset tables into
 * the finished {@code blockId -> }{@link lib.minecraft.renderer.asset.Block Block} and
 * {@code itemId -> }{@link lib.minecraft.renderer.asset.Item Item} maps the renderer context wraps.
 *
 * <p>{@link lib.minecraft.renderer.content.index.BlockIndexBuilder} and
 * {@link lib.minecraft.renderer.content.index.ItemIndexBuilder} are not loaders - they consume the
 * already-loaded DTO tables (models, tints, variants, tags, block-entity geometry, item trees) and
 * assemble them into the runtime index, dropping the parent / template models that render nothing.
 * They are the layer the loader-side pivots and bakes move onto, keeping every JSON loader a pure
 * {@code document.as(...)} read. The production context answers the finished ids grouped, related
 * subjects next to each other.
 *
 * <p>The joined records are read through {@link lib.minecraft.renderer.content.index.RendererContext
 * RendererContext}, the lookup surface every renderer takes, which sits here beside
 * {@link lib.minecraft.renderer.content.index.IndexedRendererContext IndexedRendererContext}, the
 * production implementation. {@link lib.minecraft.renderer.content.index.RendererContext#load
 * RendererContext.load} builds one from the client assets, and
 * {@link lib.minecraft.renderer.content.index.RendererContext#builder() RendererContext.builder} an
 * in-memory one from maps. The answer a lookup hands back that is its own -
 * {@link lib.minecraft.renderer.content.index.CitResult CitResult} with its
 * {@link lib.minecraft.renderer.content.index.GlintPolicy GlintPolicy} - sits beside it, and so do
 * {@link lib.minecraft.renderer.content.index.CtmContext CtmContext}, the per-face query the
 * connected-texture lookup builds and the CTM matcher reads, and
 * {@link lib.minecraft.renderer.content.index.SubstitutionCollector SubstitutionCollector}, the
 * stand-ins one render has drawn, which a context derived by
 * {@link lib.minecraft.renderer.content.index.RendererContext#collecting RendererContext.collecting}
 * carries.
 *
 * <p>The two model loaders each drive the table reads below this package and the join here.
 * {@link lib.minecraft.renderer.content.index.BlockModelLoader BlockModelLoader} drives the
 * block-entity join: it runs the two block-entity table reads with a pack stack's
 * {@code renderer/*.json} override channel laid over them, hands both to
 * {@link lib.minecraft.renderer.content.index.BlockEntityAssembler BlockEntityAssembler}, and leaves the
 * probe of that stack for shadowed models to
 * {@link lib.minecraft.renderer.content.pack.BlockEntityShadows BlockEntityShadows}.
 * {@link lib.minecraft.renderer.content.index.EntityModelLoader EntityModelLoader} drives the entity
 * join: it hands the three tables {@link lib.minecraft.renderer.content.table.EntityTables EntityTables}
 * reads to {@link lib.minecraft.renderer.content.index.EntityIndexBuilder EntityIndexBuilder} and holds
 * the joined map every caller shares.
 *
 * <p><b>Parity.</b> These builders run between the loaders and the renderer context, so a dump
 * taken before them would serialise inputs that are identical whatever the builders did with
 * them. It is taken after instead, which is what makes a change here visible at all.
 *
 * @see lib.minecraft.renderer.content.index.IndexedRendererContext
 */
@Parity(claim = "index-and-loader")
package lib.minecraft.renderer.content.index;

import lib.minecraft.renderer.parity.Parity;
