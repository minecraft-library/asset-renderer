package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The audit measures a built style against the clearance envelope the shipped table defines,
 * names the forgotten coupling when a pose tears a bind-adjacent pair outside it, and reads
 * clean where the shipped evidence - a seat derived from a state silhouette, a joint resolved
 * from the pose's own articulation - carries the coupled part along.
 */
@DisplayName("the pose audit reads forgotten couplings out of the shipped envelope")
class PoseValidatorTest {

    /**
     * A begging wolf whose body pitches - the tail seat and the hind legs are carried by the
     * seat the sitting silhouette derives, and the mane's contact is one vanilla itself sits at.
     */
    private static final @NotNull BuiltStyle BEG = Poses.quadruped("beg")
        .body(body -> body.pitch(-40))
        .hindLegs(leg -> leg.pitch(-70))
        .frontLegs(leg -> leg.pitch(-35))
        .head(head -> head.pitch(-15))
        .tail(tail -> tail.sway(Turn.YAW, -25, 25))
        .build();

    /**
     * A rearing horse whose neck curl lands on the {@code head_parts} assembly the shipped pose
     * turns for the head, so the snout, mane and ears ride with it.
     */
    private static final @NotNull BuiltStyle REAR = Poses.quadruped("rear")
        .frontLegs(leg -> leg.pitch(-65))
        .head(head -> head.pitch(25))
        .tail(tail -> tail.pitch(-30))
        .build();

    @Test
    @DisplayName("the wolf beg audits clean - the tail and hind legs ride the seated body")
    void begAuditsCleanWithTheWolfSeated() {
        PoseAudit audit = BEG.validate(row("minecraft:wolf"));

        assertTrue(audit.clean(), audit.report());
    }

    @Test
    @DisplayName("the beg audits on the cat the cookbook reuses it for - a flattened mesh whose silhouettes place its root")
    void begAuditsOnTheCookbooksCatReuse() {
        Entity cat = row("minecraft:cat");
        assumeTrue(cat.model().getFlattenedScale() != 1f && !cat.pose().states().isEmpty(),
            "the cat is flattened and carries silhouettes, or the case is vacuous");

        PoseAudit audit = assertDoesNotThrow(() -> BEG.validate(cat));

        assertTrue(audit.pairsChecked() > 0, audit.report());
    }

    @Test
    @DisplayName("every readable shipped row audits a trivial style - no silhouette the kit cannot place fails the audit")
    void everyReadableRowAuditsATrivialStyle() {
        ConcurrentMap<String, Entity> definitions = EntityModelLoader.load();
        assumeTrue(!definitions.isEmpty(), "bundled entity tables are present");

        int audited = 0;
        for (Entity row : definitions.values()) {
            if (!row.pose().isReadable() || row.model().getBones().isEmpty()) continue;
            String first = row.model().getBones().keySet().iterator().next();
            BuiltStyle probe = Poses.custom("probe").bone(first, stance -> stance.pitchBy(5)).build();
            assertDoesNotThrow(() -> probe.validate(row), row.id() + " audits");
            audited++;
        }
        assertTrue(audited >= 90, "the roster audits whole, yet only " + audited + " rows answered");
    }

    @Test
    @DisplayName("vanilla's own sitting branch buries the chest in the mane by the same measure the beg does")
    void vanillaSittingMeasuresTheSameManeContact() {
        Entity wolf = row("minecraft:wolf");
        Entity bare = withoutSilhouettes(wolf);

        // Over the bare envelope the sitting branch is a hand placement the audit has never seen,
        // so it measures the mane the way the beg's is measured: a chest set two to three pixels
        // into the mane.
        PoseAudit.Finding mane = finding(sitting(true).validate(bare), PoseAudit.Kind.OVERLAP, "body", "upper_body");
        assertTrue(mane.posedMin() < -2f && mane.posedMin() > -3f,
            "vanilla's own sitting silhouette sets the chest between two and three pixels into the mane: " + mane.describe());
        PoseAudit.Finding beg = finding(BEG.validate(bare), PoseAudit.Kind.OVERLAP, "body", "upper_body");
        assertTrue(Math.abs(beg.posedMin() - mane.posedMin()) < 1f,
            "the beg's mane contact is within a pixel of vanilla's own: " + beg.describe());

        // Over the row as it ships, the same silhouette is in the envelope and the seats carry the
        // tail and the hips, so neither placement reads as a fault.
        PoseAudit seated = sitting(false).validate(wolf);
        assertTrue(seated.findings().stream().noneMatch(finding ->
                finding.boneA().equals("body") && (finding.boneB().equals("upper_body") || finding.boneB().equals("real_tail"))),
            "the mane and the tail read inside what vanilla draws:\n" + seated.report());
    }

    @Test
    @DisplayName("the same beg on a wolf carrying no silhouette splits the tail and buries the mane")
    void begWithoutSilhouettesReadsTheTailAndMane() {
        PoseAudit audit = BEG.validate(withoutSilhouettes(row("minecraft:wolf")));

        assertFalse(audit.clean(), "with no seat to derive, the beg leaves the shipped envelope");
        PoseAudit.Finding tail = finding(audit, PoseAudit.Kind.SPLIT, "body", "real_tail");
        assertTrue(tail.posedMin() > tail.knownMax(),
            "the tail sits outside the whole shipped range, not merely past the margin");

        PoseAudit.Finding mane = finding(audit, PoseAudit.Kind.OVERLAP, "body", "upper_body");
        assertTrue(mane.aStanced() && !mane.bStanced(),
            "the body moved and the mane did not - the reading names the forgotten side");
    }

