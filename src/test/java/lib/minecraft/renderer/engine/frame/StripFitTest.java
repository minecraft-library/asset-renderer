package lib.minecraft.renderer.engine.frame;

import lib.minecraft.renderer.engine.frame.StripTrack.Kind;
import lib.minecraft.renderer.engine.frame.StripTrack.Role;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strip fit over synthetic tracks, one rule a case.
 *
 * <p>Every track here is built by hand, so each case states the motion it fits and the strip that
 * motion owes, with no pose, mesh or clip in between. Where a strip follows from the motion alone - an
 * exact loop, a floor, a stop - the case pins the number; where it is the smallest of many seams, the
 * case reads every candidate on the lattice itself and holds the fit to the smallest.
 *
 * <p>Exact loops are sampled as {@code t % period}, so their seams are zero to the bit rather than to
 * the sine's last place.
 */
@DisplayName("the strip fit over synthetic tracks")
class StripFitTest {

    // ---- the still test ------------------------------------------------------------------------

    @Test
    @DisplayName("nothing travelling past one tolerance is the still strip, the tolerance itself included")
    void stillWhereNothingTravelsPastOneTolerance() {
        assertEquals(new StripFit(StripFit.COARSE_STEP, 1, 0d, Optional.empty()), StripFit.STILL);
        assertTrue(StripFit.STILL.isStill());
        assertTrue(StripFit.STILL.closes());
        assertEquals(StripFit.COARSE_STEP, StripFit.STILL.loopTicks());

        assertEquals(StripFit.STILL, StripFit.fit(List.of()), "no tracks at all");
        assertEquals(StripFit.STILL, StripFit.fit(List.of(
            rotation("head.x_rot", expression(), sine(0.4, 24)),
            position("head.y", expression(), sine(0.1, 30))
        )), "every channel inside its tolerance");
        // A square wave that steps exactly one degree away from its start and back.
        assertEquals(StripFit.STILL, StripFit.fit(List.of(
            rotation("head.x_rot", expression(), tick -> tick % 2 == 0 ? 0d : 1d)
        )), "a channel travelling exactly one tolerance");
    }

    // ---- the search ----------------------------------------------------------------------------

    @Test
    @DisplayName("a 24-tick sine loops at its own period, twenty-four fine frames with no jump")
    void aTwentyFourTickSineLoopsAtItsOwnPeriod() {
        // Its loop fits the fine strip and closes there, as it does at the coarse step, so it takes a tick a frame.
        StripFit fit = StripFit.fit(List.of(rotation("leg.x_rot", expression(), sine(30d, 24))));

        assertEquals(new StripFit(1, 24, 0d, Optional.empty()), fit);
        assertEquals(24, fit.loopTicks());
        assertTrue(fit.closes());
        assertFalse(fit.isStill());
    }

    @Test
    @DisplayName("an irrational term takes its first length within a tolerance, else its smallest seam, the shorter on a tie")
    void anIrrationalTermTakesTheFirstCloseElseTheSmallestSeam() {
        // The creeper's stride at its wander pace: a 26.762-tick swing of 28.267 degrees, which meets the
        // 3-tick lattice nowhere under the cap within a degree, and comes nearest at 27. A tick a frame meets
        // it within one at 26, so that is the strip.
        double[] stride = samples(cosine(28.267, 26.762));
        StripFit closing = StripFit.fit(List.of(rotation("leg.x_rot", expression(), stride)));
        assertEquals(new StripFit(1, 26, seamAt(26, 1, 1d, stride), Optional.of("leg.x_rot")), closing);
        assertTrue(closing.closes(), "seam " + closing.seam());
        assertTrue(seamAt(27, 3, 1d, stride) > 1d, "the coarse strip misses by a little over one degree, seam " + seamAt(27, 3, 1d, stride));

        // Three times the swing meets neither lattice within a degree under its cap. The fine strip's smallest
        // seam is no larger than the coarse one's, so it is the strip.
        double[] wide = samples(cosine(3 * 28.267, 26.762));
        StripFit fallback = StripFit.fit(List.of(rotation("leg.x_rot", expression(), wide)));
        assertEquals(1, fallback.ticksPerFrame());
        assertFalse(fallback.closes(), "seam " + fallback.seam());
        assertEquals(Optional.of("leg.x_rot"), fallback.worstChannel());
        for (int length = fallback.loopTicks(); length <= StripFit.MAX_FRAMES; length++)
            assertTrue(seamAt(length, 1, 1d, wide) >= fallback.seam(), "a smaller seam at " + length);
        assertEquals(seamAt(fallback.loopTicks(), 1, 1d, wide), fallback.seam(), 1e-12);
        assertEquals(27, fallback.loopTicks());

        // The first length within a tolerance wins over a smaller seam further on: the 48-tick part is
        // under a degree, so it closes the strip at 24 and is back exactly at 48.
        StripFit first = StripFit.fit(List.of(rotation("leg.x_rot", expression(),
            tick -> sine(30d, 24).applyAsDouble(tick) + sine(0.45, 48).applyAsDouble(tick))));
        assertEquals(24, first.loopTicks());
        assertTrue(first.seam() > 0d && first.closes(), "seam " + first.seam());

        // Two lengths tie on the smallest seam, eight tolerances of a quarter pixel at 12 and at 15. The fine
        // strip ties as the coarse one does, and takes the shorter.
        StripFit tie = StripFit.fit(List.of(position("body.y", new Role.Looping(Double.NaN),
            tick -> tick == 0 ? 0d : (tick == 12 || tick == 15 ? 2d : 5d))));
        assertEquals(new StripFit(1, 12, 8d, Optional.of("body.y")), tie);
    }

    @Test
    @DisplayName("parts come round together under the cap, else at the smallest seam under it")
    void partsComeRoundTogetherUnderTheCap() {
        // A 10-tick and a 15-tick loop meet at 30, inside the fine strip.
        StripFit together = StripFit.fit(List.of(
            rotation("leg.x_rot", new Role.Looping(10), sine(20d, 10)),
            rotation("tail.y_rot", new Role.Looping(15), sine(20d, 15))
        ));
        assertEquals(new StripFit(1, 30, 0d, Optional.empty()), together);

        double[] thirteen = samples(sine(20d, 13));
        double[] seventeen = samples(sine(20d, 17));
        StripFit capped = StripFit.fit(List.of(
            rotation("leg.x_rot", new Role.Looping(13), thirteen),
            rotation("tail.y_rot", new Role.Looping(17), seventeen)
        ));
        // 13 and 17 meet first at 221 ticks, past the 66 a fine strip may run and the 198 a coarse one may.
        // The fine strip's smallest seam is no larger than the coarse one's, so it is the strip.
        int floor = 17;
        int best = floor;
        for (int length = floor; length <= StripFit.MAX_FRAMES; length++)
            if (seamAt(length, 1, 1d, thirteen, seventeen) < seamAt(best, 1, 1d, thirteen, seventeen)) best = length;
        assertEquals(1, capped.ticksPerFrame());
        assertEquals(best, capped.loopTicks());
        assertEquals(52, capped.loopTicks());
        assertEquals(seamAt(best, 1, 1d, thirteen, seventeen), capped.seam(), 1e-12);
        assertFalse(capped.closes());
    }

