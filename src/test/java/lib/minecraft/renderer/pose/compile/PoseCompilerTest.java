package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseOperator;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Side;
import lib.minecraft.renderer.pose.author.Turn;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.boneWrite;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.constant;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.dadd;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.flattened;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.input;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.pose;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lowering rules measured through the posing seam - evaluated channel values at given ticks
 * against hand-replicated expected radians, plus the field grammar, width and unit pins the
 * compiled records must hold.
 */
@DisplayName("the compiler lowers a built style into splices the runtime evaluates as authored")
class PoseCompilerTest {

    /**
     * The catalog period every fixture row frames its excursions against.
     */
    private static final int PERIOD = 24;

    @Test
    @DisplayName("an absolute write rebases against the evaluated rest and lands the stated angle")
    void absoluteWriteRebasesAgainstTheRest() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("t_pose").arm(Side.RIGHT, arm -> arm.roll(90)).build(),
            row(mesh, EntityPose.NONE));

        float rest = mesh.getBones().get("right_arm").getRotation().rollRadians();
        StyleDriver driver = compiled.style().drivers().get("style$t_pose$right_arm$z_rot");
        assertNotNull(driver, "one channel splice takes one style-namespaced field");
        assertEquals(StyleDriver.Wave.HOLD, driver.wave(), "a held stance is a held extent");
        assertEquals(extentOf(90, rest), driver.extent(), "the extent is the rebased delta, narrowed once");

        float landed = posed(compiled, mesh, 0).getBones().get("right_arm").getRotation().roll();
        assertEquals(degreesOf(rest, driver.extent()), landed, "the sum of rest and delta is the write-back");
        assertEquals(90f, landed, 1e-4f, "and the sum lands the absolute target, not the extent");
    }

    @Test
    @DisplayName("a channel the shipped pose writes is spliced over the shipped instance itself")
    void shippedBaseIsReferencedByInstance() {
        PoseExpr shippedExpr = dadd(constant(0.3d), constant(0d));
        EntityPose shipped = boneWrite("right_arm", PoseChannel.X_ROT, shippedExpr);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("lean").arm(Side.RIGHT, arm -> arm.pitch(-40)).build(),
            row(humanoid(), shipped));

        PoseExpr woven = compiled.pose().bones().get("right_arm").get(PoseChannel.X_ROT);
        PoseExpr.Op splice = assertInstanceOf(PoseExpr.Op.class, woven);
        assertEquals(PoseOperator.DADD, splice.operator(), "the splice sums at double width");
        assertSame(shippedExpr, splice.operands().getFirst(),
            "the base is the shipped instance, never a rebuilt duplicate");
        PoseExpr.Input field = assertInstanceOf(PoseExpr.Input.class, splice.operands().getLast());
        assertEquals("style$lean$right_arm$x_rot", field.field());
        assertEquals(extentOf(-40, 0.3f),
            compiled.style().drivers().get("style$lean$right_arm$x_rot").extent(),
            "the rebase reads the shipped expression's evaluated rest");
    }

    @Test
    @DisplayName("an absolute write that rebases to a zero delta elides whole")
    void zeroDeltaElidesTheWrite() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("still").head(head -> head.pitch(0)).build(),
            row(mesh, EntityPose.NONE));

        assertTrue(compiled.style().drivers().isEmpty(), "no field and no driver");
        assertFalse(compiled.pose().bones().containsKey("head"), "and no splice");
        assertTrue(compiled.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == StyleDiagnostics.Severity.INFO
                    && entry.message().contains("head") && entry.message().contains("x_rot")),
            "the elision records its bone and channel");
    }

    @Test
    @DisplayName("a ladder-shaped shipped base compiles - every scan visits each node once")
    void ladderBaseCompilesByVisitingEachNodeOnce() {
        // Each rung's two operands are the SAME previous-rung instance, so forty-one nodes
        // stand for two-to-the-fortieth paths - the humanoid-arm shape in miniature. The rest
        // evaluation, the driven-base scan, the raw-hatch check and the interning of the gate
        // the hatch rides all walk it, and each completes only by memoizing per node instance.
        PoseExpr ladder = constant(0.25d);
        for (int rung = 0; rung < 40; rung++)
            ladder = dadd(ladder, ladder);
        EntityPose shipped = boneWrite("right_arm", PoseChannel.X_ROT, ladder);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("lean")
                .keepStride()
                .arm(Side.RIGHT, arm -> arm.pitch(-40))
                .build(),
            row(humanoid(), shipped));

        PoseExpr.Op splice = assertInstanceOf(PoseExpr.Op.class,
            compiled.pose().bones().get("right_arm").get(PoseChannel.X_ROT));
        assertSame(ladder, splice.operands().getFirst(),
            "the shipped ladder rides the splice by instance");

        PoseExpr duplicate = constant(0.25d);
        for (int rung = 0; rung < 40; rung++)
            duplicate = dadd(duplicate, duplicate);
        PoseCompiler.Compiled hatched = PoseCompiler.compile(
            Poses.custom("twin").expr("right_arm", PoseChannel.Y_ROT, duplicate).build(),
            row(humanoid(), shipped));
        PoseExpr.Select gated = assertInstanceOf(PoseExpr.Select.class,
            hatched.pose().bones().get("right_arm").get(PoseChannel.Y_ROT));
        assertSame(ladder, gated.whenTrue(),
            "a structural duplicate interns to the shipped instances before its checks walk it");
    }

    @Test
    @DisplayName("under a row that drives none of its fields the woven graph answers the shipped values")
    void restingSplicesAnswerShippedValues() {
        EntityModelData mesh = humanoid();
        PoseExpr shippedExpr = constant(0.25d);
        // The shipped table writes head and hat with one shared instance, the way a shell that
        // copies its head is baked.
        EntityPose shipped = pose(List.of(), Map.of(
            "head", Map.of(PoseChannel.Y_ROT, shippedExpr),
            "hat", Map.of(PoseChannel.Y_ROT, shippedExpr)), List.of());
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("nod")
                .head(head -> head.yaw(35).pitch(-20))
                .arm(Side.LEFT, arm -> arm.rollBy(15))
                .build(),
            row(mesh, shipped));

        EntityModelData underShipped = PoseKit.posed(shipped, mesh, undriven("other"), PERIOD, 7);
        EntityModelData underWoven = PoseKit.posed(compiled.pose(), mesh, undriven("other"), PERIOD, 7);
        assertEquals(underShipped.getBones(), underWoven.getBones(),
            "every added node evaluates to the shipped value when its field rests");
    }

    @Test
    @DisplayName("an additive write rides a live driven base with no rebase")
    void additiveWriteRidesTheLiveBase() {
        EntityModelData mesh = humanoid();
        PoseExpr stride = input("walkAnimationPos");
        EntityPose shipped = boneWrite("right_arm", PoseChannel.X_ROT, stride);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("jog").keepStride().arm(Side.RIGHT, arm -> arm.pitchBy(-20)).build(),
            row(mesh, shipped));

        assertEquals(StyleDriver.Wave.RAMP, compiled.style().drivers().get("walkAnimationPos").wave(),
            "the stride trio copies onto the row");
        assertEquals((float) Math.toRadians(-20), compiled.style().drivers().get("style$jog$right_arm$x_rot").extent(),
            "an additive delta converts once and never rebases");

        // At tick six the ramped stride phase answers six, and the splice adds its delta onto
        // that live value rather than onto a rest snapshot.
        float landed = posed(compiled, mesh, 6).getBones().get("right_arm").getRotation().pitch();
        float written = (float) (6d + (double) (float) Math.toRadians(-20));
        assertEquals((float) Math.toDegrees(written), landed);
    }

    @Test
    @DisplayName("a sway lowers to one swept driver whose bounds are the authored deltas")
    void swayLowersToOneSweptDriver() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("wag").arm(Side.RIGHT, arm -> arm.sway(Turn.ROLL, -8, 8)).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver driver = compiled.style().drivers().get("style$wag$right_arm$z_rot");
        assertNotNull(driver);
        assertEquals(StyleDriver.Wave.SWEEP, driver.wave());
        assertEquals((float) Math.toRadians(-8), driver.rest(), "the sweep rests at the first bound");
        assertEquals((float) Math.toRadians(8), driver.extent(), "and peaks at the second");

        float rest = mesh.getBones().get("right_arm").getRotation().rollRadians();
        assertEquals(degreesOf(rest, driver.at(0, PERIOD)),
            posed(compiled, mesh, 0).getBones().get("right_arm").getRotation().roll(),
            "tick zero holds the resting bound");
        assertEquals(degreesOf(rest, driver.at(12, PERIOD)),
            posed(compiled, mesh, 12).getBones().get("right_arm").getRotation().roll(),
            "mid-period holds the peak");
        assertEquals(degreesOf(rest, (float) Math.toRadians(8)),
            posed(compiled, mesh, 12).getBones().get("right_arm").getRotation().roll(),
            "which is the far bound exactly");
    }

    @Test
    @DisplayName("a spin lowers to one cycling driver wrapping a full turn per period")
    void spinLowersToOneCyclingDriver() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("twirl").head(head -> head.spin(Turn.YAW, 360)).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver driver = compiled.style().drivers().get("style$twirl$head$y_rot");
        assertNotNull(driver);
        assertEquals(StyleDriver.Wave.CYCLE, driver.wave());
        assertEquals(0f, driver.rest());
        assertEquals((float) Math.toRadians(360), driver.extent());
        assertEquals((float) Math.toRadians(360) * 0.25f, driver.at(6, PERIOD),
            "a quarter period travels a quarter turn");
    }

    @Test
    @DisplayName("a stance write and a sway on one channel fold into one driver's shifted bounds")
    void writeAndSwayFoldIntoOneDriver() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("tilt").head(head -> head.yawBy(30).sway(Turn.YAW, -5, 5)).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver driver = compiled.style().drivers().get("style$tilt$head$y_rot");
        assertNotNull(driver, "one field holds one driver");
        assertEquals(StyleDriver.Wave.SWEEP, driver.wave());
        double delta = Math.toRadians(30);
        assertEquals((float) (Math.toRadians(-5) + delta), driver.rest(),
            "the stance delta shifts both bounds");
        assertEquals((float) (Math.toRadians(5) + delta), driver.extent());
    }

    @Test
    @DisplayName("timelines lower to one clip behind a selection-gated play site")
    void timelinesLowerToOneGatedClip() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("wave")
                .arm(Side.RIGHT, arm -> arm.roll(30)
                    .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
                .build(),
            row(mesh, EntityPose.NONE));

        assertEquals(1, compiled.pose().clips().size(), "one clip per built style");
        EntityPose.Clip site = compiled.pose().clips().getFirst();
        assertEquals(MotionSource.SELECT, site.drive());
        assertEquals(Optional.of("style$wave"), site.field(), "the gate field is present by construction");
        assertEquals(1, site.arguments().size(), "the selection arity is satisfied by construction");
        PoseExpr.Input clock = assertInstanceOf(PoseExpr.Input.class, site.arguments().getFirst());
        assertEquals("style$wave$clock", clock.field(), "a fresh clock, never a shipped idle term");
        assertEquals(1f, compiled.style().drivers().get("style$wave").extent(), "the gate holds at one");
        assertEquals(StyleDriver.Wave.RAMP, compiled.style().drivers().get("style$wave$clock").wave());

        PoseClip clip = site.clip();
        assertEquals(0.6f, clip.lengthSeconds());
        assertTrue(clip.looping());
        assertEquals(1, clip.channels().size());
        PoseClip.Channel channel = clip.channels().getFirst();
        assertEquals("right_arm", channel.bone());
        assertEquals(PoseClip.Target.ROTATION, channel.target());
        assertEquals(List.of(0f, 0.3f, 0.6f),
            channel.keyframes().stream().map(PoseClip.Keyframe::timeSeconds).toList(),
            "the swing triangle keys its ends and middle");
        assertEquals((float) Math.toRadians(-20), channel.keyframes().getFirst().z(),
            "keyframe components are radians");
        assertEquals(PoseClip.Interpolation.LINEAR, channel.keyframes().getFirst().interpolation());
    }

    @Test
    @DisplayName("the clip's deltas add onto the stance at real twenty-per-second seconds")
    void clipDeltasRideTheStance() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("wave")
                .arm(Side.RIGHT, arm -> arm.roll(30)
                    .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
                .build(),
            row(mesh, EntityPose.NONE));

        float rest = mesh.getBones().get("right_arm").getRotation().rollRadians();
        float extent = compiled.style().drivers().get("style$wave$right_arm$z_rot").extent();
        float stance = (float) ((double) rest + (double) extent);
        // Ticks zero, three and six are clock seconds 0, 0.15 and 0.3 - the swing's start, its
        // midpoint climb and its peak.
        float[] deltas = {(float) Math.toRadians(-20), 0f, (float) Math.toRadians(20)};
        int[] ticks = {0, 3, 6};
        for (int at = 0; at < ticks.length; at++) {
            float landed = posed(compiled, mesh, ticks[at]).getBones().get("right_arm").getRotation().roll();
            float expected = (float) Math.toDegrees(stance + deltas[at]);
            assertEquals(expected, landed, 1e-4f, "tick " + ticks[at]);
        }
    }

    @Test
    @DisplayName("a paired stamp mirrors the timeline, so the pair moves as mirror images")
    void pairedTimelineMirrors() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("clap")
                .arms(arm -> arm.timeline(track -> track.swing(Turn.YAW, 0, -25).over(0.4)))
                .build(),
            row(mesh, EntityPose.NONE));

        PoseClip clip = compiled.pose().clips().getFirst().clip();
        assertEquals(2, clip.channels().size(), "one rotation channel per arm");
        PoseClip.Keyframe right = clip.channels().getFirst().keyframes().get(1);
        PoseClip.Keyframe left = clip.channels().getLast().keyframes().get(1);
        assertEquals((float) Math.toRadians(-25), right.y(), "the right swings as authored");
        assertEquals((float) Math.toRadians(25), left.y(), "the left swings negated");
    }

    @Test
    @DisplayName("a container step appends innermost with bare field reads")
    void containerStepAppendsBareFieldReads() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("sit")
                .container(step -> step.offset(0, 7, 0))
                .build(),
            row(mesh, EntityPose.NONE));

        assertEquals(1, compiled.pose().container().size());
        PoseExpr vertical = compiled.pose().container().getFirst().get(PoseChannel.Y);
        assertEquals("style$sit$$container$y", assertInstanceOf(PoseExpr.Input.class, vertical).field(),
            "a container channel rests at no transform, so the read needs no base");
        assertEquals(7f, compiled.style().drivers().get("style$sit$$container$y").extent());

        EntityModelData posed = posed(compiled, mesh, 0);
        assertEquals(7f, posed.getBones().get("$container").getPivot().y(),
            "the seat drops the whole figure");
        assertTrue(compiled.style().sources().isEmpty(), "a held seat is a statue, not an animation");
    }

    @Test
    @DisplayName("hover lowers to a lift held and a bob swept on two container fields")
    void hoverLowersToLiftAndBob() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("levitate").hover(8, 2).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver lift = compiled.style().drivers().get("style$levitate$$container$y");
        StyleDriver bob = compiled.style().drivers().get("style$levitate$$container$y_bob");
        assertEquals(StyleDriver.Wave.HOLD, lift.wave());
        assertEquals(-8f, lift.extent(), "y-down, so lift is negative and the surface hides the sign");
        assertEquals(StyleDriver.Wave.SWEEP, bob.wave());
        assertEquals(-2f, bob.extent());

        PoseExpr vertical = compiled.pose().container().getFirst().get(PoseChannel.Y);
        assertEquals(PoseOperator.DADD, assertInstanceOf(PoseExpr.Op.class, vertical).operator(),
            "two drivers cannot share one field, so the channel sums two reads");

        assertEquals(-8f, posed(compiled, mesh, 0).getBones().get("$container").getPivot().y(),
            "the sweep rests at zero, leaving the lift alone");
        assertEquals(-10f, posed(compiled, mesh, 12).getBones().get("$container").getPivot().y(),
            "and peaks mid-period, dipping the figure");
        assertEquals(List.of(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty())),
            List.copyOf(compiled.style().sources()), "a nonzero bob moves the style on the clock");
    }

    @Test
    @DisplayName("on a row whose clips displace the container the custom step is the fold seat")
    void foldSeatTakesTheCustomStep() {
        EntityModelData mesh = humanoid();
        // A shipped clip reaching the part every bone hangs from marks the fold-seat row; its
        // first instant displaces nothing, so evaluation stays neutral.
        EntityPose shipped = pose(List.of(), Map.of(), List.of(displacingSite()));
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("perch").container(step -> step.offset(0, 5, 0)).build(),
            row(mesh, shipped));

        assertEquals(1, compiled.pose().container().size(),
            "the custom channels form the row's sole written step, which the displacement folds onto");
    }

    @Test
    @DisplayName("a shipped container step a clip displaces is spliced, never re-seated")
    void foldSeatSplicesTheShippedStep() {
        EntityModelData mesh = humanoid();
        PoseExpr shippedStep = constant(0.1d);
        EntityPose shipped = pose(
            List.of(Map.of(PoseChannel.X_ROT, shippedStep)),
            Map.of(), List.of(displacingSite()));
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("perch")
                .container(step -> step.pitch(-30).yawBy(10))
                .build(),
            row(mesh, shipped));

        assertEquals(1, compiled.pose().container().size(), "no step appends below a shipped seat");
        Map<PoseChannel, PoseExpr> innermost = compiled.pose().container().getFirst();
        PoseExpr.Op folded = assertInstanceOf(PoseExpr.Op.class, innermost.get(PoseChannel.X_ROT));
        assertSame(shippedStep, folded.operands().getFirst(),
            "a channel the shipped step writes is spliced on the shipped instance");
        assertInstanceOf(PoseExpr.Input.class, innermost.get(PoseChannel.Y_ROT),
            "a channel it does not write joins the step as a bare read");
    }

    /**
     * A shipped play site whose clip reaches the container and rests displacement-free.
     */
    private static @NotNull EntityPose.Clip displacingSite() {
        PoseClip clip = new PoseClip(1f, true, Concurrent.newUnmodifiableList(
            new PoseClip.Channel("root", PoseClip.Target.ROTATION, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(0.5f, 0f, 0f, 0.05f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(1f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR)))));
        return new EntityPose.Clip("sway", MotionSource.NONE, Optional.empty(),
            Concurrent.newUnmodifiableList(), clip);
    }

    @Test
    @DisplayName("a uniform scale splices three channels reading one shared field")
    void uniformScaleSplicesThreeChannelsOverOneField() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("bulk").arm(Side.RIGHT, arm -> arm.scale(1.5)).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver driver = compiled.style().drivers().get("style$bulk$right_arm$scale");
        assertNotNull(driver, "one shared field, spelled outside the channel-token set");
        assertEquals(0.5f, driver.extent(), "the delta rebases against the rest the axes agree on");

        Map<PoseChannel, PoseExpr> arm = compiled.pose().bones().get("right_arm");
        PoseExpr.Op x = assertInstanceOf(PoseExpr.Op.class, arm.get(PoseChannel.X_SCALE));
        PoseExpr.Op y = assertInstanceOf(PoseExpr.Op.class, arm.get(PoseChannel.Y_SCALE));
        PoseExpr.Op z = assertInstanceOf(PoseExpr.Op.class, arm.get(PoseChannel.Z_SCALE));
        assertSame(x.operands().getLast(), y.operands().getLast(), "one interned read per shared field");
        assertSame(y.operands().getLast(), z.operands().getLast());

        assertEquals(1.5f, posed(compiled, mesh, 0).getBones().get("right_arm").getScale(),
            "equal axis values satisfy the uniform fold and land the authored factor");
    }

    @Test
    @DisplayName("an aim solve lands pitch and yaw from the row's own pivot, roll untouched")
    void aimSolvesPitchAndYawFromTheRowsPivot() {
        EntityModelData mesh = humanoid();
        double targetX = 18, targetY = -30, targetZ = -14;
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("hail")
                .arm(Side.RIGHT, arm -> arm.aimAt(targetX, targetY, targetZ))
                .head(head -> head.aimAt(targetX, targetY, targetZ))
                .build(),
            row(mesh, EntityPose.NONE));

        // Recompute the two solves from the row's own pivots - the built value states the
        // TARGET, and each row solves its own angles.
        double[] armSolve = solved(mesh, "right_arm", targetX, targetY, targetZ);
        double[] headSolve = solved(mesh, "head", targetX, targetY, targetZ);

        EntityModelData posed = posed(compiled, mesh, 0);
        assertEquals(armSolve[0] - 90d, posed.getBones().get("right_arm").getRotation().pitch(), 1e-3,
            "a hanging limb rests pointing down and takes the quarter-turn offset");
        assertEquals(armSolve[1], posed.getBones().get("right_arm").getRotation().yaw(), 1e-3);
        assertEquals(headSolve[0], posed.getBones().get("head").getRotation().pitch(), 1e-3,
            "a facing bone takes the solve as-is");
        assertEquals(headSolve[1], posed.getBones().get("head").getRotation().yaw(), 1e-3);
        assertEquals(10f, posed.getBones().get("right_arm").getRotation().roll(),
            "roll stays authored - the solve writes two channels");
    }

    /**
     * The facing solve for one bone - pitch and yaw degrees toward a model-space target.
     */
    private static double @NotNull [] solved(
        @NotNull EntityModelData mesh, @NotNull String bone,
        double targetX, double targetY, double targetZ) {

        double dx = targetX - mesh.getBones().get(bone).getPivot().x();
        double dy = targetY - mesh.getBones().get(bone).getPivot().y();
        double dz = targetZ - mesh.getBones().get(bone).getPivot().z();
        return new double[] {
            Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))),
            Math.toDegrees(Math.atan2(-dx, -dz))
        };
    }

    @Test
    @DisplayName("a pixel offset crosses the flattened factor once and lands whole pixels")
    void pixelOffsetCrossesTheFlattenedFactorOnce() {
        EntityModelData mesh = flattened(2f);
        float authored = mesh.getBones().get("tail").getPivot().y();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("settle").bone("tail", tail -> tail.offset(0, 4, 0)).build(),
            row(mesh, EntityPose.NONE));

        assertEquals(2f, compiled.style().drivers().get("style$settle$tail$y").extent(),
            "the graph speaks authored-over-factor space, so the surface pixels divide once");
        assertEquals(authored + 4f, posed(compiled, mesh, 0).getBones().get("tail").getPivot().y(),
            "and the write-back multiplies the factor once, landing the authored pixels");
    }

    @Test
    @DisplayName("a pixel offset on a parentless bone of a flattened mesh crosses the factor and its anchor once")
    void parentlessOffsetOnAFlattenedMeshLandsWholePixels() {
        EntityModelData mesh = flattened(2f);
        float authored = mesh.getBones().get("body").getPivot().y();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("settle").bone("body", body -> body.offset(0, 4, 0)).build(),
            row(mesh, EntityPose.NONE));

        assertEquals(2f, compiled.style().drivers().get("style$settle$body$y").extent(),
            "the surface pixels divide once, a top-level bone no differently from a child");
        assertEquals(authored + 4f, posed(compiled, mesh, 0).getBones().get("body").getPivot().y(), 1e-4f,
            "and the write-back multiplies the factor once and puts the feet anchor back, landing the authored pixels");
    }

    @Test
    @DisplayName("driver extents narrow to float exactly once at the record boundary")
    void extentsNarrowOnceAtTheDriverBoundary() {
        EntityModelData mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("lean").torso(torso -> torso.pitch(12.34)).build(),
            row(mesh, EntityPose.NONE));

        double exact = Math.toRadians(12.34) - (double) mesh.getBones().get("body").getRotation().pitchRadians();
        assertEquals(Float.floatToIntBits((float) exact),
            Float.floatToIntBits(compiled.style().drivers().get("style$lean$body$x_rot").extent()),
            "the double delta rounds exactly once");
    }

    @Test
    @DisplayName("a woven layer rebases on per-layer fields and spells shared fields the body's way")
    void layerCompileRebasesOnPerLayerFields() {
        EntityModelData body = humanoid();
        EntityModelData wool = humanoid();
        // The layer's arm rests elsewhere, so one absolute target needs a per-row delta.
        wool.getBones().put("right_arm", CompilerFixtures.bone(-5f, 2f, 0f, -15f, 0f, 25f, 1f, null));
        BuiltStyle style = Poses.humanoid("raise")
            .arm(Side.RIGHT, arm -> arm.roll(90).pitchBy(-10))
            .build();

        StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null);
        PoseCompiler.Compiled bodyArm = PoseCompiler.compile(style, row(body, EntityPose.NONE),
            root.child("minecraft:test").child("raise"));
        PoseCompiler.Compiled layerArm = PoseCompiler.compileLayer(style, EntityPose.NONE, wool,
            "$layer1", root.child("minecraft:test").child("raise"));

        assertEquals(List.of("style$raise$$layer1$right_arm$z_rot", "style$raise$right_arm$x_rot"),
            List.copyOf(layerArm.style().drivers().keySet()),
            "a rebased extent is per-row data; the additive delta drives the body's shared spelling");
        PoseExpr.Op additive = assertInstanceOf(PoseExpr.Op.class,
            layerArm.pose().bones().get("right_arm").get(PoseChannel.X_ROT));
        assertEquals("style$raise$right_arm$x_rot",
            assertInstanceOf(PoseExpr.Input.class, additive.operands().getLast()).field());

        LinkedHashMap<String, StyleDriver> merged = new LinkedHashMap<>(bodyArm.style().drivers());
        layerArm.style().drivers().forEach(merged::putIfAbsent);
        PoseStyle installed = new PoseStyle("raise", bodyArm.style().sources(),
            Concurrent.newUnmodifiableMap(merged), bodyArm.style().toggles(), bodyArm.style().age(),
            bodyArm.style().periodTicks());

        float onBody = PoseKit.posed(bodyArm.pose(), body, installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll();
        float onLayer = PoseKit.posed(layerArm.pose(), wool, installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll();
        assertEquals(90f, onBody, 1e-4f);
        assertEquals(90f, onLayer, 1e-4f,
            "two rows' rests need not agree - each lands its own delta on the same absolute target");
    }

    @Test
    @DisplayName("a written bone the mesh lacks is dropped, recorded, and mirrored by one warning")
    void droppedBonesAreRecordedAndWarned() {
        EntityModelData mesh = flattened(1f);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.legged("beg")
                .head(head -> head.pitch(-15))
                .bone("left_hind_leg", leg -> leg.pitch(-70))
                .build(),
            row(mesh, EntityPose.NONE));

        assertEquals(List.of("left_hind_leg"), List.copyOf(compiled.droppedBones()));
        List<StyleDiagnostics.Entry> warned = compiled.diagnostics().entries().stream()
            .filter(entry -> entry.severity() == StyleDiagnostics.Severity.WARN)
            .toList();
        assertEquals(1, warned.size(), "one line carries the whole drop");
        assertTrue(warned.getFirst().message().contains("left_hind_leg"), "naming each missing bone");
        assertTrue(warned.getFirst().message().contains("tail"), "and the roster the mesh declares");
        assertNotNull(compiled.pose().bones().get("head"), "everything else still lowers");
    }

    @Test
    @DisplayName("a clip outrunning the strip window records the truncation warning")
    void longClipRecordsTheTruncationWarning() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("stretch")
                .arm(Side.RIGHT, arm -> arm.timeline(track -> track.swing(Turn.PITCH, -10, 10).over(2.4)))
                .build(),
            row(humanoid(), EntityPose.NONE));

        assertTrue(compiled.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == StyleDiagnostics.Severity.WARN
                    && entry.message().contains("truncat")),
            "frames beyond the window render truncated, and the compile says so");
    }

    @Test
    @DisplayName("every compile records its lowering inventory")
    void compileRecordsItsInventory() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("sit").container(step -> step.offset(0, 7, 0)).build(),
            row(humanoid(), EntityPose.NONE));

        assertTrue(compiled.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == StyleDiagnostics.Severity.INFO
                    && entry.message().contains("inventory")
                    && entry.path().endsWith("/compile")),
            "driver, field, clip-channel and container-step counts the compiler already holds");
    }

    @Test
    @DisplayName("recording is observation only - a quiet and a console compile emit the same output")
    void diagnosticsAreNeverLoadBearing() {
        EntityModelData mesh = humanoid();
        BuiltStyle style = Poses.humanoid("wave")
            .arm(Side.RIGHT, arm -> arm.roll(30)
                .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
            .build();

        PoseCompiler.Compiled quiet = PoseCompiler.compile(style, row(mesh, EntityPose.NONE),
            StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null).child("quiet"));
        PoseCompiler.Compiled loud = PoseCompiler.compile(style, row(mesh, EntityPose.NONE),
            StyleDiagnostics.root("styles", StyleDiagnostics.Output.CONSOLE, null).child("loud"));

        assertEquals(quiet.style().drivers(), loud.style().drivers());
        assertEquals(quiet.droppedBones(), loud.droppedBones());
        assertEquals(
            posed(quiet, mesh, 3).getBones(),
            PoseKit.posed(loud.pose(), mesh, loud.style(), PERIOD, 3).getBones(),
            "bit-identical compiled output under either mode");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The compiled style posed onto its target mesh at one tick.
     */
    private static @NotNull EntityModelData posed(
        @NotNull PoseCompiler.Compiled compiled, @NotNull EntityModelData mesh, int tick) {

        return PoseKit.posed(compiled.pose(), mesh, compiled.style(), PERIOD, tick);
    }

    /**
     * A resolved row nothing drives - what any other selection answers about the woven graph.
     */
    private static @NotNull PoseStyle undriven(@NotNull String id) {
        return new PoseStyle(id, Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty(),
            Optional.empty());
    }

    /**
     * The compile-time rebase arithmetic, replicated bit for bit.
     */
    private static float extentOf(double absoluteDegrees, float restRadians) {
        return (float) (Math.toRadians(absoluteDegrees) - (double) restRadians);
    }

    /**
     * The evaluator's write-back arithmetic, replicated bit for bit.
     */
    private static float degreesOf(float restRadians, float extentRadians) {
        return (float) Math.toDegrees((float) ((double) restRadians + (double) extentRadians));
    }

}
