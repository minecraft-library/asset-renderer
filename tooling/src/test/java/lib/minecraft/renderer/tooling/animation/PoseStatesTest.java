package lib.minecraft.renderer.tooling.animation;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PosePredicate;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what a state silhouette carries - the branch a resting subject does not take, folded at
 * rest with one answer flipped, keeping only what it places away from the resting row.
 *
 * <p>And how a row two ages reach writes each state once: a channel the ages agree on as it stands,
 * a position each age places at every site's own rest left out, and anything else refused.
 */
@DisplayName("the resting silhouette of each state branch")
class PoseStatesTest {

    /** The one figure the tick drives in these fixtures. */
    private static final @NotNull Set<String> FREE = Set.of("walkAnimationPos", "walkAnimationSpeed");

    /** The branch a body takes when a boolean the render state holds is set. */
    private static @NotNull PosePredicate sitting() {
        return new PosePredicate(PosePredicate.Comparison.NE,
            new PoseExpr.Input("isSitting"), new PoseExpr.Constant(0));
    }

    /** The stride term every walker carries - zero before anything has happened to the subject. */
    private static @NotNull PoseExpr stride() {
        return PoseExpr.operation(PoseOperator.MUL,
            PoseExpr.operation(PoseOperator.MTH_COS,
                PoseExpr.operation(PoseOperator.F2D,
                    PoseExpr.operation(PoseOperator.MUL, new PoseExpr.Input("walkAnimationPos"), new PoseExpr.Constant(0.6662f)))),
            new PoseExpr.Input("walkAnimationSpeed"));
    }