    // ---- the step test -------------------------------------------------------------------------

    @Test
    @DisplayName("a term too fast for coarse frames takes the fine step when it is big enough to see")
    void aFastVisibleTermTakesTheFineStep() {
        // The bee's wing: a 2.992-tick beat of 27 degrees.
        StripFit wing = StripFit.fit(List.of(rotation("wing.z_rot", expression(), cosine(27d, 2.992))));
        assertEquals(StripFit.FINE_STEP, wing.ticksPerFrame());
        assertTrue(wing.frames() > 1 && wing.frames() <= StripFit.MAX_FRAMES, "frames " + wing.frames());

        StripFit faint = StripFit.fit(List.of(rotation("wing.z_rot", expression(), cosine(1.1, 2.992))));
        assertEquals(StripFit.FINE_STEP, faint.ticksPerFrame(), "a beat a little over one degree");

        // Under one degree of beat the motion still travels 1.8 degrees, so it is not still, but it is
        // too small for coarse frames to alias. Alone, its loop fits the fine strip and closes there, so it
        // takes a tick a frame regardless; beside a swing longer than sixty-six ticks, which only aliasing
        // buys a fine strip long enough for, the beat's size alone decides the step.
        StripTrack unseenBeat = rotation("wing.z_rot", expression(), cosine(0.9, 2.992));
        StripFit unseen = StripFit.fit(List.of(unseenBeat));
        assertEquals(StripFit.FINE_STEP, unseen.ticksPerFrame());
        assertFalse(unseen.isStill());

        StripTrack slow = rotation("body.x_rot", expression(), sine(30d, 150));
        assertEquals(StripFit.COARSE_STEP, StripFit.fit(List.of(unseenBeat, slow)).ticksPerFrame(), "0.9 degrees of beat beside a 150-tick swing");
        assertEquals(StripFit.FINE_STEP, StripFit.fit(List.of(rotation("wing.z_rot", expression(), cosine(1.1, 2.992)), slow)).ticksPerFrame(),
            "1.1 degrees of beat beside a 150-tick swing");
    }

    // ---- the step rule -------------------------------------------------------------------------

    @Test
    @DisplayName("a stride whose every part fits sixty-six ticks takes 1-tick frames where its restart is no worse")
    void aStrideThatFitsTheFineStripTakesOneTickFrames() {
        // Every swing here is slower than two coarse frames, so no coarse frame aliases it, and each is shown
        // whole inside sixty-six ticks. An exact loop closes at its own period at either step, and a strip that
        // closes is no worse than another that closes.
        assertEquals(new StripFit(1, 12, 0d, Optional.empty()), swing(sine(30d, 12)), "a 12-tick stride");
        assertEquals(new StripFit(1, 60, 0d, Optional.empty()), swing(sine(30d, 60)), "a 60-tick swing");

        // A stride at the wolf's period, 11.89 ticks: four coarse frames come back with the frame after the
        // restart nearly two degrees off, twelve fine frames within one.
        double[] stride = samples(cosine(30d, 11.89));
        StripFit fit = swing(stride);
        assertEquals(StripFit.FINE_STEP, fit.ticksPerFrame());
        assertEquals(12, fit.loopTicks());
        assertTrue(fit.closes(), "seam " + fit.seam());
        assertTrue(seamAt(12, 3, 1d, stride) > 1d, "coarse seam " + seamAt(12, 3, 1d, stride));
    }

    @Test
    @DisplayName("a stride beside a slower part longer than sixty-six ticks keeps 3-tick frames")
    void aStrideBesideAPartPastTheFineCapKeepsCoarseFrames() {
        // A zombie's shape: a 12-tick stride beside a 94-tick arm bob, which sixty-six fine frames would cut.
        // The coarse strip shows the bob whole and closes where both come round, at 96.
        double[] bob = samples(sine(5d, 94));
        StripTrack leg = rotation("leg.x_rot", expression(), sine(30d, 12));
        StripTrack arm = rotation("arm.x_rot", expression(), bob);
        StripFit fit = StripFit.fit(List.of(leg, arm));
        assertEquals(new StripFit(3, 32, seamAt(96, 3, 1d, bob), Optional.of("arm.x_rot")), fit);
        assertTrue(fit.closes(), "seam " + fit.seam());

        // A turn that ends 30 degrees on stops the strip at the bob's whole loop, and jumps by those 30 degrees
        // at either step, so the fine restart would be no worse; the cut bob alone keeps three ticks a frame.
        StripFit stopping = StripFit.fit(List.of(leg, arm,
            rotation("head.x_rot", new Role.OneShot(20), tick -> tick < 20 ? 30d * tick / 20 : 30d)));
        assertEquals(new StripFit(3, 32, 30d, Optional.of("head.x_rot")), stopping);
    }

    @Test
    @DisplayName("a fine strip whose restart closes is taken even where the coarse seam is smaller")
    void aFineStripThatClosesIsTakenOverASmallerCoarseSeam() {
        // A 10-tick swing carrying half a degree of 60-tick drift. Ten fine frames come back within a tolerance;
        // the coarse lattice first meets the swing at 30, where the drift has come further round.
        double[] values = samples(tick -> sine(30d, 10).applyAsDouble(tick) + sine(0.5, 60).applyAsDouble(tick));
        StripFit fit = swing(values);
        assertEquals(new StripFit(1, 10, seamAt(10, 1, 1d, values), Optional.of("body.x_rot")), fit);
        assertTrue(fit.closes(), "seam " + fit.seam());
        for (int length = 9; length < 30; length += 3)
            assertTrue(seamAt(length, 3, 1d, values) > 1d, "a coarse close at " + length);
        assertTrue(seamAt(30, 3, 1d, values) < fit.seam(), "coarse seam " + seamAt(30, 3, 1d, values) + " against fine " + fit.seam());
    }

