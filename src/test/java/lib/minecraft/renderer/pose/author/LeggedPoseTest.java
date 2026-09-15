package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.pose.PoseChannel;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legged tier's verb table - the walker roster's bone mappings and the paired-leg stamps.
 */
@DisplayName("the legged builder maps the walker roster and stamps leg pairs")
class LeggedPoseTest {

    @Test
    @DisplayName("selectors land on the walker bones with their aim-axis stamps")
    void selectorsMapTheWalkerRoster() {
        PoseScript script = Poses.legged("map")
            .head(h -> h.pitch(-15))
            .body(b -> b.pitch(-40))
            .tail(t -> t.yaw(20))
            .build()
            .script();

        assertEquals(PoseScript.AimAxis.FACING, stanceOf(script, "head").limb().orElseThrow().axis());
        assertEquals(PoseScript.AimAxis.DOWN, stanceOf(script, "body").limb().orElseThrow().axis());
        assertEquals(List.of(new PoseScript.Write(PoseChannel.Y_ROT, 20, true)),
            List.copyOf(stanceOf(script, "tail").of(PoseScript.Write.class)));
    }

    @Test
    @DisplayName("each rank and side pair captures the address it was written with")
    void ranksAndSidesCaptureTheirAddress() {
        PoseScript script = Poses.legged("splay")
            .leg(Rank.FRONT, Side.LEFT, l -> l.pitch(1))
            .leg(Rank.FRONT, Side.RIGHT, l -> l.pitch(2))
            .leg(Rank.HIND, Side.LEFT, l -> l.pitch(3))
            .leg(Rank.HIND, Side.RIGHT, l -> l.pitch(4))
            .build()
            .script();

        assertEquals(List.of(
                new LimbSelector.Legs(Optional.of(Rank.FRONT), Optional.of(Side.LEFT)),
                new LimbSelector.Legs(Optional.of(Rank.FRONT), Optional.of(Side.RIGHT)),
                new LimbSelector.Legs(Optional.of(Rank.HIND), Optional.of(Side.LEFT)),
                new LimbSelector.Legs(Optional.of(Rank.HIND), Optional.of(Side.RIGHT))),
            selectorsOf(script), "one address per call, in author order");
        assertEquals(List.of(1d, 2d, 3d, 4d),
            script.stances().stream().map(stance -> stance.of(PoseScript.Write.class).getFirst().value()).toList(),
            "each keeping the value it was written with");
    }

    @Test
    @DisplayName("a rank naming a row between the ends is captured, and the mesh decides whether it lands")
    void aMiddleRankIsCapturedForTheMeshToAnswer() {
        PoseScript script = Poses.legged("amble")
            .legs(Rank.SECOND, l -> l.pitch(1))
            .build()
            .script();

        assertEquals(List.of(
                new LimbSelector.Legs(Optional.of(Rank.SECOND), Optional.of(Side.RIGHT), Reach.ROOT,
                    LimbSelector.Stamp.NEAR),
                new LimbSelector.Legs(Optional.of(Rank.SECOND), Optional.of(Side.LEFT),
                    Reach.ROOT, LimbSelector.Stamp.FAR)),
            selectorsOf(script),
            "the tier states the row rather than resolving it, so a three-row mesh can answer");
    }

    @Test
    @DisplayName("the front row stamps the right as authored and the left under x, -y, -z")
    void frontRowStampsTheMirrorPair() {
        PoseScript script = Poses.legged("beg")
            .legs(Rank.FRONT, l -> l.pitch(-35).yawBy(5))
            .build()
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -35, true),
                new PoseScript.Write(PoseChannel.Y_ROT, 5, false)),
            List.copyOf(script.stances().getFirst().of(PoseScript.Write.class)), "the near side as authored");
        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -35, true),
                new PoseScript.Write(PoseChannel.Y_ROT, -5, false)),
            List.copyOf(script.stances().getLast().of(PoseScript.Write.class)), "the far side under the sign rule");
        assertEquals(List.of(
                new LimbSelector.Legs(Optional.of(Rank.FRONT), Optional.of(Side.RIGHT), Reach.ROOT,
                    LimbSelector.Stamp.NEAR),
                new LimbSelector.Legs(Optional.of(Rank.FRONT), Optional.of(Side.LEFT),
                    Reach.ROOT, LimbSelector.Stamp.FAR)),
            selectorsOf(script), "the far side is the derived half and reports no miss of its own");
    }

    @Test
    @DisplayName("the hind row stamps its pair the same way")
    void hindRowStampsTheMirrorPair() {
        PoseScript script = Poses.legged("kick")
            .legs(Rank.HIND, l -> l.roll(10))
            .build()
            .script();

        assertEquals(10, script.stances().getFirst().of(PoseScript.Write.class).getFirst().value());
        assertEquals(-10, script.stances().getLast().of(PoseScript.Write.class).getFirst().value());
    }

    /**
     * The address each captured stance was written with, in author order.
     */
    private static @NotNull List<LimbSelector> selectorsOf(@NotNull PoseScript script) {
        return script.stances().stream()
            .map(stance -> stance.limb().orElseThrow())
            .map(PoseScript.Limb.Selected.class::cast)
            .map(PoseScript.Limb.Selected::selector)
            .toList();
    }

    @Test
    @DisplayName("a whole begging chain captures seven stances in author order")
    void begChainCapturesInOrder() {
        PoseScript script = Poses.legged("beg")
            .body(b -> b.pitch(-40))
            .legs(Rank.HIND, l -> l.pitch(-70))
            .legs(Rank.FRONT, l -> l.pitch(-35))
            .head(h -> h.pitch(-15)
                .timeline(t -> t.swing(Turn.ROLL, -8, 8).over(1.2).ease(Ease.SMOOTH)))
            .tail(t -> t.sway(Turn.YAW, -25, 25))
            .build()
            .script();

        assertEquals(7, script.stances().size());
        assertEquals(Optional.of("body"),
            script.stances().getFirst().limb().orElseThrow().named());
        assertEquals(Optional.of("tail"),
            script.stances().getLast().limb().orElseThrow().named());
        assertEquals(1, stanceOf(script, "head").of(PoseScript.Track.class).size());
        assertEquals(List.of(new PoseScript.Sway(Turn.YAW, -25, 25)),
            List.copyOf(stanceOf(script, "tail").of(PoseScript.Sway.class)));
    }

    /**
     * The last stance addressing one bone.
     */
    private static @NotNull PoseScript.Stance stanceOf(@NotNull PoseScript script, @NotNull String bone) {
        return script.stances().stream()
            .filter(stance -> stance.limb().flatMap(PoseScript.Limb::named)
                .filter(bone::equals).isPresent())
            .reduce((first, second) -> second)
            .orElseThrow();
    }

}
