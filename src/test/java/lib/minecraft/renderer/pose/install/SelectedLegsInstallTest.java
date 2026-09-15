package lib.minecraft.renderer.pose.install;

import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.LimbSelector;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Reach;
import lib.minecraft.renderer.pose.author.Side;
import lib.minecraft.renderer.pose.author.Turn;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One authored stance over however many legs a subject carries.
 *
 * <p>The point of a selector is that the count is the mesh's answer: the same chain reaches a
 * wolf's four legs and a spider's eight without the author writing either number, because the
 * roster resolves against the row being built. These install the identical style on subjects with
 * different leg counts and read the driver fields back out.
 */
@DisplayName("a selected stance lands on every leg the subject carries")
class SelectedLegsInstallTest {

    /**
     * One stance over every leg of every row, reaching each leg's root.
     */
    private static @NotNull BuiltStyle splay() {
        return Poses.custom("splay")
            .legs(new LimbSelector.Legs(Optional.empty(), Optional.empty()),
                leg -> leg.pitch(-20))
            .build();
    }

    /**
     * The driver fields one style installed on one subject spells, sorted.
     */
    private static @NotNull List<String> fieldsOf(@NotNull BuiltStyle style,
                                                  @NotNull String entityId) {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.add(entityId, style);
        Entity woven = registrar.definitions().get(entityId);
        PoseStyle installed = woven.styles().byId(style.styleId()).orElseThrow();
        return installed.drivers().keySet().stream().sorted().toList();
    }

    /**
     * One style tolerantly installed on one subject, and the row it wove.
     */
    private static @NotNull Entity tolerantly(@NotNull BuiltStyle style,
                                              @NotNull String entityId) {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.addTolerant(entityId, style);
        return registrar.definitions().get(entityId);
    }

    /**
     * The clip one style's play site carries on one subject.
     */
    private static @NotNull PoseClip clipOf(@NotNull BuiltStyle style, @NotNull String entityId) {
        return tolerantly(style, entityId).pose().clips().stream()
            .filter(site -> site.coordinate().equals("style:" + style.styleId()))
            .findFirst().orElseThrow(() -> new AssertionError(
                "no play site for '" + style.styleId() + "' on " + entityId))
            .clip();
    }

    /**
     * One timeline over every leg of every row.
     */
    private static @NotNull BuiltStyle wag() {
        return Poses.custom("wag")
            .legs(new LimbSelector.Legs(Optional.empty(), Optional.empty()),
                leg -> leg.timeline(track -> track.swing(Turn.PITCH, -10, 10).over(1.0)))
            .build();
    }

    @Test
    @DisplayName("the same chain reaches a walker's four legs and a crawler's eight")
    void oneChainReachesEveryLegCount() {
        List<String> wolf = fieldsOf(splay(), "minecraft:wolf");
        List<String> spider = fieldsOf(splay(), "minecraft:spider");

        assertEquals(4, wolf.size(), () -> "the wolf answers four legs: " + wolf);
        assertEquals(8, spider.size(), () -> "the spider answers eight legs: " + spider);
        assertTrue(wolf.stream().allMatch(field -> field.endsWith("$x_rot")),
            () -> "each leg takes the authored pitch: " + wolf);
        assertTrue(spider.stream().allMatch(field -> field.endsWith("$x_rot")),
            () -> "each leg takes the authored pitch: " + spider);
    }

    @Test
    @DisplayName("a rank narrows the stance to one row, and a side to one leg of it")
    void rankAndSideNarrowTheReach() {
        BuiltStyle row = Poses.custom("row")
            .legs(new LimbSelector.Legs(Optional.of(Rank.HIND), Optional.empty()),
                leg -> leg.pitch(-20))
            .build();
        BuiltStyle one = Poses.custom("one")
            .legs(new LimbSelector.Legs(Optional.of(Rank.HIND), Optional.of(Side.RIGHT)),
                leg -> leg.pitch(-20))
            .build();

        List<String> rowFields = fieldsOf(row, "minecraft:wolf");
        List<String> oneField = fieldsOf(one, "minecraft:wolf");

        assertEquals(2, rowFields.size(), () -> "a rank reaches the row's pair: " + rowFields);
        assertEquals(1, oneField.size(), () -> "a rank and a side reach one leg: " + oneField);
        assertTrue(oneField.getFirst().contains("right_hind_leg"),
            () -> "and it is the leg named: " + oneField);
    }

