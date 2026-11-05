package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.frame.ImageFrame;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static lib.minecraft.renderer.fixture.TooltipFixtures.stubContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Loop-sizing pin for {@link TextRenderer}'s scrolling gradient: the frame count covers a whole number
 * of scroll cycles AND lands the wrap frame on phase 0 - {@code lcm(cycleTicks, ticksPerFrame) /
 * ticksPerFrame} - so the animation loops without a mid-cycle seam even when the frame cadence is
 * coarser than the tick rate ({@code fps < 20}). A book page whose one animation is a scrolling gradient
 * loops on its cycle at the request's frame rate, a fresh phase on every frame, and a book's lines past the
 * fourteenth, which the page does not draw, take no part in the loop.
 */
@ExtendWith(MinecraftFontsExtension.class)
class TextRendererGradientTest {

    /**
     * Builds a black-to-white gradient scrolling left over the given cycle.
     *
     * @param cycleTicks the ticks one scroll cycle spans
     * @return the gradient
     */
    private static GradientSpec scrollSpec(int cycleTicks) {
        return GradientSpec.builder(GradientSpec.Mode.START_END)
            .addStop(0x000000).addStop(0xFFFFFF)
            .bandPx(1)
            .scroll(new GradientSpec.Scroll(cycleTicks, GradientSpec.Scroll.Direction.LEFT))
            .build();
    }

    /**
     * Builds LORE options carrying one left-scrolling gradient segment, the two arguments being what
     * the loop sizing is a function of.
     *
     * @param cycleTicks the ticks one scroll cycle spans
     * @param fps the output frame rate, which fixes the ticks per frame
     * @return the options whose baked frame count is asserted
     */
    private static TextOptions scrollOptions(int cycleTicks, int fps) {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder()
            .segments(Concurrent.newList(TextSegment.builder().text("Scroll").gradient(scrollSpec(cycleTicks)).build()))
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
        ImageData image = new TextRenderer().render(scrollOptions(40, 20)).image();
        assertThat(image.getFrames().size(), is(40));
    }

    @Test
    @DisplayName("a coarse fps sizes the loop to lcm(cycleTicks, ticksPerFrame)/ticksPerFrame")
    void coarseFpsSizesToLcm() {
        // fps 10 -> ticksPerFrame 2; cycleTicks 5 -> lcm(5,2)/2 = 5 frames (spanning two full cycles),
        // wrap frame at tick 10 == phase 0. A truncating cycleTicks / ticksPerFrame divide drops the
        // partial cycle - 2 frames here - and seams mid-cycle.
        ImageData image = new TextRenderer().render(scrollOptions(5, 10)).image();
        assertThat(image.getFrames().size(), is(5));
    }

    @Test
    @DisplayName("a book page whose one animation is a scrolling gradient loops on its cycle at the request's frame rate")
    void aBookPagesGradientLoopsOnItsCycleAtTheRequestsFrameRate() {
        ImageData image = bookRenderer().render(TextOptions.builder()
            .style(TextOptions.Style.BOOK)
            .page(TextSegment.builder().text("Scroll").gradient(scrollSpec(30)).build())
            .framesPerSecond(10)
            .build()).image();
        ConcurrentList<ImageFrame> frames = image.getFrames();
        ConcurrentList<Integer> alike = Concurrent.newList();
        for (int f = 1; f < frames.size(); f++)
            if (frames.get(f).pixels().equals(frames.getFirst().pixels())) alike.add(f);

        // fps 10 is 2 ticks a frame, so the 30-tick cycle is 15 frames of 100 ms, each 2 ticks on from the last.
        assertThat("animated", image.isAnimated(), is(true));
        assertThat("frames", frames.size(), is(15));
        assertThat("frame delays", frames.stream().map(ImageFrame::delayMs).distinct().toList(), is(List.of(100)));
        assertThat("frames drawing the first frame's phase", alike, is(empty()));
    }

    @Test
    @DisplayName("a book's line past the fourteenth, which the page does not draw, sizes no loop and animates nothing")
    void aBookLinePastTheFourteenthSizesNoLoop() {
        TextRenderer renderer = bookRenderer();
        TextSegment obfuscated = TextSegment.builder().text("ab").obfuscated(true).build();
        TextSegment scrolling = TextSegment.builder().text("Scroll").gradient(scrollSpec(30)).build();

        assertThat("frames where the gradient's line is dropped - the obfuscation's own count",
            renderer.render(page(obfuscated, 15, scrolling)).image().getFrames().size(), is(20));
        assertThat("frames where the gradient's line is drawn - the least common multiple of both loops",
            renderer.render(page(obfuscated, 14, scrolling)).image().getFrames().size(), is(60));
        assertThat("whether a page whose one obfuscated line is dropped animates",
            renderer.render(page(TextSegment.literal("a"), 15, obfuscated)).image().isAnimated(), is(false));
    }

    /**
     * Builds a renderer whose context serves an opaque paper as the book texture and nothing else.
     *
     * @return the renderer
     */
    private static TextRenderer bookRenderer() {
        PixelBuffer paper = PixelBuffer.straight(256, 256);
        paper.fill(0xFFC8B48C);
        return new TextRenderer(stubContext(Map.of("minecraft:gui/book", paper), Map.of()));
    }

    /**
     * Builds a book page whose first run is its first line and whose last run is the line numbered
     * {@code lastLine}, the numbers between them its other lines.
     *
     * @param first the page's first line
     * @param lastLine the number of the page's last line, counted from one
     * @param last the page's last line
     * @return the options drawing the page as a book
     */
    private static TextOptions page(TextSegment first, int lastLine, TextSegment last) {
        StringBuilder between = new StringBuilder("\n");
        for (int line = 2; line < lastLine; line++)
            between.append(line).append('\n');

        TextSegment page = TextSegment.builder().extra(List.of(first, TextSegment.literal(between.toString()), last)).build();
        return TextOptions.builder().style(TextOptions.Style.BOOK).page(page).build();
    }

}
