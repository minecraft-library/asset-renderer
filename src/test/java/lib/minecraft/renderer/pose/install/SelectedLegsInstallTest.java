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
                Reach.ROOT, false), leg -> leg.pitch(-10))
            .build();
        BuiltStyle chain = Poses.custom("chain")
            .legs(new LimbSelector.Legs(Optional.empty(), Optional.empty(),
                Reach.CHAIN, false), leg -> leg.pitch(-10))
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
