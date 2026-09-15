package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseOperator;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Ease;
import lib.minecraft.renderer.pose.author.Gait;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Side;
import lib.minecraft.renderer.pose.author.Turn;
import lib.minecraft.renderer.pose.install.StyleRegistrar;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.boneWrite;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.chained;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.constant;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.crawler;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.flattened;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.fused;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.fusedRows;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.halfFused;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.input;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.row;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.walker;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compile-time refusals - each an {@link IllegalArgumentException} thrown where the author
 * is, front-running the render failure it would otherwise become, with its context recorded
 * beside the throw.
 */
@DisplayName("the compiler refuses authored content the runtime cannot honour")
class PoseCompilerRefusalTest {

    @Test
    @DisplayName("loop() and once() mixed across limbs refuse - a clip loops or holds as one")
    void mixedLoopingRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("wave")
            .arm(Side.RIGHT, arm -> arm.timeline(track -> track.swing(Turn.ROLL, -10, 10)))
            .arm(Side.LEFT, arm -> arm.timeline(track -> track.swing(Turn.ROLL, 10, -10).once()))
            .build());
        assertTrue(refusal.getMessage().contains("loop() and once()"), refusal.getMessage());
    }

    @Test
    @DisplayName("a duplicate keyframe time on one channel refuses")
    void duplicateKeyframeTimeRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("twitch")
            .arm(Side.RIGHT, arm -> arm.timeline(track -> track
                .keyframe(0.2, 10, 0, 0)
                .keyframe(0.2, -10, 0, 0)))
            .build());
        assertTrue(refusal.getMessage().contains("0.2"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("right_arm"), refusal.getMessage());
    }

    @Test
    @DisplayName("two waves on one channel refuse - one field holds one driver")
    void twoWavesOnOneChannelRefuse() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("wobble")
            .head(head -> head.sway(Turn.ROLL, -5, 5).spin(Turn.ROLL, 360))
            .build());
        assertTrue(refusal.getMessage().contains("one field holds one driver"), refusal.getMessage());
    }

    @Test
    @DisplayName("an absolute write over a driven base refuses, and its context records before the throw")
    void absoluteOverDrivenBaseRefuses() {
        EntityPose shipped = boneWrite("right_arm", PoseChannel.X_ROT, input("walkAnimationPos"));
        BuiltStyle style = Poses.humanoid("march")
            .keepStride()
            .arm(Side.RIGHT, arm -> arm.pitch(-40))
            .build();
        StyleDiagnostics scope = StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null)
            .child("minecraft:test").child("march");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> PoseCompiler.compile(style, row(humanoid(), shipped), scope));
        assertTrue(refusal.getMessage().contains("walkAnimationPos"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("pitchBy, yawBy and rollBy compose with a live base"),
            refusal.getMessage());
        assertEquals(1, scope.count(StyleDiagnostics.Severity.ERROR),
            "the entry is the post-mortem, recorded beside the throw");
        assertEquals(refusal.getMessage(), scope.entries().stream()
                .filter(entry -> entry.severity() == StyleDiagnostics.Severity.ERROR)
                .findFirst().orElseThrow().message(),
            "carrying the exact thrown message");
    }

    @Test
    @DisplayName("a scale over a driven base names no remedy, because the verb set holds none")
    void drivenScaleBaseNamesNoRemedy() {
        EntityPose shipped = boneWrite("right_arm", PoseChannel.X_SCALE, input("walkAnimationSpeed"));
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("swell")
                .keepStride()
                .arm(Side.RIGHT, arm -> arm.scale(1.5))
                .build(),
            humanoid(), shipped);
        assertTrue(refusal.getMessage().contains("walkAnimationSpeed"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("no additive scale spelling exists"),
            refusal.getMessage());
    }

    @Test
    @DisplayName("a uniform scale over divergent axis rests refuses")
    void divergentScaleRestsRefuse() {
        EntityPose shipped = boneWrite("right_arm", PoseChannel.X_SCALE, constant(1.2d));
        IllegalArgumentException refusal = refusalOf(
            Poses.humanoid("bulk").arm(Side.RIGHT, arm -> arm.scale(1.5)).build(),
            humanoid(), shipped);
        assertTrue(refusal.getMessage().contains("divergent"), refusal.getMessage());
    }

    @Test
    @DisplayName("an aim whose target coincides with the pivot refuses - no direction to aim")
    void aimAtOwnPivotRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("stare")
            .arm(Side.RIGHT, arm -> arm.aimAt(-5, 2, 0))
            .build());
        assertTrue(refusal.getMessage().contains("no direction"), refusal.getMessage());
    }

    @Test
    @DisplayName("a pose that could not be read refuses to compile")
    void unreadablePoseRefuses() {
        EntityPose unreadable = new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(),
            Optional.of("the walk could not read this model"));
        IllegalArgumentException refusal = refusalOf(
            Poses.humanoid("sit").torso(torso -> torso.pitch(10)).build(),
            humanoid(), unreadable);
        assertTrue(refusal.getMessage().contains("could not be read"), refusal.getMessage());
    }

    @Test
    @DisplayName("scale on a container step refuses - it reaches no bone below the seat")
    void containerScaleRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("grow")
            .container(step -> step.scale(2))
            .build());
        assertTrue(refusal.getMessage().contains("scales a container step"), refusal.getMessage());
    }

    @Test
    @DisplayName("an aim on a container step refuses - a step has no pivot to aim from")
    void containerAimRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("face")
            .container(step -> step.aimAt(1, 2, 3))
            .build());
        assertTrue(refusal.getMessage().contains("no pivot"), refusal.getMessage());
    }

    @Test
    @DisplayName("a timeline on a container step refuses - a clip channel names a bone")
    void containerTimelineRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("rock")
            .container(step -> step.timeline(track -> track.swing(Turn.ROLL, -5, 5)))
            .build());
        assertTrue(refusal.getMessage().contains("names a bone"), refusal.getMessage());
    }

    @Test
    @DisplayName("a container position channel on a flattened mesh refuses, hover included")
    void containerPositionOnFlattenedMeshRefuses() {
        IllegalArgumentException stepped = refusalOf(
            Poses.custom("scoot").container(step -> step.offset(0, 3, -5)).build(),
            flattened(2f), EntityPose.NONE);
        assertTrue(stepped.getMessage().contains("flattened"), stepped.getMessage());

        IllegalArgumentException hovered = refusalOf(
            Poses.custom("float").hover(8, 0).build(),
            flattened(2f), EntityPose.NONE);
        assertTrue(hovered.getMessage().contains("flattened"), hovered.getMessage());
    }

    @Test
    @DisplayName("a rotation-only container step on a flattened mesh passes where positions refuse")
    void rotationOnlyContainerStepPassesOnFlattenedMesh() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("rear").container(step -> step.pitch(-30)).build(),
            row(flattened(2f), EntityPose.NONE));
        assertEquals(1, compiled.pose().container().size(),
            "an unwritten position leaves the seat at its pivot");
    }

    @Test
    @DisplayName("two container steps writing one channel refuse - one field holds one driver")
    void twoStepsOnOneContainerChannelRefuse() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("stack")
            .container(step -> step.pitch(-10))
            .container(step -> step.pitchBy(5))
            .build());
        assertTrue(refusal.getMessage().contains("twice"), refusal.getMessage());
    }

    @Test
    @DisplayName("a raw float literal no float holds exactly refuses")
    void inexactFloatLiteralRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.custom("hatch")
            .expr("head", PoseChannel.X_ROT, new PoseExpr.Const(0.1d, PoseOperator.Width.FLOAT))
            .build());
        assertTrue(refusal.getMessage().contains("float"), refusal.getMessage());
    }

    @Test
    @DisplayName("a raw graph reading another style's field refuses")
    void foreignStyleFieldRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.custom("hatch")
            .expr("head", PoseChannel.X_ROT, input("style$other$head$x_rot"))
            .build());
        assertTrue(refusal.getMessage().contains("style$other$head$x_rot"), refusal.getMessage());
    }

    @Test
    @DisplayName("a raw graph reading its own gate passes the namespace check, and rides the gate the lowering coins")
    void ownStyleFieldPasses() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(Poses.custom("hatch")
                .expr("head", PoseChannel.X_ROT, input("style$hatch$head$x_rot"))
                .build(),
            row(humanoid(), EntityPose.NONE));
        PoseExpr.Select gated = (PoseExpr.Select) compiled.pose().bones().get("head").get(PoseChannel.X_ROT);
        assertEquals("style$hatch$head$x_rot", ((PoseExpr.Input) gated.whenTrue()).field());
        assertEquals("style$hatch", ((PoseExpr.Input) gated.condition().left()).field(),
            "the splice rides this style's own gate");
    }

    @Test
    @DisplayName("a raw bone read of a bone the mesh does not declare refuses")
    void undeclaredBoneReadRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.custom("hatch")
            .expr("head", PoseChannel.X_ROT, new PoseExpr.BoneRead("wing", PoseChannel.X_ROT))
            .build());
        assertTrue(refusal.getMessage().contains("wing"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("throws at render"), refusal.getMessage());
    }

    @Test
    @DisplayName("a raw operation with the wrong operand count refuses")
    void operatorArityRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.custom("hatch")
            .expr("head", PoseChannel.X_ROT,
                new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(constant(1d))))
            .build());
        assertTrue(refusal.getMessage().contains("dadd"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("takes 2"), refusal.getMessage());
    }

    @Test
    @DisplayName("a non-positive period refuses")
    void nonPositivePeriodRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("breathe")
            .torso(torso -> torso.sway(Turn.PITCH, -2, 2))
            .period(-1)
            .build());
        assertTrue(refusal.getMessage().contains("not positive"), refusal.getMessage());
    }

    @Test
    @DisplayName("a period the eight-frame strip does not tile refuses")
    void untileablePeriodRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("breathe")
            .torso(torso -> torso.sway(Turn.PITCH, -2, 2))
            .period(1.0)
            .build());
        assertTrue(refusal.getMessage().contains("does not tile"), refusal.getMessage());
    }

    @Test
    @DisplayName("a period on a still style refuses - only a moving style reads one")
    void periodOnStillStyleRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.humanoid("statue")
            .torso(torso -> torso.pitch(10))
            .period(2.4)
            .build());
        assertTrue(refusal.getMessage().contains("holds still"), refusal.getMessage());
    }

    @Test
    @DisplayName("two ranks one mesh answers with a single row refuse")
    void aliasedRanksRefuse() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("crouch")
            .legs(Rank.FRONT, leg -> leg.pitch(-20))
            .legs(Rank.HIND, leg -> leg.pitch(20))
            .build());
        assertTrue(refusal.getMessage().contains("answers with one row"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("FRONT"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("HIND"), refusal.getMessage());
    }

    @Test
    @DisplayName("one rank stanced over both sides is one name and not two")
    void oneRankOverBothSidesPasses() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.legged("brace").legs(Rank.FRONT, leg -> leg.pitch(-20)).build(),
            row(humanoid(), EntityPose.NONE));
        assertEquals(2, compiled.style().drivers().size(),
            "a row verb captures an authored half and a derived one, both naming the one rank");
    }

    @Test
    @DisplayName("a rank the mesh has no row for is passed over rather than collided with")
    void absentRankDoesNotCollide() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(Poses.legged("reach")
                .legs(Rank.FRONT, leg -> leg.pitch(-20))
                .legs(Rank.SECOND, leg -> leg.pitch(10))
                .build(),
            row(humanoid(), EntityPose.NONE));
        assertEquals(2, compiled.style().drivers().size(),
            "an interior rank addresses nothing on a one-row mesh, and nothing already held");
        assertEquals(1, compiled.drops().size(),
            () -> "the reach that answered nothing is what drops: " + compiled.drops());
    }

    @Test
    @DisplayName("a timing number at a rank the mesh has no row for drops rather than refusing")
    void anAbsentRankOnATimingNumberDrops() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(Poses.legged("amble")
                .gait(gait -> gait
                    .step(Rank.FRONT, leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20)))
                    .phase(Rank.SECOND, 0.25)
                    .gain(Rank.THIRD, 0.5))
                .build(),
            row(humanoid(), EntityPose.NONE));

        assertEquals(1, compiled.pose().clips().size(),
            "a number keyed on a row this mesh does not carry states nothing about any leg, and "
                + "a chain reaching one subject's rows must not refuse outright on the next");
        assertEquals(List.of("phase(SECOND)", "gain(THIRD)"), described(compiled.drops()),
            () -> "but it is reported, in the order a rank ladder reads rather than a hash "
                + "order: " + compiled.drops());
    }

    @Test
    @DisplayName("a timing number at an absent rank refuses a strict install and not a tolerant one")
    void anAbsentRankForksOnStrictness() {
        BuiltStyle amble = Poses.legged("amble")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4)))
                .phase(Rank.SECOND, 0.25))
            .build();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> StyleRegistrar.ofShipped().add("minecraft:wolf", amble));
        assertTrue(refusal.getMessage().contains("phase(SECOND)"), refusal.getMessage());

        assertEquals(4, StyleRegistrar.ofShipped().addTolerant("minecraft:wolf", amble)
                .definitions().get("minecraft:wolf").pose().clips().getLast().clip()
                .channels().size(),
            "while the tolerant path installs it over all four legs, which is the escape a "
                + "chain written to run over two, four and eight legs alike needs");
    }

    @Test
    @DisplayName("a mesh naming no leg at all reports no absent rank, having no rows to be absent from")
    void aLeglessMeshReportsNoAbsentRank() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(Poses.legged("amble")
                .gait(gait -> gait
                    .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4)))
                    .phase(Rank.SECOND, 0.25)
                    .gain(Rank.HIND, 0.5))
                .build(),
            row(new EntityModelData(), EntityPose.NONE));

        assertEquals(List.of("selector every row RIGHT ROOT"), described(compiled.drops()),
            () -> "the selector's own empty resolution reports the subject, and reporting every "
                + "rank beside it would report the mesh rather than the chain: "
                + compiled.drops());
    }

    @Test
    @DisplayName("a phase over a swayed shape refuses - a wave carries no offset of its own")
    void phaseOverASwayRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("amble")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.sway(Turn.PITCH, -20, 20))
                .phase(Rank.FRONT, 0.25))
            .build());
        assertTrue(refusal.getMessage().contains("carries no offset of its own"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("timeline"), refusal.getMessage());
    }

    @Test
    @DisplayName("a phase over an unranked shape refuses too - the shape reaches the phased row")
    void phaseOverAnUnrankedSwayRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("amble")
            .gait(gait -> gait
                .step(leg -> leg.sway(Turn.PITCH, -20, 20))
                .phase(Rank.FRONT, 0.25))
            .build());
        assertTrue(refusal.getMessage().contains("carries no offset of its own"), refusal.getMessage());
    }

    @Test
    @DisplayName("a whole number of cycles is no offset, so what an offset refuses it does not")
    void aWholeCycleIsNoOffset() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(Poses.legged("amble")
                .gait(gait -> gait
                    .step(Rank.FRONT, leg -> leg.timeline(track -> track
                        .swing(Turn.PITCH, -20, 20).over(0.4).ease(Ease.SMOOTH)))
                    .phase(Rank.FRONT, 1.0))
                .build(),
            row(humanoid(), EntityPose.NONE));
        assertEquals(1, compiled.pose().clips().size(),
            "the wrap takes a whole cycle to zero, so it states what no phase at all states");
    }

    @Test
    @DisplayName("a phase over a track keying one instant twice refuses, as the unphased track does")
    void phaseOverACollidingTrackRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("amble")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.timeline(track -> track
                    .keyframe(0, -30, 0, 0)
                    .keyframe(0.2, 10, 0, 0)
                    .keyframe(0.2, -10, 0, 0)
                    .keyframe(0.4, -30, 0, 0)
                    .over(0.4)))
                .phase(Rank.FRONT, 0.25))
            .build());
        assertTrue(refusal.getMessage().contains("ascend strictly"), refusal.getMessage());
    }

    @Test
    @DisplayName("a phase over two motions writing one target refuses, as the unphased pair does")
    void phaseOverTwoMotionsOnOneTargetRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("amble")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, 0, 35)
                    .swing(Turn.YAW, 0, 10)
                    .over(0.4)))
                .phase(Rank.FRONT, 0.25))
            .build());
        assertTrue(refusal.getMessage().contains("ascend strictly"), refusal.getMessage());
    }

    @Test
    @DisplayName("a phase over a smoothed track refuses - re-timing a spline states a different curve")
    void phaseOverASmoothedTrackRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("amble")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, -20, 20).over(0.4).ease(Ease.SMOOTH)))
                .phase(Rank.FRONT, 0.25))
            .build());
        assertTrue(refusal.getMessage().contains("smoothed track"), refusal.getMessage());
    }

    @Test
    @DisplayName("a phase over a clip that holds rather than loops refuses")
    void phaseOverAHeldClipRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("amble")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, -20, 20).over(0.4).once()))
                .phase(Rank.FRONT, 0.25))
            .build());
        assertTrue(refusal.getMessage().contains("holds rather than loops"), refusal.getMessage());
    }

    @Test
    @DisplayName("an opposed side over a swayed shape refuses, naming the side rather than a row")
    void opposeOverASwayRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("pace")
            .gait(gait -> gait
                .step(leg -> leg.sway(Turn.PITCH, -20, 20))
                .oppose(0.5))
            .build());
        assertTrue(refusal.getMessage().contains("an opposed far side"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("carries no offset of its own"), refusal.getMessage());
    }

    @Test
    @DisplayName("a track's closure is measured against the script's own window, never the target row's period")
    void closureIsMeasuredAgainstTheScriptsWindowAndNotTheRows() {
        // The default window is 24 ticks, so a track with no length of its own closes at 1.2
        // seconds whatever the row says. The second row's catalog period is twice that: read off
        // the ROW, the first style would refuse there and pass at 24, and the second the reverse.
        double defaultWindow = 1.2d;
        for (int periodTicks : new int[] {24, 48}) {
            assertDoesNotThrow(
                () -> PoseCompiler.compile(closingAt(defaultWindow), row(walker(), EntityPose.NONE, periodTicks)),
                "a track closing at the default window installs on a row at " + periodTicks + " ticks");
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> PoseCompiler.compile(closingAt(defaultWindow * 2d), row(walker(), EntityPose.NONE, periodTicks)),
                "and one closing at twice it refuses there too");
            assertTrue(refusal.getMessage().contains("does not close"), refusal.getMessage());
        }
    }

    @Test
    @DisplayName("an opposed side over a track that does not close refuses, as a phase does")
    void opposeOverAnOpenTrackRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("pace")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track
                    .keyframe(0, -30, 0, 0)
                    .keyframe(0.4, 10, 0, 0)
                    .over(0.4)))
                .oppose(0.25))
            .build());
        assertTrue(refusal.getMessage().contains("does not close"), refusal.getMessage());
    }

    @Test
    @DisplayName("a whole cycle of opposition is no offset, so what one refuses it does not")
    void aWholeCycleOfOppositionIsNoOffset() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(Poses.legged("pace")
                .gait(gait -> gait
                    .step(leg -> leg.sway(Turn.PITCH, -20, 20))
                    .oppose(1.0))
                .build(),
            row(humanoid(), EntityPose.NONE));
        assertEquals(2, compiled.style().drivers().size(),
            "the wrap takes it to zero, so the swayed shape keeps its driver rather than "
                + "being asked for a timeline");
    }

    @Test
    @DisplayName("an opposed side on a mesh whose rows carry no side refuses, naming the bones")
    void opposeOnAnUnsidedRowRefuses() {
        BuiltStyle pace = Poses.legged("pace")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4)))
                .oppose(0.5))
            .build();

        for (EntityModelData mesh : List.of(fused(), fusedRows())) {
            IllegalArgumentException refusal = refusalOf(pace, mesh, EntityPose.NONE);
            assertTrue(refusal.getMessage().contains("carry no side"), refusal::getMessage);
            assertTrue(refusal.getMessage().contains("an opposed far side"), refusal::getMessage);
        }
    }

    @Test
    @DisplayName("a shared far side refuses there too - there is no far side to share it with")
    void shareOnAnUnsidedRowRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("glide")
            .gait(gait -> gait.share().step(leg -> leg.rollBy(8)))
            .build(), fused(), EntityPose.NONE);

        assertTrue(refusal.getMessage().contains("a shared far side"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("feet"), refusal.getMessage());
    }

    @Test
    @DisplayName("a side-keyed verb is what refuses, not a fused row itself")
    void afusedRowTakesAGaitThatSaysNothingAboutSides() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(Poses.legged("hover")
                .gait(gait -> gait
                    .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4)))
                    .plant(0.25))
                .build(),
            row(fused(), EntityPose.NONE));

        assertEquals(List.of("0.0 " + (float) Math.toRadians(-20),
                "0.1 " + (float) Math.toRadians(-20),
                "0.25 " + (float) Math.toRadians(20),
                "0.4 " + (float) Math.toRadians(-20)),
            compiled.pose().clips().getLast().clip().channels().getFirst().keyframes().stream()
                .map(frame -> frame.timeSeconds() + " " + frame.x())
                .toList(),
            "one bone paints both legs of the row and takes one copy of the shape, which is "
                + "the whole point of the row - so a cycle saying nothing about the two sides "
                + "walks it exactly as written");
    }

    @Test
    @DisplayName("a plant of the whole cycle refuses - a shape that never travels is no cycle")
    void aPlantOfTheWholeCycleRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("amble")
            .gait(gait -> gait
                .plant(1.0)
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4))))
            .build());
        assertTrue(refusal.getMessage().contains("less than all of it"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("1.0"), refusal.getMessage());
    }

    @Test
    @DisplayName("a plant of less than none of the cycle refuses on the same words")
    void aNegativePlantRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("amble")
            .gait(gait -> gait
                .plant(-0.1)
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4))))
            .build());
        assertTrue(refusal.getMessage().contains("at least none of it"), refusal.getMessage());
    }

    @Test
    @DisplayName("a plant refuses against the script, so a legless subject refuses with the rest")
    void aPlantRefusesOnEverySubject() {
        BuiltStyle amble = Poses.legged("amble")
            .gait(gait -> gait
                .plant(1.5)
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4))))
            .build();

        for (EntityModelData mesh : List.of(humanoid(), walker(), fused(), new EntityModelData()))
            assertTrue(refusalOf(amble, mesh, EntityPose.NONE).getMessage()
                    .contains("less than all of it"),
                "a share of a cycle is a number the author wrote, so no mesh has a say in it");
    }

    @Test
    @DisplayName("a trot on anything but two rows refuses - a diagonal has no reading there")
    void aTrotOffTwoRowsRefuses() {
        BuiltStyle canter = trotted();

        IllegalArgumentException onOneRow = refusalOf(canter, humanoid(), EntityPose.NONE);
        assertTrue(onOneRow.getMessage().contains("no unique reading of"), onOneRow.getMessage());
        assertTrue(onOneRow.getMessage().contains("'1' leg row(s)"), onOneRow.getMessage());

        IllegalArgumentException onFourRows = refusalOf(canter, crawler(), EntityPose.NONE);
        assertTrue(onFourRows.getMessage().contains("'4' leg row(s)"), onFourRows.getMessage());
    }

    @Test
    @DisplayName("a trot on two rows one bone paints whole refuses, naming the bones")
    void aTrotOnUnsidedRowsRefuses() {
        IllegalArgumentException refusal = refusalOf(trotted(), fusedRows(), EntityPose.NONE);

        assertTrue(refusal.getMessage().contains("carry no side"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("front_legs"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("back_legs"), refusal.getMessage());
    }

    @Test
    @DisplayName("a trot on a subject with no legs at all keeps the drop a tolerant install allows")
    void aTrotOnALeglessMeshDoesNotRefuse() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(trotted(),
            row(new EntityModelData(), EntityPose.NONE));

        assertEquals(1, compiled.pose().clips().size(),
            "no legs is a subject this cycle is not for rather than the wrong legs, which is "
                + "the drop the strict and tolerant install paths already fork on");
    }

    @Test
    @DisplayName("a trot beside an opposed side refuses whatever the two numbers are")
    void aTrotBesideAnOpposedSideRefuses() {
        for (double opposed : List.of(0.3, 0.5))
            assertTrue(refusalOf(Poses.legged("muddle")
                    .gait(gait -> gait
                        .step(leg -> leg.timeline(track -> track
                            .swing(Turn.PITCH, -20, 20).over(0.4)))
                        .trot(0.5)
                        .oppose(opposed))
                    .build(), walker(), EntityPose.NONE)
                    .getMessage().contains("neither a diagonal nor a pace"),
                "the two write one number, so the pair refuses on agreeing values as on "
                    + "disagreeing ones");
    }

    @Test
    @DisplayName("a trot over a swayed shape refuses, naming the trot")
    void aTrotOverASwayRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("canter")
            .gait(gait -> gait.step(leg -> leg.sway(Turn.PITCH, -20, 20)).trot(0.5))
            .build(), walker(), EntityPose.NONE);

        assertTrue(refusal.getMessage().contains("a trot"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("carries no offset of its own"), refusal.getMessage());
    }

    @Test
    @DisplayName("a trailing chain on legs that are one bone refuses - there is nothing to lag")
    void aTrailWithNoSegmentsRefuses() {
        BuiltStyle glide = Poses.legged("glide")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -32, 32).over(0.8)))
                .trail(0.5, 0.5))
            .build();

        for (EntityModelData mesh : List.of(humanoid(), walker(), crawler())) {
            IllegalArgumentException refusal = refusalOf(glide, mesh, EntityPose.NONE);
            assertTrue(refusal.getMessage().contains("no bone below the root"),
                refusal::getMessage);
        }
        assertEquals(1, PoseCompiler.compile(glide, row(chained(), EntityPose.NONE))
                .pose().clips().size(),
            "and a mesh whose legs do carry bones below their roots compiles, because there is "
                + "something for the lag and the fade to be about");
    }

    @Test
    @DisplayName("a trailing chain over a swayed shape refuses, naming the chain")
    void aTrailOverASwayRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("glide")
            .gait(gait -> gait.step(leg -> leg.sway(Turn.PITCH, -32, 32)).trail(0.5, 0.5))
            .build(), chained(), EntityPose.NONE);

        assertTrue(refusal.getMessage().contains("a trailing chain"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("carries no offset of its own"), refusal.getMessage());
    }

    @Test
    @DisplayName("one row with no side is enough to refuse a verb speaking for every row")
    void oneUnsidedRowAmongSidedOnesRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("pace")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4)))
                .oppose(0.5))
            .build(), halfFused(), EntityPose.NONE);

        assertTrue(refusal.getMessage().contains("back_legs"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains("right_front_leg"),
            () -> "the sided row answers the verb and is not what refuses it: "
                + refusal.getMessage());
    }

    @Test
    @DisplayName("a gait number that is not a number refuses before it can key a frame at no time")
    void anUnrealGaitNumberRefuses() {
        List<UnaryOperator<Gait>> unreal = List.of(
            gait -> gait.phase(Rank.FRONT, Double.NaN),
            gait -> gait.gain(Rank.HIND, Double.POSITIVE_INFINITY),
            gait -> gait.oppose(Double.NaN),
            gait -> gait.trot(Double.NEGATIVE_INFINITY),
            gait -> gait.plant(Double.NaN),
            gait -> gait.trail(Double.NaN, 0.5),
            gait -> gait.trail(0.5, Double.POSITIVE_INFINITY));

        for (UnaryOperator<Gait> written : unreal) {
            BuiltStyle style = Poses.legged("garbled")
                .gait(gait -> written.apply(gait.step(leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, -20, 20).over(0.4)))))
                .build();
            IllegalArgumentException refusal = refusalOf(style, walker(), EntityPose.NONE);
            assertTrue(refusal.getMessage().contains("is a real one")
                    || refusal.getMessage().contains("at least none of it"),
                () -> "a value outside the reals passes the whole-cycle test, the wrap and the "
                    + "clip's own duplicate-frame test, because it is equal to nothing including "
                    + "itself: " + refusal.getMessage());
        }
    }

    @Test
    @DisplayName("a shape stated over every row beside one stated for a row refuses")
    void anUnkeyedShapeBesideAKeyedOneRefuses() {
        IllegalArgumentException refusal = refusalOf(Poses.legged("amble")
            .gait(gait -> gait
                .step(leg -> leg.sway(Turn.PITCH, -20, 20))
                .step(Rank.FRONT, leg -> leg.sway(Turn.PITCH, -35, 35)))
            .build());
        assertTrue(refusal.getMessage().contains("one field holds one driver"), refusal.getMessage());
    }

    // ------------------------------------------------------------------------------------

    /**
     * One cycle running every leg with the one across the body from it, half a cycle apart.
     */
    private static @NotNull BuiltStyle trotted() {
        return Poses.legged("canter")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4)))
                .trot(0.5))
            .build();
    }

    /**
     * The readings of a compile's unreached addresses, in order.
     *
     * @param drops what the compile reported as reaching nothing
     * @return each address as the message would name it
     */
    private static @NotNull List<String> described(
        @NotNull List<PoseCompiler.Unreached> drops) {

        return drops.stream().map(PoseCompiler.Unreached::describe).toList();
    }

    /**
     * A gait whose timeline closes over the given seconds, offset so the closure rule reads it.
     *
     * @param seconds where the timeline's last keyframe sits, its first resting at zero
     * @return the built style
     */
    private static @NotNull BuiltStyle closingAt(double seconds) {
        return Poses.legged("amble")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.timeline(track -> track
                    .keyframe(0, -20, 0, 0)
                    .keyframe(seconds / 2d, 20, 0, 0)
                    .keyframe(seconds, -20, 0, 0)))
                .phase(Rank.FRONT, 0.25))
            .build();
    }

    /**
     * Compiles against a fresh humanoid row and hands back the refusal.
     */
    private static @NotNull IllegalArgumentException refusalOf(@NotNull BuiltStyle style) {
        return refusalOf(style, humanoid(), EntityPose.NONE);
    }

    /**
     * Compiles against the given mesh and shipped pose and hands back the refusal.
     */
    private static @NotNull IllegalArgumentException refusalOf(
        @NotNull BuiltStyle style, @NotNull EntityModelData mesh, @NotNull EntityPose shipped) {

        return assertThrows(IllegalArgumentException.class,
            () -> PoseCompiler.compile(style, row(mesh, shipped)));
    }

}