    @Test
    @DisplayName("a biped's silhouettes witness no seat and widen nothing - an arm driven into the torso reads the same with them as without")
    void seatlessSilhouettesWidenNothing() {
        Entity piglin = row("minecraft:piglin");
        assumeTrue(!piglin.pose().states().isEmpty(), "the piglin carries silhouettes, or the case is vacuous");
        BuiltStyle buried = Poses.custom("buried").bone("right_arm", arm -> arm.offset(3, 0, 0)).build();

        PoseAudit audit = buried.validate(piglin);
        PoseAudit bare = buried.validate(withoutSilhouettes(piglin));

        PoseAudit.Finding arm = finding(audit, PoseAudit.Kind.OVERLAP, "body", "right_arm");
        assertTrue(arm.posedMin() < arm.knownMin() - 1f,
            "three units into the torso is an overlap the crouch and the swing do not excuse: " + arm.describe());
        assertEquals(bare.report(), audit.report(),
            "no seat derives on a biped, so none of its silhouettes is in the envelope");
    }

    @Test
    @DisplayName("the horse rear keeps the neck assembly whole - no finding names the snout, mane or ears")
    void rearKeepsTheNeckAssemblyWhole() {
        PoseAudit audit = REAR.validate(row("minecraft:horse"));

        for (PoseAudit.Finding finding : audit.findings())
            for (String part : new String[] {"head", "upper_mouth", "mane", "left_ear", "right_ear"})
                assertFalse(finding.boneA().equals(part) || finding.boneB().equals(part),
                    "the neck assembly moves whole, yet the audit reports: " + finding.describe());
    }

    @Test
    @DisplayName("the rear's one residual is the tail's own stance turned into the rump, read the same over the bare envelope")
    void rearResidualIsTheTailStanceItself() {
        PoseAudit audit = REAR.validate(row("minecraft:horse"));

        assertEquals(1, audit.findings().size(), audit.report());
        PoseAudit.Finding tail = finding(audit, PoseAudit.Kind.OVERLAP, "body", "tail");
        assertTrue(tail.bStanced() && !tail.aStanced(),
            "the tail is the stanced side - a real child of the body, turned sixty degrees into it");
        assertTrue(tail.bindClearance() < 0f, "the pair already interpenetrates at bind");

        PoseAudit.Finding bare = finding(REAR.validate(withoutSilhouettes(row("minecraft:horse"))),
            PoseAudit.Kind.OVERLAP, "body", "tail");
        assertEquals(tail.posedMin(), bare.posedMin(), 0f,
            "no seat derives on the horse, so the posed clearance is the stance's own with or without the evidence");
    }

    @Test
    @DisplayName("the same rear on a horse whose pose turns the head cube itself tears the snout off")
    void rearWithTheHeadArticulatedReadsTheSnout() {
        PoseAudit audit = REAR.validate(withHeadArticulated(row("minecraft:horse")));

        assertFalse(audit.clean(), "with the head its own articulation, the stance lands on the head cube alone");
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
     * The sitting branch of the wolf's own {@code setupAnim}, spelled as a custom style at
     * vanilla's constants: the body four down, two forward and pitched to forty-five; the mane
     * two down at seventy-two; the hips folded flat and the paws pitched back a hair and one
     * down. By hand, the tail and the hips are placed where that branch places them; over a row
     * carrying the silhouette, the seats carry them and the placements are left unspelled.
     */
    private static @NotNull BuiltStyle sitting(boolean byHand) {
        CustomPose.Builder sitting = Poses.custom("sitting")
            .bone("body", body -> body.pitch(45).offset(0, 4, -2))
            .bone("upper_body", mane -> mane.pitch(72).offset(0, 2, 0))
            .bone("right_hind_leg", hip -> hip.pitch(-90))
            .bone("left_hind_leg", hip -> hip.pitch(-90))
            .bone("right_front_leg", paw -> paw.pitch(-27).offset(0.01, 1, 0))
            .bone("left_front_leg", paw -> paw.pitch(-27).offset(-0.01, 1, 0));
        if (byHand)
            sitting.bone("tail", tail -> tail.offset(0, 9, -2))
                .bone("right_hind_leg", hip -> hip.offset(0, 6.7, -5))
                .bone("left_hind_leg", hip -> hip.offset(0, 6.7, -5));
        return sitting.build();
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
     * The same row with every state silhouette taken off its pose - no seat can be derived and
     * the envelope is the shipped styles alone, which is what the audit read before the table
     * carried silhouettes.
     */
    private static @NotNull Entity withoutSilhouettes(@NotNull Entity row) {
        EntityPose pose = row.pose();
        return row.mutate()
            .pose(new EntityPose(pose.container(), pose.bones(), pose.clips(), pose.refusal()))
            .build();
    }

    /**
     * The same row with its pose writing the head cube's own pitch, so the head is its own
     * articulation and an anatomical head stance lands on the cube rather than on the neck.
     */
    private static @NotNull Entity withHeadArticulated(@NotNull Entity row) {
        EntityPose pose = row.pose();
        Map<String, Map<PoseChannel, PoseExpr>> bones = new LinkedHashMap<>(pose.bones());
        bones.put("head", Map.of(PoseChannel.X_ROT, new PoseExpr.Const(0d, PoseOperator.Width.FLOAT)));
        return row.mutate()
            .pose(new EntityPose(pose.container(), Concurrent.newUnmodifiableMap(bones), pose.clips(),
                pose.refusal(), pose.states()))
            .build();
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
