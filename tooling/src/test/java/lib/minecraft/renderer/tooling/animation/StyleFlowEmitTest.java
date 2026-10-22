package lib.minecraft.renderer.tooling.animation;

import com.google.gson.Gson;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.content.index.EntityIndexBuilder;
import lib.minecraft.renderer.content.table.EntityModelsTable;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PosePredicate;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.policy.StyleRoster;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The style emitter's contracts: every emitted id unique per entity - two rows sharing one only
 * where disjoint ages split them - and off the reserved ids, every state field earning exactly
 * one choice, every emittable token loader-readable, every drive filed under the kind that moves it
 * and measured by the arithmetic the loaded row renders with, and the two still-nesses kept apart in
 * the emit log.
 *
 * <p>The walk and the clocks ride the same rows: a stride side whose probe reads the walk clock is
 * driven at its pace and carries its ceiling, an age pair splits wherever its drives or ceilings
 * part, each clock lands its idle half on the standing row and its stride half on the walking one
 * for the age whose forms read it, and a pass's opacity reads its clock in as a pose would.
 *
 * <p>Exercised on hand-built model trees and pose programs rather than a walked corpus, so each
 * contract is pinned per shape independently of any entity's layout; the corpus-wide answers are
 * read off the emitted table itself.
 */
@DisplayName("style flow emit")
class StyleFlowEmitTest {

    private static final int PERIOD = StyleCatalog.FIGURE_PERIOD_TICKS;

    /** The four reserved universal ids no selection row may take. */
    private static final @NotNull Set<String> RESERVED = Set.of("bind", "idle", "stride", "animated");

    // ------------------------------------------------------------------------------------
    // fixture builders
    // ------------------------------------------------------------------------------------

    private static @NotNull Diagnostics fresh() {
        return Diagnostics.root("styles", Diagnostics.Output.NONE, null);
    }

    /** One family row: an adult mesh posed by the given class. */
    private static @NotNull JsonTree entityRow(@NotNull String poseClass) {
        JsonTree row = JsonTree.object();
        row.child("axes").child("age").child("options").child("adult")
            .put("geometry", poseClass + "#createBodyLayer")
            .put("pose", poseClass);
        return row;
    }

    private static @NotNull PoseOutcome.Extracted posing(
        @NotNull String model, @NotNull Map<String, Map<PoseChannel, PoseExpr>> bones,
        @NotNull List<PoseClipSite> sites) {

        return new PoseOutcome.Extracted(new PoseProgram(model, List.of(), bones, Map.of(), sites));
    }

    private static @NotNull PoseClipSite selectSite(@NotNull String clip, @NotNull String field) {
        return new PoseClipSite(clip, PoseClipSite.Gate.SELECT, field,
            List.of(new PoseExpr.Input("ageInTicks")), PoseClipSite.ALWAYS);
    }

    private static @NotNull PoseClipSite strideSite(@NotNull String clip) {
        return new PoseClipSite(clip, PoseClipSite.Gate.STRIDE, "",
            List.of(new PoseExpr.Input("walkAnimationPos"), new PoseExpr.Input("walkAnimationSpeed"),
                new PoseExpr.Constant(1.5f), new PoseExpr.Constant(2.5f)),
            PoseClipSite.ALWAYS);
    }

    /**
     * A select site clocked by a literal - what a model applying a clip at a fixed instant leaves,
     * and equally what a term reading a field no roster member drives folds to.
     */
    private static @NotNull PoseClipSite heldClockSite(@NotNull String clip, @NotNull String field) {
        return new PoseClipSite(clip, PoseClipSite.Gate.SELECT, field,
            List.of(new PoseExpr.Constant(0f)), PoseClipSite.ALWAYS);
    }

    private static @NotNull AnimationValue.Frame frame(float time, float x) {
        return new AnimationValue.Frame(time, new AnimationValue.Vec(x, 0f, 0f), "linear");
    }

    /** A clip whose one channel travels, under the coordinate {@code <owner>#<field>}. */
    private static @NotNull KeyframeClip movingClip(@NotNull String owner, @NotNull String field) {
        return new KeyframeClip("net/minecraft/client/animation/definitions/" + owner, field, 1f, true,
            List.of(new KeyframeClip.BoneChannel("head", "rotation",
                List.of(frame(0f, 0f), frame(0.5f, 1f)))));
    }

    /** A clip that poses and holds - one keyframe, so it writes bones and nothing travels. */
    private static @NotNull KeyframeClip holdingClip(@NotNull String owner, @NotNull String field) {
        return new KeyframeClip("net/minecraft/client/animation/definitions/" + owner, field, 1f, true,
            List.of(new KeyframeClip.BoneChannel("head", "rotation", List.of(frame(0f, 1f)))));
    }

    /** One age's walks - the wander a stride is driven at and the fastest walk it carries as its ceiling. */
    private static @NotNull AgePace side(float speed, float rate, float fastestSpeed, float fastestRate) {
        return new AgePace(new WalkPace(speed, rate, "fixture stroll"), new WalkPace(fastestSpeed, fastestRate, "fixture flight"));
    }

    /** The walks of a subject offering the adult age alone. */
    private static @NotNull EntityPace adultPace(float speed, float rate, float fastestSpeed, float fastestRate) {
        return new EntityPace(Optional.of(side(speed, rate, fastestSpeed, fastestRate)), Optional.empty());
    }

    /** One family row with a baby form posed by its own class beside the adult's. */
    private static @NotNull JsonTree agedRow(@NotNull String adultClass, @NotNull String babyClass) {
        JsonTree row = entityRow(adultClass);
        row.child("axes").child("age").child("options").child("baby")
            .put("geometry", babyClass + "#createBodyLayer")
            .put("pose", babyClass);
        return row;
    }

