package lib.minecraft.renderer.tooling2.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

/**
 * Replays the legacy entity-geometry key assignment (10-bridge SS4.2, decision 16 PRIMARY): legacy
 * keys are {@code geometry.<stem>} where the stem is the lowercased simple factory-class name with
 * the {@code Model} suffix stripped, plus a {@code _<n>} collision suffix assigned by INSERTION
 * (walk) order. The S0 spike proved (and the flow's renderer-scale fix restored) that a stage-major
 * walk of the v2 models file - primary body, then variant-model, composite-overlay, baby, equipment,
 * shape, size, first-seen deduped - reproduces the legacy key SEQUENCE exactly for every mesh the
 * two pipelines share.
 *
 * <p>The map covers ALL v2 geometry coordinates: the stage-major-reached ones first (legacy order),
 * then any coordinate the walk does not reach (v2-only equipment-baby meshes) appended in geometry
 * file order. Both entity converters read the SAME map, so a geometry_ref in the models output
 * always resolves to a key in the geometry output.
 */
final class LegacyGeometryIds {

    private static final @NotNull String GEOMETRY_PREFIX = "geometry.";
    private static final @NotNull String MODEL_SUFFIX = "Model";

    private LegacyGeometryIds() {
    }

    /**
     * Builds the v2-coordinate -&gt; legacy-key map in legacy emission order.
     *
     * @param v2Models the parsed {@code v2/entity_models.json}
     * @param v2Geometry the parsed {@code v2/entity_geometry.json}
     * @return an ordered map from factory coordinate to {@code geometry.<stem>[_<n>]} key
     */
    static @NotNull Map<String, String> map(@NotNull JsonObject v2Models, @NotNull JsonObject v2Geometry) {
        JsonObject families = v2Models.getAsJsonObject("families");
        LinkedHashSet<String> coordinates = new LinkedHashSet<>();
        stageMajor(families, coordinates);
        // Any geometry coordinate the model walk does not reach (v2-only equipment-baby meshes,
        // e.g. the scaled happy_ghast harness [D65]) is appended in geometry file order so it still
        // receives a stable key and RefClosure holds.
        for (String coordinate : v2Geometry.getAsJsonObject("geometries").keySet()) coordinates.add(coordinate);

        Map<String, String> keys = new LinkedHashMap<>();
        LinkedHashSet<String> minted = new LinkedHashSet<>();
        for (String coordinate : coordinates) keys.put(coordinate, mint(coordinate, minted));
        return keys;
    }

    /**
     * Walks the family tree stage-major, collecting geometry coordinates first-seen (the
     * {@code GEOMETRY_STAGE_ORDER} contract - the legacy main-loop stage sequence).
     */
    private static void stageMajor(@NotNull JsonObject families, @NotNull LinkedHashSet<String> seen) {
        // 1: primary body meshes (family order == EntityType registry order).
        for (Map.Entry<String, JsonElement> entry : families.entrySet())
            add(seen, primaryGeometry(entry.getValue().getAsJsonObject()));
        // 2: variant-model geometry overrides.
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject variant = axis(entry.getValue().getAsJsonObject(), "variant");
            if (variant != null)
                for (Map.Entry<String, JsonElement> option : variant.getAsJsonObject("options").entrySet())
                    add(seen, geometryOf(option.getValue().getAsJsonObject()));
        }
        // 3: composite overlays baking their own mesh (ref != family primary).
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject family = entry.getValue().getAsJsonObject();
            if (!family.has("overlays")) continue;
            String primary = primaryGeometry(family);
            for (JsonElement overlay : family.getAsJsonArray("overlays")) {
                String reference = geometryOf(overlay.getAsJsonObject());
                if (reference != null && !reference.equals(primary)) add(seen, reference);
            }
        }
        // 4: baby meshes (age axis).
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject age = axis(entry.getValue().getAsJsonObject(), "age");
            if (age == null) continue;
            JsonObject baby = age.getAsJsonObject("options").getAsJsonObject("baby");
            if (baby != null) add(seen, geometryOf(baby));
        }
        // 5: equipment layer meshes.
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject family = entry.getValue().getAsJsonObject();
            if (!family.has("layers")) continue;
            for (JsonElement element : family.getAsJsonArray("layers")) {
                JsonObject layer = element.getAsJsonObject();
                // The armor classification row [LOCKED 3] carries no `when`/`overlay` mesh - skip it.
                if (layer.has("when") && layer.getAsJsonObject("when").has("equipment"))
                    add(seen, geometryOf(layer.getAsJsonObject("overlay")));
            }
        }
        // 6: shape-axis large body (tropical fish).
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject shape = axis(entry.getValue().getAsJsonObject(), "shape");
            if (shape == null) continue;
            JsonObject large = shape.getAsJsonObject("options").getAsJsonObject("large");
            if (large != null) add(seen, geometryOf(large));
        }
        // 7: size-axis mesh selections (pufferfish).
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject size = axis(entry.getValue().getAsJsonObject(), "size");
            if (size == null) continue;
            for (Map.Entry<String, JsonElement> option : size.getAsJsonObject("options").entrySet())
                add(seen, geometryOf(option.getValue().getAsJsonObject()));
        }
    }

    /**
     * Mints a coordinate's legacy key: {@code geometry.<stem>}, then {@code stem_1}, {@code stem_2}
     * ... for each collision in encounter order (matching the legacy
     * {@code EntityRuntimeJsonWriter.buildGeometryTable} suffixing).
     */
    private static @NotNull String mint(@NotNull String coordinate, @NotNull LinkedHashSet<String> minted) {
        String stem = stem(coordinate);
        String candidate = GEOMETRY_PREFIX + stem;
        int collision = 0;
        while (minted.contains(candidate)) candidate = GEOMETRY_PREFIX + stem + "_" + (++collision);
        minted.add(candidate);
        return candidate;
    }

    /** The stem of a factory coordinate: lowercased simple class name, {@code Model} suffix stripped. */
    private static @NotNull String stem(@NotNull String coordinate) {
        String simpleClass = coordinate.substring(0, coordinate.indexOf('#'));
        if (simpleClass.endsWith(MODEL_SUFFIX)) simpleClass = simpleClass.substring(0, simpleClass.length() - MODEL_SUFFIX.length());
        return simpleClass.toLowerCase(Locale.ROOT);
    }

    private static void add(@NotNull LinkedHashSet<String> seen, @Nullable String coordinate) {
        if (coordinate != null) seen.add(coordinate);
    }

    private static @Nullable String geometryOf(@NotNull JsonObject node) {
        JsonElement geometry = node.get("geometry");
        return geometry == null ? null : geometry.getAsString();
    }

    /**
     * The family's primary body-mesh coordinate, read from the mandatory age axis' {@code options.adult}
     * (axis unification #1 moved the family baseline there from the former top-level {@code geometry}).
     */
    private static @NotNull String primaryGeometry(@NotNull JsonObject family) {
        return family.getAsJsonObject("axes").getAsJsonObject("age").getAsJsonObject("options")
            .getAsJsonObject("adult").getAsJsonPrimitive("geometry").getAsString();
    }

    private static @Nullable JsonObject axis(@NotNull JsonObject family, @NotNull String name) {
        if (!family.has("axes")) return null;
        JsonObject axes = family.getAsJsonObject("axes");
        return axes.has(name) ? axes.getAsJsonObject(name) : null;
    }

}
