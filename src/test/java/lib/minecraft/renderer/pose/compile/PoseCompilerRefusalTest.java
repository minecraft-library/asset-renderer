package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
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

import java.util.Optional;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.boneWrite;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.constant;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.flattened;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.input;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(1, compiled.droppedBones().size(),
            () -> "the reach that answered nothing is what drops: " + compiled.droppedBones());
    }

    // ------------------------------------------------------------------------------------

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
