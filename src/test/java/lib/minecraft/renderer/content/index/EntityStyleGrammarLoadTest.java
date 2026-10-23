package lib.minecraft.renderer.content.index;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PaceCeiling;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawAxes;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawAxis;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawModel;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOption;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOverlay;
import lib.minecraft.renderer.content.table.EntityModelsTable.RawOverlayBaby;
import lib.minecraft.renderer.content.table.EntityModelsTable;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * declaration through the one conversion every frame's opacity takes - a constant opacity, the grammar's
 * own {@code const}, drawing at its value. A constant at or under zero is refused, the generator dropping
 * any pass that would draw nothing. Each row's opacity is read once by its place in the table, so a baby
 * form draws the very expression its adult row does.
 *
 * <p>A stroke's {@code rate} and a trapezoid's four spans load onto the driver's wave, a span the row
 * does not spell reading zero. A timing member on a wave that does not read it, a stroke that does not
 * advance and a trapezoid spanning no tick are refused where the row is read, naming the entity and the
 * row.
 *
 * <p>A family's {@code ceilings} - the fastest walk vanilla gives its subject at each age its stride walks
 * at a pace of its own - load onto its catalog keyed by age. One keyed by a token that is not an age or by
 * an age no stride row walks at, one missing its speed or its rate, one outside the range a walk pair
 * holds, and one on a family shipping no styles are refused where the row is read, naming the entity.
 *
 * <p>A source's {@code gate} loads as the {@link Flag} it names, and a gate naming no flag is refused
 * where the row is read.
 *
 * <p>A {@code carried} or {@code equipment} option's {@code pose} - the body pose its wearer takes while a
 * caller selects a block, or fills the slot the option keys - loads as a form of the row: the row's own
 * mesh at the pose the pose table carries under that key, each pass that shared the row's pose sharing
 * it. A key the pose table lacks answers the pose of a model that poses nothing, as a size option's does.
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
    @DisplayName("an opacity loads its pass at its value with every input at rest, as a constant opacity loads")
    void anOpacityLoadsAtItsAtRestValue() {
        assertEquals(PassDeclaration.drawnAlpha(0.25f), pass(SPOTS).pass().alpha(),
            "the spots draw at a quarter when nothing has aged");
        assertEquals(0f, pass(ANTI_PHASE_SPOTS).pass().alpha(),
            "and their anti-phase layer draws nothing");
        assertEquals(pass("{\"const\": 0.25}").pass().alpha(), pass(SPOTS).pass().alpha(),
            "a constant opacity and an opacity's at-rest value draw by one rule");
    }

    @Test
    @DisplayName("an opacity reading a bone is refused, having no mesh to read it from")
    void anOpacityReadingABoneIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () -> pass(
            "{\"mul\": [{\"bone\": [\"body\", \"x_rot\"]}, {\"const\": 0.5}]}"));
        assertEquals("Opacity of 'minecraft:test overlay 0' reads 'bone', which a self-contained expression does not",
            raised.getMessage());
    }

    @Test
    @DisplayName("an opacity naming a shared entry is refused, having no table to resolve it against")
    void anOpacityReadingASharedEntryIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () -> pass(
            "{\"max\": [{\"const\": 0.0}, {\"ref\": 0}]}"));
        assertEquals("Opacity of 'minecraft:test overlay 0' reads 'ref', which a self-contained expression does not",
            raised.getMessage());
    }

    @Test
    @DisplayName("a constant opacity of zero is refused, a pass that draws nothing being one the generator drops")
    void aConstantOpacityOfZeroIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () -> pass("{\"const\": 0.0}"));
        assertEquals("Entity 'minecraft:test' overlay '0' declares opacity '0.0', and a shipped pass draws",
            raised.getMessage());
    }

    @Test
    @DisplayName("a refusal names a baby form's row by its place in the table, not in the baby forms")
    void aBabyFormRefusalNamesItsTableRow() {
        RawOverlay plain = overlay(null, null);
        RawOverlay boned = overlay(JsonParser.parseString("{\"mul\": [{\"bone\": [\"body\", \"x_rot\"]}, {\"const\": 0.5}]}"),
            new RawOverlayBaby(null, null, null));
        ContentException raised = assertThrows(ContentException.class, () -> load(List.of(plain, boned), true));
        assertEquals("Opacity of 'minecraft:test overlay 1' reads 'bone', which a self-contained expression does not",
            raised.getMessage());
    }

    @Test
    @DisplayName("a baby form draws the very opacity its adult row does, the row read once")
    void aBabyFormSharesItsAdultsOpacity() {
        RawOverlay spotted = overlay(JsonParser.parseString(SPOTS), new RawOverlayBaby(null, null, null));
        Entity entity = load(List.of(spotted), true);

        PoseExpr adult = entity.overlays().getFirst().opacity().orElseThrow();
        assertSame(adult, entity.axes().baby().orElseThrow().overlays().getFirst().opacity().orElseThrow(),
            "the baby's pass holds the adult row's expression");
    }

    @Test
    @DisplayName("an opacity reads through the table's Gson, and a JSON null reads as no opacity")
    void anOpacityReadsThroughTheTableGson() {
        String json = "{\"models\": {\"" + ENTITY + "\": {"
            + "\"overlays\": ["
            + "{\"texture\": \"test\", \"opacity\": " + SPOTS + "}, "
            + "{\"texture\": \"test\", \"opacity\": {\"const\": 0.25}}, "
            + "{\"texture\": \"test\", \"pipeline\": {\"emissive\": true}, \"opacity\": null}], "
            + "\"axes\": {\"age\": {\"options\": {\"adult\": {\"geometry\": \"" + COORD + "\", \"texture\": \"test\"}}}}"
            + "}}}";
        EntityModelsTable table = GsonSettings.defaults().create()
            .fromJson(JsonParser.parseString(json), EntityModelsTable.class);
        List<Entity.OverlayLayer> passes = EntityIndexBuilder.assemble(Map.of(COORD, mesh()), table, Map.of())
            .get(ENTITY).overlays();

        assertTrue(passes.getFirst().opacity().isPresent(), "the member loads onto its pass as an expression");
        assertEquals(PassDeclaration.drawnAlpha(0.25f), passes.getFirst().pass().alpha(),
            "drawn at its value with every input at rest");
        assertEquals(Optional.of(new PoseExpr.Constant(0.25f)), passes.get(1).opacity(),
            "a constant loads as the grammar's constant");
        assertEquals(PassDeclaration.drawnAlpha(0.25f), passes.get(1).pass().alpha(), "drawn at its value");
        assertTrue(passes.getLast().opacity().isEmpty(), "a JSON null is no opacity");
        assertEquals(1f, passes.getLast().pass().alpha(), "so its pass draws at a constant one");
    }

    // ------------------------------------------------------------------------------------
    // drive timing
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a stroke's rate and a trapezoid's spans load onto the driver's wave, an absent span reading zero")
    void aDrivesTimingLoadsOntoItsDriver() {
        StyleDriver stroke = driver("{\"field\": \"tentacleAngle\", \"wave\": \"stroke\", \"rest\": 0.0, "
            + "\"extent\": 0.7853982, \"rate\": 0.11559614}");
        assertEquals(new StyleDriver.Wave.Stroke(0.11559614f), stroke.wave(), "the stroke reads its rate");

        StyleDriver peek = driver("{\"field\": \"peekAmount\", \"wave\": \"trapezoid\", \"rest\": 0.0, "
            + "\"extent\": 0.29999998, \"rise\": 6, \"dwell\": 14, \"fall\": 6, \"gap\": 0}");
        assertEquals(new StyleDriver.Wave.Trapezoid(6, 14, 6, 0), peek.wave(), "the trapezoid reads its spans");

        StyleDriver heart = driver("{\"field\": \"heartAnimation\", \"wave\": \"trapezoid\", \"rest\": 0.0, "
            + "\"extent\": 1.0, \"fall\": 10, \"gap\": 30}");
        assertEquals(new StyleDriver.Wave.Trapezoid(0, 0, 10, 30), heart.wave(),
            "a span the row does not spell reads zero");

        StyleDriver held = driver("{\"field\": \"croakAnimationState\", \"wave\": \"hold\", \"extent\": 1.0}");
        assertEquals(StyleDriver.Wave.Plain.HOLD, held.wave(), "every other wave reads nothing beside its rest and extent");
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
    @DisplayName("a family's ceilings load onto its catalog by age, and an age it records none at has none")
    void aFamilysCeilingsLoadOntoItsCatalog() {
        StyleCatalog catalog = catalog(IDLE_ROW + ", " + stride(null), "{\"adult\": {\"speed\": 0.69693, \"rate\": 2.09079}}");

        assertEquals(Optional.of(new PaceCeiling(0.69693f, 2.09079f)), catalog.ceilingAt(Age.ADULT),
            "the catalog reads the adult's ceiling");
        assertTrue(catalog.ceilingAt(Age.BABY).isEmpty(), "an age it records none at walks at no pace of its own");
        assertTrue(catalog(IDLE_ROW + ", " + stride(null), null).ceilings().isEmpty(),
            "a family naming no ceilings records none");
        assertEquals(Optional.of(new PaceCeiling(0.5f, 1.5f)),
            catalog(IDLE_ROW + ", " + stride("baby"), "{\"baby\": {\"speed\": 0.5, \"rate\": 1.5}}").ceilingAt(Age.BABY),
            "a ceiling at the age its stride is scoped to loads");
    }

    @Test
    @DisplayName("a ceiling at an age no stride row walks at, or keyed by a token that is not an age, is refused")
    void aCeilingWithNoStrideIsRefused() {
        ContentException strideless = assertThrows(ContentException.class, () ->
            catalog(IDLE_ROW, "{\"adult\": {\"speed\": 0.5, \"rate\": 0.5}}"));
        assertEquals("Entity 'minecraft:test' records a ceiling at 'adult', where no stride row walks",
            strideless.getMessage());

        ContentException otherAge = assertThrows(ContentException.class, () ->
            catalog(IDLE_ROW + ", " + stride("baby"), "{\"adult\": {\"speed\": 0.5, \"rate\": 0.5}}"));
        assertEquals("Entity 'minecraft:test' records a ceiling at 'adult', where no stride row walks",
            otherAge.getMessage());

        ContentException unaged = assertThrows(ContentException.class, () ->
            catalog(IDLE_ROW + ", " + stride(null), "{\"elder\": {\"speed\": 0.5, \"rate\": 0.5}}"));
        assertEquals("Entity 'minecraft:test' records a ceiling at 'elder', which is not an age", unaged.getMessage());
    }

    @Test
    @DisplayName("ceilings on a family shipping no styles are refused")
    void ceilingsWithNoStylesAreRefused() {
        String json = "{\"period_ticks\": 24, \"models\": {\"" + ENTITY + "\": {"
            + "\"axes\": {\"age\": {\"options\": {\"adult\": {\"geometry\": \"" + COORD + "\", \"texture\": \"test\"}}}}, "
            + "\"ceilings\": {\"adult\": {\"speed\": 0.5, \"rate\": 0.5}}"
            + "}}}";
        EntityModelsTable table = GsonSettings.defaults().create()
            .fromJson(JsonParser.parseString(json), EntityModelsTable.class);
        ContentException raised = assertThrows(ContentException.class, () ->
            EntityIndexBuilder.assemble(Map.of(COORD, mesh()), table, Map.of()));
        assertEquals("Entity 'minecraft:test' records ceilings but ships no styles", raised.getMessage());
    }

    @Test
    @DisplayName("a ceiling naming no speed, or no rate, is refused")
    void aCeilingMissingAMemberIsRefused() {
        ContentException noRate = assertThrows(ContentException.class, () ->
            catalog(IDLE_ROW + ", " + stride(null), "{\"adult\": {\"speed\": 0.5}}"));
        assertEquals("Entity 'minecraft:test' ceiling at 'adult' names no 'rate'", noRate.getMessage());

        ContentException noSpeed = assertThrows(ContentException.class, () ->
            catalog(IDLE_ROW + ", " + stride(null), "{\"adult\": {\"rate\": 0.5}}"));
        assertEquals("Entity 'minecraft:test' ceiling at 'adult' names no 'speed'", noSpeed.getMessage());
    }

    @Test
    @DisplayName("a ceiling swinging past one, or whose walk clock does not advance, is refused")
    void aCeilingOutOfRangeIsRefused() {
        ContentException fast = assertThrows(ContentException.class, () ->
            catalog(IDLE_ROW + ", " + stride(null), "{\"adult\": {\"speed\": 1.5, \"rate\": 1.0}}"));
        assertEquals("Entity 'minecraft:test' ceiling at 'adult' swings '1.5' and advances '1.0' a tick, "
            + "outside (0, 1] and above zero", fast.getMessage());

        ContentException still = assertThrows(ContentException.class, () ->
            catalog(IDLE_ROW + ", " + stride(null), "{\"adult\": {\"speed\": 0.5, \"rate\": 0.0}}"));
        assertEquals("Entity 'minecraft:test' ceiling at 'adult' swings '0.5' and advances '0.0' a tick, "
            + "outside (0, 1] and above zero", still.getMessage());
    }

    // ------------------------------------------------------------------------------------
    // source gates
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a source's gate loads as the flag it names")
    void aSourceGateLoadsAsItsFlag() {
        PoseStyle idle = styles("{\"id\": \"idle\", \"sources\": [\"tick\", {\"source\": \"scroll\", \"gate\": \"charged\"}, "
            + "{\"source\": \"figure\", \"gate\": \"carried\"}]}").getFirst();

        assertEquals(List.of(Optional.empty(), Optional.of(Flag.CHARGED), Optional.of(Flag.CARRIED)),
            idle.sources().stream().map(PoseStyle.StyleSource::gate).toList());
    }

    @Test
    @DisplayName("a source gated on a token no flag is is refused")
    void aSourceGateNamingNoFlagIsRefused() {
        ContentException raised = assertThrows(ContentException.class, () ->
            styles("{\"id\": \"idle\", \"sources\": [{\"source\": \"scroll\", \"gate\": \"tinted\"}]}"));
        assertEquals("Entity 'minecraft:test' style 'idle' gates 'scroll' on 'tinted', which is not a flag",
            raised.getMessage());
    }

    // ------------------------------------------------------------------------------------
    // the forms a carried block and a filled slot re-pose the wearer as
    // ------------------------------------------------------------------------------------

    /** The key the fixture's body pose is named by in its pose table. */
    private static final @NotNull String BODY = "TestModel";

    /** The key a carrier variant is named by in the fixture's pose table. */
    private static final @NotNull String VARIANT = "TestModel@carriedBlock.isEmpty=false";

    @Test
    @DisplayName("a carried option's pose loads as the carried form - the row's mesh at that pose, its pass following")
    void aCarriedPoseLoadsAsTheCarriedForm() {
        EntityPose body = variant(0f);
        EntityPose variant = variant(-0.5f);
        Entity row = withAxis("\"carried\": {\"options\": {\"block\": {\"pose\": \"" + VARIANT + "\"}}}",
            Map.of(BODY, body, VARIANT, variant));
        Entity carried = row.axes().carried().orElseThrow();

        assertSame(body, row.pose(), "the row keeps its own pose");
        assertSame(variant, carried.pose(), "the form carries the pose its key names");
        assertSame(row.model(), carried.model(), "over the row's own mesh");
        assertSame(variant, carried.overlays().getFirst().pose(), "and the pass that shared the row's pose follows it");
        assertTrue(row.axes().equipment().options().isEmpty(), "a row naming no slot carries no equipment form");
    }

    @Test
    @DisplayName("an equipment option's pose loads as the form of the slot it keys, the axis declaring none")
    void anEquipmentPoseLoadsAsTheSlotsForm() {
        EntityPose variant = variant(-0.5f);
        Entity row = withAxis("\"equipment\": {\"options\": {\"body\": {\"pose\": \"" + VARIANT + "\"}}}",
            Map.of(BODY, variant(0f), VARIANT, variant));
        Entity.Variation<String, Entity> equipment = row.axes().equipment();

        assertEquals(List.of("body"), List.copyOf(equipment.options().keySet()), "the axis keys the slot");
        assertSame(variant, equipment.select("body").orElseThrow().pose(), "whose form carries the pose its key names");
        assertTrue(equipment.declared().isEmpty(), "and the bare row fills no slot");
        assertTrue(row.axes().carried().isEmpty(), "a row naming no carried option carries no carried form");
    }

    @Test
    @DisplayName("a carried pose the pose table carries no pose for answers the pose of a model that poses nothing, as a size option's does")
    void anUnresolvedCarriedPoseAnswersNoPose() {
        Entity row = withAxis("\"carried\": {\"options\": {\"block\": {\"pose\": \"" + VARIANT + "\"}}}",
            Map.of(BODY, variant(0f)));
        assertSame(EntityPose.NONE, row.axes().carried().orElseThrow().pose());
    }

    // ------------------------------------------------------------------------------------

    /**
     * The fixture entity drawing one pass over the body's mesh and carrying one axis beside its age
     * axis, read through the table's Gson.
     *
     * @param axis the axis member as JSON, its name and its node
     * @param poses the pose table the load resolves every pose against
     * @return the loaded entity
     */
    private static @NotNull Entity withAxis(@NotNull String axis, @NotNull Map<String, EntityPose> poses) {
        String json = "{\"models\": {\"" + ENTITY + "\": {"
            + "\"overlays\": [{\"texture\": \"test\"}], "
            + "\"axes\": {\"age\": {\"options\": {\"adult\": {\"geometry\": \"" + COORD + "\", \"texture\": \"test\"}}}, "
            + axis + "}"
            + "}}}";
        EntityModelsTable table = GsonSettings.defaults().create()
            .fromJson(JsonParser.parseString(json), EntityModelsTable.class);
        return EntityIndexBuilder.assemble(Map.of(COORD, mesh()), table, poses).get(ENTITY);
    }

    /** A pose holding the right arm at a pitch, distinct by identity from every other pose. */
    private static @NotNull EntityPose variant(float pitch) {
        return new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("right_arm", Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(pitch)))),
            Concurrent.newUnmodifiableList(), Optional.empty());
    }

    /**
     * The fixture entity's one pass, drawing the body's mesh.
     *
     * @param opacity the pass's {@code opacity} as JSON
     * @return the loaded pass
     */
    private static @NotNull Entity.OverlayLayer pass(@NotNull String opacity) {
        RawOverlay row = overlay(JsonParser.parseString(opacity), null);
        return load(List.of(row), false).overlays().getFirst();
    }

    /**
     * One overlay row drawing the body's mesh.
     *
     * @param opacity the row's {@code opacity}, or {@code null} where it declares none
     * @param baby the row's age delta, or {@code null} where it draws on an adult only
     * @return the row
     */
    private static @NotNull RawOverlay overlay(@Nullable JsonElement opacity, @Nullable RawOverlayBaby baby) {

        return new RawOverlay(
            null,                                                         // geometry
            null,                                                         // no_hat_geometry
            "test",                                                       // texture
            null,                                                         // tint
            null,                                                         // tint_by
            null,                                                         // texture_by
            null,                                                         // pipeline
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
            null,                                                         // styles
            null));                                                       // ceilings
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
     * A stride row composed over {@link #IDLE_ROW}.
     *
     * @param age the row's {@code age} token, or {@code null} where it applies to both
     * @return the row as JSON
     */
    private static @NotNull String stride(@Nullable String age) {
        return "{\"id\": \"stride\", \"base\": \"idle\""
            + (age == null ? "" : ", \"age\": \"" + age + "\"")
            + ", \"sources\": [\"stride\"], \"drives\": ["
            + "{\"field\": \"walkAnimationSpeed\", \"wave\": \"hold\", \"extent\": 1.0}, "
            + "{\"field\": \"walkAnimationPos\", \"wave\": \"ramp\", \"extent\": 1.0}]}";
    }

    /**
     * The style rows a fixture entity carrying the given rows loads, read through the table's Gson.
     *
     * @param rows the entity's {@code styles} rows as comma-separated JSON, in table order
     * @return the loaded rows, in table order
     */
    private static @NotNull List<PoseStyle> styles(@NotNull String rows) {
        return catalog(rows, null).styles();
    }

    /**
     * The style catalog a fixture entity carrying the given rows and ceilings loads, read through the
     * table's Gson.
     *
     * @param rows the entity's {@code styles} rows as comma-separated JSON, in table order
     * @param ceilings the entity's {@code ceilings} member as JSON, or {@code null} where it names none
     * @return the loaded catalog
     */
    private static @NotNull StyleCatalog catalog(@NotNull String rows, @Nullable String ceilings) {
        String json = "{\"period_ticks\": 24, \"models\": {\"" + ENTITY + "\": {"
            + "\"axes\": {\"age\": {\"options\": {\"adult\": {\"geometry\": \"" + COORD + "\", \"texture\": \"test\"}}}}, "
            + "\"styles\": [" + rows + "]"
            + (ceilings == null ? "" : ", \"ceilings\": " + ceilings)
            + "}}}";
        EntityModelsTable table = GsonSettings.defaults().create()
            .fromJson(JsonParser.parseString(json), EntityModelsTable.class);
        return EntityIndexBuilder.assemble(Map.of(COORD, mesh()), table, Map.of())
            .get(ENTITY).styles();
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
        return new RawAxes(null, new RawAxis(null, options), null, null, null, null, null);
    }

}
