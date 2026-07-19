package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The block-models registry walk - the ONLY stage that touches the output tree. Builds the
 * session-wide indexes once (shared with the entity flow), loops the subjects in registry order,
 * and fans each subject into its family splits via {@link BlockGeometrySourceResolver}, appending
 * one {@code models} entry per split id.
 *
 * <p>The block side has no {@code group_of} analogue - no post-pass.
 */
public final class BlockEntityRegistryWalk {

    private BlockEntityRegistryWalk() {
    }

    /**
     * Runs the per-split resolver chain over every subject, appending to {@code root.models}.
     *
     * @param session the live session
     * @param subjects the discovered subjects in registry order
     * @param manifest the geometry-request registry the resolvers populate
     * @param root the envelope root owning the {@code models} node
     */
    public static void run(
        @NotNull ToolingSession session,
        @NotNull List<BlockEntitySubject> subjects,
        @NotNull GeometryManifest manifest,
        @NotNull JsonTree root
    ) {
        LayerDefinitionIndex layerDefinitions = LayerDefinitionIndex.build(session);
        BlockRegistryIndex blockRegistry = BlockRegistryIndex.build(session);
        BlockTintFlagResolver tint = new BlockTintFlagResolver(session.cache());
        BlockGuiResolver gui = new BlockGuiResolver(session.cache());
        InventoryTransformResolver transform = new InventoryTransformResolver(session.cache());

        JsonTree models = root.child("models");
        for (BlockEntitySubject subject : subjects) {
            BlockGeometrySourceResolver geometry = new BlockGeometrySourceResolver(
                session, subject, layerDefinitions, manifest, session.diagnostics().child(subject.beTypeId()));
            List<BlockGeometrySourceResolver.Split> splits = geometry.resolveSplits();

            List<String> splitIds = new ArrayList<>();
            for (BlockGeometrySourceResolver.Split split : splits) splitIds.add(split.splitId());
            BlockCatalogResolver catalog = new BlockCatalogResolver(session.cache(), blockRegistry, subject,
                splitIds, session.diagnostics().child(subject.beTypeId()));

            for (BlockGeometrySourceResolver.Split split : splits)
                models.put(split.splitId(), new BlockEntityRendererResolver(subject, split, tint, gui, catalog, transform).resolve());
        }
    }

}
