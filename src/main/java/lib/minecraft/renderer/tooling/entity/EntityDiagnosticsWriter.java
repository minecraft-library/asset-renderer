package lib.minecraft.renderer.tooling.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.JsonOptional;
import lib.minecraft.renderer.tooling.util.ToolingJson;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes the two dev-only diagnostic JSON dumps that {@code ToolingEntityModels} emits
 * alongside the production runtime JSONs. Both files land under
 * {@code cache/asset-renderer/diagnostics/} (gitignored) and carry intermediate state the
 * production output collapses away - per-binding texture path, hierarchy-source inheritance,
 * per-geometry bone/cube counts, the full variant-table dump.
 */
@UtilityClass
public final class EntityDiagnosticsWriter {

    /**
     * Discovery + per-entity-binding diagnostic output path. Lives under {@code cache/} (gitignored).
     */
    public static final @NotNull Path DISCOVERY_OUTPUT =
        Path.of("cache/asset-renderer/diagnostics/java_entity_renderers.json");

    /**
     * Geometry diagnostic output path. Lives under {@code cache/} (gitignored).
     */
    public static final @NotNull Path GEOMETRY_DIAGNOSTIC_OUTPUT =
        Path.of("cache/asset-renderer/diagnostics/java_entity_geometry.json");

    /**
     * Builds and writes the discovery + per-entity-binding diagnostic JSON document
     * (per-entity renderer / texture binding / layer enumeration plus the variant tables).
     *
     * @param options the pipeline options for the {@code client_version} stamp
     * @param registry the discovery result (mob counts, mobs-without-renderer list)
     * @param records the per-entity walk results
     * @param variants the loaded variant tables (per-entity-stem variant lists)
     * @param withPrimaryTexture count of entities with a resolved primary texture; emitted as
     *     {@code texture_primary_count}
     * @param variantDriven count of entities whose texture is variant-driven; emitted as
     *     {@code texture_variant_driven_count}
     * @param unresolvedTexture count of entities whose texture could not be resolved; emitted as
     *     {@code texture_unresolved_count}
     * @param diagnostics the diagnostic sink whose entries land in the JSON's
     *     {@code diagnostics} array
     * @return the absolute path written (for the caller's {@code System.out.println} stamp)
     * @throws IOException if the output file cannot be written
     */
    public static @NotNull Path writeDiscoveryDiagnostic(
        @NotNull PipelineOptions options,
        @NotNull EntityRegistryDiscovery.Result registry,
        @NotNull Map<String, EntitySessionWalk.Result> records,
        @NotNull ConcurrentMap<String, ConcurrentList<EntityVariantResolver.Result>> variants,
        int withPrimaryTexture,
        int variantDriven,
        int unresolvedTexture,
        @NotNull Diagnostics diagnostics
    ) throws IOException {
        JsonObject root = buildDiscoveryDoc(options, registry, records, variants, withPrimaryTexture, variantDriven, unresolvedTexture, diagnostics);
        ToolingJson.writeJson(DISCOVERY_OUTPUT, root, ToolingJson.PRETTY_HTML_SAFE);
        return DISCOVERY_OUTPUT.toAbsolutePath();
    }

    /**
     * Builds and writes the geometry diagnostic JSON document.
     *
     * @param options the pipeline options for the {@code client_version} stamp
     * @param mobsTotal total mob count for the stats line
     * @param mobsWithRenderer joined-mob count for the stats line
     * @param entityToResolution per-entity layer-definition resolution map
     * @param geometries parsed geometry JSON keyed by entity id
     * @param diagnostics the diagnostic sink
     * @return the absolute path written
     * @throws IOException if the output file cannot be written
     */
    public static @NotNull Path writeGeometryDiagnostic(
        @NotNull PipelineOptions options,
        int mobsTotal,
        int mobsWithRenderer,
        @NotNull ConcurrentMap<String, EntityLayerDefinitionResolver.Result> entityToResolution,
        @NotNull ConcurrentMap<String, JsonObject> geometries,
        @NotNull Diagnostics diagnostics
    ) throws IOException {
        JsonObject root = buildGeometryDoc(options, mobsTotal, mobsWithRenderer, entityToResolution, geometries, diagnostics);
        ToolingJson.writeJson(GEOMETRY_DIAGNOSTIC_OUTPUT, root, ToolingJson.PRETTY_HTML_SAFE);
        return GEOMETRY_DIAGNOSTIC_OUTPUT.toAbsolutePath();
    }