    @Test
    @DisplayName("a fine strip whose open restart is larger keeps 3-tick frames")
    void aLargerOpenFineRestartKeepsCoarseFrames() {
        // An 8-tick and a 9-tick loop meet at 72, past the sixty-six ticks a fine strip runs and on the coarse
        // lattice; every fine length short of it jumps.
        double[] eight = samples(sine(20d, 8));
        double[] nine = samples(sine(20d, 9));
        StripFit fit = StripFit.fit(List.of(
            rotation("leg.x_rot", new Role.Looping(8), eight),
            rotation("tail.y_rot", new Role.Looping(9), nine)
        ));
        assertEquals(new StripFit(3, 24, 0d, Optional.empty()), fit);
        for (int length = 9; length <= StripFit.MAX_FRAMES; length++)
            assertTrue(seamAt(length, 1, 1d, eight, nine) > 1d, "a fine close at " + length);
    }

    @Test
    @DisplayName("a strip coarse frames would alias runs past sixty-six frames to show a slower part whole, never past the coarse cap")
    void anAliasingStripRunsPastTheFineCapForASlowPart() {
        // A 4-tick beat coarse frames would alias, beside the 94-tick arm bob: the fine strip runs as long as a
        // coarse one may, so it reaches the bob's whole loop and closes where the two meet, at 188.
        StripTrack beat = rotation("wing.x_rot", new Role.Looping(4), sine(10d, 4));
        StripFit bob = StripFit.fit(List.of(beat, rotation("arm.x_rot", expression(), sine(30d, 94))));
        assertEquals(new StripFit(1, 188, 0d, Optional.empty()), bob);
        assertTrue(bob.frames() > StripFit.MAX_FRAMES, "frames " + bob.frames());

        // A part longer than the coarse cap is shown as long as the cap allows, and no longer.
        StripFit past = StripFit.fit(List.of(beat, rotation("arm.x_rot", expression(), sine(30d, 250))));
        assertEquals(StripFit.FINE_STEP, past.ticksPerFrame());
        assertTrue(past.loopTicks() >= 188 && past.loopTicks() <= StripFit.MAX_TICKS, past.toString());

        // Where every part fits sixty-six ticks the cap stays sixty-six frames: a 9-tick and a 10-tick loop
        // beside the beat meet at 180, which the fine strip does not reach.
        double[] nine = samples(sine(20d, 9));
        double[] ten = samples(sine(20d, 10));
        double[] beatValues = beat.values();
        StripFit kept = StripFit.fit(List.of(beat,
            rotation("leg.x_rot", new Role.Looping(9), nine),
            rotation("tail.y_rot", new Role.Looping(10), ten)
        ));
        assertEquals(StripFit.FINE_STEP, kept.ticksPerFrame());
        assertTrue(kept.frames() <= StripFit.MAX_FRAMES, kept.toString());
        assertEquals(0d, seamAt(180, 1, 1d, beatValues, nine, ten));
    }

    @Test
    @DisplayName("a fine restart within rounding of the coarse one is no worse, and one a hundredth of a tolerance larger is")
    void aFineRestartWithinRoundingOfTheCoarseOneIsNoWorse() {
        // The breeze's slide: a 30-tick turn that ends 63 degrees on stops the strip at 30 at either step,
        // beside a turn that rises to the same 63 degrees and holds, setting no floor. The frame after the
        // restart reads the hold at tick 31 fine and 33 coarse, where single-precision pose math leaves it about
        // six float steps apart - 63.000060 against 63.000016.
        StripFit rounded = held(63.00006, 63.000016);
        assertEquals(new StripFit(1, 30, 63.00006, Optional.of("rods.x_rot")), rounded);

        StripFit larger = held(63.01, 63.000016);
        assertEquals(new StripFit(3, 10, 63.000016, Optional.of("rods.x_rot")), larger);
    }

    // ---- one-shots -----------------------------------------------------------------------------

    @Test
    @DisplayName("a one-shot that returns is played whole and searched past; one that jumps stops the strip at the floor")
    void aOneShotThatReturnsIsPlayedWholeAndOneThatJumpsStopsTheStrip() {
        // A 40-tick one-shot floors the strip at 40; the 24-tick body beside it closes next at 48. Both fit
        // the fine strip, so it takes a tick a frame.
        StripFit returns = StripFit.fit(List.of(
            rotation("body.x_rot", expression(), sine(30d, 24)),
            rotation("head.x_rot", new Role.OneShot(40), tick -> tick < 40 ? 30 * StrictMath.sin(StrictMath.PI * tick / 40) : 0d)
        ));
        assertEquals(new StripFit(1, 48, 0d, Optional.empty()), returns);

        // A strip that stops jumps by the one-shot's own 30 degrees at either step, so the fine one is no worse.
        StripFit jumps = StripFit.fit(List.of(
            rotation("body.x_rot", expression(), sine(10d, 24)),
            rotation("head.x_rot", new Role.OneShot(40), tick -> tick < 40 ? 30d * tick / 40 : 30d)
        ));
        assertEquals(new StripFit(1, 40, 30d, Optional.of("head.x_rot")), jumps);
        assertFalse(jumps.closes());
    }

    @Test
    @DisplayName("a one-shot shorter than a loop beside it stops the strip at that loop's whole length")
    void aOneShotShorterThanALoopStopsAtTheLoop() {
        // The breeze's shape: a 4-tick turn that holds, beside a 50-tick wind scroll on a 32-texel sheet. The
        // scroll fits the fine strip and the stop jumps by the turn's 90 degrees at either step.
        StripFit fit = StripFit.fit(List.of(
            rotation("rods.y_rot", new Role.OneShot(4), tick -> tick < 4 ? 90d * tick / 4 : 90d),
            texel("wind.u", new Role.Looping(50), 32d, tick -> 32d * tick / 50)
        ));

        assertEquals(50, fit.frames(), "the scroll's whole loop, not the one-shot's 4 frames");
        assertEquals(50, fit.loopTicks());
        assertEquals(StripFit.FINE_STEP, fit.ticksPerFrame());
        assertEquals(90d, fit.seam());
        assertEquals(Optional.of("rods.y_rot"), fit.worstChannel());
        assertFalse(fit.closes());
    }

