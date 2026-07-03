package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The de-risk gate for the normalized entity-model schema: asserts that flattening the generated
 * {@code entity_models2.json} reproduces the committed, known-good {@code entity_models.json}
 * exactly (semantic JSON equality). Green means the family form is a loss-free re-encoding of the
 * current data, so {@code EntityFamilyJsonWriter} (group) and {@link EntityFamilyFlattener}
 * (flatten) are provably inverse over the real dataset.
 *
 * <p>The comparison is order-insensitive and value-verbatim - it walks entity ids and reports the
 * first differing field so a regression points straight at the offending row. Fixture cases pin the
 * inverse behaviour (variant expansion, plain-family carry-through) independent of the big file.
 */
@DisplayName("entity_models2.json round-trip == entity_models.json")
class EntityModelsV2RoundTripTest {

    private static final String FLAT_RESOURCE = "/lib/minecraft/renderer/entity_models.json";
    private static final String FAMILY_RESOURCE = "/lib/minecraft/renderer/entity_models2.json";

    @Test
    @DisplayName("flatten(entity_models2.json) reproduces entity_models.json (entities + families)")
    void roundTripMatchesKnownGood() {
        JsonObject flat = readResource(FLAT_RESOURCE);
        JsonObject family = readResource(FAMILY_RESOURCE);

        EntityFamilyFlattener.Flat rebuilt = EntityFamilyFlattener.flattenV2(family.getAsJsonObject("families"));

        JsonObject expectedEntities = flat.getAsJsonObject("entities");
        JsonObject expectedFamilies = flat.has("families") ? flat.getAsJsonObject("families") : new JsonObject();

        assertSemanticEqual("entities", expectedEntities, rebuilt.entities());
        assertSemanticEqual("families", expectedFamilies, rebuilt.families());
    }

    @Test
    @DisplayName("variant family expands to base (no variant_of) + variant rows, honouring geometry overrides")
    void variantFamilyExpands() {
        JsonObject families = new JsonObject();
        JsonObject cow = new JsonObject();
        cow.addProperty("geometry_ref", "geometry.cow");
        cow.addProperty("armor_type", "none");
        JsonObject variant = new JsonObject();
        variant.addProperty("id_encoded", true);
        variant.addProperty("default", "temperate");
        JsonObject options = new JsonObject();
        options.add("temperate", option("cow/cow_temperate", null));
        options.add("cold", option("cow/cow_cold", "geometry.coldcow"));
        variant.add("options", options);
        JsonObject axes = new JsonObject();
        axes.add("variant", variant);
        cow.add("axes", axes);
        families.add("minecraft:cow", cow);

        JsonObject entities = EntityFamilyFlattener.flattenV2(families).entities();

        JsonObject base = entities.getAsJsonObject("minecraft:cow_temperate");
        assertThat("base row exists", base, notNullValue());
        assertThat("base has no variant_of", base.has("variant_of"), is(false));
        assertThat(base.get("geometry_ref").getAsString(), is("geometry.cow"));
        assertThat(base.get("texture_ref").getAsString(), is("cow/cow_temperate"));

        JsonObject cold = entities.getAsJsonObject("minecraft:cow_cold");
        assertThat(cold.get("variant_of").getAsString(), is("minecraft:cow_temperate"));
        assertThat("geometry override honoured", cold.get("geometry_ref").getAsString(), is("geometry.coldcow"));
        assertThat(cold.get("texture_ref").getAsString(), is("cow/cow_cold"));
    }

    @Test
    @DisplayName("plain family keeps texture_ref + carried fields and re-emits family_of")
    void plainFamilyCarriesThrough() {
        JsonObject families = new JsonObject();
        JsonObject mooshroom = new JsonObject();
        mooshroom.addProperty("geometry_ref", "geometry.cow");
        mooshroom.addProperty("armor_type", "none");
        mooshroom.addProperty("texture_ref", "cow/mooshroom_red");
        com.google.gson.JsonArray blockOverlays = new com.google.gson.JsonArray();
        JsonObject overlay = new JsonObject();
        overlay.addProperty("block_id", "minecraft:red_mushroom");
        blockOverlays.add(overlay);
        mooshroom.add("block_overlays", blockOverlays);
        mooshroom.addProperty("family_of", "minecraft:cow_temperate");
        families.add("minecraft:mooshroom", mooshroom);

        EntityFamilyFlattener.Flat rebuilt = EntityFamilyFlattener.flattenV2(families);

        JsonObject row = rebuilt.entities().getAsJsonObject("minecraft:mooshroom");
        assertThat(row.get("texture_ref").getAsString(), is("cow/mooshroom_red"));
        assertThat("block_overlays carried verbatim", row.get("block_overlays"), is(mooshroom.get("block_overlays")));
        assertThat("family_of not left on the row", row.has("family_of"), is(false));
        assertThat("family_of re-collected into the families table",
            rebuilt.families().get("minecraft:mooshroom").getAsString(), is("minecraft:cow_temperate"));
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    /** Builds a variant option carrying a wild texture and an optional geometry override. */
    private static JsonObject option(String wildTexture, String geometryOverride) {
        JsonObject option = new JsonObject();
        JsonObject textures = new JsonObject();
        textures.addProperty("wild", wildTexture);
        option.add("textures", textures);
        if (geometryOverride != null) option.addProperty("geometry_ref", geometryOverride);
        return option;
    }

    /**
     * Asserts two id-keyed JSON objects are semantically equal (same keys, deep-equal values),
     * reporting the first differing entity id and field for a targeted failure.
     */
    private static void assertSemanticEqual(String label, JsonObject expected, JsonObject actual) {
        for (String key : expected.keySet())
            assertThat(label + " is missing id '" + key + "'", actual.has(key), is(true));
        for (String key : actual.keySet())
            assertThat(label + " has unexpected id '" + key + "'", expected.has(key), is(true));
        for (String key : expected.keySet()) {
            JsonElement e = expected.get(key), a = actual.get(key);
            if (!e.equals(a)) fail(firstDiff(label, key, e, a));
        }
    }

    /** Pinpoints the first differing field within a row (or falls back to the whole-value diff). */
    private static String firstDiff(String label, String key, JsonElement expected, JsonElement actual) {
        if (expected.isJsonObject() && actual.isJsonObject()) {
            JsonObject e = expected.getAsJsonObject(), a = actual.getAsJsonObject();
            for (String f : e.keySet()) {
                if (!a.has(f)) return label + "['" + key + "'] missing field '" + f + "' (expected " + e.get(f) + ")";
                if (!e.get(f).equals(a.get(f)))
                    return label + "['" + key + "'] field '" + f + "': expected " + e.get(f) + " but was " + a.get(f);
            }
            for (String f : a.keySet())
                if (!e.has(f)) return label + "['" + key + "'] unexpected field '" + f + "' = " + a.get(f);
        }
        return label + "['" + key + "']: expected " + expected + " but was " + actual;
    }

    /** Reads a classpath JSON resource into a {@link JsonObject}, asserting it is present. */
    private static JsonObject readResource(String path) {
        try (InputStream in = EntityModelsV2RoundTripTest.class.getResourceAsStream(path)) {
            assertThat("resource on classpath: " + path + " (run ./gradlew entityModels if missing)", in, notNullValue());
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
