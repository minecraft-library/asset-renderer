package lib.minecraft.renderer.author.audit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PaceCeiling;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.CustomPose;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.Rank;
import lib.minecraft.renderer.author.Side;
import lib.minecraft.renderer.author.Turn;
import lib.minecraft.renderer.author.compile.PoseCompiler;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.fixture.CompilerFixtures;
import lib.minecraft.renderer.fixture.RegistrarFixtures;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The audit measures a built style against the clearance envelope the shipped table defines,
 * names the forgotten coupling when a pose tears a bind-adjacent pair outside it, and reads
 * clean where the shipped evidence - a seat derived from a state silhouette, a joint resolved
 * from the pose's own articulation - carries the coupled part along.
 */
@DisplayName("the pose audit reads forgotten couplings out of the shipped envelope")
class PoseAuditorTest {

    /**
     * A begging wolf at vanilla's own sitting numbers - the body settled and pitched to
     * forty-five, the mane settled two down and pitched to seventy-two, the hips folded flat, the
     * paws pitched back a hair - so the tail seat and the hind legs are carried by the seat the
     * sitting silhouette derives, and the mane's contact is one vanilla itself sits at.
     */
    private static final @NotNull BuiltStyle BEG = Poses.legged("beg")
        .body(body -> body.pitch(45).offset(0, 4, -2))
        .bone("upper_body", mane -> mane.pitch(72).offset(0, 2, 0))
        .legs(Rank.HIND, leg -> leg.pitch(-90))
        .legs(Rank.FRONT, leg -> leg.pitch(-27).offset(0, 1, 0))
        .head(head -> head.pitch(-15))
        .tail(tail -> tail.sway(Turn.YAW, -25, 25))
        .build();

    /**
     * A rearing horse at vanilla's own standing numbers - the body tipped up, the neck lifted
     * and curled, the forelegs lifted and pawing, the hind legs braced, the tail written
     * nothing - whose neck curl lands on the {@code head_parts} assembly the shipped pose turns
     * for the head, so the snout, mane and ears ride with it.
     */
    private static final @NotNull BuiltStyle REAR = Poses.legged("rear")
        .body(body -> body.pitch(-45))
        .head(head -> head.pitch(15).offset(0, -8.8, 8.8))
        .leg(Rank.FRONT, Side.LEFT, leg -> leg.pitch(-117.3).offset(0, -13.2, 4.4))
        .leg(Rank.FRONT, Side.RIGHT, leg -> leg.pitch(-2.7).offset(0, -13.2, 4.4))
        .legs(Rank.HIND, leg -> leg.pitch(15))
        .build();

    @Test
    @DisplayName("the wolf beg audits clean - the tail and hind legs ride the seated body")
    void begAuditsCleanWithTheWolfSeated() {
        PoseAudit audit = PoseAuditor.validate(BEG, row("minecraft:wolf"));

        assertTrue(audit.clean(), audit.report());
    }

    @Test
    @DisplayName("the beg audits on the cat the cookbook reuses it for - a flattened mesh whose seated tail rides its placed root")
    void begAuditsOnTheCookbooksCatReuse() {
        Entity cat = row("minecraft:cat");
        assumeTrue(cat.model().getFlattenedScale() != 1f && !cat.pose().states().isEmpty(),
            "the cat is flattened and carries silhouettes, or the case is vacuous");

        PoseAudit audit = assertDoesNotThrow(() -> PoseAuditor.validate(BEG, cat));

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
            assertDoesNotThrow(() -> PoseAuditor.validate(probe, row), row.id() + " audits");
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
        PoseAudit.Finding mane = finding(PoseAuditor.validate(sitting(true), bare), PoseAudit.Kind.OVERLAP, "body", "upper_body");
        assertTrue(mane.posedMin() < -2f && mane.posedMin() > -3f,
            "vanilla's own sitting silhouette sets the chest between two and three pixels into the mane: " + mane.describe());
        PoseAudit.Finding begMane = finding(PoseAuditor.validate(BEG, bare), PoseAudit.Kind.OVERLAP, "body", "upper_body");
        assertEquals(mane.posedMin(), begMane.posedMin(), 0.05f,
            "the same clearance to a twentieth of a pixel: " + begMane.describe());

        // Over the row as it ships, the same silhouette is in the envelope and the seats carry the
        // tail and the hips, so neither placement reads as a fault.
        PoseAudit seated = PoseAuditor.validate(sitting(false), wolf);
        assertTrue(seated.findings().stream().noneMatch(finding ->
                names(finding, "body", "upper_body") || names(finding, "body", "real_tail")),
            "the mane and the tail read inside what vanilla draws:\n" + seated.report());
    }

