package lib.minecraft.renderer.tooling.entity;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Emits the runtime-consumable {@code entity_models.json} and {@code entity_geometry.json}.
 * The geometry file deduplicates by factory class+method so multiple entities sharing one
 * {@code createBodyLayer} (e.g. zombie / husk / drowned all point at the same
 * {@code AbstractZombieRenderer} chain) share one geometry entry. The models file emits one
 * row per discovered entity plus additional rows per non-default data-driven variant
 * (cow_cold / cow_warm / pig_cold / etc.), each carrying {@code variant_of} pointing back at
 * its base entity.
 *
 * <p>Internally the writer is a single sequential method: build the factory-key -> geometry
 * id dedupe map, write {@code entity_geometry.json}, walk each entity record emitting a row
 * (including overlay rows, block-overlay rows, base tint, hidden bones), emit variant rows
 * for data-driven variants, then write {@code entity_models.json} with the families table
 * from {@link #deriveFamilies}.
 */
@UtilityClass
public final class EntityRuntimeJsonWriter {

    /**
     * Runtime-consumable per-entity metadata path. Same shape as the legacy
     * {@code entity_models.json} but populated by the Java pipeline; loaded by
     * {@code EntityModelLoader} when {@code PipelineOptions.entityModelSource = JAVA}.
     */
    public static final @NotNull Path MODELS_OUTPUT =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json");

    /**
     * Runtime-consumable per-geometry bone/cube data path. Same shape as the legacy
     * {@code entity_geometry.json}; one geometry entry per unique factory method,
     * deduplicated when multiple entities share the same {@code createBodyLayer}.
     */
    public static final @NotNull Path GEOMETRY_OUTPUT =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json");

    /**
     * Variant id treated as the base entity (no separate {@code variant_of} row emitted) when
     * walking data-driven variant tables. Vanilla 1.21+ uses {@code "temperate"} for cow / pig /
     * chicken / frog as the climate-default; the base entity takes that variant's texture
     * when no spawn-condition match overrides it.
     */
    private static final @NotNull String DEFAULT_VARIANT_ID = "temperate";

    /**
     * Emits both runtime JSON files. Returns the number of variant rows written (in addition
     * to base-entity rows) for the caller's summary line.
     */
    public static int writeAll(
        @NotNull EntityToolingContext context,
        @NotNull Map<String, EntitySessionWalk.Result> records,
        @NotNull ConcurrentMap<String, EntityLayerDefinitionResolver.Result> entityToResolution,
        @NotNull ConcurrentMap<String, JsonObject> geometries,
        @NotNull ConcurrentMap<String, ConcurrentList<EntityVariantResolver.Result>> variants,
        @NotNull Diagnostics diagnostics,
        @NotNull Map<String, ConcurrentList<EntityOverlayResolver.Result>> overlaysByEntity,
        @NotNull Map<String, EntityLayerDefinitionResolver.Result> overlayFieldToResolution,
        @NotNull Map<String, String> dataVariantDefaults,
        @NotNull Map<String, ConcurrentList<EntityBlockOverlayResolver.Result>> blockOverlaysByEntity
    ) throws IOException {
        // Build (factoryKey -> geometry id) so multiple entities sharing one createBodyLayer
        // map to one geometry entry. Geometry id derived from the factory class name's lowercased
        // simple name plus the method's suffix - matches the convention {@code geometry.X}.
        Map<String, String> factoryKeyToGeometryId = new LinkedHashMap<>();
        JsonObject geometriesOut = new JsonObject();
        for (Map.Entry<String, JsonObject> entry : geometries.entrySet()) {
            EntityLayerDefinitionResolver.Result res = entityToResolution.get(entry.getKey());
            if (res == null) continue;
            // Include defaultInflate in the dedupe key so the same factory called with different
            // CubeDeformation args (e.g. {@code DrownedModel.createBodyLayer(NONE)} for the body
            // vs {@code .createBodyLayer(0.25)} for the outer-layer overlay) gets distinct
            // geometry entries instead of collapsing onto a single inflate=0 row.
            String factoryKey = res.targetClass() + "#" + res.targetMethod()
                + (res.defaultInflate() != 0f ? "#inflate=" + res.defaultInflate() : "")
                + (res.defaultFloatParam() != null ? "#fparam=" + res.defaultFloatParam() : "")
                + (res.appliedMeshTransformerScale() != 1f ? "#appliedMT=" + res.appliedMeshTransformerScale() : "");
            String geometryId = factoryKeyToGeometryId.computeIfAbsent(factoryKey, k -> {
                String simple = res.targetClass().substring(res.targetClass().lastIndexOf('/') + 1);
                String entityName = stripModelSuffix(simple).toLowerCase(Locale.ROOT);
                String candidate = "geometry." + entityName;
                int collision = 0;
                while (geometriesOut.has(candidate)) {
                    collision++;
                    candidate = "geometry." + entityName + "_" + collision;
                }
                return candidate;
            });
            if (!geometriesOut.has(geometryId)) geometriesOut.add(geometryId, entry.getValue());
        }

        JsonObject geometryRoot = new JsonObject();
        geometryRoot.addProperty("//", "Generated by ToolingEntityModels. Per-geometry bone/cube tree from Java client jar bytecode, deduplicated by factory class+method. Frame is vanilla Java's natural Y-DOWN.");
        geometryRoot.add("geometries", geometriesOut);
        Files.createDirectories(GEOMETRY_OUTPUT.getParent());
        Files.writeString(
            GEOMETRY_OUTPUT,
            new GsonBuilder().setPrettyPrinting().create().toJson(geometryRoot) + System.lineSeparator()
        );

        JsonObject entitiesOut = new JsonObject();
        int variantRows = 0;
        for (Map.Entry<String, EntitySessionWalk.Result> entry : records.entrySet()) {
            String entityId = entry.getKey();
            EntitySessionWalk.Result rec = entry.getValue();
            EntityLayerDefinitionResolver.Result res = entityToResolution.get(entityId);
            String geometryId = res == null ? null : factoryKeyToGeometryId.get(
                res.targetClass() + "#" + res.targetMethod()
                + (res.defaultInflate() != 0f ? "#inflate=" + res.defaultInflate() : "")
                + (res.defaultFloatParam() != null ? "#fparam=" + res.defaultFloatParam() : "")
                + (res.appliedMeshTransformerScale() != 1f ? "#appliedMT=" + res.appliedMeshTransformerScale() : ""));
            if (geometryId == null) continue;

            JsonObject row = new JsonObject();
            row.addProperty("geometry_ref", geometryId);
            String texture = rec.binding().primaryTexturePath();
            // Variant-driven base entities (cow / pig / chicken / frog / cat / wolf) have no
            // hardcoded primary texture - their renderer reads it from the variant's data-driven
            // asset_id at runtime. Default to the temperate / first variant's texture so the
            // base-entity row still has a sensible texture_ref the renderer can fall back on.
            if (texture == null && rec.variantStem() != null) {
                ConcurrentList<EntityVariantResolver.Result> vlist = variants.get(rec.variantStem());
                if (vlist != null && !vlist.isEmpty()) {
                    // Holder-class DEFAULT (WolfVariants.DEFAULT etc.) wins; if absent (cat),
                    // fall back to the alphabetically-first unconditional variant scanned from
                    // data/minecraft/<stem>_variant/*.json.
                    String canonical = dataVariantDefaults.get(rec.variantStem());
                    if (canonical == null)
                        canonical = EntityVariantResolver.findAlphaFirstUnconditionalVariantId(
                            context, rec.variantStem(), diagnostics);
                    EntityVariantResolver.Result defaultVariant = EntityVariantResolver.pickDefault(vlist, canonical);
                    String def = defaultVariant.primaryTexturePath();
                    if (def != null) texture = def;
                }
            }
            if (texture != null) row.addProperty("texture_ref", EntityTextureResolver.stripPrefix(texture));
            row.addProperty("armor_type", EntityBoneResolver.inferArmorType(rec.layers()));
            // Renderer.scale residue extracted by EntityRendererOverrides. Non-null only when
            // the renderer's scale override contains at least one literal poseStack.scale call
            // AND the product differs from 1.0 - currently wither (2.0) and slime (0.999).
            if (rec.rendererScale() != null) row.addProperty("renderer_scale", rec.rendererScale());

            // Emit overlays (eye layers + composite-model layers like slime outer shell, sheep
            // wool, sheep wool undercoat). Eye overlays carry modelLayerField == null and reuse
            // the base entity's geometry. Composite overlays carry their own ModelLayers.X
            // field; resolving it through the same factoryKey -> geometryId table that primaries
            // use gives the overlay a stable deduped geometry entry.
            ConcurrentList<EntityOverlayResolver.Result> overlays =
                overlaysByEntity.getOrDefault(entityId, Concurrent.newList());
            if (!overlays.isEmpty()) {
                JsonArray overlaysJson = new JsonArray();
                for (EntityOverlayResolver.Result desc : overlays) {
                    String overlayGeometryId = geometryId;
                    if (desc.modelLayerField() != null) {
                        EntityLayerDefinitionResolver.Result overlayRes =
                            overlayFieldToResolution.get(desc.modelLayerField());
                        if (overlayRes == null) continue;
                        String overlayFactoryKey = overlayRes.targetClass() + "#" + overlayRes.targetMethod()
                            + (overlayRes.defaultInflate() != 0f ? "#inflate=" + overlayRes.defaultInflate() : "")
                            + (overlayRes.defaultFloatParam() != null ? "#fparam=" + overlayRes.defaultFloatParam() : "")
                            + (overlayRes.appliedMeshTransformerScale() != 1f ? "#appliedMT=" + overlayRes.appliedMeshTransformerScale() : "");
                        overlayGeometryId = factoryKeyToGeometryId.get(overlayFactoryKey);
                        if (overlayGeometryId == null) continue;
                    }
                    JsonObject overlay = new JsonObject();
                    overlay.addProperty("geometry_ref", overlayGeometryId);
                    overlay.addProperty("texture_ref", EntityTextureResolver.stripPrefix(desc.texturePath()));
                    if (desc.emissive()) overlay.addProperty("emissive", true);
                    if (desc.tintArgb() != 0xFFFFFFFF)
                        overlay.addProperty("tint_color", String.format("0x%08X", desc.tintArgb()));
                    // Overlays sharing the base geometry need a microscopic outward inflate to
                    // clear ModelEngine's equal-Z depth-fail. Without it the overlay lands on
                    // the same depth as the lit skin texel and never wins.
                    boolean sharesBaseGeometry = desc.modelLayerField() == null;
                    if (desc.inflate() != 0f)
                        overlay.addProperty("inflate", desc.inflate());
                    else if (sharesBaseGeometry)
                        overlay.addProperty("inflate", 0.001f);
                    if (desc.skipBounds())
                        overlay.addProperty("skip_bounds", true);
                    overlaysJson.add(overlay);
                }
                if (!overlaysJson.isEmpty()) row.add("overlays", overlaysJson);
            }

            // Block-model overlays - mooshroom mushrooms, iron-golem flower, enderman carried block.
            ConcurrentList<EntityBlockOverlayResolver.Result> blockOverlayDescs =
                blockOverlaysByEntity.getOrDefault(entityId, Concurrent.newList());
            if (!blockOverlayDescs.isEmpty()) row.add("block_overlays", EntityBlockOverlayResolver.toJson(blockOverlayDescs));

            // setup_yaw_addend - only ShulkerRenderer surfaces a non-zero value (+180F).
            if (rec.setupYawAddend() != 0f) row.addProperty("setup_yaw_addend", rec.setupYawAddend());

            // base_tint - per-entity multiplicative tint; currently only TropicalFishRenderer surfaces a non-default.
            int baseTint = EntityOverlayResolver.resolveBaseTint(context.classNodes(), rec.rendererInternalName());
            if (baseTint != 0xFFFFFFFF) row.addProperty("base_tint", String.format("0x%08X", baseTint));

            // Constructor-static visibility - armor_stand / illager hat hides plus the chest-bone
            // gates on AbstractChestedHorse subclasses.
            ConcurrentList<String> hiddenBones = EntityBoneResolver.resolveHiddenBones(context.classNodes(), res.targetClass(), rec.rendererInternalName(), diagnostics);
            if (!hiddenBones.isEmpty()) {
                JsonArray hidden = new JsonArray();
                for (String bone : hiddenBones) hidden.add(bone);
                row.add("hidden_bones", hidden);
            }

            entitiesOut.add(entityId, row);

            // Variant rows for data-driven variants only. Skip the default (temperate) since it
            // IS the base entity. Skip overlay-state variants (creeper_charged, sheep_sheared)
            // - those need RenderLayer extraction the resolver doesn't surface yet.
            if (rec.variantStem() == null) continue;
            ConcurrentList<EntityVariantResolver.Result> variantList = variants.get(rec.variantStem());
            if (variantList == null) continue;
            for (EntityVariantResolver.Result variant : variantList) {
                if (DEFAULT_VARIANT_ID.equals(variant.variantId())) continue;
                String variantPrimary = variant.primaryTexturePath();
                if (variantPrimary == null) continue;
                String variantEntityId = entityId + "_" + variant.variantId();
                String variantGeometryId = geometryId;
                EntityLayerDefinitionResolver.Result variantRes = entityToResolution.get(variantEntityId);
                if (variantRes != null) {
                    String variantFactoryKey = variantRes.targetClass() + "#" + variantRes.targetMethod()
                        + (variantRes.defaultInflate() != 0f ? "#inflate=" + variantRes.defaultInflate() : "")
                        + (variantRes.defaultFloatParam() != null ? "#fparam=" + variantRes.defaultFloatParam() : "")
                        + (variantRes.appliedMeshTransformerScale() != 1f ? "#appliedMT=" + variantRes.appliedMeshTransformerScale() : "");
                    String resolvedVariant = factoryKeyToGeometryId.get(variantFactoryKey);
                    if (resolvedVariant != null) variantGeometryId = resolvedVariant;
                }
                JsonObject variantRow = new JsonObject();
                variantRow.addProperty("geometry_ref", variantGeometryId);
                variantRow.addProperty("texture_ref", EntityTextureResolver.stripPrefix(variantPrimary));
                variantRow.addProperty("armor_type", row.get("armor_type").getAsString());
                variantRow.addProperty("variant_of", entityId);
                entitiesOut.add(variantEntityId, variantRow);
                variantRows++;
            }
        }
        diagnostics.info("entity_models.json: %d base entities + %d variant rows", entitiesOut.size() - variantRows, variantRows);

        JsonObject modelsRoot = new JsonObject();
        modelsRoot.addProperty("//", "Generated by ToolingEntityModels. Per-entity metadata pointing at entity_geometry.json. Variant rows (cow_cold, pig_warm, ...) emitted from data/minecraft/X_variant/ tables.");
        modelsRoot.add("entities", entitiesOut);
        // Cross-entity families derived from shared geometry_ref. variant_of on each entity row
        // covers variant-of-same-entity groupings (cow_cold -> cow). The families table handles
        // non-variant entities that share a primary createBodyLayer factory (mooshroom and cow
        // both bake CowModel.createBodyLayer -> both end up at geometry.cow).
        JsonObject familiesOut = deriveFamilies(entitiesOut, diagnostics);
        if (familiesOut.size() > 0) modelsRoot.add("families", familiesOut);
        Files.createDirectories(MODELS_OUTPUT.getParent());
        Files.writeString(
            MODELS_OUTPUT,
            new GsonBuilder().setPrettyPrinting().create().toJson(modelsRoot) + System.lineSeparator()
        );
        return variantRows;
    }

    /**
     * Strips trailing {@code "Model"} from a class simple name; falls back to the input on no
     * match. Local to the geometry-id derivation.
     */
    private static @NotNull String stripModelSuffix(@NotNull String simpleName) {
        return simpleName.endsWith("Model") ? simpleName.substring(0, simpleName.length() - "Model".length()) : simpleName;
    }

    // ----------------------------------------------------------------------------------------
    // Cross-entity family derivation
    // ----------------------------------------------------------------------------------------
    //
    // Pairs sibling entities that share a `geometry_ref` with a canonical root entity (the
    // member whose id, after the `minecraft:` namespace strip, matches the geometry stem
    // after the `geometry.` prefix and any GEOMETRY_NAME_PREFIXES strip). Example vanilla
    // pairings:
    // - geometry.cow shared by (cow, mooshroom) -> root is cow
    // - geometry.adultcamel shared by (camel, camel_husk) -> root is camel (after stripping "adult")
    // - geometry.illager shared by (evoker, illusioner, pillager, vindicator) -> no member id
    //   matches the stem, so no family is emitted
    //
    // The "id matches geometry stem" rule cleanly separates the wanted families (where the
    // geometry was authored for one canonical entity and re-used by a derivative) from the
    // coincidence families (where multiple sibling entities share a generic-named model).
    // Variant rows (variant_of present) are pre-filtered because they're already grouped
    // under their declared root via the per-entity variant_of field.

    /**
     * Common geometry-name prefixes that don't appear in the entity id.
     * {@code geometry.adultcamel} pairs with {@code minecraft:camel}; the resolver strips
     * these prefixes before matching the geometry stem against entity ids.
     */
    private static final @NotNull List<String> GEOMETRY_NAME_PREFIXES = List.of("adult", "baby");

    /**
     * Builds the families JSON object by clustering non-variant entities that share a
     * {@code geometry_ref} and selecting the canonical root per cluster.
     *
     * @param entitiesOut the {@code entities} JSON object from {@code entity_models.json}
     *     (one row per entity-id; rows with {@code variant_of} are skipped)
     * @param diagnostics the diagnostic sink; emits one {@code INFO} line per resolved or
     *     skipped family
     * @return a JSON object mapping each non-root sibling to its family root; empty when no
     *     family resolves
     */
    private static @NotNull JsonObject deriveFamilies(@NotNull JsonObject entitiesOut, @NotNull Diagnostics diagnostics) {
        Map<String, List<String>> geometryToBaseEntities = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : entitiesOut.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject row = entry.getValue().getAsJsonObject();
            if (row.has("variant_of")) continue;
            if (!row.has("geometry_ref")) continue;
            String geomRef = row.get("geometry_ref").getAsString();
            geometryToBaseEntities.computeIfAbsent(geomRef, k -> new ArrayList<>()).add(entry.getKey());
        }

        JsonObject families = new JsonObject();
        for (Map.Entry<String, List<String>> e : geometryToBaseEntities.entrySet()) {
            List<String> members = e.getValue();
            if (members.size() < 2) continue;
            String root = pickCanonicalFamilyRoot(e.getKey(), members);
            if (root == null) {
                diagnostics.info("cross-entity family skipped: '%s' shared by %s - no member id matches the geometry stem",
                    e.getKey(), members);
                continue;
            }
            for (String member : members) {
                if (member.equals(root)) continue;
                families.addProperty(member, root);
                diagnostics.info("cross-entity family: %s -> %s (shared %s)", member, root, e.getKey());
            }
        }
        return families;
    }

    /**
     * Returns the family root by matching the geometry stem (after stripping the
     * {@code geometry.} prefix and any {@link #GEOMETRY_NAME_PREFIXES} prefix) against each
     * candidate entity id (after the {@code minecraft:} namespace strip).
     */
    private static @Nullable String pickCanonicalFamilyRoot(@NotNull String geometryRef, @NotNull List<String> members) {
        String stem = geometryRef.startsWith("geometry.") ? geometryRef.substring("geometry.".length()) : geometryRef;
        for (String prefix : GEOMETRY_NAME_PREFIXES) {
            if (stem.startsWith(prefix)) stem = stem.substring(prefix.length());
        }
        String targetId = VanillaSourcePaths.MINECRAFT_NAMESPACE + stem;
        for (String member : members)
            if (member.equals(targetId)) return member;
        return null;
    }

}
