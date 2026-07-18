/**
 * The pack-model components {@link lib.minecraft.renderer.asset.PackStack PackStack} caches - one
 * logical resource pack's identity, byte containers, and parsed metadata. Pipeline-built at
 * acquisition, render-consumed through the stack.
 *
 * <p>{@link lib.minecraft.renderer.asset.pack.PackId PackId} is the normalized pack-addressed
 * identity (an {@link lib.minecraft.renderer.asset.ResourceId ResourceId} sibling);
 * {@link lib.minecraft.renderer.asset.pack.ResourcePack ResourcePack} binds an id to its
 * {@link lib.minecraft.renderer.asset.pack.PackContainer PackContainer} (read-only byte access - an
 * exploded {@code Directory}, a plain {@code Zip}, or a Catharsis {@code Cats} archive decoded by
 * {@link lib.minecraft.renderer.asset.pack.CatsIndex CatsIndex}), its active
 * {@link lib.minecraft.renderer.asset.pack.PackRoot PackRoot} roots, namespaces, and
 * {@link lib.minecraft.renderer.asset.pack.Capability capabilities}.
 * {@link lib.minecraft.renderer.asset.pack.MCMeta MCMeta} is the umbrella over every {@code .mcmeta}
 * section (pack format, texture animation, GUI scaling), with
 * {@link lib.minecraft.renderer.asset.pack.FormatRange FormatRange} normalizing the three pack-format
 * generations to one inclusive span.
 * {@link lib.minecraft.renderer.asset.pack.ResolvedTexture ResolvedTexture} is the cached texture
 * index row (winning path plus optional {@code MCMeta} sidecar).
 */
package lib.minecraft.renderer.asset.pack;
