package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * The registry walk - the ONLY stage that touches the output tree.
 * Builds the session-wide indexes once, loops the subjects in registry order appending one
 * family per subject, and hosts the post-pass linker hook.
 */
public final class EntityRegistryWalk {

    private EntityRegistryWalk() {
    }

    /**
     * Runs the per-subject resolver chain over every subject, appending to
     * {@code root.families}.
     *
     * @param session the live session
     * @param subjects the discovered subjects in registry order
     * @param manifest the geometry-request registry the resolvers populate
     * @param root the envelope root owning the {@code families} node
     */
    public static void run(
        @NotNull ToolingSession session,
        @NotNull List<EntitySubject> subjects,
        @NotNull GeometryManifest manifest,
        @NotNull JsonNode root
    ) {
        LayerDefinitionIndex layerDefinitions = LayerDefinitionIndex.build(session);
        VariantIndex variants = VariantIndex.build(session);
        BlockRegistryIndex blocks = BlockRegistryIndex.build(session);
        EntityPipelineTraits pipelineTraits = new EntityPipelineTraits(session.cache());
        Set<String> nonBaseSuffixes = EntityTextureResolver.deriveNonBaseSuffixes(session);

        JsonNode families = root.child("families");
        for (EntitySubject subject : subjects)
            families.put(subject.entityId(),
                new EntityRendererResolver(session, subject, layerDefinitions, variants, nonBaseSuffixes,
                    blocks, pipelineTraits, manifest).resolve());
        // The family_of post-pass needs all rows.
        EntityFamilyLinker.link(root, variants, session.diagnostics().child("familyOf"));
    }

}
