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
    @DisplayName("a 24-tick sine loops at its own period, eight coarse frames with no jump")
    void aTwentyFourTickSineLoopsAtEightFrames() {
        StripFit fit = StripFit.fit(List.of(rotation("leg.x_rot", expression(), sine(30d, 24))));

        assertEquals(new StripFit(3, 8, 0d, Optional.empty()), fit);
        assertEquals(24, fit.loopTicks());
        assertTrue(fit.closes());
        assertFalse(fit.isStill());
    }

    @Test
    @DisplayName("an irrational term takes its first length within a tolerance, else its smallest seam, the shorter on a tie")
    void anIrrationalTermTakesTheFirstCloseElseTheSmallestSeam() {
        // The creeper's stride at its wander pace: a 26.762-tick swing of 28.267 degrees, which meets the
        // 3-tick lattice nowhere under the cap within a degree, and comes nearest at 27.
        double[] stride = samples(cosine(28.267, 26.762));
        StripFit fallback = StripFit.fit(List.of(rotation("leg.x_rot", expression(), stride)));
        assertEquals(3, fallback.ticksPerFrame());
        assertEquals(27, fallback.loopTicks());
        assertFalse(fallback.closes(), "the stride misses by a little over one degree, seam " + fallback.seam());
        assertEquals(Optional.of("leg.x_rot"), fallback.worstChannel());
        for (int length = fallback.loopTicks(); length <= StripFit.MAX_FRAMES * 3; length += 3)
            assertTrue(seamAt(length, 3, 1d, stride) >= fallback.seam(), "a smaller seam at " + length);
        assertEquals(seamAt(27, 3, 1d, stride), fallback.seam(), 1e-12);

        StripFit closing = StripFit.fit(List.of(rotation("leg.x_rot", expression(), cosine(25d, 26.762))));
        assertEquals(27, closing.loopTicks());
        assertTrue(closing.closes(), "a 25-degree swing comes round within a degree, seam " + closing.seam());

        // The first length within a tolerance wins over a smaller seam further on: the 48-tick part is
        // under a degree, so it closes the strip at 24 and is back exactly at 48.
        StripFit first = StripFit.fit(List.of(rotation("leg.x_rot", expression(),
            tick -> sine(30d, 24).applyAsDouble(tick) + sine(0.45, 48).applyAsDouble(tick))));
        assertEquals(24, first.loopTicks());
        assertTrue(first.seam() > 0d && first.closes(), "seam " + first.seam());

        // Two lengths tie on the smallest seam, eight tolerances of a quarter pixel at 12 and at 15.
        StripFit tie = StripFit.fit(List.of(position("body.y", new Role.Looping(Double.NaN),
            tick -> tick < 6 ? 0d : (tick >= 12 && tick <= 18 ? 2d : 5d))));
        assertEquals(new StripFit(3, 4, 8d, Optional.of("body.y")), tie);
    }

    @Test
    @DisplayName("parts come round together under the cap, else at the smallest seam under it")
    void partsComeRoundTogetherUnderTheCap() {
        StripFit together = StripFit.fit(List.of(
            rotation("leg.x_rot", new Role.Looping(10), sine(20d, 10)),
            rotation("tail.y_rot", new Role.Looping(15), sine(20d, 15))
        ));
        assertEquals(new StripFit(3, 10, 0d, Optional.empty()), together);

        double[] thirteen = samples(sine(20d, 13));
        double[] seventeen = samples(sine(20d, 17));
        StripFit capped = StripFit.fit(List.of(
            rotation("leg.x_rot", new Role.Looping(13), thirteen),
            rotation("tail.y_rot", new Role.Looping(17), seventeen)
        ));
        // 13, 17 and 3 meet first at 663 ticks, far past the 198 a coarse strip may run.
        int floor = 18;
        int best = floor;
        for (int length = floor; length <= StripFit.MAX_FRAMES * 3; length += 3)
            if (seamAt(length, 3, 1d, thirteen, seventeen) < seamAt(best, 3, 1d, thirteen, seventeen)) best = length;
        assertEquals(3, capped.ticksPerFrame());
        assertEquals(best, capped.loopTicks());
        assertEquals(51, capped.loopTicks());
        assertEquals(seamAt(best, 3, 1d, thirteen, seventeen), capped.seam(), 1e-12);
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
        // too small to buy the fine step.
        StripFit unseen = StripFit.fit(List.of(rotation("wing.z_rot", expression(), cosine(0.9, 2.992))));
        assertEquals(StripFit.COARSE_STEP, unseen.ticksPerFrame());
        assertFalse(unseen.isStill());
    }

    // ---- one-shots -----------------------------------------------------------------------------

    @Test
    @DisplayName("a one-shot that returns is played whole and searched past; one that jumps stops the strip at the floor")
    void aOneShotThatReturnsIsPlayedWholeAndOneThatJumpsStopsTheStrip() {
        // A 40-tick one-shot floors the strip at 42; the 24-tick body beside it closes next at 48.
        StripFit returns = StripFit.fit(List.of(
            rotation("body.x_rot", expression(), sine(30d, 24)),
            rotation("head.x_rot", new Role.OneShot(40), tick -> tick < 40 ? 30 * StrictMath.sin(StrictMath.PI * tick / 40) : 0d)
        ));
        assertEquals(new StripFit(3, 16, 0d, Optional.empty()), returns);

        StripFit jumps = StripFit.fit(List.of(
            rotation("body.x_rot", expression(), sine(10d, 24)),
            rotation("head.x_rot", new Role.OneShot(40), tick -> tick < 40 ? 30d * tick / 40 : 30d)
        ));
        assertEquals(new StripFit(3, 14, 30d, Optional.of("head.x_rot")), jumps);
        assertFalse(jumps.closes());
    }

    @Test
    @DisplayName("a one-shot shorter than a loop beside it stops the strip at that loop's whole length")
    void aOneShotShorterThanALoopStopsAtTheLoop() {
        // The breeze's shape: a 4-tick turn that holds, beside a 50-tick wind scroll on a 32-texel sheet.
        StripFit fit = StripFit.fit(List.of(
            rotation("rods.y_rot", new Role.OneShot(4), tick -> tick < 4 ? 90d * tick / 4 : 90d),
            texel("wind.u", new Role.Looping(50), 32d, tick -> 32d * tick / 50)
        ));

        assertEquals(17, fit.frames(), "the scroll's whole loop, not the one-shot's 2 frames");
        assertEquals(51, fit.loopTicks());
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

        // A swing beside such a one-shot is shown whole the same way, at its own period rounded up to the step.
        StripTrack turn = rotation("head.x_rot", new Role.OneShot(20), tick -> tick < 20 ? 30d * tick / 20 : 30d);
        for (int period : new int[] { 50, 64, 100, 139, 150 }) {
            StripFit fit = StripFit.fit(List.of(rotation("body.x_rot", expression(), sine(30d, period)), turn));
            assertEquals(new StripFit(3, roundUp(period, 3) / 3, 30d, Optional.of("head.x_rot")), fit, period + " ticks");
        }
    }

    @Test
    @DisplayName("an exact part longer than the fine cap sets no floor; a one-shot still running is in the seam")
    void anExactPartPastTheCapSetsNoFloorAndARunningOneShotIsInTheSeam() {
        StripTrack beat = rotation("wing.x_rot", new Role.Looping(4), sine(10d, 4));

        StripFit pastTheCap = StripFit.fit(List.of(beat, rotation("tail.x_rot", new Role.Looping(80), sine(2d, 80))));
        assertEquals(StripFit.FINE_STEP, pastTheCap.ticksPerFrame());
        assertEquals(4, pastTheCap.loopTicks(), "an 80-tick loop cannot be shown whole in 66 ticks, so it sets no floor");
        assertTrue(pastTheCap.closes());

        StripFit underTheCap = StripFit.fit(List.of(beat, rotation("tail.x_rot", new Role.Looping(60), sine(2d, 60))));
        assertEquals(new StripFit(1, 60, 0d, Optional.empty()), underTheCap, "a 60-tick loop is shown whole");

        // An 80-tick one-shot that returns, beating every 8 ticks while it runs: the cap cuts it at every
        // length, so the restart is judged with it in, and only every eighth tick lands on its beat.
        StripFit running = StripFit.fit(List.of(beat, rotation("head.x_rot", new Role.OneShot(80),
            tick -> tick < 80 ? sine(10d, 8).applyAsDouble(tick) : 0d)));
        assertEquals(new StripFit(1, 8, 0d, Optional.empty()), running);
    }

    // ---- wraps ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a full turn and a scroll come round their wrap with no jump")
    void wrappedChannelsCloseRoundTheirWrap() {
        StripFit turn = StripFit.fit(List.of(rotation("head.y_rot", expression(), tick -> 5d * tick)));
        assertEquals(new StripFit(3, 24, 0d, Optional.empty()), turn, "one turn every 72 ticks");

        StripFit scroll = StripFit.fit(List.of(texel("swirl.u", new Role.Looping(32), 64d, tick -> 2d * tick)));
        assertEquals(new StripFit(3, 32, 0d, Optional.empty()), scroll, "a 64-texel sheet every 32 ticks");
    }

    // ---- opacity -------------------------------------------------------------------------------

    @Test
    @DisplayName("an opacity pulse beside a still body sets the floor; one under 1/64 is still")
    void anOpacityPulseSetsTheFloorAndAFaintOneIsStill() {
        StripTrack body = rotation("body.x_rot", expression(), tick -> 10d);

        StripFit pulsing = StripFit.fit(List.of(body,
            opacity("pass1.opacity", tick -> 0.125 - 0.125 * StrictMath.cos(2 * StrictMath.PI * (tick % 60) / 60))));
        // The pulse reads a hair over its 60 ticks, so its whole period rounds up to 61 and the step to 63.
        assertEquals(new StripFit(3, 21, 0d, Optional.empty()), pulsing, "a 60-tick pulse from 0 to 0.25, shown whole");

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
        // The flash, read at 40.005, floors the strip at 42 - its whole period rounded up, then up to the
        // step; the body closes next at 48, where the flash is 8 ticks into its fall - a jump of 51 opacity
        // tolerances the restart does not read. With the flash in the seam the first close would be 120.
        assertEquals(new StripFit(3, 16, 0d, Optional.empty()), fit);
        assertTrue(Math.abs(flash.applyAsDouble(48) - flash.applyAsDouble(0)) > Kind.OPACITY.tolerance());

        StripFit missing = StripFit.fit(List.of(rotation("body.x_rot", expression(), cosine(28.267, 26.762)), opacity("pass1.opacity", flash)));
        assertFalse(missing.closes());
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

        for (double amplitude : new double[] { 2d, 30d, 90d })
            for (int period = 12; period <= StripFit.MAX_FRAMES * 3; period += 3)
                for (int eighth = 0; eighth < 4; eighth++) {
                    StripFit fit = swing(sine(amplitude, period, eighth * StrictMath.PI / 4));
                    String label = amplitude + " degrees at " + period + " ticks, " + eighth + "/8 of a turn on: " + fit;
                    assertEquals(3, fit.ticksPerFrame(), label);
                    assertTrue(fit.closes(), label);
                    assertTrue(fit.loopTicks() <= period && fit.loopTicks() >= 0.9 * period, label);
                }
    }

    @Test
    @DisplayName("a swing the restart judges closes at a near-loop a fraction of a tick short of its period")
    void aJudgedSwingClosesJustShortOfItsPeriod() {
        // 15 degrees every 24.27 ticks is back within a degree at 24, where the first length past its period, 27,
        // jumps by several degrees. The restart judges what a strip leaves out of such a swing, so the search
        // starts short of its period by the slack, and the near-loop under it is the strip.
        double[] near = samples(cosine(15d, 24.27));
        StripFit fit = swing(near);
        assertEquals(3, fit.ticksPerFrame());
        assertEquals(24, fit.loopTicks());
        assertTrue(fit.closes(), "seam " + fit.seam());
        assertEquals(seamAt(24, 3, 1d, near), fit.seam(), 1e-12);
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
        for (double period : new double[] { 87d, 150d, 204d }) {
            StripFit fit = swing(tick -> sine(30d, 24).applyAsDouble(tick) + sineAt(0.9, period).applyAsDouble(tick));
            assertEquals(3, fit.ticksPerFrame(), "beside 0.9 degrees at " + period);
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
        // The seam reads tick L + s with L up to the coarse cap. The iron golem's offered flower drops at
        // tick 400 and stays down, which a window reaching it would read as a slow period to cover.
        assertEquals(3, StripFit.COARSE_STEP);
        assertEquals(1, StripFit.FINE_STEP);
        assertEquals(Timeline.MAX_LOOP_TICKS / StripFit.COARSE_STEP, StripFit.MAX_FRAMES);
        assertEquals(66, StripFit.MAX_FRAMES);
        assertTrue(StripFit.WINDOW_TICKS >= StripFit.MAX_FRAMES * StripFit.COARSE_STEP + StripFit.COARSE_STEP + 1,
            "window " + StripFit.WINDOW_TICKS);
        assertTrue(StripFit.WINDOW_TICKS < 400, "window " + StripFit.WINDOW_TICKS);
        assertEquals(1, Integer.bitCount(StripFit.WINDOW_TICKS), "a power of two, for the transform");
    }

    @Test
    @DisplayName("a loop with no period sets no floor and is still judged at the restart")
    void aLoopWithNoPeriodSetsNoFloorAndStaysInTheSeam() {
        // A 12-tick expression closes at 12 alone; the 24-tick part beside it is what holds it to 24.
        for (double period : new double[] { 0d, Double.NaN }) {
            StripFit fit = StripFit.fit(List.of(
                rotation("head.x_rot", expression(), sine(30d, 12)),
                rotation("leg.x_rot", new Role.Looping(period), sine(30d, 24))
            ));
            assertEquals(new StripFit(3, 8, 0d, Optional.empty()), fit, "period " + period);
        }

        StripFit periodic = StripFit.fit(List.of(
            rotation("head.x_rot", expression(), sine(30d, 12)),
            rotation("leg.x_rot", new Role.Looping(60), sine(30d, 24))
        ));
        assertEquals(72, periodic.loopTicks(), "a 60-tick period floors the strip past 24 and 48");
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
