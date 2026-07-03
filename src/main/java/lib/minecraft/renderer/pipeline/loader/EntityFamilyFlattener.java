package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Flattens the normalized {@code entity_models2.json} family form back into the flat
 * {@code entities} + {@code families} shape {@link EntityModelLoader} consumes.
 *
 * <p>This is the exact inverse of {@code EntityFamilyJsonWriter.group}: each id-encoded
 * {@code variant} axis re-expands into one flat row per option ({@code minecraft:<id>_<opt>},
 * the default option carrying the family's overlays / tints / hidden-bones and no
 * {@code variant_of}, the rest pointing back at it), and each per-family {@code family_of} link
 * re-collects into the cross-entity {@code families} table. Carried values are copied as verbatim
 * {@code JsonElement} deep-copies, so no number or string is ever reformatted.
 *
 * <p>The round-trip {@code flattenV2(group(x)) == x} against the committed
 * {@code entity_models.json} is pinned by {@code EntityModelsV2RoundTripTest}; keep the copied
 * field list here in step with the writer's.
 */
public final class EntityFamilyFlattener {

    /**
     * Optional base fields carried verbatim between a flat row and its family entry. Must match
     * {@code EntityFamilyJsonWriter}'s copied-field list; the round-trip golden catches drift.
     */
    private static final @NotNull List<String> CARRIED_FIELDS =
        List.of("renderer_scale", "setup_yaw_addend", "base_tint", "hidden_bones", "overlays", "block_overlays");

    private EntityFamilyFlattener() {
    }

    /**
     * The reconstructed flat form: the {@code entities} object (one row per {@code minecraft:<id>}
     * / {@code minecraft:<id>_<variant>}) and the cross-entity {@code families} table.
     *
     * @param entities the flat per-entity rows keyed by namespaced id
     * @param families the cross-entity grouping table (derivative id -&gt; family root)
     */
    public record Flat(@NotNull JsonObject entities, @NotNull JsonObject families) {
    }

    /**
     * Flattens the family form into the runtime flat shape.
     *
     * @param familyForm the {@code families} object of {@code entity_models2.json} (family id -&gt;
     *     family entry)
     * @return the reconstructed flat {@code entities} + {@code families}
     */
    public static @NotNull Flat flattenV2(@NotNull JsonObject familyForm) {
        JsonObject entities = new JsonObject();
        JsonObject crossFamilies = new JsonObject();

        for (Map.Entry<String, com.google.gson.JsonElement> entry : familyForm.entrySet()) {
            String familyId = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject family = entry.getValue().getAsJsonObject();

            JsonObject variantAxis = variantAxis(family);
            if (variantAxis != null) expandVariantFamily(familyId, family, variantAxis, entities);
            else entities.add(familyId, plainRow(family));

            if (family.has("family_of")) crossFamilies.addProperty(familyId, family.get("family_of").getAsString());
        }
        return new Flat(entities, crossFamilies);
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
    private static void expandVariantFamily(@NotNull String familyId, @NotNull JsonObject family, @NotNull JsonObject variantAxis, @NotNull JsonObject entities) {
        String defaultOption = variantAxis.get("default").getAsString();
        String baseId = familyId + "_" + defaultOption;
        String familyGeometry = family.get("geometry_ref").getAsString();
        String armorType = family.get("armor_type").getAsString();
        JsonObject options = variantAxis.getAsJsonObject("options");

        for (Map.Entry<String, com.google.gson.JsonElement> entry : options.entrySet()) {
            String option = entry.getKey();
            JsonObject optionObj = entry.getValue().getAsJsonObject();

            JsonObject row = new JsonObject();
            row.addProperty("geometry_ref",
                optionObj.has("geometry_ref") ? optionObj.get("geometry_ref").getAsString() : familyGeometry);
            addTextureRef(row, optionObj);
            row.addProperty("armor_type", armorType);
            if (option.equals(defaultOption)) copyCarriedFields(family, row);
            else row.addProperty("variant_of", baseId);

            entities.add(familyId + "_" + option, row);
        }
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