    @Test
    @DisplayName("a one-shot that jumps stops the strip at the whole period of every expression beside it")
    void aOneShotThatJumpsStopsAtEveryExpressionsWholePeriod() {
        // The warden's dig: a 100-tick one-shot that ends 90 degrees from its start, beside the spots' two
        // layers - a quarter of a cosine's positive half each, half a turn apart, every 2 pi / 0.045 = 139.6
        // ticks. A strip that stops judges no restart, so the floor alone shows the pulse: 140 ticks, its whole
        // period read and rounded up, and 141 at the step.
        StripFit digging = StripFit.fit(List.of(
            rotation("body.x_rot", new Role.OneShot(100), tick -> tick < 100 ? 90d * tick / 100 : 90d),
            opacity("pass1.opacity", tick -> 0.25 * Math.max(0d, StrictMath.cos(0.045 * tick))),
            opacity("pass2.opacity", tick -> 0.25 * Math.max(0d, StrictMath.cos(0.045 * tick + StrictMath.PI)))
        ));
        assertEquals(new StripFit(3, 47, 90d, Optional.of("body.x_rot")), digging);

        // A swing beside such a one-shot is shown whole the same way, at its own period rounded up to the step:
        // a tick a frame where the period fits the fine strip, since the stop jumps by the turn's 30 degrees at
        // either step, and three where it does not.
        StripTrack turn = rotation("head.x_rot", new Role.OneShot(20), tick -> tick < 20 ? 30d * tick / 20 : 30d);
        for (int period : new int[] { 50, 64, 100, 139, 150 }) {
            StripFit fit = StripFit.fit(List.of(rotation("body.x_rot", expression(), sine(30d, period)), turn));
            int step = period <= StripFit.MAX_FRAMES ? 1 : 3;
            assertEquals(new StripFit(step, roundUp(period, step) / step, 30d, Optional.of("head.x_rot")), fit, period + " ticks");
        }
    }

    @Test
    @DisplayName("an exact part longer than the coarse cap sets no floor; a one-shot still running is in the seam")
    void anExactPartPastTheCapSetsNoFloorAndARunningOneShotIsInTheSeam() {
        StripTrack beat = rotation("wing.x_rot", new Role.Looping(4), sine(10d, 4));

        // Beside a beat coarse frames would alias, the fine strip runs as long as a coarse one may where a part
        // is longer than sixty-six ticks, so an 80-tick loop is shown whole; a 250-tick one cannot be shown
        // whole in the 198 ticks even that strip covers, so it sets no floor.
        StripFit pastTheFineCap = StripFit.fit(List.of(beat, rotation("tail.x_rot", new Role.Looping(80), sine(2d, 80))));
        assertEquals(new StripFit(1, 80, 0d, Optional.empty()), pastTheFineCap, "an 80-tick loop is shown whole past sixty-six frames");

        StripFit pastTheCap = StripFit.fit(List.of(beat, rotation("tail.x_rot", new Role.Looping(250), sine(2d, 250))));
        assertEquals(StripFit.FINE_STEP, pastTheCap.ticksPerFrame());
        assertEquals(4, pastTheCap.loopTicks(), "a 250-tick loop cannot be shown whole in 198 ticks, so it sets no floor");
        assertTrue(pastTheCap.closes());

        StripFit underTheCap = StripFit.fit(List.of(beat, rotation("tail.x_rot", new Role.Looping(60), sine(2d, 60))));
        assertEquals(new StripFit(1, 60, 0d, Optional.empty()), underTheCap, "a 60-tick loop is shown whole");

        // A 220-tick one-shot that returns, beating every 8 ticks while it runs: the cap cuts it at every
        // length, so the restart is judged with it in, and only every eighth tick lands on its beat. An 80-tick
        // one the strip runs past ends before the restart and is shown whole.
        StripFit running = StripFit.fit(List.of(beat, rotation("head.x_rot", new Role.OneShot(220),
            tick -> tick < 220 ? sine(10d, 8).applyAsDouble(tick) : 0d)));
        assertEquals(new StripFit(1, 8, 0d, Optional.empty()), running);

        StripFit ended = StripFit.fit(List.of(beat, rotation("head.x_rot", new Role.OneShot(80),
            tick -> tick < 80 ? sine(10d, 8).applyAsDouble(tick) : 0d)));
        assertEquals(new StripFit(1, 80, 0d, Optional.empty()), ended);
    }

    // ---- wraps ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a full turn and a scroll come round their wrap with no jump")
    void wrappedChannelsCloseRoundTheirWrap() {
        // A turn every 72 ticks is longer than the fine strip holds; a scroll every 32 fits it.
        StripFit turn = StripFit.fit(List.of(rotation("head.y_rot", expression(), tick -> 5d * tick)));
        assertEquals(new StripFit(3, 24, 0d, Optional.empty()), turn, "one turn every 72 ticks");

        StripFit scroll = StripFit.fit(List.of(texel("swirl.u", new Role.Looping(32), 64d, tick -> 2d * tick)));
        assertEquals(new StripFit(1, 32, 0d, Optional.empty()), scroll, "a 64-texel sheet every 32 ticks");
    }

    // ---- opacity -------------------------------------------------------------------------------

    @Test
    @DisplayName("an opacity pulse beside a still body sets the floor; one under 1/64 is still")
    void anOpacityPulseSetsTheFloorAndAFaintOneIsStill() {
        StripTrack body = rotation("body.x_rot", expression(), tick -> 10d);

        StripFit pulsing = StripFit.fit(List.of(body,
            opacity("pass1.opacity", tick -> 0.125 - 0.125 * StrictMath.cos(2 * StrictMath.PI * (tick % 60) / 60))));
        // The pulse reads a hair over its 60 ticks, so its whole period rounds up to 61, which the fine strip holds.
        assertEquals(new StripFit(1, 61, 0d, Optional.empty()), pulsing, "a 60-tick pulse from 0 to 0.25, shown whole");

        StripFit faint = StripFit.fit(List.of(body,
            opacity("pass1.opacity", tick -> 0.005 - 0.005 * StrictMath.cos(2 * StrictMath.PI * (tick % 60) / 60))));
        assertEquals(StripFit.STILL, faint, "a pulse travelling 0.01");
    }

    @Test
    @DisplayName("an opacity channel sets how long the strip runs and never where it restarts")
    void anOpacityChannelSetsTheFloorAndStaysOutOfTheSeam() {
        // The warden's heart: a 40-tick flash from full falling to a tenth over 10 ticks, then dark.
        IntToDoubleFunction flash = tick -> {
            int phase = Math.floorMod(tick, 40);
            return phase < 10 ? 1d - phase / 10d : 0d;
        };

        StripFit fit = StripFit.fit(List.of(rotation("body.x_rot", expression(), sine(30d, 24)), opacity("pass1.opacity", flash)));
        // The flash, read at 40.005, floors the strip at 41 - its whole period rounded up, which the fine strip
        // holds; the body closes next at 48, where the flash is 8 ticks into its fall - a jump of 51 opacity
        // tolerances the restart does not read. With the flash in the seam the first close would be 120.
        assertEquals(new StripFit(1, 48, 0d, Optional.empty()), fit);
        assertTrue(Math.abs(flash.applyAsDouble(48) - flash.applyAsDouble(0)) > Kind.OPACITY.tolerance());

        // Twice the creeper's stride misses within a degree at every fine length from the flash's floor.
        StripFit missing = StripFit.fit(List.of(rotation("body.x_rot", expression(), cosine(2 * 28.267, 26.762)), opacity("pass1.opacity", flash)));
        assertFalse(missing.closes(), "seam " + missing.seam());
        assertEquals(Optional.of("body.x_rot"), missing.worstChannel(), "the body's jump, never the flash's larger one");
    }

