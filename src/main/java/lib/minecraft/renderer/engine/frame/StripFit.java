package lib.minecraft.renderer.engine.frame;

import lib.minecraft.renderer.engine.frame.StripTrack.Kind;
import lib.minecraft.renderer.engine.frame.StripTrack.Role;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The derived strip of one posed row - how many frames an animated render draws and how many ticks each
 * spans - and the fit that derives it from the row's sampled channels.
 *
 * <p>The strip is the shortest length on the frame step at which every channel comes back within one
 * tolerance of where it started, judged across the first two frames after the restart so that a mirror
 * image, back at its start but heading the other way, does not pass for a loop. An exact loop is that
 * rule at a jump of zero, and parts on different periods meet at their combined loop by the same
 * search. Where no length under the cap comes back, the strip is the length with the smallest jump, the
 * shorter of two that tie. Around the search, the fit:
 * <ul>
 *     <li><b>answers {@link #STILL}</b> where no channel ever moves more than one tolerance from its value
 *     at tick 0;</li>
 *     <li><b>picks the step</b> - {@link #FINE_STEP} where some channel carries most of its repeating
 *     motion faster than two coarse frames, and at least one tolerance of it, which coarse frames would
 *     alias. Otherwise it fits the strip at both steps and takes the fine one where that shows every
 *     visible part whole - each part's own length, as the floor below reads it but without the cap, fits
 *     in {@link #MAX_FRAMES} ticks - and restarts no worse, closing within one tolerance or jumping no
 *     further than the coarse one, rounding aside; else the coarse one. The cap is {@link #MAX_TICKS}
 *     ticks at the coarse step and {@link #MAX_FRAMES} frames at the fine, save that a strip coarse frames
 *     would alias runs to {@link #MAX_TICKS} fine frames where a visible part is longer than
 *     {@link #MAX_FRAMES} ticks, so that part is not cut;</li>
 *     <li><b>sets a floor</b> every length must reach, so each visible part is shown whole once: a
 *     one-shot's full length, a looping part's period, an expression's slowest visible period read off its
 *     spectrum and rounded up. The search for a restart starts a little short of the period of an expression
 *     the restart judges, since the reading is approximate and the restart then measures what a near-loop
 *     just under it leaves out. An exact part longer than the cap sets none and is judged at the restart
 *     instead, where a one-shot the length cuts short counts as the cut it is;</li>
 *     <li><b>stops at the floor</b> where a one-shot does not end where it started, since its own jump hides
 *     the rest of the restart - a floor in which a part too long to show whole counts at the cap. The seam
 *     reported is then that jump, or the rest's at the floor where that is larger, so the strip does not
 *     close.</li>
 * </ul>
 *
 * <p>A channel of {@link Kind#OPACITY} takes part in all four and is left out of the restart: it decides
 * how long a strip runs and never where it restarts.
 *
 * <p><b>The sampling contract.</b> Every track holds {@link #WINDOW_TICKS} finite samples from tick 0, and
 * the strip chosen runs at most {@link #MAX_TICKS} ticks at either step. The still test, the step test,
 * the part lengths and the floor read the whole window rather than the strip, so the fit assumes every
 * input behaves over the window as it does over the strip. An input that changes for
 * good past the strip but inside the window - a countdown that runs out, a clip longer than the window,
 * which reads as never returning - moves those tests by motion no frame shows. The fit does not check
 * that a sample is finite, and the strip it answers over one that is not means nothing.
 *
 * <p>The fit is pure and answers the same bits on every run: it reads nothing but its argument, takes
 * every transcendental through {@link StrictMath}, groups channels in the order they first appear, and
 * breaks every tie by that order or by the shorter length.
 *
 * @param ticksPerFrame the game ticks each frame spans
 * @param frames the number of frames, one exactly for a still strip
 * @param seam the restart's worst jump, in tolerances of the channel it is on
 */
@Parity(subject = Subject.ENTITY)
public record StripFit(int ticksPerFrame, int frames, double seam) {

    /**
     * The frame step a strip takes where coarse frames alias none of its motion and a strip at
     * {@link #FINE_STEP} would cut a visible part short or restart worse - three ticks a frame.
     */
    public static final int COARSE_STEP = 3;

    /**
     * The frame step a strip takes where coarse frames would alias its visible motion, or where a strip at
     * this step shows every visible part whole and restarts no worse than one at {@link #COARSE_STEP} - a
     * tick a frame.
     */
    static final int FINE_STEP = 1;

    /**
     * The most frames a derived strip draws - as many coarse frames as fit in {@link Timeline#MAX_LOOP_TICKS},
     * and as many fine ones. A strip coarse frames would alias that holds a visible part longer than this
     * many ticks draws up to {@link #MAX_TICKS} fine frames instead, so that part is not cut.
     */
    public static final int MAX_FRAMES = Timeline.MAX_LOOP_TICKS / COARSE_STEP;

    /** The most ticks a derived strip covers at either step - {@link #MAX_FRAMES} coarse frames. */
    public static final int MAX_TICKS = MAX_FRAMES * COARSE_STEP;

    /**
     * The ticks every track is sampled over, from tick 0 - a power of two for the transform, and long enough
     * to hold the last sample the restart reads at the longest coarse strip.
     */
    public static final int WINDOW_TICKS = 256;

    /** The still strip - one frame, for a row with no visible motion. */
    public static final @NotNull StripFit STILL = new StripFit(COARSE_STEP, 1, 0d);

    /** The share of a channel's motion faster than two coarse frames that makes it too fast for them. */
    private static final double HIGH_BAND_FRACTION = 0.5;

    /** The amplitude, in tolerances, that motion faster than two coarse frames needs to buy the fine step. */
    private static final double VISIBILITY_FLOOR = 1d;

    /**
     * The factor on the spectral period of an expression the restart judges that gives the length the search
     * starts from, the reading being approximate and a near-loop just under the period coming back within a
     * tolerance.
     */
    private static final double COVERAGE_SLACK = 0.95;

    /** The factor the floor's spectrum zero-pads the window by, so a period over half the window still reads finely. */
    private static final int PERIOD_PADDING = 2;

    /** The margin within which two seams are equal, and by which a part's length is trimmed before it rounds up to whole ticks. */
    private static final double TIE_EPSILON = 1e-9;

    /**
     * The margin, in tolerances, by which a fine strip's open restart may jump further than the coarse one's
     * and still count as no worse - a thousandth of a tolerance. The two strips read the frame after their
     * restarts at different ticks, where samples taken through single-precision pose math differ by rounding
     * alone: a float holds about seven significant digits, so a few steps of it come to a few
     * hundred-thousandths of a degree on a turn of a few radians. A thousandth stays clear of that and is far
     * under anything a frame can show.
     */
    private static final double SEAM_TIE = 1e-3;

    /** The smallest amplitude the period's parabola takes the logarithm of, so an empty bin still has one. */
    private static final double LOG_FLOOR = 1e-300;

    /** The golden-section steps that settle a slow peak's fitted frequency, each narrowing it by the golden ratio. */
    private static final int FIT_STEPS = 60;

    /** The cells a slow peak's frequency range is sampled in before a fit settles, so it settles in its best basin rather than the nearest. */
    private static final int FIT_CELLS = 8;

    /** The most harmonics a slow peak's shape is fitted with, its fundamental counted. */
    private static final int MOST_HARMONICS = 4;

    /** The share of a slow motion's variance one of its harmonic fits explains before the frequency they read is taken. */
    private static final double HARMONIC_SHARE = 0.8;

    /** The Hann taper the floor's spectrum reads the window through. */
    private static final double[] HANN = hann();

    /** The taper's sum in tick order, which scales a tapered bin back to an amplitude. */
    private static final double HANN_SUM = sum(HANN);

    /** The transform's twiddles over the window. */
    private static final Twiddles WINDOW = Twiddles.of(WINDOW_TICKS);

    /** The transform's twiddles over the zero-padded window. */
    private static final Twiddles PADDED = Twiddles.of(PERIOD_PADDING * WINDOW_TICKS);

    /**
     * Counts the ticks the strip spans before it loops.
     *
     * @return the frame count times the frame step
     */
    public int loopTicks() {
        return this.ticksPerFrame * this.frames;
    }

    /**
     * Tests whether the strip comes back round within one tolerance on every channel the restart judges.
     *
     * @return true where the seam is at most one tolerance
     */
    boolean closes() {
        return this.seam <= 1d;
    }

    /**
     * Tests whether the strip is a single frame, which only a row with no visible motion takes.
     *
     * @return true where the strip has one frame
     */
    public boolean isStill() {
        return this.frames == 1;
    }

    /**
     * Fits the strip a set of sampled tracks owes. It takes {@link #FINE_STEP} where coarse frames would alias
     * some channel's motion, running as long as a coarse strip may where a visible part is longer than
     * {@link #MAX_FRAMES} ticks; otherwise {@link #FINE_STEP} where every visible part's own length fits in
     * {@link #MAX_FRAMES} ticks and the fine restart closes or jumps no further than the coarse one, and
     * {@link #COARSE_STEP} where it does not.
     *
     * @param tracks the row's sampled contributions, in the order ties are broken by
     * @return the strip, {@link #STILL} where nothing visibly moves
     * @throws IllegalArgumentException if two tracks of one channel disagree on its kind or its wrap
     */
    public static @NotNull StripFit fit(@NotNull List<StripTrack> tracks) {
        Map<String, Channel> channels = new LinkedHashMap<>();
        for (StripTrack track : tracks)
            channels.computeIfAbsent(track.channel(), key -> new Channel(track)).add(track);

        if (channels.values().stream().noneMatch(Channel::travels)) return STILL;

        // Every visible part's own length, read once for each step the fit tries.
        List<Part> parts = new ArrayList<>();
        for (StripTrack track : tracks)
            if (travels(track.values(), wrapOf(track.wrap()), track.kind().tolerance())) parts.add(Part.of(track));
        int fineCap = MAX_FRAMES * FINE_STEP;
        boolean fitsFine = parts.stream().allMatch(part -> part.whole() <= fineCap);

        // Motion coarse frames would alias takes the fine step whatever it costs, and runs as long as a coarse
        // strip may where a part is longer than the fine cap.
        if (channels.values().stream().anyMatch(Channel::tooFastForCoarseFrames))
            return fitAt(parts, channels.values(), FINE_STEP, fitsFine ? fineCap : MAX_TICKS);

        StripFit coarse = fitAt(parts, channels.values(), COARSE_STEP, MAX_TICKS);
        if (!fitsFine) return coarse;

        StripFit fine = fitAt(parts, channels.values(), FINE_STEP, fineCap);
        return fine.restartsNoWorseThan(coarse) ? fine : coarse;
    }

    /**
     * Fits the strip a row owes at one frame step and cap.
     *
     * @param parts every visible part's own length
     * @param channels the row's tracks summed by channel, in the order each first appears
     * @param step the frame step
     * @param capTicks the longest strip at that step, a multiple of it
     * @return the strip at that step
     */
    private static @NotNull StripFit fitAt(@NotNull List<Part> parts, @NotNull Collection<Channel> channels, int step, int capTicks) {
        // The floor that shows every part whole, and the one the search starts from.
        int cover = 0;
        int searchCover = 0;
        for (Part part : parts) {
            Coverage coverage = part.coverage(capTicks);
            cover = Math.max(cover, coverage.whole());
            searchCover = Math.max(searchCover, coverage.searched());
        }

        // A one-shot that does not end where it started stops the strip at the floor.
        double oneShotJump = 0d;
        for (Channel channel : channels) {
            if (channel.oneShots.isEmpty()) continue;
            double jump = channel.oneShotJump();
            if (jump > oneShotJump) oneShotJump = jump;
        }
        if (oneShotJump > 1d) {
            int length = lengthFloor(cover, step, capTicks);
            return new StripFit(step, length / step, Math.max(oneShotJump, seamAt(channels, length, step)));
        }

        int floor = lengthFloor(searchCover, step, capTicks);
        int bestLength = floor;
        double bestSeam = Double.MAX_VALUE;
        for (int length = floor; length <= capTicks; length += step) {
            double seam = seamAt(channels, length, step);
            if (seam <= 1d) {
                bestLength = length;
                bestSeam = seam;
                break;
            }
            if (seam < bestSeam - TIE_EPSILON) {
                bestLength = length;
                bestSeam = seam;
            }
        }
        return new StripFit(step, bestLength / step, bestSeam);
    }

    /**
     * Tests whether the strip restarts no worse than another: it closes, or its seam is no larger, within
     * {@link #SEAM_TIE}.
     *
     * @param other the strip it would take the place of
     * @return true where the restart is no worse
     */
    private boolean restartsNoWorseThan(@NotNull StripFit other) {
        return this.closes() || this.seam <= other.seam + SEAM_TIE;
    }

    /**
     * Rounds a coverage to the shortest length a strip may take over it: up to the step, at least two
     * frames, at most the cap.
     *
     * @param cover the longest coverage asked for
     * @param step the frame step
     * @param capTicks the longest strip at that step
     * @return the floor in ticks, a multiple of the step
     */
    private static int lengthFloor(int cover, int step, int capTicks) {
        int rounded = (Math.min(cover, capTicks) + step - 1) / step * step;
        return Math.min(Math.max(2 * step, rounded), capTicks);
    }

    /**
     * Measures the restart's worst jump at a length over the channels it judges, in tolerances.
     *
     * @param channels the row's channels
     * @param length the candidate length
     * @param step the frame step
     * @return the largest jump, zero where no judged channel moves
     */
    private static double seamAt(@NotNull Collection<Channel> channels, int length, int step) {
        double worst = 0d;
        for (Channel channel : channels)
            if (channel.judgedAt(length)) worst = Math.max(worst, channel.jumpAt(length, step));
        return worst;
    }

    /**
     * Tests whether a series ever moves more than a tolerance from its value at tick 0.
     *
     * @param values the series
     * @param wrap the span it wraps at, zero for none
     * @param tolerance the tolerance in the series' unit
     * @return true where some tick is further than the tolerance from tick 0
     */
    private static boolean travels(double @NotNull [] values, double wrap, double tolerance) {
        for (int tick = 1; tick < WINDOW_TICKS; tick++)
            if (difference(values[tick], values[0], wrap) > tolerance) return true;

        return false;
    }

    /**
     * Reads the period of an expression's slowest visible motion: the lowest spectral peak of at least a
     * tolerance, read through a Hann taper over the zero-padded window, or the turn period of a channel that
     * spins whole turns where that is longer.
     *
     * <p>A peak stands at or above every bin within one bin of the unpadded spectrum, so the sidelobe a large
     * swing throws beside its own lobe never reads as a slower motion. It is refined by a parabola through
     * its neighbours, or, under two cycles of the window, where its lobe overlaps its own mirror and the
     * parabola reads it long, by fitting the samples themselves.
     *
     * @param values the expression's samples
     * @param wrap the span it wraps at, zero for none
     * @param tolerance the tolerance in the samples' unit
     * @return the period in ticks, zero where nothing reaches a tolerance
     */
    private static double slowestPeriod(double @NotNull [] values, double wrap, double tolerance) {
        double[] x = unwrapped(values, wrap);
        double slope = spinSlope(x, wrap);
        double spin = slope != 0 ? wrap / Math.abs(slope) : 0;
        double mean = mean(x);
        for (int tick = 0; tick < WINDOW_TICKS; tick++) x[tick] -= mean;

        int points = PERIOD_PADDING * WINDOW_TICKS;
        double[] re = new double[points];
        double[] im = new double[points];
        for (int tick = 0; tick < WINDOW_TICKS; tick++) re[tick] = x[tick] * HANN[tick];
        transform(re, im, PADDED);

        double[] amplitude = new double[points / 2 + 1];
        for (int bin = 0; bin <= points / 2; bin++)
            amplitude[bin] = 2 * Math.sqrt(re[bin] * re[bin] + im[bin] * im[bin]) / HANN_SUM;

        for (int bin = 1; bin < points / 2; bin++) {
            if (amplitude[bin] < tolerance || !peaksAt(amplitude, bin)) continue;
            if (bin < 2 * PERIOD_PADDING) return Math.max(spin, points / slowBin(x, bin));

            double left = StrictMath.log(Math.max(amplitude[bin - 1], LOG_FLOOR));
            double centre = StrictMath.log(Math.max(amplitude[bin], LOG_FLOOR));
            double right = StrictMath.log(Math.max(amplitude[bin + 1], LOG_FLOOR));
            double curvature = left - 2 * centre + right;
            double offset = curvature != 0 ? 0.5 * (left - right) / curvature : 0;
            return Math.max(spin, points / (bin + offset));
        }

        return spin;
    }

    /**
     * Tests whether a bin of the padded spectrum stands at or above every bin within one bin of the unpadded
     * spectrum, a bin past either end read as its mirror.
     *
     * @param amplitude the padded spectrum's amplitudes, from DC to the Nyquist bin
     * @param bin the bin
     * @return true where no bin that close is larger
     */
    private static boolean peaksAt(double @NotNull [] amplitude, int bin) {
        int nyquist = amplitude.length - 1;
        for (int other = bin - PERIOD_PADDING; other <= bin + PERIOD_PADDING; other++) {
            int mirrored = Math.abs(other);
            if (mirrored > nyquist) mirrored = 2 * nyquist - mirrored;
            if (amplitude[bin] < amplitude[mirrored]) return false;
        }

        return true;
    }

    /**
     * Reads the frequency of a peak under two cycles of the window, within a bin either side of it, from
     * fits of the samples themselves.
     *
     * <p>A lone sinusoid fits a lone sinusoid exactly, but a motion this slow holds its second harmonic inside
     * its own lobe, and a lone sinusoid leans on it and reads the motion fast. So the samples are also fitted
     * with a fundamental and its harmonics, two to {@link #MOST_HARMONICS} of them. Each of those leans on the
     * first harmonic it does not hold, which only ever reads the motion fast, so the slowest of their
     * frequencies is the one least leaned on, and it is taken where one of them explains at least
     * {@link #HARMONIC_SHARE} of the samples' variance. Where none does, a faster motion beside the slow one
     * holds the rest, which the harmonics would lean on instead, so the lone sinusoid's frequency is taken.
     *
     * @param x the series, unwrapped, detrended and centred on its mean
     * @param bin the peak's bin of the padded spectrum
     * @return the frequency, in bins of the padded spectrum
     */
    private static double slowBin(double @NotNull [] x, int bin) {
        double low = Math.max(bin - 1, 0.5);
        double high = bin + 1;
        double variance = weightedVariance(x);
        double slowest = Double.MAX_VALUE;
        double explained = 0;
        for (int harmonics = 2; harmonics <= MOST_HARMONICS; harmonics++) {
            Optional<Harmonics> fit = bestFit(x, low, high, harmonics);
            if (fit.isEmpty()) continue;
            slowest = Math.min(slowest, fit.get().bin());
            explained = Math.max(explained, 1 - fit.get().residual() / variance);
        }

        return explained >= HARMONIC_SHARE ? slowest : settled(x, low, high, 1);
    }

    /**
     * Finds the frequency in a range at which a fundamental and its harmonics fit a series best: the residual
     * is read at the edges of {@link #FIT_CELLS} cells across the range, and settled between the neighbours
     * of each edge no higher than they are. A fit whose fundamental is weaker than one of its harmonics is
     * passed over, since it fits a slower motion that is not there - a lone sinusoid fits as the second
     * harmonic of half its frequency.
     *
     * @param x the series, centred on its mean
     * @param low the range's lowest frequency, in bins of the padded spectrum
     * @param high the range's highest frequency, in bins of the padded spectrum
     * @param harmonics the harmonics fitted, the fundamental counted
     * @return the best fit, empty where every fit is passed over
     */
    private static @NotNull Optional<Harmonics> bestFit(double @NotNull [] x, double low, double high, int harmonics) {
        double[] residuals = new double[FIT_CELLS + 1];
        for (int cell = 0; cell <= FIT_CELLS; cell++)
            residuals[cell] = harmonicFit(x, low + (high - low) * cell / FIT_CELLS, harmonics).residual();

        Harmonics best = null;
        for (int cell = 0; cell <= FIT_CELLS; cell++) {
            if (cell > 0 && residuals[cell] > residuals[cell - 1] || cell < FIT_CELLS && residuals[cell] > residuals[cell + 1])
                continue;

            double from = low + (high - low) * Math.max(cell - 1, 0) / FIT_CELLS;
            double to = low + (high - low) * Math.min(cell + 1, FIT_CELLS) / FIT_CELLS;
            Harmonics fit = harmonicFit(x, settled(x, from, to, harmonics), harmonics);
            if (fit.fundamental() < fit.strongestHarmonic()) continue;
            if (best == null || fit.residual() < best.residual()) best = fit;
        }
        return Optional.ofNullable(best);
    }

    /**
     * Settles the frequency in a range at which a fundamental and its harmonics fit a series best, by a
     * golden-section search on the fit's residual.
     *
     * @param x the series, centred on its mean
     * @param low the range's lowest frequency, in bins of the padded spectrum
     * @param high the range's highest frequency, in bins of the padded spectrum
     * @param harmonics the harmonics fitted, the fundamental counted
     * @return the frequency, in bins of the padded spectrum
     */
    private static double settled(double @NotNull [] x, double low, double high, int harmonics) {
        double golden = (Math.sqrt(5) - 1) / 2;
        double inner = high - golden * (high - low);
        double outer = low + golden * (high - low);
        double innerResidual = harmonicFit(x, inner, harmonics).residual();
        double outerResidual = harmonicFit(x, outer, harmonics).residual();
        for (int step = 0; step < FIT_STEPS; step++) {
            if (innerResidual < outerResidual) {
                high = outer;
                outer = inner;
                outerResidual = innerResidual;
                inner = high - golden * (high - low);
                innerResidual = harmonicFit(x, inner, harmonics).residual();
            } else {
                low = inner;
                inner = outer;
                innerResidual = outerResidual;
                outer = low + golden * (high - low);
                outerResidual = harmonicFit(x, outer, harmonics).residual();
            }
        }
        return (low + high) / 2;
    }

    /**
     * Fits a series by least squares with a constant, a fundamental and its harmonics, each tick weighted by
     * the Hann taper.
     *
     * @param x the series, centred on its mean
     * @param bin the fundamental's frequency, in bins of the padded spectrum
     * @param harmonics the harmonics fitted, the fundamental counted
     * @return the fit, which leaves the whole series where its terms cannot be told apart
     */
    private static @NotNull Harmonics harmonicFit(double @NotNull [] x, double bin, int harmonics) {
        int terms = 1 + 2 * harmonics;
        double frequency = bin / (PERIOD_PADDING * WINDOW_TICKS);
        double cosStep = StrictMath.cos(2 * StrictMath.PI * frequency);
        double sinStep = StrictMath.sin(2 * StrictMath.PI * frequency);
        double cos = 1;
        double sin = 0;
        double[] basis = new double[terms];
        double[][] normal = new double[terms][terms];
        double[] projection = new double[terms];
        double energy = 0;
        for (int tick = 0; tick < WINDOW_TICKS; tick++) {
            // The constant, then each harmonic's cosine and sine, stepped from the fundamental's by rotation.
            basis[0] = 1;
            double harmonicCos = cos;
            double harmonicSin = sin;
            for (int harmonic = 0; harmonic < harmonics; harmonic++) {
                basis[1 + 2 * harmonic] = harmonicCos;
                basis[2 + 2 * harmonic] = harmonicSin;
                double next = harmonicCos * cos - harmonicSin * sin;
                harmonicSin = harmonicSin * cos + harmonicCos * sin;
                harmonicCos = next;
            }
            double w = HANN[tick];
            for (int row = 0; row < terms; row++) {
                double weighted = w * basis[row];
                for (int column = 0; column <= row; column++) normal[row][column] += weighted * basis[column];
                projection[row] += weighted * x[tick];
            }
            energy += w * x[tick] * x[tick];
            double next = cos * cosStep - sin * sinStep;
            sin = sin * cosStep + cos * sinStep;
            cos = next;
        }

        // The normal equations' lower triangle, factored in place by Cholesky.
        for (int row = 0; row < terms; row++)
            for (int column = 0; column <= row; column++) {
                double sum = normal[row][column];
                for (int k = 0; k < column; k++) sum -= normal[row][k] * normal[column][k];
                if (row != column) normal[row][column] = sum / normal[column][column];
                else if (sum > 0) normal[row][row] = Math.sqrt(sum);
                else return new Harmonics(bin, energy, 0, 0);
            }

        // Forward substitution measures what the fit explains, and back substitution recovers its terms.
        double[] reduced = new double[terms];
        double explained = 0;
        for (int row = 0; row < terms; row++) {
            double sum = projection[row];
            for (int k = 0; k < row; k++) sum -= normal[row][k] * reduced[k];
            reduced[row] = sum / normal[row][row];
            explained += reduced[row] * reduced[row];
        }
        double[] term = new double[terms];
        for (int row = terms - 1; row >= 0; row--) {
            double sum = reduced[row];
            for (int k = row + 1; k < terms; k++) sum -= normal[k][row] * term[k];
            term[row] = sum / normal[row][row];
        }

        double strongest = 0;
        for (int harmonic = 1; harmonic < harmonics; harmonic++)
            strongest = Math.max(strongest, Math.sqrt(term[1 + 2 * harmonic] * term[1 + 2 * harmonic] + term[2 + 2 * harmonic] * term[2 + 2 * harmonic]));

        return new Harmonics(bin, energy - explained, Math.sqrt(term[1] * term[1] + term[2] * term[2]), strongest);
    }

    /**
     * Measures what a constant fitted by least squares leaves of a series, each tick weighted by the Hann
     * taper - its spread about its weighted mean.
     *
     * @param x the series
     * @return the weighted sum of the squares about the weighted mean
     */
    private static double weightedVariance(double @NotNull [] x) {
        double weights = 0;
        double value = 0;
        double square = 0;
        for (int tick = 0; tick < WINDOW_TICKS; tick++) {
            weights += HANN[tick];
            value += HANN[tick] * x[tick];
            square += HANN[tick] * x[tick] * x[tick];
        }
        return square - value * value / weights;
    }

    /**
     * Lays a wrapped series out as one continuous line, each step taken the short way round the wrap.
     *
     * @param values the series
     * @param wrap the span it wraps at, zero for none
     * @return a fresh copy, unwrapped
     */
    private static double @NotNull [] unwrapped(double @NotNull [] values, double wrap) {
        double[] x = new double[WINDOW_TICKS];
        x[0] = values[0];
        for (int tick = 1; tick < WINDOW_TICKS; tick++)
            x[tick] = wrap > 0 ? x[tick - 1] + signedDifference(values[tick], values[tick - 1], wrap) : values[tick];

        return x;
    }

    /**
     * Takes the trend out of an unwrapped series that turns at least one whole wrap over the window, so the
     * spin's sawtooth cannot pass for motion of its own.
     *
     * @param x the unwrapped series, detrended in place where it spins
     * @param wrap the span it wraps at, zero for none
     * @return the spin's slope per tick, zero where it does not spin
     */
    private static double spinSlope(double @NotNull [] x, double wrap) {
        if (!(wrap > 0)) return 0d;
        double slope = slope(x);
        if (!(Math.abs(slope) * WINDOW_TICKS >= wrap)) return 0d;

        for (int tick = 0; tick < WINDOW_TICKS; tick++) x[tick] -= slope * tick;
        return slope;
    }

    /**
     * Fits the least-squares slope of a series over the window's ticks.
     *
     * @param x the series
     * @return its slope, per tick
     */
    private static double slope(double @NotNull [] x) {
        double n = WINDOW_TICKS;
        double sumTicks = (n - 1) * n / 2;
        double sumSquares = (n - 1) * n * (2 * n - 1) / 6;
        double sumValues = 0;
        double sumProducts = 0;
        for (int tick = 0; tick < WINDOW_TICKS; tick++) {
            sumValues += x[tick];
            sumProducts += tick * x[tick];
        }
        return (n * sumProducts - sumTicks * sumValues) / (n * sumSquares - sumTicks * sumTicks);
    }

    /**
     * Averages a series over the window, summed in tick order.
     *
     * @param x the series
     * @return its mean
     */
    private static double mean(double @NotNull [] x) {
        return sum(x) / WINDOW_TICKS;
    }

    /**
     * Sums a series in index order.
     *
     * @param x the series
     * @return its sum
     */
    private static double sum(double @NotNull [] x) {
        double sum = 0;
        for (double value : x) sum += value;
        return sum;
    }

    /**
     * Takes the difference of two values the short way round a wrap.
     *
     * @param a the value moved to
     * @param b the value moved from
     * @param wrap the span they wrap at, zero for none
     * @return the difference, in {@code [-wrap / 2, wrap / 2)} where it wraps
     */
    private static double signedDifference(double a, double b, double wrap) {
        double d = a - b;
        return wrap > 0 ? d - wrap * Math.floor(d / wrap + 0.5) : d;
    }

    /**
     * Measures the distance between two values the short way round a wrap.
     *
     * @param a one value
     * @param b the other
     * @param wrap the span they wrap at, zero for none
     * @return the distance
     */
    private static double difference(double a, double b, double wrap) {
        double d = a - b;
        if (wrap > 0) d = d - wrap * Math.floor(d / wrap + 0.5);
        return Math.abs(d);
    }

    /**
     * Reads a track's wrap as the arithmetic takes it.
     *
     * @param wrap the wrap, empty for none
     * @return the span, zero for none
     */
    private static double wrapOf(@NotNull OptionalDouble wrap) {
        return wrap.orElse(0d);
    }

    /**
     * Builds the Hann taper over the window.
     *
     * @return one weight per tick
     */
    private static double @NotNull [] hann() {
        double[] taper = new double[WINDOW_TICKS];
        for (int tick = 0; tick < WINDOW_TICKS; tick++)
            taper[tick] = 0.5 - 0.5 * StrictMath.cos(2 * StrictMath.PI * tick / (WINDOW_TICKS - 1));

        return taper;
    }

    /**
     * Transforms a complex series in place - the iterative radix-2 discrete Fourier transform.
     *
     * @param re the real parts, a power of two of them
     * @param im the imaginary parts, as many
     * @param twiddles the twiddles for that many points
     */
    private static void transform(double @NotNull [] re, double @NotNull [] im, @NotNull Twiddles twiddles) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double swap = re[i];
                re[i] = re[j];
                re[j] = swap;
                swap = im[i];
                im[i] = im[j];
                im[j] = swap;
            }
        }
        for (int span = 2; span <= n; span <<= 1) {
            int half = span >> 1;
            int stride = n / span;
            for (int i = 0; i < n; i += span)
                for (int j = 0; j < half; j++) {
                    double wr = twiddles.cos[j * stride];
                    double wi = twiddles.sin[j * stride];
                    double ur = re[i + j];
                    double ui = im[i + j];
                    double vr = re[i + j + half] * wr - im[i + j + half] * wi;
                    double vi = re[i + j + half] * wi + im[i + j + half] * wr;
                    re[i + j] = ur + vr;
                    im[i + j] = ui + vi;
                    re[i + j + half] = ur - vr;
                    im[i + j + half] = ui - vi;
                }
        }
    }

    /**
     * The transform's rotation factors for one size, built once.
     *
     * @param cos the cosine of each factor
     * @param sin the negated sine of each factor
     */
    private record Twiddles(double @NotNull [] cos, double @NotNull [] sin) {

        /**
         * Builds the factors for a transform of a size.
         *
         * @param size the number of points
         * @return half as many factors
         */
        static @NotNull Twiddles of(int size) {
            double[] cos = new double[size / 2];
            double[] sin = new double[size / 2];
            for (int i = 0; i < size / 2; i++) {
                cos[i] = StrictMath.cos(2 * StrictMath.PI * i / size);
                sin[i] = -StrictMath.sin(2 * StrictMath.PI * i / size);
            }
            return new Twiddles(cos, sin);
        }

    }

    /**
     * A least-squares fit of a series by a constant, a fundamental and its harmonics.
     *
     * @param bin the fundamental's frequency, in bins of the padded spectrum
     * @param residual the Hann-weighted sum of the squares the fit leaves
     * @param fundamental the fundamental's amplitude
     * @param strongestHarmonic the largest amplitude of the other harmonics, zero where there are none
     */
    private record Harmonics(double bin, double residual, double fundamental, double strongestHarmonic) {}

    /**
     * One visible track's own length, read once and shared by every step the fit tries.
     *
     * @param length the ticks the part asks to be shown whole in, with no cap - a one-shot's length, a looping part's period, an expression's slowest visible period - and zero for a looping part with no period or an expression no peak of which reaches a tolerance
     * @param read whether the length is read off the samples, as an expression's is, rather than declared
     * @param opacity whether the part is an opacity, which the restart never judges
     */
    private record Part(double length, boolean read, boolean opacity) {

        /**
         * Reads a visible track's part.
         *
         * @param track the track
         * @return its part
         */
        static @NotNull Part of(@NotNull StripTrack track) {
            boolean opacity = track.kind() == Kind.OPACITY;
            return switch (track.role()) {
                case Role.OneShot(double lengthTicks) -> new Part(lengthTicks, false, opacity);
                case Role.Looping(double periodTicks) -> new Part(periodTicks > 0d && Double.isFinite(periodTicks) ? periodTicks : 0d, false, opacity);
                case Role.Expression() -> new Part(slowestPeriod(track.values(), wrapOf(track.wrap()), track.kind().tolerance()), true, opacity);
            };
        }

        /**
         * Rounds the part's length up to whole ticks.
         *
         * @return the ticks that show the part whole once, with no cap
         */
        int whole() {
            return (int) Math.ceil(this.length - TIE_EPSILON);
        }

        /**
         * Measures the least length the part asks to be shown whole in under a cap, and the least the search
         * for a restart starts from over it. A declared length asks for itself rounded up, and the search
         * leaves it out where it is longer than the cap. A read period asks for itself clamped to the cap and
         * rounded up; where the restart judges it, the search starts from that period trimmed by the slack.
         *
         * @param capTicks the longest strip at the step tried
         * @return the part's coverage in ticks
         */
        @NotNull Coverage coverage(int capTicks) {
            if (!this.read) return Coverage.exact(this.whole(), capTicks);

            double period = Math.min(this.length, capTicks);
            int whole = (int) Math.ceil(period - TIE_EPSILON);
            return new Coverage(whole, this.opacity ? whole : (int) Math.floor(COVERAGE_SLACK * period));
        }

    }

    /**
     * The least lengths one part asks a strip to run, in ticks.
     *
     * @param whole the length that shows the part whole once, which a strip that stops at its floor runs
     * @param searched the length the search for a restart starts from - zero for an exact part too long to show whole, and the period trimmed by the slack for an expression the restart judges
     */
    private record Coverage(int whole, int searched) {

        /**
         * Builds the coverage of an exact part, whose whole length the search starts from where it fits under the cap.
         *
         * @param whole the part's length or period, rounded up to whole ticks
         * @param capTicks the longest strip at the step tried
         * @return the coverage
         */
        static @NotNull Coverage exact(int whole, int capTicks) {
            return new Coverage(whole, whole <= capTicks ? whole : 0);
        }

    }

    /**
     * The tracks of one channel key: what they carry, and their contributions summed - the repeating parts
     * in one series, the one-shots in another, each one-shot also kept apart for the restart to judge while
     * it is still running.
     */
    private static final class Channel {

        /** The channel's key. */
        private final @NotNull String key;

        /** What the channel carries. */
        private final @NotNull Kind kind;

        /** The span the channel wraps at, as its tracks declare it. */
        private final @NotNull OptionalDouble wrapping;

        /** The span the channel wraps at, zero for none. */
        private final double wrap;

        /** The expression and looping contributions, summed in track order. */
        private final double[] loop = new double[WINDOW_TICKS];

        /** The one-shot contributions, summed in track order. */
        private final double[] once = new double[WINDOW_TICKS];

        /** The one-shot tracks, in track order. */
        private final @NotNull List<StripTrack> oneShots = new ArrayList<>();

        /** Whether any expression or looping track contributes. */
        private boolean looping;

        /**
         * Opens a channel on the first track that names it.
         *
         * @param first the track
         */
        Channel(@NotNull StripTrack first) {
            this.key = first.channel();
            this.kind = first.kind();
            this.wrapping = first.wrap();
            this.wrap = wrapOf(first.wrap());
        }

        /**
         * Adds a track's contribution.
         *
         * @param track the track
         * @throws IllegalArgumentException if it disagrees with the channel on kind or wrap
         */
        void add(@NotNull StripTrack track) {
            if (track.kind() != this.kind)
                throw new IllegalArgumentException(String.format("Channel '%s' carries both '%s' and '%s'", this.key, this.kind, track.kind()));
            if (!track.wrap().equals(this.wrapping))
                throw new IllegalArgumentException(String.format("Channel '%s' wraps at both '%s' and '%s'", this.key, spelled(this.wrapping), spelled(track.wrap())));

            double[] into;
            if (track.role() instanceof Role.OneShot) {
                this.oneShots.add(track);
                into = this.once;
            } else {
                this.looping = true;
                into = this.loop;
            }
            for (int tick = 0; tick < WINDOW_TICKS; tick++) into[tick] += track.values()[tick];
        }

        /**
         * Tests whether the channel's whole sum ever moves more than a tolerance from its value at tick 0.
         *
         * @return true where it visibly moves
         */
        boolean travels() {
            double start = this.loop[0] + this.once[0];
            for (int tick = 1; tick < WINDOW_TICKS; tick++)
                if (difference(this.loop[tick] + this.once[tick], start, this.wrap) > this.kind.tolerance()) return true;

            return false;
        }

        /**
         * Tests whether the channel's repeating motion is too fast for coarse frames and big enough to see: a
         * spin turning faster than two coarse frames, or most of its power faster than that with at least a
         * tolerance of amplitude there.
         *
         * @return true where the channel asks for the fine step
         */
        boolean tooFastForCoarseFrames() {
            double[] x = unwrapped(this.loop, this.wrap);
            double slope = spinSlope(x, this.wrap);
            if (slope != 0 && this.wrap / Math.abs(slope) < 2 * COARSE_STEP) return true;

            double mean = mean(x);
            double[] re = new double[WINDOW_TICKS];
            double[] im = new double[WINDOW_TICKS];
            for (int tick = 0; tick < WINDOW_TICKS; tick++) re[tick] = x[tick] - mean;
            transform(re, im, WINDOW);

            int firstFast = WINDOW_TICKS / (2 * COARSE_STEP) + 1;
            double total = 0;
            double fast = 0;
            for (int bin = 1; bin <= WINDOW_TICKS / 2; bin++) {
                double power = re[bin] * re[bin] + im[bin] * im[bin];
                total += power;
                if (bin >= firstFast) fast += power;
            }
            double amplitude = 2 * Math.sqrt(fast) / WINDOW_TICKS / this.kind.tolerance();
            double fraction = total > 0 ? fast / total : 0;
            return fraction >= HIGH_BAND_FRACTION && amplitude >= VISIBILITY_FLOOR;
        }

        /**
         * Measures how far the channel's one-shots leave it from where they started, read at the window's last
         * tick.
         *
         * @return the jump in tolerances
         */
        double oneShotJump() {
            return difference(this.once[WINDOW_TICKS - 1], this.once[0], this.wrap) / this.kind.tolerance();
        }

        /**
         * Tests whether the restart at a length judges this channel: one that is not an opacity and has a
         * repeating part, or a one-shot the length cuts short.
         *
         * @param length the candidate length
         * @return true where the channel is in the seam
         */
        boolean judgedAt(int length) {
            if (this.kind == Kind.OPACITY) return false;
            if (this.looping) return true;

            for (StripTrack oneShot : this.oneShots)
                if (lengthOf(oneShot) > length) return true;

            return false;
        }

        /**
         * Measures the restart's jump on this channel at a length - the larger move across the first two
         * frames after it - in tolerances.
         *
         * @param length the candidate length
         * @param step the frame step
         * @return the jump
         */
        double jumpAt(int length, int step) {
            double jump = Math.max(difference(at(length, length), at(0, length), this.wrap),
                difference(at(length + step, length), at(step, length), this.wrap));
            return jump / this.kind.tolerance();
        }

        /**
         * Reads the channel's value at a tick as the restart at a length sees it: the repeating parts, and
         * every one-shot still running at that length.
         *
         * @param tick the tick
         * @param length the candidate length
         * @return the value
         */
        private double at(int tick, int length) {
            double value = this.loop[tick];
            for (StripTrack oneShot : this.oneShots)
                if (lengthOf(oneShot) > length) value += oneShot.values()[tick];

            return value;
        }

        /**
         * Reads a one-shot track's length.
         *
         * @param oneShot the track
         * @return its length in ticks
         */
        private static double lengthOf(@NotNull StripTrack oneShot) {
            return ((Role.OneShot) oneShot.role()).lengthTicks();
        }

        /**
         * Spells a wrap the way a refusal names it.
         *
         * @param wrap the wrap
         * @return its span, or {@code nowhere}
         */
        private static @NotNull String spelled(@NotNull OptionalDouble wrap) {
            return wrap.isPresent() ? String.valueOf(wrap.getAsDouble()) : "nowhere";
        }

    }

}
