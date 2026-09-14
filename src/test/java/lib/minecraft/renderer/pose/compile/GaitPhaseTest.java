package lib.minecraft.renderer.pose.compile;

import lib.minecraft.renderer.asset.model.EntityModelData;
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
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.walker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
     * A four-legged walker swinging every leg it carries, through the given cycle.
     */
    private static @NotNull BuiltStyle walked(@NotNull UnaryOperator<Gait> cycle) {
        return Poses.legged("amble")
            .gait(gait -> cycle.apply(gait.step(
                leg -> leg.timeline(track -> track.swing(Turn.PITCH, -35, 35).over(LENGTH)))))
            .build();
    }

    /**
     * The rotation keyframes one bone plays on the canonical biped.
     */
    private static @NotNull List<String> framesOf(@NotNull BuiltStyle style,
                                                  @NotNull String bone) {
        return framesOf(style, humanoid(), bone);
    }

    /**
     * The rotation keyframes one bone plays on the given mesh, as seconds-and-pitch pairs.
     */
    private static @NotNull List<String> framesOf(@NotNull BuiltStyle style,
                                                  @NotNull EntityModelData mesh,
                                                  @NotNull String bone) {
        PoseClip clip = PoseCompiler.compile(style, row(mesh, EntityPose.NONE))
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
    @DisplayName("an opposed far side starts behind the near one, which runs as written")
    void anOpposedFarSideStartsBehind() {
        BuiltStyle style = swung(gait -> gait.oppose(0.5));

        assertEquals(List.of("0.0 " + -SWEPT, "0.25 " + SWEPT, "0.5 " + -SWEPT),
            framesOf(style, "right_leg"),
            "the near side takes the shape exactly as written");
        assertEquals(List.of("0.0 " + SWEPT, "0.25 " + -SWEPT, "0.5 " + SWEPT),
            framesOf(style, "left_leg"),
            "and the far side half a cycle later - which the mirror sign rule cannot state, "
                + "because it keeps pitch and pitch is where this shape lives");
    }

    @Test
    @DisplayName("a whole cycle of opposition is no offset, so both sides run together")
    void aWholeCycleOfOppositionIsNoOffset() {
        BuiltStyle style = swung(gait -> gait.oppose(2.0));

        assertEquals(framesOf(style, "right_leg"), framesOf(style, "left_leg"),
            "the wrap takes a whole number of cycles to zero, so it states what zero states");
    }

    @Test
    @DisplayName("a row's offset and the side's sum on each leg they both reach")
    void theRowAndSideOffsetsSum() {
        BuiltStyle style = walked(gait -> gait.phase(Rank.HIND, 0.25).oppose(0.25));

        assertEquals(List.of("0.0 " + -SWEPT, "0.25 " + SWEPT, "0.5 " + -SWEPT),
            framesOf(style, walker(), "right_front_leg"),
            "the near leg of the unphased row takes neither term");
        assertEquals(List.of("0.0 0.0", "0.125 " + -SWEPT, "0.375 " + SWEPT, "0.5 0.0"),
            framesOf(style, walker(), "left_front_leg"),
            "the far leg of the unphased row takes the side's term alone");
        assertEquals(framesOf(style, walker(), "left_front_leg"),
            framesOf(style, walker(), "right_hind_leg"),
            "and the near leg of the phased row takes the row's term alone, which is the same "
                + "share of the same cycle");
        assertEquals(List.of("0.0 " + SWEPT, "0.25 " + -SWEPT, "0.5 " + SWEPT),
            framesOf(style, walker(), "left_hind_leg"),
            "while the far leg of the phased row takes both, summed before the shift is applied");
    }

    @Test
    @DisplayName("a side offset that cancels a row's leaves that leg running as written")
    void theTwoOffsetsCanCancel() {
        BuiltStyle style = walked(gait -> gait.phase(Rank.HIND, 0.5).oppose(0.5));

        assertEquals(framesOf(style, walker(), "right_front_leg"),
            framesOf(style, walker(), "left_hind_leg"),
            "a half and a half wrap to nothing, so the far hind leg runs with the near front "
                + "one - which is the diagonal, stated as two numbers");
        assertEquals(framesOf(style, walker(), "left_front_leg"),
            framesOf(style, walker(), "right_hind_leg"),
            "and the other two carry the other half");
        assertEquals(List.of("0.0 " + -SWEPT, "0.25 " + SWEPT, "0.5 " + -SWEPT),
            framesOf(style, walker(), "right_front_leg"),
            "with the leg neither term reaches running as the shape was written");
    }

    @Test
    @DisplayName("one opposed side reaches every shape a gait states, row by row")
    void everyKeyedShapeTakesTheSideOffset() {
        BuiltStyle style = Poses.legged("amble")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, -35, 35).over(LENGTH)))
                .step(Rank.HIND, leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, -35, 35).over(LENGTH)))
                .oppose(0.5))
            .build();

        assertEquals(List.of("0.0 " + SWEPT, "0.25 " + -SWEPT, "0.5 " + SWEPT),
            framesOf(style, walker(), "left_front_leg"),
            "the front row's own shape takes the offset on its far leg");
        assertEquals(List.of("0.0 " + SWEPT, "0.25 " + -SWEPT, "0.5 " + SWEPT),
            framesOf(style, walker(), "left_hind_leg"),
            "and the hind row's does too, from the same one opposed side");
        assertEquals(framesOf(style, walker(), "right_front_leg"),
            framesOf(style, walker(), "right_hind_leg"),
            "while both near legs run as their shapes were written");
    }

    @Test
    @DisplayName("a trot runs each leg with the one across the body from it")
    void aTrotPairsTheDiagonals() {
        BuiltStyle style = walked(gait -> gait.trot(0.5));
        List<String> leading = List.of("0.0 " + -SWEPT, "0.25 " + SWEPT, "0.5 " + -SWEPT);
        List<String> following = List.of("0.0 " + SWEPT, "0.25 " + -SWEPT, "0.5 " + SWEPT);

        assertEquals(leading, framesOf(style, walker(), "right_front_leg"),
            "the near front leg takes the shape as written, so the bound order is the lead");
        assertEquals(leading, framesOf(style, walker(), "left_hind_leg"),
            "and the far hind leg travels with it, which is the diagonal");
        assertEquals(following, framesOf(style, walker(), "left_front_leg"),
            "the other pair follows a share of the cycle behind");
        assertEquals(following, framesOf(style, walker(), "right_hind_leg"),
            "both of it, together");
    }

    @Test
    @DisplayName("a trot pairs the diagonals at any share, not only at a half")
    void aTrotHoldsAwayFromAHalf() {
        BuiltStyle style = walked(gait -> gait.trot(0.2));

        assertEquals(List.of("0.0 " + -SWEPT, "0.25 " + SWEPT, "0.5 " + -SWEPT),
            framesOf(style, walker(), "left_hind_leg"),
            "the far hind leg leads, carrying no offset at all - a couplet that summed a side "
                + "term and a row term would put it at twice the share instead, which is the "
                + "cycle only at a half");
        assertEquals(framesOf(style, walker(), "left_front_leg"),
            framesOf(style, walker(), "right_hind_leg"),
            "and the following pair carries the share once between them");
        assertNotEquals(framesOf(style, walker(), "right_front_leg"),
            framesOf(style, walker(), "left_front_leg"),
            "with the two pairs genuinely apart");
    }

    @Test
    @DisplayName("a row's offset nudges a trot rather than replacing it")
    void aPhaseNudgesATrot() {
        BuiltStyle style = walked(gait -> gait.trot(0.5).phase(Rank.HIND, 0.25));

        assertEquals(List.of("0.0 " + -SWEPT, "0.25 " + SWEPT, "0.5 " + -SWEPT),
            framesOf(style, walker(), "right_front_leg"),
            "the front row's near leg is reached by neither term");
        assertEquals(List.of("0.0 " + SWEPT, "0.25 " + -SWEPT, "0.5 " + SWEPT),
            framesOf(style, walker(), "left_front_leg"),
            "its far leg by the couplet alone");
        assertEquals(List.of("0.0 0.0", "0.125 " + -SWEPT, "0.375 " + SWEPT, "0.5 0.0"),
            framesOf(style, walker(), "left_hind_leg"),
            "the hind row's far leg by the row's offset alone, since the couplet leads it");
        assertEquals(List.of("0.0 0.0", "0.125 " + SWEPT, "0.375 " + -SWEPT, "0.5 0.0"),
            framesOf(style, walker(), "right_hind_leg"),
            "and its near leg by both, summed - three quarters of the cycle in");
    }

    @Test
    @DisplayName("a whole cycle of trot is no offset, so both pairs run together")
    void aWholeCycleOfTrotIsNoOffset() {
        BuiltStyle style = walked(gait -> gait.trot(1.0));

        assertEquals(framesOf(style, walker(), "right_front_leg"),
            framesOf(style, walker(), "left_front_leg"),
            "the wrap takes a whole number of cycles to zero, on the couplet as on every other "
                + "term that states one");
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
