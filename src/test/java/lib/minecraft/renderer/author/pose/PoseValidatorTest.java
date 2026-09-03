package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The audit measures a built style against the clearance envelope the shipped styles define,
 * and names the forgotten coupling when a pose tears a bind-adjacent pair outside it.
 */
@DisplayName("the pose audit reads forgotten couplings out of the shipped envelope")
class PoseValidatorTest {

    /**
     * A begging wolf whose body pitches while the tail seat and mane stay authored nowhere -
     * the two couplings vanilla carries outside the pose table.
     */
    private static final @NotNull BuiltStyle BEG = Poses.quadruped("beg")
        .body(body -> body.pitch(-40))
        .hindLegs(leg -> leg.pitch(-70))
        .frontLegs(leg -> leg.pitch(-35))
        .head(head -> head.pitch(-15))
        .tail(tail -> tail.sway(Turn.YAW, -25, 25))
        .build();

    /**
     * A rearing horse whose neck curl lands on the {@code head} child instead of the
     * {@code head_parts} assembly, leaving the snout and mane behind.
     */
    private static final @NotNull BuiltStyle REAR = Poses.quadruped("rear")
        .frontLegs(leg -> leg.pitch(-65))
        .head(head -> head.pitch(25))
        .tail(tail -> tail.pitch(-30))
        .build();

    @Test
    @DisplayName("the wolf beg splits the tail from the pitched body and buries the mane")
    void begReadsTheWolfTailAndMane() {
        PoseAudit audit = BEG.validate(row("minecraft:wolf"));

        assertFalse(audit.clean(), "the beg leaves the shipped envelope");
        PoseAudit.Finding tail = finding(audit, PoseAudit.Kind.SPLIT, "body", "real_tail");
        assertTrue(tail.posedMin() > tail.knownMax(),
            "the tail sits outside the whole shipped range, not merely past the margin");

        PoseAudit.Finding mane = finding(audit, PoseAudit.Kind.OVERLAP, "body", "upper_body");
        assertTrue(mane.aStanced() && !mane.bStanced(),
            "the body moved and the mane did not - the reading names the forgotten side");
    }

    @Test
    @DisplayName("the horse rear names the neck assembly the head write should have targeted")
    void rearReadsTheEquineSnout() {
        PoseAudit audit = REAR.validate(row("minecraft:horse"));

        assertFalse(audit.clean(), "the rear leaves the shipped envelope");
        PoseAudit.Finding snout = finding(audit, PoseAudit.Kind.OVERLAP, "head", "upper_mouth");
        assertEquals(Optional.of("head_parts"), snout.sharedParent(),
            "the snout hangs beside the head under the neck assembly - the reading names it");
        assertTrue(snout.describe().contains("stance the parent"),
            "the rendered reading points at the shared parent");
    }

    @Test
    @DisplayName("a coupling-safe mover audits clean")
    void flutterAuditsClean() {
        BuiltStyle flutter = Poses.custom("flutter")
            .bone("left_wing", wing -> wing.timeline(track -> track.swing(Turn.YAW, -50, 10).over(0.3)))
            .bone("right_wing", wing -> wing.timeline(track -> track.swing(Turn.YAW, 50, -10).over(0.3)))
            .bone("head", head -> head.pitchBy(-8))
            .hover(4, 1)
            .build();

        assertTrue(flutter.validate(row("minecraft:allay")).clean(),
            "wingbeats and a hover ride inside the shipped envelope");
    }

    @Test
    @DisplayName("a container-only lift audits clean - rigid motion breaks no coupling")
    void levitateAuditsClean() {
        BuiltStyle levitate = Poses.humanoid("levitate")
            .arms(arm -> arm.roll(35))
            .legs(leg -> leg.pitch(-6))
            .hover(8, 2)
            .build();

        assertTrue(levitate.validate(row("minecraft:zombie")).clean(),
            "drifted limbs and the lift stay within the stride envelope");
    }

    /**
     * The shipped row for one entity, skipping the suite where the bundled tables are absent.
     */
    private static @NotNull Entity row(@NotNull String entityId) {
        Entity row = EntityModelLoader.load().get(entityId);
        assumeTrue(row != null, "bundled entity tables answer " + entityId);
        return row;
    }

    /**
     * The one finding of a kind on a pair, failing with the whole report when absent.
     */
    private static @NotNull PoseAudit.Finding finding(
        @NotNull PoseAudit audit, @NotNull PoseAudit.Kind kind,
        @NotNull String boneA, @NotNull String boneB) {

        return audit.findings().stream()
            .filter(candidate -> candidate.kind() == kind
                && candidate.boneA().equals(boneA) && candidate.boneB().equals(boneB))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no " + kind + " finding on " + boneA + " <-> " + boneB + " in:\n" + audit.report()));
    }

}
