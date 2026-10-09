package lib.minecraft.renderer.content.index;

import com.google.gson.Gson;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.content.table.EntityModelsTable;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The row the model table carries for a registered type vanilla draws nothing for - the types its
 * renderer registry binds to the no-op renderer. The row names the renderer and an adult form with no
 * mesh and no texture, and assembles into a definition whose body mesh holds no bone, which is what
 * {@link Entity#drawsNothing()} reads and what the production context answers empty by.
 *
 * <p>The adult coordinate reads three ways, and each is pinned: one the geometry table holds draws its
 * mesh, an omitted one draws none, and one the table lacks still refuses the load.
 */
@DisplayName("a row naming no mesh assembles as a definition that draws nothing")
class EntityMeshlessRowTest {

    /**
     * Plain, because every reader here is declared on the type it reads.
     */
    private static final @NotNull Gson GSON = new Gson();

    private static final @NotNull String COORD = "DrawnModel#createBodyLayer";

    /**
     * The three ids vanilla 26.1 binds to its no-op renderer.
     */
    static final @NotNull List<String> DRAWING_NOTHING = List.of(
        "minecraft:area_effect_cloud", "minecraft:interaction", "minecraft:marker");

    /**
     * Spells the row the tooling writes for a type vanilla draws nothing for.
     *
     * @param id the namespaced id the row is keyed by
     * @return the {@code models} member, as JSON text
     */
    private static @NotNull String meshless(@NotNull String id) {
        return """
            "%s": {
              "renderer": "net/minecraft/client/renderer/entity/NoopRenderer",
              "axes": { "age": { "options": { "adult": {} } } } }""".formatted(id);
    }

    /**
     * A drawn row over the one fixture mesh.
     */
    private static final @NotNull String DRAWN = """
        "minecraft:drawn": {
          "axes": { "age": { "options": {
              "adult": { "geometry": "DrawnModel#createBodyLayer", "texture": "drawn" } } } } }""";

    @Test
    @DisplayName("a row whose adult form names no mesh draws no bone, poses nothing and names no texture")
    void aMeshlessRowAssembles() {
        ConcurrentMap<String, Entity> built = assemble(DRAWN + ", " + String.join(", ",
            DRAWING_NOTHING.stream().map(EntityMeshlessRowTest::meshless).toList()));

        for (String id : DRAWING_NOTHING) {
            Entity row = built.get(id);
            assertThat(id + " assembles", row, is(notNullValue()));
            assertThat(id + " draws nothing", row.drawsNothing(), is(true));
            assertThat(id + " holds no bone", row.model().getBones(), is(anEmptyMap()));
            assertThat(id + " poses nothing", row.pose(), is(sameInstance(EntityPose.NONE)));
            assertThat(id + " draws no pass", row.overlays(), is(empty()));
            assertThat(id + " names no texture", row.textureRef(), is(Optional.empty()));
            assertThat(id + " ships no style row", row.styles(), is(sameInstance(StyleCatalog.BIND_ONLY)));
        }
        Entity drawn = built.get("minecraft:drawn");
        assertThat("a row naming its mesh draws it", drawn.drawsNothing(), is(false));
        assertThat("and reads its texture", drawn.textureRef(), is(Optional.of("drawn")));
    }

    @Test
    @DisplayName("a coordinate the geometry table lacks still refuses the load, naming the entity and the coordinate")
    void aDanglingCoordinateRefuses() {
        ContentException raised = assertThrows(ContentException.class, () -> assemble("""
            "minecraft:dangling": {
              "axes": { "age": { "options": {
                  "adult": { "geometry": "MissingModel#createBodyLayer", "texture": "dangling" } } } } }"""));
        assertThat(raised.getMessage(), containsString("minecraft:dangling"));
        assertThat(raised.getMessage(), containsString("MissingModel#createBodyLayer"));
    }

    @Test
    @DisplayName("a row naming no mesh but declaring an overlay pass refuses the load")
    void aMeshlessRowDeclaringAPassRefuses() {
        ContentException raised = assertThrows(ContentException.class, () -> assemble("""
            "minecraft:malformed": {
              "overlays": [ { "texture": "malformed_overlay" } ],
              "axes": { "age": { "options": { "adult": {} } } } }"""));
        assertThat(raised.getMessage(), containsString("minecraft:malformed"));
    }

    // ------------------------------------------------------------------------------------

    /**
     * Assembles the given model members over the one fixture mesh.
     *
     * @param members the {@code models} members, comma-separated
     * @return the assembled rows
     */
    static @NotNull ConcurrentMap<String, Entity> assemble(@NotNull String members) {
        EntityModelsTable raw = GSON.fromJson(
            "{ \"period_ticks\": 24, \"models\": { " + members + " } }", EntityModelsTable.class);
        return EntityIndexBuilder.assemble(Map.of(COORD, mesh()), raw, Map.of());
    }

    /**
     * The rows the shipped table carries for the three types vanilla draws nothing for, assembled as
     * the loader assembles them.
     *
     * @return the three rows keyed by id
     */
    static @NotNull ConcurrentMap<String, Entity> drawingNothing() {
        return assemble(String.join(", ", DRAWING_NOTHING.stream().map(EntityMeshlessRowTest::meshless).toList()));
    }

    private static @NotNull EntityMesh mesh() {
        EntityMesh model = new EntityMesh();
        model.getBones().put("body", new EntityMesh.Bone());
        return model;
    }

}
