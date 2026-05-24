package lib.minecraft.renderer.tooling.entity;

import com.google.gson.JsonObject;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Unit coverage for {@link EntityRuntimeJsonWriter#deriveFamilies}, the cross-entity family
 * clustering folded into {@link EntityRuntimeJsonWriter} from the deleted
 * {@code EntityFamilyResolver}. Tests build a small {@code entities} JSON object directly so
 * the clustering logic is exercised without standing up {@link EntityRuntimeJsonWriter#writeAll}.
 */
@DisplayName("EntityRuntimeJsonWriter.deriveFamilies")
class EntityRuntimeJsonWriterTest {

    @Test
    @DisplayName("two entities sharing geometry, one matches stem -> root selected, other mapped")
    void rootBySimpleStemMatch() {
        JsonObject entities = new JsonObject();
        addEntity(entities, "minecraft:cow", "geometry.cow");
        addEntity(entities, "minecraft:mooshroom", "geometry.cow");

        JsonObject families = EntityRuntimeJsonWriter.deriveFamilies(entities, new Diagnostics());

        assertThat(families.size(), equalTo(1));
        assertThat(families.get("minecraft:mooshroom").getAsString(), equalTo("minecraft:cow"));
        assertThat("root entry is not mapped to itself", families.has("minecraft:cow"), is(false));
    }

    @Test
    @DisplayName("'adult' geometry prefix is stripped before matching against entity id")
    void adultPrefixStripped() {
        JsonObject entities = new JsonObject();
        addEntity(entities, "minecraft:camel", "geometry.adultcamel");
        addEntity(entities, "minecraft:camel_husk", "geometry.adultcamel");

        JsonObject families = EntityRuntimeJsonWriter.deriveFamilies(entities, new Diagnostics());

        assertThat(families.size(), equalTo(1));
        assertThat(families.get("minecraft:camel_husk").getAsString(), equalTo("minecraft:camel"));
    }

    @Test
    @DisplayName("'baby' geometry prefix is stripped before matching against entity id")
    void babyPrefixStripped() {
        JsonObject entities = new JsonObject();
        addEntity(entities, "minecraft:turtle", "geometry.babyturtle");
        addEntity(entities, "minecraft:turtle_baby", "geometry.babyturtle");

        JsonObject families = EntityRuntimeJsonWriter.deriveFamilies(entities, new Diagnostics());

        assertThat(families.size(), equalTo(1));
        assertThat(families.get("minecraft:turtle_baby").getAsString(), equalTo("minecraft:turtle"));
    }

    @Test
    @DisplayName("shared geometry with no id-stem match is skipped (the illager case)")
    void noStemMatchSkipped() {
        JsonObject entities = new JsonObject();
        addEntity(entities, "minecraft:evoker", "geometry.illager");
        addEntity(entities, "minecraft:pillager", "geometry.illager");
        addEntity(entities, "minecraft:vindicator", "geometry.illager");

        Diagnostics diag = new Diagnostics();
        JsonObject families = EntityRuntimeJsonWriter.deriveFamilies(entities, diag);

        assertThat("no family emitted when no member id matches the geometry stem",
            families.size(), equalTo(0));
        assertThat("a 'skipped' diagnostic is recorded",
            diag.entries(), hasItem(org.hamcrest.Matchers.containsString("family skipped")));
    }

    @Test
    @DisplayName("single-entity geometry is not a family")
    void singleEntityIgnored() {
        JsonObject entities = new JsonObject();
        addEntity(entities, "minecraft:cow", "geometry.cow");

        JsonObject families = EntityRuntimeJsonWriter.deriveFamilies(entities, new Diagnostics());

        assertThat(families.size(), equalTo(0));
    }

    @Test
    @DisplayName("rows with variant_of are pre-filtered (climate variants don't cluster)")
    void variantRowsExcluded() {
        JsonObject entities = new JsonObject();
        addEntity(entities, "minecraft:cow", "geometry.cow");
        addEntity(entities, "minecraft:mooshroom", "geometry.cow");
        // Climate variant row; carries variant_of so it MUST be excluded from clustering.
        addVariantEntity(entities, "minecraft:cow_cold", "geometry.cow", "minecraft:cow");

        JsonObject families = EntityRuntimeJsonWriter.deriveFamilies(entities, new Diagnostics());

        // Only mooshroom -> cow should be emitted; cow_cold is excluded by variant_of.
        assertThat(families.size(), equalTo(1));
        assertThat(families.get("minecraft:mooshroom").getAsString(), equalTo("minecraft:cow"));
        assertThat(families.has("minecraft:cow_cold"), is(false));
    }

    @Test
    @DisplayName("entities without geometry_ref are skipped (defensive)")
    void rowsWithoutGeometryRefSkipped() {
        JsonObject entities = new JsonObject();
        addEntity(entities, "minecraft:cow", "geometry.cow");
        // Bare row with no geometry_ref - the writer doesn't always emit one (e.g. unresolved
        // entities). Clustering must skip these rather than NPE.
        JsonObject bare = new JsonObject();
        bare.addProperty("texture_ref", "entity/missing.png");
        entities.add("minecraft:bare", bare);

        JsonObject families = EntityRuntimeJsonWriter.deriveFamilies(entities, new Diagnostics());

        assertThat(families.size(), equalTo(0));
    }

    @Test
    @DisplayName("non-object entries (e.g. JsonArray) are tolerated")
    void nonObjectEntriesTolerated() {
        JsonObject entities = new JsonObject();
        addEntity(entities, "minecraft:cow", "geometry.cow");
        entities.add("minecraft:rogue", new com.google.gson.JsonArray());

        JsonObject families = EntityRuntimeJsonWriter.deriveFamilies(entities, new Diagnostics());

        // Single cow under geometry.cow is below the 2-member threshold; nothing emitted.
        assertThat(families.size(), equalTo(0));
    }

    // ============================================================================================
    // Fixture helpers
    // ============================================================================================

    private static void addEntity(JsonObject entities, String entityId, String geometryRef) {
        JsonObject row = new JsonObject();
        row.addProperty("geometry_ref", geometryRef);
        entities.add(entityId, row);
    }

    private static void addVariantEntity(JsonObject entities, String entityId, String geometryRef, String variantOf) {
        JsonObject row = new JsonObject();
        row.addProperty("geometry_ref", geometryRef);
        row.addProperty("variant_of", variantOf);
        entities.add(entityId, row);
    }
}
