package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.index.EntityIndexBuilder;
import lib.minecraft.renderer.pipeline.index.RawEntityModelsFile;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * The reader for entity model definitions, orchestrating two pure reads and one assembler:
 * {@code entity_geometry.json} decodes straight into the deduplicated bone/cube trees keyed by geometry
 * coordinate, {@code entity_models.json} decodes straight into the raw {@link RawEntityModelsFile} tree
 * (90 base-entity models), and {@link EntityIndexBuilder} joins them into the {@link Entity} map the
 * renderer consumes.
 *
 * <p>The geometry file is keyed by the same manifest factory coordinate the model baseline names under
 * {@code axes.age.options.adult.geometry} (e.g. {@code AdultWolfModel#createBodyLayer},
 * {@code PigModel#createBodyLayer@grow=0.5}), so a coordinate resolves directly - the geometry join, the
 * mesh surgery, the axes pivot, and the cross-entity grouping are {@link EntityIndexBuilder}'s concern,
 * not this reader's.
 */
public final class EntityModelLoader {

    private static final @NotNull String MODELS_RESOURCE = "entity_models.json";
    private static final @NotNull String GEOMETRY_RESOURCE = "entity_geometry.json";

    private EntityModelLoader() {}

    /**
     * Loads the bundled entity definitions with a default console diagnostics scope.
     *
     * @return definitions keyed by namespaced entity id (empty when the geometry resource is absent)
     * @throws PipelineException when a resource file is present but unparseable, or when an entity
     *     references a geometry id not in the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> load() {
        return load(Diagnostics.root("entity_models", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the entity model catalog natively from the bundled resources, then hands the two raw reads to
     * {@link EntityIndexBuilder} for the join, surgery, pivot, and grouping.
     *
     * @param diagnostics the scope envelope and read warnings are recorded to
     * @return definitions keyed by namespaced entity id (empty when the geometry resource is absent)
     * @throws PipelineException if a resource is malformed, or an entity references a geometry
     *     coordinate absent from the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> load(@NotNull Diagnostics diagnostics) {
        Optional<ResourceDocument> geometryDoc = BundledResource.read(GEOMETRY_RESOURCE, BundledResource.MissingPolicy.GRACEFUL_EMPTY, diagnostics);
        Optional<ResourceDocument> modelsDoc = BundledResource.read(MODELS_RESOURCE, BundledResource.MissingPolicy.GRACEFUL_EMPTY, diagnostics);
        if (geometryDoc.isEmpty() || modelsDoc.isEmpty()) return Concurrent.newMap();

        Map<String, EntityModelData> geometries = parseGeometries(geometryDoc.get());
        if (geometries.isEmpty()) return Concurrent.newMap();
        RawEntityModelsFile raw = modelsDoc.get().as(RawEntityModelsFile.class);
        if (raw == null || raw.models() == null) return Concurrent.newMap();

        return EntityIndexBuilder.assemble(geometries, raw, diagnostics);
    }

    /** Reads the {@code geometries} coordinate map straight into {@link EntityModelData} values. */
    private static @NotNull Map<String, EntityModelData> parseGeometries(@NotNull ResourceDocument geometryDoc) {
        Map<String, EntityModelData> geometries = geometryDoc.as(EntityGeometryFile.class).geometries();
        return geometries == null ? Map.of() : geometries;
    }

    /** The {@code entity_geometry.json} payload: geometry coordinate to its bone tree. */
    private record EntityGeometryFile(@NotNull Map<String, EntityModelData> geometries) {}
}
