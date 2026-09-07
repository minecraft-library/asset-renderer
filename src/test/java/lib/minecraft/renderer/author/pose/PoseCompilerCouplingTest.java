package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static lib.minecraft.renderer.author.pose.CompilerFixtures.bone;
import static lib.minecraft.renderer.author.pose.CompilerFixtures.constant;
import static lib.minecraft.renderer.author.pose.CompilerFixtures.pose;
import static lib.minecraft.renderer.author.pose.CompilerFixtures.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two couplings the compiler carries without the author spelling them: a seat, read off the
 * shipped silhouettes and cascaded as a held displacement of the follower's pivot, and a joint,
 * an anatomical name landing on the articulation the shipped pose turns for it.
 */
@DisplayName("the compiler carries seats and resolves joints from the shipped evidence")
class PoseCompilerCouplingTest {

    /** The catalog period every fixture frames against. */
    private static final int PERIOD = 24;

    /** A quarter turn in radians, the body's pitch in every sitting fixture here. */
    private static final float QUARTER = (float) Math.toRadians(45);

    /**
     * A five-unit reach along the body's axis turned a quarter turn - it rises by this much and
     * comes forward by this much, the pitch carrying local z onto -y and +z.
     */
    private static final float REACH = (float) (5d * Math.sin(Math.toRadians(45)));

    // ------------------------------------------------------------------------------------
    // seats
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a seat")
    class Seat {

        @Test
        @DisplayName("carries the follower's pivot to where the leader's held stance puts its frame, position only")
        void carriesTheFollowerPositionOnly() {
            EntityModelData mesh = bodyAndTail();
            EntityPose shipped = sitting(mesh);
            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.quadruped("sit").body(body -> body.pitch(90)).build(), row(mesh, shipped));

            EntityModelData posed = PoseKit.posed(compiled.pose(), mesh, compiled.style(), PERIOD, 0);
            assertPivot(posed.getBones().get("tail").getPivot(), 0f, 5f, 0f,
                "five units along a body pitched a quarter turn is five units up the world");
            assertEquals(0f, posed.getBones().get("tail").getRotation().pitch(), 0f,
                "the tail keeps its own pitch");
            assertTrue(compiled.style().drivers().containsKey("style$sit$tail$y"), "a held displacement on y");
            assertTrue(compiled.style().drivers().containsKey("style$sit$tail$z"), "and on z");
            assertNull(compiled.style().drivers().get("style$sit$tail$x"), "and nothing sideways");
            PoseExpr.Op carry = (PoseExpr.Op) compiled.pose().bones().get("tail").get(PoseChannel.Y);
            assertEquals(new PoseExpr.BoneRead("tail", PoseChannel.Y), carry.operands().getFirst(),
                "the carry splices over the read of the authored pivot, as a spelled offset would");
            assertTrue(shipped.bones().isEmpty(), "and the shipped pose is untouched");
            assertTrue(compiled.diagnostics().entries().stream().anyMatch(entry ->
                    entry.severity() == StyleDiagnostics.Severity.INFO
                        && entry.message().startsWith("seat: 'tail' rides 'body'")),
                "the carry records what it did");
        }

        @Test
        @DisplayName("carries nothing when the leader stands where it rests")
        void carriesNothingForAnUnstancedLeader() {
            EntityModelData mesh = bodyAndTail();
            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.quadruped("nod").head(head -> head.pitch(10)).build(), row(mesh, sitting(mesh)));

