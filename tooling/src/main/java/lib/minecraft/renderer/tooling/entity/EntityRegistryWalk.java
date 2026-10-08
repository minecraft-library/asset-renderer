package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.TexturePathStrip;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The registry walk - the ONLY stage that touches the output tree.
 * Builds the run-wide indexes once, loops the registrations in registry order appending one
 * model per registration, and hosts the post-pass linker hook.
 */
@UtilityClass
public final class EntityRegistryWalk {

    /**
     * Runs the per-subject resolver chain over every subject, appending to
     * {@code root.models}, and writes each type vanilla draws nothing for at its registry position
     * as a row naming {@code NoopRenderer} and an adult form with no mesh and no texture.
     *
     * @param run the live run
     * @param registrations the discovered registrations in registry order
     * @param manifest the geometry-request registry the resolvers populate
     * @param root the envelope root owning the {@code models} node
     */
    public static void run(
        @NotNull ToolingRun run,
        @NotNull List<EntityRegistration> registrations,
        @NotNull GeometryManifest manifest,
        @NotNull JsonTree root
    ) {
        EntityBlockOverlayResolver.requireVariantRenderStates(run.cache());
        EntityIndexes indexes = EntityIndexes.build(run, manifest);

        JsonTree models = root.child("models");
        for (EntityRegistration registration : registrations) {
            models.put(registration.entityId(), switch (registration) {
                case EntitySubject subject -> new EntityRendererResolver(
                    new EntityContext(run, indexes, subject, run.diagnostics().child(subject.entityId()))
                ).resolve();
                case NoopRegistration ignored -> drawsNothingRow();
            });
        }
        // The grouping post-pass needs all rows.
        EntityGroupLinker.link(root, indexes.variants(), run.diagnostics().child("groupOf"));
        // Settled after the walk because every resolver spells a texture the way the bytecode does -
        // the full asset path - and the reader resolves the sub-path.
        TexturePathStrip.stripTexturePaths(models);
    }

    /**
     * The row of a type vanilla draws nothing for: the renderer it is bound to, and the mandatory age
     * axis holding an adult form that names no mesh and no texture.
     *
     * @return the row
     */
    private static @NotNull JsonTree drawsNothingRow() {
        JsonTree row = JsonTree.object().put("renderer", SourceClasses.Types.NOOP_RENDERER);
        row.child("axes").child("age").child("options").child("adult");
        return row;
    }

}
