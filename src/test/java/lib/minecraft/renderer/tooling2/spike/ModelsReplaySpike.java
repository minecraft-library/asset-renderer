package lib.minecraft.renderer.tooling2.spike;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.pipeline.loader.EntityFamilyFlattener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase-0 spike 0b (10-bridge SS8.2) - proves the {@code EntityModelsBridge} two-step replay
 * (decision 27): the checked-in {@code entity_models.json} is inverted into a synthetic v2
 * fixture (10-bridge SS5.1 table reversed), run through a prototype of the two-step
 * (flat rows keyed in {@code ENTITY_FLAT_ROW_KEYS} order -> {@code group()}-order regroup), and
 * the round trip must be canonical-SHA equal to the original - envelope included (Q3). The
 * untouched {@link EntityFamilyFlattener} is the secondary oracle, order-insensitive on flat
 * rows (10-bridge flag 3).
 *
 * <p>Scratch spike code - outside the SPINE SS2 naming registry; the ordering constants and
 * transform tables prototyped here are promoted into {@code bridge/LegacyOrder} +
 * {@code bridge/EntityModelsBridge} at S11, the {@code CanonicalSha} copy into its named test
 * util. Every regrouped family entry is REBUILT from the flat rows + v2 axis data with declared
 * key orders - never deep-copied from the input file - so the choreography contracts, not the
 * parse order, carry the proof.
 *
 * <p>SPIKE FINDING (sheep undercoat, feeds 06/S7 + 10 SS5.1): the legacy 0.001 depth-clearance
 * keys on "overlay has NO own ModelLayers mesh", NOT on {@code geometry_ref == base} - a
 * composite overlay may dedupe INTO the base geometry id and carries no inflate. v2 therefore
 * emits the overlay {@code geometry} key ONLY for own-mesh overlays (absent = inherits the
 * family mesh); the bridge re-adds 0.001 exactly on the absent arm.
 */
@DisplayName("spike 0b: entity_models two-step replay round-trips SHA-equal through a v2 fixture")
class ModelsReplaySpike {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();
    private static final @NotNull String TEX_PREFIX = "minecraft:textures/entity/";
    private static final @NotNull String TEX_SUFFIX = ".png";
    private static final @NotNull String V2_KEY_PREFIX = "V2|";
    private static final float DEPTH_CLEARANCE_INFLATE = 0.001f;

    /** EntityRowField declaration order (EntityRuntimeJsonWriter) - prototype ENTITY_FLAT_ROW_KEYS. */
    private static final @NotNull List<String> ENTITY_FLAT_ROW_KEYS = List.of(
        "geometry_ref", "texture_ref", "armor_type", "renderer_scale",
        "overlays", "block_overlays", "setup_yaw_addend", "base_tint", "hidden_bones", "bone_toggles");

    /** copyBaseOptionalFields list order (EntityFamilyJsonWriter) - family carried-field order. */
    private static final @NotNull List<String> FAMILY_CARRIED_ORDER = List.of(
        "renderer_scale", "setup_yaw_addend", "base_tint", "hidden_bones", "bone_toggles", "overlays", "block_overlays");

    /** Legacy overlay-row key order (EntityRuntimeJsonWriter OVERLAYS contributor). */
    private static final @NotNull List<String> OVERLAY_ROW_KEYS = List.of(
        "geometry_ref", "texture_ref", "emissive", "tint_color", "tint_by", "texture_by",
        "shearable", "requires_tint", "requires_charged", "inflate", "skip_bounds", "blend", "alpha", "retain_bones");

    /** Legacy block-overlay row key order (EntityBlockOverlayResolver.toJson). */
    private static final @NotNull List<String> BLOCK_OVERLAY_ROW_KEYS = List.of(
        "block_id", "attached_bone", "selectable", "transforms");

