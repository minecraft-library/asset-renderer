/**
 * What one resource pack is, decoded - the records
 * {@link lib.minecraft.renderer.content.pack.PackStack PackStack} caches and a render reads through
 * the stack.
 *
 * <p>{@link lib.minecraft.renderer.asset.pack.ResourcePack ResourcePack} is one logical pack: its
 * {@link lib.minecraft.renderer.vanilla.id.PackId PackId}, the
 * {@link lib.minecraft.renderer.asset.pack.PackFiles PackFiles} byte access its container answers
 * (a {@link lib.minecraft.renderer.content.pack.PackContainer PackContainer} - an exploded
 * {@code Directory}, a plain {@code Zip}, or a Catharsis {@code Cats} archive decoded by
 * {@link lib.minecraft.renderer.content.pack.cats.CatsIndex CatsIndex}), its active
 * {@link lib.minecraft.renderer.asset.pack.PackRoot PackRoot} roots, its namespaces and its
 * {@link lib.minecraft.renderer.asset.pack.PackCapability capabilities}.
 *
 * <p>{@link lib.minecraft.renderer.asset.pack.MCMeta MCMeta} is the umbrella over every
 * {@code .mcmeta} section - the pack root's and the four a texture sidecar may combine - with
 * {@link lib.minecraft.renderer.asset.pack.FormatRange FormatRange} normalizing the three pack-format
 * generations to one inclusive span and {@link lib.minecraft.renderer.asset.pack.Flipbook Flipbook}
 * resolving an animation section against the strip it plays over.
 * {@link lib.minecraft.renderer.asset.pack.PalettedPermutationSource PalettedPermutationSource} is one
 * atlas source list's permutation entry, what a sprite no pack ships as a PNG is synthesised from.
 */
package lib.minecraft.renderer.asset.pack;
