package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.option.TextOptions;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Pins {@link TextRenderer}'s scrolling-gradient loop sizing: the frame count covers a
 * whole number of scroll cycles AND lands the wrap frame on phase 0 - {@code lcm(cycleTicks,
 * ticksPerFrame) / ticksPerFrame} - so the animation loops without a mid-cycle seam even when the
 * frame cadence is coarser than the tick rate ({@code fps < 20}).
 */
@ExtendWith(MinecraftFontsExtension.class)
class TextRendererGradientTest {

    private static TextOptions scrollOptions(int cycleTicks, int fps) {
        GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
            .addStop(0x000000).addStop(0xFFFFFF)
            .bandPx(1)
            .scroll(new GradientSpec.Scroll(cycleTicks, GradientSpec.Scroll.Direction.LEFT))
            .build();
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder()
            .withSegments(ColorSegment.builder().withText("Scroll").withGradient(spec).build())
            .build());
        return TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .framesPerSecond(fps)
            .build();
    }

    @Test
    @DisplayName("at the 20 fps default one frame is one tick, so the loop is cycleTicks frames")
    void defaultFpsFrameCount() {
        ImageData image = new TextRenderer().render(scrollOptions(40, 20));
        assertThat(image.getFrames().size(), is(40));
    }

    @Test
    @DisplayName("a coarse fps sizes the loop to lcm(cycleTicks, ticksPerFrame)/ticksPerFrame")
    void coarseFpsSizesToLcm() {
        // fps 10 -> ticksPerFrame 2; cycleTicks 5 -> lcm(5,2)/2 = 5 frames (spanning two full cycles),
        // wrap frame at tick 10 == phase 0. The pre-fix (int)(5/2) = 2 frames would seam mid-cycle.
        ImageData image = new TextRenderer().render(scrollOptions(5, 10));
        assertThat(image.getFrames().size(), is(5));
    }
}
