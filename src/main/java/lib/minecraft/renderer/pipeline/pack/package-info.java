/**
 * The pack acquisition layer - builds the {@link lib.minecraft.renderer.asset.PackStack PackStack}
 * and its {@link lib.minecraft.renderer.asset.pack pack-model} components from vanilla plus user
 * packs, then loads the per-pack domain resources. Pipeline-only; nothing here is held past context
 * build.
 *
 * <p>{@link lib.minecraft.renderer.pipeline.pack.PackAcquisition PackAcquisition} is the entry point -
 * detect each container by content, derive stable ids via
 * {@link lib.minecraft.renderer.pipeline.pack.PackIdDeriver PackIdDeriver}, resolve overlay roots
 * (including the {@code Catharsis*} {@code fabric:overlays} activation), assemble the stack, scan the
 * texture index ({@link lib.minecraft.renderer.pipeline.pack.TextureIndexer TextureIndexer}), and
 * merge the rule layer. {@link lib.minecraft.renderer.pipeline.pack.ResolvedModels ResolvedModels}
 * folds the block / entity model parent chains; the per-domain loaders
 * ({@code BannerPatternLoader}, {@code BlockStateLoader}, {@code BlockTagLoader},
 * {@code ColorMapLoader}, {@code PalettedPermutationLoader}) read one resource family each.
 * {@link lib.minecraft.renderer.client.VanillaSourcePaths VanillaSourcePaths} centralizes the
 * vanilla asset-subdirectory templates.
 *
 * <p><b>Parity.</b> With no pack loaded the rule set is empty and most of the id deriver never
 * executes, so a vanilla-only dump is a fixed empty shape whatever this code does - only the packs
 * configuration puts a rule through it at all. A loader here is not rule code and reaches both
 * dumps, and the colormap digests are taken over exactly what the colormap loader hands back.
 */
@Parity(claim = "pack-resolution")
package lib.minecraft.renderer.pipeline.pack;

import lib.minecraft.renderer.parity.Parity;

