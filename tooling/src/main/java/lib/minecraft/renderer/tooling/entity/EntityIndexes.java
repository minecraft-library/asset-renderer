package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.index.ArmorMeshIndex;
import lib.minecraft.renderer.tooling.index.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.index.EntityPipelineTraits;
import lib.minecraft.renderer.tooling.index.EquipmentAssetIndex;
import lib.minecraft.renderer.tooling.index.LayerDefinitionIndex;
import lib.minecraft.renderer.tooling.index.NonBaseSuffixIndex;
import lib.minecraft.renderer.tooling.index.VariantIndex;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The run-lifetime values the entity flow derives once and every subject reads - built before
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
public record EntityIndexes(
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
     * Derives every run-lifetime index from the run.
     *
     * <p>The locals exist to hold the derivation order, which is not the component order: each build
     * records its own {@code INFO} entries, so reordering them reorders the diagnostics log. That is
     * invisible to the emitted tables and visible in the log, which is the one place it would show.
     *
     * @param run the live run
     * @param manifest the geometry-request registry the flow's caller owns
     * @return the indexes every subject reads
     */
    static @NotNull EntityIndexes build(@NotNull ToolingRun run, @NotNull GeometryManifest manifest) {
        LayerDefinitionIndex layerDefinitions = LayerDefinitionIndex.build(run);
        VariantIndex variants = VariantIndex.build(run, EntityNamingPolicies.ENUM_DEFAULT_FIELD.stringValue());
        BlockRegistryIndex blocks = BlockRegistryIndex.build(run);
        EquipmentAssetIndex equipmentAssets = EquipmentAssetIndex.build(run);
        ArmorMeshIndex armorMeshes = ArmorMeshIndex.build(run);
        EntityPipelineTraits pipelineTraits = new EntityPipelineTraits(run.cache());
        Set<String> nonBaseSuffixes = NonBaseSuffixIndex.deriveNonBaseSuffixes(
            run, EntityNamingPolicies.SUFFIX_MIN_RECURRENCE.intValue());
        return new EntityIndexes(layerDefinitions, variants, nonBaseSuffixes, blocks, pipelineTraits,
            equipmentAssets, armorMeshes, manifest);
    }

}
