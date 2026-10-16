package lib.minecraft.renderer.engine.frame;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of {@link FrameLoan}: a loan leases its first frame from the thread's pixel buffer pool,
 * which hands back the buffer the thread last returned, cleared, and lends once; it returns only the
 * buffer it lent; loans nest, the innermost lending; and no frame is leased outside a loan.
 */
@DisplayName("A frame loan leases a render's first frame and returns only its own")
class FrameLoanTest {

    @Test
    @DisplayName("outside a loan every frame is a new buffer")
    void outsideALoanEveryFrameIsNew() {
        PixelBuffer first = FrameLoan.frame(4, 4);
        PixelBuffer second = FrameLoan.frame(4, 4);
        assertThat(second.data(), is(not(sameInstance(first.data()))));
    }

    @Test
    @DisplayName("a buffer given back is lent to the next loan's first frame, cleared to transparent")
    void aGivenBackBufferIsLentClear() {
        PixelBuffer drawn = lentAndGivenBack(4, 4);

        try (FrameLoan loan = FrameLoan.open()) {
            PixelBuffer next = FrameLoan.frame(4, 4);
            assertThat(next.data(), is(sameInstance(drawn.data())));
            assertThat(Arrays.stream(next.data()).boxed().toList(), everyItem(is(0)));
            assertThat(next.mask().isPresent(), is(false));
        }
    }

    @Test
    @DisplayName("a loan lends only its first frame, and nothing after it is given back")
    void aLoanLendsOnce() {
        PixelBuffer drawn = lentAndGivenBack(4, 4);

        try (FrameLoan loan = FrameLoan.open()) {
            PixelBuffer first = FrameLoan.frame(4, 4);
            PixelBuffer second = FrameLoan.frame(4, 4);
            assertThat(first.data(), is(sameInstance(drawn.data())));
            assertThat(second.data(), is(not(sameInstance(drawn.data()))));

            loan.giveBack(first);
            assertThat("a frame after the give-back", FrameLoan.frame(4, 4).data(), is(not(sameInstance(drawn.data()))));
        }
    }

    @Test
    @DisplayName("a picture holding another buffer's pixels returns nothing")
    void aCopyReturnsNothing() {
        PixelBuffer kept = lentAndGivenBack(4, 4);

        try (FrameLoan loan = FrameLoan.open()) {
            PixelBuffer lent = FrameLoan.frame(4, 4);
            assertThat(lent.data(), is(sameInstance(kept.data())));
            loan.giveBack(lent.copy());
        }

        try (FrameLoan loan = FrameLoan.open()) {
            assertThat(FrameLoan.frame(4, 4).data(), is(not(sameInstance(kept.data()))));
        }
    }

    @Test
    @DisplayName("a loan opened inside another lends its own first frame, and the outer one lends after it")
    void theInnermostLoanLends() {
        PixelBuffer kept = lentAndGivenBack(4, 4);
        PixelBuffer outerFrame;

        try (FrameLoan outer = FrameLoan.open()) {
            try (FrameLoan inner = FrameLoan.open()) {
                assertThat("the inner loan lends the pooled buffer", FrameLoan.frame(4, 4).data(), is(sameInstance(kept.data())));
            }

            outerFrame = FrameLoan.frame(4, 4);
            assertThat("the outer loan lends another, the pooled one being held", outerFrame.data(), is(not(sameInstance(kept.data()))));
            outer.giveBack(outerFrame);
            assertThat("and lends nothing more", FrameLoan.frame(4, 4).data(), is(not(sameInstance(outerFrame.data()))));
        }

        try (FrameLoan loan = FrameLoan.open()) {
            assertThat("the outer loan's buffer is pooled after it", FrameLoan.frame(4, 4).data(), is(sameInstance(outerFrame.data())));
        }
    }

    @Test
    @DisplayName("a frame of another size is lent a buffer of its own size")
    void anotherSizeIsLentItsOwn() {
        PixelBuffer small = lentAndGivenBack(4, 4);
        PixelBuffer large = lentAndGivenBack(5, 5);
        assertThat(large.data(), is(not(sameInstance(small.data()))));

        try (FrameLoan loan = FrameLoan.open()) {
            assertThat(FrameLoan.frame(5, 5).data(), is(sameInstance(large.data())));
        }
    }

    /**
     * Leases a frame under a loan, draws into it and gives it back.
     *
     * @param width the frame width
     * @param height the frame height
     * @return the frame given back
     */
    private static PixelBuffer lentAndGivenBack(int width, int height) {
        try (FrameLoan loan = FrameLoan.open()) {
            PixelBuffer drawn = FrameLoan.frame(width, height);
            drawn.fill(0xFF336699);
            drawn.enableMask();
            loan.giveBack(drawn);
            return drawn;
        }
    }

}
