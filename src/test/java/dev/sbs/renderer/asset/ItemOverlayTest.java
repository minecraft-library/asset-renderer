package dev.sbs.renderer.asset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Verifies the inner {@link Item.Overlay} sealed interface: constant values, per-variant kind
 * dispatch, and record equality.
 */
class ItemOverlayTest {

    @Test
    @DisplayName("LEATHER_DEFAULT_ARGB matches vanilla DyedItemColor constant")
    void leatherDefaultMatchesVanilla() {
        // net.minecraft.world.item.component.DyedItemColor uses 0xFFA06540 as the leather default.
        assertThat(Item.Overlay.LEATHER_DEFAULT_ARGB, is(equalTo(0xFFA06540)));
    }

    @Test
    @DisplayName("FIREWORK_DEFAULT_ARGB is a visible gray placeholder")
    void fireworkDefaultIsVisible() {
        assertThat(Item.Overlay.FIREWORK_DEFAULT_ARGB, is(equalTo(0xFF808080)));
        // Alpha is fully opaque so the placeholder renders visible rather than ghosted.
        assertThat((Item.Overlay.FIREWORK_DEFAULT_ARGB >>> 24) & 0xFF, is(0xFF));
    }

    @Test
    @DisplayName("each overlay record reports its matching Kind")
    void kindDispatchMatchesRecord() {
        Item.Overlay leather = new Item.Overlay.Leather("base", "overlay", Item.Overlay.LEATHER_DEFAULT_ARGB);
        Item.Overlay potion = new Item.Overlay.Potion("base", "overlay");
        Item.Overlay tippedArrow = new Item.Overlay.TippedArrow("base", "overlay");
        Item.Overlay firework = new Item.Overlay.Firework("base", "overlay", Item.Overlay.FIREWORK_DEFAULT_ARGB);

        assertThat(leather.kind(), is(Item.Overlay.Kind.LEATHER));
        assertThat(potion.kind(), is(Item.Overlay.Kind.POTION));
        assertThat(tippedArrow.kind(), is(Item.Overlay.Kind.TIPPED_ARROW));
        assertThat(firework.kind(), is(Item.Overlay.Kind.FIREWORK));
    }

    @Test
    @DisplayName("overlay records preserve base and overlay texture references")
    void textureReferencesRoundTrip() {
        Item.Overlay.Firework firework = new Item.Overlay.Firework(
            "minecraft:item/firework_star",
            "minecraft:item/firework_star_overlay",
            0xFFFF4400
        );
        assertThat(firework.baseTexture(), is("minecraft:item/firework_star"));
        assertThat(firework.overlayTexture(), is("minecraft:item/firework_star_overlay"));
        assertThat(firework.defaultColor(), is(0xFFFF4400));
    }

    @Test
    @DisplayName("record equality treats identical components as equal")
    void recordEquality() {
        Item.Overlay a = new Item.Overlay.Leather("base", "overlay", 0xFFA06540);
        Item.Overlay b = new Item.Overlay.Leather("base", "overlay", 0xFFA06540);
        Item.Overlay c = new Item.Overlay.Leather("other", "overlay", 0xFFA06540);

        assertThat(a, is(equalTo(b)));
        assertThat(a, is(not(sameInstance(b))));
        assertThat(a, is(not(equalTo(c))));
    }

    private static <T> org.hamcrest.Matcher<T> not(org.hamcrest.Matcher<T> matcher) {
        return org.hamcrest.Matchers.not(matcher);
    }

}