    // ---- the floor -----------------------------------------------------------------------------

    @Test
    @DisplayName("an expression of 129 to 198 ticks is floored within one step of its period")
    void aSlowExpressionIsFlooredThroughThePaddedSpectrum() {
        // Under two cycles in the window, where a peak's lobe overlaps its own mirror image; two degrees of
        // swing closes at the first length the floor allows, so the strip is the floor itself. The band
        // runs to the coarse cap at either phase.
        for (int period = 129; period <= StripFit.MAX_FRAMES * 3; period++)
            for (boolean sine : new boolean[] { true, false }) {
                StripFit fit = slowSwing(sine, period);
                int floor = roundUp((int) Math.floor(0.95 * period), 3);
                assertEquals(3, fit.ticksPerFrame());
                assertTrue(Math.abs(fit.loopTicks() - floor) <= 3,
                    (sine ? "sine " : "cosine ") + period + ": " + fit.loopTicks() + " against " + floor);
            }
    }

    @Test
    @DisplayName("the floor reads a slow swing through a spectrum zero-padded twice over")
    void theFloorReadsASlowSwingThroughTheTwicePaddedSpectrum() {
        // Each lands one step shorter than the unpadded spectrum puts it.
        assertEquals(123, slowSwing(true, 131).loopTicks(), "sine 131");
        assertEquals(126, slowSwing(false, 132).loopTicks(), "cosine 132");
        assertEquals(126, slowSwing(true, 133).loopTicks(), "sine 133");
        assertEquals(135, slowSwing(false, 140).loopTicks(), "cosine 140");
    }

    @Test
    @DisplayName("a swing of any size or phase is floored at its own loop, never at a sidelobe of it or past it")
    void aSwingIsFlooredAtItsOwnLoop() {
        // The taper throws a sidelobe of about 3 percent of a peak a few bins beside it, which a large swing
        // lifts past one tolerance and which lies lower than the peak. Each swing here meets the lattice at
        // its own period, so read true it closes there at the latest, and a floor at 0.95 of a period read
        // within 5 percent keeps it at 0.9 of that period at the least.
        assertEquals(new StripFit(3, 29, 0d, Optional.empty()), swing(sine(30d, 87)), "30 degrees at 87 ticks");
        assertEquals(new StripFit(3, 30, 0d, Optional.empty()), swing(sine(30d, 90)), "30 degrees at 90 ticks");

        // A loop the fine strip holds closes there at its own period as surely as on the coarse lattice, so it
        // takes a tick a frame; a loop read past sixty-six ticks takes three - and a 66-tick sine reads a hair
        // over, rounding to 67, as the 60-tick pulse below reads 61.
        for (double amplitude : new double[] { 2d, 30d, 90d })
            for (int period = 12; period <= StripFit.MAX_FRAMES * 3; period += 3)
                for (int eighth = 0; eighth < 4; eighth++) {
                    StripFit fit = swing(sine(amplitude, period, eighth * StrictMath.PI / 4));
                    String label = amplitude + " degrees at " + period + " ticks, " + eighth + "/8 of a turn on: " + fit;
                    assertEquals(period < StripFit.MAX_FRAMES ? 1 : 3, fit.ticksPerFrame(), label);
                    assertTrue(fit.closes(), label);
                    assertTrue(fit.loopTicks() <= period && fit.loopTicks() >= 0.9 * period, label);
                }
    }

    @Test
    @DisplayName("a swing the restart judges closes at a near-loop short of its period")
    void aJudgedSwingClosesJustShortOfItsPeriod() {
        // 15 degrees every 24.27 ticks is back within a degree at 23, a tick and a quarter short of its period.
        // The restart judges what a strip leaves out of such a swing, so the search starts short of its period
        // by the slack, and the near-loop under it is the strip; the coarse lattice's near-loop is 24, and its
        // first length past the period, 27, jumps by several degrees.
        double[] near = samples(cosine(15d, 24.27));
        StripFit fit = swing(near);
        assertEquals(1, fit.ticksPerFrame());
        assertEquals(23, fit.loopTicks());
        assertTrue(fit.closes(), "seam " + fit.seam());
        assertEquals(seamAt(23, 1, 1d, near), fit.seam(), 1e-12);
        assertTrue(seamAt(24, 3, 1d, near) <= 1d, "seam at 24 " + seamAt(24, 3, 1d, near));
        assertTrue(seamAt(27, 3, 1d, near) > 1d, "seam at 27 " + seamAt(27, 3, 1d, near));
    }

    @Test
    @DisplayName("a visible term no restart judges is shown at least one whole period, at any period, size or phase")
    void aTermNoRestartJudgesIsShownWhole() {
        // Beside a one-shot that jumps, and as an opacity beside a still body, nothing measures the restart, so
        // the floor alone decides how much of the term a strip shows. A whole-tick period reads within a tick
        // of itself, so its strip holds it whole; one between whole ticks can read under its last whole tick,
        // and its strip then stops short by the fraction past it - half a tick at the most here.
        StripTrack jump = rotation("head.x_rot", new Role.OneShot(2), tick -> tick < 2 ? 45d * tick : 90d);
        StripTrack still = rotation("body.x_rot", expression(), tick -> 10d);
        for (boolean stopping : new boolean[] { true, false }) {
            for (int period = 6; period <= StripFit.MAX_FRAMES * 3; period++)
                for (double amplitude : new double[] { 2d, 30d })
                    for (int eighth = 0; eighth < 2; eighth++) {
                        StripFit fit = unjudged(stopping, jump, still, sineAt(amplitude, period, eighth * StrictMath.PI / 4));
                        assertTrue(fit.loopTicks() >= period, (stopping ? "stopping, " : "opacity, ") + amplitude
                            + " tolerances at " + period + " ticks, " + eighth + "/8 of a turn on: " + fit);
                    }

            for (int quarters = 25; quarters < 4 * StripFit.MAX_FRAMES * 3; quarters++) {
                if (quarters % 4 == 0) continue;
                double period = quarters / 4d;
                StripFit fit = unjudged(stopping, jump, still, sineAt(16d, period, StrictMath.PI / 8));
                assertTrue(fit.loopTicks() >= period - 0.5, (stopping ? "stopping, " : "opacity, ") + "16 tolerances at "
                    + period + " ticks: " + fit);
            }
        }
    }

