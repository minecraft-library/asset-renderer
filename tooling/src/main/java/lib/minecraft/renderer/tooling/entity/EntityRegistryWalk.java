package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The registry walk - the ONLY stage that touches the output tree.
 * Builds the session-wide indexes once, loops the subjects in registry order appending one
 * model per subject, and hosts the post-pass linker hook.
 */
@UtilityClass
public final class EntityRegistryWalk {

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
        EntityBlockOverlayResolver.requireVariantRenderStates(session.cache());
        EntityIndexes indexes = EntityIndexes.build(session, manifest);

        JsonTree models = root.child("models");
        for (EntitySubject subject : subjects)
            models.put(subject.entityId(), new EntityRendererResolver(
                new EntityContext(session, indexes, subject, session.diagnostics().child(subject.entityId()))
            ).resolve());
        // The group_of post-pass needs all rows.
        EntityGroupLinker.link(root, indexes.variants(), session.diagnostics().child("groupOf"));
    }

}
