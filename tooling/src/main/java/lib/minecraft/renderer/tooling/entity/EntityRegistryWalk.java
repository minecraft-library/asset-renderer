package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.TexturePathStrip;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The registry walk - the ONLY stage that touches the output tree.
 * Builds the run-wide indexes once, loops the subjects in registry order appending one
 * model per subject, and hosts the post-pass linker hook.
 */
@UtilityClass
public final class EntityRegistryWalk {

    /**
     * Runs the per-subject resolver chain over every subject, appending to
     * {@code root.models}.
     *
     * @param run the live run
     * @param subjects the discovered subjects in registry order
     * @param manifest the geometry-request registry the resolvers populate
     * @param root the envelope root owning the {@code models} node
     */
    public static void run(
        @NotNull ToolingRun run,
        @NotNull List<EntitySubject> subjects,
        @NotNull GeometryManifest manifest,
        @NotNull JsonTree root
    ) {
        EntityBlockOverlayResolver.requireVariantRenderStates(run.cache());
        EntityIndexes indexes = EntityIndexes.build(run, manifest);

        JsonTree models = root.child("models");
        for (EntitySubject subject : subjects)
            models.put(subject.entityId(), new EntityRendererResolver(
                new EntityContext(run, indexes, subject, run.diagnostics().child(subject.entityId()))
            ).resolve());
        // The grouping post-pass needs all rows.
        EntityGroupLinker.link(root, indexes.variants(), run.diagnostics().child("groupOf"));
        // Settled after the walk because every resolver spells a texture the way the bytecode does -
        // the full asset path - and the reader resolves the sub-path.
        TexturePathStrip.stripTexturePaths(models);
    }

}
