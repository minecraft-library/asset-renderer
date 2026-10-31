package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.call.result.MenuResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Holds a menu's static composite to laying each slot's image down in the convention it holds. A render
 * is a canvas, every translucent colour already scaled by its alpha, so it lands over the slot beneath it
 * as drawing the subject over that slot would, rather than scaled by its alpha a second time; a straight
 * picture lands as the client lays a straight texel.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("A menu lays a slot's render down as it was drawn")
class MenuRendererSlotBlendTest {

    @Test
    @DisplayName("a translucent slot render lands over the opaque slot as drawing it there would")
    void aTranslucentSlotRenderLandsAsDrawn() {
        int drawn = ColorMath.blend(0x80FFFFFF, ColorMath.TRANSPARENT, BlendMode.NORMAL);
        PixelBuffer pixels = PixelBuffer.canvas(32, 32);
        pixels.fill(0x80FFFFFF);
        Laid laid = lay(pixels);

        assertThat("the canvas holds the colour drawn over nothing", pixels.getPixel(0, 0), is(drawn));
        assertThat("the slot beneath is opaque", ColorMath.alpha(laid.beneath()), is(0xFF));
        assertThat("the render is laid over the slot as a finished render",
            laid.pixel(), is(ColorMath.blend(drawn, laid.beneath(), BlendMode.PREMULTIPLIED)));
        assertThat("and not scaled by its alpha a second time",
            laid.pixel(), is(not(ColorMath.blend(drawn, laid.beneath(), BlendMode.NORMAL))));
    }

    @Test
    @DisplayName("a straight translucent slot picture lands over the opaque slot as a straight texel does")
    void aStraightSlotPictureLandsAsATexel() {
        PixelBuffer pixels = PixelBuffer.straight(32, 32);
        pixels.fill(0x80FFFFFF);
        Laid laid = lay(pixels);

        assertThat("the slot beneath is opaque", ColorMath.alpha(laid.beneath()), is(0xFF));
        assertThat("the picture is laid over the slot as a straight texel",
            laid.pixel(), is(ColorMath.blend(0x80FFFFFF, laid.beneath(), BlendMode.NORMAL)));
    }

    /**
     * Renders a one-row chest whose first slot holds a picture, and the same chest with every slot empty.
     *
     * @param pixels the picture the first slot's content answers
     * @return the pixel at the slot's centre, and the empty chest's pixel there
     */
    private static @NotNull Laid lay(@NotNull PixelBuffer pixels) {
        ConcurrentMap<Integer, MenuOptions.MenuSlotContent> slots = Concurrent.newMap();
        slots.put(0, MenuOptions.MenuSlotContent.of(() -> RenderResult.of(Timeline.still(pixels))));
        MenuOptions bare = MenuOptions.builder().type(MenuOptions.Type.CHEST).rows(1).build();
        MenuRenderer renderer = new MenuRenderer(ClientAssetsExtension.context());

        MenuResult menu = renderer.render(bare.mutate().slots(slots).build());
        MenuResult.Slot slot = menu.slots().getFirst();
        int x = slot.x() + slot.width() / 2;
        int y = slot.y() + slot.height() / 2;
        int beneath = renderer.render(bare).image().toPixelBuffer().getPixel(x, y);
        return new Laid(menu.image().toPixelBuffer().getPixel(x, y), beneath);
    }

    /**
     * The pixel a slot's picture leaves at the slot's centre, and the empty menu's pixel there.
     *
     * @param pixel the composited pixel
     * @param beneath the slot's own pixel, with nothing laid over it
     */
    private record Laid(int pixel, int beneath) {}

}
