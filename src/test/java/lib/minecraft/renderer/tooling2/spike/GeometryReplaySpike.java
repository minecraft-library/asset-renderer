package lib.minecraft.renderer.tooling2.spike;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-0 spike 0a (10-bridge SS8.2) - proves the legacy {@code entity_geometry.json} key set,
 * {@code _<n>} collision-suffix assignment, and MAP ORDER all replay from the checked-in
 * {@code entity_models.json} alone, via stage-major reference enumeration (primary ->
 * variant-model -> composite-overlay -> baby -> equipment -> shape -> size; first-seen dedupe)
 * plus walk-order stem minting (decision 16 PRIMARY; the enumeration is the
 * {@code LegacyOrder.GEOMETRY_STAGE_ORDER} contract).
 *
 * <p>Scratch spike code - outside the SPINE SS2 naming registry; promoted into
 * {@code bridge/LegacyGeometryIds} + {@code bridge/LegacyOrder} at S11 or deleted. Deviation from
 * IMPLEMENTATION_PLAN SS3's sketch (recorded in {@code notes/tooling/spike/notes.md}): no jar walk
 * is needed - the models file alone carries the complete stage-major structure, and replaying
 * from it is the stronger proof because it is exactly the bridge's information position
 * (pure JSON to JSON).
 */
@DisplayName("spike 0a: entity_geometry key order replays from entity_models stage-major refs")
class GeometryReplaySpike {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();
    private static final @NotNull String GEOMETRY_PREFIX = "geometry.";

    @Test
    @DisplayName("stage-major enumeration + stem minting reproduce all 125 keys in sequence")
    void keyOrderReplays() {
        JsonObject models = readResource("/lib/minecraft/renderer/entity_models.json");
        JsonObject geometry = readResource("/lib/minecraft/renderer/entity_geometry.json");
        List<String> legacyKeys = new ArrayList<>(geometry.getAsJsonObject("geometries").keySet());

        List<String> replayed = enumerateStageMajor(models.getAsJsonObject("families"));
        assertEquals(legacyKeys, replayed,
            "Q2: stage-major reference enumeration must reproduce the legacy geometries map order");

        List<String> minted = mint(replayed);
        assertEquals(legacyKeys, minted,
            "Q1: stem + _<n> minting by encounter order must reproduce every legacy key");

        long collisions = legacyKeys.stream().filter(k -> k.matches(".*_\\d+$")).count();
        assertEquals(19, collisions, "the 26.1 collision-suffix census (10-bridge SS4.2)");
        assertEquals(125, legacyKeys.size(), "the 26.1 geometry-entry census (10-bridge SS4.2)");
    }

    /**
     * The {@code GEOMETRY_STAGE_ORDER} replay recipe: walks families in file order once per
     * stage, collecting geometry references first-seen. Mirrors the legacy main's source-append
     * stages (ToolingEntityModels.main).
     *
     * @param families the {@code families} object of the checked-in models file
     * @return the deduped reference sequence in legacy emission order
     */
    private static @NotNull List<String> enumerateStageMajor(@NotNull JsonObject families) {
        Set<String> seen = new LinkedHashSet<>();

        // stage 1: primary body meshes (family order == records/registry order)
        for (Map.Entry<String, JsonElement> entry : families.entrySet())
            seen.add(entry.getValue().getAsJsonObject().get("geometry_ref").getAsString());

        // stage 2: variant-model geometry overrides (per family, options order)
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject variant = axis(entry.getValue().getAsJsonObject(), "variant");
            if (variant == null) continue;
            for (Map.Entry<String, JsonElement> option : variant.getAsJsonObject("options").entrySet()) {
                JsonElement ref = option.getValue().getAsJsonObject().get("geometry_ref");
                if (ref != null) seen.add(ref.getAsString());
            }
        }

        // stage 3: composite overlays baking their own mesh (ref != family primary)
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject family = entry.getValue().getAsJsonObject();
            if (!family.has("overlays")) continue;
            String primary = family.get("geometry_ref").getAsString();
            for (JsonElement overlay : family.getAsJsonArray("overlays")) {
                String ref = overlay.getAsJsonObject().get("geometry_ref").getAsString();
                if (!ref.equals(primary)) seen.add(ref);
            }
        }

        // stage 4: baby meshes (age axis)
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject age = axis(entry.getValue().getAsJsonObject(), "age");
            if (age == null) continue;
            JsonObject baby = age.getAsJsonObject("options").getAsJsonObject("baby");
            if (baby != null && baby.has("geometry_ref")) seen.add(baby.get("geometry_ref").getAsString());
        }

        // stage 5: equipment layer meshes
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject family = entry.getValue().getAsJsonObject();
            if (!family.has("layers")) continue;
            for (JsonElement element : family.getAsJsonArray("layers")) {
                JsonObject layer = element.getAsJsonObject();
                if (layer.getAsJsonObject("when").has("equipment"))
                    seen.add(layer.getAsJsonObject("overlay").get("geometry_ref").getAsString());
            }
        }

        // stage 6: shape-axis large body (tropical fish)
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject shape = axis(entry.getValue().getAsJsonObject(), "shape");
            if (shape == null) continue;
            JsonObject large = shape.getAsJsonObject("options").getAsJsonObject("large");
            if (large != null && large.has("geometry_ref")) seen.add(large.get("geometry_ref").getAsString());
        }

        // stage 7: size-axis mesh selections (pufferfish)
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            JsonObject size = axis(entry.getValue().getAsJsonObject(), "size");
            if (size == null) continue;
            for (Map.Entry<String, JsonElement> option : size.getAsJsonObject("options").entrySet()) {
                JsonElement ref = option.getValue().getAsJsonObject().get("geometry_ref");
                if (ref != null) seen.add(ref.getAsString());
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * Replays the legacy id assignment (EntityRuntimeJsonWriter.buildGeometryTable): stem =
     * key minus prefix minus any trailing {@code _<n>}, then first-free candidate stem,
     * {@code stem_1}, {@code stem_2} ... by encounter order.
     *
     * @param refs the stage-major reference sequence
     * @return the re-minted key sequence
     */
    private static @NotNull List<String> mint(@NotNull List<String> refs) {
        Set<String> minted = new LinkedHashSet<>();
        for (String ref : refs) {
            String stem = ref.substring(GEOMETRY_PREFIX.length()).replaceAll("_\\d+$", "");
            String candidate = GEOMETRY_PREFIX + stem;
            int collision = 0;
            while (minted.contains(candidate)) {
                collision++;
                candidate = GEOMETRY_PREFIX + stem + "_" + collision;
            }
            minted.add(candidate);
        }
        return new ArrayList<>(minted);
    }

    private static @Nullable JsonObject axis(@NotNull JsonObject family, @NotNull String name) {
        if (!family.has("axes")) return null;
        JsonObject axes = family.getAsJsonObject("axes");
        return axes.has(name) ? axes.getAsJsonObject(name) : null;
    }

    static @NotNull JsonObject readResource(@NotNull String classpath) {
        InputStream in = Objects.requireNonNull(
            GeometryReplaySpike.class.getResourceAsStream(classpath), classpath);
        JsonObject parsed = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class)
            .getAsJsonObject();
        assertTrue(parsed.size() > 0, classpath + " parsed empty");
        return parsed;
    }

}