    private static @NotNull Map<PoseChannel, PoseExpr> channels(Object... pairs) {
        Map<PoseChannel, PoseExpr> out = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2)
            out.put((PoseChannel) pairs[at], (PoseExpr) pairs[at + 1]);
        return out;
    }

    private static @NotNull PoseProgram program(@NotNull Map<String, Map<PoseChannel, PoseExpr>> bones) {
        return new PoseProgram("Model", List.of(), bones, Map.of(), List.of());
    }

    private static @NotNull Map<String, PoseStates.Silhouette> states(
        @NotNull PoseProgram program, @NotNull Map<String, String> subjectRest,
        @NotNull Map<String, String> restDefaults) {

        return PoseStates.of(program, subjectRest, restDefaults, Map.of(), Map.of(), FREE, Map.of());
    }

    @Test
    @DisplayName("a boolean branch places its bones under the flipped answer")
    void booleanBranchPlacesUnderTheFlippedAnswer() {
        PosePredicate sitting = sitting();
        PoseProgram program = program(Map.of(
            "body", channels(
                PoseChannel.Y, new PoseExpr.Select(sitting, new PoseExpr.Constant(18f), new PoseExpr.BoneRead("body", PoseChannel.Y)),
                PoseChannel.X_ROT, new PoseExpr.Select(sitting, new PoseExpr.Constant(0.7853982f), new PoseExpr.Constant(1.5707964f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of());

        assertEquals(Set.of("isSitting=true"), states.keySet(), "the subject rests unsat, so the state is sitting");
        Map<PoseChannel, PoseExpr> body = states.get("isSitting=true").bones().get("body");
        assertEquals(new PoseExpr.Constant(18f), body.get(PoseChannel.Y), "the placed pivot, as a literal");
        assertEquals(new PoseExpr.Constant(0.7853982f), body.get(PoseChannel.X_ROT), "the lowered body's angle");
    }

    @Test
    @DisplayName("a state's age-scaled offset is placed at the age the defaults carry")
    void anAgeScaledOffsetIsPlacedAtTheDefaultsAge() {
        // HumanoidModel's attack places each arm at 5 * ageScale; a baby's row hands the silhouettes
        // the same defaults copy it folds against, so the baby's arm lands at half the adult's.
        PoseProgram program = program(Map.of(
            "left_arm", channels(PoseChannel.X, new PoseExpr.Select(sitting(),
                PoseExpr.operation(PoseOperator.MUL, new PoseExpr.Constant(5f), new PoseExpr.Input("ageScale")),
                new PoseExpr.BoneRead("left_arm", PoseChannel.X)))));

        Map<String, PoseStates.Silhouette> baby =
            PoseStates.of(program, Map.of(), Map.of(), Map.of(), Map.of("ageScale", 0.5f), FREE, Map.of());
        Map<String, PoseStates.Silhouette> adult =
            PoseStates.of(program, Map.of(), Map.of(), Map.of(), Map.of("ageScale", 1f), FREE, Map.of());

        assertEquals(new PoseExpr.Constant(2.5f),
            baby.get("isSitting=true").bones().get("left_arm").get(PoseChannel.X), "the baby's arm at half");
        assertEquals(new PoseExpr.Constant(5f),
            adult.get("isSitting=true").bones().get("left_arm").get(PoseChannel.X), "the adult's at the whole");
    }

    @Test
    @DisplayName("a channel the state leaves where the resting row leaves it is not written")
    void unmovedChannelsAreOmitted() {
        PosePredicate sitting = sitting();
        PoseProgram program = program(Map.of(
            "head", channels(PoseChannel.X_ROT, new PoseExpr.Constant(0f)),
            "tail", channels(
                PoseChannel.Y, new PoseExpr.Select(sitting, new PoseExpr.Constant(5f), new PoseExpr.Constant(5f)),
                PoseChannel.Z, new PoseExpr.Select(sitting, new PoseExpr.Constant(6f), new PoseExpr.BoneRead("tail", PoseChannel.Z)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of());

        Map<String, Map<PoseChannel, PoseExpr>> placed = states.get("isSitting=true").bones();
        assertFalse(placed.containsKey("head"), "a bone the state never moves is absent");
        assertEquals(Set.of(PoseChannel.Z), placed.get("tail").keySet(),
            "a channel both arms write the same is absent, the one they disagree about is kept");
    }

    @Test
    @DisplayName("a placement relative to the authored pivot keeps the read of that pivot")
    void relativePlacementKeepsTheBoneRead() {
        PoseExpr read = new PoseExpr.BoneRead("tail1", PoseChannel.Y);
        PoseProgram program = program(Map.of(
            "tail1", channels(PoseChannel.Y, new PoseExpr.Select(sitting(),
                PoseExpr.operation(PoseOperator.ADD, read, new PoseExpr.Constant(8f)), read))));

        PoseExpr placed = states(program, Map.of(), Map.of()).get("isSitting=true").bones().get("tail1").get(PoseChannel.Y);

        PoseExpr.Op shifted = assertInstanceOf(PoseExpr.Op.class, placed);
        assertEquals(PoseOperator.ADD, shifted.operator());
        assertEquals(read, shifted.operands().getFirst(), "the mesh's own value stays a read, never a number");
        assertEquals(new PoseExpr.Constant(8f), shifted.operands().getLast());
    }

    @Test
    @DisplayName("a shared term is compared once however many paths reach it")
    void sharedTermsCompareOncePerNode() {
        // Forty doublings of one read stand for a million million paths; a comparison that recursed
        // per path would not return, and one that answers each pair of nodes once returns at once.
        assertTrue(PoseStates.sameShape(doubled(40), doubled(40)), "one shape, however it is shared");
        assertFalse(PoseStates.sameShape(doubled(40),
                PoseExpr.operation(PoseOperator.ADD, doubled(39), new PoseExpr.Constant(1f))),
            "a leaf that differs is found down the one path it lives on");
        assertFalse(PoseStates.sameShape(doubled(3), null));
    }

    /** One read of the mesh added to itself {@code depth} times over, each sum shared by the next. */
    private static @NotNull PoseExpr doubled(int depth) {
        PoseExpr term = new PoseExpr.BoneRead("body", PoseChannel.Y);
        for (int level = 0; level < depth; level++)
            term = PoseExpr.operation(PoseOperator.ADD, term, term);
        return term;
    }

    @Test
    @DisplayName("a stride term folds to what it rests at, so a tuck over the stride is the tuck alone")
    void strideFoldsAtRest() {
        PoseExpr walk = stride();
        PoseProgram program = program(Map.of(
            "right_arm", channels(PoseChannel.X_ROT, new PoseExpr.Select(sitting(),
                PoseExpr.operation(PoseOperator.ADD, walk, new PoseExpr.Constant(0.4f)), walk))));

        PoseExpr placed = states(program, Map.of(), Map.of()).get("isSitting=true").bones().get("right_arm").get(PoseChannel.X_ROT);

        assertEquals(new PoseExpr.Constant(0.4f), placed, "the stride rests at zero and the literal survives");
    }

    @Test
    @DisplayName("a figure the tick drives is no state, and neither is a comparison by order")
    void drivenAndOrderedComparisonsAreNoStates() {
        PosePredicate moving = new PosePredicate(PosePredicate.Comparison.NE,
            new PoseExpr.Input("isMoving"), new PoseExpr.Constant(0));
        PosePredicate fast = new PosePredicate(PosePredicate.Comparison.GT,
            new PoseExpr.Input("walkAnimationSpeed"), new PoseExpr.Constant(0.2f));
        PoseProgram program = program(Map.of(
            "body", channels(
                PoseChannel.Y, new PoseExpr.Select(moving, new PoseExpr.Constant(3f), new PoseExpr.Constant(0f)),
                PoseChannel.Z, new PoseExpr.Select(fast, new PoseExpr.Constant(3f), new PoseExpr.Constant(0f)))));

        Map<String, PoseStates.Silhouette> states = PoseStates.of(program, Map.of(), Map.of(), Map.of(),
            Map.of(), Set.of("isMoving", "walkAnimationSpeed"), Map.of());

        assertTrue(states.isEmpty(), "a driven boolean stays symbolic in the row, a threshold is a number");
    }

    @Test
    @DisplayName("each enum constant the body tests is a state, except the one it rests holding")
    void enumConstantsAreStates() {
        PoseProgram program = program(Map.of(
            "head", channels(PoseChannel.X_ROT, new PoseExpr.Select(new PoseExpr.Answered.EnumMatch("pose", "SITTING").truthy(),
                new PoseExpr.Constant(1f),
                new PoseExpr.Select(new PoseExpr.Answered.EnumMatch("pose", "FLYING").truthy(), new PoseExpr.Constant(2f), new PoseExpr.Constant(0f))))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of("pose", "STANDING"));

        assertEquals(Set.of("pose=FLYING", "pose=SITTING"), states.keySet());
        assertEquals(new PoseExpr.Constant(1f), states.get("pose=SITTING").bones().get("head").get(PoseChannel.X_ROT));
        assertEquals(new PoseExpr.Constant(2f), states.get("pose=FLYING").bones().get("head").get(PoseChannel.X_ROT));

        assertEquals(Set.of("pose=FLYING"), states(program, Map.of("pose", "SITTING"), Map.of()).keySet(),
            "a subject resting in a constant has that constant as its row, not as a state");
    }

    @Test
    @DisplayName("a boolean resting set flips to unset")
    void restingTrueFlipsToFalse() {
        PosePredicate swimming = new PosePredicate(PosePredicate.Comparison.EQ,
            new PoseExpr.Constant(0), new PoseExpr.Input("isInWater"));
        PoseProgram program = program(Map.of(
            "body", channels(PoseChannel.Z_ROT, new PoseExpr.Select(swimming, new PoseExpr.Constant(1.5f), new PoseExpr.Constant(0f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of("isInWater", "true"), Map.of());

        assertEquals(Set.of("isInWater=false"), states.keySet(), "the fish rests swimming, so the state is on land");
        assertEquals(new PoseExpr.Constant(1.5f), states.get("isInWater=false").bones().get("body").get(PoseChannel.Z_ROT));
    }

    @Test
    @DisplayName("a unit figure resting at zero is moved to one, and one resting elsewhere is no state")
    void unitFigureRestingAtZeroFoldsAtOne() {
        PoseProgram program = program(Map.of(
            "head_parts", channels(
                PoseChannel.X_ROT, PoseExpr.operation(PoseOperator.MUL, new PoseExpr.Input("standAnimation"), new PoseExpr.Constant(0.5f)),
                PoseChannel.Y, PoseExpr.operation(PoseOperator.MUL, new PoseExpr.Input("scale"), new PoseExpr.Constant(3f)))));

        Map<String, PoseStates.Silhouette> states = PoseStates.of(program, Map.of(), Map.of(), Map.of(),
            Map.of("scale", 1f), FREE, Map.of());

        assertEquals(Set.of("standAnimation=1"), states.keySet(),
            "the completed stand is a state; a figure resting at one is what the row already holds");
        Map<PoseChannel, PoseExpr> placed = states.get("standAnimation=1").bones().get("head_parts");
        assertEquals(Set.of(PoseChannel.X_ROT), placed.keySet(), "only the channel the figure moves");
        assertEquals(0.5d, assertInstanceOf(PoseExpr.Constant.class, placed.get(PoseChannel.X_ROT)).value(), 1e-6);
    }

    @Test
    @DisplayName("a figure the frame spells as a boolean flips as one, however the body reads it")
    void frameSpelledBooleanFlipsAsABoolean() {
        PoseProgram program = program(Map.of(
            "body", channels(PoseChannel.Y, PoseExpr.operation(PoseOperator.MUL, new PoseExpr.Input("isBaby"), new PoseExpr.Constant(2f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of("isBaby", "false"), Map.of());

        assertEquals(Set.of("isBaby=true"), states.keySet(), "flipped through the frame, never moved to one");
    }

    @Test
    @DisplayName("a channel resting at no number in a state places nothing")
    void nonFiniteRestIsNoPlacement() {
        PosePredicate sitting = sitting();
        PoseProgram program = program(Map.of(
            "right_arm", channels(
                PoseChannel.X_ROT, new PoseExpr.Select(sitting,
                    PoseExpr.operation(PoseOperator.DIV, new PoseExpr.Input("useTicks"), new PoseExpr.Input("useDuration")),
                    new PoseExpr.Constant(0f)),
                PoseChannel.Y_ROT, new PoseExpr.Select(sitting, new PoseExpr.Constant(0.3f), new PoseExpr.Constant(0f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of());

        assertEquals(Set.of(PoseChannel.Y_ROT), states.get("isSitting=true").bones().get("right_arm").keySet(),
            "a zero over zero rests at no number and is left out");
    }

    @Test
    @DisplayName("a scale is never part of a silhouette, and a flag cannot be one")
    void scaleAndFlagChannelsAreOmitted() {
        // The scale is omitted by the silhouette's own rule. The flag is omitted by the TYPE: it
        // travels beside the channel map rather than in it, so a silhouette's channel keyspace has
        // no spelling for one and a state carrying a flag is unrepresentable rather than filtered.
        PosePredicate sitting = sitting();
        PoseProgram program = program(Map.of(
            "body", channels(
                PoseChannel.X_SCALE, new PoseExpr.Select(sitting, new PoseExpr.Constant(2f), new PoseExpr.Constant(1f)),
                PoseChannel.Y, new PoseExpr.Select(sitting, new PoseExpr.Constant(18f), new PoseExpr.Constant(14f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of());

        assertEquals(Set.of(PoseChannel.Y), states.get("isSitting=true").bones().get("body").keySet());
    }

    // ------------------------------------------------------------------------------------
    // one row two ages reach
    // ------------------------------------------------------------------------------------

    /** The small mesh a two-age row is drawn on at half the age, as a coordinate. */
    private static final @NotNull String SMALL = "Stand#small";

    /** The large mesh a two-age row is drawn on at the full age, as a coordinate. */
    private static final @NotNull String LARGE = "Stand#large";

    /** One state placing one bone's channels, at one age. */
    private static @NotNull Map<String, PoseStates.Silhouette> placing(
        @NotNull String bone, @NotNull Map<PoseChannel, PoseExpr> channels) {

        return Map.of("attackTime=1", new PoseStates.Silhouette(Map.of(bone, channels)));
    }

    /** The two ages' silhouettes, the small stand's at half the age. */
    private static @NotNull SortedMap<Float, Map<String, PoseStates.Silhouette>> byAge(
        @NotNull Map<String, PoseStates.Silhouette> half, @NotNull Map<String, PoseStates.Silhouette> whole) {

        SortedMap<Float, Map<String, PoseStates.Silhouette>> out = new TreeMap<>();
        out.put(0.5f, half);
        out.put(1f, whole);
        return out;
    }

    /** The sites each age is drawn on, the arm resting at {@code small} on the small mesh and {@code large} on the large one. */
    private static @NotNull SortedMap<Float, List<PoseStates.Site>> armAt(
        @NotNull Map<PoseChannel, Float> small, @NotNull Map<PoseChannel, Float> large) {

        SortedMap<Float, List<PoseStates.Site>> out = new TreeMap<>();
        out.put(0.5f, List.of(new PoseStates.Site(SMALL, Map.of("left_arm", small))));
        out.put(1f, List.of(new PoseStates.Site(LARGE, Map.of("left_arm", large))));
        return out;
    }

    /** The row resting the arm at its own read, as HumanoidModel's does. */
    private static final @NotNull Map<String, Map<PoseChannel, PoseExpr>> ROW = Map.of("left_arm", Map.of(
        PoseChannel.X, new PoseExpr.BoneRead("left_arm", PoseChannel.X),
        PoseChannel.X_ROT, new PoseExpr.Constant(0f)));

    @Test
    @DisplayName("a channel every age places alike is written as it stands, the reference age's own instance")
    void anAgreeingChannelIsKept() {
        PoseExpr whole = new PoseExpr.Constant(-0.0f);
        List<String> left = new ArrayList<>();

        Map<String, PoseStates.Silhouette> unified = PoseStates.unify("Stand",
            byAge(placing("left_arm", Map.of(PoseChannel.Z, new PoseExpr.Constant(-0.0f))),
                placing("left_arm", Map.of(PoseChannel.Z, whole))),
            1f, ROW, armAt(Map.of(), Map.of()), left);

        assertSame(whole, unified.get("attackTime=1").bones().get("left_arm").get(PoseChannel.Z),
            "the agreeing literal is kept, and no mesh is consulted for it");
        assertEquals(List.of(), left, "nothing is left at a rest");
    }

    @Test
    @DisplayName("a position each age places at the rest of every mesh drawn at that age is left out, and a state left empty with it")
    void aPositionAtEachSitesRestIsLeftOut() {
        List<String> left = new ArrayList<>();

        Map<String, PoseStates.Silhouette> unified = PoseStates.unify("Stand",
            byAge(placing("left_arm", Map.of(PoseChannel.X, new PoseExpr.Constant(2.5f))),
                placing("left_arm", Map.of(PoseChannel.X, new PoseExpr.Constant(5f)))),
            1f, ROW, armAt(Map.of(PoseChannel.X, 2.5f), Map.of(PoseChannel.X, 5f)), left);

        assertEquals(Map.of(), unified, "the state places nothing either size does not already rest at");
        assertEquals(List.of("attackTime=1 left_arm.x"), left, "and the channel is named as left at its rest");
    }

    @Test
    @DisplayName("an age placing nothing and an age placing its own rest agree to leave the channel out")
    void anAgeAtRestJoinsTheOmission() {
        List<String> left = new ArrayList<>();

        Map<String, PoseStates.Silhouette> unified = PoseStates.unify("Stand",
            byAge(Map.of(), placing("left_arm", Map.of(PoseChannel.X, new PoseExpr.Constant(5f)))),
            1f, ROW, armAt(Map.of(PoseChannel.X, 2.5f), Map.of(PoseChannel.X, 5f)), left);

        assertEquals(Map.of(), unified);
        assertEquals(List.of("attackTime=1 left_arm.x"), left);
    }

    @Test
    @DisplayName("a position placed apart refuses where one mesh at an age rests the part elsewhere")
    void oneSiteOffItsRestRefuses() {
        // The skeleton's row poses the stray at 5 and the parched skeleton at 5.5; a literal equal to
        // one site's rest is not every site's.
        SortedMap<Float, List<PoseStates.Site>> sites = armAt(Map.of(PoseChannel.X, 2.5f), Map.of(PoseChannel.X, 5f));
        sites.put(1f, List.of(new PoseStates.Site(LARGE, Map.of("left_arm", Map.of(PoseChannel.X, 5f))),
            new PoseStates.Site("Stand#parched", Map.of("left_arm", Map.of(PoseChannel.X, 5.5f)))));

        ToolingException raised = assertThrows(ToolingException.class, () -> PoseStates.unify("Stand",
            byAge(placing("left_arm", Map.of(PoseChannel.X, new PoseExpr.Constant(2.5f))),
                placing("left_arm", Map.of(PoseChannel.X, new PoseExpr.Constant(5f)))),
            1f, ROW, sites, new ArrayList<>()));

        for (String named : List.of("Stand", "attackTime=1", "left_arm.x", "Stand#parched", "5.5", "2.5"))
            assertTrue(raised.getMessage().contains(named), "the refusal names " + named + ": " + raised.getMessage());
    }

    @Test
    @DisplayName("a rotation placed apart by age refuses, a turn having no rest a mesh can prove")
    void aRotationApartRefuses() {
        // Everything a position would need holds - the row's turn is the part's own read, and each
        // site answers a "rest" equal to what its age places - so only the channel's kind refuses.
        Map<String, Map<PoseChannel, PoseExpr>> row = Map.of("left_arm", Map.of(
            PoseChannel.X_ROT, new PoseExpr.BoneRead("left_arm", PoseChannel.X_ROT)));

        assertThrows(ToolingException.class, () -> PoseStates.unify("Stand",
            byAge(placing("left_arm", Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(0.25f))),
                placing("left_arm", Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(0.5f)))),
            1f, row, armAt(Map.of(PoseChannel.X_ROT, 0.25f), Map.of(PoseChannel.X_ROT, 0.5f)), new ArrayList<>()));
    }

    @Test
    @DisplayName("a position placed apart refuses where the row's own channel is more than the part's read")
    void aRowChannelOtherThanTheReadRefuses() {
        // Left out, the state would leave the arm wherever the row puts it, which here is not the rest.
        Map<String, Map<PoseChannel, PoseExpr>> row = Map.of("left_arm", Map.of(PoseChannel.X,
            PoseExpr.operation(PoseOperator.ADD, new PoseExpr.BoneRead("left_arm", PoseChannel.X), new PoseExpr.Constant(1f))));

        assertThrows(ToolingException.class, () -> PoseStates.unify("Stand",
            byAge(placing("left_arm", Map.of(PoseChannel.X, new PoseExpr.Constant(2.5f))),
                placing("left_arm", Map.of(PoseChannel.X, new PoseExpr.Constant(5f)))),
            1f, row, armAt(Map.of(PoseChannel.X, 2.5f), Map.of(PoseChannel.X, 5f)), new ArrayList<>()));
    }

    @Test
    @DisplayName("a position placed apart refuses where a mesh's rest is unknown - a part it lacks, or a pivot that moves")
    void anUnknownRestRefuses() {
        ToolingException raised = assertThrows(ToolingException.class, () -> PoseStates.unify("Stand",
            byAge(placing("left_arm", Map.of(PoseChannel.Y, new PoseExpr.Constant(13f))),
                placing("left_arm", Map.of(PoseChannel.Y, new PoseExpr.Constant(2f)))),
            1f, ROW, armAt(Map.of(PoseChannel.X, 2.5f), Map.of(PoseChannel.X, 5f, PoseChannel.Y, 2f)), new ArrayList<>()));
        assertTrue(raised.getMessage().contains("an unknown value"), raised.getMessage());
    }

    @Test
    @DisplayName("a position placed apart refuses where an age places it by more than a literal")
    void aSymbolicPlacementRefuses() {
        assertThrows(ToolingException.class, () -> PoseStates.unify("Stand",
            byAge(placing("left_arm", Map.of(PoseChannel.X, new PoseExpr.BoneRead("body", PoseChannel.X))),
                placing("left_arm", Map.of(PoseChannel.X, new PoseExpr.Constant(5f)))),
            1f, ROW, armAt(Map.of(PoseChannel.X, 2.5f), Map.of(PoseChannel.X, 5f)), new ArrayList<>()));
    }

    @Test
    @DisplayName("two folds of one row agree where every expression spells one shape, and name the first channel they part on")
    void twoFoldsArePlacedApartByChannel() {
        PoseProgram here = program(Map.of("body", channels(PoseChannel.Y, new PoseExpr.Constant(12f),
            PoseChannel.X_ROT, new PoseExpr.Constant(0f))));
        PoseProgram same = program(Map.of("body", channels(PoseChannel.Y, new PoseExpr.Constant(12f),
            PoseChannel.X_ROT, new PoseExpr.Constant(0f))));
        PoseProgram moved = program(Map.of("body", channels(PoseChannel.Y, new PoseExpr.Constant(6f),
            PoseChannel.X_ROT, new PoseExpr.Constant(0f))));

        assertEquals(Optional.empty(), PoseStates.whereApart(here, same));
        assertEquals(Optional.of("body.y"), PoseStates.whereApart(here, moved));
        assertEquals(Optional.of("body.x_rot"), PoseStates.whereApart(here, program(Map.of("body",
            channels(PoseChannel.Y, new PoseExpr.Constant(12f), PoseChannel.X_ROT, new PoseExpr.Constant(-0.0f))))),
            "a signed zero is a different literal");
    }

    @Test
    @DisplayName("the writer spells the states after everything the runtime reads, and none where there are none")
    void writerSpellsStatesLast() {
        PosePredicate sitting = sitting();
        PoseProgram program = program(Map.of(
            "body", channels(
                PoseChannel.Y, new PoseExpr.Select(sitting, new PoseExpr.Constant(18f), new PoseExpr.BoneRead("body", PoseChannel.Y)))));
        PoseOutcome folded = new PoseOutcome.Extracted(PoseFold.fold(program, Map.of(), Map.of(), Map.of(),
            Map.of(), FREE, FREE, Map.of()));
        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of());

        JsonTree bare = PoseJson.of(folded);
        JsonTree carrying = PoseJson.of(folded, states);

        assertEquals(bare.toJson(), PoseJson.of(folded, Map.of()).toJson(),
            "a row placing no state spells exactly what it spelled before");
        assertEquals(List.of("bones", "states"), carrying.keys().toList(), "the member sits last");
        assertEquals(18f, carrying.findPath("states", "isSitting=true", "bones", "body", "y").orElseThrow()
            .findFloat("const").orElseThrow(), "the silhouette spells its bones the way the row does");
        assertEquals(bare.find("bones").orElseThrow().toJson(), carrying.find("bones").orElseThrow().toJson(),
            "the row's own bones are untouched by what is written after them");
    }

}
