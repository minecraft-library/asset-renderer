package lib.minecraft.renderer.content.index;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.PaceCeiling;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawAxes;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawAxis;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawModel;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOption;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOverlay;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOverlayBaby;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawPipeline;
import lib.minecraft.renderer.content.table.EntityModelsTable;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The style grammar the model table's rows carry beyond what the shipped table uses, loaded from fixture
 * tables and held to what each member draws and what each refuses.
 *
 * <p>A pass's {@code opacity} is an expression over the render state alone, so it evaluates against one
 * frame's binding with nothing else in hand: a read of a bone or of a shared entry has no table to
 * answer from, and is refused where the row is read. Where a frame poses nothing - a bind render - the
 * pass draws at the expression's value with every input at rest, which the load writes onto the
 * declaration through the same conversion a constant {@code pipeline.alpha} takes, so the two spellings
 * of one opacity draw alike. A constant at or under zero is refused, the generator dropping any pass
 * that would draw nothing.
 *
 * <p>A stroke's {@code rate} and a trapezoid's four spans load onto the driver's timing, a span the row
 * does not spell reading zero. A timing member on a wave that does not read it, a stroke that does not
 * advance and a trapezoid spanning no tick are refused where the row is read, naming the entity and the
 * row, so the driver never meets a timing it would refuse.
 *
 * <p>A stride row's {@code ceiling} - the fastest walk vanilla gives its subject at its age - loads onto
 * that row alone and composes over no base. One on a row that is not the stride, one missing its speed or
 * its rate, and one outside the range a walk pair holds are refused where the row is read, naming the
 * entity, so the ceiling never meets a value it would refuse.
 *
 * <p>A refusal names a row by its place in the table. A baby form is loaded from the rows carrying a
 * delta alone, and loaded first, so a refusal counting the baby forms would name a different row than
 * the table spells. Gson reads a JSON {@code null} into the member as a node rather than as nothing,
 * and the load takes it as the absent member it spells.
 */
@DisplayName("the style grammar loads what it draws and refuses what it cannot")
class EntityStyleGrammarLoadTest {

    private static final @NotNull String ENTITY = "minecraft:test";
    private static final @NotNull String COORD = "TestModel#createBodyLayer";

    /** The warden's first pulsating-spots layer, spelled as the model table spells an opacity. */
    private static final @NotNull String SPOTS = "{\"max\": [{\"const\": 0.0}, {\"mul\": [{\"mth_cos\": [{\"f2d\": "
        + "[{\"mul\": [{\"input\": \"ageInTicks\"}, {\"const\": 0.045}]}]}]}, {\"const\": 0.25}]}]}";

    /** The same layer half a cycle on, dark at rest. */
    private static final @NotNull String ANTI_PHASE_SPOTS = "{\"max\": [{\"const\": 0.0}, {\"mul\": [{\"mth_cos\": "
        + "[{\"f2d\": [{\"add\": [{\"mul\": [{\"input\": \"ageInTicks\"}, {\"const\": 0.045}]}, {\"const\": 3.1415927}]}]}]}, "
        + "{\"const\": 0.25}]}]}";

    /** A standing row ramping elapsed age, which a stride composes over. */
    private static final @NotNull String IDLE_ROW = "{\"id\": \"idle\", \"sources\": [\"figure\"], "
        + "\"drives\": [{\"field\": \"ageInTicks\", \"wave\": \"ramp\", \"extent\": 1.0}]}";

    @Test
    @DisplayName("an opacity loads its pass at its value with every input at rest, as a constant alpha loads")
    void anOpacityLoadsAtItsAtRestValue() {
        assertEquals(PassDeclaration.drawnAlpha(0.25f), pass(null, SPOTS).pass().alpha(),
            "the spots draw at a quarter when nothing has aged");
        assertEquals(0f, pass(null, ANTI_PHASE_SPOTS).pass().alpha(),
            "and their anti-phase layer draws nothing");
        assertEquals(pass(0.25f, null).pass().alpha(), pass(null, SPOTS).pass().alpha(),
            "a constant alpha and an opacity's at-rest value draw by one rule");
    }

