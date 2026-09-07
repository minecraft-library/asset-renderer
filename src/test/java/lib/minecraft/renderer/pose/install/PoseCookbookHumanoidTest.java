package lib.minecraft.renderer.pose.install;

import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseOperator;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Preset;
import lib.minecraft.renderer.pose.author.Side;
import lib.minecraft.renderer.pose.author.Turn;
import lib.minecraft.renderer.pose.compile.PoseCompiler;
import lib.minecraft.renderer.tensor.EulerRotation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.bone;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.boneWrite;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.input;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The worked humanoid chains, each compiled as authored and measured through the posing seam -
 * the drivers and splices a chain lowers to, the clip shapes it bakes, and the degrees each bone
 * lands at named ticks, hand-replicated bit for bit where the arithmetic allows it.
 */
@DisplayName("the humanoid cookbook chains land the poses they spell")
class PoseCookbookHumanoidTest {

    /**
     * The catalog period every chain frames its excursions against.
     */
    private static final int PERIOD = 24;

    /**
     * The eight strip ticks an animated schedule samples.
     */
    private static final int[] STRIP_TICKS = {0, 3, 6, 9, 12, 15, 18, 21};

    /**
     * The one-entry inventory every moving style infers.
     */
    private static final @NotNull List<PoseStyle.StyleSource> TICK_ONLY =
        List.of(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty()));

    // ------------------------------------------------------------------------------------
    // wave
    // ------------------------------------------------------------------------------------

    /**
     * One raised arm rolling on a looping swing while the head turns aside.
     */
    @Nested
    @DisplayName("wave")
    class Wave {

        private final @NotNull EntityModelData mesh = zeroed();

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("wave")
                .arm(Side.RIGHT, arm -> arm.rotate(-160, 0, 10)
                    .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
                .head(head -> head.yaw(15))
                .build(),
            row(this.mesh, EntityPose.NONE));

        @Test
        @DisplayName("three held stances, the gate and the clock are the whole driver set")
        void threeHeldStancesPlusTheGatePair() {
            assertHold(this.compiled, "style$wave$right_arm$x_rot", rad(-160));
            assertHold(this.compiled, "style$wave$right_arm$z_rot", rad(10));
            assertHold(this.compiled, "style$wave$head$y_rot", rad(15));
            assertHold(this.compiled, "style$wave", 1f);
            assertEquals(StyleDriver.Wave.RAMP,
                this.compiled.style().drivers().get("style$wave$clock").wave());
            assertEquals(5, this.compiled.style().drivers().size(),
                "nothing else is driven - the zero pitch on the head never became a field");
            assertEquals(TICK_ONLY, List.copyOf(this.compiled.style().sources()));
        }

        @Test
        @DisplayName("the hat rides the head's spliced instances rather than fields of its own")
        void hatRidesTheHeadsInstances() {
            assertSame(this.compiled.pose().bones().get("head").get(PoseChannel.Y_ROT),
                this.compiled.pose().bones().get("hat").get(PoseChannel.Y_ROT),
                "one instance serves both shells");
            assertNull(this.compiled.style().drivers().get("style$wave$hat$y_rot"),
                "so the hat needs no driver of its own");
        }

        @Test
        @DisplayName("one looping clip swings the arm behind the style's own gate and clock")
        void oneGatedClipCarriesTheSwing() {
            assertEquals(1, this.compiled.pose().clips().size());
            EntityPose.Clip site = this.compiled.pose().clips().getFirst();
            assertEquals(MotionSource.SELECT, site.drive());
            assertEquals(Optional.of("style$wave"), site.field());
            assertEquals("style$wave$clock",
                assertInstanceOf(PoseExpr.Input.class, site.arguments().getFirst()).field());

            PoseClip clip = site.clip();
            assertEquals(0.6f, clip.lengthSeconds());
            assertTrue(clip.looping());
            assertEquals(1, clip.channels().size());
            PoseClip.Channel channel = clip.channels().getFirst();
            assertEquals("right_arm", channel.bone());
            assertEquals(PoseClip.Target.ROTATION, channel.target());
            assertEquals(List.of(0f, 0.3f, 0.6f),
                channel.keyframes().stream().map(PoseClip.Keyframe::timeSeconds).toList());
            assertEquals(List.of(rad(-20), rad(20), rad(-20)),
                channel.keyframes().stream().map(PoseClip.Keyframe::z).toList(),
                "the swing keys its two bounds and returns");
            assertEquals(PoseClip.Interpolation.LINEAR, channel.keyframes().getFirst().interpolation());
        }

        @Test
        @DisplayName("the roll at named ticks is the stance plus the swing's hand-computed delta")
        void rollAtNamedTicks() {
            float stance = rad(10);
            float[] deltas = {rad(-20), 0f, rad(20)};
            int[] ticks = {0, 3, 6};
            for (int at = 0; at < ticks.length; at++)
                assertEquals((float) Math.toDegrees(stance + deltas[at]),
                    turned(this.compiled, this.mesh, ticks[at], "right_arm").roll(), 1e-4f,
                    "tick " + ticks[at]);
        }

        @Test
        @DisplayName("the swing tiles twice across one strip period")
        void twoSwingsPerStrip() {
            for (int tick : new int[] {0, 3, 6, 9})
                assertEquals(turned(this.compiled, this.mesh, tick, "right_arm").roll(),
                    turned(this.compiled, this.mesh, tick + 12, "right_arm").roll(), 1e-3f,
                    "tick " + tick + " repeats half a period later");
        }

    }

    // ------------------------------------------------------------------------------------
    // clap
    // ------------------------------------------------------------------------------------

    /**
     * Both palms driven toward the centre line, meeting at each swing peak.
     */
    @Nested
    @DisplayName("clap")
    class Clap {

        private final @NotNull EntityModelData mesh = zeroed();

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("clap")
                .arms(arm -> arm.pitch(-90).yaw(-10)
                    .timeline(track -> track.swing(Turn.YAW, 0, -25).over(0.4)))
                .build(),
            row(this.mesh, EntityPose.NONE));

        @Test
        @DisplayName("four held stances - the left pair negated on yaw, kept on pitch")
        void fourHeldStancesUnderTheSignRule() {
            assertHold(this.compiled, "style$clap$right_arm$x_rot", rad(-90));
            assertHold(this.compiled, "style$clap$left_arm$x_rot", rad(-90));
            assertHold(this.compiled, "style$clap$right_arm$y_rot", rad(-10));
            assertHold(this.compiled, "style$clap$left_arm$y_rot", rad(10));
            assertEquals(6, this.compiled.style().drivers().size(),
                "the four stances plus the gate and its clock");
            assertEquals(TICK_ONLY, List.copyOf(this.compiled.style().sources()));
        }

        @Test
        @DisplayName("one clip carries both arms' swings, the left negated, at the default curve")
        void oneClipCarriesTheMirroredPair() {
            assertEquals(1, this.compiled.pose().clips().size());
            PoseClip clip = this.compiled.pose().clips().getFirst().clip();
            assertEquals(0.4f, clip.lengthSeconds());
            assertEquals(2, clip.channels().size(), "one rotation channel per arm");
            PoseClip.Keyframe right = clip.channels().getFirst().keyframes().get(1);
            PoseClip.Keyframe left = clip.channels().getLast().keyframes().get(1);
            assertEquals(rad(-25), right.y(), "the right swings as authored");
            assertEquals(rad(25), left.y(), "the left swings negated, so the palms close");
            assertEquals(PoseClip.Interpolation.LINEAR, right.interpolation());
        }

        @Test
        @DisplayName("at every strip tick the left arm is the right under x, -y, -z")
        void mirrorLawHoldsAtEveryStripTick() {
            for (int tick : STRIP_TICKS) {
                EulerRotation right = turned(this.compiled, this.mesh, tick, "right_arm");
                EulerRotation left = turned(this.compiled, this.mesh, tick, "left_arm");
                assertEquals(right.pitch(), left.pitch(), 0f, "tick " + tick + " pitch holds");
                assertEquals(-right.yaw(), left.yaw(), 0f, "tick " + tick + " yaw negates");
                assertEquals(-right.roll(), left.roll(), 0f, "tick " + tick + " roll negates");
            }
        }

        @Test
        @DisplayName("the swing tiles three claps across one strip period")
        void threeClapsPerStrip() {
            for (int tick : new int[] {0, 3, 6}) {
                assertEquals(turned(this.compiled, this.mesh, tick, "right_arm").yaw(),
                    turned(this.compiled, this.mesh, tick + 8, "right_arm").yaw(), 1e-3f,
                    "tick " + tick + " repeats a third of a period later");
                assertEquals(turned(this.compiled, this.mesh, tick, "right_arm").yaw(),
                    turned(this.compiled, this.mesh, tick + 16, "right_arm").yaw(), 1e-3f,
                    "and again at two thirds");
            }
        }

    }

    // ------------------------------------------------------------------------------------
    // sit
    // ------------------------------------------------------------------------------------

    /**
     * The seated statue on the shipped armor stand row - preset triples plus a seat drop.
     */
    @Nested
    @DisplayName("sit")
    class Sit {

        private final @NotNull Entity stand = armorStand();

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("sit")
                .preset(Preset.SITTING)
                .container(step -> step.offset(0, 7, 0))
                .build(),
            this.stand);

        @Test
        @DisplayName("a statue holds still - nothing sourced, nothing clipped, one frame")
        void holdsStill() {
            assertTrue(this.compiled.style().sources().isEmpty());
            assertFalse(this.compiled.style().moves(), "so the schedule is one frame at tick zero");
            assertTrue(this.compiled.pose().clips().isEmpty());
        }

        @Test
        @DisplayName("the six preset triples land as authored on the stand's silhouette")
        void landsThePresetSilhouette() {
            EntityModelData posed = posed(this.compiled, this.stand.model(), 0);
            assertTriple(posed.getBones().get("right_arm").getRotation(), -80, 20, 0, "right_arm");
            assertTriple(posed.getBones().get("left_arm").getRotation(), -80, -20, 0, "left_arm");
            assertTriple(posed.getBones().get("right_leg").getRotation(), -90, 10, 0, "right_leg");
            assertTriple(posed.getBones().get("left_leg").getRotation(), -90, -10, 0, "left_leg");
            assertTriple(posed.getBones().get("head").getRotation(), 0, 0, 0, "head");
            assertTriple(posed.getBones().get("body").getRotation(), 0, 0, 0, "body");
        }

        @Test
        @DisplayName("the seat drops the whole figure seven pixels")
        void seatDropsTheFigure() {
            EntityModelData.Bone seat = posed(this.compiled, this.stand.model(), 0)
                .getBones().get("$container");
            assertNotNull(seat, "the container step seats the roots");
            assertEquals(0f, seat.getPivot().x());
            assertEquals(7f, seat.getPivot().y(), "y-down, so positive is a drop");
            assertEquals(0f, seat.getPivot().z());
        }

        @Test
        @DisplayName("the stand's one-degree leg splay rebases to a real sub-degree delta")
        void legSplayRebasesToASubDegreeDelta() {
            StyleDriver splay = this.compiled.style().drivers().get("style$sit$right_leg$z_rot");
            assertNotNull(splay, "the preset's zero roll is a real write on a splayed rest");
            assertEquals(-1d, Math.toDegrees(splay.extent()), 1e-3,
                "one degree back to upright");
            assertNull(this.compiled.style().drivers().get("style$sit$head$x_rot"),
                "where the rest already sits at the target the write elides whole");
        }

    }

    // ------------------------------------------------------------------------------------
    // levitate
    // ------------------------------------------------------------------------------------

    /**
     * Arms drifted outward and legs trailed while the whole figure lifts and bobs.
     */
    @Nested
    @DisplayName("levitate")
    class Levitate {

        private final @NotNull EntityModelData mesh = zeroed();

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("levitate")
                .arms(arm -> arm.roll(35))
                .legs(leg -> leg.pitch(-6))
                .hover(8, 2)
                .build(),
            row(this.mesh, EntityPose.NONE));

        @Test
        @DisplayName("four held limb stances, the arm pair mirrored on roll")
        void fourHeldLimbStances() {
            assertHold(this.compiled, "style$levitate$right_arm$z_rot", rad(35));
            assertHold(this.compiled, "style$levitate$left_arm$z_rot", rad(-35));
            assertHold(this.compiled, "style$levitate$right_leg$x_rot", rad(-6));
            assertHold(this.compiled, "style$levitate$left_leg$x_rot", rad(-6));
        }

        @Test
        @DisplayName("hover lowers to a held lift and a swept bob summed on one seat channel")
        void hoverLowersToLiftAndBobOnOneSeat() {
            StyleDriver lift = this.compiled.style().drivers().get("style$levitate$$container$y");
            StyleDriver bob = this.compiled.style().drivers().get("style$levitate$$container$y_bob");
            assertEquals(StyleDriver.Wave.HOLD, lift.wave());
            assertEquals(-8f, lift.extent(), "y-down, so a lift is negative under the surface");
            assertEquals(StyleDriver.Wave.SWEEP, bob.wave());
            assertEquals(-2f, bob.extent());

            PoseExpr.Op vertical = assertInstanceOf(PoseExpr.Op.class,
                this.compiled.pose().container().getFirst().get(PoseChannel.Y));
            assertEquals(PoseOperator.DADD, vertical.operator());
            assertEquals("style$levitate$$container$y",
                assertInstanceOf(PoseExpr.Input.class, vertical.operands().getFirst()).field());
            assertEquals("style$levitate$$container$y_bob",
                assertInstanceOf(PoseExpr.Input.class, vertical.operands().getLast()).field());
        }

        @Test
        @DisplayName("the figure holds its lift at tick zero and dips to the bob's peak mid-period")
        void liftHoldsAndBobDips() {
            assertEquals(-8f, posed(this.compiled, this.mesh, 0).getBones().get("$container").getPivot().y(),
                "the sweep rests at zero, leaving the lift alone");
            assertEquals(-10f, posed(this.compiled, this.mesh, 12).getBones().get("$container").getPivot().y(),
                "and peaks mid-period, so the figure dips and returns once per period");
            assertEquals(TICK_ONLY, List.copyOf(this.compiled.style().sources()),
                "the nonzero bob is what moves the style");
        }

    }

    // ------------------------------------------------------------------------------------
    // dab
    // ------------------------------------------------------------------------------------

    /**
     * The face buried toward the left elbow, one arm folded across and one extended.
     */
    @Nested
    @DisplayName("dab")
    class Dab {

        private final @NotNull EntityModelData mesh = zeroed();

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("dab")
                .head(head -> head.rotate(30, -35, 0))
                .arm(Side.LEFT, arm -> arm.rotate(-150, -35, 0))
                .arm(Side.RIGHT, arm -> arm.rotate(-160, 35, 0))
                .build(),
            row(this.mesh, EntityPose.NONE));

        @Test
        @DisplayName("the three authored triples land whole at tick zero, hat with head")
        void landsTheAuthoredTriples() {
            EntityModelData posed = posed(this.compiled, this.mesh, 0);
            assertTriple(posed.getBones().get("head").getRotation(), 30, -35, 0, "head");
            assertTriple(posed.getBones().get("hat").getRotation(), 30, -35, 0, "hat");
            assertTriple(posed.getBones().get("left_arm").getRotation(), -150, -35, 0, "left_arm");
            assertTriple(posed.getBones().get("right_arm").getRotation(), -160, 35, 0, "right_arm");
        }

        @Test
        @DisplayName("the zero roll writes elide on rests already at roll zero - six splices remain")
        void zeroRollWritesElide() {
            assertNull(this.compiled.style().drivers().get("style$dab$head$z_rot"));
            assertNull(this.compiled.style().drivers().get("style$dab$right_arm$z_rot"));
            assertNull(this.compiled.style().drivers().get("style$dab$left_arm$z_rot"));
            assertEquals(6, this.compiled.style().drivers().size(),
                "pitch and yaw per stanced bone, nothing more");
            assertTrue(this.compiled.style().sources().isEmpty(), "a statue");
        }

        @Test
        @DisplayName("the hat mirrors the head's spliced instances")
        void hatMirrorsHeadByInstance() {
            assertSame(this.compiled.pose().bones().get("head").get(PoseChannel.X_ROT),
                this.compiled.pose().bones().get("hat").get(PoseChannel.X_ROT));
            assertSame(this.compiled.pose().bones().get("head").get(PoseChannel.Y_ROT),
                this.compiled.pose().bones().get("hat").get(PoseChannel.Y_ROT));
        }

    }

    // ------------------------------------------------------------------------------------
    // jog
    // ------------------------------------------------------------------------------------

    /**
     * A lean and an elbows-forward carriage added over the live shipped stride.
     */
    @Nested
    @DisplayName("jog")
    class Jog {

        private final @NotNull EntityModelData mesh = zeroed();

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("jog")
                .keepStride()
                .torso(torso -> torso.pitchBy(12))
                .head(head -> head.pitchBy(-12))
                .arms(arm -> arm.pitchBy(-20))
                .build(),
            row(this.mesh, boneWrite("right_arm", PoseChannel.X_ROT, input("walkAnimationPos"))));

        @Test
        @DisplayName("the stride trio copies onto the row and the deltas convert with no rebase")
        void strideTrioCopiesAndDeltasNeverRebase() {
            assertEquals(StyleDriver.Wave.RAMP, this.compiled.style().drivers().get("ageInTicks").wave());
            assertEquals(StyleDriver.Wave.HOLD, this.compiled.style().drivers().get("walkAnimationSpeed").wave());
            assertEquals(StyleDriver.Wave.RAMP, this.compiled.style().drivers().get("walkAnimationPos").wave());
            assertHold(this.compiled, "style$jog$body$x_rot", rad(12));
            assertHold(this.compiled, "style$jog$head$x_rot", rad(-12));
            assertHold(this.compiled, "style$jog$right_arm$x_rot", rad(-20));
            assertHold(this.compiled, "style$jog$left_arm$x_rot", rad(-20));
            assertEquals(TICK_ONLY, List.copyOf(this.compiled.style().sources()));
        }

        @Test
        @DisplayName("the arm delta rides the live stride base - the shipped swing runs under it")
        void armDeltaRidesTheLiveStride() {
            PoseStyle stride = StyleCatalog.BIND_ONLY.resolve(
                PoseStyle.STRIDE, EntityOptions.of("minecraft:test"));
            float underStride = PoseKit.posed(this.compiled.pose(), this.mesh, stride, PERIOD, 6)
                .getBones().get("right_arm").getRotation().pitch();
            float underJog = turned(this.compiled, this.mesh, 6, "right_arm").pitch();

            // At tick six the ramped phase answers six radians; the jog value adds its converted
            // delta onto that live figure rather than onto a rest snapshot.
            assertEquals((float) Math.toDegrees(6f), underStride);
            assertEquals((float) Math.toDegrees((float) (6d + (double) rad(-20))), underJog);
        }

        @Test
        @DisplayName("the lean and the level gaze land as plain additive degrees")
        void leanAndLevelGazeLand() {
            assertEquals(12, turned(this.compiled, this.mesh, 6, "body").pitch(), 1e-3,
                "the torso tips forward");
            assertEquals(-12, turned(this.compiled, this.mesh, 6, "head").pitch(), 1e-3,
                "and the head holds level against the lean");
        }

    }

    // ------------------------------------------------------------------------------------
    // point
    // ------------------------------------------------------------------------------------

    /**
     * A preset stamp adjusted after the fact - the later write wins the fold.
     */
    @Nested
    @DisplayName("point")
    class Point {

        private final @NotNull EntityModelData mesh = zeroed();

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("point")
                .preset(Preset.POINTING)
                .arm(Side.RIGHT, arm -> arm.yaw(25))
                .build(),
            row(this.mesh, EntityPose.NONE));

        @Test
        @DisplayName("exactly one held stance per final non-zero channel, the adjustment superseding")
        void oneStancePerFinalNonZeroChannel() {
            assertHold(this.compiled, "style$point$right_arm$x_rot", rad(-90));
            assertHold(this.compiled, "style$point$right_arm$y_rot", rad(25));
            assertHold(this.compiled, "style$point$head$y_rot", rad(20));
            assertHold(this.compiled, "style$point$left_arm$z_rot", rad(-10));
            assertEquals(4, this.compiled.style().drivers().size(),
                "the preset's other writes rebase to zero on a zeroed rest and elide");
            assertTrue(this.compiled.style().sources().isEmpty(), "a statue");
        }

        @Test
        @DisplayName("the aim lands wider of the camera, everything else as the preset stamped it")
        void landsTheAdjustedStamp() {
            EntityModelData posed = posed(this.compiled, this.mesh, 0);
            assertTriple(posed.getBones().get("right_arm").getRotation(), -90, 25, 0, "right_arm");
            assertTriple(posed.getBones().get("head").getRotation(), 0, 20, 0, "head");
            assertTriple(posed.getBones().get("left_arm").getRotation(), 0, 0, -10, "left_arm");
            assertTriple(posed.getBones().get("right_leg").getRotation(), 0, 0, 0, "right_leg");
        }

    }

    // ------------------------------------------------------------------------------------
    // t-pose
    // ------------------------------------------------------------------------------------

    /**
     * Arms straight out on the shipped armor stand row, open to every age.
     */
    @Nested
    @DisplayName("t-pose")
    class TPose {

        private final @NotNull Entity stand = armorStand();

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("t_pose")
                .preset(Preset.T_POSE)
                .allAges()
                .build(),
            this.stand);

        @Test
        @DisplayName("the sum of rest and rebased delta lands the absolute, not the extent")
        void sumLandsTheAbsoluteTarget() {
            EntityModelData posed = posed(this.compiled, this.stand.model(), 0);
            assertTriple(posed.getBones().get("right_arm").getRotation(), 0, 0, 90, "right_arm");
            assertTriple(posed.getBones().get("left_arm").getRotation(), 0, 0, -90, "left_arm");
        }

        @Test
        @DisplayName("the extents are eighty degrees - the stand's ten-degree rest folded in")
        void extentsCarryTheRebasedEighty() {
            assertEquals(80d,
                Math.toDegrees(this.compiled.style().drivers().get("style$t_pose$right_arm$z_rot").extent()),
                1e-2, "ninety asked, ten resting");
            assertEquals(-80d,
                Math.toDegrees(this.compiled.style().drivers().get("style$t_pose$left_arm$z_rot").extent()),
                1e-2);
            assertEquals(15d,
                Math.toDegrees(this.compiled.style().drivers().get("style$t_pose$right_arm$x_rot").extent()),
                1e-2, "the zero pitch is a real write over the hung rest");
            assertEquals(10d,
                Math.toDegrees(this.compiled.style().drivers().get("style$t_pose$left_arm$x_rot").extent()),
                1e-2);
        }

        @Test
        @DisplayName("allAges clears the adult default, so a folded baby poses instead of refusing")
        void allAgesClearsTheAdultDefault() {
            assertEquals(Optional.empty(), this.compiled.style().age());
        }

    }

    // ------------------------------------------------------------------------------------
    // hail
    // ------------------------------------------------------------------------------------

    /**
     * Arm and gaze stating one target, re-solved from each row's own pivots.
     */
    @Nested
    @DisplayName("hail")
    class Hail {

        private final @NotNull BuiltStyle hail = Poses.humanoid("hail")
            .arm(Side.RIGHT, arm -> arm.aimAt(18, -30, -14))
            .head(head -> head.aimAt(18, -30, -14))
            .build();

        @Test
        @DisplayName("each row lands its own solve - the arm offset a quarter turn, the head as-is")
        void eachRowLandsItsOwnSolve() {
            EntityModelData mesh = zeroed();
            PoseCompiler.Compiled compiled = PoseCompiler.compile(this.hail, row(mesh, EntityPose.NONE));
            EntityModelData posed = posed(compiled, mesh, 0);
            double[] armSolve = solved(mesh, "right_arm");
            double[] headSolve = solved(mesh, "head");
            assertEquals(armSolve[0] - 90d, posed.getBones().get("right_arm").getRotation().pitch(), 1e-3,
                "a hanging limb rests pointing down");
            assertEquals(armSolve[1], posed.getBones().get("right_arm").getRotation().yaw(), 1e-3);
            assertEquals(headSolve[0], posed.getBones().get("head").getRotation().pitch(), 1e-3,
                "a facing bone takes the solve whole");
            assertEquals(headSolve[1], posed.getBones().get("head").getRotation().yaw(), 1e-3);
            assertEquals(0f, posed.getBones().get("right_arm").getRotation().roll(),
                "roll stays untouched - the solve writes two channels");
            assertTrue(compiled.style().sources().isEmpty(), "a statue");
        }

        @Test
        @DisplayName("one built value re-solves per row - a higher shoulder answers different angles")
        void oneValueResolvesPerRow() {
            EntityModelData low = zeroed();
            EntityModelData high = zeroed();
            high.getBones().put("right_arm", bone(-5f, -4f, 0f, 0f, 0f, 0f, 1f, null));

            float lowPitch = turned(PoseCompiler.compile(this.hail, row(low, EntityPose.NONE)),
                low, 0, "right_arm").pitch();
            float highPitch = turned(PoseCompiler.compile(this.hail, row(high, EntityPose.NONE)),
                high, 0, "right_arm").pitch();
            assertEquals(solved(high, "right_arm")[0] - 90d, highPitch, 1e-3,
                "the raised pivot's own solve, not the first row's");
            assertTrue(Math.abs(lowPitch - highPitch) > 1d,
                "the value states the target, so two shoulders answer two pitches");
        }

        /**
         * The facing solve toward the shared target, recomputed from one row's own pivot.
         */
        private static double @NotNull [] solved(@NotNull EntityModelData mesh, @NotNull String bone) {
            double dx = 18d - mesh.getBones().get(bone).getPivot().x();
            double dy = -30d - mesh.getBones().get(bone).getPivot().y();
            double dz = -14d - mesh.getBones().get(bone).getPivot().z();
            return new double[] {
                Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))),
                Math.toDegrees(Math.atan2(-dx, -dz))
            };
        }

    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    /**
     * The canonical seven-bone biped with every rotation resting at zero.
     */
    private static @NotNull EntityModelData zeroed() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("head", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("body", bone(0f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("right_arm", bone(-5f, 2f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_arm", bone(5f, 2f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("right_leg", bone(-2f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_leg", bone(2f, 12f, 0f, 0f, 0f, 0f, 1f, null));
        return mesh;
    }

    /**
     * The shipped armor stand row - the rest silhouette every preset table was read against.
     */
    private static @NotNull Entity armorStand() {
        return EntityModelLoader.load().get("minecraft:armor_stand");
    }

    /**
     * The compiled style posed onto one mesh at one tick.
     */
    private static @NotNull EntityModelData posed(
        @NotNull PoseCompiler.Compiled compiled, @NotNull EntityModelData mesh, int tick) {

        return PoseKit.posed(compiled.pose(), mesh, compiled.style(), PERIOD, tick);
    }

    /**
     * One posed bone's rotation.
     */
    private static @NotNull EulerRotation turned(
        @NotNull PoseCompiler.Compiled compiled, @NotNull EntityModelData mesh,
        int tick, @NotNull String bone) {

        return posed(compiled, mesh, tick).getBones().get(bone).getRotation();
    }

    /**
     * A rotation triple asserted in whole degrees.
     */
    private static void assertTriple(
        @NotNull EulerRotation rotation, double pitch, double yaw, double roll, @NotNull String bone) {

        assertEquals(pitch, rotation.pitch(), 1e-3, bone + " pitch");
        assertEquals(yaw, rotation.yaw(), 1e-3, bone + " yaw");
        assertEquals(roll, rotation.roll(), 1e-3, bone + " roll");
    }

    /**
     * The authored degrees as the radians a driver extent carries.
     */
    private static float rad(double degrees) {
        return (float) Math.toRadians(degrees);
    }

    /**
     * One driver asserted held at one extent.
     */
    private static void assertHold(
        @NotNull PoseCompiler.Compiled compiled, @NotNull String field, float extent) {

        StyleDriver driver = compiled.style().drivers().get(field);
        assertNotNull(driver, "driver '" + field + "' rides the row");
        assertEquals(StyleDriver.Wave.HOLD, driver.wave(), field);
        assertEquals(extent, driver.extent(), field + " extent");
    }

}
