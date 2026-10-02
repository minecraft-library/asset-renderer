package lib.minecraft.renderer.content.table;

import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.content.read.BundledResource;
import lib.minecraft.renderer.content.read.ResourceDocument;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * The three shipped entity tables, read off the classpath as they stand: {@code entity_geometry.json}
 * decodes straight into the deduplicated bone/cube trees keyed by geometry coordinate,
 * {@code entity_models.json} decodes straight into the raw {@link EntityModelsTable} tree (90 base-entity
 * models), and {@code entity_poses.json} decodes into the pose each model class takes.
 *
 * <p>The geometry file is keyed by the same manifest factory coordinate the model baseline names under
 * {@code axes.age.options.adult.geometry} (e.g. {@code AdultWolfModel#createBodyLayer},
 * {@code PigModel#createBodyLayer@grow=0.5}), so a coordinate resolves directly - the geometry join, the
 * mesh surgery, the axes pivot, and the cross-entity grouping belong to the entity index, not to these
 * reads.
 *
 * @param geometries the bone tree of each geometry coordinate
 * @param models the raw model table, one row per base entity
 * @param poses the pose of each model class, empty when the pose table is absent
 */
public record EntityTables(
    @NotNull Map<String, EntityMesh> geometries,
    @NotNull EntityModelsTable models,
    @NotNull EntityPosesTable poses
) {

    private static final @NotNull String MODELS_RESOURCE = "entity_models.json";
    private static final @NotNull String GEOMETRY_RESOURCE = "entity_geometry.json";
    private static final @NotNull String POSES_RESOURCE = "entity_poses.json";

    /**
     * The {@code format} value the models and poses tables are read under - the two entity tables
     * ship at format 3, where the geometry table keeps the strict format 2 read.
     */
    private static final int ENTITY_TABLE_FORMAT = 3;

    /**
     * Reads the three entity tables natively from the bundled resources.
     *
     * @return the three reads, or nothing answered when the geometry or models resource is absent or
     *     carries no rows
     * @throws ContentException if a resource is malformed
     */
    public static @NotNull Optional<EntityTables> read() {
        Optional<ResourceDocument> geometryDoc = BundledResource.read(GEOMETRY_RESOURCE);
        Optional<ResourceDocument> modelsDoc = BundledResource.read(MODELS_RESOURCE, ENTITY_TABLE_FORMAT);
        if (geometryDoc.isEmpty() || modelsDoc.isEmpty()) return Optional.empty();

        Map<String, EntityMesh> geometries = parseGeometries(geometryDoc.get());
        if (geometries.isEmpty()) return Optional.empty();
        EntityModelsTable raw = modelsDoc.get().as(EntityModelsTable.class);
        if (raw == null || raw.models() == null) return Optional.empty();

        return Optional.of(new EntityTables(geometries, raw, parsePoses()));
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
