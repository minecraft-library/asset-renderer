/**
 * The pack-model layer: identity, containers, and parsed metadata for one logical resource pack.
 *
 * <p>{@link lib.minecraft.renderer.asset.pack.PackId PackId} is the normalized pack-addressed
 * identity, derived from a pack's naming inputs by
 * {@link lib.minecraft.renderer.pipeline.pack.PackIdDeriver PackIdDeriver} (a four-rung ladder plus
 * loud letter-ordinal collision suffixes).
 * {@link lib.minecraft.renderer.asset.pack.PackContainer PackContainer} is the read-only byte
 * access - an exploded {@code Directory}, a plain {@code Zip}, or a Catharsis {@code Cats} archive
 * decoded by {@link lib.minecraft.renderer.asset.pack.CatsIndex CatsIndex} - detected by content,
 * orthogonal to content capability. {@link lib.minecraft.renderer.pipeline.pack.MCMeta MCMeta} is the
 * umbrella over every {@code .mcmeta} section, with
 * {@link lib.minecraft.renderer.asset.pack.FormatRange FormatRange} normalizing the three
 * pack-format generations to one inclusive span.
 */
package lib.minecraft.renderer.pipeline.pack;
