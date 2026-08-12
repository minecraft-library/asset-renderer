package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.ArmorMeshIndex;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The session-lifetime values the entity flow derives once and every subject reads - built before
 * the registry walk begins and unchanged across all of it.
 *
 * <p>Separate from {@link EntityContext} because the lifetimes differ: this is built once per run,
 * a context once per subject and again per diagnostics scope. Adding a value here reaches every
 * resolver without widening one constructor between the walk and its consumer.
 *
 * @param layerDefinitions the {@code ModelLayers} registration index
 * @param variants the variant-table index
 * @param nonBaseSuffixes the derived texture suffixes that mark a non-base sibling
 * @param blocks the block-registry index, for the block an entity carries or wears
 * @param pipelineTraits the per-layer render-pipeline reader
 * @param equipmentAssets the equipment-asset index
 * @param armorMeshes the worn-armour mesh set index
 * @param manifest the geometry-request registry the resolvers populate
 */
record EntityIndexes(
    @NotNull LayerDefinitionIndex layerDefinitions,
    @NotNull VariantIndex variants,
    @NotNull Set<String> nonBaseSuffixes,
    @NotNull BlockRegistryIndex blocks,
    @NotNull EntityPipelineTraits pipelineTraits,
    @NotNull EquipmentAssetIndex equipmentAssets,
    @NotNull ArmorMeshIndex armorMeshes,
    @NotNull GeometryManifest manifest
) {

    /**
     * Derives every session-lifetime index from the session.
     *
     * <p>The locals exist to hold the derivation order, which is not the component order: each build
     * records its own {@code INFO} entries, so reordering them reorders the diagnostics log. That is
     * invisible to the emitted tables and visible in the log, which is the one place it would show.
     *
     * @param session the live session
     * @param manifest the geometry-request registry the flow's caller owns
     * @return the indexes every subject reads
     */
    static @NotNull EntityIndexes build(@NotNull ToolingSession session, @NotNull GeometryManifest manifest) {
        LayerDefinitionIndex layerDefinitions = LayerDefinitionIndex.build(session);
        VariantIndex variants = VariantIndex.build(session);
        BlockRegistryIndex blocks = BlockRegistryIndex.build(session);
        EquipmentAssetIndex equipmentAssets = EquipmentAssetIndex.build(session);
        ArmorMeshIndex armorMeshes = ArmorMeshIndex.build(session);
        EntityPipelineTraits pipelineTraits = new EntityPipelineTraits(session.cache());
        Set<String> nonBaseSuffixes = EntityTextureResolver.deriveNonBaseSuffixes(session);
        return new EntityIndexes(layerDefinitions, variants, nonBaseSuffixes, blocks, pipelineTraits,
            equipmentAssets, armorMeshes, manifest);
    }

}
