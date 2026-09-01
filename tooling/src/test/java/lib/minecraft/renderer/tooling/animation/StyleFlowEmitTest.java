package lib.minecraft.renderer.tooling.animation;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The style emitter's contracts: every emitted id unique per entity - two rows sharing one only
 * where disjoint ages split them - and off the reserved ids, every state field earning exactly
 * one choice, every emittable token loader-readable, and the two still-nesses kept apart in the
 * emit log.
 *
 * <p>Exercised on hand-built model trees and pose programs rather than a walked corpus, so each
 * contract is pinned per shape independently of any entity's layout; the corpus-wide answers are
 * read off the emitted table itself.
 */
@DisplayName("style flow emit")
class StyleFlowEmitTest {

    private static final int PERIOD = 24;

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

        return new PoseOutcome.Extracted(new PoseProgram(model, List.of(), bones, sites));
    }

    private static @NotNull PoseClipSite selectSite(@NotNull String clip, @NotNull String field) {
        return new PoseClipSite(clip, PoseClipSite.Gate.SELECT, field,
            List.of(new PoseExpr.Input("ageInTicks")), PoseClipSite.ALWAYS);
    }

    private static @NotNull PoseClipSite strideSite(@NotNull String clip) {
        return new PoseClipSite(clip, PoseClipSite.Gate.STRIDE, "",
            List.of(new PoseExpr.Input("walkAnimationPos"), new PoseExpr.Input("walkAnimationSpeed"),
                PoseExpr.Const.of(1.5f), PoseExpr.Const.of(2.5f)),
            PoseClipSite.ALWAYS);
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

    private static @NotNull List<JsonTree> stylesOf(@NotNull JsonTree models, @NotNull String entity) {
        return models.child(entity).find("styles").orElseThrow().elements().toList();
    }

    private static @NotNull List<String> idsOf(@NotNull List<JsonTree> styles) {
        return styles.stream().map(row -> row.findString("id").orElseThrow()).toList();
    }

    @SuppressWarnings("unchecked")
    private static @NotNull Set<String> poseFlowSet(@NotNull String name) throws Exception {
        Field field = PoseFlow.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Set<String>) field.get(null);
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
                    PoseExpr.Op.of(PoseOperator.MUL, new PoseExpr.Input("tentacleAngle"), PoseExpr.Const.of(2f)))),
                List.of(selectSite("UniqueAnim#CROAK", "croakAnimationState"))),
            "AxoishModel", posing("AxoishModel",
                Map.of("tail", Map.of(PoseChannel.X_ROT, PoseExpr.Op.of(PoseOperator.MUL,
                    new PoseExpr.Input("playingDeadFactor"), PoseExpr.Const.of(0.5f)))),
                List.of()),
            "AxoishBabyModel", posing("AxoishBabyModel", Map.of(),
                List.of(selectSite("AxoishAnim#PLAY_DEAD", "playDeadAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of(
            "UniqueAnim#CROAK", movingClip("UniqueAnim", "CROAK"),
            "AxoishAnim#PLAY_DEAD", holdingClip("AxoishAnim", "PLAY_DEAD"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD);

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

    /** The six select tokens whose member is a resting selection at one gait or both. */
    private static final @NotNull Set<String> DEFAULT_TOKENS = Set.of(
        "flyAnimationState", "idleAnimationState", "idle",
        "idleHeadTiltAnimationState", "hopAnimationState", "slide");

    @Test
    @DisplayName("each of the 44 select tokens is exactly one of row, folded default, or the no-row token")
    void everyStateFieldEarnsAChoice() throws Exception {
        Set<String> figures = new LinkedHashSet<>(Set.of("ageInTicks", "walkAnimationPos", "walkAnimationSpeed"));
        for (StyleRoster.Figure figure : StyleRoster.FIGURES) figures.add(figure.field());
        assertEquals(poseFlowSet("DRIVEN_FIGURES"), figures,
            "the roster's figures are the pose walk's own figure half");

        Set<String> driven = StyleRoster.driven();
        assertEquals(47, driven.size(), "the roster drives 47 fields");
        Set<String> union = new LinkedHashSet<>(figures);
        union.addAll(driven);
        assertEquals(poseFlowSet("DRIVEN"), union,
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
        assertEquals(37, rows.size(), "37 select tokens earn a row of their own");
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
        Set.of("id", "base", "age", "sources", "toggles", "drives");

    private static final @NotNull Set<String> DRIVE_MEMBERS =
        Set.of("field", "wave", "rest", "extent", "group");

    private static final @NotNull Set<String> WAVES = Set.of("hold", "ramp", "sweep", "cycle");

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
        // A fixture that produces every emittable shape at once: sweep and cycle figures, hold and
        // ramp drives, a grouped selection with its toggles, a gated and an ungated source, a
        // baseless row, and a baby-only row.
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
                    "tentacle", Map.of(PoseChannel.X_ROT, PoseExpr.Op.of(PoseOperator.MUL,
                        new PoseExpr.Input("tentacleAngle"), PoseExpr.Const.of(2f))),
                    "wing", Map.of(PoseChannel.Y_ROT, PoseExpr.Op.of(PoseOperator.MUL,
                        new PoseExpr.Input("flapTime"), PoseExpr.Const.of(3f)))),
                List.of(selectSite("SpellAnim#CROAK", "croakAnimationState"))),
            "HatchModel", posing("HatchModel",
                Map.of("body", Map.of(PoseChannel.X, PoseExpr.Const.of(1f))), List.of()),
            "HatchBabyModel", posing("HatchBabyModel", Map.of(),
                List.of(selectSite("HatchAnim#SWIM", "swimAnimation"))));
        Map<String, KeyframeClip> clips = Map.of(
            "SpellAnim#CROAK", movingClip("SpellAnim", "CROAK"),
            "HatchAnim#SWIM", movingClip("HatchAnim", "SWIM"));

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD);

        List<JsonTree> rows = new ArrayList<>(stylesOf(models, "minecraft:probe"));
        rows.addAll(stylesOf(models, "minecraft:hatchling"));
        boolean[] sawGate = {false};
        boolean[] sawBare = {false};
        for (JsonTree row : rows) {
            for (String member : row.keys().toList())
                assertTrue(ROW_MEMBERS.contains(member), "row member '" + member + "' is not loader-read");
            row.findString("age").ifPresent(age -> assertTrue(AGES.contains(age)));
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
    // 4 - the two still-nesses
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
                Map.of("head", Map.of(PoseChannel.X_ROT, PoseExpr.Const.of(0.3f))), List.of()),
            "LittleModel", posing("LittleModel",
                Map.of("tail", Map.of(PoseChannel.Y_ROT, PoseExpr.Op.of(PoseOperator.MUL,
                    new PoseExpr.Input("ageInTicks"), PoseExpr.Const.of(0.1f)))), List.of()),
            "LittleBabyModel", posing("LittleBabyModel", Map.of(),
                List.of(selectSite("LittleAnim#WALK", "walkAnimationState"))));
        Map<String, KeyframeClip> clips = Map.of("LittleAnim#WALK", movingClip("LittleAnim", "WALK"));

        Diagnostics diagnostics = fresh();
        StyleFlow.emit(diagnostics, models, poses, clips, PERIOD);

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

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD);

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

        StyleFlow.emit(fresh(), models, poses, clips, PERIOD);

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
                Map.of("head", Map.of(PoseChannel.X_ROT, PoseExpr.Const.of(0.3f))), List.of()),
            "SheepishModel", posing("SheepishModel",
                Map.of("head", Map.of(PoseChannel.X_ROT, PoseExpr.Const.of(0.3f))), List.of()));

        Diagnostics diagnostics = fresh();
        StyleFlow.emit(diagnostics, models, poses, Map.of(), PERIOD);

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

}
