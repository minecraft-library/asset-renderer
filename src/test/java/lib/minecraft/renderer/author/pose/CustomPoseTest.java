package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The custom tier's verb table - raw bone names, container steps and the raw expression hatch.
 */
@DisplayName("the custom builder addresses raw bones and splices raw expressions")
class CustomPoseTest {

    @Test
    @DisplayName("bone stances any mesh name under the limb-length aim convention")
    void boneStancesAnyName() {
        PoseScript script = Poses.custom("flutter")
            .bone("left_wing", w -> w.yaw(-50))
            .bone("real_head", h -> h.pitchBy(-8))
            .build()
            .script();

        PoseScript.Limb wing = script.stances().getFirst().limb().orElseThrow();
        assertEquals("left_wing", wing.bone());
        assertEquals(PoseScript.AimAxis.DOWN, wing.axis(), "every custom bone aims down its length");
        assertEquals("real_head", script.stances().getLast().limb().orElseThrow().bone());
    }

    @Test
    @DisplayName("container captures an ordered step with no limb")
    void containerCapturesASteplessStance() {
        PoseScript script = Poses.custom("tilt")
            .container(c -> c.pitch(-30))
            .build()
            .script();

        PoseScript.Stance step = script.stances().getFirst();
        assertEquals(Optional.empty(), step.limb());
        assertEquals(List.of(new PoseScript.Write(PoseChannel.X_ROT, -30, true)),
            List.copyOf(step.writes()));
    }

    @Test
    @DisplayName("expr rides the graph by reference - never copied, never walked")
    void exprRidesByReference() {
        PoseExpr shared = new PoseExpr.Input("tentacleAngle");
        PoseExpr graph = new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(shared, shared));
        PoseScript script = Poses.custom("wobble")
            .expr("body", PoseChannel.X_ROT, graph)
            .build()
            .script();

        PoseScript.Raw raw = script.raws().getFirst();
        assertEquals("body", raw.bone());
        assertEquals(PoseChannel.X_ROT, raw.channel());
        assertSame(graph, raw.expr(), "a shared-subexpression graph survives capture by identity");
    }

    @Test
    @DisplayName("the shared tail verbs capture into the script")
    void sharedTailVerbsCapture() {
        BuiltStyle style = Poses.custom("drift")
            .bone("head", h -> h.pitchBy(-8))
            .keepStride()
            .hover(4, 1)
            .period(2.4)
            .build();

        assertTrue(style.script().keepStride());
        assertEquals(Optional.of(new PoseScript.Hover(4, 1)), style.script().hover());
        assertEquals(OptionalDouble.of(2.4), style.script().periodSeconds());
    }

    @Test
    @DisplayName("a flutter chain captures wings, head and hover in author order")
    void flutterChainCaptures() {
        PoseScript script = Poses.custom("flutter")
            .bone("left_wing", w -> w.timeline(t -> t.swing(Turn.YAW, -50, 10).over(0.3)))
            .bone("right_wing", w -> w.timeline(t -> t.swing(Turn.YAW, 50, -10).over(0.3)))
            .bone("head", h -> h.pitchBy(-8))
            .hover(4, 1)
            .build()
            .script();

        assertEquals(3, script.stances().size());
        assertEquals(List.of(new PoseScript.Swing(Turn.YAW, -50, 10)),
            List.copyOf(script.stances().getFirst().tracks().getFirst().motions()));
        assertEquals(List.of(new PoseScript.Swing(Turn.YAW, 50, -10)),
            List.copyOf(script.stances().get(1).tracks().getFirst().motions()),
            "antiphase wings are two authored timelines, not a derived mirror");
        assertEquals(Optional.of(new PoseScript.Hover(4, 1)), script.hover());
    }

}
