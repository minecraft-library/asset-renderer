package lib.minecraft.renderer.tooling.animation;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the frame a pose is folded against - which subjects it merges, which it keeps apart, and that
 * two subjects it merges really do fold to one residual.
 */
@DisplayName("the frame a pose is folded against")
class PoseFoldTest {

    private static final @NotNull Map<String, String> PILLAGER = Map.of("armPose", "NEUTRAL");

    private static final @NotNull Map<String, String> EVOKER = Map.of("armPose", "CROSSED");

    private static final @NotNull Map<String, String> MODEL_RESTS_NEUTRAL = Map.of("armPose", "NEUTRAL");

    /** One bone channel written to whatever the given expression computes. */
    private static PoseProgram posing(PoseExpr written) {
        Map<PoseChannel, PoseExpr> channels = new LinkedHashMap<>();
        channels.put(PoseChannel.X_ROT, written);
        return new PoseProgram("Model", List.of(), Map.of("head", channels), List.of());
    }

    /** The arm an enum member picks, which is the shape every switch over a render state decomposes to. */
    private static PoseExpr onArmPose() {
        return new PoseExpr.Select(new PosePredicate.EnumEq("armPose", "CROSSED"),
            PoseExpr.Const.of(1f), PoseExpr.Const.of(2f));
    }

    @Test
    @DisplayName("a member the pose never names is no part of its frame")
    void unnamedMembersDoNotSplit() {
        // The whole reason the frame is not the resting map: eleven of the thirteen humanoids name no
        // constant and the two that do name one HumanoidModel never reads, so comparing the maps
        // refuses a fold that has nothing to choose between.
        PoseProgram program = posing(new PoseExpr.Input("ageInTicks"));
        assertEquals(PoseFold.frameOf(program, EVOKER, Map.of()),
            PoseFold.frameOf(program, Map.of(), Map.of()),
            "a member no expression names must not reach the frame");
    }

    @Test
    @DisplayName("a subject silent on a named member stands where its model rests")
    void silenceTakesTheModelDefault() {
        PoseProgram program = posing(onArmPose());
        assertEquals(PoseFold.frameOf(program, PILLAGER, MODEL_RESTS_NEUTRAL),
            PoseFold.frameOf(program, Map.of(), MODEL_RESTS_NEUTRAL),
            "naming the constant the model already rests at must not be a second frame");
    }

    @Test
    @DisplayName("subjects disagreeing about a member the pose reads are two frames")
    void realDisagreementSplits() {
        PoseProgram program = posing(onArmPose());
        assertNotEquals(PoseFold.frameOf(program, EVOKER, MODEL_RESTS_NEUTRAL),
            PoseFold.frameOf(program, PILLAGER, MODEL_RESTS_NEUTRAL),
            "an illager with its arms crossed and one with them down stand in two frames");
    }

    @Test
    @DisplayName("a member named only in a clip argument still reaches the frame")
    void clipArgumentsAreRead() {
        // An argument is folded like any other expression, so a frame blind to it would merge two
        // subjects the pose plays a clip at two amplitudes for.
        PoseProgram program = new PoseProgram("Model", List.of(), Map.of(),
            List.of(new PoseClipSite("Model#clip", PoseClipSite.Gate.STATIC, List.of(onArmPose()))));
        assertNotEquals(PoseFold.frameOf(program, EVOKER, MODEL_RESTS_NEUTRAL),
            PoseFold.frameOf(program, PILLAGER, MODEL_RESTS_NEUTRAL),
            "a clip argument names members like anything else does");
    }

    @Test
    @DisplayName("two subjects sharing a frame fold to one residual")
    void oneFrameIsOneResidual() {
        // What the merge is FOR. Whichever of the two the emitter picks as the representative, the
        // row it writes is the same one - which is what makes picking one of them safe at all.
        PoseProgram program = posing(PoseExpr.Op.of(PoseOperator.MUL,
            onArmPose(), new PoseExpr.Input("ageInTicks")));
        assertEquals(PoseFold.frameOf(program, PILLAGER, MODEL_RESTS_NEUTRAL),
            PoseFold.frameOf(program, Map.of(), MODEL_RESTS_NEUTRAL), "the two are one frame");
        assertEquals(
            PoseFold.fold(program, PILLAGER, MODEL_RESTS_NEUTRAL, Map.of(), Map.of(), Set.of("ageInTicks")),
            PoseFold.fold(program, Map.of(), MODEL_RESTS_NEUTRAL, Map.of(), Map.of(), Set.of("ageInTicks")),
            "one frame must fold to one residual whichever subject stands for it");
    }

    @Test
    @DisplayName("a member nothing answers is left out rather than answered")
    void unanswerableMembersAreAbsent() {
        PoseProgram program = posing(onArmPose());
        assertTrue(PoseFold.frameOf(program, Map.of(), Map.of()).isEmpty(),
            "a member neither the subject nor the model rests at answers nothing");
    }

}
