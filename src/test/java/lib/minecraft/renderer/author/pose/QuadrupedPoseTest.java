package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.asset.pose.PoseChannel;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The quadruped tier's verb table - the walker roster's bone mappings and the paired-leg
 * stamps.
 */
@DisplayName("the quadruped builder maps the walker roster and stamps leg pairs")
class QuadrupedPoseTest {

    @Test
    @DisplayName("selectors land on the walker bones with their aim-axis stamps")
    void selectorsMapTheWalkerRoster() {
        PoseScript script = Poses.quadruped("map")
            .head(h -> h.pitch(-15))
            .body(b -> b.pitch(-40))
            .tail(t -> t.yaw(20))
            .build()
            .script();

        assertEquals(PoseScript.AimAxis.FACING, stanceOf(script, "head").limb().orElseThrow().axis());
        assertEquals(PoseScript.AimAxis.DOWN, stanceOf(script, "body").limb().orElseThrow().axis());
        assertEquals(List.of(new PoseScript.Write(PoseChannel.Y_ROT, 20, true)),
            List.copyOf(stanceOf(script, "tail").writes()));
    }

    @Test
    @DisplayName("each corner selector lands on its own leg bone")
    void cornersMapTheirLegs() {
        PoseScript script = Poses.quadruped("splay")
            .leg(Corner.FRONT_LEFT, l -> l.pitch(1))
            .leg(Corner.FRONT_RIGHT, l -> l.pitch(2))
            .leg(Corner.HIND_LEFT, l -> l.pitch(3))
            .leg(Corner.HIND_RIGHT, l -> l.pitch(4))
            .build()
            .script();

        assertEquals(List.of(new PoseScript.Write(PoseChannel.X_ROT, 1, true)),
            List.copyOf(stanceOf(script, "left_front_leg").writes()));
        assertEquals(List.of(new PoseScript.Write(PoseChannel.X_ROT, 2, true)),
            List.copyOf(stanceOf(script, "right_front_leg").writes()));
        assertEquals(List.of(new PoseScript.Write(PoseChannel.X_ROT, 3, true)),
            List.copyOf(stanceOf(script, "left_hind_leg").writes()));
        assertEquals(List.of(new PoseScript.Write(PoseChannel.X_ROT, 4, true)),
            List.copyOf(stanceOf(script, "right_hind_leg").writes()));
    }

    @Test
    @DisplayName("frontLegs stamps the right as authored and the left under x, -y, -z")
    void frontLegsStampTheMirrorPair() {
        PoseScript script = Poses.quadruped("beg")
            .frontLegs(l -> l.pitch(-35).yawBy(5))
            .build()
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -35, true),
                new PoseScript.Write(PoseChannel.Y_ROT, 5, false)),
            List.copyOf(stanceOf(script, "right_front_leg").writes()));
        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -35, true),
                new PoseScript.Write(PoseChannel.Y_ROT, -5, false)),
            List.copyOf(stanceOf(script, "left_front_leg").writes()));
    }

    @Test
    @DisplayName("hindLegs stamps its pair the same way")
    void hindLegsStampTheMirrorPair() {
        PoseScript script = Poses.quadruped("kick")
            .hindLegs(l -> l.roll(10))
            .build()
            .script();

        assertEquals(10, stanceOf(script, "right_hind_leg").writes().getFirst().value());
        assertEquals(-10, stanceOf(script, "left_hind_leg").writes().getFirst().value());
    }

    @Test
    @DisplayName("a whole begging chain captures seven stances in author order")
    void begChainCapturesInOrder() {
        PoseScript script = Poses.quadruped("beg")
            .body(b -> b.pitch(-40))
            .hindLegs(l -> l.pitch(-70))
            .frontLegs(l -> l.pitch(-35))
            .head(h -> h.pitch(-15)
                .timeline(t -> t.swing(Turn.ROLL, -8, 8).over(1.2).ease(Ease.SMOOTH)))
            .tail(t -> t.sway(Turn.YAW, -25, 25))
            .build()
            .script();

        assertEquals(7, script.stances().size());
        assertEquals("body", script.stances().getFirst().limb().orElseThrow().bone());
        assertEquals("tail", script.stances().getLast().limb().orElseThrow().bone());
        assertEquals(1, stanceOf(script, "head").tracks().size());
        assertEquals(List.of(new PoseScript.Sway(Turn.YAW, -25, 25)),
            List.copyOf(stanceOf(script, "tail").sways()));
    }

    /**
     * The last stance addressing one bone.
     */
    private static @NotNull PoseScript.Stance stanceOf(@NotNull PoseScript script, @NotNull String bone) {
        return script.stances().stream()
            .filter(stance -> stance.limb().map(limb -> limb.bone().equals(bone)).orElse(false))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

}
