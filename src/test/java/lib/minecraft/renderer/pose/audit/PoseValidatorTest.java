package lib.minecraft.renderer.pose.audit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseOperator;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Corner;
import lib.minecraft.renderer.pose.author.CustomPose;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Turn;
import lib.minecraft.renderer.pose.install.RegistrarFixtures;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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
     * A begging wolf at vanilla's own sitting numbers - the body settled and pitched to
     * forty-five, the mane settled two down and pitched to seventy-two, the hips folded flat, the
     * paws pitched back a hair - so the tail seat and the hind legs are carried by the seat the
     * sitting silhouette derives, and the mane's contact is one vanilla itself sits at.
     */
    private static final @NotNull BuiltStyle BEG = Poses.quadruped("beg")
        .body(body -> body.pitch(45).offset(0, 4, -2))
        .bone("upper_body", mane -> mane.pitch(72).offset(0, 2, 0))
        .hindLegs(leg -> leg.pitch(-90))
        .frontLegs(leg -> leg.pitch(-27).offset(0, 1, 0))
        .head(head -> head.pitch(-15))
        .tail(tail -> tail.sway(Turn.YAW, -25, 25))
        .build();

    /**
     * A rearing horse at vanilla's own standing numbers - the body tipped up, the neck lifted
     * and curled, the forelegs lifted and pawing, the hind legs braced, the tail written
     * nothing - whose neck curl lands on the {@code head_parts} assembly the shipped pose turns
     * for the head, so the snout, mane and ears ride with it.
     */
    private static final @NotNull BuiltStyle REAR = Poses.quadruped("rear")
        .body(body -> body.pitch(-45))
        .head(head -> head.pitch(15).offset(0, -8.8, 8.8))
        .leg(Corner.FRONT_LEFT, leg -> leg.pitch(-117.3).offset(0, -13.2, 4.4))
        .leg(Corner.FRONT_RIGHT, leg -> leg.pitch(-2.7).offset(0, -13.2, 4.4))
        .hindLegs(leg -> leg.pitch(15))
        .build();

    @Test
    @DisplayName("the wolf beg audits clean - the tail and hind legs ride the seated body")
    void begAuditsCleanWithTheWolfSeated() {
        PoseAudit audit = BEG.validate(row("minecraft:wolf"));

        assertTrue(audit.clean(), audit.report());
    }

    @Test
    @DisplayName("the beg audits on the cat the cookbook reuses it for - a flattened mesh whose seated tail rides its placed root")
    void begAuditsOnTheCookbooksCatReuse() {
        Entity cat = row("minecraft:cat");
        assumeTrue(cat.model().getFlattenedScale() != 1f && !cat.pose().states().isEmpty(),
            "the cat is flattened and carries silhouettes, or the case is vacuous");

        PoseAudit audit = assertDoesNotThrow(() -> BEG.validate(cat));

        assertTrue(audit.pairsChecked() > 0, audit.report());
        assertTrue(audit.findings().stream().noneMatch(finding ->
                names(finding, "body", "tail1") || names(finding, "tail1", "tail2")),
            "the feline tail rides its pitched body, parentless on a flattened mesh though it is:\n" + audit.report());
    }

    /** Whether a finding is about the given pair, in either order. */
    private static boolean names(@NotNull PoseAudit.Finding finding, @NotNull String a, @NotNull String b) {
        return (finding.boneA().equals(a) && finding.boneB().equals(b))
            || (finding.boneA().equals(b) && finding.boneB().equals(a));
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
    @DisplayName("the beg buries the chest in the mane exactly as vanilla's own sitting branch does - the same depth over the bare envelope")
    void begReadsVanillasOwnManeContact() {
        Entity wolf = row("minecraft:wolf");
        Entity bare = withoutSilhouettes(wolf);

        // Over the bare envelope the sitting branch is a hand placement the audit has never seen:
        // the mane settled two down and pitched to seventy-two sets the chest two to three pixels
        // into it. The beg settles the same mane at the same numbers and meets it as deep.
        PoseAudit.Finding mane = finding(sitting(true).validate(bare), PoseAudit.Kind.OVERLAP, "body", "upper_body");
        assertTrue(mane.posedMin() < -2f && mane.posedMin() > -3f,
            "vanilla's own sitting silhouette sets the chest between two and three pixels into the mane: " + mane.describe());
        PoseAudit.Finding begMane = finding(BEG.validate(bare), PoseAudit.Kind.OVERLAP, "body", "upper_body");
        assertEquals(mane.posedMin(), begMane.posedMin(), 0.05f,
            "the same clearance to a twentieth of a pixel: " + begMane.describe());

        // Over the row as it ships, the same silhouette is in the envelope and the seats carry the
        // tail and the hips, so neither placement reads as a fault.
        PoseAudit seated = sitting(false).validate(wolf);
        assertTrue(seated.findings().stream().noneMatch(finding ->
                finding.boneA().equals("body") && (finding.boneB().equals("upper_body") || finding.boneB().equals("real_tail"))),
            "the mane and the tail read inside what vanilla draws:\n" + seated.report());
    }

    @Test
    @DisplayName("the same beg on a wolf carrying no silhouette splits the tail - the one reading the seats answer, beside the mane contact vanilla's own branch shares")
    void begWithoutSilhouettesReadsTheTail() {
        PoseAudit audit = BEG.validate(withoutSilhouettes(row("minecraft:wolf")));

        assertFalse(audit.clean(), "with no seat to derive, the beg leaves the shipped envelope");
        assertEquals(List.of("OVERLAP body <-> upper_body", "SPLIT body <-> real_tail"), pairs(audit),
            audit.report());
        PoseAudit.Finding tail = finding(audit, PoseAudit.Kind.SPLIT, "body", "real_tail");
        assertTrue(tail.posedMin() > tail.knownMax(),
            "the tail sits outside the whole shipped range, not merely past the margin");
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
    @DisplayName("the rear reads what vanilla's own standing branch reads - the legs set into the tipped body - and the same over the bare envelope")
    void rearReadsVanillasOwnStandingContacts() {
        Entity horse = row("minecraft:horse");
        PoseAudit audit = REAR.validate(horse);
        PoseAudit standing = RegistrarFixtures.silhouette(horse, "standAnimation=1", "standing").validate(horse);

        // No seat derives on the horse, so its standing silhouette is not in the envelope and the
        // branch's own leg contacts read as findings; the rear, spelled at the same numbers, reads
        // those and no other - the tail, written nothing, rises with the body and reads no contact.
        assertEquals(pairs(standing), pairs(audit),
            "vanilla's standing branch reads:\n" + standing.report() + "\nthe rear reads:\n" + audit.report());
        assertFalse(audit.findings().isEmpty(), "the tipped body meets its legs outside the stride envelope");
        for (int at = 0; at < audit.findings().size(); at++)
            assertEquals(standing.findings().get(at).posedMin(), audit.findings().get(at).posedMin(), 0.05f,
                "the same clearance to a twentieth of a pixel: " + audit.findings().get(at).describe());
        assertTrue(audit.findings().stream().noneMatch(finding -> names(finding, "body", "tail")),
            "the tail reads no contact of its own:\n" + audit.report());

        assertEquals(pairs(audit), pairs(REAR.validate(withoutSilhouettes(horse))),
            "with the evidence off the readings are the same - the posed clearances are the stance's own");
    }

    /**
     * The findings of an audit as their kind and pair, in report order.
     */
    private static @NotNull List<String> pairs(@NotNull PoseAudit audit) {
        return audit.findings().stream()
            .map(finding -> finding.kind() + " " + finding.boneA() + " <-> " + finding.boneB())
            .toList();
    }

    @Test
    @DisplayName("the same rear on a horse whose pose turns the head cube itself lifts the head off the snout")
    void rearWithTheHeadArticulatedReadsTheSnout() {
        PoseAudit audit = REAR.validate(withHeadArticulated(row("minecraft:horse")));

        assertFalse(audit.clean(), "with the head its own articulation, the stance lands on the head cube alone");
        PoseAudit.Finding snout = finding(audit, PoseAudit.Kind.SPLIT, "head", "upper_mouth");
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
