package lib.minecraft.renderer.content.table;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.content.index.EntityIndexBuilder;
import lib.minecraft.renderer.content.read.BundledResource;
import lib.minecraft.renderer.content.read.ResourceDocument;
import lib.minecraft.renderer.content.table.EntityModelsTable;
import lib.minecraft.renderer.content.table.EntityPosesTable;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.Optional;

/**
 * The reader for entity model definitions, orchestrating three pure reads and one assembler:
 * {@code entity_geometry.json} decodes straight into the deduplicated bone/cube trees keyed by geometry
 * coordinate, {@code entity_models.json} decodes straight into the raw {@link EntityModelsTable} tree
 * (90 base-entity models), {@code entity_poses.json} decodes into the pose each model class takes, and
 * {@link EntityIndexBuilder} joins them into the {@link Entity} map the renderer consumes.
 *
 * <p>The geometry file is keyed by the same manifest factory coordinate the model baseline names under
 * {@code axes.age.options.adult.geometry} (e.g. {@code AdultWolfModel#createBodyLayer},
 * {@code PigModel#createBodyLayer@grow=0.5}), so a coordinate resolves directly - the geometry join, the
 * mesh surgery, the axes pivot, and the cross-entity grouping are {@link EntityIndexBuilder}'s concern,
 * not this reader's.
 */
@UtilityClass
public final class EntityModelLoader {

    private static final @NotNull String MODELS_RESOURCE = "entity_models.json";
    private static final @NotNull String GEOMETRY_RESOURCE = "entity_geometry.json";
    private static final @NotNull String POSES_RESOURCE = "entity_poses.json";

    /**
     * The {@code format} value the models and poses tables are read under - the two entity tables
     * ship at format 3, where the geometry table keeps the strict format 2 read.
     */
    private static final int ENTITY_TABLE_FORMAT = 3;

    /** Guards {@link #memo}, so concurrent first callers assemble the index once. */
    private static final @NotNull Object MEMO_LOCK = new Object();

    /** The memoized index, held softly so memory pressure can reclaim it between renders. */
    private static @Nullable SoftReference<ConcurrentMap<String, Entity>> memo;

    /**
     * Reads the entity model catalog natively from the bundled resources, then hands the three raw reads
     * to {@link EntityIndexBuilder} for the join, surgery, pivot, and grouping.
     *
     * <p>The assembled map is memoized behind a soft reference: every caller shares one instance,
     * re-assembled only after memory pressure reclaims it.
     *
     * @return definitions keyed by namespaced entity id (empty when the geometry resource is absent)
     * @throws ContentException if a resource is malformed, or an entity references a geometry
     *     coordinate absent from the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> load() {
        synchronized (MEMO_LOCK) {
            ConcurrentMap<String, Entity> held = memo == null ? null : memo.get();
            if (held != null) return held;
            ConcurrentMap<String, Entity> built = assemble();
            memo = new SoftReference<>(built);
            return built;
        }
    }

    /** One whole assembly of the index - the three raw reads and the {@link EntityIndexBuilder} join. */
    private static @NotNull ConcurrentMap<String, Entity> assemble() {
        Optional<ResourceDocument> geometryDoc = BundledResource.read(GEOMETRY_RESOURCE);
        Optional<ResourceDocument> modelsDoc = BundledResource.read(MODELS_RESOURCE, ENTITY_TABLE_FORMAT);
        if (geometryDoc.isEmpty() || modelsDoc.isEmpty()) return Concurrent.newMap();

        Map<String, EntityMesh> geometries = parseGeometries(geometryDoc.get());
        if (geometries.isEmpty()) return Concurrent.newMap();
        EntityModelsTable raw = modelsDoc.get().as(EntityModelsTable.class);
        if (raw == null || raw.models() == null) return Concurrent.newMap();

        return EntityIndexBuilder.assemble(geometries, raw, parsePoses().poses());
    }

    /**
     * Reads the pose table, which every entity does without rather than none of them existing.
     *
     * <p>Deliberately outside the guard the other two reads share. Geometry and models are what an
     * entity IS - an absent one leaves nothing to build and the empty map is the honest answer - but
     * a pose is something an entity does, and a build with no poses is ninety entities that hold
     * still rather than no entities at all. Joining this read to that disjunction would turn a
     * missing pose table into a renderer with no entities in it.
     *
     * @return the pose of each model class, or nothing answered when the resource is absent
     */
    private static @NotNull EntityPosesTable parsePoses() {
        return BundledResource.read(POSES_RESOURCE, ENTITY_TABLE_FORMAT)
            .map(document -> document.as(EntityPosesTable.class))
            .orElseGet(() -> new EntityPosesTable(Map.of()));
    }

    /** Reads the {@code geometries} coordinate map straight into {@link EntityMesh} values. */
    private static @NotNull Map<String, EntityMesh> parseGeometries(@NotNull ResourceDocument geometryDoc) {
        Map<String, EntityMesh> geometries = geometryDoc.as(EntityGeometryFile.class).geometries();
        return geometries == null ? Map.of() : geometries;
    }

    /** The {@code entity_geometry.json} payload: geometry coordinate to its bone tree. */
    private record EntityGeometryFile(@NotNull Map<String, EntityMesh> geometries) {}
}
