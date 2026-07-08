package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Unit coverage for {@link EntityFamilyFlattener}, which flattens the normalized family-form
 * {@code entity_models.json} back into the per-entity rows + option side-channels the loader
 * consumes. Fixtures pin the load-bearing behaviour independently of the generated resource:
 * variant expansion (base row + {@code variant_of} siblings, geometry overrides), plain-family
 * carry-through, and the option side-channels (state textures, collar, baby geometry/texture).
 */
@DisplayName("EntityFamilyFlattener")
class EntityFamilyFlattenerTest {

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
        JsonArray variantValues = new JsonArray();
        variantValues.add("temperate");
        variantValues.add("cold");
        variant.add("values", variantValues);
        JsonObject options = new JsonObject();
        options.add("temperate", option("cow/cow_temperate", null));
        options.add("cold", option("cow/cow_cold", "geometry.coldcow"));
        variant.add("options", options);
        JsonObject axes = new JsonObject();
        axes.add("variant", variant);
        cow.add("axes", axes);
        families.add("minecraft:cow", cow);

        JsonObject entities = EntityFamilyFlattener.flatten(families).entities();

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
        JsonArray blockOverlays = new JsonArray();
        JsonObject overlay = new JsonObject();
        overlay.addProperty("block_id", "minecraft:red_mushroom");
        blockOverlays.add(overlay);
        mooshroom.add("block_overlays", blockOverlays);
        mooshroom.addProperty("family_of", "minecraft:cow_temperate");
        families.add("minecraft:mooshroom", mooshroom);

        EntityFamilyFlattener.Flat rebuilt = EntityFamilyFlattener.flatten(families);

        JsonObject row = rebuilt.entities().getAsJsonObject("minecraft:mooshroom");
        assertThat(row.get("texture_ref").getAsString(), is("cow/mooshroom_red"));
        assertThat("block_overlays carried verbatim", row.get("block_overlays"), is(mooshroom.get("block_overlays")));
        assertThat("family_of not left on the row", row.has("family_of"), is(false));
        assertThat("family_of re-collected into the families table",
            rebuilt.families().get("minecraft:mooshroom").getAsString(), is("minecraft:cow_temperate"));
    }

    @Test
    @DisplayName("option axes populate the side-channels (state textures, collar, baby geometry/texture)")
    void optionAxesSideChannels() {
        JsonObject families = new JsonObject();

        // Variant family with state + baby-per-option textures, plus a collar layer.
        JsonObject wolf = new JsonObject();
        wolf.addProperty("geometry_ref", "geometry.adultwolf");
        wolf.addProperty("armor_type", "none");
        JsonObject variant = new JsonObject();
        variant.addProperty("id_encoded", true);
        variant.addProperty("default", "pale");
        JsonArray variantValues = new JsonArray();
        variantValues.add("pale");
        variant.add("values", variantValues);
        JsonObject pale = option("wolf/wolf", null);
        pale.getAsJsonObject("textures").addProperty("tame", "wolf/wolf_tame");
        pale.getAsJsonObject("textures").addProperty("baby", "wolf/wolf_baby");
        JsonObject options = new JsonObject();
        options.add("pale", pale);
        variant.add("options", options);
        JsonObject axes = new JsonObject();
        axes.add("variant", variant);
        JsonObject age = new JsonObject();
        // Uniform axis contract: "values" lists the full domain; "options" carries only the "baby"
        // body (the default "adult" is the implicit baseline).
        JsonArray ageValues = new JsonArray();
        ageValues.add("adult");
        ageValues.add("baby");
        age.add("values", ageValues);
        JsonObject ageOptions = new JsonObject();
        JsonObject babyOpt = new JsonObject();
        babyOpt.addProperty("geometry_ref", "geometry.babywolf");
        ageOptions.add("baby", babyOpt);
        age.add("options", ageOptions);
        axes.add("age", age);
        wolf.add("axes", axes);
        JsonArray layers = new JsonArray();
        JsonObject collar = new JsonObject();
        collar.addProperty("id", "collar");
        JsonObject collarOverlay = new JsonObject();
        collarOverlay.addProperty("texture_ref", "wolf/wolf_collar");
        collar.add("overlay", collarOverlay);
        layers.add(collar);
        wolf.add("layers", layers);
        families.add("minecraft:wolf", wolf);

        EntityFamilyFlattener.Flat flat = EntityFamilyFlattener.flatten(families);

        assertThat(flat.stateTextures().get("minecraft:wolf_pale").get("tame"), is("wolf/wolf_tame"));
        assertThat(flat.stateTextures().get("minecraft:wolf_pale").get("baby"), is("wolf/wolf_baby"));
        assertThat(flat.collarTextures().get("minecraft:wolf_pale"), is("wolf/wolf_collar"));
        assertThat(flat.babyGeometry().get("minecraft:wolf_pale"), is("geometry.babywolf"));
    }

    /** Builds a variant option carrying a wild texture and an optional geometry override. */
    private static JsonObject option(String wildTexture, String geometryOverride) {
        JsonObject option = new JsonObject();
        JsonObject textures = new JsonObject();
        textures.addProperty("wild", wildTexture);
        option.add("textures", textures);
        if (geometryOverride != null) option.addProperty("geometry_ref", geometryOverride);
        return option;
    }
}