    /**
     * Assembles the discovery diagnostic document: the header comment + {@code client_version}
     * stamp, the coverage stat block (mob counts, texture-resolution tallies), a per-entity
     * {@code entities} object (renderer / texture / baby-texture / variant source / inherited
     * binding / overlay layers), the {@code mobs_without_renderer_list}, the full
     * {@code variant_tables} dump, and the trailing {@code diagnostics} log array.
     */
    private static @NotNull JsonObject buildDiscoveryDoc(
        @NotNull PipelineOptions options,
        @NotNull EntityRegistryDiscovery.Result registry,
        @NotNull Map<String, EntitySessionWalk.Result> records,
        @NotNull ConcurrentMap<String, ConcurrentList<EntityVariantResolver.Result>> variants,
        int withPrimaryTexture,
        int variantDriven,
        int unresolvedTexture,
        @NotNull Diagnostics diagnostics
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingEntityModels. Discovery + per-entity-binding diagnostic - lists living mobs from EntityRegistryDiscovery and their detected renderer + model classes. Run ./gradlew :asset-renderer:entityModels to refresh.");
        root.addProperty("client_version", options.getVersion());
        root.addProperty("mobs_discovered", registry.totalMobsDiscovered());
        root.addProperty("mobs_with_renderer", records.size());
        root.addProperty("mobs_without_renderer", registry.mobsWithoutRenderer().size());
        root.addProperty("renderer_registrations_total", registry.entries().size());
        root.addProperty("texture_primary_count", withPrimaryTexture);
        root.addProperty("texture_variant_driven_count", variantDriven);
        root.addProperty("texture_unresolved_count", unresolvedTexture);

        JsonObject entities = new JsonObject();
        for (Map.Entry<String, EntitySessionWalk.Result> entry : records.entrySet()) {
            JsonObject row = new JsonObject();
            EntitySessionWalk.Result rec = entry.getValue();
            row.addProperty("renderer", rec.rendererInternalName());
            EntityTextureResolver.Result binding = rec.binding();
            if (binding.primaryTexturePath() != null) row.addProperty("texture", binding.primaryTexturePath());
            if (binding.babyTexturePath() != null) row.addProperty("baby_texture", binding.babyTexturePath());
            if (binding.isVariantDriven()) {
                row.addProperty("variant_source_class", binding.variantSourceClass());
                if (rec.variantStem() != null) row.addProperty("variant_directory", rec.variantStem());
            }
            if (binding.hierarchySource() != null) row.addProperty("binding_inherited_from", binding.hierarchySource());
            if (!rec.layers().isEmpty()) {
                JsonArray layers = new JsonArray();
                for (String layer : rec.layers()) layers.add(layer);
                row.add("overlay_layers", layers);
            }
            entities.add(entry.getKey(), row);
        }
        root.add("entities", entities);

        JsonArray unmappedList = new JsonArray();
        registry.mobsWithoutRenderer().forEach(unmappedList::add);
        root.add("mobs_without_renderer_list", unmappedList);

        JsonObject variantTables = new JsonObject();
        for (Map.Entry<String, ConcurrentList<EntityVariantResolver.Result>> e : variants.entrySet()) {
            JsonArray arr = new JsonArray();
            for (EntityVariantResolver.Result v : e.getValue()) {
                JsonObject vo = new JsonObject();
                vo.addProperty("variant_id", v.variantId());
                String primary = v.primaryTexturePath();
                if (primary != null) vo.addProperty("texture", primary);
                String babyPrimary = v.primaryBabyTexturePath();
                if (babyPrimary != null) vo.addProperty("baby_texture", babyPrimary);
                if (v.textures().size() > 1) {
                    JsonObject sub = new JsonObject();
                    v.textures().forEach(sub::addProperty);
                    vo.add("textures", sub);
                }
                if (!v.babyTextures().isEmpty() && v.babyTextures().size() > 1) {
                    JsonObject sub = new JsonObject();
                    v.babyTextures().forEach(sub::addProperty);
                    vo.add("baby_textures", sub);
                }
                if (v.model() != null) vo.addProperty("model", v.model());
                arr.add(vo);
            }
            variantTables.add(e.getKey(), arr);
        }
        root.add("variant_tables", variantTables);

        JsonArray diagnosticsArr = new JsonArray();
        for (String line : diagnostics.entries()) diagnosticsArr.add(line);
        root.add("diagnostics", diagnosticsArr);

        return root;
    }