    @Test
    @DisplayName("reaching the chain takes the segments below a leg, where the subject has them")
    void theChainTakesTheSegments() {
        BuiltStyle roots = Poses.custom("roots")
            .legs(new LimbSelector.Legs(Optional.empty(), Optional.empty(),
                Reach.ROOT, LimbSelector.Stamp.LONE), leg -> leg.pitch(-10))
            .build();
        BuiltStyle chain = Poses.custom("chain")
            .legs(new LimbSelector.Legs(Optional.empty(), Optional.empty(),
                Reach.CHAIN, LimbSelector.Stamp.LONE), leg -> leg.pitch(-10))
            .build();

        List<String> rootFields = fieldsOf(roots, "minecraft:ender_dragon");
        List<String> chainFields = fieldsOf(chain, "minecraft:ender_dragon");

        assertEquals(4, rootFields.size(), () -> "four legs at the root: " + rootFields);
        assertEquals(12, chainFields.size(),
            () -> "four legs and their eight segments over the chain: " + chainFields);
        assertTrue(chainFields.stream().anyMatch(field -> field.contains("_foot")),
            () -> "the chain reaches the last link: " + chainFields);
        assertFalse(rootFields.stream().anyMatch(field -> field.contains("_foot")),
            () -> "the root does not: " + rootFields);
    }

