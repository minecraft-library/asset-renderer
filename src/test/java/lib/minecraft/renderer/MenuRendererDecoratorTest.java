package lib.minecraft.renderer;

import dev.simplified.image.AnimatedImageData;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.StaticImageData;
import dev.simplified.image.frame.FrameBlend;
import dev.simplified.image.frame.FrameDisposal;
import dev.simplified.image.frame.ImageFrame;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.call.slot.MenuSlot;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * Pins a menu's output to the layers it actually composites rather than to the ones it builds
 * itself.
 * <p>
 * A caller's {@link MenuOptions#getLayerDecorator() layerDecorator} splices into the same stack the
 * built-in layers went into, so a spliced animation is a layer of the composite like any other and
 * the output has to play it. The menu here carries no animated layer of its own - no slot, no fill,
 * no obfuscated title - so the decorator is the only thing that could animate it, and the control
 * render below is what holds that.
 * <p>
 * A spliced layer is drawn as it was placed, too: a still menu rescales a placement its caller fitted
 * to an extent across that extent, as an animated one does.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("A menu composites the layers its caller spliced in")
class MenuRendererDecoratorTest {

    /** The side of the spliced layer, in output pixels, which is small because only its ink matters. */
    private static final int LAYER_PX = 8;

    /** A chest whose every built-in layer is static, which is the one this claim is taken on. */
    private static MenuOptions staticMenu() {
        return MenuOptions.builder().type(MenuOptions.Type.CHEST).rows(1).build();
    }

    /** A two-frame animation of one solid colour and then another, so a dropped frame shows as ink. */
    private static ImageData twoFrames() {
        return AnimatedImageData.builder()
            .withFrame(frame(0xFFFF0000))
            .withFrame(frame(0xFF00FF00))
            .build();
    }

    /** One solid frame of {@code argb}, spelled at the disposal a transparent canvas needs. */
    private static ImageFrame frame(int argb) {
        PixelBuffer buffer = PixelBuffer.straight(LAYER_PX, LAYER_PX);

        for (int y = 0; y < LAYER_PX; y++)
            for (int x = 0; x < LAYER_PX; x++) buffer.setPixel(x, y, argb);

        return ImageFrame.of(buffer, 50, 0, 0, FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE);
    }

    private static ImageData render(MenuOptions options) {
        return new MenuRenderer(ClientAssetsExtension.context()).render(options).image();
    }

    /**
     * The control: without a decorator the same menu is static, so an animated result below is the
     * spliced layer's doing and not something the chest was going to do anyway.
     */
    @Test
    @DisplayName("a chest with no animated layer of its own renders static")
    void aChestWithNothingAnimatedRendersStatic() {
        assertThat("a bare chest has nothing to animate",
            render(staticMenu()).isAnimated(), is(false));
    }

    @Test
    @DisplayName("an animated layer spliced through the decorator animates the menu")
    void aSplicedAnimatedLayerAnimatesTheMenu() {
        MenuOptions decorated = staticMenu().mutate()
            .layerDecorator(stack -> stack.append(MenuSlot.TEXT,
                sink -> sink.add(new FramePlacement(0, 0, twoFrames()))))
            .build();

        assertThat("the menu plays the animation its caller spliced in",
            render(decorated).isAnimated(), is(true));
    }

    /**
     * Animating is not enough on its own - a composite that flipped the flag and then sampled every
     * layer at frame zero would pass the claim above while drawing one still twice.
     */
    @Test
    @DisplayName("the spliced layer's frames each reach the output")
    void theSplicedFramesReachTheOutput() {
        MenuOptions decorated = staticMenu().mutate()
            .layerDecorator(stack -> stack.append(MenuSlot.TEXT,
                sink -> sink.add(new FramePlacement(0, 0, twoFrames()))))
            .build();

        ImageData rendered = render(decorated);
        PixelBuffer first = rendered.getFrames().getFirst().pixels();
        PixelBuffer last = rendered.getFrames().getLast().pixels();
        int differing = 0;

        for (int y = 0; y < LAYER_PX; y++)
            for (int x = 0; x < LAYER_PX; x++)
                if (first.getPixel(x, y) != last.getPixel(x, y)) differing++;

        assertThat("the two spliced frames draw different ink", differing, is(greaterThan(0)));
    }

    @Test
    @DisplayName("a still layer spliced in fitted to an extent is rescaled across it")
    void aSplicedFittedLayerIsRescaledAcrossItsExtent() {
        PixelBuffer texel = PixelBuffer.straight(1, 1);
        texel.setPixel(0, 0, 0xFFFF0000);
        MenuOptions decorated = staticMenu().mutate()
            .layerDecorator(stack -> stack.append(MenuSlot.TEXT,
                sink -> sink.add(new FramePlacement(0, 0, StaticImageData.of(texel)).fittedTo(LAYER_PX, LAYER_PX))))
            .build();

        ImageData rendered = render(decorated);
        PixelBuffer pixels = rendered.toPixelBuffer();
        int red = 0;

        for (int y = 0; y < LAYER_PX; y++)
            for (int x = 0; x < LAYER_PX; x++)
                if (pixels.getPixel(x, y) == 0xFFFF0000) red++;

        assertThat("the menu stays still", rendered.isAnimated(), is(false));
        assertThat("the one-pixel picture covers its whole extent", red, is(LAYER_PX * LAYER_PX));
    }

}
