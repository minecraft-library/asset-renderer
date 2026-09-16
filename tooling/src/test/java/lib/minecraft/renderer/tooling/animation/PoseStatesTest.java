package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.pose.PoseOperator;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what a state silhouette carries - the branch a resting subject does not take, folded at
 * rest with one answer flipped, keeping only what it places away from the resting row.
 */
@DisplayName("the resting silhouette of each state branch")
class PoseStatesTest {

    /** The one figure the tick drives in these fixtures. */
    private static final @NotNull Set<String> FREE = Set.of("walkAnimationPos", "walkAnimationSpeed");

    /** The branch a body takes when a boolean the render state holds is set. */
    private static @NotNull PosePredicate sitting() {
        return new PosePredicate.Compare(PosePredicate.Comparison.NE,
            new PoseExpr.Input("isSitting"), PoseValue.constant(0));
    }

    /** The stride term every walker carries - zero before anything has happened to the subject. */
    private static @NotNull PoseExpr stride() {
        return PoseValue.operation(PoseOperator.MUL,
            PoseValue.operation(PoseOperator.MTH_COS,
                PoseValue.operation(PoseOperator.F2D,
                    PoseValue.operation(PoseOperator.MUL, new PoseExpr.Input("walkAnimationPos"), PoseValue.constant(0.6662f)))),
            new PoseExpr.Input("walkAnimationSpeed"));
    }

