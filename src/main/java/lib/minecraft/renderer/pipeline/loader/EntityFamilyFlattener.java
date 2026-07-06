package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens the normalized {@code entity_models.json} family form back into the flat
 * {@code entities} + {@code families} shape {@link EntityModelLoader} consumes.
 *
 * <p>This is the exact inverse of {@code EntityFamilyJsonWriter.group}: each id-encoded
 * {@code variant} axis re-expands into one flat row per option ({@code minecraft:<id>_<opt>},
 * the default option carrying the family's overlays / tints / hidden-bones and no
 * {@code variant_of}, the rest pointing back at it), and each per-family {@code family_of} link
 * re-collects into the cross-entity {@code families} table. Carried values are copied as verbatim
 * {@code JsonElement} deep-copies, so no number or string is ever reformatted. Keep the copied
 * field list here in step with {@code EntityFamilyJsonWriter}'s; {@code EntityFamilyFlattenerTest}
 * pins the expansion.
 */
public final class EntityFamilyFlattener {

    /**
     * Optional base fields carried verbatim between a flat row and its family entry. Must match
     * {@code EntityFamilyJsonWriter}'s copied-field list.
     */
    private static final @NotNull List<String> CARRIED_FIELDS =
        List.of("renderer_scale", "setup_yaw_addend", "base_tint", "hidden_bones", "bone_toggles", "overlays", "block_overlays");

    private EntityFamilyFlattener() {
    }