    @Test
    @DisplayName("the same beg on a wolf carrying no silhouette splits the tail - the one reading the seats answer, beside the mane contact vanilla's own branch shares")
    void begWithoutSilhouettesReadsTheTail() {
        PoseAudit audit = PoseAuditor.validate(BEG, withoutSilhouettes(row("minecraft:wolf")));

        assertFalse(audit.clean(), "with no seat to derive, the beg leaves the shipped envelope");
        // The audit spells a pair in the mesh's bone order, which is vanilla's bake order: the wolf's
        // mane and tail iterate ahead of its body.
        assertEquals(List.of("OVERLAP upper_body <-> body", "SPLIT real_tail <-> body"), pairs(audit),
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

        PoseAudit audit = PoseAuditor.validate(buried, piglin);
        PoseAudit bare = PoseAuditor.validate(buried, withoutSilhouettes(piglin));

        PoseAudit.Finding arm = finding(audit, PoseAudit.Kind.OVERLAP, "body", "right_arm");
        assertTrue(arm.posedMin() < arm.knownMin() - 1f,
            "three units into the torso is an overlap the crouch and the swing do not excuse: " + arm.describe());
        assertEquals(bare.report(), audit.report(),
            "no seat derives on a biped, so none of its silhouettes is in the envelope");
    }

    @Test
    @DisplayName("the horse rear keeps the neck assembly whole - no finding names the snout, mane or ears")
    void rearKeepsTheNeckAssemblyWhole() {
        PoseAudit audit = PoseAuditor.validate(REAR, row("minecraft:horse"));

        for (PoseAudit.Finding finding : audit.findings())
            for (String part : new String[] {"head", "upper_mouth", "mane", "left_ear", "right_ear"})
                assertFalse(finding.boneA().equals(part) || finding.boneB().equals(part),
                    "the neck assembly moves whole, yet the audit reports: " + finding.describe());
    }

    @Test
    @DisplayName("the rear reads what vanilla's own standing branch reads - the legs set into the tipped body - and the same over the bare envelope")
    void rearReadsVanillasOwnStandingContacts() {
        Entity horse = row("minecraft:horse");
        PoseAudit audit = PoseAuditor.validate(REAR, horse);
        PoseAudit standing = PoseAuditor.validate(RegistrarFixtures.silhouette(horse, "standAnimation=1", "standing"), horse);

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

        assertEquals(pairs(audit), pairs(PoseAuditor.validate(REAR, withoutSilhouettes(horse))),
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
        PoseAudit audit = PoseAuditor.validate(REAR, withHeadArticulated(row("minecraft:horse")));

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

        assertTrue(PoseAuditor.validate(flutter, row("minecraft:allay")).clean(),
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

        assertTrue(PoseAuditor.validate(levitate, row("minecraft:zombie")).clean(),
            "drifted limbs and the lift stay within the stride envelope");
    }

    @Test
    @DisplayName("a stride at its ceiling takes the ceiling's swing and clock and keeps everything else - a row with no ceiling, and an idle, answer themselves")
    void atCeilingRaisesOnlyTheWalkPair() {
        PaceCeiling ceiling = new PaceCeiling(0.79f, 2.38f);
        LinkedHashMap<String, StyleDriver> drivers = new LinkedHashMap<>();
        drivers.put("walkAnimationSpeed",
            new StyleDriver("walkAnimationSpeed", StyleDriver.Wave.HOLD, 0.05f, 0.35f, Optional.empty()));
        drivers.put("walkAnimationPos",
            new StyleDriver("walkAnimationPos", StyleDriver.Wave.RAMP, 0f, 1.05f, Optional.of("gait")));
        drivers.put("ageInTicks", new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
        drivers.put("tailAnimation", new StyleDriver("tailAnimation", StyleDriver.Wave.RAMP, 0f, 0.5f, Optional.empty()));
        PoseStyle stride = new PoseStyle(PoseStyle.STRIDE,
            Concurrent.newUnmodifiableList(new PoseStyle.StyleSource(StyleClock.STRIDE, Optional.empty())),
            Concurrent.adoptLinkedMap(drivers).toUnmodifiable(), Concurrent.newUnmodifiableList("saddle"),
            Optional.of(Age.BABY), Optional.of(48), Optional.of(ceiling));

        PoseStyle raised = PoseAuditor.atCeiling(stride);

        assertEquals(new StyleDriver("walkAnimationSpeed", StyleDriver.Wave.HOLD, 0.05f, 0.79f, Optional.empty()),
            raised.drivers().get("walkAnimationSpeed"), "the swing holds at the ceiling's speed, its wave and rest kept");
        assertEquals(new StyleDriver("walkAnimationPos", StyleDriver.Wave.RAMP, 0f, 2.38f, Optional.of("gait")),
            raised.drivers().get("walkAnimationPos"), "the walk clock ramps at the ceiling's rate, its group kept");
        assertSame(stride.drivers().get("ageInTicks"), raised.drivers().get("ageInTicks"), "elapsed age is kept");
        assertSame(stride.drivers().get("tailAnimation"), raised.drivers().get("tailAnimation"),
            "and so is every clock beside the walk pair");
        assertEquals(List.copyOf(stride.drivers().keySet()), List.copyOf(raised.drivers().keySet()),
            "in the row's own order");
        assertSame(stride.sources(), raised.sources(), "the sources are kept");
        assertSame(stride.toggles(), raised.toggles(), "the toggles are kept");
        assertEquals(stride.age(), raised.age(), "the age is kept");
        assertEquals(stride.periodTicks(), raised.periodTicks(), "the period is kept");
        assertEquals(stride.ceiling(), raised.ceiling(), "and the ceiling itself");

        // A stride recording no ceiling is audited at the walk it drives - the universal row's full
        // swing held at one and its clock ramped by it.
        PoseStyle walking = StyleCatalog.BIND_ONLY.resolve(PoseStyle.STRIDE, row -> true, "minecraft:test");
        assertTrue(walking.ceiling().isEmpty(), "the universal stride records no ceiling");
        assertEquals(1f, walking.drivers().get("walkAnimationSpeed").extent(), "it swings at one");
        assertEquals(1f, walking.drivers().get("walkAnimationPos").extent(), "and its clock ramps at one");
        assertSame(walking, PoseAuditor.atCeiling(walking), "a row with no ceiling answers itself");
        PoseStyle standing = StyleCatalog.BIND_ONLY.resolve(PoseStyle.IDLE, row -> true, "minecraft:test");
        assertSame(standing, PoseAuditor.atCeiling(standing), "and so does an idle row");
    }

    @Test
    @DisplayName("the envelope reads the stride at its ceiling, not at the walk it drives - an ambling horse audits as one at full stride")
    void theEnvelopeReadsTheCeilingNotTheWander() {
        Entity horse = row("minecraft:horse");
        PoseStyle stride = horse.styles().byId(PoseStyle.STRIDE).orElseThrow(() -> new AssertionError("the horse ships a stride"));
        assertTrue(stride.ceiling().isPresent(), "the shipped stride records the horse's fastest walk");
        assertNotEquals(PoseAuditor.validate(REAR, horse).report(),
            PoseAuditor.validate(REAR, withStride(horse, shipped -> walking(shipped, 0.25f, 0.25f, Optional.empty()))).report(),
            "the horse's leg swing sets a range the rear's report prints, or the case is vacuous");

        Entity ambling = withStride(horse, shipped -> walking(shipped, 0.25f, 0.25f, shipped.ceiling()));

        assertEquals(PoseAuditor.validate(REAR, horse).report(), PoseAuditor.validate(REAR, ambling).report(),
            "the shipped stride and one ambling under the same ceiling enter the envelope at the horse's fastest walk");
    }

    @Test
    @DisplayName("an audit reads the stride of its own age at that stride's own ceiling - a baby's is not its adult's")
    void aBabyAuditReadsTheBabyStride() {
        // A foal following its parent: its fastest walk swings less than its adult's, and its clock is
        // not three times the adult's.
        PoseStyle standing = StyleCatalog.BIND_ONLY.resolve(PoseStyle.IDLE, row -> true, "minecraft:test");
        PoseStyle adult = walking(universalStride(Age.ADULT), 0.172687f, 0.172687f,
            Optional.of(new PaceCeiling(0.507489f, 0.507489f)));
        PoseStyle baby = walking(universalStride(Age.BABY), 0.172687f, 0.518062f,
            Optional.of(new PaceCeiling(0.352423f, 1.05727f)));
        StyleCatalog catalog = new StyleCatalog(StyleCatalog.FIGURE_PERIOD_TICKS,
            Concurrent.newUnmodifiableList(standing, adult, baby));

        List<PoseStyle> babyKnown = PoseAuditor.knownStyles(catalog, Age.BABY);
        assertEquals(List.of(PoseStyle.BIND, PoseStyle.IDLE, PoseStyle.STRIDE),
            babyKnown.stream().map(PoseStyle::id).toList(), "bind, the ageless idle, and one stride");
        PoseStyle babyStride = babyKnown.getLast();
        assertEquals(Optional.of(Age.BABY), babyStride.age(), "the baby's stride answers a baby audit");
        assertEquals(0.352423f, babyStride.drivers().get("walkAnimationSpeed").extent(),
            "swinging at the baby's own fastest pace");
        assertEquals(1.05727f, babyStride.drivers().get("walkAnimationPos").extent(),
            "with its clock at the baby's own fastest rate");

        PoseStyle adultStride = PoseAuditor.knownStyles(catalog, Age.ADULT).getLast();
        assertEquals(Optional.of(Age.ADULT), adultStride.age(), "and the adult's stride answers an adult audit");
        assertEquals(0.507489f, adultStride.drivers().get("walkAnimationSpeed").extent(),
            "at the adult's own ceiling");
    }

    @Test
    @DisplayName("an audit at an age no shipped row is scoped to reads the rows a render at that age draws")
    void anAuditAtTheOtherAgeReadsTheRowsARenderDraws() {
        // The axolotl's shape: its idle and stride are both scoped to the adult, so a baby renders the
        // universal rows, and those are what a baby audit measures against.
        PoseStyle universal = StyleCatalog.BIND_ONLY.resolve(PoseStyle.IDLE, row -> true, "minecraft:test");
        PoseStyle adultIdle = new PoseStyle(PoseStyle.IDLE, universal.sources(), universal.drivers(),
            universal.toggles(), Optional.of(Age.ADULT), Optional.empty());
        StyleCatalog catalog = new StyleCatalog(StyleCatalog.FIGURE_PERIOD_TICKS,
            Concurrent.newUnmodifiableList(adultIdle, universalStride(Age.ADULT)));
        Predicate<PoseStyle> baby = row -> row.age().map(Age.BABY::equals).orElse(true);

        List<PoseStyle> babyKnown = PoseAuditor.knownStyles(catalog, Age.BABY);
        assertEquals(List.of(PoseStyle.BIND, PoseStyle.IDLE, PoseStyle.STRIDE),
            babyKnown.stream().map(PoseStyle::id).toList(), "bind, then the idle and stride a baby draws");
        assertSame(catalog.resolve(PoseStyle.IDLE, baby, "minecraft:test"), babyKnown.get(1),
            "the universal idle a baby render resolves to");
        assertSame(catalog.resolve(PoseStyle.STRIDE, baby, "minecraft:test"), babyKnown.get(2),
            "and the universal stride, which carries no ceiling to raise");

        List<PoseStyle> adultKnown = PoseAuditor.knownStyles(catalog, Age.ADULT);
        assertSame(adultIdle, adultKnown.get(1), "while an adult audit reads the adult's own idle");
    }

    @Test
    @DisplayName("a kept stride spanning both ages audits each row at its own age's stride, adult then baby - scoped to the adult it audits once, as ever")
    void aPairAuditsEachAgeAtItsOwnStride() {
        Entity piglin = ageSplitStride(row("minecraft:piglin"), 0.35f, 1.05f);
        assertTrue(piglin.axes().baby().isPresent(), "the shipped piglin carries a baby form");

        PoseAudit pair = PoseAuditor.validate(keptBuried(Optional.empty()), piglin);
        PoseAudit adult = PoseAuditor.validate(keptBuried(Optional.of(Age.ADULT)), piglin);
        PoseAudit baby = PoseAuditor.validate(keptBuried(Optional.of(Age.BABY)), piglin);
        assertFalse(adult.findings().isEmpty(), "an arm three units into the torso reads, or the case is vacuous");
        assertNotEquals(adult.findings(), baby.findings(),
            "the two ages' strides set apart what each reads, or the case cannot tell them apart");

        List<PoseAudit.Finding> expected = new ArrayList<>();
        adult.findings().forEach(finding -> expected.add(aged(finding, Age.ADULT)));
        baby.findings().forEach(finding -> expected.add(aged(finding, Age.BABY)));
        assertEquals(expected, List.copyOf(pair.findings()),
            "each row reads as its own age's style does, the adult's first:\n" + pair.report());
        assertTrue(pair.report().contains(" (adult): ") && pair.report().contains(" (baby): "),
            "and each finding names its age after the pair:\n" + pair.report());

        assertTrue(adult.findings().stream().allMatch(finding -> finding.age().isEmpty()),
            "a style installing as one row reports no age");
        assertFalse(adult.report().contains("(adult)"), "so its report reads as it always has:\n" + adult.report());
    }

    @Test
    @DisplayName("an audit reads the age its style is compiled at, the adult where it spans both")
    void theAuditedAgeIsTheStylesOwn() {
        assertEquals(Age.BABY, PoseAuditor.auditedAge(Poses.custom("probe").age(Age.BABY).build()));
        assertEquals(Age.ADULT, PoseAuditor.auditedAge(Poses.custom("probe").age(Age.ADULT).build()));
        assertEquals(Age.ADULT, PoseAuditor.auditedAge(Poses.custom("probe").build()),
            "a style spanning both ages is audited at the adult");
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
        bones.put("head", Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(0d, PoseWidth.FLOAT)));
        return row.mutate()
            .pose(new EntityPose(pose.container(), Concurrent.newUnmodifiableMap(bones), pose.clips(),
                pose.refusal(), pose.states()))
            .build();
    }

    /**
     * The same row with each of its shipped stride rows rebuilt, every other row kept.
     */
    private static @NotNull Entity withStride(@NotNull Entity row, @NotNull UnaryOperator<PoseStyle> rebuild) {
        StyleCatalog catalog = row.styles();
        return row.mutate()
            .styles(new StyleCatalog(catalog.periodTicks(), catalog.styles().stream()
                .map(style -> style.id().equals(PoseStyle.STRIDE) ? rebuild.apply(style) : style)
                .collect(Concurrent.toUnmodifiableList())))
            .build();
    }

    /**
     * A stride row swinging at one speed with its walk clock at one rate a tick, recording the given
     * ceiling, every other component kept.
     */
    private static @NotNull PoseStyle walking(
        @NotNull PoseStyle stride, float speed, float rate, @NotNull Optional<PaceCeiling> ceiling) {

        Map<String, StyleDriver> drivers = new LinkedHashMap<>(stride.drivers());
        drivers.put("walkAnimationSpeed",
            new StyleDriver("walkAnimationSpeed", StyleDriver.Wave.HOLD, 0f, speed, Optional.empty()));
        drivers.put("walkAnimationPos",
            new StyleDriver("walkAnimationPos", StyleDriver.Wave.RAMP, 0f, rate, Optional.empty()));
        return new PoseStyle(stride.id(), stride.sources(), Concurrent.newUnmodifiableMap(drivers),
            stride.toggles(), stride.age(), stride.periodTicks(), ceiling);
    }

    /**
     * The same row with its shipped stride split by age - the adult swinging at one speed with its
     * walk clock ramped by it, the baby at the same swing with its clock at another rate - every other
     * row kept.
     */
    private static @NotNull Entity ageSplitStride(@NotNull Entity row, float speed, float babyRate) {
        StyleCatalog catalog = row.styles();
        return row.mutate()
            .styles(new StyleCatalog(catalog.periodTicks(), catalog.styles().stream()
                .flatMap(style -> style.id().equals(PoseStyle.STRIDE)
                    ? Stream.of(walking(scoped(style, Age.ADULT), speed, speed, Optional.empty()),
                        walking(scoped(style, Age.BABY), speed, babyRate, Optional.empty()))
                    : Stream.of(style))
                .collect(Concurrent.toUnmodifiableList())))
            .build();
    }

    /**
     * The same row applying to one age alone, every other component kept.
     */
    private static @NotNull PoseStyle scoped(@NotNull PoseStyle style, @NotNull Age age) {
        return new PoseStyle(style.id(), style.sources(), style.drivers(), style.toggles(), Optional.of(age),
            style.periodTicks(), style.ceiling());
    }

    /**
     * A right arm driven three units into the torso under the target's kept stride, scoped to one age
     * or, where none is given, spanning both.
     */
    private static @NotNull BuiltStyle keptBuried(@NotNull Optional<Age> age) {
        CustomPose.Builder buried = Poses.custom("buried").keepStride().bone("right_arm", arm -> arm.offset(3, 0, 0));
        return age.map(buried::age).orElseGet(buried::allAges).build();
    }

    /**
     * The same finding measured for the installed row of one age.
     */
    private static @NotNull PoseAudit.Finding aged(@NotNull PoseAudit.Finding finding, @NotNull Age age) {
        return new PoseAudit.Finding(finding.kind(), finding.boneA(), finding.boneB(), finding.bindClearance(),
            finding.knownMin(), finding.knownMax(), finding.posedMin(), finding.posedMax(), finding.worstTick(),
            finding.aStanced(), finding.bStanced(), finding.sharedParent(), Optional.of(age));
    }

    /**
     * The universal walking row, applying to one age alone.
     */
    private static @NotNull PoseStyle universalStride(@NotNull Age age) {
        PoseStyle universal = StyleCatalog.BIND_ONLY.resolve(PoseStyle.STRIDE, row -> true, "minecraft:test");
        return new PoseStyle(universal.id(), universal.sources(), universal.drivers(), universal.toggles(),
            Optional.of(age), universal.periodTicks());
    }

    /**
     * The one finding of a kind on a pair, failing with the whole report when absent. A pair is
     * matched either way round, the audit spelling it in the mesh's bone order.
     */
    private static @NotNull PoseAudit.Finding finding(
        @NotNull PoseAudit audit, @NotNull PoseAudit.Kind kind,
        @NotNull String boneA, @NotNull String boneB) {

        return audit.findings().stream()
            .filter(candidate -> candidate.kind() == kind && names(candidate, boneA, boneB))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no " + kind + " finding on " + boneA + " <-> " + boneB + " in:\n" + audit.report()));
    }


    @Test
    @DisplayName("an address a distinct pass does not declare, beside one it does, is reported - a strict install refuses it on that pass")
    void aDistinctHalfMatchPassIsReported() {
        EntityMesh partial = CompilerFixtures.humanoid();
        partial.getBones().remove("head");
        Entity row = RegistrarFixtures.entity("minecraft:test", CompilerFixtures.humanoid(),
            CompilerFixtures.pose(List.of(), Map.of(), List.of()), StyleCatalog.BIND_ONLY,
            RegistrarFixtures.overlay(partial, CompilerFixtures.pose(List.of(), Map.of(), List.of())));

        // The arm lands on the pass, so the install compiles it, and the head the pass lacks is
        // what a strict install refuses the pass over.
        PoseAudit audit = PoseAuditor.validate(Poses.humanoid("nod")
            .head(head -> head.pitch(-15))
            .arm(Side.RIGHT, arm -> arm.roll(90))
            .build(), row);

        assertEquals(List.of("bone 'head'"), described(audit),
            () -> "the pass the style reaches nothing on is what the install would refuse over: " + audit.drops());
    }

    @Test
    @DisplayName("a pass sharing the body's pose reports nothing - the install re-points it rather than compiling it")
    void aPassSharingTheBodysPoseReportsNothing() {
        Entity row = RegistrarFixtures.entity("minecraft:test", CompilerFixtures.humanoid(), EntityPose.NONE,
            StyleCatalog.BIND_ONLY, RegistrarFixtures.overlay(wings(), EntityPose.NONE));

        PoseAudit audit = PoseAuditor.validate(Poses.humanoid("nod").head(head -> head.pitch(-15)).build(), row);

        assertEquals(List.of(), described(audit),
            () -> "the wings follow the body by instance, so no strict install refuses over them: " + audit.drops());
    }

    @Test
    @DisplayName("a distinct pass no written bone lands on reports nothing - the install leaves it untouched")
    void aPassNothingLandsOnReportsNothing() {
        Entity row = RegistrarFixtures.entity("minecraft:test", CompilerFixtures.humanoid(),
            CompilerFixtures.pose(List.of(), Map.of(), List.of()), StyleCatalog.BIND_ONLY,
            RegistrarFixtures.overlay(wings(), CompilerFixtures.pose(List.of(), Map.of(), List.of())));

        PoseAudit audit = PoseAuditor.validate(Poses.humanoid("nod").head(head -> head.pitch(-15)).build(), row);

        assertEquals(List.of(), described(audit),
            () -> "the wings are skipped whole, so no strict install refuses over them: " + audit.drops());
    }

    /**
     * A pass mesh of one wing, declaring no bone the humanoid body does.
     */
    private static @NotNull EntityMesh wings() {
        EntityMesh wings = new EntityMesh();
        wings.getBones().put("left_wing", CompilerFixtures.bone(2f, 4f, 0f, 0f, 0f, 0f, 1f, null));
        return wings;
    }

    /**
     * The unreached addresses of an audit as their readings, in report order.
     */
    private static @NotNull List<String> described(@NotNull PoseAudit audit) {
        return audit.drops().stream().map(PoseCompiler.Unreached::describe).toList();
    }

}
