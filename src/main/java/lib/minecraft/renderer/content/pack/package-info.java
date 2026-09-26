/**
 * Resolving the caller's pack stack into one readable, cached whole - vanilla at the bottom and every
 * user pack above it, higher winning, read through one byte-access handle per pack.
 *
 * <p>{@link lib.minecraft.renderer.content.pack.PackAcquisition PackAcquisition} is the entry point: it
 * detects each source by content as a
 * {@link lib.minecraft.renderer.content.pack.PackContainer PackContainer} - an exploded directory, a
 * zip or a Catharsis archive, all read the same way - derives a stable id for it through
 * {@link lib.minecraft.renderer.content.pack.PackIdDeriver PackIdDeriver}, resolves its overlay roots,
 * and assembles the {@link lib.minecraft.renderer.content.pack.PackStack PackStack} every other member
 * reads through. {@link lib.minecraft.renderer.content.pack.MCMetaParser MCMetaParser} reads a pack's
 * root {@code pack.mcmeta} and every per-texture sidecar, and
 * {@link lib.minecraft.renderer.content.pack.TextureIndexer TextureIndexer} indexes the textures the
 * stack serves. The Catharsis format's own grammar and index are
 * {@link lib.minecraft.renderer.content.pack.cats}.
 *
 * <p>The rest read one resource family each off the resolved stack.
 * {@link lib.minecraft.renderer.content.pack.ResolvedModels ResolvedModels} folds the block and item
 * model parent chains. {@link lib.minecraft.renderer.content.pack.BlockStateLoader BlockStateLoader},
 * {@link lib.minecraft.renderer.content.pack.BlockTagLoader BlockTagLoader},
 * {@link lib.minecraft.renderer.content.pack.BannerPatternLoader BannerPatternLoader},
 * {@link lib.minecraft.renderer.content.pack.ColorMapLoader ColorMapLoader},
 * {@link lib.minecraft.renderer.content.pack.EquipmentModelLoader EquipmentModelLoader} and
 * {@link lib.minecraft.renderer.content.pack.PalettedPermutationLoader PalettedPermutationLoader} each
 * read the files of one kind across the stack ascending.
 * {@link lib.minecraft.renderer.content.pack.ItemModelTreeLoader ItemModelTreeLoader} merges every
 * pack's {@code items/*.json} dispatch trees, each pack's {@code filter.block} erasing the lower ids it
 * hides, and {@link lib.minecraft.renderer.content.pack.LegacyOverrideMapper LegacyOverrideMapper} maps
 * a pre-format-46 pack's {@code models/item} {@code overrides} array onto the same node vocabulary, so a
 * legacy pack resolves like a native items file.
 * {@link lib.minecraft.renderer.content.pack.BlockModelLoader BlockModelLoader} reads the block-entity
 * model catalog with the pack override channel laid over it, and
 * {@link lib.minecraft.renderer.content.pack.TextureSynthesizer TextureSynthesizer} generates the
 * paletted-permutation sprites no pack ships, on the miss that asks for one.
 *
 * <p>A type that reads a bundled resource - one of the tables the generator shipped into the JAR -
 * does not belong here; that is {@link lib.minecraft.renderer.content.table}.
 *
 * <p><b>Parity.</b> With no pack loaded the rule set is empty and most of the id deriver never
 * executes, so a vanilla-only dump is a fixed empty shape whatever this code does - only the packs
 * configuration puts a rule through it at all. A loader here is not rule code and reaches both
 * dumps, and the colormap digests are taken over exactly what the colormap loader hands back.
 */
@Parity(claim = "pack-resolution")
package lib.minecraft.renderer.content.pack;

import lib.minecraft.renderer.parity.Parity;
