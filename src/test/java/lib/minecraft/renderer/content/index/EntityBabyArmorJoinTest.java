package lib.minecraft.renderer.content.index;

import com.google.gson.Gson;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.content.table.EntityModelsTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * The two joins a family's row makes against the geometry table on the side of its body: the baby mesh
 * its {@code age.baby} option names, and the worn-armor shell its {@code armor} node names.
 * <p>
 * Each reads three ways. A family declaring no baby, or carrying no armor row, has none and says
 * nothing - that is a row without the form, not a broken one. A baby naming a coordinate the table
 * lacks, or an armor row whose shell does not resolve, has none too, but is a defect: the load warns
 * once, names what it could not join, and drops what hangs off it rather than drawing a guess. A join
 * that resolves draws the mesh it names.
 */
@DisplayName("A row's baby and armor joins tell a family declaring none from one naming what is not there")
class EntityBabyArmorJoinTest {

    /** Plain, because every reader here is declared on the type it reads. */
    private static final @NotNull Gson GSON = new Gson();

    private static final @NotNull String ENTITY = "minecraft:test";
    private static final @NotNull String BODY = "TestModel#createBodyLayer";
    private static final @NotNull String BABY = "TestModel#createBabyLayer";
    private static final @NotNull String ARMOR = "TestModel#createArmorLayer";
    private static final @NotNull String MISSING = "TestModel#createMissingLayer";

    /** The armor row's two layer deformations, as the shipped table writes them. */
    private static final @NotNull String GROW = "\"grow\": { \"inner\": 0.5, \"outer\": 1.0 }";

    private static final @NotNull EntityMesh BODY_MESH = mesh();
    private static final @NotNull EntityMesh BABY_MESH = mesh();
    private static final @NotNull EntityMesh ARMOR_MESH = mesh();

    @Test
    @DisplayName("a family declaring no baby has no baby form and warns nothing, though a pass declares a baby delta")
    void aFamilyDeclaringNoBabyWarnsNothing() {
        Assembled built = assemble(null, true, null);

        assertThat("no baby form", built.row().axes().baby().isPresent(), is(false));
        assertThat("and nothing to say about it", built.warnings(), is(empty()));
    }

    @Test
    @DisplayName("a baby naming a geometry the table lacks warns once and drops every baby pass")
    void aBabyNamingAMissingGeometryWarnsAndDrops() {
        Assembled built = assemble(MISSING, true, null);

        assertThat("no baby form", built.row().axes().baby().isPresent(), is(false));
        assertThat("the load names the coordinate it could not join", built.warnings(), contains(
            "entity '" + ENTITY + "' baby overlay references geometry '" + MISSING
                + "' absent from entity_geometry; every baby form dropped"));
        assertThat("and the adult still draws its own pass", built.row().overlays().size(), is(1));
    }

    @Test
    @DisplayName("a baby naming a geometry the table lacks warns nothing where no pass declares a baby delta")
    void aBabyNamingAMissingGeometryWithNoBabyPassWarnsNothing() {
        Assembled built = assemble(MISSING, false, null);

        assertThat("no baby form", built.row().axes().baby().isPresent(), is(false));
        assertThat("the warning is about the passes it drops, and there are none", built.warnings(), is(empty()));
    }

    @Test
    @DisplayName("a baby whose geometry resolves draws that mesh and its baby passes")
    void aBabyWhoseGeometryResolvesDraws() {
        Assembled built = assemble(BABY, true, null);

        Entity baby = built.row().axes().baby().orElseThrow();
        assertThat("the baby draws the mesh its coordinate names", baby.model(), is(sameInstance(BABY_MESH)));
        assertThat("with the pass's baby delta", baby.overlays().getFirst().textureRef().orElseThrow(),
            is("test_eyes_baby"));
        assertThat(built.warnings(), is(empty()));
    }

    @Test
    @DisplayName("a family carrying no armor row wears none and warns nothing")
    void aFamilyWithNoArmorRowWarnsNothing() {
        Assembled built = assemble(BABY, true, null);

        assertThat("no shell", built.row().humanoidArmor().isPresent(), is(false));
        assertThat("on the baby form either", built.row().axes().baby().orElseThrow().humanoidArmor().isPresent(),
            is(false));
        assertThat(built.warnings(), is(empty()));
    }