    @Test
    @DisplayName("v2 fixture -> flat rows -> group()-order regroup is canonical-SHA equal (Q3)")
    void twoStepRoundTripsShaEqual() throws NoSuchAlgorithmException {
        JsonObject legacy = GeometryReplaySpike.readResource("/lib/minecraft/renderer/entity_models.json");
        JsonObject legacyFamilies = legacy.getAsJsonObject("families");

        JsonObject v2Families = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : legacyFamilies.entrySet())
            v2Families.add(entry.getKey(), v2ify(entry.getValue().getAsJsonObject()));

        JsonObject bridged = new JsonObject();
        bridged.addProperty("//", legacy.get("//").getAsString());   // declared header constant (Q5)
        bridged.addProperty("source_version", legacy.get("source_version").getAsString());
        JsonObject bridgedFamilies = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : v2Families.entrySet())
            bridgedFamilies.add(entry.getKey(), bridgeFamily(entry.getValue().getAsJsonObject()));
        bridged.add("families", bridgedFamilies);

        String expected = canonicalSha(legacy);
        String actual = canonicalSha(bridged);
        if (!expected.equals(actual)) reportFirstDivergence(legacyFamilies, bridgedFamilies);
        assertEquals(expected, actual, "two-step round trip diverged from the checked-in resource");

        // secondary oracle: flatten BOTH sides through the untouched flattener and compare
        // order-insensitively (JsonObject.equals is order-insensitive deep equality)
        EntityFamilyFlattener.Flat original = EntityFamilyFlattener.flatten(legacyFamilies);
        EntityFamilyFlattener.Flat roundTrip = EntityFamilyFlattener.flatten(bridgedFamilies);
        assertEquals(original.entities().keySet(), roundTrip.entities().keySet(), "flat row id sets");
        assertEquals(original.entities(), roundTrip.entities(), "flat row values");
        assertEquals(original.families(), roundTrip.families(), "cross-entity families");
        assertEquals(original.stateTextures(), roundTrip.stateTextures(), "state-texture side-channel");
        assertEquals(original.collarTextures(), roundTrip.collarTextures(), "collar side-channel");
        assertEquals(original.babyGeometry(), roundTrip.babyGeometry(), "baby-geometry side-channel");
        assertEquals(original.equipment(), roundTrip.equipment(), "equipment side-channel");
        assertEquals(original.shapeAlternatives(), roundTrip.shapeAlternatives(), "shape side-channel");
        assertEquals(original.sizeAlternatives(), roundTrip.sizeAlternatives(), "size-mesh side-channel");
        assertEquals(original.sizeScales(), roundTrip.sizeScales(), "size-scale side-channel");
        assertEquals(original.markingsRows(), roundTrip.markingsRows(), "markings side-channel");
    }

    // ------------------------------------------------------------------------------------
    // forward inversion: legacy family -> synthetic v2 family (10-bridge SS5.1 reversed)
    // ------------------------------------------------------------------------------------

    private static @NotNull JsonObject v2ify(@NotNull JsonObject family) {
        JsonObject v2 = new JsonObject();
        v2.addProperty("renderer", "net/minecraft/client/renderer/entity/SpikeRenderer");
        v2.addProperty("geometry", v2Key(family.get("geometry_ref").getAsString()));
        v2.addProperty("armor_type", family.get("armor_type").getAsString());
        if (family.has("texture_ref")) v2.addProperty("texture", fullPath(family.get("texture_ref").getAsString()));

        JsonObject render = new JsonObject();
        if (family.has("renderer_scale")) render.add("scale", family.get("renderer_scale").deepCopy());
        if (family.has("setup_yaw_addend")) render.add("yaw_addend", family.get("setup_yaw_addend").deepCopy());
        if (family.has("base_tint")) render.add("tint", family.get("base_tint").deepCopy());
        if (!render.isEmpty()) v2.add("render", render);

        JsonObject bones = new JsonObject();
        if (family.has("hidden_bones")) bones.add("hidden", family.get("hidden_bones").deepCopy());
        if (family.has("bone_toggles")) bones.add("toggles", family.get("bone_toggles").deepCopy());
        if (!bones.isEmpty()) v2.add("bones", bones);

        if (family.has("axes")) v2.add("axes", v2ifyAxes(family.getAsJsonObject("axes")));
        if (family.has("overlays"))
            v2.add("overlays", v2ifyOverlays(family.getAsJsonArray("overlays"), family.get("geometry_ref").getAsString()));
        if (family.has("block_overlays")) {
            JsonArray rows = new JsonArray();
            int index = 0;
            for (JsonElement element : family.getAsJsonArray("block_overlays"))
                rows.add(v2ifyBlockOverlay(element.getAsJsonObject(), index++));
            v2.add("block_overlays", rows);
        }
        if (family.has("layers")) {
            JsonArray rows = new JsonArray();
            int index = 0;
            for (JsonElement element : family.getAsJsonArray("layers"))
                rows.add(v2ifyLayer(element.getAsJsonObject(), index++));
            v2.add("layers", rows);
        }
        if (family.has("family_of")) v2.add("family_of", family.get("family_of").deepCopy());
        return v2;
    }

    private static @NotNull JsonArray v2ifyOverlays(@NotNull JsonArray overlays, @NotNull String baseGeometry) {
        JsonArray rows = new JsonArray();
        int index = 0;
        for (JsonElement element : overlays) rows.add(v2ifyOverlay(element.getAsJsonObject(), baseGeometry, index++));
        return rows;
    }

    private static @NotNull JsonObject v2ifyOverlay(@NotNull JsonObject overlay, @NotNull String baseGeometry, int index) {
        JsonObject row = new JsonObject();
        row.addProperty("source", "SpikeLayer");
        row.addProperty("layer_index", index);
        // sheep-undercoat finding: geometry key absent == inherits the family mesh (the 0.001 arm)
        boolean inheritsBase = overlay.get("geometry_ref").getAsString().equals(baseGeometry)
            && overlay.has("inflate") && overlay.get("inflate").getAsFloat() == DEPTH_CLEARANCE_INFLATE;
        if (!inheritsBase) row.addProperty("geometry", v2Key(overlay.get("geometry_ref").getAsString()));
        if (overlay.has("texture_ref")) row.addProperty("texture", fullPath(overlay.get("texture_ref").getAsString()));

        JsonObject when = new JsonObject();
        if (overlay.has("shearable")) {
            when.addProperty("flag", "sheared");
            when.addProperty("value", false);
        } else if (overlay.has("requires_tint")) {
            when.addProperty("tinted", true);
        } else if (overlay.has("requires_charged")) {
            when.addProperty("charged", true);
        }
        if (!when.isEmpty()) row.add("when", when);

        JsonObject pipeline = new JsonObject();
        if (overlay.has("emissive")) pipeline.add("emissive", overlay.get("emissive").deepCopy());
        if (overlay.has("blend")) pipeline.add("blend", overlay.get("blend").deepCopy());
        if (overlay.has("alpha")) pipeline.add("alpha", overlay.get("alpha").deepCopy());
        if (!pipeline.isEmpty()) row.add("pipeline", pipeline);

        if (overlay.has("tint_color")) row.add("tint_color", overlay.get("tint_color").deepCopy());
        if (overlay.has("tint_by")) row.add("tint_by", overlay.get("tint_by").deepCopy());
        if (overlay.has("texture_by")) row.add("texture_by", overlay.get("texture_by").deepCopy());
        if (overlay.has("inflate") && !inheritsBase) row.add("grow", overlay.get("inflate").deepCopy());
        if (overlay.has("skip_bounds")) row.add("skip_bounds", overlay.get("skip_bounds").deepCopy());
        if (overlay.has("retain_bones")) row.add("retain_bones", overlay.get("retain_bones").deepCopy());
        return row;
    }

    private static @NotNull JsonObject v2ifyBlockOverlay(@NotNull JsonObject overlay, int index) {
        JsonObject row = new JsonObject();
        row.addProperty("source", "SpikeLayer");
        row.addProperty("layer_index", index);
        for (String key : BLOCK_OVERLAY_ROW_KEYS)
            if (overlay.has(key)) row.add(key, overlay.get(key).deepCopy());
        return row;
    }

    private static @NotNull JsonObject v2ifyLayer(@NotNull JsonObject layer, int index) {
        JsonObject row = new JsonObject();
        row.addProperty("source", "SpikeLayer");
        row.addProperty("layer_index", index);
        row.add("id", layer.get("id").deepCopy());
        if ("collar".equals(layer.get("id").getAsString())) {
            JsonObject when = new JsonObject();
            when.addProperty("collar_color", "set");     // truthful v2 gate (D42)
            row.add("when", when);
        } else {
            row.add("when", layer.get("when").deepCopy());
        }
        JsonObject overlay = layer.getAsJsonObject("overlay");
        JsonObject out = new JsonObject();
        out.addProperty("geometry", v2Key(overlay.get("geometry_ref").getAsString()));
        if (overlay.has("texture_ref")) out.addProperty("texture", fullPath(overlay.get("texture_ref").getAsString()));
        for (String key : List.of("tint_by", "texture_by", "texture_template", "default_material"))
            if (overlay.has(key)) out.add(key, overlay.get(key).deepCopy());
        row.add("overlay", out);
        return row;
    }

    private static @NotNull JsonObject v2ifyAxes(@NotNull JsonObject axes) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : axes.entrySet()) {
            String name = entry.getKey();
            JsonObject axis = entry.getValue().getAsJsonObject();
            JsonObject a = new JsonObject();
            if (axis.has("id_encoded")) a.add("id_encoded", axis.get("id_encoded").deepCopy());
            a.add("default", axis.get("default").deepCopy());
            a.add("values", axis.get("values").deepCopy());
            JsonObject options = new JsonObject();
            for (Map.Entry<String, JsonElement> option : axis.getAsJsonObject("options").entrySet()) {
                JsonObject body = option.getValue().getAsJsonObject();
                JsonObject b = new JsonObject();
                if ("variant".equals(name)) {
                    JsonObject textures = new JsonObject();
                    String baby = null;
                    for (Map.Entry<String, JsonElement> texture : body.getAsJsonObject("textures").entrySet()) {
                        if ("baby".equals(texture.getKey())) baby = texture.getValue().getAsString();
                        else textures.addProperty(texture.getKey(), fullPath(texture.getValue().getAsString()));
                    }
                    b.add("textures", textures);
                    if (baby != null) b.addProperty("baby_texture", fullPath(baby));
                    if (body.has("geometry_ref")) b.addProperty("geometry", v2Key(body.get("geometry_ref").getAsString()));
                } else {
                    if (body.has("geometry_ref")) b.addProperty("geometry", v2Key(body.get("geometry_ref").getAsString()));
                    if (body.has("texture_ref")) b.addProperty("texture", fullPath(body.get("texture_ref").getAsString()));
                    if (body.has("scale")) b.add("scale", body.get("scale").deepCopy());
                    if (body.has("overlays"))
                        b.add("overlays", v2ifyOverlays(body.getAsJsonArray("overlays"), body.get("geometry_ref").getAsString()));
                }
                options.add(option.getKey(), b);
            }
            a.add("options", options);
            out.add(name, a);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------
    // prototype EntityModelsBridge: step 1 (flat rows) + step 2 (group()-order regroup)
    // ------------------------------------------------------------------------------------

    private static @NotNull JsonObject bridgeFamily(@NotNull JsonObject v2) {
        JsonObject axes = v2.has("axes") ? v2.getAsJsonObject("axes") : new JsonObject();
        JsonObject variant = axes.has("variant") ? axes.getAsJsonObject("variant") : null;
        JsonObject family = new JsonObject();

        if (variant != null) {
            String defaultOption = variant.get("default").getAsString();
            JsonObject options = variant.getAsJsonObject("options");
            String baseTexture = stripPath(options.getAsJsonObject(defaultOption)
                .getAsJsonObject("textures").get("wild").getAsString());
            JsonObject baseRow = flatBaseRow(v2, baseTexture);

            String familyGeometry = legacyKey(v2.get("geometry").getAsString());
            family.addProperty("geometry_ref", familyGeometry);
            family.add("armor_type", baseRow.get("armor_type").deepCopy());
            for (String key : FAMILY_CARRIED_ORDER)
                if (baseRow.has(key)) family.add(key, baseRow.get(key).deepCopy());
            if (v2.has("family_of")) family.add("family_of", v2.get("family_of").deepCopy());

            JsonObject optionsOut = new JsonObject();
            for (Map.Entry<String, JsonElement> option : options.entrySet()) {
                JsonObject body = option.getValue().getAsJsonObject();
                JsonObject b = new JsonObject();
                JsonObject textures = new JsonObject();
                for (Map.Entry<String, JsonElement> texture : body.getAsJsonObject("textures").entrySet())
                    textures.addProperty(texture.getKey(), stripPath(texture.getValue().getAsString()));
                if (body.has("baby_texture"))
                    textures.addProperty("baby", stripPath(body.get("baby_texture").getAsString()));  // re-slot, last
                b.add("textures", textures);
                if (body.has("geometry")) {
                    String optionGeometry = legacyKey(body.get("geometry").getAsString());
                    if (!optionGeometry.equals(familyGeometry)) b.addProperty("geometry_ref", optionGeometry);
                }
                optionsOut.add(option.getKey(), b);
            }
            JsonObject variantOut = new JsonObject();
            variantOut.addProperty("id_encoded", true);
            variantOut.addProperty("default", defaultOption);
            JsonArray values = new JsonArray();
            for (String key : optionsOut.keySet()) values.add(key);
            variantOut.add("values", values);
            variantOut.add("options", optionsOut);
            JsonObject axesOut = new JsonObject();
            axesOut.add("variant", variantOut);
            if (axes.has("state")) {
                JsonObject state = axes.getAsJsonObject("state");
                JsonObject stateOut = new JsonObject();
                stateOut.add("default", state.get("default").deepCopy());
                stateOut.add("values", state.get("values").deepCopy());
                stateOut.add("options", new JsonObject());
                axesOut.add("state", stateOut);
            }
            family.add("axes", axesOut);
        } else {
            JsonObject baseRow = flatBaseRow(v2, v2.has("texture") ? stripPath(v2.get("texture").getAsString()) : null);
            family.add("geometry_ref", baseRow.get("geometry_ref").deepCopy());
            family.add("armor_type", baseRow.get("armor_type").deepCopy());
            if (baseRow.has("texture_ref")) family.add("texture_ref", baseRow.get("texture_ref").deepCopy());
            for (String key : FAMILY_CARRIED_ORDER)
                if (baseRow.has(key)) family.add(key, baseRow.get(key).deepCopy());
            if (v2.has("family_of")) family.add("family_of", v2.get("family_of").deepCopy());
        }

        // attachments in group() call order: collar -> markings -> equipment (layers array),
        // then age -> shape -> size (axes; merged into the variant axes object when present)
        JsonArray layersOut = new JsonArray();
        for (JsonElement element : layerRows(v2, "collar")) layersOut.add(element);
        for (JsonElement element : layerRows(v2, "markings")) layersOut.add(element);
        for (JsonElement element : equipmentLayerRows(v2)) layersOut.add(element);
        if (!layersOut.isEmpty()) family.add("layers", layersOut);

        if (axes.has("age")) {
            JsonObject baby = axes.getAsJsonObject("age").getAsJsonObject("options").getAsJsonObject("baby");
            JsonObject b = new JsonObject();
            b.addProperty("geometry_ref", legacyKey(baby.get("geometry").getAsString()));
            if (baby.has("texture")) b.addProperty("texture_ref", stripPath(baby.get("texture").getAsString()));
            JsonObject options = new JsonObject();
            options.add("baby", b);
            attachAxis(family, "age", axisShape("adult", List.of("adult", "baby"), options));
        }
        if (axes.has("shape")) {
            JsonObject large = axes.getAsJsonObject("shape").getAsJsonObject("options").getAsJsonObject("large");
            String largeGeometry = legacyKey(large.get("geometry").getAsString());
            JsonObject b = new JsonObject();
            b.addProperty("geometry_ref", largeGeometry);
            if (large.has("texture")) b.addProperty("texture_ref", stripPath(large.get("texture").getAsString()));
            if (large.has("overlays")) {
                JsonArray overlays = new JsonArray();
                for (JsonElement element : large.getAsJsonArray("overlays"))
                    overlays.add(legacyOverlayRow(element.getAsJsonObject(), largeGeometry));
                b.add("overlays", overlays);
            }
            JsonObject options = new JsonObject();
            options.add("large", b);
            attachAxis(family, "shape", axisShape("small", List.of("small", "large"), options));
        }
        if (axes.has("size")) {
            JsonObject options = new JsonObject();
            for (Map.Entry<String, JsonElement> option : axes.getAsJsonObject("size").getAsJsonObject("options").entrySet()) {
                JsonObject body = option.getValue().getAsJsonObject();
                JsonObject b = new JsonObject();
                if (body.has("geometry")) b.addProperty("geometry_ref", legacyKey(body.get("geometry").getAsString()));
                if (body.has("scale")) b.add("scale", body.get("scale").deepCopy());
                options.add(option.getKey(), b);
            }
            String defaultSize = List.of("small", "medium", "large").stream()
                .filter(candidate -> !options.has(candidate)).findFirst().orElseThrow();
            attachAxis(family, "size", axisShape(defaultSize, List.of("small", "medium", "large"), options));
        }
        return family;
    }

    /** Step 1: the flat base row, keys in {@code ENTITY_FLAT_ROW_KEYS} order. */
    private static @NotNull JsonObject flatBaseRow(@NotNull JsonObject v2, @Nullable String textureRef) {
        String baseGeometry = legacyKey(v2.get("geometry").getAsString());
        Map<String, JsonElement> values = new LinkedHashMap<>();
        values.put("geometry_ref", GSON.toJsonTree(baseGeometry));
        if (textureRef != null) values.put("texture_ref", GSON.toJsonTree(textureRef));
        values.put("armor_type", v2.get("armor_type").deepCopy());
        JsonObject render = v2.has("render") ? v2.getAsJsonObject("render") : new JsonObject();
        if (render.has("scale")) values.put("renderer_scale", render.get("scale").deepCopy());
        if (render.has("yaw_addend")) values.put("setup_yaw_addend", render.get("yaw_addend").deepCopy());
        if (render.has("tint")) values.put("base_tint", render.get("tint").deepCopy());
        JsonObject bones = v2.has("bones") ? v2.getAsJsonObject("bones") : new JsonObject();
        if (bones.has("hidden")) values.put("hidden_bones", bones.get("hidden").deepCopy());
        if (bones.has("toggles")) values.put("bone_toggles", bones.get("toggles").deepCopy());
        if (v2.has("overlays")) {
            JsonArray overlays = new JsonArray();
            for (JsonElement element : v2.getAsJsonArray("overlays"))
                overlays.add(legacyOverlayRow(element.getAsJsonObject(), baseGeometry));
            values.put("overlays", overlays);
        }
        if (v2.has("block_overlays")) {
            JsonArray rows = new JsonArray();
            for (JsonElement element : v2.getAsJsonArray("block_overlays"))
                rows.add(legacyBlockOverlayRow(element.getAsJsonObject()));
            values.put("block_overlays", rows);
        }
        JsonObject row = new JsonObject();
        for (String key : ENTITY_FLAT_ROW_KEYS)
            if (values.containsKey(key)) row.add(key, values.get(key));
        return row;
    }

    private static @NotNull JsonObject legacyOverlayRow(@NotNull JsonObject v2Row, @NotNull String baseGeometry) {
        Map<String, JsonElement> values = new LinkedHashMap<>();
        boolean inheritsBase = !v2Row.has("geometry");           // absent = inherits the family mesh
        values.put("geometry_ref", GSON.toJsonTree(inheritsBase ? baseGeometry : legacyKey(v2Row.get("geometry").getAsString())));
        if (v2Row.has("texture")) values.put("texture_ref", GSON.toJsonTree(stripPath(v2Row.get("texture").getAsString())));
        JsonObject pipeline = v2Row.has("pipeline") ? v2Row.getAsJsonObject("pipeline") : new JsonObject();
        if (pipeline.has("emissive")) values.put("emissive", pipeline.get("emissive").deepCopy());
        if (v2Row.has("tint_color")) values.put("tint_color", v2Row.get("tint_color").deepCopy());
        if (v2Row.has("tint_by")) values.put("tint_by", v2Row.get("tint_by").deepCopy());
        if (v2Row.has("texture_by")) values.put("texture_by", v2Row.get("texture_by").deepCopy());
        JsonObject when = v2Row.has("when") ? v2Row.getAsJsonObject("when") : new JsonObject();
        if (when.has("flag") && "sheared".equals(when.get("flag").getAsString()))
            values.put("shearable", GSON.toJsonTree(true));
        if (when.has("tinted") && when.get("tinted").getAsBoolean())
            values.put("requires_tint", GSON.toJsonTree(true));
        if (when.has("charged") && when.get("charged").getAsBoolean())
            values.put("requires_charged", GSON.toJsonTree(true));
        // Re-add (decision 22). Minted as a PARSED literal, not JsonPrimitive(Float): Gson's
        // JsonPrimitive.equals compares doubleValue(), and Float 0.001f widens to
        // 0.00100000004... != the parsed 0.001 - serialization is identical (SHA-safe) but the
        // flattener oracle's JsonObject.equals would false-negative. S11's round-trip test must
        // compare canonical serializations or mint the same way.
        if (v2Row.has("grow")) values.put("inflate", v2Row.get("grow").deepCopy());
        else if (inheritsBase)
            values.put("inflate", GSON.fromJson(Float.toString(DEPTH_CLEARANCE_INFLATE), JsonElement.class));
        if (v2Row.has("skip_bounds")) values.put("skip_bounds", v2Row.get("skip_bounds").deepCopy());
        if (pipeline.has("blend")) values.put("blend", pipeline.get("blend").deepCopy());
        if (pipeline.has("alpha")) values.put("alpha", pipeline.get("alpha").deepCopy());
        if (v2Row.has("retain_bones")) values.put("retain_bones", v2Row.get("retain_bones").deepCopy());
        JsonObject row = new JsonObject();
        for (String key : OVERLAY_ROW_KEYS)
            if (values.containsKey(key)) row.add(key, values.get(key));
        return row;
    }

    private static @NotNull JsonObject legacyBlockOverlayRow(@NotNull JsonObject v2Row) {
        JsonObject row = new JsonObject();
        for (String key : BLOCK_OVERLAY_ROW_KEYS)
            if (v2Row.has(key)) row.add(key, v2Row.get(key).deepCopy());
        return row;
    }

    private static @NotNull JsonArray layerRows(@NotNull JsonObject v2, @NotNull String id) {
        JsonArray out = new JsonArray();
        if (!v2.has("layers")) return out;
        for (JsonElement element : v2.getAsJsonArray("layers")) {
            JsonObject layer = element.getAsJsonObject();
            if (!id.equals(layer.get("id").getAsString())) continue;
            JsonObject overlay = layer.getAsJsonObject("overlay");
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            if ("collar".equals(id)) {
                JsonObject when = new JsonObject();
                when.addProperty("state", "tame");           // legacy fiction re-emitted (D42)
                row.add("when", when);
                JsonObject out2 = new JsonObject();
                out2.addProperty("geometry_ref", legacyKey(overlay.get("geometry").getAsString()));
                out2.addProperty("texture_ref", stripPath(overlay.get("texture").getAsString()));
                out2.add("tint_by", overlay.get("tint_by").deepCopy());
                row.add("overlay", out2);
            } else {
                row.add("when", layer.get("when").deepCopy());
                JsonObject out2 = new JsonObject();
                out2.addProperty("geometry_ref", legacyKey(overlay.get("geometry").getAsString()));
                out2.add("texture_by", overlay.get("texture_by").deepCopy());
                row.add("overlay", out2);
            }
            out.add(row);
        }
        return out;
    }

    private static @NotNull JsonArray equipmentLayerRows(@NotNull JsonObject v2) {
        JsonArray out = new JsonArray();
        if (!v2.has("layers")) return out;
        for (JsonElement element : v2.getAsJsonArray("layers")) {
            JsonObject layer = element.getAsJsonObject();
            JsonObject when = layer.getAsJsonObject("when");
            if (!when.has("equipment")) continue;
            JsonObject overlay = layer.getAsJsonObject("overlay");
            JsonObject row = new JsonObject();
            row.add("id", layer.get("id").deepCopy());
            row.add("when", when.deepCopy());
            JsonObject out2 = new JsonObject();
            out2.addProperty("geometry_ref", legacyKey(overlay.get("geometry").getAsString()));
            out2.add("texture_template", overlay.get("texture_template").deepCopy());
            out2.add("default_material", overlay.get("default_material").deepCopy());
            row.add("overlay", out2);
            out.add(row);
        }
        return out;
    }

    private static @NotNull JsonObject axisShape(@NotNull String defaultValue, @NotNull List<String> values, @NotNull JsonObject options) {
        JsonObject axis = new JsonObject();
        axis.addProperty("default", defaultValue);
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        axis.add("values", array);
        axis.add("options", options);
        return axis;
    }

    private static void attachAxis(@NotNull JsonObject family, @NotNull String name, @NotNull JsonObject axis) {
        JsonObject axes = family.has("axes") ? family.getAsJsonObject("axes") : new JsonObject();
        axes.add(name, axis);
        if (!family.has("axes")) family.add("axes", axes);
    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    private static @NotNull String fullPath(@NotNull String ref) {
        return TEX_PREFIX + ref + TEX_SUFFIX;
    }

    private static @NotNull String stripPath(@NotNull String path) {
        assertTrue(path.startsWith(TEX_PREFIX) && path.endsWith(TEX_SUFFIX), "unexpected texture path " + path);
        return path.substring(TEX_PREFIX.length(), path.length() - TEX_SUFFIX.length());
    }

    private static @NotNull String v2Key(@NotNull String legacyGeometryId) {
        // Stand-in for the factory-coordinate key: 0a proves stem + suffix minting, so the models
        // spike uses an explicit bijection and exercises LegacyGeometryIds in GeometryReplaySpike.
        return V2_KEY_PREFIX + legacyGeometryId;
    }

    private static @NotNull String legacyKey(@NotNull String v2Key) {
        assertTrue(v2Key.startsWith(V2_KEY_PREFIX), "unexpected v2 geometry key " + v2Key);
        return v2Key.substring(V2_KEY_PREFIX.length());
    }

    /** The decision-28 recipe (prototype): Gson parse-tree -> canonical re-serialise -> SHA-256 hex. */
    private static @NotNull String canonicalSha(@NotNull JsonElement tree) throws NoSuchAlgorithmException {
        byte[] canonical = GSON.toJson(tree).getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    }

    private static void reportFirstDivergence(@NotNull JsonObject legacyFamilies, @NotNull JsonObject bridgedFamilies) {
        for (Map.Entry<String, JsonElement> entry : legacyFamilies.entrySet()) {
            String expected = GSON.toJson(entry.getValue());
            String actual = GSON.toJson(bridgedFamilies.get(entry.getKey()));
            if (!expected.equals(actual)) {
                int at = 0;
                int limit = Math.min(expected.length(), actual.length());
                while (at < limit && expected.charAt(at) == actual.charAt(at)) at++;
                fail("first divergent family '" + entry.getKey() + "' at char " + at
                    + "\n  legacy : " + expected.substring(Math.max(0, at - 60), Math.min(expected.length(), at + 60))
                    + "\n  bridged: " + actual.substring(Math.max(0, at - 60), Math.min(actual.length(), at + 60)));
            }
        }
        fail("families all equal - envelope diverged");
    }

}
