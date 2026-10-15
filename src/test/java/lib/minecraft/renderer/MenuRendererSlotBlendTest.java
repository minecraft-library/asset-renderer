package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.call.result.MenuResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Holds a menu's static composite to laying each slot's render down as a finished render. A slot's
 * render holds every translucent colour already scaled by its alpha, so it lands over the slot beneath
 * it as drawing the subject over that slot would, rather than scaled by its alpha a second time.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("A menu lays a slot's render down as it was drawn")
class MenuRendererSlotBlendTest {

    @Test
    @DisplayName("a translucent slot render lands over the opaque slot as drawing it there would")
    void aTranslucentSlotRenderLandsAsDrawn() {
        int drawn = ColorMath.blend(0x80FFFFFF, ColorMath.TRANSPARENT, BlendMode.NORMAL);
        PixelBuffer pixels = PixelBuffer.create(32, 32);
        pixels.fill(drawn);

        ConcurrentMap<Integer, MenuOptions.MenuSlotContent> slots = Concurrent.newMap();
        slots.put(0, MenuOptions.MenuSlotContent.of(() -> RenderResult.of(Timeline.still(pixels))));
        MenuOptions bare = MenuOptions.builder().type(MenuOptions.Type.CHEST).rows(1).build();
        MenuRenderer renderer = new MenuRenderer(ClientAssetsExtension.context());

        MenuResult menu = renderer.render(bare.mutate().slots(slots).build());
        MenuResult.Slot slot = menu.slots().getFirst();
        int x = slot.x() + slot.width() / 2;
        int y = slot.y() + slot.height() / 2;
        int beneath = renderer.render(bare).image().toPixelBuffer().getPixel(x, y);

        assertThat("the slot beneath is opaque", ColorMath.alpha(beneath), is(0xFF));
        assertThat("the render is laid over the slot as a finished render",
            menu.image().toPixelBuffer().getPixel(x, y), is(ColorMath.blend(drawn, beneath, BlendMode.PREMULTIPLIED)));
        assertThat("and not scaled by its alpha a second time",
            menu.image().toPixelBuffer().getPixel(x, y), is(not(ColorMath.blend(drawn, beneath, BlendMode.NORMAL))));
    }

}