    @Test
    @DisplayName("an armor row whose shell does not resolve warns once and drops the wearer's armor, the row still loading")
    void anArmorRowThatDoesNotResolveWarnsAndDrops() {
        Map<String, String> unresolved = Map.of(
            "{ \"geometry\": \"" + MISSING + "\", " + GROW + " }",
            "entity '" + ENTITY + "' adult armor shell references geometry '" + MISSING
                + "' absent from entity_geometry; wearer dropped",
            "{ \"geometry\": \"" + ARMOR + "\" }",
            "entity '" + ENTITY + "' adult armor shell carries no mesh reference or deformations; wearer dropped",
            "{ \"geometry\": \"" + ARMOR + "\", " + GROW + ", \"alternate\": { \"geometry\": \"" + ARMOR + "\", "
                + GROW + " } }",
            "entity '" + ENTITY + "' alternate armor shell names no appearance selection; wearer dropped");

        unresolved.forEach((armor, warning) -> {
            Assembled built = assemble(BABY, true, armor);

            assertThat(armor + " still loads its row", built.row().model(), is(sameInstance(BODY_MESH)));
            assertThat(armor + " wears no shell", built.row().humanoidArmor().isPresent(), is(false));
            assertThat(armor + " says why", built.warnings(), contains(warning));
        });
    }

    @Test
    @DisplayName("an armor row that resolves dresses the wearer and its baby form in the shell it names")
    void anArmorRowThatResolvesDresses() {
        Assembled built = assemble(BABY, true, "{ \"geometry\": \"" + ARMOR + "\", " + GROW + " }");

        Shell shell = built.row().humanoidArmor().orElseThrow();
        assertThat("the shell is the mesh the row names", shell.mesh(), is(sameInstance(ARMOR_MESH)));
        assertThat("and the baby form wears the same one",
            built.row().axes().baby().orElseThrow().humanoidArmor().orElseThrow(), is(sameInstance(shell)));
        assertThat(built.warnings(), is(empty()));
    }

    // ------------------------------------------------------------------------------------

    /**
     * One assembled row and every line the load wrote to the error stream while assembling it.
     *
     * @param row the assembled row
     * @param warnings the lines the load warned
     */
    private record Assembled(@NotNull Entity row, @NotNull List<String> warnings) {}

    /**
     * Assembles a one-family table over the fixture geometry, capturing what the load warns.
     *
     * @param baby the coordinate the family's {@code age.baby} option names, or {@code null} for a family
     *     declaring no baby
     * @param babyPass whether the family's one pass declares a {@code baby} delta
     * @param armor the family's {@code armor} node as JSON, or {@code null} for a family carrying none
     * @return the row and the warnings
     */
    private static @NotNull Assembled assemble(@Nullable String baby, boolean babyPass, @Nullable String armor) {
        String overlay = babyPass
            ? "{ \"texture\": \"test_eyes\", \"baby\": { \"texture\": \"test_eyes_baby\" } }"
            : "{ \"texture\": \"test_eyes\" }";
        String options = "\"adult\": { \"geometry\": \"" + BODY + "\", \"texture\": \"test\" }"
            + (baby == null ? "" : ", \"baby\": { \"geometry\": \"" + baby + "\", \"texture\": \"test_baby\" }");
        String json = """
            { "models": { "%s": {
                "overlays": [ %s ],
                "axes": { "age": { "options": { %s } } }%s } } }"""
            .formatted(ENTITY, overlay, options, armor == null ? "" : ", \"armor\": " + armor);
        EntityModelsTable raw = GSON.fromJson(json, EntityModelsTable.class);

        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        Entity row;
        try {
            row = EntityIndexBuilder.assemble(Map.of(BODY, BODY_MESH, BABY, BABY_MESH, ARMOR, ARMOR_MESH), raw,
                Map.of()).get(ENTITY);
        } finally {
            System.setErr(original);
        }
        assertThat("the fixture assembles its row", row, is(notNullValue()));
        return new Assembled(row, captured.toString(StandardCharsets.UTF_8).lines().toList());
    }

    private static @NotNull EntityMesh mesh() {
        EntityMesh model = new EntityMesh();
        model.getBones().put("body", new EntityMesh.Bone());
        return model;
    }

}