    /**
     * Assembles the geometry diagnostic document: the header comment + {@code client_version}
     * stamp, the coverage stat block (mob totals, primary-layer / geometry coverage), a
     * per-entity {@code entities} object (layer field + factory class/method, texture
     * dimensions, bone/cube counts, bone-name list), running {@code total_bones} /
     * {@code total_cubes} sums accumulated across the entity loop, and the trailing
     * {@code diagnostics} log array. Cube counts are summed by walking each bone's
     * {@code cubes} array in the parsed geometry JSON.
     */
    private static @NotNull JsonObject buildGeometryDoc(
        @NotNull PipelineOptions options,
        int mobsTotal,
        int mobsWithRenderer,
        @NotNull ConcurrentMap<String, EntityLayerDefinitionResolver.Result> entityToResolution,
        @NotNull ConcurrentMap<String, JsonObject> geometries,
        @NotNull Diagnostics diagnostics
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingEntityModels. Per-entity geometry from Java client jar bytecode (LayerDefinition.create / CubeListBuilder / PartPose / addOrReplaceChild walked via the shared block-entity Parser). Frame is vanilla Java's natural Y-DOWN.");
        root.addProperty("client_version", options.getVersion());
        root.addProperty("mobs_total", mobsTotal);
        root.addProperty("mobs_with_renderer", mobsWithRenderer);
        root.addProperty("mobs_with_primary_layer", entityToResolution.size());
        root.addProperty("mobs_with_geometry", geometries.size());

        int totalBones = 0;
        int totalCubes = 0;
        JsonObject entities = new JsonObject();
        for (Map.Entry<String, JsonObject> entry : geometries.entrySet()) {
            JsonObject geometry = entry.getValue();
            JsonObject row = new JsonObject();
            EntityLayerDefinitionResolver.Result res = entityToResolution.get(entry.getKey());
            if (res != null) {
                row.addProperty("layer_field", res.sourceLayerField());
                row.addProperty("factory_class", res.targetClass());
                row.addProperty("factory_method", res.targetMethod());
            }
            int textureWidth = JsonOptional.optInt(geometry, "textureWidth", 0);
            int textureHeight = JsonOptional.optInt(geometry, "textureHeight", 0);
            row.addProperty("texture_width", textureWidth);
            row.addProperty("texture_height", textureHeight);

            int boneCount = 0;
            int cubeCount = 0;
            JsonArray boneNames = new JsonArray();
            JsonObject bones = JsonOptional.optObject(geometry, "bones");
            if (bones != null) {
                boneCount = bones.size();
                for (Map.Entry<String, JsonElement> b : bones.entrySet()) {
                    boneNames.add(b.getKey());
                    if (b.getValue().isJsonObject()
                        && b.getValue().getAsJsonObject().has("cubes")
                        && b.getValue().getAsJsonObject().get("cubes").isJsonArray())
                        cubeCount += b.getValue().getAsJsonObject().getAsJsonArray("cubes").size();
                }
            }
            row.addProperty("bone_count", boneCount);
            row.addProperty("cube_count", cubeCount);
            row.add("bone_names", boneNames);
            entities.add(entry.getKey(), row);
            totalBones += boneCount;
            totalCubes += cubeCount;
        }
        root.addProperty("total_bones", totalBones);
        root.addProperty("total_cubes", totalCubes);
        root.add("entities", entities);

        JsonArray diagnosticsArr = new JsonArray();
        for (String line : diagnostics.entries()) diagnosticsArr.add(line);
        root.add("diagnostics", diagnosticsArr);
        return root;
    }

}