    @Test
    @DisplayName("a subject with no legs refuses a strict install, naming the selector that reached nothing")
    void alegLessSubjectRefusesStrictly() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> StyleRegistrar.ofShipped().add("minecraft:squid", splay()));

        assertTrue(refusal.getMessage().contains("every row both sides ROOT"),
            () -> "the refusal names the selector in the author's own terms: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("minecraft:squid"), refusal.getMessage());
    }

    @Test
    @DisplayName("the two end ranks name two rows on a walker and one row on a biped, which refuses")
    void endRanksOnOneRowRefuse() {
        BuiltStyle stretch = Poses.legged("stretch")
            .legs(Rank.FRONT, leg -> leg.pitch(-30))
            .legs(Rank.HIND, leg -> leg.pitch(15))
            .build();

        List<String> wolf = fieldsOf(stretch, "minecraft:wolf");
        assertEquals(4, wolf.size(), () -> "two rows answer two ranks: " + wolf);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> StyleRegistrar.ofShipped().addTolerant("minecraft:zombie", stretch));
        assertTrue(refusal.getMessage().contains("answers with one row"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("FRONT"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("HIND"), refusal.getMessage());
    }

    @Test
    @DisplayName("one gait chain walks a biped, a walker and a crawler with no count in it")
    void oneGaitWalksEveryLegCount() {
        BuiltStyle scuttle = Poses.legged("scuttle")
            .gait(gait -> gait.over(0.8).step(leg -> leg.sway(Turn.YAW, -23, 23)))
            .build();

        List<String> biped = fieldsOf(scuttle, "minecraft:zombie");
        List<String> walker = fieldsOf(scuttle, "minecraft:wolf");
        List<String> crawler = fieldsOf(scuttle, "minecraft:spider");

        assertEquals(2, biped.size(), () -> "two legs: " + biped);
        assertEquals(4, walker.size(), () -> "four: " + walker);
        assertEquals(8, crawler.size(), () -> "eight: " + crawler);
        assertTrue(crawler.stream().allMatch(field -> field.endsWith("$y_rot")),
            () -> "every leg sweeps the yaw the one step stated: " + crawler);
    }

    /**
     * The rotation keyframes one style plays on one bone of one subject, as second-and-pitch pairs.
     */
    private static @NotNull List<String> framesOf(@NotNull BuiltStyle style,
                                                  @NotNull String entityId,
                                                  @NotNull String bone) {
        return tolerantly(style, entityId).pose().clips().stream()
            .filter(site -> site.coordinate().equals("style:" + style.styleId()))
            .findFirst().orElseThrow().clip().channels().stream()
            .filter(channel -> channel.bone().equals(bone))
            .findFirst().orElseThrow(() -> new AssertionError("no channel for " + bone))
            .keyframes().stream()
            .map(frame -> frame.timeSeconds() + " " + frame.x())
            .toList();
    }

    @Test
    @DisplayName("a phase reaches a shape stated once over the whole roster, row by row")
    void aPhaseReachesTheWholeRosterStep() {
        BuiltStyle amble = Poses.legged("amble")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.8)))
                .phase(Rank.HIND, 0.5))
            .build();

        List<String> front = framesOf(amble, "minecraft:wolf", "right_front_leg");
        List<String> hind = framesOf(amble, "minecraft:wolf", "right_hind_leg");

        assertEquals(List.of("0.0 -0.34906584", "0.4 0.34906584", "0.8 -0.34906584"), front,
            () -> "the front row opens where the shape does: " + front);
        assertEquals(List.of("0.0 0.34906584", "0.4 -0.34906584", "0.8 0.34906584"), hind,
            () -> "and the hind row half a cycle later, from the same one stated shape: " + hind);
    }

    @Test
    @DisplayName("a second gait keeps the first one's offsets rather than unsaying them")
    void asecondGaitKeepsTheFirstOnesOffsets() {
        BuiltStyle twogaits = Poses.legged("twogaits")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.8)))
                .phase(Rank.FRONT, 0.5))
            .gait(gait -> gait
                .step(Rank.HIND, leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.8))))
            .build();

        assertEquals(List.of("0.0 0.34906584", "0.4 -0.34906584", "0.8 0.34906584"),
            framesOf(twogaits, "minecraft:wolf", "right_front_leg"),
            "a row's offset is the style's, so a later cycle naming other rows leaves it standing");
    }

    @Test
    @DisplayName("a row one bone paints whole answers the row stamp and refuses the single leg")
    void aFusedRowAnswersTheRowAndNotTheLeg() {
        BuiltStyle hover = Poses.legged("hover")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.pitchBy(22.5))
                .step(Rank.SECOND, leg -> leg.pitchBy(45))
                .step(Rank.HIND, leg -> leg.pitchBy(45)))
            .build();

        List<String> bee = fieldsOf(hover, "minecraft:bee");
        assertEquals(3, bee.size(),
            () -> "three rows, three bones, one stance each rather than two cancelling: " + bee);
        assertTrue(bee.stream().anyMatch(field -> field.contains("front_legs")), bee::toString);
        assertTrue(bee.stream().anyMatch(field -> field.contains("middle_legs")), bee::toString);
        assertTrue(bee.stream().anyMatch(field -> field.contains("back_legs")), bee::toString);

        BuiltStyle one = Poses.legged("lift")
            .leg(Rank.FRONT, Side.RIGHT, leg -> leg.pitchBy(22.5))
            .build();
        Entity woven = tolerantly(one, "minecraft:bee");
        assertEquals(List.of(), woven.styles().byId("lift").orElseThrow()
                .drivers().keySet().stream().sorted().toList(),
            "one bone paints both legs of the row, so the row has no right leg to stance");
    }

    @Test
    @DisplayName("a gait's rows are the mesh's, so a keyed row lands only where the mesh has one")
    void aKeyedRowLandsOnlyWhereTheMeshCarriesIt() {
        BuiltStyle amble = Poses.legged("amble")
            .gait(gait -> gait.over(0.8)
                .step(Rank.FRONT, leg -> leg.sway(Turn.PITCH, -35, 35))
                .step(Rank.SECOND, leg -> leg.sway(Turn.PITCH, -20, 20)))
            .build();

        List<String> crawler = fieldsOf(amble, "minecraft:spider");
        assertEquals(4, crawler.size(),
            () -> "a crawler carries a second row, so both shapes land: " + crawler);

        Entity walker = tolerantly(amble, "minecraft:wolf");
        List<String> fields = walker.styles().byId("amble").orElseThrow()
            .drivers().keySet().stream().sorted().toList();
        assertEquals(2, fields.size(),
            () -> "a walker carries no row between its ends, so only the front shape lands: " + fields);
        assertTrue(fields.stream().allMatch(field -> field.contains("front")),
            () -> "and it lands on the front row: " + fields);
    }

    @Test
    @DisplayName("one opposed side alternates a walker's pairs and leaves a fused row whole")
    void anOpposedSideReachesOnlyARowWithTwoOfThem() {
        BuiltStyle pace = Poses.legged("pace")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.8)))
                .oppose(0.5))
            .build();

        List<String> near = framesOf(pace, "minecraft:wolf", "right_front_leg");
        List<String> far = framesOf(pace, "minecraft:wolf", "left_front_leg");
        assertEquals(List.of("0.0 -0.34906584", "0.4 0.34906584", "0.8 -0.34906584"), near,
            () -> "the near side takes the shape as written: " + near);
        assertEquals(List.of("0.0 0.34906584", "0.4 -0.34906584", "0.8 0.34906584"), far,
            () -> "and the far side half a cycle behind it: " + far);
        assertEquals(near, framesOf(pace, "minecraft:wolf", "right_hind_leg"),
            "a side offset states nothing about rows, so both near legs run together");

        for (String entityId : List.of("minecraft:bee", "minecraft:bat")) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> StyleRegistrar.ofShipped().addTolerant(entityId, pace),
                () -> "one bone paints both legs of each row on " + entityId + ", so the "
                    + "alternation this states lands on nothing");
            assertTrue(refusal.getMessage().contains("carry no side"), refusal::getMessage);
        }
    }

    @Test
    @DisplayName("a trot runs a walker's diagonals and refuses every other leg count")
    void aTrotServesTwoRowsAndSaysSoElsewhere() {
        BuiltStyle canter = Poses.legged("canter")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.8)))
                .trot(0.5))
            .build();

        List<String> leading = List.of("0.0 -0.34906584", "0.4 0.34906584", "0.8 -0.34906584");
        List<String> following = List.of("0.0 0.34906584", "0.4 -0.34906584", "0.8 0.34906584");
        assertEquals(leading, framesOf(canter, "minecraft:wolf", "right_front_leg"),
            "the near front leg leads");
        assertEquals(leading, framesOf(canter, "minecraft:wolf", "left_hind_leg"),
            "with the leg across the body from it");
        assertEquals(following, framesOf(canter, "minecraft:wolf", "left_front_leg"),
            "and the other diagonal follows");
        assertEquals(following, framesOf(canter, "minecraft:wolf", "right_hind_leg"),
            "both of it");

        for (String entityId : List.of("minecraft:zombie", "minecraft:spider", "minecraft:bee")) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> StyleRegistrar.ofShipped().addTolerant(entityId, canter),
                () -> "a diagonal has no reading on " + entityId);
            assertTrue(refusal.getMessage().contains("no unique reading of"),
                refusal::getMessage);
        }

        assertEquals(List.of(), tolerantly(canter, "minecraft:squid")
                .styles().byId("canter").orElseThrow().drivers().keySet().stream()
                .filter(field -> field.contains("leg")).sorted().toList(),
            "and a subject with no legs at all keeps the drop, because it has none rather than "
                + "the wrong number of them");
    }

    @Test
    @DisplayName("one shape at two amplitudes, stated once, over a real four-legged subject")
    void aGainCarriesTheSecondAmplitude() {
        BuiltStyle canter = Poses.legged("canter")
            .gait(gait -> gait
                .over(0.8)
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -32, 32).over(0.8)))
                .gain(Rank.HIND, 0.625)
                .trot(0.5))
            .build();
        float front = (float) Math.toRadians(32);
        float hind = (float) Math.toRadians(20);

        assertEquals(List.of("0.0 " + -front, "0.4 " + front, "0.8 " + -front),
            framesOf(canter, "minecraft:horse", "right_front_leg"),
            "the front row reaches the whole of the one authored bound");
        assertEquals(List.of("0.0 " + -hind, "0.4 " + hind, "0.8 " + -hind),
            framesOf(canter, "minecraft:horse", "left_hind_leg"),
            "the leg across the body from it travels with it and five eighths as far");
        assertEquals(List.of("0.0 " + front, "0.4 " + -front, "0.8 " + front),
            framesOf(canter, "minecraft:horse", "left_front_leg"),
            "and the following pair carries the same two amplitudes half a cycle behind");
        assertEquals(List.of("0.0 " + hind, "0.4 " + -hind, "0.8 " + hind),
            framesOf(canter, "minecraft:horse", "right_hind_leg"),
            "which is four legs, two amplitudes and one diagonal out of three numbers and "
                + "one shape");
    }

    @Test
    @DisplayName("one trailing cycle reaches a dragon's twelve leg bones with no reach in it")
    void aTrailReachesTheWholeChain() {
        BuiltStyle glide = Poses.legged("glide")
            .gait(gait -> gait
                .share()
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -6, 6).over(0.8)))
                .trail(0.5, 0.5))
            .build();
        float root = (float) Math.toRadians(6);
        float link = (float) Math.toRadians(3);
        float foot = (float) Math.toRadians(1.5);

        assertEquals(12, clipOf(glide, "minecraft:ender_dragon").channels().size(),
            "four roots, four links and four feet, from a chain naming none of them");
        assertEquals(List.of("0.0 " + -root, "0.4 " + root, "0.8 " + -root),
            framesOf(glide, "minecraft:ender_dragon", "right_front_leg"),
            "the root travels the whole authored bound from the cycle's own start");
        assertEquals(List.of("0.0 " + link, "0.4 " + -link, "0.8 " + link),
            framesOf(glide, "minecraft:ender_dragon", "right_front_leg_tip"),
            "the link below it half as far and half a cycle late");
        assertEquals(List.of("0.0 " + -foot, "0.4 " + foot, "0.8 " + -foot),
            framesOf(glide, "minecraft:ender_dragon", "right_front_foot"),
            "and the foot a quarter as far, a whole cycle late, which wraps back to the "
                + "cycle's start");
    }

    @Test
    @DisplayName("a leg timeline coins the same clip whether or not the subject answers a leg")
    void anUnansweredSelectorStillCoinsTheClip() {
        BuiltStyle wag = wag();

        PoseClip walker = clipOf(wag, "minecraft:wolf");
        PoseClip crawler = clipOf(wag, "minecraft:spider");
        PoseClip legless = clipOf(wag, "minecraft:squid");

        assertEquals(4, walker.channels().size(), "a wolf plays four legs");
        assertEquals(8, crawler.channels().size(), "a spider plays eight");
        assertEquals(0, legless.channels().size(), "a squid plays none");
        assertEquals(walker.lengthSeconds(), legless.lengthSeconds(),
            "the length a track states is the style's, never the mesh's");
        assertEquals(walker.looping(), legless.looping(),
            "and so is whether the clip loops or holds");

        assertEquals(List.of("style$wag", "style$wag$clock"),
            tolerantly(wag, "minecraft:squid").styles().byId("wag").orElseThrow()
                .drivers().keySet().stream().sorted().toList(),
            "a subject answering no leg is still gated and clocked");
    }

    @Test
    @DisplayName("mixing loop() and once() refuses on a subject whose legs the selector misses")
    void theLoopRefusalDoesNotDependOnTheMesh() {
        BuiltStyle mixed = Poses.custom("mixed")
            .bone("body", body -> body.timeline(track -> track.swing(Turn.PITCH, -2, 2)))
            .legs(new LimbSelector.Legs(Optional.empty(), Optional.empty()),
                leg -> leg.timeline(track -> track.swing(Turn.PITCH, -10, 10).once()))
            .build();

        for (String entityId : List.of("minecraft:wolf", "minecraft:squid")) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> StyleRegistrar.ofShipped().addTolerant(entityId, mixed),
                () -> "a clip loops or holds as one on " + entityId);
            assertTrue(refusal.getMessage().contains("loop() and once()"), refusal.getMessage());
        }
    }

    @Test
    @DisplayName("a subject with no legs drops the stance on a tolerant install and weaves the rest")
    void alegLessSubjectDropsTolerantly() {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.addTolerant("minecraft:squid", splay());
        Entity woven = registrar.definitions().get("minecraft:squid");
        PoseStyle installed = woven.styles().byId("splay").orElseThrow();

        assertEquals(List.of(), installed.drivers().keySet().stream().sorted().toList(),
            "a squid answers no leg, so the stance lands nowhere");
    }

}
