package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture engine records verbs as authored fragments - author units, author order, no
 * lowering.
 */
@DisplayName("the capture engine records verbs as authored fragments")
class PoseScriptCaptureTest {

    @Test
    @DisplayName("pitch/yaw/roll capture absolute channel writes in authored degrees")
    void absoluteRotationWrites() {
        PoseScript script = new PoseScript.Capture()
            .stance("head", PoseScript.AimAxis.FACING, s -> s.pitch(30).yaw(-35).roll(5))
            .script();

        PoseScript.Stance stance = script.stances().getFirst();
        assertEquals(Optional.of(new PoseScript.Limb.Named("head", PoseScript.AimAxis.FACING)), stance.limb());
        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, 30, true),
                new PoseScript.Write(PoseChannel.Y_ROT, -35, true),
                new PoseScript.Write(PoseChannel.Z_ROT, 5, true)),
            List.copyOf(stance.writes()), "degrees held as authored, in call order");
    }

    @Test
    @DisplayName("rotate stamps all three rotation channels, zero components included")
    void rotateStampsAllThree() {
        PoseScript script = new PoseScript.Capture()
            .stance("right_arm", PoseScript.AimAxis.DOWN, s -> s.rotate(-160, 0, 10))
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -160, true),
                new PoseScript.Write(PoseChannel.Y_ROT, 0, true),
                new PoseScript.Write(PoseChannel.Z_ROT, 10, true)),
            List.copyOf(script.stances().getFirst().writes()),
            "an authored zero is an absolute zero write, not an omission");
    }

    @Test
    @DisplayName("rotateBy adds to all three rotation channels in one stamp")
    void rotateByAddsToAllThree() {
        PoseScript script = new PoseScript.Capture()
            .stance("right_arm", PoseScript.AimAxis.DOWN, s -> s.rotateBy(-160, 0, 10))
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -160, false),
                new PoseScript.Write(PoseChannel.Y_ROT, 0, false),
                new PoseScript.Write(PoseChannel.Z_ROT, 10, false)),
            List.copyOf(script.stances().getFirst().writes()),
            "three additive writes, where rotate stamps three absolute ones");
    }

    @Test
    @DisplayName("offset captures additive position writes in model pixels")
    void offsetCapturesAdditivePosition() {
        PoseScript script = new PoseScript.Capture()
            .stance("body", PoseScript.AimAxis.DOWN, s -> s.offset(0, 7, -2))
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X, 0, false),
                new PoseScript.Write(PoseChannel.Y, 7, false),
                new PoseScript.Write(PoseChannel.Z, -2, false)),
            List.copyOf(script.stances().getFirst().writes()), "position is always additive");
    }

    @Test
    @DisplayName("the -By verbs capture additive rotation writes")
    void additiveRotationWrites() {
        PoseScript script = new PoseScript.Capture()
            .stance("torso", PoseScript.AimAxis.DOWN, s -> s.pitchBy(12).yawBy(-4).rollBy(3))
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, 12, false),
                new PoseScript.Write(PoseChannel.Y_ROT, -4, false),
                new PoseScript.Write(PoseChannel.Z_ROT, 3, false)),
            List.copyOf(script.stances().getFirst().writes()));
    }

    @Test
    @DisplayName("scale captures the uniform factor")
    void scaleCapturesTheFactor() {
        PoseScript script = new PoseScript.Capture()
            .stance("head", PoseScript.AimAxis.FACING, s -> s.scale(1.5))
            .script();

        assertEquals(List.of(new PoseScript.Scale(1.5)),
            List.copyOf(script.stances().getFirst().scales()));
    }

    @Test
    @DisplayName("aimAt captures the target point under the stance's axis stamp")
    void aimAtCarriesTheAxisStamp() {
        PoseScript script = new PoseScript.Capture()
            .stance("right_arm", PoseScript.AimAxis.DOWN, s -> s.aimAt(18, -30, -14))
            .stance("head", PoseScript.AimAxis.FACING, s -> s.aimAt(18, -30, -14))
            .script();

        PoseScript.Stance arm = script.stances().getFirst();
        PoseScript.Stance head = script.stances().getLast();
        assertEquals(List.of(new PoseScript.Aim(18, -30, -14)), List.copyOf(arm.aims()));
        assertEquals(PoseScript.AimAxis.DOWN, arm.limb().orElseThrow().axis(), "a hanging limb aims down its length");
        assertEquals(PoseScript.AimAxis.FACING, head.limb().orElseThrow().axis(), "a head aims its facing direction");
    }

    @Test
    @DisplayName("sway and spin capture their axis and bounds")
    void swayAndSpinCaptureTheirBounds() {
        PoseScript script = new PoseScript.Capture()
            .stance("tail", PoseScript.AimAxis.DOWN, s -> s.sway(Turn.YAW, -25, 25).spin(Turn.ROLL, 360))
            .script();

        PoseScript.Stance stance = script.stances().getFirst();
        assertEquals(List.of(new PoseScript.Sway(Turn.YAW, -25, 25)), List.copyOf(stance.sways()));
        assertEquals(List.of(new PoseScript.Spin(Turn.ROLL, 360)), List.copyOf(stance.spins()));
    }

    @Test
    @DisplayName("timeline verbs capture motion fragments in call order with their settings")
    void timelineCapturesMotionsAndSettings() {
        PoseScript script = new PoseScript.Capture()
            .stance("right_arm", PoseScript.AimAxis.DOWN, s -> s.timeline(t -> t
                .swing(Turn.ROLL, -20, 20)
                .bob(2)
                .keyframe(0.3, 10, 0, -5)
                .shift(0.6, 0, -1, 0)
                .over(0.6)
                .ease(Ease.SMOOTH)
                .once()))
            .script();

        PoseScript.Track track = script.stances().getFirst().tracks().getFirst();
        assertEquals(List.of(
                new PoseScript.Swing(Turn.ROLL, -20, 20),
                new PoseScript.Bob(2),
                new PoseScript.Keyframe(0.3, 10, 0, -5),
                new PoseScript.Shift(0.6, 0, -1, 0)),
            List.copyOf(track.motions()), "fragments in call order");
        assertEquals(OptionalDouble.of(0.6), track.overSeconds());
        assertEquals(Ease.SMOOTH, track.ease());
        assertFalse(track.looping(), "once() holds the last frame");
    }

    @Test
    @DisplayName("a bare timeline defaults to the strip window, LINEAR and looping")
    void timelineDefaults() {
        PoseScript script = new PoseScript.Capture()
            .stance("head", PoseScript.AimAxis.FACING, s -> s.timeline(t -> t.swing(Turn.PITCH, -5, 5)))
            .script();

        PoseScript.Track track = script.stances().getFirst().tracks().getFirst();
        assertEquals(OptionalDouble.empty(), track.overSeconds(), "unset length defaults to the strip window");
        assertEquals(Ease.LINEAR, track.ease());
        assertTrue(track.looping());
    }

    @Test
    @DisplayName("a container step captures the same verbs under no limb")
    void containerStepCarriesNoLimb() {
        PoseScript script = new PoseScript.Capture()
            .step(s -> s.pitch(-30))
            .script();

        PoseScript.Stance step = script.stances().getFirst();
        assertEquals(Optional.empty(), step.limb(), "a step addresses the seat, not a bone");
        assertEquals(List.of(new PoseScript.Write(PoseChannel.X_ROT, -30, true)), List.copyOf(step.writes()));
    }

    @Test
    @DisplayName("stances keep author order, later calls on one bone staying separate entries")
    void stancesKeepAuthorOrder() {
        PoseScript script = new PoseScript.Capture()
            .stance("right_arm", PoseScript.AimAxis.DOWN, s -> s.yaw(18))
            .step(s -> s.offset(0, 7, 0))
            .stance("right_arm", PoseScript.AimAxis.DOWN, s -> s.yaw(25))
            .script();

        assertEquals(3, script.stances().size());
        assertEquals("right_arm", script.stances().getFirst().limb().orElseThrow().bone());
        assertTrue(script.stances().get(1).limb().isEmpty(), "the step sits where it was authored");
        assertEquals(List.of(new PoseScript.Write(PoseChannel.Y_ROT, 25, true)),
            List.copyOf(script.stances().getLast().writes()),
            "the later stamp reads after the earlier one, so a fold can let it win");
    }

    @Test
    @DisplayName("script-level verbs capture stride, hover, period and raw expressions")
    void scriptLevelCaptures() {
        PoseExpr raw = new PoseExpr.Input("tentacleAngle");
        PoseScript script = new PoseScript.Capture()
            .keepStride()
            .hover(8, 2)
            .period(2.4)
            .raw("body", PoseChannel.X_ROT, raw)
            .script();

        assertTrue(script.keepStride());
        assertEquals(Optional.of(new PoseScript.Hover(8, 2)), script.hover());
        assertEquals(OptionalDouble.of(2.4), script.periodSeconds());
        PoseScript.Raw captured = script.raws().getFirst();
        assertEquals("body", captured.bone());
        assertEquals(PoseChannel.X_ROT, captured.channel());
        assertSame(raw, captured.expr(), "the expression graph rides by reference, never copied");
    }

    @Test
    @DisplayName("a later hover or period replaces the earlier one")
    void laterHoverAndPeriodReplace() {
        PoseScript script = new PoseScript.Capture()
            .hover(8, 2)
            .hover(4, 0)
            .period(2.4)
            .period(1.2)
            .script();

        assertEquals(Optional.of(new PoseScript.Hover(4, 0)), script.hover());
        assertEquals(OptionalDouble.of(1.2), script.periodSeconds());
    }

    @Test
    @DisplayName("an empty capture snapshots an empty script")
    void emptyCapture() {
        PoseScript script = new PoseScript.Capture().script();

        assertTrue(script.stances().isEmpty());
        assertTrue(script.raws().isEmpty());
        assertFalse(script.keepStride());
        assertEquals(Optional.empty(), script.hover());
        assertEquals(OptionalDouble.empty(), script.periodSeconds());
    }

    @Test
    @DisplayName("a snapshot is unaffected by captures taken after it")
    void snapshotIsStable() {
        PoseScript.Capture capture = new PoseScript.Capture()
            .stance("head", PoseScript.AimAxis.FACING, s -> s.pitch(30));
        PoseScript first = capture.script();
        capture.stance("body", PoseScript.AimAxis.DOWN, s -> s.pitch(10));

        assertEquals(1, first.stances().size(), "the earlier snapshot keeps its own fragment list");
        assertEquals(2, capture.script().stances().size());
    }

}
