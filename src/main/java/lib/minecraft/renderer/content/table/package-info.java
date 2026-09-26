/**
 * Reading the tables the generator shipped into this JAR, one resource family each.
 *
 * <p>Every table under {@code lib/minecraft/renderer/} is walked out of the vanilla client by a
 * generator and shipped as JSON; these are what read it back. Each read lands in a
 * {@link lib.minecraft.renderer.content.table.ResourceDocument ResourceDocument}, which validates the
 * envelope's {@code format} discriminator and deserialises the payload through {@code as(...)}, and
 * {@link lib.minecraft.renderer.content.table.TableEnvelope TableEnvelope} mints the envelope header a
 * generator writes and that document validates.
 *
 * <p>A loader named for its table -
 * {@link lib.minecraft.renderer.content.table.BlockItemsLoader BlockItemsLoader},
 * {@link lib.minecraft.renderer.content.table.BlockTintsLoader BlockTintsLoader},
 * {@link lib.minecraft.renderer.content.table.GlintItemsLoader GlintItemsLoader},
 * {@link lib.minecraft.renderer.content.table.PotionColorLoader PotionColorLoader} - is a single
 * {@code document.as(...)} read and nothing else.
 * {@link lib.minecraft.renderer.content.table.BlockDefaultsLoader BlockDefaultsLoader} is the same read
 * with a pack's {@code renderer/block_defaults.json} override channel laid over it per block id.
 *
 * <p>The two model families are three types rather than one, because a model's shape and its
 * geometry are separate tables that have to be joined:
 * {@link lib.minecraft.renderer.content.table.BlockModelReader BlockModelReader} and
 * {@link lib.minecraft.renderer.content.table.BlockGeometryReader BlockGeometryReader} are the two
 * pure reads, {@link lib.minecraft.renderer.content.index.BlockEntityAssembler BlockEntityAssembler}
 * is the join, and
 * {@link lib.minecraft.renderer.content.pack.BlockModelLoader BlockModelLoader} is the thin
 * orchestrator over all three. {@link lib.minecraft.renderer.content.table.EntityModelLoader
 * EntityModelLoader} is the same shape for entities, handing its three reads - the geometry, the raw
 * {@link lib.minecraft.renderer.content.table.EntityModelsTable EntityModelsTable} and the
 * {@link lib.minecraft.renderer.content.table.EntityPosesTable EntityPosesTable} - to the index builder
 * that owns the geometry join.
 *
 * <p>A type that reads a {@code PackStack} does not belong here. What a pack overrides reaches a
 * reader here already gathered, as a
 * {@link lib.minecraft.renderer.content.read.BlockRendererOverrides BlockRendererOverrides}.
 *
 * <p><b>Parity.</b> A dump taken before the index was built would serialise what these hand back, and
 * the index builders that run between here and the renderer context could then be broken without
 * moving a dumped byte. It is taken after them instead, which is what makes a change here visible -
 * and two of these loaders decide a corpus count outright, because each count is the size of what its
 * loader returns.
 *
 * @see lib.minecraft.renderer.content.index
 * @see lib.minecraft.renderer.content.index.IndexedRendererContext
 */
@Parity(claim = "index-and-loader")
package lib.minecraft.renderer.content.table;

import lib.minecraft.renderer.parity.Parity;
