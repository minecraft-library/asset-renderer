package lib.minecraft.renderer.pose.install;

import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Turn;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two clocks one style already runs, and the rate each of them keeps.
 *
 * <p>A wave lowers to a driver field the render samples against the style's own window; a timeline
 * lowers to a clip carrying the length its author gave it. Nothing joins the two, so a style that
 * spells both runs two rates at once - which is what a paddling turtle wants, a fast row pair
 * against a slow diagonal, and it wants it without a second clip per style.
 *
 * <p>Pinned on the shipped turtle rather than a fixture because the claim is about a real row: the
 * mesh names four legs over two rows, and the style has to reach both rows through the tier.
 */
@DisplayName("a style carries a driver clock and a clip clock at once")
class StyleClockTest {

    /** The style window, in seconds - the clock a wave rides. */
    private static final double WINDOW = 2.0d;

    /** The clip length, in seconds - a clock of its own, deliberately not the window. */
    private static final double CLIP = 1.2d;

    /**
     * A paddle over the shipped turtle: the front row waving on the window, the hind row keyed on a
     * shorter clip of its own.
     */
    private static @NotNull BuiltStyle paddle() {
        return Poses.legged("paddle")
            .period(WINDOW)
            .legs(Rank.FRONT, leg -> leg.sway(Turn.PITCH, -14, 14))
            .legs(Rank.HIND, leg -> leg.timeline(track -> track
                .swing(Turn.PITCH, -5, 5)
                .over(CLIP)))
            .build();
    }

    /**
     * The turtle with the paddle woven onto it.
     */
    private static @NotNull Entity woven() {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.add("minecraft:turtle", paddle());
        return registrar.definitions().get("minecraft:turtle");
    }

    @Test
    @DisplayName("the wave lowers to a driver field on each front leg, riding the style's own window")
    void theWaveRidesTheWindow() {
        PoseStyle installed = woven().styles().byId("paddle").orElseThrow();

        for (String leg : List.of("right_front_leg", "left_front_leg")) {
            StyleDriver driver = installed.drivers().get("style$paddle$" + leg + "$x_rot");
            assertNotNull(driver, "'" + leg + "' carries the wave's own field");
            assertEquals(StyleDriver.Wave.SWEEP, driver.wave(), "'" + leg + "' sweeps there and back");
        }
    }

    @Test
    @DisplayName("the timeline lowers to a clip keeping its own length, which is not the window")
    void theClipKeepsItsOwnLength() {
        Entity woven = woven();
        List<PoseClip> clips = woven.pose().clips().stream()
            .filter(site -> site.field().filter("style$paddle"::equals).isPresent())
            .map(site -> site.clip())
            .toList();

        assertEquals(1, clips.size(), "the style folds its timelines into one clip");
        assertEquals((float) CLIP, clips.getFirst().lengthSeconds(),
            "the clip keeps the length its author gave it");
        assertFalse(clips.getFirst().lengthSeconds() == (float) WINDOW,
            "which is a clock of its own rather than the window");
    }

    @Test
    @DisplayName("both clocks land in one style, so two rates need no second clip")
    void bothClocksLandTogether() {
        Entity woven = woven();
        PoseStyle installed = woven.styles().byId("paddle").orElseThrow();

        boolean waved = installed.drivers().keySet().stream()
            .anyMatch(field -> field.startsWith("style$paddle$right_front_leg"));
        boolean keyed = woven.pose().clips().stream()
            .anyMatch(site -> site.field().filter("style$paddle"::equals).isPresent());

        assertTrue(waved, "the front row waves");
        assertTrue(keyed, "the hind row is keyed");
        assertTrue(waved && keyed, "and one style holds both, at two lengths");
    }

    @Test
    @DisplayName("the keyed row's channels name the hind legs, so the two rates reach different legs")
    void theTwoRatesReachDifferentLegs() {
        Entity woven = woven();
        PoseClip clip = woven.pose().clips().stream()
            .filter(site -> site.field().filter("style$paddle"::equals).isPresent())
            .map(site -> site.clip())
            .findFirst()
            .orElseThrow();

        List<String> keyed = clip.channels().stream().map(PoseClip.Channel::bone).sorted().toList();
        assertEquals(List.of("left_hind_leg", "right_hind_leg"), keyed,
            "the clip reaches the hind row and nothing else");
    }

}