    @Test
    @DisplayName("an opacity reading a bone is refused, having no mesh to read it from")
    void anOpacityReadingABoneIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () -> pass(null,
            "{\"mul\": [{\"bone\": [\"body\", \"x_rot\"]}, {\"const\": 0.5}]}"));
        assertEquals("Opacity of 'minecraft:test overlay 0' reads 'bone', which a self-contained expression does not",
            raised.getMessage());
    }

    @Test
    @DisplayName("an opacity naming a shared entry is refused, having no table to resolve it against")
    void anOpacityReadingASharedEntryIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () -> pass(null,
            "{\"max\": [{\"const\": 0.0}, {\"ref\": 0}]}"));
        assertEquals("Opacity of 'minecraft:test overlay 0' reads 'ref', which a self-contained expression does not",
            raised.getMessage());
    }

    @Test
    @DisplayName("a constant alpha of zero is refused, a pass that draws nothing being one the generator drops")
    void anAlphaOfZeroIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () -> pass(0f, null));
        assertEquals("Entity 'minecraft:test' overlay '0' declares alpha '0.0', and a shipped pass draws",
            raised.getMessage());
    }

    @Test
    @DisplayName("a refusal names a baby form's row by its place in the table, not in the baby forms")
    void aBabyFormRefusalNamesItsTableRow() {
        RawOverlay plain = overlay(null, null, null);
        RawOverlay spotted = overlay(0.25f, JsonParser.parseString(SPOTS), new RawOverlayBaby(null, null, null));
        ContentException raised = assertThrows(ContentException.class, () -> load(List.of(plain, spotted), true));
        assertEquals("Entity 'minecraft:test' overlay '1' declares an alpha and an opacity, and a pass has one",
            raised.getMessage());
    }

    @Test
    @DisplayName("an opacity reads through the table's Gson, and a JSON null reads as no opacity")
    void anOpacityReadsThroughTheTableGson() {
        String json = "{\"models\": {\"" + ENTITY + "\": {"
            + "\"overlays\": ["
            + "{\"texture\": \"test\", \"opacity\": " + SPOTS + "}, "
            + "{\"texture\": \"test\", \"pipeline\": {\"emissive\": true, \"alpha\": 0.25}, \"opacity\": null}], "
            + "\"axes\": {\"age\": {\"options\": {\"adult\": {\"geometry\": \"" + COORD + "\", \"texture\": \"test\"}}}}"
            + "}}}";
        EntityModelsTable table = GsonSettings.defaults().create()
            .fromJson(JsonParser.parseString(json), EntityModelsTable.class);
        List<Entity.OverlayLayer> passes = EntityIndexBuilder.assemble(Map.of(COORD, mesh()), table, Map.of())
            .get(ENTITY).overlays();

        assertTrue(passes.getFirst().opacity().isPresent(), "the member loads onto its pass as an expression");
        assertEquals(PassDeclaration.drawnAlpha(0.25f), passes.getFirst().pass().alpha(),
            "drawn at its value with every input at rest");
        assertTrue(passes.getLast().opacity().isEmpty(), "a JSON null is no opacity");
        assertEquals(PassDeclaration.drawnAlpha(0.25f), passes.getLast().pass().alpha(),
            "so its pass draws at the constant alpha beside it");
    }

    // ------------------------------------------------------------------------------------
    // drive timing
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a stroke's rate and a trapezoid's spans load onto the driver's timing, an absent span reading zero")
    void aDrivesTimingLoadsOntoItsDriver() {
        StyleDriver stroke = driver("{\"field\": \"tentacleAngle\", \"wave\": \"stroke\", \"rest\": 0.0, "
            + "\"extent\": 0.7853982, \"rate\": 0.11559614}");
        assertEquals(StyleDriver.Wave.STROKE, stroke.wave());
        assertEquals(StyleDriver.Timing.stroke(0.11559614f), stroke.timing(), "the stroke reads its rate");

        StyleDriver peek = driver("{\"field\": \"peekAmount\", \"wave\": \"trapezoid\", \"rest\": 0.0, "
            + "\"extent\": 0.29999998, \"rise\": 6, \"dwell\": 14, \"fall\": 6, \"gap\": 0}");
        assertEquals(StyleDriver.Timing.trapezoid(6, 14, 6, 0), peek.timing(), "the trapezoid reads its spans");

        StyleDriver heart = driver("{\"field\": \"heartAnimation\", \"wave\": \"trapezoid\", \"rest\": 0.0, "
            + "\"extent\": 1.0, \"fall\": 10, \"gap\": 30}");
        assertEquals(StyleDriver.Timing.trapezoid(0, 0, 10, 30), heart.timing(),
            "a span the row does not spell reads zero");

        StyleDriver held = driver("{\"field\": \"croakAnimationState\", \"wave\": \"hold\", \"extent\": 1.0}");
        assertEquals(StyleDriver.Timing.NONE, held.timing(), "every other wave reads no timing");
    }

    @Test
    @DisplayName("a timing member on a wave that does not read it is refused")
    void aTimingMemberTheWaveDoesNotReadIsRefused() {
        ContentException rated = assertThrows(ContentException.class, () ->
            driver("{\"field\": \"croakAnimationState\", \"wave\": \"hold\", \"extent\": 1.0, \"rate\": 0.1}"));
        assertEquals("Entity 'minecraft:test' style 'idle' drives 'croakAnimationState' by 'hold', which reads no 'rate'",
            rated.getMessage());

        ContentException spanned = assertThrows(ContentException.class, () ->
            driver("{\"field\": \"tentacleAngle\", \"wave\": \"stroke\", \"extent\": 0.7853982, "
                + "\"rate\": 0.11559614, \"dwell\": 4}"));
        assertEquals("Entity 'minecraft:test' style 'idle' drives 'tentacleAngle' by 'stroke', which reads no 'dwell'",
            spanned.getMessage());

        ContentException shaped = assertThrows(ContentException.class, () ->
            driver("{\"field\": \"peekAmount\", \"wave\": \"trapezoid\", \"extent\": 0.29999998, "
                + "\"rise\": 6, \"rate\": 0.1}"));
        assertEquals("Entity 'minecraft:test' style 'idle' drives 'peekAmount' by 'trapezoid', which reads no 'rate'",
            shaped.getMessage());
    }

    @Test
    @DisplayName("a stroke with no rate, or one at most zero, is refused, a stroke being a phase that advances")
    void aStrokeWithNoRateIsRefused() {
        ContentException absent = assertThrows(ContentException.class, () ->
            driver("{\"field\": \"tentacleAngle\", \"wave\": \"stroke\", \"extent\": 0.7853982}"));
        assertEquals("Entity 'minecraft:test' style 'idle' strokes 'tentacleAngle' at 'null' a tick, and a stroke advances",
            absent.getMessage());

        ContentException still = assertThrows(ContentException.class, () ->
            driver("{\"field\": \"tentacleAngle\", \"wave\": \"stroke\", \"extent\": 0.7853982, \"rate\": 0.0}"));
        assertEquals("Entity 'minecraft:test' style 'idle' strokes 'tentacleAngle' at '0.0' a tick, and a stroke advances",
            still.getMessage());
    }

    @Test
    @DisplayName("a trapezoid spanning no tick, or carrying a negative span, is refused")
    void aTrapezoidSpanningNoTickIsRefused() {
        ContentException empty = assertThrows(ContentException.class, () ->
            driver("{\"field\": \"peekAmount\", \"wave\": \"trapezoid\", \"extent\": 0.29999998}"));
        assertEquals("Entity 'minecraft:test' style 'idle' shapes 'peekAmount' over '0 + 0 + 0 + 0' ticks, "
            + "and a trapezoid spans at least one", empty.getMessage());

        ContentException negative = assertThrows(ContentException.class, () ->
            driver("{\"field\": \"peekAmount\", \"wave\": \"trapezoid\", \"extent\": 0.29999998, "
                + "\"rise\": -1, \"dwell\": 14, \"fall\": 6}"));
        assertEquals("Entity 'minecraft:test' style 'idle' shapes 'peekAmount' over '-1 + 14 + 6 + 0' ticks, "
            + "and a trapezoid spans at least one", negative.getMessage());
    }

    // ------------------------------------------------------------------------------------
    // stride ceiling
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a stride's ceiling loads onto that row alone - neither the row it composes over nor one composed over it carries one")
    void aStrideCeilingLoadsOntoItsRow() {
        List<PoseStyle> rows = styles(IDLE_ROW + ", " + stride("{\"speed\": 0.69693, \"rate\": 2.09079}")
            + ", {\"id\": \"dash\", \"base\": \"stride\", \"sources\": [\"stride\"]}");

        assertEquals(Optional.of(new PaceCeiling(0.69693f, 2.09079f)), rows.get(1).ceiling(),
            "the stride reads its ceiling");
        assertTrue(rows.getFirst().ceiling().isEmpty(), "the idle it composes over carries none");
        assertTrue(rows.getLast().ceiling().isEmpty(), "and a row composed over it inherits none");
        assertTrue(styles(IDLE_ROW + ", " + stride(null)).getLast().ceiling().isEmpty(),
            "a stride naming no ceiling carries none");
    }

    @Test
    @DisplayName("a ceiling on a row that is not the stride is refused")
    void aCeilingOffTheStrideIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () -> styles(
            "{\"id\": \"idle\", \"sources\": [\"figure\"], \"ceiling\": {\"speed\": 0.5, \"rate\": 0.5}}"));
        assertEquals("Entity 'minecraft:test' style 'idle' carries a ceiling, which only a stride row carries",
            raised.getMessage());
    }

    @Test
    @DisplayName("a ceiling naming no speed, or no rate, is refused")
    void aCeilingMissingAMemberIsRefused() {
        ContentException noRate = assertThrows(ContentException.class, () ->
            styles(IDLE_ROW + ", " + stride("{\"speed\": 0.5}")));
        assertEquals("Entity 'minecraft:test' stride ceiling names no 'rate'", noRate.getMessage());

        ContentException noSpeed = assertThrows(ContentException.class, () ->
            styles(IDLE_ROW + ", " + stride("{\"rate\": 0.5}")));
        assertEquals("Entity 'minecraft:test' stride ceiling names no 'speed'", noSpeed.getMessage());
    }

    @Test
    @DisplayName("a ceiling swinging past one, or whose walk clock does not advance, is refused")
    void aCeilingOutOfRangeIsRefused() {
        ContentException fast = assertThrows(ContentException.class, () ->
            styles(IDLE_ROW + ", " + stride("{\"speed\": 1.5, \"rate\": 1.0}")));
        assertEquals("Entity 'minecraft:test' stride ceiling swings '1.5' and advances '1.0' a tick, "
            + "outside (0, 1] and above zero", fast.getMessage());

        ContentException still = assertThrows(ContentException.class, () ->
            styles(IDLE_ROW + ", " + stride("{\"speed\": 0.5, \"rate\": 0.0}")));
        assertEquals("Entity 'minecraft:test' stride ceiling swings '0.5' and advances '0.0' a tick, "
            + "outside (0, 1] and above zero", still.getMessage());
    }

    // ------------------------------------------------------------------------------------

    /**
     * The fixture entity's one pass, drawing the body's mesh.
     *
     * @param alpha the pass's {@code pipeline.alpha}, or {@code null} where the row declares none
     * @param opacity the pass's {@code opacity} as JSON, or {@code null} where the row declares none
     * @return the loaded pass
     */
    private static @NotNull Entity.OverlayLayer pass(@Nullable Float alpha, @Nullable String opacity) {
        RawOverlay row = overlay(alpha, opacity == null ? null : JsonParser.parseString(opacity), null);
        return load(List.of(row), false).overlays().getFirst();
    }

    /**
     * One overlay row drawing the body's mesh.
     *
     * @param alpha the row's {@code pipeline.alpha}, or {@code null} where it declares none
     * @param opacity the row's {@code opacity}, or {@code null} where it declares none
     * @param baby the row's age delta, or {@code null} where it draws on an adult only
     * @return the row
     */
    private static @NotNull RawOverlay overlay(
        @Nullable Float alpha, @Nullable JsonElement opacity, @Nullable RawOverlayBaby baby) {

        return new RawOverlay(
            null,                                                         // geometry
            null,                                                         // no_hat_geometry
            "test",                                                       // texture
            null,                                                         // tint
            null,                                                         // tint_by
            null,                                                         // texture_by
            alpha == null ? null : new RawPipeline(true, null, alpha, false, false),  // pipeline
            null,                                                         // texture_scroll
            false,                                                        // skip_bounds
            null,                                                         // when
            baby,                                                         // baby
            opacity);                                                     // opacity
    }

    /**
     * The fixture entity carrying the given overlay rows.
     *
     * @param overlays the family's overlay rows, in table order
     * @param baby whether the family declares a baby form, drawing the adult's mesh
     * @return the loaded entity
     */
    private static @NotNull Entity load(@NotNull List<RawOverlay> overlays, boolean baby) {
        Map<String, RawModel> models = new LinkedHashMap<>();
        models.put(ENTITY, new RawModel(
            null,                                                         // renderer
            null,                                                         // render
            overlays,                                                     // overlays
            null,                                                         // block_overlays
            null,                                                         // armor
            null,                                                         // wings
            null,                                                         // equipment
            ageAxis(baby),                                                // axes
            null,                                                         // members
            null));                                                       // styles
        return EntityIndexBuilder.assemble(Map.of(COORD, mesh()), new EntityModelsTable(models, null), Map.of())
            .get(ENTITY);
    }

    /**
     * The one driver a fixture entity's {@code idle} row carries, read through the table's Gson.
     *
     * @param drive the row's one drive as JSON
     * @return the loaded driver of the field the drive names
     */
    private static @NotNull StyleDriver driver(@NotNull String drive) {
        String field = JsonParser.parseString(drive).getAsJsonObject().get("field").getAsString();
        PoseStyle idle = styles("{\"id\": \"idle\", \"sources\": [\"figure\"], \"drives\": [" + drive + "]}")
            .getFirst();
        return idle.drivers().get(field);
    }

    /**
     * A stride row composed over {@link #IDLE_ROW}, its ceiling spelled as the row's last member.
     *
     * @param ceiling the row's {@code ceiling} as JSON, or {@code null} where the row names none
     * @return the row as JSON
     */
    private static @NotNull String stride(@Nullable String ceiling) {
        return "{\"id\": \"stride\", \"base\": \"idle\", \"sources\": [\"stride\"], \"drives\": ["
            + "{\"field\": \"walkAnimationSpeed\", \"wave\": \"hold\", \"extent\": 1.0}, "
            + "{\"field\": \"walkAnimationPos\", \"wave\": \"ramp\", \"extent\": 1.0}]"
            + (ceiling == null ? "" : ", \"ceiling\": " + ceiling) + "}";
    }

    /**
     * The style rows a fixture entity carrying the given rows loads, read through the table's Gson.
     *
     * @param rows the entity's {@code styles} rows as comma-separated JSON, in table order
     * @return the loaded rows, in table order
     */
    private static @NotNull List<PoseStyle> styles(@NotNull String rows) {
        String json = "{\"period_ticks\": 24, \"models\": {\"" + ENTITY + "\": {"
            + "\"axes\": {\"age\": {\"options\": {\"adult\": {\"geometry\": \"" + COORD + "\", \"texture\": \"test\"}}}}, "
            + "\"styles\": [" + rows + "]"
            + "}}}";
        EntityModelsTable table = GsonSettings.defaults().create()
            .fromJson(JsonParser.parseString(json), EntityModelsTable.class);
        return EntityIndexBuilder.assemble(Map.of(COORD, mesh()), table, Map.of())
            .get(ENTITY).styles().styles();
    }

    /** The fixture body - one bone, which no opacity reads. */
    private static @NotNull EntityMesh mesh() {
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone());
        return mesh;
    }

    private static @NotNull RawAxes ageAxis(boolean baby) {
        Map<String, RawOption> options = new LinkedHashMap<>();
        options.put("adult", new RawOption(COORD, "test", null, null, null, null, null, null));
        if (baby) options.put("baby", new RawOption(COORD, "test_baby", null, null, null, null, null, null));
        return new RawAxes(null, new RawAxis(null, options), null, null, null);
    }

}
