package lib.minecraft.renderer.pose.compile;

import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Gait;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Turn;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a phased row's copy of the shape lands in the cycle.
 *
 * <p>A phase is real seconds on the clip clock, so what it owes is the frames: every frame inside
 * the cycle moved and wrapped, the pair at the ends read back off the unshifted shape, and the
 * duplicate a wrap can land dropped rather than refused.
 */
@DisplayName("a phased row starts its copy of the shape where the cycle says")
class GaitPhaseTest {

    /** One cycle of the fixture's clip, in seconds. */
    private static final double LENGTH = 0.5d;

    /** The excursion the shape sweeps, in radians, as the clip narrows it. */
    private static final float SWEPT = (float) Math.toRadians(35);

    /**
     * A one-row walker swinging its legs, its front row starting the given share of a cycle in.
     */
    private static @NotNull BuiltStyle swung(@NotNull UnaryOperator<Gait> phased) {
        return Poses.legged("amble")
            .gait(gait -> phased.apply(gait.step(Rank.FRONT,
                leg -> leg.timeline(track -> track.swing(Turn.PITCH, -35, 35).over(LENGTH)))))
            .build();
    }

    /**
     * The rotation keyframes one bone plays, as seconds-and-pitch pairs.
     */
    private static @NotNull List<String> framesOf(@NotNull BuiltStyle style,
                                                  @NotNull String bone) {
        PoseClip clip = PoseCompiler.compile(style, row(humanoid(), EntityPose.NONE))
            .pose().clips().getLast().clip();
        return clip.channels().stream()
            .filter(channel -> channel.bone().equals(bone))
            .filter(channel -> channel.target() == PoseClip.Target.ROTATION)
            .findFirst().orElseThrow(() -> new AssertionError("no rotation channel for " + bone))
            .keyframes().stream()
            .map(frame -> frame.timeSeconds() + " " + frame.x())
            .toList();
    }

    @Test
    @DisplayName("an unphased row opens at the shape's own first bound")
    void anUnphasedRowRunsAsWritten() {
        assertEquals(List.of("0.0 " + -SWEPT, "0.25 " + SWEPT, "0.5 " + -SWEPT),
            framesOf(swung(gait -> gait), "right_leg"),
            "the swing as the compiler already places it - rest, peak, rest");
    }

    @Test
    @DisplayName("half a cycle moves every frame and lands the wrap on a frame of its own")
    void aHalfCycleWrapsOntoItsOwnFrame() {
        assertEquals(List.of("0.0 " + SWEPT, "0.25 " + -SWEPT, "0.5 " + SWEPT),
            framesOf(swung(gait -> gait.phase(Rank.FRONT, 0.5)), "right_leg"),
            "three frames, not four - the wrap and the moved frame land together carrying one "
                + "value, and the later of the two is dropped rather than refused");
    }

    @Test
    @DisplayName("a share that is not a half reads the ends back off the unshifted shape")
    void anOffFrameShareInterpolatesTheEnds() {
        assertEquals(List.of("0.0 0.0", "0.125 " + -SWEPT, "0.375 " + SWEPT, "0.5 0.0"),
            framesOf(swung(gait -> gait.phase(Rank.FRONT, 0.25)), "right_leg"),
            "the wrap lands midway up the shape's own return, which is where the ends read zero");
    }

    @Test
    @DisplayName("two frames written close together survive an offset, as they survive without one")
    void closelyWrittenFramesBothSurvive() {
        BuiltStyle snapped = Poses.legged("snap")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.timeline(track -> track
                    .keyframe(0d, -35, 0, 0)
                    .keyframe(0.2d, 35, 0, 0)
                    .keyframe(0.2000005d, -35, 0, 0)
                    .keyframe(LENGTH, -35, 0, 0)
                    .over(LENGTH)))
                .phase(Rank.FRONT, 0.25))
            .build();

        assertEquals(5, framesOf(snapped, "right_leg").size(),
            () -> "an instantaneous snap is two frames the clip can tell apart, and an offset "
                + "moves them rather than merging them: " + framesOf(snapped, "right_leg"));
    }

    @Test
    @DisplayName("which of two unstateable offsets is named does not depend on the run")
    void theNamedRankIsTheFrontmost() {
        BuiltStyle swayed = Poses.legged("amble")
            .gait(gait -> gait
                .step(leg -> leg.sway(Turn.PITCH, -20, 20))
                .phase(Rank.HIND, 0.25)
                .phase(Rank.FRONT, 0.25))
            .build();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> PoseCompiler.compile(swayed, row(humanoid(), EntityPose.NONE)));
        assertTrue(refusal.getMessage().contains("FRONT"),
            () -> "the offsets are read front to back however they were written: "
                + refusal.getMessage());
    }

    @Test
    @DisplayName("the far side of a phased row is phased with it")
    void bothSidesOfAPhasedRowMove() {
        BuiltStyle style = swung(gait -> gait.phase(Rank.FRONT, 0.5));

        assertEquals(framesOf(style, "right_leg").size(), framesOf(style, "left_leg").size(),
            "a phase is a fact about the row, so it reaches the derived half too");
        assertEquals("0.0 " + SWEPT, framesOf(style, "left_leg").getFirst(),
            "and the mirror keeps pitch, so both legs of the row open together");
    }

}
