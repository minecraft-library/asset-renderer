package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.kit.GlintKit;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.instanceOf;

/**
 * End-to-end coverage for glint × animation composition - the path no vanilla render
 * exercises (vanilla ships no animated-and-enchanted subject), reachable only when a caller opts a
 * glinted subject into {@code frameCount > 1}. Bakes an animated {@link Timeline} with an enchanted
 * {@link GlintKit.Foil} finish over a flat opaque base and asserts the result is a multi-frame
 * {@link AnimatedImageData} whose frames DIFFER - i.e. the foil is stamped per frame at the
 * tick-derived glint schedule rather than dropped or frozen.
 */
class GlintAnimationTest {

    /** A small non-uniform glint texture so a scrolled sample visibly changes across phases. */
    private static @NotNull PixelBuffer glintTexture() {
        int size = 16;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                pixels[y * size + x] = ColorMath.pack(255, (x * 16) & 0xFF, (y * 16) & 0xFF, ((x + y) * 8) & 0xFF);
        return PixelBuffer.of(pixels, size, size);
    }

    /**
     * Fills the whole target with a flat DARK opaque colour - a glintable silhouette. Dark (not white)
     * so the additive {@code QUADRATIC_ADD} glint blend ({@code out = src^2 + dst}) does not clamp to
     * white and the scrolled foil's phase shows per frame.
     */
    private static void fillOpaque(@NotNull PixelBuffer target) {
        int dark = ColorMath.pack(255, 32, 32, 40);
        for (int y = 0; y < target.height(); y++)
            for (int x = 0; x < target.width(); x++)
                target.setPixel(x, y, dark);
    }

    /** Counts pixels that differ between two equal-size buffers. */
    private static int differingPixels(@NotNull PixelBuffer a, @NotNull PixelBuffer b) {
        int diff = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) diff++;
        return diff;
    }

    @Test
    @DisplayName("enchanted + animated: strip carries the animation's frames, each with its own scrolled foil")
    void animatedGlintStampsPerFrame() {
        PixelBuffer glint = glintTexture();
        GlintKit.Foil foil = GlintKit.Foil.item(id -> Optional.of(glint), true, true, 30);
        // frameCount=4, ticksPerFrame=50 -> ticks 0/50/100/150 -> distinct glint phases per frame.
        AnimationOptions anim = AnimationOptions.builder().frameCount(4).ticksPerFrame(50).build();

        ImageData result = Timeline.tickStrip(anim).bake(
            RasterPass.of(24, 24, 1, false, (target, tick) -> fillOpaque(target)).finishing(foil));

        assertThat(result, is(instanceOf(AnimatedImageData.class)));
        AnimatedImageData animated = (AnimatedImageData) result;
        assertThat(animated.getFrames().size(), is(4));
        // The foil scrolls: successive frames must differ (not a frozen glint).
        PixelBuffer f0 = animated.getFrames().get(0).pixels();
        PixelBuffer f2 = animated.getFrames().get(2).pixels();
        assertThat(differingPixels(f0, f2), greaterThan(0));
    }

    @Test
    @DisplayName("animateGlint=false + animated base: every frame stamps frame-0's phase (static foil, frames equal)")
    void nonScrollingGlintIsFrozen() {
        PixelBuffer glint = glintTexture();
        GlintKit.Foil foil = GlintKit.Foil.item(id -> Optional.of(glint), true, false, 30);
        AnimationOptions anim = AnimationOptions.builder().frameCount(3).ticksPerFrame(50).build();

        ImageData result = Timeline.tickStrip(anim).bake(
            RasterPass.of(24, 24, 1, false, (target, tick) -> fillOpaque(target)).finishing(foil));

        AnimatedImageData animated = (AnimatedImageData) result;
        assertThat(animated.getFrames().size(), is(3));
        // A non-scrolling glint stamps startTick's phase on every frame, so all frames are identical.
        PixelBuffer f0 = animated.getFrames().get(0).pixels();
        PixelBuffer f2 = animated.getFrames().get(2).pixels();
        assertThat(differingPixels(f0, f2), is(0));
    }
}