    /**
     * The reconstructed flat form: the {@code entities} object (one row per {@code minecraft:<id>}
     * / {@code minecraft:<id>_<variant>}), the cross-entity {@code families} table, and the
     * option-axis side-channels (state textures, collar textures, baby geometry) kept out of
     * {@code entities} so the flat rows stay minimal.
     *
     * @param entities the flat per-entity rows keyed by namespaced id
     * @param families the cross-entity grouping table (derivative id -&gt; family root)
     * @param stateTextures per-row alternate base textures keyed by behavioural state (wolf
     *     {@code wild}/{@code tame}/{@code angry}); only multi-state variant rows appear
     * @param sizeAlternatives base entity id -&gt; ({@code size} option -&gt; alternate mesh geometry ref)
     *     from the {@code size} axis (pufferfish small / medium); the renderer swaps to these when the
     *     {@code size} axis selects a non-default mesh
     */
    public record Flat(
        @NotNull JsonObject entities,
        @NotNull JsonObject families,
        @NotNull Map<String, Map<String, String>> stateTextures,
        @NotNull Map<String, String> collarTextures,
        @NotNull Map<String, String> babyGeometry,
        @NotNull Map<String, List<EquipmentSpec>> equipment,
        @NotNull Map<String, ShapeSpec> shapeAlternatives,
        @NotNull Map<String, Map<String, String>> sizeAlternatives
    ) {

        /**
         * The canonical empty flat form - no entities, families, or option side-channels. Returned
         * for a legitimately-absent models resource (missing classpath entry, or no {@code families}
         * key), as distinct from a malformed resource, which throws.
         *
         * @return an empty flat form
         */
        public static @NotNull Flat empty() {
            return new Flat(new JsonObject(), new JsonObject(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * The non-default {@code large} option of a family's {@code shape} axis (tropical fish): the large
     * body geometry, its base texture, and the pattern overlays cloned onto the large mesh. The
     * renderer swaps to these when the selected pattern's {@code Shape} is large.
     *
     * @param geometryRef the large body geometry id ({@code geometry.tropicalfishlarge})
     * @param textureRef the large body base texture ({@code fish/tropical_b})
     * @param overlays the pattern overlays on the large mesh, in the same JSON shape as a family's
     *     {@code overlays} array (materialised by {@code EntityModelLoader.loadOverlays})
     */
    public record ShapeSpec(
        @NotNull String geometryRef,
        @NotNull String textureRef,
        @NotNull JsonArray overlays
    ) {}

    /**
     * One equipment overlay layer read from a family's {@code layers} (a {@code when.equipment}-gated
     * layer): its slot, baked geometry, and the {@code <material>}-templated texture path plus the
     * default material the static icon shows when the equipment axis selects the slot without one.
     *
     * @param slot the equipment slot id the layer is gated on ({@code saddle} / {@code body})
     * @param geometryRef the baked equipment mesh geometry id
     * @param textureTemplate the equipment texture path with a {@code <material>} placeholder
     *     ({@code equipment/pig_saddle/<material>})
     * @param defaultMaterial the material substituted for {@code <material>} when the caller selects
     *     the slot without one ({@code saddle} for a saddle, {@code leather} for body armor)
     */
    public record EquipmentSpec(
        @NotNull String slot,
        @NotNull String geometryRef,
        @NotNull String textureTemplate,
        @NotNull String defaultMaterial
    ) {}

    /**
     * Flattens the family form into the runtime flat shape.
     *
     * @param familyForm the {@code families} object of {@code entity_models.json} (family id -&gt;
     *     family entry)
     * @return the reconstructed flat {@code entities} + {@code families} + option side-channels
     */
    public static @NotNull Flat flatten(@NotNull JsonObject familyForm) {
        JsonObject entities = new JsonObject();
        JsonObject crossFamilies = new JsonObject();
        Map<String, Map<String, String>> stateTextures = new LinkedHashMap<>();
        Map<String, String> collarTextures = new LinkedHashMap<>();
        Map<String, String> babyGeometry = new LinkedHashMap<>();
        Map<String, List<EquipmentSpec>> equipment = new LinkedHashMap<>();
        Map<String, ShapeSpec> shapeAlternatives = new LinkedHashMap<>();
        Map<String, Map<String, String>> sizeAlternatives = new LinkedHashMap<>();

        for (Map.Entry<String, JsonElement> entry : familyForm.entrySet()) {
            String familyId = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject family = entry.getValue().getAsJsonObject();
            String collar = collarTextureOf(family);
            String baby = babyGeometryOf(family);
            List<EquipmentSpec> equip = equipmentOf(family);
            ShapeSpec shape = shapeLargeOf(family);
            Map<String, String> size = sizeAlternativesOf(family);

            JsonObject variantAxis = variantAxis(family);
            if (variantAxis != null) {
                expandVariantFamily(familyId, family, variantAxis, entities, stateTextures, collar, collarTextures, baby, babyGeometry, equip, equipment);
            } else {
                entities.add(familyId, plainRow(family));
                if (collar != null) collarTextures.put(familyId, collar);
                if (baby != null) babyGeometry.put(familyId, baby);
                if (!equip.isEmpty()) equipment.put(familyId, equip);
                if (shape != null) shapeAlternatives.put(familyId, shape);
                if (!size.isEmpty()) sizeAlternatives.put(familyId, size);
                // Plain families carry their single baby texture on age.baby.texture_ref; expose it
                // under the "baby" state key so the renderer binds it the same way as variant families.
                String babyTex = babyTextureOf(family);
                if (babyTex != null) stateTextures.computeIfAbsent(familyId, k -> new LinkedHashMap<>()).put("baby", babyTex);
            }

            if (family.has("family_of")) crossFamilies.addProperty(familyId, family.get("family_of").getAsString());
        }
        return new Flat(entities, crossFamilies, stateTextures, collarTextures, babyGeometry, equipment, shapeAlternatives, sizeAlternatives);
    }

    /**
     * Returns the family's {@code size} axis options as a {@code size option -> alternate mesh
     * geometry ref} map (pufferfish {@code small} / {@code medium}), or an empty map when the family
     * has no {@code size} axis. The default size ({@code large} for pufferfish) is the family's base
     * {@code geometry_ref} and carries no option override, so it is absent from the map.
     */
    private static @NotNull Map<String, String> sizeAlternativesOf(@NotNull JsonObject family) {
        if (!family.has("axes")) return Map.of();
        JsonObject axes = family.getAsJsonObject("axes");
        if (!axes.has("size")) return Map.of();
        JsonObject options = axes.getAsJsonObject("size").getAsJsonObject("options");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> option : options.entrySet()) {
            JsonObject value = option.getValue().getAsJsonObject();
            if (value.has("geometry_ref")) out.put(option.getKey(), value.get("geometry_ref").getAsString());
        }
        return out;
    }

    /**
     * Returns the family's {@code shape.large} option as a {@link ShapeSpec}, or {@code null} when
     * the family has no {@code shape} axis (only tropical fish carries one).
     */
    private static ShapeSpec shapeLargeOf(@NotNull JsonObject family) {
        if (!family.has("axes")) return null;
        JsonObject axes = family.getAsJsonObject("axes");
        if (!axes.has("shape")) return null;
        JsonObject options = axes.getAsJsonObject("shape").getAsJsonObject("options");
        if (!options.has("large")) return null;
        JsonObject large = options.getAsJsonObject("large");
        if (!large.has("geometry_ref")) return null;
        return new ShapeSpec(
            large.get("geometry_ref").getAsString(),
            large.has("texture_ref") ? large.get("texture_ref").getAsString() : "",
            large.has("overlays") ? large.getAsJsonArray("overlays") : new JsonArray());
    }

    /**
     * Returns the family's baby geometry id from its {@code age} axis, or {@code null} when it has
     * no age axis.
     */
    private static String babyGeometryOf(@NotNull JsonObject family) {
        JsonObject baby = ageBaby(family);
        return baby != null && baby.has("geometry_ref") ? baby.get("geometry_ref").getAsString() : null;
    }

    /**
     * Returns the family's single baby texture from its {@code age.baby.texture_ref} (non-variant
     * entities), or {@code null}.
     */
    private static String babyTextureOf(@NotNull JsonObject family) {
        JsonObject baby = ageBaby(family);
        return baby != null && baby.has("texture_ref") ? baby.get("texture_ref").getAsString() : null;
    }

    /**
     * Returns the {@code age.baby} option object, or {@code null} when the family has no age axis.
     */
    private static JsonObject ageBaby(@NotNull JsonObject family) {
        if (!family.has("axes")) return null;
        JsonObject axes = family.getAsJsonObject("axes");
        if (!axes.has("age")) return null;
        JsonObject options = axes.getAsJsonObject("age").getAsJsonObject("options");
        return options.has("baby") ? options.getAsJsonObject("baby") : null;
    }

    /**
     * Returns the dyed-collar layer's texture ref from a family's {@code layers}, or {@code null}
     * when the family has no collar layer.
     */
    private static String collarTextureOf(@NotNull JsonObject family) {
        if (!family.has("layers")) return null;
        for (JsonElement element : family.getAsJsonArray("layers")) {
            JsonObject layer = element.getAsJsonObject();
            if (layer.has("id") && "collar".equals(layer.get("id").getAsString()) && layer.has("overlay")) {
                JsonObject overlay = layer.getAsJsonObject("overlay");
                if (overlay.has("texture_ref")) return overlay.get("texture_ref").getAsString();
            }
        }
        return null;
    }

    /**
     * Returns the equipment overlay specs from a family's {@code layers}: each {@code layer} whose
     * {@code when.equipment} names a slot and whose {@code overlay} carries a {@code geometry_ref} +
     * {@code texture_template} + {@code default_material}. Empty when the family has no equipment layer.
     *
     * @param family the family entry
     * @return the equipment overlay specs, in layer order (empty when none)
     */
    private static @NotNull List<EquipmentSpec> equipmentOf(@NotNull JsonObject family) {
        if (!family.has("layers")) return List.of();
        List<EquipmentSpec> out = new ArrayList<>();
        for (JsonElement element : family.getAsJsonArray("layers")) {
            JsonObject layer = element.getAsJsonObject();
            if (!layer.has("when") || !layer.has("overlay")) continue;
            JsonObject when = layer.getAsJsonObject("when");
            if (!when.has("equipment")) continue;
            JsonObject overlay = layer.getAsJsonObject("overlay");
            if (!overlay.has("geometry_ref") || !overlay.has("texture_template") || !overlay.has("default_material")) continue;
            out.add(new EquipmentSpec(
                when.get("equipment").getAsString(),
                overlay.get("geometry_ref").getAsString(),
                overlay.get("texture_template").getAsString(),
                overlay.get("default_material").getAsString()));
        }
        return out;
    }

    /**
     * Returns the {@code axes.variant} object when the family carries an id-encoded variant axis,
     * else {@code null}.
     */
    private static JsonObject variantAxis(@NotNull JsonObject family) {
        if (!family.has("axes")) return null;
        JsonObject axes = family.getAsJsonObject("axes");
        return axes.has("variant") ? axes.getAsJsonObject("variant") : null;
    }

    /**
     * Expands a variant family into one flat row per option: the default option becomes the base
     * row (carries the family's optional fields, no {@code variant_of}); every other option rolls
     * up to it via {@code variant_of}.
     */
    private static void expandVariantFamily(@NotNull String familyId, @NotNull JsonObject family, @NotNull JsonObject variantAxis, @NotNull JsonObject entities, @NotNull Map<String, Map<String, String>> stateTextures, String collar, @NotNull Map<String, String> collarTextures, String baby, @NotNull Map<String, String> babyGeometry, @NotNull List<EquipmentSpec> equipment, @NotNull Map<String, List<EquipmentSpec>> equipmentByRow) {
        String defaultOption = variantAxis.get("default").getAsString();
        String baseId = familyId + "_" + defaultOption;
        String familyGeometry = family.get("geometry_ref").getAsString();
        String armorType = family.get("armor_type").getAsString();
        JsonObject options = variantAxis.getAsJsonObject("options");

        for (Map.Entry<String, JsonElement> entry : options.entrySet()) {
            String option = entry.getKey();
            JsonObject optionObj = entry.getValue().getAsJsonObject();
            String rowId = familyId + "_" + option;

            JsonObject row = new JsonObject();
            row.addProperty("geometry_ref",
                optionObj.has("geometry_ref") ? optionObj.get("geometry_ref").getAsString() : familyGeometry);
            addTextureRef(row, optionObj);
            row.addProperty("armor_type", armorType);
            if (option.equals(defaultOption)) copyCarriedFields(family, row);
            else row.addProperty("variant_of", baseId);

            entities.add(rowId, row);
            collectStateTextures(rowId, optionObj, stateTextures);
            if (collar != null) collarTextures.put(rowId, collar);
            if (baby != null) babyGeometry.put(rowId, baby);
            if (!equipment.isEmpty()) equipmentByRow.put(rowId, equipment);
        }
    }

    /**
     * Records a variant option's per-state textures into the side-channel when the option carries
     * more than the default {@code wild} entry (i.e. a genuine multi-state family like wolf). The
     * {@code entities} row above keeps only {@code wild} as {@code texture_ref}, so the round-trip
     * to the flat rows is unaffected.
     */
    private static void collectStateTextures(@NotNull String rowId, @NotNull JsonObject optionObj, @NotNull Map<String, Map<String, String>> stateTextures) {
        if (!optionObj.has("textures")) return;
        JsonObject textures = optionObj.getAsJsonObject("textures");
        if (textures.size() <= 1) return;
        Map<String, String> states = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> texture : textures.entrySet())
            states.put(texture.getKey(), texture.getValue().getAsString());
        stateTextures.put(rowId, states);
    }

    /**
     * Builds the single flat row for a plain (no variant axis) family, keeping its family-level
     * {@code texture_ref} and carried fields.
     */
    private static @NotNull JsonObject plainRow(@NotNull JsonObject family) {
        JsonObject row = new JsonObject();
        row.addProperty("geometry_ref", family.get("geometry_ref").getAsString());
        if (family.has("texture_ref")) row.add("texture_ref", family.get("texture_ref").deepCopy());
        row.addProperty("armor_type", family.get("armor_type").getAsString());
        copyCarriedFields(family, row);
        return row;
    }

    /**
     * Copies {@code textures.wild} from a variant option onto the flat row as {@code texture_ref}.
     */
    private static void addTextureRef(@NotNull JsonObject row, @NotNull JsonObject optionObj) {
        if (!optionObj.has("textures")) return;
        JsonObject textures = optionObj.getAsJsonObject("textures");
        if (textures.has("wild")) row.add("texture_ref", textures.get("wild").deepCopy());
    }

    /**
     * Copies the {@link #CARRIED_FIELDS} present on a family entry onto a flat row, verbatim.
     */
    private static void copyCarriedFields(@NotNull JsonObject family, @NotNull JsonObject row) {
        for (String key : CARRIED_FIELDS)
            if (family.has(key)) row.add(key, family.get(key).deepCopy());
    }
}
