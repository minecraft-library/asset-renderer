package lib.minecraft.renderer.tooling.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.ToolingJson;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Emits the runtime-consumable {@code entity_models.json} and {@code entity_geometry.json}.
 * The geometry file deduplicates by factory class+method so multiple entities sharing one
 * {@code createBodyLayer} (e.g. zombie / husk / drowned all point at the same
 * {@code AbstractZombieRenderer} chain) share one geometry entry. The models file emits one
 * row per discovered entity plus additional rows per non-default data-driven variant
 * (cow_cold / cow_warm / pig_cold / etc.), each carrying {@code variant_of} pointing back at
 * its base entity.
 *
 * <p>Internally the writer is a single sequential method ({@link #writeAll}): build the
 * {@code factoryKey -> geometryId} dedupe map, write {@code entity_geometry.json}, walk each
 * entity record emitting a row (including overlay rows, block-overlay rows, base tint, hidden
 * bones), emit variant rows for data-driven variants, then write {@code entity_models.json}
 * with the families table from {@link #deriveFamilies}.
 *
 * <p>The emitted field names are the runtime schema contract read back by
 * {@code EntityModelLoader}; see {@link #writeAll} for the per-row field list.
 */
@UtilityClass
public final class EntityRuntimeJsonWriter {

    /**
     * Outcome of {@link #writeAll}: the flat entity + cross-entity family tables (built in-memory
     * for {@code EntityFamilyJsonWriter} to group into {@code entity_models.json}), plus the
     * {@code factoryKey -> geometry id} dedupe map so callers (baby-geometry enumeration) can
     * resolve the geometry id a given {@link EntityLayerDefinitionResolver.Result} baked to.
     *
     * @param variantRows the number of data-driven variant rows emitted (base rows excluded)
     * @param factoryKeyToGeometryId the {@code targetClass#targetMethod#...} key -&gt; deduped
     *     {@code geometry.X} id map used across the geometry emission
     * @param flatEntities the flat {@code entities} object (id -&gt; row), the grouping input
     * @param flatFamilies the cross-entity {@code families} table (derivative id -&gt; family root)
     */
    public record WriteResult(
        int variantRows,
        @NotNull Map<String, String> factoryKeyToGeometryId,
        @NotNull JsonObject flatEntities,
        @NotNull JsonObject flatFamilies
    ) {}

    /**
     * Builds the deduplication key for a resolved layer factory - the same key the geometry table
     * is deduped on ({@code targetClass#targetMethod} plus inflate / float-param / MeshTransformer
     * scale discriminators). Public so the baby-geometry enumeration can look a resolution's geometry
     * id up in {@link WriteResult#factoryKeyToGeometryId}.
     *
     * @param res the resolved layer factory
     * @return the dedupe key
     */
    public static @NotNull String factoryKey(@NotNull EntityLayerDefinitionResolver.Result res) {
        return res.targetClass() + "#" + res.targetMethod()
            + (res.defaultInflate() != 0f ? "#inflate=" + res.defaultInflate() : "")
            + (res.defaultFloatParam() != null ? "#fparam=" + res.defaultFloatParam() : "")
            + (res.appliedMeshTransformerScale() != 1f ? "#appliedMT=" + res.appliedMeshTransformerScale() : "");
    }

    /**
     * Runtime-consumable per-geometry bone/cube data path; one geometry entry per unique factory
     * method, deduplicated when multiple entities share the same {@code createBodyLayer}.
     */
    public static final @NotNull Path GEOMETRY_OUTPUT =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json");

    /**
     * Emits both runtime JSON files and returns the number of variant rows written (in addition
     * to base-entity rows) for the caller's summary line.
     *
     * <p>Each entity row in {@code entity_models.json} carries the schema fields read back by
     * {@code EntityModelLoader}:
     * <ul>
     *   <li><b>{@code geometry_ref}</b> - the deduped {@code geometry.X} key into
     *       {@code entity_geometry.json}.</li>
     *   <li><b>{@code texture_ref}</b> - the primary texture path (prefix-stripped); for
     *       variant-driven base entities without a hardcoded texture, the canonical default
     *       variant's texture is substituted.</li>
     *   <li><b>{@code armor_type}</b> - inferred from the render layers.</li>
     *   <li><b>{@code renderer_scale}</b> - the {@code scale} override product when it differs
     *       from {@code 1.0} (wither {@code 2.0}, slime {@code 0.999}).</li>
     *   <li><b>{@code overlays}</b> - eye / composite overlay rows (each with its own
     *       {@code geometry_ref}, {@code texture_ref}, and optional {@code emissive} /
     *       {@code tint_color} / {@code inflate} / {@code skip_bounds}).</li>
     *   <li><b>{@code block_overlays}</b> - block-model overlays (mooshroom mushrooms, iron-golem
     *       flower, enderman carried block).</li>
     *   <li><b>{@code setup_yaw_addend}</b> - non-zero only for {@code ShulkerRenderer}.</li>
     *   <li><b>{@code base_tint}</b> - per-entity multiplicative tint (non-default only for
     *       {@code TropicalFishRenderer}).</li>
     *   <li><b>{@code hidden_bones}</b> - constructor-static bone-visibility hides.</li>
     *   <li><b>{@code variant_of}</b> - present only on data-driven variant rows, pointing back
     *       at the base entity id.</li>
     * </ul>
     *
     * @param context the tooling context (jar + diagnostics + ClassNode cache)
     * @param records discovered entity id -&gt; session-walk record
     * @param entityToResolution entity id -&gt; resolved layer-definition (factory class/method,
     *     inflate, MeshTransformer scale)
     * @param geometries entity id -&gt; per-geometry bone/cube JSON, deduped by factory key
     * @param variants variant stem -&gt; ordered data-driven variant list
     * @param diagnostics the diagnostic sink for summary lines
     * @param overlaysByEntity entity id -&gt; eye / composite overlay descriptors
     * @param overlayFieldToResolution overlay {@code ModelLayers} field -&gt; resolved
     *     layer-definition for composite overlays that carry their own geometry
     * @param dataVariantDefaults variant stem -&gt; canonical default variant id (from
     *     {@code <X>Variants.DEFAULT})
     * @param blockOverlaysByEntity entity id -&gt; block-model overlay descriptors
     * @return the emitted variant-row count plus the factory-key -&gt; geometry-id dedupe map
     * @throws IOException when either output file cannot be written
     */
    public static WriteResult writeAll(
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
            String factoryKey = factoryKey(res);
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

        ToolingJson.writeResource(GEOMETRY_OUTPUT,
            "Generated by ToolingEntityModels. Per-geometry bone/cube tree from Java client jar bytecode, deduplicated by factory class+method. Frame is vanilla Java's natural Y-DOWN.",
            context.options().getVersion(), "geometries", geometriesOut);

        JsonObject entitiesOut = new JsonObject();
        int variantRows = 0;
        for (Map.Entry<String, EntitySessionWalk.Result> entry : records.entrySet()) {
            String entityId = entry.getKey();
            EntitySessionWalk.Result rec = entry.getValue();
            EntityLayerDefinitionResolver.Result res = entityToResolution.get(entityId);
            String geometryId = res == null ? null : factoryKeyToGeometryId.get(factoryKey(res));
            if (geometryId == null) continue;

            JsonObject row = new JsonObject();
            row.addProperty("geometry_ref", geometryId);

            // Variant-registry entities (cow / pig / chicken / frog / wolf / cat / zombie_nautilus)
            // have no single canonical id in vanilla: the client renders EVERY variant under its own
            // {@code <id>_<variant>} id (cow_temperate / cow_cold / ...) and emits NO plain {@code <id>}
            // render. Resolve the canonical DEFAULT variant id here - Holder-class DEFAULT
            // (WolfVariants.DEFAULT etc.) wins; if absent (cat), the alphabetically-first unconditional
            // variant from {@code data/minecraft/<stem>_variant/}. The base row is named
            // {@code <id>_<canonicalVariant>} below so it lines up 1:1 with the harness reference PNGs
            // (and the non-default variants roll up to it via {@code variant_of}); a null canonical
            // (non-variant entity, or an unresolvable default) falls back to the plain id.
            String canonicalVariant = null;
            if (rec.variantStem() != null) {
                ConcurrentList<EntityVariantResolver.Result> vlist = variants.get(rec.variantStem());
                if (vlist != null && !vlist.isEmpty()) {
                    canonicalVariant = dataVariantDefaults.get(rec.variantStem());
                    if (canonicalVariant == null)
                        canonicalVariant = EntityVariantResolver.findAlphaFirstUnconditionalVariantId(
                            context, rec.variantStem(), diagnostics);
                }
            }

            String texture = rec.binding().primaryTexturePath();
            // Variant-driven base entities have no hardcoded primary texture - their renderer reads it
            // from the variant's data-driven asset_id at runtime. Default to the canonical variant's
            // texture so the base row carries the right texture_ref.
            if (texture == null && canonicalVariant != null) {
                ConcurrentList<EntityVariantResolver.Result> vlist = variants.get(rec.variantStem());
                if (vlist != null && !vlist.isEmpty()) {
                    EntityVariantResolver.Result defaultVariant = EntityVariantResolver.pickDefault(vlist, canonicalVariant);
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
                        String overlayFactoryKey = factoryKey(overlayRes);
                        overlayGeometryId = factoryKeyToGeometryId.get(overlayFactoryKey);
                        if (overlayGeometryId == null) continue;
                    }
                    JsonObject overlay = new JsonObject();
                    overlay.addProperty("geometry_ref", overlayGeometryId);
                    overlay.addProperty("texture_ref", EntityTextureResolver.stripPrefix(desc.texturePath()));
                    if (desc.emissive()) overlay.addProperty("emissive", true);
                    if (desc.tintArgb() != 0xFFFFFFFF)
                        overlay.addProperty("tint_color", String.format("0x%08X", desc.tintArgb()));
                    // tint_by names the render axis whose selected colour overrides tint_color at
                    // render (sheep wool: "wool_color"); the baked tint_color stays the default.
                    if (desc.tintBy() != null)
                        overlay.addProperty("tint_by", desc.tintBy());
                    // shearable overlays (sheep wool) drop when the sheared render axis is set.
                    if (desc.shearable())
                        overlay.addProperty("shearable", true);
                    // requires_tint overlays (sheep wool undercoat) only render once a tint_by colour
                    // is selected - skipped for the default entity so it stays byte-identical.
                    if (desc.requiresTint())
                        overlay.addProperty("requires_tint", true);
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

            // bone_toggles - the subset of the hidden bones that a render option can un-hide
            // (donkey/mule/llama chest, gated on state.hasChest). Additive metadata: the same bones
            // stay stripped by hidden_bones above, so the default render is unchanged.
            Map<String, EntityBoneResolver.BoneToggle> boneToggles = EntityBoneResolver.resolveBoneToggles(context.classNodes(), res.targetClass(), diagnostics);
            if (!boneToggles.isEmpty()) {
                JsonObject geometryEntry = geometriesOut.getAsJsonObject(geometryId);
                JsonObject geometryBones = geometryEntry == null ? null : geometryEntry.getAsJsonObject("bones");
                JsonObject togglesJson = new JsonObject();
                for (Map.Entry<String, EntityBoneResolver.BoneToggle> toggle : boneToggles.entrySet()) {
                    JsonArray bones = new JsonArray();
                    for (String bone : expandToggleSubtree(geometryBones, toggle.getValue().bones())) bones.add(bone);
                    JsonObject toggleObj = new JsonObject();
                    toggleObj.add("bones", bones);
                    toggleObj.addProperty("default", toggle.getValue().defaultVisible());
                    togglesJson.add(toggle.getKey(), toggleObj);
                }
                row.add("bone_toggles", togglesJson);
            }

            // Variant-registry entities emit the base row under {@code <id>_<canonicalVariant>}
            // (matching the harness's per-variant reference naming, e.g. cow_temperate / wolf_pale),
            // with the non-default variants rolling up to it via {@code variant_of}. There is NO plain
            // {@code <id>} row for these - vanilla renders none. Non-variant entities keep their plain id.
            String baseId = canonicalVariant != null ? entityId + "_" + canonicalVariant : entityId;
            entitiesOut.add(baseId, row);

            // Variant rows for data-driven variants only. Skip the canonical variant (it IS the base
            // row above). Skip overlay-state variants (creeper_charged, sheep_sheared) - those need
            // RenderLayer extraction the resolver doesn't surface yet.
            if (rec.variantStem() == null) continue;
            ConcurrentList<EntityVariantResolver.Result> variantList = variants.get(rec.variantStem());
            if (variantList == null) continue;
            for (EntityVariantResolver.Result variant : variantList) {
                if (variant.variantId().equals(canonicalVariant)) continue;
                String variantPrimary = variant.primaryTexturePath();
                if (variantPrimary == null) continue;
                String variantEntityId = entityId + "_" + variant.variantId();
                String variantGeometryId = geometryId;
                EntityLayerDefinitionResolver.Result variantRes = entityToResolution.get(variantEntityId);
                if (variantRes != null) {
                    String variantFactoryKey = factoryKey(variantRes);
                    String resolvedVariant = factoryKeyToGeometryId.get(variantFactoryKey);
                    if (resolvedVariant != null) variantGeometryId = resolvedVariant;
                }
                JsonObject variantRow = new JsonObject();
                variantRow.addProperty("geometry_ref", variantGeometryId);
                variantRow.addProperty("texture_ref", EntityTextureResolver.stripPrefix(variantPrimary));
                variantRow.addProperty("armor_type", row.get("armor_type").getAsString());
                variantRow.addProperty("variant_of", baseId);
                entitiesOut.add(variantEntityId, variantRow);
                variantRows++;
            }
        }
        diagnostics.info("flat entities: %d base + %d variant rows", entitiesOut.size() - variantRows, variantRows);

        // The flat entities + cross-entity families are returned in-memory rather than written:
        // EntityFamilyJsonWriter groups them into the normalized entity_models.json (family form).
        // Cross-entity families are derived from shared geometry_ref; variant_of on each entity row
        // covers variant-of-same-entity groupings (cow_cold -> cow), while the families table handles
        // non-variant entities that share a primary createBodyLayer factory (mooshroom and cow both
        // bake CowModel.createBodyLayer -> both end up at geometry.cow).
        JsonObject familiesOut = deriveFamilies(entitiesOut, diagnostics);
        return new WriteResult(variantRows, factoryKeyToGeometryId, entitiesOut, familiesOut);
    }

    /**
     * Strips trailing {@code "Model"} from a class simple name; falls back to the input on no
     * match. Local to the geometry-id derivation.
     */
    private static @NotNull String stripModelSuffix(@NotNull String simpleName) {
        return simpleName.endsWith("Model") ? simpleName.substring(0, simpleName.length() - "Model".length()) : simpleName;
    }

    /**
     * Expands a bone-toggle's directly-named bones to the full subtree they root, so flipping a
     * group bone (bogged's {@code mushrooms}) takes its children with it: the geometry parents the
     * six mushroom cubes to the group, and stripping only the group would orphan them to the model
     * root. Every geometry bone whose parent-chain reaches a named bone joins the list, in geometry
     * (insertion) order after the seeds. Leaf toggle bones (goat horns, donkey/mule/llama chest)
     * root no subtree, so the expansion returns them unchanged - the existing toggles stay
     * byte-identical.
     *
     * @param geometryBones the entity geometry's {@code name -> bone} object (each bone's optional
     *     {@code parent} names its parent), or {@code null} when the geometry is unavailable
     * @param seeds the toggle's directly-named bones
     * @return the seeds plus every descendant bone, deduplicated, seeds first
     */
    private static @NotNull List<String> expandToggleSubtree(@Nullable JsonObject geometryBones, @NotNull List<String> seeds) {
        LinkedHashSet<String> members = new LinkedHashSet<>(seeds);
        if (geometryBones == null) return new ArrayList<>(members);
        // Fixpoint over the bone map so a subtree of any depth fully closes regardless of the
        // parent-before-child ordering in the geometry JSON.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, JsonElement> entry : geometryBones.entrySet()) {
                if (members.contains(entry.getKey()) || !entry.getValue().isJsonObject()) continue;
                JsonElement parent = entry.getValue().getAsJsonObject().get("parent");
                if (parent != null && parent.isJsonPrimitive() && members.contains(parent.getAsString())) {
                    members.add(entry.getKey());
                    grew = true;
                }
            }
        }
        return new ArrayList<>(members);
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
     * <p>Package-private (rather than private) so {@code EntityRuntimeJsonWriterTest} can
     * exercise the pure JSON-in / JSON-out clustering in isolation without standing up
     * {@link #writeAll}'s full record + geometry + variant fixture surface.
     *
     * @param entitiesOut the {@code entities} JSON object from {@code entity_models.json}
     *     (one row per entity-id; rows with {@code variant_of} are skipped)
     * @param diagnostics the diagnostic sink; emits one {@code INFO} line per resolved or
     *     skipped family
     * @return a JSON object mapping each non-root sibling to its family root; empty when no
     *     family resolves
     */
    static @NotNull JsonObject deriveFamilies(@NotNull JsonObject entitiesOut, @NotNull Diagnostics diagnostics) {
        // Variant-registry bases (the target of some {@code variant_of}, e.g. cow_temperate,
        // zombie_nautilus_temperate) head their own variant family - the harness groups an
        // entity's variants under a single EntityType root. Such a base must NOT be absorbed as a
        // NON-ROOT member of another entity's shared-geometry family: zombie_nautilus_temperate
        // shares geometry.nautilus with the live nautilus, but the harness keeps zombie_nautilus
        // (temperate + warm) separate from nautilus. Absorbing temperate into nautilus's family
        // splits the asset grouping ({nautilus, temperate} + {warm}) so the coral variant's larger
        // canvas never reaches temperate, and temperate mis-sizes vs the harness's coral-union
        // canvas. Variant-registry bases stay eligible as family ROOTS (mooshroom -> cow_temperate
        // is unaffected); they're only barred from non-root membership.
        Set<String> variantRoots = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : entitiesOut.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject row = entry.getValue().getAsJsonObject();
            if (row.has("variant_of")) variantRoots.add(row.get("variant_of").getAsString());
        }

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
                if (variantRoots.contains(member)) {
                    diagnostics.info("cross-entity family: variant-registry base '%s' kept out of '%s' family (heads its own variant family)",
                        member, root);
                    continue;
                }
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
        String root = matchFamilyRoot(stem, members);
        if (root != null) return root;
        // Collision-suffixed geometry ids hide the canonical stem: when another factory claims
        // {@code geometry.skeleton} first, the shared skeleton mesh becomes {@code geometry.skeleton_1},
        // whose stem ({@code skeleton_1}) matches no member. Retry after dropping a trailing {@code _<n>}
        // collision suffix so the shared mesh still resolves to its canonical entity root, independent of
        // which factory won the unsuffixed id (the geometry table is insertion-ordered, so that is stable
        // but need not favour the canonical entity).
        String destemmed = stripCollisionSuffix(stem);
        return destemmed.equals(stem) ? null : matchFamilyRoot(destemmed, members);
    }

    /**
     * Matches a geometry {@code stem} against the family members: an exact {@code minecraft:<stem>}
     * plain-id root (mooshroom -&gt; cow), else a {@code minecraft:<stem>_<variant>} variant base
     * (geometry.cow -&gt; cow_temperate, since variant-registry entities have no plain {@code <id>}
     * row and the non-canonical variants are already filtered out of {@code members}).
     *
     * @param stem the geometry stem (after the {@code geometry.} + name-prefix strip)
     * @param members the entities sharing the geometry
     * @return the matched canonical root member, or {@code null} when none matches
     */
    private static @Nullable String matchFamilyRoot(@NotNull String stem, @NotNull List<String> members) {
        String targetId = VanillaSourcePaths.MINECRAFT_NAMESPACE + stem;
        for (String member : members)
            if (member.equals(targetId)) return member;
        for (String member : members)
            if (member.startsWith(targetId + "_")) return member;
        return null;
    }

    /**
     * Strips a trailing {@code _<digits>} collision suffix (the disambiguator the geometry-id
     * assignment appends when two factories derive the same class stem), or returns {@code stem}
     * unchanged when it carries none.
     *
     * @param stem the geometry stem to de-suffix
     * @return the stem without its trailing {@code _<n>} collision suffix
     */
    private static @NotNull String stripCollisionSuffix(@NotNull String stem) {
        int underscore = stem.lastIndexOf('_');
        if (underscore <= 0 || underscore == stem.length() - 1) return stem;
        for (int i = underscore + 1; i < stem.length(); i++)
            if (!Character.isDigit(stem.charAt(i))) return stem;
        return stem.substring(0, underscore);
    }

}
