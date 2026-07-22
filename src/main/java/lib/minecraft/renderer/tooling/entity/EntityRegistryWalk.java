package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * The registry walk - the ONLY stage that touches the output tree.
 * Builds the session-wide indexes once, loops the subjects in registry order appending one
 * model per subject, and hosts the post-pass linker hook.
 */
public final class EntityRegistryWalk {

    private EntityRegistryWalk() {
    }

    /**
     * Runs the per-subject resolver chain over every subject, appending to
     * {@code root.models}.
     *
     * @param session the live session
     * @param subjects the discovered subjects in registry order
     * @param manifest the geometry-request registry the resolvers populate
     * @param root the envelope root owning the {@code models} node
     */
    public static void run(
        @NotNull ToolingSession session,
        @NotNull List<EntitySubject> subjects,
        @NotNull GeometryManifest manifest,
        @NotNull JsonTree root
    ) {
        LayerDefinitionIndex layerDefinitions = LayerDefinitionIndex.build(session);
        VariantIndex variants = VariantIndex.build(session);
        BlockRegistryIndex blocks = BlockRegistryIndex.build(session);
        EquipmentAssetIndex equipmentAssets = EquipmentAssetIndex.build(session);
        EntityPipelineTraits pipelineTraits = new EntityPipelineTraits(session.cache());
        Set<String> nonBaseSuffixes = EntityTextureResolver.deriveNonBaseSuffixes(session);

        JsonTree models = root.child("models");
        for (EntitySubject subject : subjects)
            models.put(subject.entityId(),
                new EntityRendererResolver(session, subject, layerDefinitions, variants, nonBaseSuffixes,
                    blocks, pipelineTraits, equipmentAssets, manifest).resolve());
        // The group_of post-pass needs all rows.
        EntityGroupLinker.link(root, variants, session.diagnostics().child("groupOf"));
    }

}
