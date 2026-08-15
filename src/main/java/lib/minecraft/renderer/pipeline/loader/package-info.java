/**
 * The readers for the tables the tooling emits, one resource family each.
 *
 * <p>Every table under {@code lib/minecraft/renderer/} is walked out of the vanilla client by a
 * generator and shipped as JSON; these are what read it back. A loader named for its table -
 * {@link lib.minecraft.renderer.pipeline.loader.BlockDefaultsLoader BlockDefaultsLoader},
 * {@link lib.minecraft.renderer.pipeline.loader.BlockItemsLoader BlockItemsLoader},
 * {@link lib.minecraft.renderer.pipeline.loader.BlockTintsLoader BlockTintsLoader},
 * {@link lib.minecraft.renderer.pipeline.loader.GlintItemsLoader GlintItemsLoader},
 * {@link lib.minecraft.renderer.pipeline.loader.PotionColorLoader PotionColorLoader} - is a single
 * {@code document.as(...)} read and nothing else.
 *
 * <p>The two model families are three types rather than one, because a model's shape and its
 * geometry are separate tables that have to be joined:
 * {@link lib.minecraft.renderer.pipeline.loader.BlockModelReader BlockModelReader} and
 * {@link lib.minecraft.renderer.pipeline.loader.BlockGeometryReader BlockGeometryReader} are the two
 * pure reads, {@link lib.minecraft.renderer.pipeline.loader.BlockEntityAssembler BlockEntityAssembler}
 * is the join, and
 * {@link lib.minecraft.renderer.pipeline.loader.BlockModelLoader BlockModelLoader} is the thin
 * orchestrator over all three. {@link lib.minecraft.renderer.pipeline.loader.EntityModelLoader
 * EntityModelLoader} is the same shape for entities, handing its two reads to the index builder that
 * owns the geometry join.
 *
 * <p><b>Parity.</b> A dump taken at the pipeline result would serialise what these hand back, and the
 * index builders that run between here and the renderer context could then be broken without moving a
 * dumped byte. It is taken after them instead, which is what makes a change here visible - and two of
 * these loaders decide a corpus count outright, because each count is the size of what its loader
 * returns.
 *
 * @see lib.minecraft.renderer.pipeline.index
 * @see lib.minecraft.renderer.pipeline.PipelineRendererContext
 */
@Parity(claim = "index-and-loader")
package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.parity.Parity;
