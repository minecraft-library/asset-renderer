package lib.minecraft.renderer.pose.compile;

import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Gait;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Turn;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.chained;
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
                                                  @NotNull PoseChannel.Kind target) {
        return PoseCompiler.compile(style, row(mesh, EntityPose.NONE))
            .pose().clips().getLast().clip().channels().stream()
            .filter(channel -> channel.bone().equals(bone))
            .filter(channel -> channel.target() == target)
            .findFirst().orElseThrow(() -> new AssertionError("no " + target + " for " + bone))
            .keyframes().stream()
            .map(frame -> frame.timeSeconds() + " "
                + (target == PoseChannel.Kind.ROTATION ? frame.x() : frame.y()))
            .toList();
    }

    /**
     * The rotation keyframes one bone plays on the canonical biped.
     */
    private static @NotNull List<String> pitchesOf(@NotNull BuiltStyle style,
                                                   @NotNull String bone) {
        return framesOf(style, humanoid(), bone, PoseChannel.Kind.ROTATION);
    }

    /** The seconds one cycle of the chained fixture's clip runs. */
    private static final double CHAIN_LENGTH = 0.8d;

    /**
     * A walker with three-bone legs, swinging every bone a trail reaches through the given cycle.
     */
    private static @NotNull BuiltStyle trailed(@NotNull UnaryOperator<Gait> cycle) {
        return Poses.legged("glide")
            .gait(gait -> cycle.apply(gait.step(leg -> leg.timeline(track -> track
                .swing(Turn.PITCH, -32, 32).over(CHAIN_LENGTH)))))
            .build();
    }

    /**
     * The rotation keyframes one bone of the chained walker plays.
     */
    private static @NotNull List<String> chainedPitchesOf(@NotNull BuiltStyle style,
                                                          @NotNull String bone) {
        return framesOf(style, chained(), bone, PoseChannel.Kind.ROTATION);
    }

    @Test
    @DisplayName("a trail lags and shortens each bone below a root, the root itself untouched")
    void aTrailLagsAndShortensDownTheChain() {
        BuiltStyle style = trailed(gait -> gait.trail(0.5, 0.5));
        float root = (float) Math.toRadians(32);
        float link = (float) Math.toRadians(16);
        float foot = (float) Math.toRadians(8);

        assertEquals(List.of("0.0 " + -root, "0.4 " + root, "0.8 " + -root),
            chainedPitchesOf(style, "right_front_leg"),
            "the root is no bones below itself, so it lags none of the cycle and travels the "
                + "whole of the shape");
        assertEquals(List.of("0.0 " + link, "0.4 " + -link, "0.8 " + link),
            chainedPitchesOf(style, "right_front_leg_tip"),
            "the bone below it lags the stated share and travels the stated multiple");
        assertEquals(List.of("0.0 " + -foot, "0.4 " + foot, "0.8 " + -foot),
            chainedPitchesOf(style, "right_front_foot"),
            "and the bone below that lags twice as far - a whole cycle here, which wraps to "
                + "none - at that multiple again");
    }

    @Test
    @DisplayName("a fade is composed down the chain once and applied once")
    void aFadeComposesBeforeItIsApplied() {
        BuiltStyle style = trailed(gait -> gait.trail(0, 0.65));

        assertEquals(List.of(
                "0.0 " + (float) Math.toRadians(-32d * (0.65d * 0.65d)),
                "0.4 " + (float) Math.toRadians(32d * (0.65d * 0.65d)),
                "0.8 " + (float) Math.toRadians(-32d * (0.65d * 0.65d))),
            chainedPitchesOf(style, "right_front_foot"),
            "two bones down is the fade times itself, applied to the authored bound once - "
                + "which is the number an author works out, and not the same bits as folding "
                + "the bound through the fade twice");
    }

    @Test
    @DisplayName("a trail widens what the cycle reaches, and nothing else does")
    void onlyATrailReachesBelowTheRoots() {
        assertEquals(4, PoseCompiler.compile(trailed(gait -> gait), row(chained(), EntityPose.NONE))
                .pose().clips().getLast().clip().channels().size(),
            "a cycle stamps each leg's root and lets the bones below it ride along");
        assertEquals(12, PoseCompiler.compile(trailed(gait -> gait.trail(0.5, 0.5)),
                row(chained(), EntityPose.NONE))
                .pose().clips().getLast().clip().channels().size(),
            "and writing the verb is what says the chain is being addressed, so the author "
                + "does not restate the reach beside it");
    }

    @Test
    @DisplayName("a diagonal and a trailing chain compose, each keying on what it keys on")
    void aTrotAndATrailCompose() {
        BuiltStyle style = trailed(gait -> gait.trot(0.5).trail(0.5, 0.5));
        float root = (float) Math.toRadians(32);
        float link = (float) Math.toRadians(16);

        assertEquals(List.of("0.0 " + -root, "0.4 " + root, "0.8 " + -root),
            chainedPitchesOf(style, "right_front_leg"),
            "the leading root takes neither term");
        assertEquals(List.of("0.0 " + link, "0.4 " + -link, "0.8 " + link),
            chainedPitchesOf(style, "right_front_leg_tip"),
            "the bone below it takes the chain's lag alone, because its root leads");
        assertEquals(List.of("0.0 " + root, "0.4 " + -root, "0.8 " + root),
            chainedPitchesOf(style, "left_front_leg"),
            "the following root takes the diagonal's share alone, because it is a root");
        assertEquals(List.of("0.0 " + -link, "0.4 " + link, "0.8 " + -link),
            chainedPitchesOf(style, "left_front_leg_tip"),
            "and the bone below THAT takes both, which here sum to a whole cycle and wrap back "
                + "to the start - the two verbs compose rather than one winning");
    }

    @Test
    @DisplayName("a row's multiple and a chain's fade compose on a bone that takes both")
    void aGainAndAFadeCompose() {
        BuiltStyle style = Poses.legged("glide")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, -32, 32).over(CHAIN_LENGTH)))
                .gain(Rank.HIND, 0.5)
                .trail(0, 0.5))
            .build();
        float linked = (float) Math.toRadians(8);

        assertEquals(List.of("0.0 " + -linked, "0.4 " + linked, "0.8 " + -linked),
            chainedPitchesOf(style, "right_hind_leg_tip"),
            "half for the row it sits in and half again for the bone above it - the two are "
                + "different questions about one leg and both are answered");
    }

    @Test
    @DisplayName("a gain scales one row's travel and leaves the rows it does not name alone")
    void aGainScalesOneRowsTravel() {
        BuiltStyle canter = Poses.legged("canter")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -32, 32).over(LENGTH)))
                .gain(Rank.HIND, 0.625))
            .build();
        float front = (float) Math.toRadians(32);
        float hind = (float) Math.toRadians(20);

        assertEquals(List.of("0.0 " + -front, "0.25 " + front, "0.5 " + -front),
            framesOf(canter, walker(), "right_front_leg", PoseChannel.Kind.ROTATION),
            "the shape is stated once, and the row no gain names travels the whole of it");
        assertEquals(List.of("0.0 " + -hind, "0.25 " + hind, "0.5 " + -hind),
            framesOf(canter, walker(), "right_hind_leg", PoseChannel.Kind.ROTATION),
            "while the named row travels five eighths of it, on the same times");
        assertEquals(framesOf(canter, walker(), "right_hind_leg", PoseChannel.Kind.ROTATION),
            framesOf(canter, walker(), "left_hind_leg", PoseChannel.Kind.ROTATION),
            "and both legs of that row, because a gain is a fact about the row");
    }

    @Test
    @DisplayName("a gain reaches a shape stated for one row as readily as one stated for every row")
    void aGainReachesAKeyedShape() {
        BuiltStyle keyed = Poses.legged("canter")
            .gait(gait -> gait
                .step(Rank.HIND, leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, -32, 32).over(LENGTH)))
                .gain(Rank.HIND, 0.625))
            .build();
        float hind = (float) Math.toRadians(20);

        assertEquals(List.of("0.0 " + -hind, "0.25 " + hind, "0.5 " + -hind),
            framesOf(keyed, walker(), "right_hind_leg", PoseChannel.Kind.ROTATION),
            "the row a gain names is the mesh's, so it finds whichever copy of the shape "
                + "landed there rather than needing one stated for it");
    }

    @Test
    @DisplayName("two gains name two rows, and each row takes its own")
    void twoGainsNameTwoRows() {
        BuiltStyle style = Poses.legged("amble")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -32, 32).over(LENGTH)))
                .gain(Rank.FRONT, 0.5)
                .gain(Rank.HIND, 0.25))
            .build();
        float front = (float) Math.toRadians(16);
        float hind = (float) Math.toRadians(8);

        assertEquals(List.of("0.0 " + -front, "0.25 " + front, "0.5 " + -front),
            framesOf(style, walker(), "right_front_leg", PoseChannel.Kind.ROTATION),
            "the front row takes the front row's multiple");
        assertEquals(List.of("0.0 " + -hind, "0.25 " + hind, "0.5 " + -hind),
            framesOf(style, walker(), "right_hind_leg", PoseChannel.Kind.ROTATION),
            "and the hind row takes its own rather than the last one written");
    }

    @Test
    @DisplayName("a gain of none of the travel holds the row at its rest")
    void aGainOfNothingHoldsTheRowStill() {
        BuiltStyle style = Poses.legged("hover")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -32, 32).over(LENGTH)))
                .gain(Rank.HIND, 0))
            .build();

        assertEquals(List.of("0.0 -0.0", "0.25 0.0", "0.5 -0.0"),
            framesOf(style, walker(), "right_hind_leg", PoseChannel.Kind.ROTATION),
            "a still row beside moving ones is a real shape and not a missing one, so it keys "
                + "its channel at rest rather than dropping out of the clip");
    }

    @Test
    @DisplayName("a gain scales what travels and never what states where a limb lands")
    void aGainLeavesTheRestAlone() {
        BuiltStyle style = Poses.legged("canter")
            .gait(gait -> gait
                .step(leg -> leg.pitchBy(12).scale(2).sway(Turn.PITCH, -32, 32))
                .gain(Rank.HIND, 0.5))
            .build();
        Map<String, StyleDriver> drivers =
            PoseCompiler.compile(style, row(walker(), EntityPose.NONE)).style().drivers();

        assertEquals((float) Math.toRadians(-32 + 12), drivers.get("style$canter$right_front_leg$x_rot").rest(),
            "the unnamed row sweeps its whole authored bound around the rest it was given");
        assertEquals((float) Math.toRadians(-16 + 12), drivers.get("style$canter$right_hind_leg$x_rot").rest(),
            "the named row sweeps half of it around the SAME rest - the twelve degrees the "
                + "stance adds is where the limb sits, not how far it goes");
        assertEquals(drivers.get("style$canter$right_front_leg$scale").extent(),
            drivers.get("style$canter$right_hind_leg$scale").extent(),
            "and a uniform scale rests at one rather than at zero, so halving it would resize "
                + "the bone instead of moving it less far");
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
            framesOf(style, humanoid(), "right_leg", PoseChannel.Kind.ROTATION),
            "the swing plants");
        assertEquals(List.of("0.0 0.0", "0.2 0.0", "0.35 -3.0", "0.5 0.0"),
            framesOf(style, humanoid(), "right_leg", PoseChannel.Kind.POSITION),
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
            assertEquals(planted, framesOf(style, walker(), leg, PoseChannel.Kind.ROTATION),
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