    /** The one emitted row carrying an id at an age, the ageless row answering for both. */
    private static @NotNull JsonTree rowAt(
        @NotNull List<JsonTree> styles, @NotNull String id, @NotNull Optional<String> age) {

        return styles.stream()
            .filter(row -> id.equals(row.findString("id").orElseThrow()) && age.equals(row.findString("age")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no '" + id + "' row at " + age.orElse("both ages") + " in " + styles));
    }

    /** The drive one row spells for a field, if it spells one. */
    private static @NotNull Optional<JsonTree> driveOf(@NotNull JsonTree row, @NotNull String field) {
        return row.find("drives").orElseThrow().elements()
            .filter(drive -> field.equals(drive.findString("field").orElseThrow()))
            .findFirst();
    }

    /** Every message of one severity the scope and the scopes below it record, in order. */
    private static @NotNull List<String> messages(@NotNull Diagnostics diagnostics, @NotNull Diagnostics.Severity severity) {
        return diagnostics.entries().stream()
            .filter(entry -> entry.severity() == severity)
            .map(Diagnostics.Entry::message)
            .toList();
    }

    private static @NotNull List<JsonTree> stylesOf(@NotNull JsonTree models, @NotNull String entity) {
        return models.child(entity).find("styles").orElseThrow().elements().toList();
    }

    private static @NotNull List<String> idsOf(@NotNull List<JsonTree> styles) {
        return styles.stream().map(row -> row.findString("id").orElseThrow()).toList();
    }

    // ------------------------------------------------------------------------------------
    // 1 - id uniqueness
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("every derived id is unique and off the reserved ids; the one collision is the age-split pair")
    void everyDerivedIdIsUniqueAndOffTheReservedIds() {
        Map<String, List<StyleRoster.Group>> owners = new LinkedHashMap<>();
        for (StyleRoster.Group group : StyleRoster.GROUPS) {
            Set<String> within = new LinkedHashSet<>();
            for (StyleRoster.Member member : group.members()) {
                if (!member.drives()) continue;
                String id = StyleRoster.styleId(member.field());
                assertTrue(within.add(id), group.name() + " derives '" + id + "' twice");
                if (group.isDefault(member)) continue;
                assertFalse(RESERVED.contains(id),
                    "'" + member.field() + "' derives the reserved id '" + id + "'");
                owners.computeIfAbsent(id, key -> new ArrayList<>()).add(group);
            }
        }
        // Two row-earning members may derive one id only where their groups' declared ages are
        // disjoint and non-empty, and the one sanctioned pair is the axolotl's play_dead.
        Map<String, List<String>> shared = new LinkedHashMap<>();
        owners.forEach((id, held) -> {
            if (held.size() == 1) return;
            List<String> ages = held.stream().map(StyleRoster.Group::age).toList();
            assertEquals(held.size(), Set.copyOf(ages).size(),
                "'" + id + "' is derived by two row-earning members of one age");
            assertFalse(ages.contains(""),
                "'" + id + "' is shared by a group either age's forms read");
            shared.put(id, ages);
        });
        assertEquals(Map.of("play_dead", List.of("adult", "baby")), shared,
            "the age-split pair is the one sanctioned id collision");
        assertEquals("play_dead", StyleRoster.styleId("playingDeadFactor"),
            "the override joins the adult factor to the id its baby clip twin derives");
        assertEquals("on_ground", StyleRoster.styleId("onGroundFactor"),
            "the factor arm strips the suffix and snake-cases the stem");
        // The two known reserved collisions both derive "idle" and both are default selections,
        // so neither ever earns a row of its own and the override table stays empty.
        assertEquals("idle", StyleRoster.styleId("idleAnimationState"));
        assertEquals("idle", StyleRoster.styleId("idle"));
        for (StyleRoster.Group group : StyleRoster.GROUPS)
            for (StyleRoster.Member member : group.members())
                if (member.drives() && RESERVED.contains(StyleRoster.styleId(member.field())))
                    assertTrue(group.isDefault(member),
                        "'" + member.field() + "' derives a reserved id and is not a default");
    }

    @Test
    @DisplayName("an emitted catalog's ids are unique per entity; a shared id carries disjoint ages")
    void emittedIdsAreUniquePerEntity() {
        JsonTree axoish = entityRow("AxoishModel");
        axoish.child("axes").child("age").child("options").child("baby")
            .put("geometry", "AxoishBabyModel#createBodyLayer")
            .put("pose", "AxoishBabyModel");
        JsonTree models = JsonTree.object()
            .put("minecraft:probe", entityRow("UniqueModel"))
            .put("minecraft:axoish", axoish);
        Map<String, PoseOutcome> poses = Map.of(
            "UniqueModel", posing("UniqueModel",
                Map.of("tentacle", Map.of(PoseChannel.X_ROT,
                    PoseExpr.operation(PoseOperator.MUL, new PoseExpr.Input("tentacleAngle"), new PoseExpr.Constant(2f)))),
                List.of(selectSite("UniqueAnim#CROAK", "croakAnimationState"))),
            "AxoishModel", posing("AxoishModel",
                Map.of("tail", Map.of(PoseChannel.X_ROT, PoseExpr.operation(PoseOperator.MUL,
                    new PoseExpr.Input("playingDeadFactor"), new PoseExpr.Constant(0.5f)))),
                List.of()),
            "AxoishBabyModel", posing("AxoishBabyModel", Map.of(),
                List.of(selectSite("AxoishAnim#PLAY_DEAD", "playDeadAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of(
            "UniqueAnim#CROAK", movingClip("UniqueAnim", "CROAK"),
            "AxoishAnim#PLAY_DEAD", holdingClip("AxoishAnim", "PLAY_DEAD"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD, Map.of());

        for (String entity : List.of("minecraft:probe", "minecraft:axoish")) {
            List<JsonTree> styles = stylesOf(models, entity);
            Map<String, List<String>> ages = new LinkedHashMap<>();
            for (JsonTree row : styles)
                ages.computeIfAbsent(row.findString("id").orElseThrow(), key -> new ArrayList<>())
                    .add(row.findString("age").orElse(""));
            ages.forEach((id, held) -> {
                if (held.size() == 1) return;
                assertEquals(held.size(), Set.copyOf(held).size(),
                    entity + " emits '" + id + "' twice at one age");
                assertFalse(held.contains(""),
                    entity + " emits '" + id + "' twice and one row applies to both ages");
            });
            List<String> ids = idsOf(styles);
            assertFalse(ids.contains("bind"), "the bind row is synthesized, never emitted");
            assertFalse(ids.contains("animated"), "animated is a resolution, never a row");
        }

        // The sanctioned pair: a held adult factor beside a held baby clip, one id, both rows
        // shipping the empty source inventory a held-but-distinct selection earns.
        List<JsonTree> pair = stylesOf(models, "minecraft:axoish").stream()
            .filter(row -> "play_dead".equals(row.findString("id").orElseThrow()))
            .toList();
        assertEquals(List.of("adult", "baby"),
            pair.stream().map(row -> row.findString("age").orElseThrow()).toList(),
            "the axolotl-shaped pair ships the adult factor row beside the baby clip row");
        for (JsonTree row : pair)
            assertTrue(row.find("sources").orElseThrow().elements().toList().isEmpty(),
                "a held-but-distinct row ships an empty source inventory");
    }

    // ------------------------------------------------------------------------------------
    // 2 - every state field earns a choice
    // ------------------------------------------------------------------------------------

    /** The four driven fields that are blend factors or flags rather than select-gated states. */
    private static final @NotNull Set<String> INPUT_TOKENS =
        Set.of("playingDeadFactor", "inWaterFactor", "onGroundFactor", "isMoving");

    /** The eight select tokens whose member is a resting selection at one gait or both. */
    private static final @NotNull Set<String> DEFAULT_TOKENS = Set.of(
        "flyAnimationState", "idleAnimationState", "idle",
        "idleHeadTiltAnimationState", "hopAnimationState", "slide",
        "swimAnimation", "idleUnderWaterAnimationState");

    @Test
    @DisplayName("each of the 44 select tokens is exactly one of row, folded default, or the no-row token")
    void everyStateFieldEarnsAChoice() throws Exception {
        Set<String> figures = new LinkedHashSet<>(Set.of("ageInTicks", "walkAnimationPos", "walkAnimationSpeed"));
        for (StyleRoster.Clock clock : StyleRoster.CLOCKS) figures.add(clock.field());
        assertEquals(StyleRoster.DRIVEN_FIGURES, figures,
            "the roster's clocks are the pose walk's own figure half");

        Set<String> driven = StyleRoster.driven();
        assertEquals(47, driven.size(), "the roster drives 47 fields");
        Set<String> union = new LinkedHashSet<>(figures);
        union.addAll(driven);
        assertEquals(StyleRoster.DRIVEN, union,
            "the roster and the pose walk name one driven set - a version bump adding a token fails here");
        assertFalse(union.contains(StyleRoster.NO_ROW_FIELD),
            "the no-row token is deliberately left off the driven set");

        Set<String> selectTokens = new TreeSet<>(driven);
        selectTokens.removeAll(INPUT_TOKENS);
        selectTokens.add(StyleRoster.NO_ROW_FIELD);
        assertEquals(44, selectTokens.size(), "the select-token census is 44");

        Set<String> defaults = new TreeSet<>();
        Set<String> rows = new TreeSet<>();
        for (StyleRoster.Group group : StyleRoster.GROUPS)
            for (StyleRoster.Member member : group.members()) {
                if (!member.drives() || INPUT_TOKENS.contains(member.field())) continue;
                (group.isDefault(member) ? defaults : rows).add(member.field());
            }
        assertEquals(DEFAULT_TOKENS, defaults, "the folded default selections are pinned");
        assertEquals(35, rows.size(), "35 select tokens earn a row of their own");
        for (String token : selectTokens) {
            int homes = (rows.contains(token) ? 1 : 0) + (defaults.contains(token) ? 1 : 0)
                + (StyleRoster.NO_ROW_FIELD.equals(token) ? 1 : 0);
            assertEquals(1, homes, "'" + token + "' has " + homes + " homes, not exactly one");
        }
    }

    // ------------------------------------------------------------------------------------
    // 3 - loader readability
    // ------------------------------------------------------------------------------------

    /** The closed token sets the renderer's loader reads, pinned as the loader spells them. */
    private static final @NotNull Set<String> ROW_MEMBERS =
        Set.of("id", "base", "age", "sources", "toggles", "drives", "ceiling");

    private static final @NotNull Set<String> CEILING_MEMBERS = Set.of("speed", "rate");

    private static final @NotNull Set<String> DRIVE_MEMBERS =
        Set.of("field", "wave", "rest", "extent", "group", "rate", "rise", "dwell", "fall", "gap");

    private static final @NotNull Set<String> WAVES =
        Set.of("hold", "ramp", "sweep", "cycle", "stroke", "trapezoid");

    private static final @NotNull Set<String> SOURCES =
        Set.of("none", "tick", "figure", "select", "stride", "scroll");

    private static final @NotNull Set<String> AGES = Set.of("adult", "baby");

    private static final @NotNull Set<String> COMPARISONS = Set.of("eq", "ne", "lt", "le", "gt", "ge");

    private static final @NotNull Set<String> OPERATORS = Set.of(
        "add", "sub", "mul", "div", "rem", "neg",
        "dadd", "dsub", "dmul", "ddiv", "dneg",
        "iadd", "isub", "imul", "idiv", "irem", "ineg",
        "f2d", "d2f", "i2f", "i2d", "f2i",
        "mth_sin", "mth_cos", "sqrt", "clamp", "lerp", "inverse_lerp", "rot_lerp", "rot_lerp_rad",
        "wrap_degrees", "triangle_wave", "min", "max", "abs", "iabs",
        "libm_sin", "libm_cos", "libm_abs", "libm_signum", "libm_sqrt", "libm_max",
        "ease_in_circ", "ease_in_quad", "ease_out_circ", "ease_out_cubic", "ease_out_quart",
        "ease_in_out_sine", "ease_in_out_expo", "ease_in_out_elastic");

    @Test
    @DisplayName("every token the emitter can produce is in the loader's closed sets")
    void everyEmittableTokenIsLoaderReadable() {
        // A fixture that produces every emittable shape at once: a stroke and a ramp clock, hold and
        // ramp drives, a grouped selection with its toggles, a gated and an ungated source, a paced
        // stride carrying its ceiling, a baseless row, and a baby-only row.
        JsonTree probe = entityRow("SpellModel");
        probe.childArray("overlays").add(JsonTree.object()
            .put("geometry", "SpellModel#createSwirlLayer")
            .put("when", JsonTree.object().put("charged", true))
            .put("texture_scroll", JsonTree.object().put("u", 0.01f).put("v", 0.01f)));
        JsonTree hatchling = entityRow("HatchModel");
        hatchling.child("axes").child("age").child("options").child("baby")
            .put("geometry", "HatchBabyModel#createBodyLayer")
            .put("pose", "HatchBabyModel");
        JsonTree models = JsonTree.object()
            .put("minecraft:probe", probe)
            .put("minecraft:hatchling", hatchling);

        Map<String, PoseOutcome> poses = Map.of(
            "SpellModel", posing("SpellModel",
                Map.of(
                    "tentacle", Map.of(PoseChannel.X_ROT, PoseExpr.operation(PoseOperator.MUL,
                        new PoseExpr.Input("tentacleAngle"), new PoseExpr.Constant(2f))),
                    "wing", Map.of(PoseChannel.Y_ROT, PoseExpr.operation(PoseOperator.MUL,
                        new PoseExpr.Input("flapTime"), new PoseExpr.Constant(3f)))),
                List.of(selectSite("SpellAnim#CROAK", "croakAnimationState"), strideSite("SpellAnim#WALK"))),
            "HatchModel", posing("HatchModel",
                Map.of("body", Map.of(PoseChannel.X, new PoseExpr.Constant(1f))), List.of()),
            "HatchBabyModel", posing("HatchBabyModel", Map.of(),
                List.of(selectSite("HatchAnim#ON_GROUND", "idleOnGroundAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of(
            "SpellAnim#CROAK", movingClip("SpellAnim", "CROAK"),
            "SpellAnim#WALK", movingClip("SpellAnim", "WALK"),
            "HatchAnim#ON_GROUND", movingClip("HatchAnim", "ON_GROUND"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD,
            Map.of("minecraft:probe", adultPace(0.35242295f, 0.35242295f, 0.7929517f, 0.7929517f)));

        List<JsonTree> rows = new ArrayList<>(stylesOf(models, "minecraft:probe"));
        rows.addAll(stylesOf(models, "minecraft:hatchling"));
        boolean[] sawGate = {false};
        boolean[] sawBare = {false};
        for (JsonTree row : rows) {
            for (String member : row.keys().toList())
                assertTrue(ROW_MEMBERS.contains(member), "row member '" + member + "' is not loader-read");
            row.findString("age").ifPresent(age -> assertTrue(AGES.contains(age)));
            row.find("ceiling").ifPresent(ceiling -> {
                for (String member : ceiling.keys().toList())
                    assertTrue(CEILING_MEMBERS.contains(member), "ceiling member '" + member + "' is not loader-read");
            });
            for (JsonTree source : row.find("sources").orElseThrow().elements().toList())
                if (source.isPrimitive()) {
                    assertTrue(SOURCES.contains(source.asString().orElseThrow()));
                    sawBare[0] = true;
                } else {
                    assertEquals(List.of("source", "gate"), source.keys().toList());
                    assertTrue(SOURCES.contains(source.findString("source").orElseThrow()));
                    sawGate[0] = true;
                }
            for (JsonTree drive : row.find("drives").orElseThrow().elements().toList()) {
                for (String member : drive.keys().toList())
                    assertTrue(DRIVE_MEMBERS.contains(member),
                        "drive member '" + member + "' is not loader-read");
                assertTrue(WAVES.contains(drive.findString("wave").orElseThrow()));
                assertTrue(drive.findString("field").isPresent());
                assertTrue(drive.findFloat("extent").isPresent());
            }
        }
        assertTrue(sawBare[0] && sawGate[0], "the fixture exercises both source spellings");
        assertTrue(rows.stream().anyMatch(row -> "baby".equals(row.findString("age").orElse(null))),
            "the fixture exercises the age spelling");
        assertTrue(rows.stream().anyMatch(row -> row.find("toggles").isPresent()),
            "the fixture exercises the toggles spelling");
        assertTrue(rows.stream().anyMatch(row -> row.find("ceiling").isPresent()),
            "the fixture exercises the ceiling spelling");

        // The pose-file half of what this build can emit: play-site drives, select conditions and
        // operator tokens, held to the loader's own closed sets.
        assertEquals(Set.of("none", "stride", "select"),
            Set.of(PoseClipSite.Gate.NONE.token(), PoseClipSite.Gate.STRIDE.token(),
                PoseClipSite.Gate.SELECT.token()));
        Set<String> comparisons = new LinkedHashSet<>();
        for (PosePredicate.Comparison comparison : PosePredicate.Comparison.values())
            comparisons.add(comparison.token());
        assertEquals(COMPARISONS, comparisons);
        Set<String> operators = new LinkedHashSet<>();
        for (PoseOperator operator : PoseOperator.values()) operators.add(operator.token());
        assertEquals(OPERATORS, operators, "the operator roster is the loader's 50 tokens exactly");
    }

    // ------------------------------------------------------------------------------------
    // 4 - the kind a drive files its variation under
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an ungrouped drive off the trio files as a figure, whatever its wave; a grouped one as a select")
    void anUngroupedDriveOffTheTrioFilesAsAFigure() {
        assertEquals("figure", new StyleFlow.Drive("tailAnimation", "ramp", 0f, 0.125f, Optional.empty()).kind(),
            "a ramped clock is a render-state scalar, not a one-hot");
        assertEquals("figure", new StyleFlow.Drive("spikesAnimation", "hold", 0f, 1f, Optional.empty()).kind(),
            "and so is a level a clock is held at");
        assertEquals("figure", new StyleFlow.Drive("tentacleAngle", "sweep", 0f, 0.7853982f, Optional.empty()).kind());
        assertEquals("figure", new StyleFlow.Drive("flapTime", "cycle", 0f, 1f, Optional.empty()).kind());
        assertEquals("select", new StyleFlow.Drive("croakAnimationState", "hold", 0f, 1f, Optional.of("frog")).kind(),
            "a grouped hold is a selection");
        assertEquals("tick", new StyleFlow.Drive("ageInTicks", "ramp", 0f, 1f, Optional.empty()).kind());
        assertEquals("stride", new StyleFlow.Drive("walkAnimationSpeed", "hold", 0f, 1f, Optional.empty()).kind());
        assertEquals("stride", new StyleFlow.Drive("walkAnimationPos", "ramp", 0f, 1f, Optional.empty()).kind());
    }

    // ------------------------------------------------------------------------------------
    // 5 - the stroke and the trapezoid
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a stroke and a trapezoid spell their timing as the loader reads it, and every wave measures as it renders")
    void aStrokeAndATrapezoidSpellTheirTimingAndMeasureAsTheyRender() {
        StyleFlow.Drive stroke = new StyleFlow.Drive("tentacleAngle", "stroke", 0f, 0.7853982f,
            StyleDriver.Timing.stroke(0.11559614f), Optional.empty());
        StyleFlow.Drive peek = new StyleFlow.Drive("peekAmount", "trapezoid", 0f, 0.29999998f,
            StyleDriver.Timing.trapezoid(6, 14, 6, 0), Optional.empty());
        StyleFlow.Drive heart = new StyleFlow.Drive("heartAnimation", "trapezoid", 0f, 1f,
            StyleDriver.Timing.trapezoid(0, 0, 10, 30), Optional.empty());

        JsonTree stroked = stroke.node();
        assertEquals(List.of("field", "wave", "rest", "extent", "rate"), stroked.keys().toList(),
            "a stroke spells its rest, then its rate after the extent");
        assertEquals(0.11559614f, stroked.findFloat("rate").orElseThrow());
        JsonTree beat = heart.node();
        assertEquals(List.of("field", "wave", "rest", "extent", "rise", "dwell", "fall", "gap"), beat.keys().toList(),
            "a trapezoid spells all four spans, a zero span included, so the row reads whole");
        assertEquals(List.of(0, 0, 10, 30), Stream.of("rise", "dwell", "fall", "gap")
            .map(span -> beat.findInt(span).orElseThrow()).toList());
        assertEquals(List.of("field", "wave", "extent", "group"),
            new StyleFlow.Drive("croakAnimationState", "hold", 0f, 1f, Optional.of("frog")).node().keys().toList(),
            "a wave reading no rest and no timing spells neither");

        List<StyleFlow.Drive> waves = List.of(
            new StyleFlow.Drive("croakAnimationState", "hold", 0f, 1f, Optional.of("frog")),
            new StyleFlow.Drive("ageInTicks", "ramp", 0f, 1f, Optional.empty()),
            new StyleFlow.Drive("movingFactor", "sweep", 0f, 1f, Optional.empty()),
            new StyleFlow.Drive("flapTime", "cycle", 0f, 1f, Optional.empty()),
            stroke, peek, heart);
        JsonTree idle = JsonTree.object().put("id", "idle").put("sources", JsonTree.array());
        JsonTree drives = idle.childArray("drives");
        for (StyleFlow.Drive drive : waves) {
            JsonTree node = drive.node();
            for (String member : node.keys().toList())
                assertTrue(DRIVE_MEMBERS.contains(member), "drive member '" + member + "' is not loader-read");
            assertTrue(WAVES.contains(drive.wave()), "wave '" + drive.wave() + "' is not loader-read");
            drives.add(node);
        }
        JsonTree models = JsonTree.object().put("minecraft:rabbity", entityRow("RabbityModel"));
        models.child("minecraft:rabbity").childArray("styles").add(idle);
        PoseStyle loaded = rowOf(load(models).styles(), "idle").orElseThrow();

        for (StyleFlow.Drive drive : waves) {
            StyleDriver read = loaded.drivers().get(drive.field());
            assertEquals(drive.driver(), read, "'" + drive.wave() + "' loads as the driver it measures by");
            for (int tick = -PERIOD; tick < 12 * PERIOD; tick++) {
                assertEquals(Float.floatToIntBits(read.at(tick, PERIOD)), Float.floatToIntBits(drive.at(tick, PERIOD)),
                    "'" + drive.wave() + "' measures what it renders at tick " + tick);
            }
        }

        ToolingException unknown = assertThrows(ToolingException.class,
            () -> new StyleFlow.Drive("tentacleAngle", "wobble", 0f, 1f, Optional.empty()).at(0, PERIOD));
        assertEquals("'tentacleAngle' travels by 'wobble', which is not a wave", unknown.getMessage());
    }

    @Test
    @DisplayName("a stroke or a trapezoid given no timing is refused where the drive is built")
    void aStrokeOrATrapezoidGivenNoTimingIsRefused() {
        ToolingException stroke = assertThrows(ToolingException.class,
            () -> new StyleFlow.Drive("tentacleAngle", "stroke", 0f, 0.7853982f, Optional.empty()));
        assertEquals("Field 'tentacleAngle' travels by 'stroke', which reads a timing, given none",
            stroke.getMessage());
        ToolingException trapezoid = assertThrows(ToolingException.class,
            () -> new StyleFlow.Drive("peekAmount", "trapezoid", 0f, 0.29999998f, StyleDriver.Timing.NONE,
                Optional.empty()));
        assertEquals("Field 'peekAmount' travels by 'trapezoid', which reads a timing, given none",
            trapezoid.getMessage());
    }

    @Test
    @DisplayName("two bindings differing only in a drive's timing are measured apart")
    void twoBindingsDifferingOnlyInTimingKeyApart() {
        Map<String, StyleFlow.Drive> slow = Map.of("tentacleAngle", new StyleFlow.Drive("tentacleAngle",
            "stroke", 0f, 0.7853982f, StyleDriver.Timing.stroke(0.11559614f), Optional.empty()));
        Map<String, StyleFlow.Drive> fast = Map.of("tentacleAngle", new StyleFlow.Drive("tentacleAngle",
            "stroke", 0f, 0.7853982f, StyleDriver.Timing.stroke(0.2f), Optional.empty()));
        assertNotEquals(StyleFlow.bindingKey(slow), StyleFlow.bindingKey(fast),
            "a stroke's rate is part of what a measurement is memoised under");
        Map<String, StyleFlow.Drive> again = Map.of("tentacleAngle", new StyleFlow.Drive("tentacleAngle",
            "stroke", 0f, 0.7853982f, StyleDriver.Timing.stroke(0.11559614f), Optional.empty()));
        assertEquals(StyleFlow.bindingKey(slow), StyleFlow.bindingKey(again),
            "and one binding spelled twice is one measurement");

        List<StyleDriver.Timing> timings = List.of(
            StyleDriver.Timing.trapezoid(1, 2, 3, 4), StyleDriver.Timing.trapezoid(2, 2, 3, 4),
            StyleDriver.Timing.trapezoid(1, 3, 3, 4), StyleDriver.Timing.trapezoid(1, 2, 4, 4),
            StyleDriver.Timing.trapezoid(1, 2, 3, 5));
        Set<String> keys = new LinkedHashSet<>();
        for (StyleDriver.Timing timing : timings) {
            keys.add(StyleFlow.bindingKey(Map.of("peekAmount",
                new StyleFlow.Drive("peekAmount", "trapezoid", 0f, 0.29999998f, timing, Optional.empty()))));
        }
        assertEquals(timings.size(), keys.size(), "each of a trapezoid's four spans keys a measurement apart");

        Map<String, StyleFlow.Drive> grouped = Map.of("isSitting", new StyleFlow.Drive("isSitting",
            "hold", 0f, 1f, Optional.of("pose")));
        Map<String, StyleFlow.Drive> ungrouped = Map.of("isSitting", new StyleFlow.Drive("isSitting",
            "hold", 0f, 1f, Optional.empty()));
        assertNotEquals(StyleFlow.bindingKey(grouped), StyleFlow.bindingKey(ungrouped),
            "a drive's group decides the kind it files under, so it keys a measurement apart too");
    }

    // ------------------------------------------------------------------------------------
    // 6 - the two still-nesses
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a figure nothing answers and a subject nothing moves stay two counted diagnostics")
    void theTwoStillnessesStayApartInTheEmitLog() {
        JsonTree axolittle = entityRow("LittleModel");
        axolittle.child("axes").child("age").child("options").child("baby")
            .put("geometry", "LittleBabyModel#createBodyLayer")
            .put("pose", "LittleBabyModel");
        JsonTree models = JsonTree.object()
            .put("minecraft:statue", entityRow("StatueModel"))
            .put("minecraft:axolittle", axolittle);

        Map<String, PoseOutcome> poses = Map.of(
            "StatueModel", posing("StatueModel",
                Map.of("head", Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(0.3f))), List.of()),
            "LittleModel", posing("LittleModel",
                Map.of("tail", Map.of(PoseChannel.Y_ROT, PoseExpr.operation(PoseOperator.MUL,
                    new PoseExpr.Input("ageInTicks"), new PoseExpr.Constant(0.1f)))), List.of()),
            "LittleBabyModel", posing("LittleBabyModel", Map.of(),
                List.of(selectSite("LittleAnim#WALK", "walkAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of("LittleAnim#WALK", movingClip("LittleAnim", "WALK"));

        Diagnostics diagnostics = fresh();
        StyleFlow.emit(diagnostics, models, poses, clips, PERIOD, Map.of());

        List<String> messages = diagnostics.entries().stream()
            .map(Diagnostics.Entry::message)
            .toList();
        assertTrue(messages.contains("1 select site(s) read a figure nothing answers: "
                + "[minecraft:axolittle plays LittleAnim#WALK behind 'walkAnimationState']"),
            "the unanswered-figure census is a counted line of its own: " + messages);
        assertTrue(messages.contains("1 of 2 subject(s) ship no style row - nothing shipped moves them: "
                + "[minecraft:statue]"),
            "the still-subject census is a counted line of its own: " + messages);
        assertTrue(models.child("minecraft:statue").find("styles").isEmpty(),
            "a subject nothing moves ships no styles member");
        assertTrue(models.child("minecraft:axolittle").find("styles").isPresent(),
            "a subject the tick moves is not still, whatever its baby leaves unanswered");
    }

    // ------------------------------------------------------------------------------------
    // composition and gating shapes
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a stride row's forked selection replaces its base's driver in the same group")
    void strideReplacesItsBasesDriverInTheSameGroup() {
        JsonTree models = JsonTree.object().put("minecraft:rabbity", entityRow("RabbityModel"));
        Map<String, PoseOutcome> poses = Map.of("RabbityModel", posing("RabbityModel", Map.of(),
            List.of(selectSite("RabbityAnim#IDLE_HEAD_TILT", "idleHeadTiltAnimationState"),
                selectSite("RabbityAnim#HOP", "hopAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of(
            "RabbityAnim#IDLE_HEAD_TILT", movingClip("RabbityAnim", "IDLE_HEAD_TILT"),
            "RabbityAnim#HOP", movingClip("RabbityAnim", "HOP"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD, Map.of());

        List<JsonTree> styles = stylesOf(models, "minecraft:rabbity");
        assertEquals(List.of("idle", "stride"), idsOf(styles),
            "both selections are gait defaults, so neither earns a row of its own");
        JsonTree idle = styles.getFirst();
        List<JsonTree> idleDrives = idle.find("drives").orElseThrow().elements().toList();
        assertEquals(List.of("ageInTicks", "idleHeadTiltAnimationState"),
            idleDrives.stream().map(drive -> drive.findString("field").orElseThrow()).toList());
        assertEquals("rabbit", idleDrives.getLast().findString("group").orElseThrow());
        JsonTree stride = styles.getLast();
        assertEquals("idle", stride.findString("base").orElseThrow());
        assertEquals(List.of("walkAnimationSpeed", "walkAnimationPos", "hopAnimationState"),
            stride.find("drives").orElseThrow().elements().toList().stream()
                .map(drive -> drive.findString("field").orElseThrow()).toList(),
            "the hop drive carries the group, and composition replaces the tilt at load");
    }

    @Test
    @DisplayName("a row whose base is not emitted spells its whole composed driver map, baseless")
    void aBaselessRowSpellsItsWholeComposedMap() {
        JsonTree models = JsonTree.object().put("minecraft:froggish", entityRow("FroggishModel"));
        Map<String, PoseOutcome> poses = Map.of("FroggishModel", posing("FroggishModel", Map.of(),
            List.of(selectSite("FroggishAnim#JUMP", "jumpAnimationState"),
                strideSite("FroggishAnim#WALKC"))));
        Map<String, KeyframeClip> clips = Map.of(
            "FroggishAnim#JUMP", movingClip("FroggishAnim", "JUMP"),
            "FroggishAnim#WALKC", movingClip("FroggishAnim", "WALKC"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD,
            Map.of("minecraft:froggish", adultPace(0.5506609f, 0.5506609f, 1f, 1f)));

        List<JsonTree> styles = stylesOf(models, "minecraft:froggish");
        assertEquals(List.of("stride", "jump"), idsOf(styles),
            "a standing row nothing moves is not emitted, and nothing composes over it");
        for (JsonTree row : styles)
            assertTrue(row.findString("base").isEmpty(), "no emitted row names an absent base");
        assertEquals(List.of("ageInTicks", "walkAnimationSpeed", "walkAnimationPos"),
            styles.getFirst().find("drives").orElseThrow().elements().toList().stream()
                .map(drive -> drive.findString("field").orElseThrow()).toList(),
            "a baseless walking row still answers elapsed age, spelled flat");
        JsonTree jump = styles.getLast();
        assertEquals(List.of("ageInTicks", "jumpAnimationState"),
            jump.find("drives").orElseThrow().elements().toList().stream()
                .map(drive -> drive.findString("field").orElseThrow()).toList());
        assertEquals(List.of("select"), jump.find("sources").orElseThrow().elements().toList().stream()
            .map(source -> source.asString().orElseThrow()).toList());
    }

    @Test
    @DisplayName("a source only a gated pass contributes carries the gate; a rests-false flag refuses")
    void aGatedContributionCarriesItsGateAndARestsFalseFlagRefuses() {
        JsonTree creeperish = entityRow("CreeperishModel");
        creeperish.childArray("overlays").add(JsonTree.object()
            .put("geometry", "CreeperishModel#createBodyLayer@inflate=2.0")
            .put("when", JsonTree.object().put("charged", true))
            .put("texture_scroll", JsonTree.object().put("u", 0.01f).put("v", 0.01f)));
        JsonTree sheepish = entityRow("SheepishModel");
        sheepish.childArray("overlays").add(JsonTree.object()
            .put("geometry", "SheepishModel#createFurLayer")
            .put("when", JsonTree.object().put("flag", "sheared").put("value", false))
            .put("texture_scroll", JsonTree.object().put("u", 0.01f).put("v", 0f)));
        JsonTree models = JsonTree.object()
            .put("minecraft:creeperish", creeperish)
            .put("minecraft:sheepish", sheepish);

        Map<String, PoseOutcome> poses = Map.of(
            "CreeperishModel", posing("CreeperishModel",
                Map.of("head", Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(0.3f))), List.of()),
            "SheepishModel", posing("SheepishModel",
                Map.of("head", Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(0.3f))), List.of()));

        Diagnostics diagnostics = fresh();
        StyleFlow.emit(diagnostics, models, poses, Map.of(), PERIOD, Map.of());

        List<JsonTree> styles = stylesOf(models, "minecraft:creeperish");
        JsonTree idle = styles.getFirst();
        assertEquals("idle", idle.findString("id").orElseThrow());
        List<JsonTree> sources = idle.find("sources").orElseThrow().elements().toList();
        assertEquals(1, sources.size());
        assertEquals("scroll", sources.getFirst().findString("source").orElseThrow());
        assertEquals("charged", sources.getFirst().findString("gate").orElseThrow(),
            "a swirl only the charged pass draws is a gated inventory entry");

        assertTrue(models.child("minecraft:sheepish").find("styles").isEmpty(),
            "a source with no spelling is refused, never invented");
        assertTrue(diagnostics.count(Diagnostics.Severity.ERROR) > 0,
            "the refusal is loud - the strict gate fails the flow");
        assertTrue(diagnostics.entries().stream()
                .anyMatch(entry -> entry.severity() == Diagnostics.Severity.ERROR
                    && entry.message().contains("sheared")),
            "the refusal names the flag");
    }

    // ------------------------------------------------------------------------------------
    // the clip clock
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a travelling clip on a clock that never advances is no select source, and its row still ships")
    void aTravellingClipOnAHeldClockIsNoSelectSource() {
        JsonTree models = JsonTree.object()
            .put("minecraft:tickish", entityRow("TickishModel"))
            .put("minecraft:frozish", entityRow("FrozishModel"));
        Map<String, PoseOutcome> poses = Map.of(
            "TickishModel", posing("TickishModel", Map.of(),
                List.of(selectSite("TickishAnim#JUMP", "jumpAnimationState"))),
            "FrozishModel", posing("FrozishModel", Map.of(),
                List.of(heldClockSite("FrozishAnim#JUMP", "jumpAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of(
            "TickishAnim#JUMP", movingClip("TickishAnim", "JUMP"),
            "FrozishAnim#JUMP", movingClip("FrozishAnim", "JUMP"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD, Map.of());

        List<JsonTree> advancing = stylesOf(models, "minecraft:tickish");
        assertEquals(List.of("select"), sourcesOf(advancing.getLast()),
            "a clip that travels, clocked by a driven field, is a select source");

        List<JsonTree> held = stylesOf(models, "minecraft:frozish");
        assertEquals(List.of(), sourcesOf(held.getLast()),
            "a clip sampled at one instant moves nothing, however far its own channels travel");
        assertEquals(idsOf(advancing), idsOf(held),
            "the held row still ships - its gate opens a play its base does not, a distinct output");

        assertEquals(drivenFields(advancing.getLast()), drivenFields(held.getLast()),
            "only the term the clip is clocked by differs between the two subjects, so the empty "
                + "inventory is attributable to the clock and to nothing else");
    }

    private static @NotNull List<String> sourcesOf(@NotNull JsonTree row) {
        return row.find("sources").orElseThrow().elements().toList().stream()
            .map(source -> source.asString().orElseThrow()).toList();
    }

    private static @NotNull List<String> drivenFields(@NotNull JsonTree row) {
        return row.find("drives").orElseThrow().elements().toList().stream()
            .map(drive -> drive.findString("field").orElseThrow()).toList();
    }

    // ------------------------------------------------------------------------------------
    // the loader's own composition
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a grouped drive evicts its base's at load, the way the emission measured it would")
    void aGroupedDriveEvictsAtLoadTheWayEmissionMeasuredIt() {
        // Group eviction is implemented twice - here, deciding the sources a row is emitted with,
        // and in the pipeline, deciding what a render composes. A row's emitted inventory describes
        // the render only while the two agree, and nothing else runs one table through both. Left
        // to diverge, the stride row below would drive the tilt and the hop together, which is a
        // rabbit hopping while tilting its head.
        JsonTree models = JsonTree.object().put("minecraft:rabbity", entityRow("RabbityModel"));
        Map<String, PoseOutcome> poses = Map.of("RabbityModel", posing("RabbityModel", Map.of(),
            List.of(selectSite("RabbityAnim#IDLE_HEAD_TILT", "idleHeadTiltAnimationState"),
                selectSite("RabbityAnim#HOP", "hopAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of(
            "RabbityAnim#IDLE_HEAD_TILT", movingClip("RabbityAnim", "IDLE_HEAD_TILT"),
            "RabbityAnim#HOP", movingClip("RabbityAnim", "HOP"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD, Map.of());

        JsonTree stride = stylesOf(models, "minecraft:rabbity").getLast();
        assertEquals("idle", stride.findString("base").orElseThrow(),
            "the emitted row leaves its base's drives to the loader rather than spelling them");
        assertEquals(List.of("walkAnimationSpeed", "walkAnimationPos", "hopAnimationState"),
            drivenFields(stride), "and spells the hop with the group that displaces the tilt");

        StyleCatalog loaded = load(models).styles();
        assertEquals(
            List.of("ageInTicks", "walkAnimationSpeed", "walkAnimationPos", "hopAnimationState"),
            List.copyOf(rowOf(loaded, "stride").orElseThrow().drivers().keySet()),
            "the loader composes the base in and drops the tilt, the one field the emitter evicted");
        assertEquals(List.of("ageInTicks", "idleHeadTiltAnimationState"),
            List.copyOf(rowOf(loaded, "idle").orElseThrow().drivers().keySet()),
            "and the base itself keeps the tilt, so the eviction is the stride's and not a load-wide drop");
    }

    @Test
    @DisplayName("a held selection evicting its base's moving clip earns no source")
    void aHeldSelectionEvictingAMovingSiblingEarnsNoSource() {
        // The bat's shape, and the one place the emitter's OWN composition decides a shipped byte: a
        // constant clip earns an empty inventory only because selecting it evicts the moving clip of
        // the same group. Stop evicting and the flying clip plays on under the resting selection, so
        // the row earns a select source and ships a strip of a bat that is supposed to be hanging
        // still. Nothing else covers that half - the emitted drives and the loader's composition are
        // both unmoved by it.
        JsonTree models = JsonTree.object().put("minecraft:battish", entityRow("BattishModel"));
        Map<String, PoseOutcome> poses = Map.of("BattishModel", posing("BattishModel", Map.of(),
            List.of(selectSite("BattishAnim#FLYING", "flyAnimationState"),
                selectSite("BattishAnim#RESTING", "restAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of(
            "BattishAnim#FLYING", movingClip("BattishAnim", "FLYING"),
            "BattishAnim#RESTING", holdingClip("BattishAnim", "RESTING"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD, Map.of());

        List<JsonTree> styles = stylesOf(models, "minecraft:battish");
        JsonTree rest = styles.getLast();
        assertEquals("rest", rest.findString("id").orElseThrow(),
            "the resting selection is not the gait default, so it earns the row");
        assertEquals(List.of(), sourcesOf(rest),
            "its clip holds one pose and the moving sibling is evicted, so nothing travels");
    }

    // ------------------------------------------------------------------------------------
    // 7 - the pace and the ceiling
    // ------------------------------------------------------------------------------------

    /** A subject whose one form plays a walk clip on the stride pair, and nothing else. */
    private static @NotNull Map<String, PoseOutcome> walking(@NotNull String model) {
        return Map.of(model, posing(model, Map.of(), List.of(strideSite(model + "Anim#WALK"))));
    }

    private static @NotNull Map<String, KeyframeClip> walkClip(@NotNull String model) {
        return Map.of(model + "Anim#WALK", movingClip(model + "Anim", "WALK"));
    }

    @Test
    @DisplayName("a stride side reading the walk clock walks at its pace, ships its probe's sources and carries its ceiling last")
    void aReadingStrideWalksAtItsPace() {
        JsonTree models = JsonTree.object().put("minecraft:walker", entityRow("WalkerModel"));
        Diagnostics diagnostics = fresh();

        StyleFlow.emit(diagnostics, models, walking("WalkerModel"), walkClip("WalkerModel"), PERIOD,
            Map.of("minecraft:walker", adultPace(0.35242295f, 0.35242295f, 0.7929517f, 0.7929517f)));

        JsonTree stride = rowAt(stylesOf(models, "minecraft:walker"), "stride", Optional.empty());
        assertEquals(List.of("stride"), sourcesOf(stride), "the paced side ships the sources its probe measured");
        assertEquals(Optional.of(0.35242295f), driveOf(stride, "walkAnimationSpeed").flatMap(drive -> drive.findFloat("extent")),
            "the swing is held at the wander's speed");
        assertEquals(Optional.of("hold"), driveOf(stride, "walkAnimationSpeed").flatMap(drive -> drive.findString("wave")));
        assertEquals(Optional.of(0.35242295f), driveOf(stride, "walkAnimationPos").flatMap(drive -> drive.findFloat("extent")),
            "the clock ramps at the wander's rate");
        assertEquals(Optional.of("ramp"), driveOf(stride, "walkAnimationPos").flatMap(drive -> drive.findString("wave")));
        assertEquals("ceiling", stride.keys().toList().getLast(), "the ceiling is the row's last member");
        JsonTree ceiling = stride.find("ceiling").orElseThrow();
        assertEquals(List.of("speed", "rate"), ceiling.keys().toList());
        assertEquals(0.7929517f, ceiling.findFloat("speed").orElseThrow(), "the ceiling's swing is the fastest walk's");
        assertEquals(0.7929517f, ceiling.findFloat("rate").orElseThrow(), "the ceiling's clock is the fastest walk's");

        assertEquals(List.of(), messages(diagnostics, Diagnostics.Severity.ERROR));
        List<String> lines = messages(diagnostics, Diagnostics.Severity.INFO);
        assertTrue(lines.contains("minecraft:walker adult walks at speed 0.35242295, clock 0.35242295 a tick - fixture stroll"),
            "the side's wander is logged: " + lines);
        assertTrue(lines.contains("minecraft:walker adult reaches speed 0.7929517, clock 0.7929517 a tick at its fastest - fixture flight"),
            "and its fastest walk right after: " + lines);
        assertTrue(lines.contains("1 stride side(s) walk at a vanilla pace; 0 read no walk clock and keep hold 1 / ramp 1"),
            "the census counts the side: " + lines);
    }

    @Test
    @DisplayName("a stride side reading the walk clock with no pace is an error, and keeps hold 1 / ramp 1")
    void aReadingStrideWithNoPaceIsAnError() {
        JsonTree models = JsonTree.object().put("minecraft:walker", entityRow("WalkerModel"));
        Diagnostics diagnostics = fresh();

        StyleFlow.emit(diagnostics, models, walking("WalkerModel"), walkClip("WalkerModel"), PERIOD, Map.of());

        assertEquals(List.of("minecraft:walker style 'stride' moves by the walk clock at adult and has no pace"),
            messages(diagnostics, Diagnostics.Severity.ERROR), "the policy's gap stops the flow");
        JsonTree stride = rowAt(stylesOf(models, "minecraft:walker"), "stride", Optional.empty());
        assertEquals(Optional.of(1f), driveOf(stride, "walkAnimationSpeed").flatMap(drive -> drive.findFloat("extent")));
        assertEquals(Optional.of(1f), driveOf(stride, "walkAnimationPos").flatMap(drive -> drive.findFloat("extent")));
        assertTrue(stride.find("ceiling").isEmpty(), "a side with no pace carries no ceiling");
    }

    @Test
    @DisplayName("two ages reading the walk clock at paces of different rate split by their drives, the sources alike")
    void twoAgesSplitByTheirDrives() {
        JsonTree models = JsonTree.object().put("minecraft:walker", agedRow("WalkerModel", "WalkerBabyModel"));
        Map<String, PoseOutcome> poses = new LinkedHashMap<>(walking("WalkerModel"));
        poses.putAll(Map.of("WalkerBabyModel", posing("WalkerBabyModel", Map.of(), List.of(strideSite("WalkerModelAnim#WALK")))));
        Diagnostics diagnostics = fresh();

        // One fastest walk for both ages, so nothing but the wander's clock tells the sides apart.
        StyleFlow.emit(diagnostics, models, poses, walkClip("WalkerModel"), PERIOD, Map.of("minecraft:walker",
            new EntityPace(Optional.of(side(0.5f, 0.5f, 1f, 3f)), Optional.of(side(0.5f, 1.5f, 1f, 3f)))));

        List<JsonTree> styles = stylesOf(models, "minecraft:walker");
        JsonTree adult = rowAt(styles, "stride", Optional.of("adult"));
        JsonTree baby = rowAt(styles, "stride", Optional.of("baby"));
        assertEquals(sourcesOf(adult), sourcesOf(baby), "the two sides measure the same sources");
        assertEquals(List.of("stride"), sourcesOf(adult));
        assertEquals(Optional.of(0.5f), driveOf(adult, "walkAnimationPos").flatMap(drive -> drive.findFloat("extent")));
        assertEquals(Optional.of(1.5f), driveOf(baby, "walkAnimationPos").flatMap(drive -> drive.findFloat("extent")),
            "the baby's clock runs at its own rate");
        assertTrue(messages(diagnostics, Diagnostics.Severity.INFO)
                .contains("minecraft:walker style 'stride' measures apart by age and is emitted age-split"),
            "the split is logged");
        assertTrue(messages(diagnostics, Diagnostics.Severity.INFO)
                .contains("2 stride side(s) walk at a vanilla pace; 0 read no walk clock and keep hold 1 / ramp 1"),
            "both sides are counted");
    }

    @Test
    @DisplayName("two ages walking alike that reach different fastest walks split, each carrying its own ceiling")
    void twoAgesSplitByTheirCeilings() {
        JsonTree models = JsonTree.object().put("minecraft:walker", agedRow("WalkerModel", "WalkerBabyModel"));
        Map<String, PoseOutcome> poses = new LinkedHashMap<>(walking("WalkerModel"));
        poses.putAll(Map.of("WalkerBabyModel", posing("WalkerBabyModel", Map.of(), List.of(strideSite("WalkerModelAnim#WALK")))));

        StyleFlow.emit(fresh(), models, poses, walkClip("WalkerModel"), PERIOD, Map.of("minecraft:walker",
            new EntityPace(Optional.of(side(0.5f, 0.5f, 1f, 1f)), Optional.of(side(0.5f, 0.5f, 1f, 3f)))));

        List<JsonTree> styles = stylesOf(models, "minecraft:walker");
        assertEquals(Optional.of(1f), rowAt(styles, "stride", Optional.of("adult")).find("ceiling")
            .flatMap(ceiling -> ceiling.findFloat("rate")), "the adult carries its own fastest walk");
        assertEquals(Optional.of(3f), rowAt(styles, "stride", Optional.of("baby")).find("ceiling")
            .flatMap(ceiling -> ceiling.findFloat("rate")), "and the baby its own, rather than one row carrying either");
    }

    @Test
    @DisplayName("a stride reading no walk clock keeps hold 1 / ramp 1 and no ceiling, whatever pace is held for it")
    void anInertStrideKeepsTheLiteralPair() {
        JsonTree models = JsonTree.object().put("minecraft:floater", entityRow("FloaterModel"));
        Map<String, PoseOutcome> poses = Map.of("FloaterModel", posing("FloaterModel",
            Map.of("tail", Map.of(PoseChannel.Y_ROT, PoseExpr.operation(PoseOperator.MUL,
                new PoseExpr.Input("ageInTicks"), new PoseExpr.Constant(0.1f)))), List.of()));
        Diagnostics diagnostics = fresh();

        StyleFlow.emit(diagnostics, models, poses, Map.of(), PERIOD,
            Map.of("minecraft:floater", adultPace(0.5f, 0.5f, 1f, 1f)));

        JsonTree stride = rowAt(stylesOf(models, "minecraft:floater"), "stride", Optional.empty());
        assertEquals(List.of("tick"), sourcesOf(stride));
        assertEquals(Optional.of(1f), driveOf(stride, "walkAnimationSpeed").flatMap(drive -> drive.findFloat("extent")));
        assertEquals(Optional.of(1f), driveOf(stride, "walkAnimationPos").flatMap(drive -> drive.findFloat("extent")));
        assertTrue(stride.find("ceiling").isEmpty(), "an inert side carries no ceiling");
        assertEquals(List.of(), messages(diagnostics, Diagnostics.Severity.ERROR));
        assertTrue(messages(diagnostics, Diagnostics.Severity.INFO)
                .contains("0 stride side(s) walk at a vanilla pace; 1 read no walk clock and keep hold 1 / ramp 1"),
            "the inert side is counted as one");
    }

    @Test
    @DisplayName("a pace that stills the stride's clip moves its sources, and the move is an error")
    void aPaceMovingTheSourcesIsAnError() {
        JsonTree models = JsonTree.object().put("minecraft:walker", entityRow("WalkerModel"));
        Diagnostics diagnostics = fresh();

        StyleFlow.emit(diagnostics, models, walking("WalkerModel"), walkClip("WalkerModel"), PERIOD,
            Map.of("minecraft:walker", adultPace(0f, 0f, 0f, 0f)));

        assertEquals(List.of("minecraft:walker style 'stride' adult sources move under the pace: [stride] -> []"),
            messages(diagnostics, Diagnostics.Severity.ERROR), "a pace holding the clip's amplitude at zero is caught");
    }

    @Test
    @DisplayName("a ceiling under its own wander is an error")
    void aCeilingUnderItsWanderIsAnError() {
        JsonTree models = JsonTree.object().put("minecraft:walker", entityRow("WalkerModel"));
        Diagnostics diagnostics = fresh();

        StyleFlow.emit(diagnostics, models, walking("WalkerModel"), walkClip("WalkerModel"), PERIOD,
            Map.of("minecraft:walker", adultPace(0.5f, 0.5f, 0.4f, 0.6f)));

        assertEquals(List.of("minecraft:walker adult ceiling [0.4, 0.6] is under its wander [0.5, 0.5]"),
            messages(diagnostics, Diagnostics.Severity.ERROR));
    }

    // ------------------------------------------------------------------------------------
    // 8 - the clocks
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a clock lands its idle half on idle and its stride half on stride, a held zero spelled")
    void aClockLandsEachHalfOnItsGait() {
        JsonTree models = JsonTree.object().put("minecraft:finned", entityRow("FinnedModel"));
        Map<String, PoseOutcome> poses = Map.of("FinnedModel", posing("FinnedModel", Map.of(
            "tail", Map.of(PoseChannel.Y_ROT, PoseExpr.operation(PoseOperator.MUL,
                new PoseExpr.Input("tailAnimation"), new PoseExpr.Constant(0.5f))),
            "spike", Map.of(PoseChannel.Y, PoseExpr.operation(PoseOperator.MUL,
                new PoseExpr.Input("spikesAnimation"), new PoseExpr.Constant(4f)))), List.of()));

        StyleFlow.emit(fresh(), models, poses, Map.of(), PERIOD, Map.of());

        List<JsonTree> styles = stylesOf(models, "minecraft:finned");
        JsonTree idle = rowAt(styles, "idle", Optional.empty());
        assertEquals(List.of("ageInTicks", "tailAnimation", "spikesAnimation"), drivenFields(idle));
        assertEquals(Optional.of("ramp"), driveOf(idle, "tailAnimation").flatMap(drive -> drive.findString("wave")));
        assertEquals(Optional.of(0.125f), driveOf(idle, "tailAnimation").flatMap(drive -> drive.findFloat("extent")));
        assertEquals(Optional.of(1f), driveOf(idle, "spikesAnimation").flatMap(drive -> drive.findFloat("extent")));
        assertEquals(List.of("figure"), sourcesOf(idle), "a ramped clock files as a render-state scalar");

        JsonTree stride = rowAt(styles, "stride", Optional.empty());
        assertEquals("idle", stride.findString("base").orElseThrow());
        assertEquals(List.of("walkAnimationSpeed", "walkAnimationPos", "tailAnimation", "spikesAnimation"), drivenFields(stride),
            "the stride spells each half that differs, after the walk pair");
        assertEquals(Optional.of(0.5f), driveOf(stride, "tailAnimation").flatMap(drive -> drive.findFloat("extent")));
        assertEquals(Optional.of(0f), driveOf(stride, "spikesAnimation").flatMap(drive -> drive.findFloat("extent")),
            "a held zero is spelled, or the stride would inherit the idle's one");
    }

    @Test
    @DisplayName("a clock with no stride half is inherited by the stride, not spelled on it")
    void aClockWithNoStrideHalfIsInherited() {
        JsonTree models = JsonTree.object().put("minecraft:winged", entityRow("WingedModel"));
        Map<String, PoseOutcome> poses = Map.of("WingedModel", posing("WingedModel", Map.of(
            "wing", Map.of(PoseChannel.Z_ROT, PoseExpr.operation(PoseOperator.MUL,
                new PoseExpr.Input("flapTime"), new PoseExpr.Constant(6.2831855f)))), List.of()));

        StyleFlow.emit(fresh(), models, poses, Map.of(), PERIOD, Map.of());

        List<JsonTree> styles = stylesOf(models, "minecraft:winged");
        JsonTree idle = rowAt(styles, "idle", Optional.empty());
        assertEquals(Optional.of(0.1f), driveOf(idle, "flapTime").flatMap(drive -> drive.findFloat("extent")));
        JsonTree stride = rowAt(styles, "stride", Optional.empty());
        assertEquals(List.of("walkAnimationSpeed", "walkAnimationPos"), drivenFields(stride),
            "the stride composes the idle's beat in rather than spelling it");
    }

    @Test
    @DisplayName("an aged side drops a grouped drive of the other age and a clock its own forms do not read")
    void anAgedSideKeepsOnlyWhatItsFormsRead() {
        JsonTree models = JsonTree.object().put("minecraft:axolittle", agedRow("AxoAdultModel", "AxoBabyModel"));
        Map<String, PoseOutcome> poses = Map.of(
            "AxoAdultModel", posing("AxoAdultModel", Map.of(
                "body", Map.of(PoseChannel.X_ROT, PoseExpr.operation(PoseOperator.MUL,
                    new PoseExpr.Input("movingFactor"), new PoseExpr.Constant(0.5f))),
                "tail", Map.of(PoseChannel.Y_ROT, PoseExpr.operation(PoseOperator.MUL,
                    new PoseExpr.Input("ageInTicks"), new PoseExpr.Constant(0.1f)))), List.of()),
            "AxoBabyModel", posing("AxoBabyModel", Map.of(),
                List.of(selectSite("AxoAnim#IDLE_UNDER_WATER", "idleUnderWaterAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of("AxoAnim#IDLE_UNDER_WATER", movingClip("AxoAnim", "IDLE_UNDER_WATER"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD, Map.of());

        List<JsonTree> styles = stylesOf(models, "minecraft:axolittle");
        for (JsonTree row : styles) {
            String age = row.findString("age").orElse("");
            if (age.equals("adult"))
                assertTrue(row.find("drives").orElseThrow().elements().noneMatch(drive ->
                    drive.findString("group").filter("axolotl_clip"::equals).isPresent()),
                    "an adult row carries no selection of the baby's group: " + row);
            if (age.equals("baby"))
                assertTrue(driveOf(row, "movingFactor").isEmpty(), "a baby row drives no clock its forms do not read: " + row);
        }
        assertEquals(Optional.of(0f), driveOf(rowAt(styles, "idle", Optional.of("adult")), "movingFactor")
            .flatMap(drive -> drive.findFloat("extent")), "the adult idle holds the clock's idle half");
        assertTrue(driveOf(rowAt(styles, "idle", Optional.of("baby")), "idleUnderWaterAnimationState").isPresent(),
            "the baby idle takes its group's resting selection");
    }

    @Test
    @DisplayName("a pass's opacity reading a clock drives that clock on the idle row and adds its kind to the sources")
    void anOpacityDrivesTheClockItReads() {
        JsonTree glow = entityRow("GlowModel");
        glow.childArray("overlays").add(JsonTree.object()
            .put("geometry", "GlowModel#createHeartLayer")
            .put("opacity", JsonTree.object().put("input", "heartAnimation")));
        JsonTree models = JsonTree.object().put("minecraft:glow", glow);
        Map<String, PoseOutcome> poses = Map.of("GlowModel", posing("GlowModel",
            Map.of("body", Map.of(PoseChannel.X, new PoseExpr.Constant(1f))), List.of()));

        StyleFlow.emit(fresh(), models, poses, Map.of(), PERIOD, Map.of());

        List<JsonTree> styles = stylesOf(models, "minecraft:glow");
        JsonTree idle = rowAt(styles, "idle", Optional.empty());
        assertEquals(Optional.of("trapezoid"), driveOf(idle, "heartAnimation").flatMap(drive -> drive.findString("wave")),
            "the heart's clock rides the idle row though no pose reads it");
        assertEquals(List.of("figure"), sourcesOf(idle), "the pulse is the row's mechanism");
        assertEquals(List.of("figure"), sourcesOf(rowAt(styles, "stride", Optional.empty())),
            "and the stride composing over the idle pulses with it");
    }

    /** The emitted models tree read back through the pipeline's own loader. */
    private static @NotNull Entity load(@NotNull JsonTree models) {
        JsonTree file = JsonTree.object();
        file.put("period_ticks", PERIOD);
        file.put("models", models);
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", new EntityMesh.Bone());
        Entity built = EntityIndexBuilder.assemble(
                Map.of("RabbityModel#createBodyLayer", mesh),
                new Gson().fromJson(file.toJson(), EntityModelsTable.class), Map.of())
            .get("minecraft:rabbity");
        assertNotNull(built, "the emitted table is expected to assemble");
        return built;
    }

    /**
     * Finds the first row of one id the catalog carries.
     *
     * @param catalog the catalog searched
     * @param id the style id to look up
     * @return the first row of that id, or empty where the catalog carries none
     */
    private static @NotNull Optional<PoseStyle> rowOf(@NotNull StyleCatalog catalog, @NotNull String id) {
        return catalog.styles().stream()
            .filter(style -> style.id().equals(id))
            .findFirst();
    }

}