    private static @NotNull Map<PoseSink, PoseExpr> channels(Object... pairs) {
        Map<PoseSink, PoseExpr> out = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2)
            out.put((PoseSink) pairs[at], (PoseExpr) pairs[at + 1]);
        return out;
    }

    private static @NotNull PoseProgram program(@NotNull Map<String, Map<PoseSink, PoseExpr>> bones) {
        return new PoseProgram("Model", List.of(), bones, List.of());
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
                PoseSink.Y, new PoseExpr.Select(sitting, PoseValue.constant(18f), new PoseExpr.BoneRead("body", PoseSink.Y)),
                PoseSink.X_ROT, new PoseExpr.Select(sitting, PoseValue.constant(0.7853982f), PoseValue.constant(1.5707964f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of());

        assertEquals(Set.of("isSitting=true"), states.keySet(), "the subject rests unsat, so the state is sitting");
        Map<PoseSink, PoseExpr> body = states.get("isSitting=true").bones().get("body");
        assertEquals(PoseValue.constant(18f), body.get(PoseSink.Y), "the placed pivot, as a literal");
        assertEquals(PoseValue.constant(0.7853982f), body.get(PoseSink.X_ROT), "the lowered body's angle");
    }

    @Test
    @DisplayName("a channel the state leaves where the resting row leaves it is not written")
    void unmovedChannelsAreOmitted() {
        PosePredicate sitting = sitting();
        PoseProgram program = program(Map.of(
            "head", channels(PoseSink.X_ROT, PoseValue.constant(0f)),
            "tail", channels(
                PoseSink.Y, new PoseExpr.Select(sitting, PoseValue.constant(5f), PoseValue.constant(5f)),
                PoseSink.Z, new PoseExpr.Select(sitting, PoseValue.constant(6f), new PoseExpr.BoneRead("tail", PoseSink.Z)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of());

        Map<String, Map<PoseSink, PoseExpr>> placed = states.get("isSitting=true").bones();
        assertFalse(placed.containsKey("head"), "a bone the state never moves is absent");
        assertEquals(Set.of(PoseSink.Z), placed.get("tail").keySet(),
            "a channel both arms write the same is absent, the one they disagree about is kept");
    }

    @Test
    @DisplayName("a placement relative to the authored pivot keeps the read of that pivot")
    void relativePlacementKeepsTheBoneRead() {
        PoseExpr read = new PoseExpr.BoneRead("tail1", PoseSink.Y);
        PoseProgram program = program(Map.of(
            "tail1", channels(PoseSink.Y, new PoseExpr.Select(sitting(),
                PoseValue.operation(PoseOperator.ADD, read, PoseValue.constant(8f)), read))));

        PoseExpr placed = states(program, Map.of(), Map.of()).get("isSitting=true").bones().get("tail1").get(PoseSink.Y);

        PoseExpr.Op shifted = assertInstanceOf(PoseExpr.Op.class, placed);
        assertEquals(PoseOperator.ADD, shifted.operator());
        assertEquals(read, shifted.operands().getFirst(), "the mesh's own value stays a read, never a number");
        assertEquals(PoseValue.constant(8f), shifted.operands().getLast());
    }

    @Test
    @DisplayName("a shared term is compared once however many paths reach it")
    void sharedTermsCompareOncePerNode() {
        // Forty doublings of one read stand for a million million paths; a comparison that recursed
        // per path would not return, and one that answers each pair of nodes once returns at once.
        assertTrue(PoseStates.sameShape(doubled(40), doubled(40)), "one shape, however it is shared");
        assertFalse(PoseStates.sameShape(doubled(40),
                PoseValue.operation(PoseOperator.ADD, doubled(39), PoseValue.constant(1f))),
            "a leaf that differs is found down the one path it lives on");
        assertFalse(PoseStates.sameShape(doubled(3), null));
    }

    /** One read of the mesh added to itself {@code depth} times over, each sum shared by the next. */
    private static @NotNull PoseExpr doubled(int depth) {
        PoseExpr term = new PoseExpr.BoneRead("body", PoseSink.Y);
        for (int level = 0; level < depth; level++)
            term = PoseValue.operation(PoseOperator.ADD, term, term);
        return term;
    }

    @Test
    @DisplayName("a stride term folds to what it rests at, so a tuck over the stride is the tuck alone")
    void strideFoldsAtRest() {
        PoseExpr walk = stride();
        PoseProgram program = program(Map.of(
            "right_arm", channels(PoseSink.X_ROT, new PoseExpr.Select(sitting(),
                PoseValue.operation(PoseOperator.ADD, walk, PoseValue.constant(0.4f)), walk))));

        PoseExpr placed = states(program, Map.of(), Map.of()).get("isSitting=true").bones().get("right_arm").get(PoseSink.X_ROT);

        assertEquals(PoseValue.constant(0.4f), placed, "the stride rests at zero and the literal survives");
    }

    @Test
    @DisplayName("a figure the tick drives is no state, and neither is a comparison by order")
    void drivenAndOrderedComparisonsAreNoStates() {
        PosePredicate moving = new PosePredicate.Compare(PosePredicate.Comparison.NE,
            new PoseExpr.Input("isMoving"), PoseValue.constant(0));
        PosePredicate fast = new PosePredicate.Compare(PosePredicate.Comparison.GT,
            new PoseExpr.Input("walkAnimationSpeed"), PoseValue.constant(0.2f));
        PoseProgram program = program(Map.of(
            "body", channels(
                PoseSink.Y, new PoseExpr.Select(moving, PoseValue.constant(3f), PoseValue.constant(0f)),
                PoseSink.Z, new PoseExpr.Select(fast, PoseValue.constant(3f), PoseValue.constant(0f)))));

        Map<String, PoseStates.Silhouette> states = PoseStates.of(program, Map.of(), Map.of(), Map.of(),
            Map.of(), Set.of("isMoving", "walkAnimationSpeed"), Map.of());

        assertTrue(states.isEmpty(), "a driven boolean stays symbolic in the row, a threshold is a number");
    }

    @Test
    @DisplayName("each enum constant the body tests is a state, except the one it rests holding")
    void enumConstantsAreStates() {
        PoseProgram program = program(Map.of(
            "head", channels(PoseSink.X_ROT, new PoseExpr.Select(new PosePredicate.EnumEq("pose", "SITTING"),
                PoseValue.constant(1f),
                new PoseExpr.Select(new PosePredicate.EnumEq("pose", "FLYING"), PoseValue.constant(2f), PoseValue.constant(0f))))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of("pose", "STANDING"));

        assertEquals(Set.of("pose=FLYING", "pose=SITTING"), states.keySet());
        assertEquals(PoseValue.constant(1f), states.get("pose=SITTING").bones().get("head").get(PoseSink.X_ROT));
        assertEquals(PoseValue.constant(2f), states.get("pose=FLYING").bones().get("head").get(PoseSink.X_ROT));

        assertEquals(Set.of("pose=FLYING"), states(program, Map.of("pose", "SITTING"), Map.of()).keySet(),
            "a subject resting in a constant has that constant as its row, not as a state");
    }

    @Test
    @DisplayName("a boolean resting set flips to unset")
    void restingTrueFlipsToFalse() {
        PosePredicate swimming = new PosePredicate.Compare(PosePredicate.Comparison.EQ,
            PoseValue.constant(0), new PoseExpr.Input("isInWater"));
        PoseProgram program = program(Map.of(
            "body", channels(PoseSink.Z_ROT, new PoseExpr.Select(swimming, PoseValue.constant(1.5f), PoseValue.constant(0f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of("isInWater", "true"), Map.of());

        assertEquals(Set.of("isInWater=false"), states.keySet(), "the fish rests swimming, so the state is on land");
        assertEquals(PoseValue.constant(1.5f), states.get("isInWater=false").bones().get("body").get(PoseSink.Z_ROT));
    }

    @Test
    @DisplayName("a unit figure resting at zero is moved to one, and one resting elsewhere is no state")
    void unitFigureRestingAtZeroFoldsAtOne() {
        PoseProgram program = program(Map.of(
            "head_parts", channels(
                PoseSink.X_ROT, PoseValue.operation(PoseOperator.MUL, new PoseExpr.Input("standAnimation"), PoseValue.constant(0.5f)),
                PoseSink.Y, PoseValue.operation(PoseOperator.MUL, new PoseExpr.Input("scale"), PoseValue.constant(3f)))));

        Map<String, PoseStates.Silhouette> states = PoseStates.of(program, Map.of(), Map.of(), Map.of(),
            Map.of("scale", 1f), FREE, Map.of());

        assertEquals(Set.of("standAnimation=1"), states.keySet(),
            "the completed stand is a state; a figure resting at one is what the row already holds");
        Map<PoseSink, PoseExpr> placed = states.get("standAnimation=1").bones().get("head_parts");
        assertEquals(Set.of(PoseSink.X_ROT), placed.keySet(), "only the channel the figure moves");
        assertEquals(0.5d, assertInstanceOf(PoseExpr.Const.class, placed.get(PoseSink.X_ROT)).value(), 1e-6);
    }

    @Test
    @DisplayName("a figure the frame spells as a boolean flips as one, however the body reads it")
    void frameSpelledBooleanFlipsAsABoolean() {
        PoseProgram program = program(Map.of(
            "body", channels(PoseSink.Y, PoseValue.operation(PoseOperator.MUL, new PoseExpr.Input("isBaby"), PoseValue.constant(2f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of("isBaby", "false"), Map.of());

        assertEquals(Set.of("isBaby=true"), states.keySet(), "flipped through the frame, never moved to one");
    }

    @Test
    @DisplayName("a channel resting at no number in a state places nothing")
    void nonFiniteRestIsNoPlacement() {
        PosePredicate sitting = sitting();
        PoseProgram program = program(Map.of(
            "right_arm", channels(
                PoseSink.X_ROT, new PoseExpr.Select(sitting,
                    PoseValue.operation(PoseOperator.DIV, new PoseExpr.Input("useTicks"), new PoseExpr.Input("useDuration")),
                    PoseValue.constant(0f)),
                PoseSink.Y_ROT, new PoseExpr.Select(sitting, PoseValue.constant(0.3f), PoseValue.constant(0f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of());

        assertEquals(Set.of(PoseSink.Y_ROT), states.get("isSitting=true").bones().get("right_arm").keySet(),
            "a zero over zero rests at no number and is left out");
    }

    @Test
    @DisplayName("scale and flag channels are never part of a silhouette")
    void scaleAndFlagChannelsAreOmitted() {
        PosePredicate sitting = sitting();
        PoseProgram program = program(Map.of(
            "body", channels(
                PoseSink.X_SCALE, new PoseExpr.Select(sitting, PoseValue.constant(2f), PoseValue.constant(1f)),
                PoseSink.VISIBLE, new PoseExpr.Select(sitting, PoseValue.constant(0), PoseValue.constant(1)),
                PoseSink.Y, new PoseExpr.Select(sitting, PoseValue.constant(18f), PoseValue.constant(14f)))));

        Map<String, PoseStates.Silhouette> states = states(program, Map.of(), Map.of());

        assertEquals(Set.of(PoseSink.Y), states.get("isSitting=true").bones().get("body").keySet());
    }

    @Test
    @DisplayName("the writer spells the states after everything the runtime reads, and none where there are none")
    void writerSpellsStatesLast() {
        PosePredicate sitting = sitting();
        PoseProgram program = program(Map.of(
            "body", channels(
                PoseSink.Y, new PoseExpr.Select(sitting, PoseValue.constant(18f), new PoseExpr.BoneRead("body", PoseSink.Y)))));
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
