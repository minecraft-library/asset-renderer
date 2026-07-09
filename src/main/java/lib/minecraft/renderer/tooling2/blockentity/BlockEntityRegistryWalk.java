package lib.minecraft.renderer.tooling2.blockentity;

import lib.minecraft.renderer.tooling2.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingSession;
import lib.minecraft.renderer.tooling2.vanilla.LayerDefinitionIndex;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The block-models registry walk (SPINE 3.3 stage 2) - the ONLY stage that touches the output
 * tree. Builds the session-wide indexes once (shared with the entity flow - decision 32),
 * loops the subjects in registry order, and fans each subject into its family splits via
 * {@link BlockGeometrySourceResolver}, appending one {@code models} entry per split id.
 *
 * <p>The block side has no {@code family_of} analogue - no post-pass.
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
        @NotNull JsonNode root
    ) {
        LayerDefinitionIndex layerDefinitions = LayerDefinitionIndex.build(session);

        JsonNode models = root.child("models");
        for (BlockEntitySubject subject : subjects) {
            BlockGeometrySourceResolver geometry = new BlockGeometrySourceResolver(
                session, subject, layerDefinitions, manifest, session.diagnostics().child(subject.beTypeId()));
            for (BlockGeometrySourceResolver.Split split : geometry.resolveSplits())
                models.put(split.splitId(), new BlockEntityRendererResolver(subject, split).resolve());
        }
    }

}