    @Test
    @DisplayName("a slow swing falls back past its own period's floor, and a mixture is floored by its slower part")
    void aSlowSwingAndAMixtureAreFlooredByTheirSlowerPart() {
        // 30 degrees at 170 ticks meets the lattice within a degree nowhere from its floor to the cap.
        double[] slow = samples(sineAt(30d, 170));
        StripFit fallback = swing(slow);
        int floor = roundUp((int) Math.floor(0.95 * 170), 3);
        assertEquals(3, fallback.ticksPerFrame());
        assertEquals(57, fallback.frames());
        assertFalse(fallback.closes(), "seam " + fallback.seam());
        for (int length = floor; length <= StripFit.MAX_FRAMES * 3; length += 3)
            assertTrue(seamAt(length, 3, 1d, slow) >= fallback.seam(), "a smaller seam at " + length);
        assertEquals(seamAt(fallback.loopTicks(), 3, 1d, slow), fallback.seam(), 1e-12);

        // A small fast part rides a large slow one: the slow part's loop is the strip.
        StripFit riding = swing(tick -> sine(30d, 87).applyAsDouble(tick) + sine(2d, 6).applyAsDouble(tick));
        assertEquals(3, riding.ticksPerFrame());
        assertEquals(29, riding.frames());
        assertTrue(riding.closes(), "seam " + riding.seam());

        // A small slow part beside a large fast one keeps its own floor.
        double[] beside = samples(tick -> sineAt(2d, 150).applyAsDouble(tick) + sineAt(30d, 20).applyAsDouble(tick));
        StripFit kept = swing(beside);
        assertEquals(3, kept.ticksPerFrame());
        assertEquals(60, kept.frames());
        assertEquals(seamAt(180, 3, 1d, beside), kept.seam(), 1e-12);
        assertFalse(kept.closes());

        // Where the fast part comes back with the slow one, the pair closes at the slow part's loop. The fast
        // part holds nearly all the motion, so the floor takes the lone sinusoid's reading: a fit of the slow
        // part's harmonics would lean on the fast part's lobe and read it long.
        StripFit together = swing(tick -> sine(2d, 150).applyAsDouble(tick) + sine(30d, 30, StrictMath.PI / 2).applyAsDouble(tick));
        assertEquals(new StripFit(3, 50, 0d, Optional.empty()), together);
    }

    @Test
    @DisplayName("a slow pulse with a second harmonic, beside a still body, is floored at its own loop at every phase")
    void aSlowPulseWithASecondHarmonicIsFlooredAtItsOwnLoop() {
        // The warden's spot shape, 0.25 max(0, cos), at slower rates. Under two cycles in the window its second
        // harmonic sits inside its own lobe, which a lone sinusoid leans on and reads short. The body closes at
        // every length, so the floor alone decides the strip: the period read, rounded up to the step. A read
        // short of the loop can leave the strip short by as much, which on such a shape stays under 5 ticks.
        StripTrack body = rotation("body.x_rot", expression(), tick -> 10d);
        for (int period : new int[] { 152, 160, 170, 180, 190 })
            for (int sixteenths = 0; sixteenths < 16; sixteenths++) {
                StripFit fit = StripFit.fit(List.of(body, opacity("pass1.opacity", shape(HALF_WAVE, period, sixteenths))));
                int bound = period - 4;
                assertTrue(fit.loopTicks() >= bound, period + " ticks, " + sixteenths + "/16 of a turn on: " + fit.loopTicks() + " against " + bound);
            }
    }

    @Test
    @DisplayName("a slow swing of that shape covers its own loop rather than closing in its rest")
    void aSlowRectifiedSwingCoversItsOwnLoop() {
        // 16 degrees of the same shape at 190 ticks, 6/16 of a turn on: it rests at zero for half its loop, so
        // a strip ending anywhere in the rest closes, and only the floor holds it to the loop.
        StripFit fit = swing(shape(turn -> 64 * HALF_WAVE.applyAsDouble(turn), 190, 6));
        assertEquals(3, fit.ticksPerFrame(), fit.toString());
        assertTrue(fit.loopTicks() >= roundUp((int) Math.floor(0.95 * 190), 3) - 3, fit.toString());
        assertTrue(fit.closes(), fit.toString());
    }

    @Test
    @DisplayName("a slow flash with only odd harmonics, beside a still body, is floored at its own loop at every phase")
    void aSlowOddFlashIsFlooredAtItsOwnLoop() {
        // A square flash, a clamped sine and a triangle hold no second harmonic; a fit of two harmonics leans on
        // their third and reads them short. As with the pulse, the strip is the period read, rounded up to the
        // step, and stays under 5 ticks short of the loop.
        StripTrack body = rotation("body.x_rot", expression(), tick -> 10d);
        Map<String, DoubleUnaryOperator> shapes = new LinkedHashMap<>();
        shapes.put("square", turn -> turn < 0.5 ? 0.25 : 0d);
        shapes.put("clamped sine", turn -> 0.25 * (Math.max(-0.5, Math.min(0.5, StrictMath.sin(2 * StrictMath.PI * turn))) + 0.5));
        shapes.put("triangle", turn -> 0.5 * (turn < 0.5 ? turn : 1 - turn));
        for (Map.Entry<String, DoubleUnaryOperator> entry : shapes.entrySet())
            for (int period : new int[] { 152, 170, 190 })
                for (int sixteenths = 0; sixteenths < 16; sixteenths++) {
                    StripFit fit = StripFit.fit(List.of(body, opacity("pass1.opacity", shape(entry.getValue(), period, sixteenths))));
                    int bound = period - 4;
                    assertTrue(fit.loopTicks() >= bound,
                        entry.getKey() + " at " + period + " ticks, " + sixteenths + "/16 of a turn on: " + fit.loopTicks() + " against " + bound);
                }
    }