            assertTrue(compiled.style().drivers().keySet().stream().noneMatch(field -> field.contains("$tail$")),
                "no field is spelled for a follower nothing carried");
        }

        @Test
        @DisplayName("adds to an offset the author spelled on the follower")
        void addsToAnAuthoredOffset() {
            EntityModelData mesh = bodyAndTail();
            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.quadruped("sit").body(body -> body.pitch(90)).tail(tail -> tail.offset(1, 0, 0)).build(),
                row(mesh, sitting(mesh)));

            EntityModelData posed = PoseKit.posed(compiled.pose(), mesh, compiled.style(), PERIOD, 0);
            assertPivot(posed.getBones().get("tail").getPivot(), 1f, 5f, 0f,
                "the authored sideways offset rides the carry");
        }

        @Test
        @DisplayName("carries along a chain - a follower seated on a follower moves with both")
        void carriesAlongAChain() {
            EntityModelData mesh = bodyAndTail();
            mesh.getBones().put("tail2", bone(0f, 10f, 10f, 0f, 0f, 0f, 1f, null));
            EntityPose shipped = withStates(Map.of(
                "isSitting=true", Map.of(
                    "body", Map.of(PoseChannel.X_ROT, constant(QUARTER)),
                    "tail", Map.of(PoseChannel.X_ROT, constant(QUARTER),
                        PoseChannel.Y, constant(10f - REACH), PoseChannel.Z, constant(REACH)),
                    "tail2", Map.of(PoseChannel.Y, constant(10f - 2 * REACH), PoseChannel.Z, constant(2 * REACH))),
                "isWagging=true", Map.of(
                    "tail", Map.of(PoseChannel.X_ROT, constant(QUARTER)),
                    "tail2", Map.of(PoseChannel.Y, constant(10f - REACH), PoseChannel.Z, constant(5f + REACH)))));
            Seats.Derived derived = Seats.derive(shipped, mesh);
            assertEquals("body", derived.seats().get("tail").leader());
            assertEquals("tail", derived.seats().get("tail2").leader());

            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.quadruped("sit").body(body -> body.pitch(90)).build(), row(mesh, shipped));

            EntityModelData posed = PoseKit.posed(compiled.pose(), mesh, compiled.style(), PERIOD, 0);
            assertPivot(posed.getBones().get("tail").getPivot(), 0f, 5f, 0f, "the tail rides the body");
            assertPivot(posed.getBones().get("tail2").getPivot(), 0f, 5f, 5f,
                "the second segment rides the tail's carried, unturned frame");
        }

        @Test
        @DisplayName("reads a per-row field on a woven layer - a carry solved against this row's pivots is this row's data")
        void readsAPerRowFieldOnAWovenLayer() {
            EntityModelData mesh = bodyAndTail();
            PoseCompiler.Compiled compiled = PoseCompiler.compileLayer(
                Poses.quadruped("sit").body(body -> body.pitch(90)).build(), sitting(mesh), mesh, "$layer0",
                StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null));

            assertTrue(compiled.style().drivers().containsKey("style$sit$$layer0$tail$y"), "the carry reads the layer's field");
            assertTrue(compiled.style().drivers().containsKey("style$sit$$layer0$tail$z"));
            assertFalse(compiled.style().drivers().containsKey("style$sit$tail$y"), "and not the shared spelling");
        }

        @Test
        @DisplayName("follows the held stance alone and says so when the leader waves")
        void followsTheHeldStanceAloneUnderAWave() {
            EntityModelData mesh = bodyAndTail();
            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.quadruped("rock").body(body -> body.pitch(90).sway(Turn.PITCH, -10, 10)).build(),
                row(mesh, sitting(mesh)));

            assertTrue(compiled.style().drivers().containsKey("style$rock$tail$y"), "the held pitch is followed");
            assertTrue(compiled.diagnostics().entries().stream().anyMatch(entry ->
                    entry.severity() == StyleDiagnostics.Severity.WARN
                        && entry.message().contains("held stance alone")),
                "the wave is not, and the compile records it");
        }

        @Test
        @DisplayName("carries a parentless follower of a flattened mesh, crossing the factor exactly once")
        void carriesAFlattenedParentlessFollowerAcrossTheFactorOnce() {
            // The flattened twin is the plain mesh as the generator stores it - every pivot times
            // the factor, the feet-anchor translate on each top-level y - so both speak one set of
            // vanilla units and the same silhouette seats the tail on both.
            float factor = 2f;
            EntityModelData plain = bodyAndTail();
            EntityModelData flat = new EntityModelData();
            plain.getBones().forEach((name, bone) -> flat.getBones().put(name, new EntityModelData.Bone(
                new Vector3f(
                    bone.getPivot().x() * factor,
                    bone.getPivot().y() * factor + (bone.getParent() == null ? EntityModelData.flattenedShift(factor) : 0f),
                    bone.getPivot().z() * factor),
                bone.getRotation(), EulerRotation.NONE, factor, bone.getCubes(), bone.getParent())));
            BuiltStyle sit = Poses.quadruped("sit").body(body -> body.pitch(90)).build();

            PoseCompiler.Compiled onPlain = PoseCompiler.compile(sit, row(plain, sitting(plain)));
            PoseCompiler.Compiled onFlat = PoseCompiler.compile(sit, row(flat, sitting(flat)));

            assertEquals(onPlain.style().drivers().get("style$sit$tail$y").extent(),
                onFlat.style().drivers().get("style$sit$tail$y").extent(), 1e-4f,
                "the carry is solved in vanilla units on both and crosses no factor inside the graph");
            Vector3f plainTail = PoseKit.posed(onPlain.pose(), plain, onPlain.style(), 24, 0).getBones().get("tail").getPivot();
            Vector3f flatTail = PoseKit.posed(onFlat.pose(), flat, onFlat.style(), 24, 0).getBones().get("tail").getPivot();
            assertEquals(plainTail.y() * factor + EntityModelData.flattenedShift(factor), flatTail.y(), 1e-3f,
                "the write-back multiplies the factor once and puts the anchor back, so the twin lands where the generator would store it");
            assertEquals(plainTail.z() * factor, flatTail.z(), 1e-3f, "z carries no anchor");
            assertTrue(onFlat.diagnostics().entries().stream().noneMatch(entry ->
                    entry.severity() == StyleDiagnostics.Severity.WARN
                        && entry.message().contains("flattened")),
                "nothing is left at rest");
        }

    }

    // ------------------------------------------------------------------------------------
    // contact
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a contact")
    class Contact {

        @Test
        @DisplayName("never cascades - the dab and the clap lower identically with the zombie's silhouettes stripped")
        void dabAndClapCascadeNothing() {
            Entity zombie = EntityModelLoader.load().get("minecraft:zombie");
            assumeTrue(zombie != null, "bundled entity tables answer the zombie");
            assertFalse(zombie.pose().states().isEmpty(), "the zombie carries silhouettes, so the comparison is not vacuous");
            EntityPose stripped = new EntityPose(zombie.pose().container(), zombie.pose().bones(),
                zombie.pose().clips(), zombie.pose().refusal());
            Entity bare = zombie.mutate().pose(stripped).build();

            for (BuiltStyle style : List.of(
                Poses.humanoid("dab")
                    .head(head -> head.rotate(30, -35, 0))
                    .arm(Side.LEFT, arm -> arm.rotate(-150, -35, 0))
                    .arm(Side.RIGHT, arm -> arm.rotate(-160, 35, 0))
                    .build(),
                Poses.humanoid("clap")
                    .arms(arm -> arm.pitch(-90).yaw(-10)
                        .timeline(timeline -> timeline.swing(Turn.YAW, 0, -25).over(0.4)))
                    .build())) {
                PoseCompiler.Compiled carrying = PoseCompiler.compile(style, zombie);
                PoseCompiler.Compiled without = PoseCompiler.compile(style, bare);
                assertEquals(without.style().drivers().keySet(), carrying.style().drivers().keySet(),
                    style.styleId() + ": the same fields, so no seat wrote a position");
                assertEquals(without.style().drivers(), carrying.style().drivers(),
                    style.styleId() + ": with the same extents");
                assertEquals(spliced(without.pose(), stripped), spliced(carrying.pose(), zombie.pose()),
                    style.styleId() + ": the same channels spliced over the same bones");
                assertTrue(carrying.diagnostics().entries().stream()
                        .noneMatch(entry -> entry.message().startsWith("seat: ") || entry.message().startsWith("joint: ")),
                    style.styleId() + ": no seat and no joint recorded");
            }
        }

    }

    // ------------------------------------------------------------------------------------
    // joints
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a joint")
    class Joint {

        @Test
        @DisplayName("lands an anatomical head on the neck assembly the shipped pose turns")
        void anatomicalHeadLandsOnTheArticulation() {
            EntityModelData mesh = neckAndHead();
            EntityPose shipped = pose(List.of(), Map.of("neck", Map.of(PoseChannel.X_ROT, constant(0.5d))), List.of());
            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.quadruped("look").head(head -> head.pitch(60)).build(), row(mesh, shipped));

            assertTrue(compiled.style().drivers().containsKey("style$look$neck$x_rot"), "the stance lands on the neck");
            assertFalse(compiled.style().drivers().containsKey("style$look$head$x_rot"), "and not on the cube");
            assertSame(shipped.bones().get("neck").get(PoseChannel.X_ROT),
                ((PoseExpr.Op) compiled.pose().bones().get("neck").get(PoseChannel.X_ROT)).operands().getFirst(),
                "over the neck's shipped instance");
            EntityModelData posed = PoseKit.posed(compiled.pose(), mesh, compiled.style(), PERIOD, 0);
            assertEquals(60f, posed.getBones().get("neck").getRotation().pitch(), 1e-3);
            assertTrue(compiled.diagnostics().entries().stream().anyMatch(entry ->
                    entry.message().startsWith("joint: 'head' lands on 'neck'")),
                "the resolution records itself");
        }

        @Test
        @DisplayName("leaves a literal custom-tier name on the mesh bone it names")
        void literalNameStaysLiteral() {
            EntityModelData mesh = neckAndHead();
            EntityPose shipped = pose(List.of(), Map.of("neck", Map.of(PoseChannel.X_ROT, constant(0.5d))), List.of());
            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.custom("look").bone("head", head -> head.pitch(60)).build(), row(mesh, shipped));

            assertTrue(compiled.style().drivers().containsKey("style$look$head$x_rot"));
            assertFalse(compiled.style().drivers().containsKey("style$look$neck$x_rot"));
        }

        @Test
        @DisplayName("leaves an anatomical name on itself where no ancestor is articulated")
        void unarticulatedAncestryStaysOnTheBone() {
            EntityModelData mesh = neckAndHead();
            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.quadruped("look").head(head -> head.pitch(60)).build(), row(mesh, EntityPose.NONE));

            assertTrue(compiled.style().drivers().containsKey("style$look$head$x_rot"));
        }

        @Test
        @DisplayName("stays on a child seated off its parent's pivot - a pivot of its own makes it a joint")
        void aChildWithItsOwnPivotStaysAJoint() {
            EntityModelData mesh = neckAndHead();
            mesh.getBones().put("head", bone(0f, -5f, 0f, 0f, 0f, 0f, 1f, "neck"));
            EntityPose shipped = pose(List.of(), Map.of("neck", Map.of(PoseChannel.X_ROT, constant(0.5d))), List.of());
            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.quadruped("look").head(head -> head.pitch(60)).build(), row(mesh, shipped));

            assertTrue(compiled.style().drivers().containsKey("style$look$head$x_rot"),
                "the head turns about a point of its own, so the stance is the head's");
            assertFalse(compiled.style().drivers().containsKey("style$look$neck$x_rot"));
            assertTrue(compiled.diagnostics().entries().stream().noneMatch(entry -> entry.message().startsWith("joint: ")),
                "and no landing is recorded");
        }

        @Test
        @DisplayName("reads the pose as it shipped, so the landing is the same whatever was installed before")
        void landingReadsTheShippedEvidenceWhateverWasInstalledBefore() {
            assumeTrue(EntityModelLoader.load().containsKey("minecraft:horse"), "bundled entity tables answer the horse");
            BuiltStyle curl = Poses.quadruped("curl").head(head -> head.pitch(25)).build();
            BuiltStyle cube = Poses.custom("cube").bone("head", head -> head.pitch(10)).build();
            BuiltStyle hatch = Poses.custom("hatch")
                .expr("head", PoseChannel.X_ROT, new PoseExpr.Const(0.1d, PoseOperator.Width.DOUBLE))
                .build();

            assertLandsOnTheNeck(StyleRegistrar.ofShipped().add("minecraft:horse", curl), "installed alone");
            assertLandsOnTheNeck(StyleRegistrar.ofShipped().add("minecraft:horse", cube).add("minecraft:horse", curl),
                "after a custom-tier stance on the head cube");
            assertLandsOnTheNeck(StyleRegistrar.ofShipped().add("minecraft:horse", hatch).add("minecraft:horse", curl),
                "after a raw splice on the head cube");
            assertLandsOnTheNeck(StyleRegistrar.ofShipped().add("minecraft:horse", curl).add("minecraft:horse", cube),
                "before a custom-tier stance on the head cube");
            assertLandsOnTheNeck(StyleRegistrar.ofShipped().add("minecraft:horse", curl).add("minecraft:horse", hatch),
                "before a raw splice on the head cube");
        }

        private void assertLandsOnTheNeck(@NotNull StyleRegistrar registrar, @NotNull String order) {
            Map<String, ?> drivers = registrar.definitions().get("minecraft:horse").styles()
                .byId("curl").orElseThrow().drivers();
            assertTrue(drivers.containsKey("style$curl$head_parts$x_rot"), order + ": the head lands on the neck assembly");
            assertFalse(drivers.containsKey("style$curl$head$x_rot"), order + ": and never on the cube");
        }

        @Test
        @DisplayName("keeps the hat on the hat - a shell is not anatomy")
        void hatStaysAShell() {
            EntityModelData mesh = CompilerFixtures.humanoid();
            mesh.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "head"));
            EntityPose shipped = pose(List.of(), Map.of("head", Map.of(PoseChannel.X_ROT, constant(0d))), List.of());
            PoseCompiler.Compiled compiled = PoseCompiler.compile(
                Poses.humanoid("tip").hat(hat -> hat.pitch(10)).build(), row(mesh, shipped));

            assertTrue(compiled.style().drivers().containsKey("style$tip$hat$x_rot"));
            assertFalse(compiled.style().drivers().containsKey("style$tip$head$x_rot"));
        }

    }

    // ------------------------------------------------------------------------------------

    /**
     * The (bone, channel) pairs a woven pose splices - every channel whose instance is not the
     * shipped one.
     */
    private static @NotNull Set<String> spliced(@NotNull EntityPose woven, @NotNull EntityPose shipped) {
        Set<String> out = new LinkedHashSet<>();
        woven.bones().forEach((bone, channels) -> channels.forEach((channel, expr) -> {
            Map<PoseChannel, PoseExpr> before = shipped.bones().get(bone);
            if (before == null || before.get(channel) != expr) out.add(bone + "." + channel.token());
        }));
        return out;
    }

    /**
     * Two top-level bones - a body, and a tail five units behind it along the body's own axis.
     */
    private static @NotNull EntityModelData bodyAndTail() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(0f, 10f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("tail", bone(0f, 10f, 5f, 0f, 0f, 0f, 1f, null));
        return mesh;
    }

    /**
     * A neck the pose turns, with a head cube seated at the neck's own pivot, a snout hanging
     * beside it and an ear under the head - the equine assembly's shape.
     */
    private static @NotNull EntityModelData neckAndHead() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("neck", bone(0f, 10f, -5f, 30f, 0f, 0f, 1f, null));
        mesh.getBones().put("head", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "neck"));
        mesh.getBones().put("snout", bone(0f, -5f, -3f, 0f, 0f, 0f, 1f, "neck"));
        mesh.getBones().put("ear", bone(1f, -2f, 0f, 0f, 0f, 0f, 1f, "head"));
        return mesh;
    }

    /**
     * The sitting silhouette of a body-and-tail mesh - the body pitched a quarter turn and the
     * tail placed where that frame carries its resting five units.
     */
    private static @NotNull EntityPose sitting(@NotNull EntityModelData mesh) {
        return withStates(Map.of("isSitting=true", Map.of(
            "body", Map.of(PoseChannel.X_ROT, constant(QUARTER)),
            "tail", Map.of(PoseChannel.Y, constant(10f - REACH), PoseChannel.Z, constant(REACH)))));
    }

    /**
     * A pose writing nothing, carrying the given silhouettes.
     */
    private static @NotNull EntityPose withStates(@NotNull Map<String, Map<String, Map<PoseChannel, PoseExpr>>> states) {
        Map<String, EntityPose.Silhouette> silhouettes = new LinkedHashMap<>();
        states.forEach((key, bones) -> silhouettes.put(key,
            new EntityPose.Silhouette(Concurrent.newUnmodifiableLinkedMap(bones))));
        return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(Map.of()),
            Concurrent.newUnmodifiableList(), Optional.empty(), Concurrent.newUnmodifiableLinkedMap(silhouettes));
    }

    private static void assertPivot(@NotNull Vector3f pivot, float x, float y, float z, @NotNull String message) {
        assertEquals(x, pivot.x(), 1e-3, message + " (x)");
        assertEquals(y, pivot.y(), 1e-3, message + " (y)");
        assertEquals(z, pivot.z(), 1e-3, message + " (z)");
    }

}
