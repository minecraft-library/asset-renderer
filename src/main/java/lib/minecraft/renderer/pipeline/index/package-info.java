/**
 * The renderer-index assembly layer - the cross-loader joins that fold the parsed asset tables into
 * the finished {@code blockId -> }{@link lib.minecraft.renderer.asset.Block Block} and
 * {@code itemId -> }{@link lib.minecraft.renderer.asset.Item Item} maps the renderer context wraps.
 *
 * <p>{@link lib.minecraft.renderer.pipeline.index.BlockIndexBuilder} and
 * {@link lib.minecraft.renderer.pipeline.index.ItemIndexBuilder} are not loaders - they consume the
 * already-loaded DTO tables (models, tints, variants, tags, block-entity geometry, item trees) and
 * assemble them into the runtime index, dropping the parent / template models that render nothing.
 * They are the layer the loader-side pivots and bakes move onto, keeping every JSON loader a pure
 * {@code document.as(...)} read.
 *
 * <p><b>Parity.</b> These builders run between the loaders and the renderer context, so a dump
 * taken before them would serialise inputs that are identical whatever the builders did with
 * them. It is taken after instead, which is what makes a change here visible at all.
 *
 * @see lib.minecraft.renderer.pipeline.PipelineRendererContext
 */
@Parity(claim = "index-and-loader")
package lib.minecraft.renderer.pipeline.index;

import lib.minecraft.renderer.parity.Parity;