    @Test
    @DisplayName("a slower part under one tolerance sets no floor, and a swing slower than the window floors the strip at the cap")
    void aFaintSlowPartSetsNoFloorAndASwingPastTheWindowRunsToTheCap() {
        // The 24-tick swing alone sets the floor, so the strip fits the fine one and closes at 24 there.
        for (double period : new double[] { 87d, 150d, 204d }) {
            StripFit fit = swing(tick -> sine(30d, 24).applyAsDouble(tick) + sineAt(0.9, period).applyAsDouble(tick));
            assertEquals(1, fit.ticksPerFrame(), "beside 0.9 degrees at " + period);
            assertEquals(24, fit.loopTicks(), "beside 0.9 degrees at " + period);
            assertTrue(fit.closes(), "beside 0.9 degrees at " + period + ", seam " + fit.seam());
        }

        // A part that cannot be shown whole is shown as long as the cap allows: from its floor to the cap.
        int capTicks = StripFit.MAX_FRAMES * 3;
        int capFloor = roundUp((int) Math.floor(0.95 * capTicks), 3);
        for (double period : new double[] { 260d, 300d, 384d })
            for (double amplitude : new double[] { 30d, 90d })
                for (boolean sine : new boolean[] { true, false }) {
                    StripFit fit = swing(sine ? sineAt(amplitude, period) : cosine(amplitude, period));
                    String label = (sine ? "sine " : "cosine ") + amplitude + " degrees at " + period + " ticks: " + fit;
                    assertEquals(3, fit.ticksPerFrame(), label);
                    assertTrue(fit.loopTicks() >= capFloor && fit.loopTicks() <= capTicks, label);
                }
    }

    @Test
    @DisplayName("the window holds every sample the seam reads and stops short of tick 400")
    void theWindowHoldsTheSeamAndStopsShortOfTheCountdown() {
        // The seam reads tick L + s with L up to the coarse cap, which a strip at either step may run to. The
        // iron golem's offered flower drops at tick 400 and stays down, which a window reaching it would read as
        // a slow period to cover.
        assertEquals(3, StripFit.COARSE_STEP);
        assertEquals(1, StripFit.FINE_STEP);
        assertEquals(Timeline.MAX_LOOP_TICKS / StripFit.COARSE_STEP, StripFit.MAX_FRAMES);
        assertEquals(66, StripFit.MAX_FRAMES);
        assertEquals(StripFit.MAX_FRAMES * StripFit.COARSE_STEP, StripFit.MAX_TICKS);
        assertTrue(StripFit.WINDOW_TICKS >= StripFit.MAX_TICKS + StripFit.COARSE_STEP + 1,
            "window " + StripFit.WINDOW_TICKS);
        assertTrue(StripFit.WINDOW_TICKS < 400, "window " + StripFit.WINDOW_TICKS);
        assertEquals(1, Integer.bitCount(StripFit.WINDOW_TICKS), "a power of two, for the transform");
    }

    @Test
    @DisplayName("a loop with no period sets no floor and is still judged at the restart")
    void aLoopWithNoPeriodSetsNoFloorAndStaysInTheSeam() {
        // A 12-tick expression closes at 12 alone; the 24-tick part beside it is what holds it to 24, inside the
        // fine strip.
        for (double period : new double[] { 0d, Double.NaN }) {
            StripFit fit = StripFit.fit(List.of(
                rotation("head.x_rot", expression(), sine(30d, 12)),
                rotation("leg.x_rot", new Role.Looping(period), sine(30d, 24))
            ));
            assertEquals(new StripFit(1, 24, 0d, Optional.empty()), fit, "period " + period);
        }

        // Floored at 60, the pair next comes round at 72, which only the coarse strip reaches.
        StripFit periodic = StripFit.fit(List.of(
            rotation("head.x_rot", expression(), sine(30d, 12)),
            rotation("leg.x_rot", new Role.Looping(60), sine(30d, 24))
        ));
        assertEquals(new StripFit(3, 24, 0d, Optional.empty()), periodic, "a 60-tick period floors the strip past 24 and 48");
    }

    // ---- refusals ------------------------------------------------------------------------------

    @Test
    @DisplayName("a track holding other than a window of samples is refused")
    void refusesAWrongSampleCount() {
        for (int length : new int[] { StripFit.WINDOW_TICKS - 1, StripFit.WINDOW_TICKS + 1 })
            assertThrows(IllegalArgumentException.class, () -> new StripTrack("head.x_rot", Kind.ROTATION,
                expression(), new double[length], OptionalDouble.of(360d)), "length " + length);
    }

    @Test
    @DisplayName("a wrap that is not positive and finite is refused")
    void refusesANonPositiveWrap() {
        for (double wrap : new double[] { 0d, -360d, Double.NaN, Double.POSITIVE_INFINITY })
            assertThrows(IllegalArgumentException.class, () -> new StripTrack("head.x_rot", Kind.ROTATION,
                expression(), new double[StripFit.WINDOW_TICKS], OptionalDouble.of(wrap)), "wrap " + wrap);
    }

    @Test
    @DisplayName("one channel carrying two kinds or two wraps is refused")
    void refusesAChannelThatDisagreesWithItself() {
        // Each pair differs in the one respect it names, so neither check can stand in for the other.
        StripTrack position = position("head.y", expression(), sine(1d, 24));
        StripTrack scale = new StripTrack("head.y", Kind.SCALE, expression(), samples(sine(0.1, 24)), OptionalDouble.empty());
        StripTrack rotation = rotation("head.x_rot", expression(), sine(30d, 24));
        StripTrack halfTurn = new StripTrack("head.x_rot", Kind.ROTATION, expression(), samples(sine(30d, 24)), OptionalDouble.of(180d));

        assertThrows(IllegalArgumentException.class, () -> StripFit.fit(List.of(position, scale)), "two kinds");
        assertThrows(IllegalArgumentException.class, () -> StripFit.fit(List.of(rotation, halfTurn)), "two wraps");
    }

    // ---- determinism ---------------------------------------------------------------------------

