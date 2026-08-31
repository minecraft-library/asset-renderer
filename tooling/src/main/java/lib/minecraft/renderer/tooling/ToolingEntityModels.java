package lib.minecraft.renderer.tooling;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.animation.PoseFlow;
import lib.minecraft.renderer.tooling.entity.EntityMeshFacing;
import lib.minecraft.renderer.tooling.entity.EntityMeshMarking;
import lib.minecraft.renderer.tooling.entity.EntityMeshOverlays;
import lib.minecraft.renderer.tooling.entity.EntityMeshShift;
import lib.minecraft.renderer.tooling.entity.EntityPoseClass;
import lib.minecraft.renderer.tooling.entity.EntityRegistryDiscovery;
import lib.minecraft.renderer.tooling.entity.EntityRegistryWalk;
import lib.minecraft.renderer.tooling.entity.EntitySubject;
import lib.minecraft.renderer.tooling.geometry.GeometryFlow;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Entry point of the {@code entityModels} Gradle task - runs the entity-models flow then the shared
 * geometry flow and the pose flow in one session: discovery, registry walk,
 * {@code entity_models.json}, {@code entity_geometry.json}, {@code entity_poses.json}.
 */
@UtilityClass
public final class ToolingEntityModels {

    /**
     * Runs the flow, writes its table, and applies the session's strict gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("entityModels", Diagnostics.Output.CONSOLE)) {
            GeometryFlow.requireModelPackage(session);
            List<EntitySubject> subjects = EntityRegistryDiscovery.discover(session);
            JsonTree root = session.envelope(
                "EntityType.<clinit> registry order; members = EntityRendererResolver.resolve() chain");
            GeometryManifest manifest = new GeometryManifest(session.cache());
            EntityRegistryWalk.run(session, subjects, manifest, root);
            // Parsed but not yet written: which bones a subject rests without is settled by the pose
            // flow below, and that answer belongs in the mesh rather than beside it - so the entries
            // are held until it has been taken, and written once.
            Map<String, JsonTree> geometries = GeometryFlow.parse(session, manifest);
            Map<String, Set<String>> rootBones = GeometryFlow.rootBones(manifest, geometries);
            // The classes the renderers pose with, which the geometry manifest does not name: a model
            // reusing its parent's layer bakes no mesh, so nothing would have walked its pose. The
            // renderers themselves travel beside them, because what a renderer puts above the meshes
            // it submits is its own fact and no model class carries it.
            Set<String> posing = subjects.stream()
                .map(subject -> EntityPoseClass.of(session.cache(), subject.rendererClass()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> renderers = subjects.stream()
                .map(EntitySubject::rendererClass)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            // The model table travels into the pose flow because the fold needs what each subject
            // rests at, and that table is the statement of record for it - it is what the reader
            // joins on, so deriving the join from anything else would be a second account of it.
            //
            // It travels there before it is WRITTEN because the pose flow owns the keyspace it joins
            // on: a class two subjects pose two ways splits into a row each, and the body that takes
            // one names it in its own row. So the models table is written after, holding the join
            // the pose table actually carries.
            PoseFlow.Emitted posed = PoseFlow.emit(session, manifest, rootBones, posing, renderers,
                root.child("models"), session.resolve("entity_poses.json"));
            // With both halves of what each subject rests without now settled, they go onto the mesh
            // that renders rather than beside it, and the members saying so come off the model table.
            EntityMeshMarking.apply(session.diagnostics().child("bones"), root.child("models"), geometries);
            EntityMeshShift.apply(session.diagnostics().child("bones"), root.child("models"), geometries,
                posed.composing());
            // The facing a renderer folds into its delegated body rotation, which reaches every render
            // rather than only the ones that pose - so it goes into the mesh and no table states it.
            // After the shift, whose translate along y leaves a pivot on the axis the turn is about.
            EntityMeshFacing.apply(session.diagnostics().child("bones"), root.child("models"), geometries,
                posed.facings(), posed.rigid(), posed.composing());
            // Last of the three, because what a pass derives from is the mesh as it finally stands:
            // an overlay drawing the body's own mesh has to inherit the marking and the shift, which
            // deriving before either would leave behind on a mesh nobody then draws.
            EntityMeshOverlays.apply(session.diagnostics().child("overlays"), root.child("models"), geometries, manifest);
            GeometryFlow.write(session, geometries, session.resolve("entity_geometry.json"));
            session.write(root, "entity_models.json");
            session.failOnStrictGate();
        }
    }

}
