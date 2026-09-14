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

/**
 * What one leg's copy of a gait's shape looks like, as against where in the cycle it starts.
 *
 * <p>A shape verb spends the cycle differently rather than lengthening it, so what each owes is
 * the frames: the times a plateau moves the peak to, and the bounds left where the author wrote
 * them. These read the emitted clip back, because a shape verb that emitted nothing would pass
 * every assertion about what it did not change.
 */
@DisplayName("a gait's shape verbs spend the cycle without lengthening it")
class GaitShapeTest {

    /** One cycle of the fixture's clip, in seconds. */
    private static final double LENGTH = 0.5d;

    /** The excursion the shape sweeps, in radians, as the clip narrows it. */
    private static final float SWEPT = (float) Math.toRadians(35);

    /**
     * A walker swinging every leg it carries, through the given cycle.
     */
    private static @NotNull BuiltStyle swung(@NotNull UnaryOperator<Gait> cycle) {
        return Poses.legged("amble")
            .gait(gait -> cycle.apply(gait.step(
                leg -> leg.timeline(track -> track.swing(Turn.PITCH, -35, 35).over(LENGTH)))))
            .build();
    }

    /**
     * The keyframes one bone plays on one target, as seconds paired with one displaced member.
     */
    private static @NotNull List<String> framesOf(@NotNull BuiltStyle style,
                                                  @NotNull EntityModelData mesh,
                                                  @NotNull String bone,
                                                  @NotNull PoseClip.Target target) {
        return PoseCompiler.compile(style, row(mesh, EntityPose.NONE))
            .pose().clips().getLast().clip().channels().stream()
            .filter(channel -> channel.bone().equals(bone))
            .filter(channel -> channel.target() == target)
            .findFirst().orElseThrow(() -> new AssertionError("no " + target + " for " + bone))
            .keyframes().stream()
            .map(frame -> frame.timeSeconds() + " "
                + (target == PoseClip.Target.ROTATION ? frame.x() : frame.y()))
            .toList();
    }

    /**
     * The rotation keyframes one bone plays on the canonical biped.
     */
    private static @NotNull List<String> pitchesOf(@NotNull BuiltStyle style,
                                                   @NotNull String bone) {
        return framesOf(style, humanoid(), bone, PoseClip.Target.ROTATION);
    }

    @Test
    @DisplayName("a plant holds the shape at its resting bound, then peaks midway through what is left")
    void aPlantMakesATrapezoidOfATriangle() {
        assertEquals(List.of(
                "0.0 " + -SWEPT, "0.2 " + -SWEPT, "0.35 " + SWEPT, "0.5 " + -SWEPT),
            pitchesOf(swung(gait -> gait.plant(0.4)), "right_leg"),
            "four frames: down at the start, still down at four tenths, the peak midway through "
                + "the remaining six tenths, and down again at the wrap");
    }

    @Test
    @DisplayName("a plant of none of the cycle is the triangle itself, frame for frame")
    void aPlantOfNothingIsTheTriangle() {
        assertEquals(pitchesOf(swung(gait -> gait), "right_leg"),
            pitchesOf(swung(gait -> gait.plant(0)), "right_leg"),
            "a plateau of no width is no plateau, and the peak still solves to exactly half "
                + "the cycle rather than to a value a hair off it");
    }

    @Test
    @DisplayName("a plant reshapes a lift as it reshapes a swing, and each keeps its own target")
    void aPlantReachesBothTriangles() {
        BuiltStyle style = Poses.legged("hop")
            .gait(gait -> gait
                .plant(0.4)
                .step(leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, -35, 35).bob(3).over(LENGTH))))
            .build();

        assertEquals(List.of(
                "0.0 " + -SWEPT, "0.2 " + -SWEPT, "0.35 " + SWEPT, "0.5 " + -SWEPT),
            framesOf(style, humanoid(), "right_leg", PoseClip.Target.ROTATION),
            "the swing plants");
        assertEquals(List.of("0.0 0.0", "0.2 0.0", "0.35 -3.0", "0.5 0.0"),
            framesOf(style, humanoid(), "right_leg", PoseClip.Target.POSITION),
            "and so does the lift beside it, on its own target and at the same two times");
    }

    @Test
    @DisplayName("a plant leaves an explicitly timed frame where the author put it")
    void aPlantLeavesKeyedFramesAlone() {
        BuiltStyle style = Poses.legged("amble")
            .gait(gait -> gait
                .plant(0.4)
                .step(leg -> leg.timeline(track -> track
                    .keyframe(0d, -35, 0, 0)
                    .keyframe(0.25d, 35, 0, 0)
                    .keyframe(LENGTH, -35, 0, 0)
                    .over(LENGTH))))
            .build();

        assertEquals(List.of("0.0 " + -SWEPT, "0.25 " + SWEPT, "0.5 " + -SWEPT),
            pitchesOf(style, "right_leg"),
            "a frame that states its own time has no triangle in it for a plateau to widen");
    }

    @Test
    @DisplayName("a plant reaches every row and both sides from one statement")
    void aPlantReachesTheWholeRoster() {
        BuiltStyle style = swung(gait -> gait.plant(0.4));
        List<String> planted = List.of(
            "0.0 " + -SWEPT, "0.2 " + -SWEPT, "0.35 " + SWEPT, "0.5 " + -SWEPT);

        for (String leg : List.of("right_front_leg", "left_front_leg",
            "right_hind_leg", "left_hind_leg"))
            assertEquals(planted, framesOf(style, walker(), leg, PoseClip.Target.ROTATION),
                () -> "a plant states no relationship between limbs, so every leg plants: " + leg);
    }

    @Test
    @DisplayName("a planted shape still closes once an offset has moved where it wraps")
    void aPlantedShapeClosesUnderAnOffset() {
        List<String> frames = pitchesOf(
            swung(gait -> gait.plant(0.4).phase(Rank.FRONT, 0.3)), "right_leg");

        assertEquals(List.of(
                "0.0 " + SWEPT, "0.15 " + -SWEPT, "0.35 " + -SWEPT, "0.5 " + SWEPT),
            frames,
            "the plateau moves intact and the peak lands on the wrap itself, where the pair the "
                + "wrap reads back carries that frame's own value and the later of the two drops");
        assertEquals(frames.getFirst().split(" ")[1], frames.getLast().split(" ")[1],
            () -> "so the two ends still agree, which is what makes the wrap not a jump: "
                + frames);
    }

}