    @Test
    @DisplayName("one list fits the same twice, and a tie between channels names the first in track order")
    void equalInputsAnswerEqualFitsAndTiesGoToTrackOrder() {
        List<StripTrack> tracks = List.of(
            rotation("leg.x_rot", expression(), cosine(28.267, 26.762)),
            rotation("tail.y_rot", new Role.Looping(13), sine(20d, 13)),
            texel("wind.u", new Role.Looping(50), 32d, tick -> 32d * tick / 50),
            opacity("pass1.opacity", sine(0.25, 40))
        );
        assertEquals(StripFit.fit(tracks), StripFit.fit(tracks));
        assertEquals(StripFit.fit(tracks), StripFit.fit(List.copyOf(tracks)));

        StripTrack left = rotation("left_leg.x_rot", expression(), cosine(28.267, 26.762));
        StripTrack right = rotation("right_leg.x_rot", expression(), cosine(28.267, 26.762));
        StripFit leftFirst = StripFit.fit(List.of(left, right));
        StripFit rightFirst = StripFit.fit(List.of(right, left));
        assertEquals(Optional.of("left_leg.x_rot"), leftFirst.worstChannel());
        assertEquals(Optional.of("right_leg.x_rot"), rightFirst.worstChannel());
        assertEquals(leftFirst.loopTicks(), rightFirst.loopTicks());
        assertEquals(leftFirst.seam(), rightFirst.seam());
        assertNotEquals(0d, leftFirst.seam());
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull Role expression() {
        return new Role.Expression();
    }

    private static @NotNull StripTrack rotation(@NotNull String channel, @NotNull Role role, @NotNull IntToDoubleFunction degrees) {
        return rotation(channel, role, samples(degrees));
    }

    private static @NotNull StripTrack rotation(@NotNull String channel, @NotNull Role role, double @NotNull [] degrees) {
        return new StripTrack(channel, Kind.ROTATION, role, degrees, OptionalDouble.of(360d));
    }

    private static @NotNull StripTrack position(@NotNull String channel, @NotNull Role role, @NotNull IntToDoubleFunction pixels) {
        return new StripTrack(channel, Kind.POSITION, role, samples(pixels), OptionalDouble.empty());
    }

    private static @NotNull StripTrack texel(@NotNull String channel, @NotNull Role role, double sheet, @NotNull IntToDoubleFunction texels) {
        return new StripTrack(channel, Kind.TEXEL, role, samples(texels), OptionalDouble.of(sheet));
    }

    private static @NotNull StripTrack opacity(@NotNull String channel, @NotNull IntToDoubleFunction fraction) {
        return new StripTrack(channel, Kind.OPACITY, expression(), samples(fraction), OptionalDouble.empty());
    }

    private static double @NotNull [] samples(@NotNull IntToDoubleFunction value) {
        double[] samples = new double[StripFit.WINDOW_TICKS];
        for (int tick = 0; tick < samples.length; tick++) samples[tick] = value.applyAsDouble(tick);
        return samples;
    }

    /** Samples a sine of a whole-tick period off its phase within the period, so it repeats to the bit. */
    private static @NotNull IntToDoubleFunction sine(double amplitude, int period) {
        return tick -> amplitude * StrictMath.sin(2 * StrictMath.PI * (tick % period) / period);
    }

    /** Samples a sine of a whole-tick period off its phase within the period, started a phase on, so it repeats to the bit. */
    private static @NotNull IntToDoubleFunction sine(double amplitude, int period, double phase) {
        return tick -> amplitude * StrictMath.sin(2 * StrictMath.PI * (tick % period) / period + phase);
    }

    /** Samples a sine of any period off the tick itself. */
    private static @NotNull IntToDoubleFunction sineAt(double amplitude, double period) {
        return tick -> amplitude * StrictMath.sin(2 * StrictMath.PI * tick / period);
    }

    /** Samples a sine of any period off the tick itself, started a phase on. */
    private static @NotNull IntToDoubleFunction sineAt(double amplitude, double period, double phase) {
        return tick -> amplitude * StrictMath.sin(2 * StrictMath.PI * tick / period + phase);
    }

    /** Samples a cosine of any period off the tick itself - at its peak at tick 0, as a walk's swing is. */
    private static @NotNull IntToDoubleFunction cosine(double amplitude, double period) {
        return tick -> amplitude * StrictMath.cos(2 * StrictMath.PI * tick / period);
    }

    /** The warden's spot shape over one turn: a cosine's positive half at a quarter of full, dark for the rest. */
    private static final DoubleUnaryOperator HALF_WAVE = turn -> 0.25 * Math.max(0d, StrictMath.cos(2 * StrictMath.PI * turn));

    /**
     * Samples a shape given over one turn at a whole-tick period off its phase within the period, started
     * sixteenths of a turn on, so it repeats to the bit.
     */
    private static @NotNull IntToDoubleFunction shape(@NotNull DoubleUnaryOperator turn, int period, int sixteenths) {
        return tick -> {
            double fraction = (double) (tick % period) / period + sixteenths / 16d;
            return turn.applyAsDouble(fraction - Math.floor(fraction));
        };
    }

    /** Fits one two-degree swing of a whole-tick period, at a sine's phase or a cosine's. */
    private static @NotNull StripFit slowSwing(boolean sine, int period) {
        return swing(sine ? sineAt(2d, period) : cosine(2d, period));
    }

    /** Fits one expression track of rotation. */
    private static @NotNull StripFit swing(@NotNull IntToDoubleFunction degrees) {
        return swing(samples(degrees));
    }

    /** Fits one expression track of rotation from its samples. */
    private static @NotNull StripFit swing(double @NotNull [] degrees) {
        return StripFit.fit(List.of(rotation("body.x_rot", expression(), degrees)));
    }

    /**
     * Fits a term no restart judges: a swing of that many degrees beside a one-shot that jumps, or an opacity of
     * that many tolerances about one half beside a still body.
     */
    private static @NotNull StripFit unjudged(boolean stopping, @NotNull StripTrack jump, @NotNull StripTrack still, @NotNull IntToDoubleFunction term) {
        return stopping
            ? StripFit.fit(List.of(rotation("body.x_rot", expression(), term), jump))
            : StripFit.fit(List.of(still, opacity("pass1.opacity", tick -> 0.5 + term.applyAsDouble(tick) * Kind.OPACITY.tolerance())));
    }

    /**
     * Fits a 30-tick turn that ends 63 degrees on, beside a turn that rises to 63 degrees and holds there, read
     * at ticks 31 and 33 as given.
     */
    private static @NotNull StripFit held(double atThirtyOne, double atThirtyThree) {
        return StripFit.fit(List.of(
            rotation("rods.y_rot", new Role.OneShot(30), tick -> tick < 30 ? 2.1 * tick : 63d),
            rotation("rods.x_rot", new Role.Looping(Double.NaN), tick -> tick <= 3 ? 0d
                : tick < 31 ? 63d * (tick - 3) / 28 : tick == 31 ? atThirtyOne : tick == 33 ? atThirtyThree : 63d)
        ));
    }

    /**
     * Measures the restart's jump at a length over channels that wrap nowhere in their range, in
     * tolerances: the worst channel's larger move across the first two frames after the restart.
     */
    private static double seamAt(int length, int step, double tolerance, double @NotNull []... channels) {
        double worst = 0d;
        for (double[] values : channels)
            worst = Math.max(worst, Math.max(Math.abs(values[length] - values[0]), Math.abs(values[length + step] - values[step])) / tolerance);
        return worst;
    }

    private static int roundUp(int value, int step) {
        return (value + step - 1) / step * step;
    }

}
