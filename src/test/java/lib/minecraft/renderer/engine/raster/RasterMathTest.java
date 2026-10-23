package lib.minecraft.renderer.engine.raster;

import lib.minecraft.renderer.engine.math.Vector2f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Coverage of the fixed-point coverage math the rasterizer runs per pixel - the sub-pixel sample
 * quantization, the Pineda edge-function factoring, the top-left fill rule, the barycentric and
 * bounding-box helpers that ride alongside them, and the texel an interpolated coordinate reads.
 * <p>
 * Three claims the class states in prose and nothing checked.
 * <ul>
 * <li><b>The factoring is exact.</b> The precomputed {@link RasterMath.EdgeCoefficients} form claims
 * to be an exact factoring of the unfactored edge function
 * {@code e_ij(sx, sy) = (xj-xi)*(sy-yi) - (yj-yi)*(sx-xi)}. These tests evaluate both and demand the
 * same ownership answer over degenerate, positive-determinant and negative-determinant triangles, and
 * over samples landing inside, outside, on an edge and on a vertex.</li>
 * <li><b>The sign normalization is winding-neutral.</b> {@code of(v0, v1, v2)} and
 * {@code of(v0, v2, v1)} are one triangle submitted the other way round. The {@code topLeft} flags are
 * negated alongside the coefficients on an antisymmetry argued in prose and asserted nowhere, so the
 * two coefficient sets have to agree up to the slot swap the reversal performs, and the two have to
 * own exactly the same samples.</li>
 * <li><b>The incremental walk lands on the same value, over exactly the columns it has to.</b> The
 * {@code stepX} / {@code stepY} fields are the coefficients times the sub-pixel precision, and stepping
 * an edge value across a row's span has to reach what a fresh evaluation computes - that walk is what
 * the engine's inner loop runs. The span itself, read off each edge's crossing and stepped a row at a
 * time, has to hold exactly the columns where a fresh evaluation finds all three edge values
 * non-negative, so every sample the triangle owns lies inside it.</li>
 * </ul>
 * <p>
 * <b>Observed rather than documented.</b> The free-function overload of
 * {@code isInsideTriangle} builds the coefficients and delegates to the precomputed overload, so the
 * "bit-identical inside-test outcome" its javadoc credits to the algebra is true by construction
 * instead. The overload comparison is kept as a fence against a future hand-written second
 * implementation, and the factoring claim is pinned against an independently unfactored evaluation.
 * <p>
 * <b>The loop's trace calls are read, not run.</b> Every per-pixel trace call in the rasterizer's tile
 * walk has to sit behind the tracing flag the tile reads once. A call left bare in the loop draws the
 * same pixels and slows every sample the loop visits, so no render can tell, and the last nested class
 * reads the rasterizer's source instead.
 */
@DisplayName("Raster coverage math")
class RasterMathTest {

    /** Screen-space sample grid every ownership table sweeps, at half-pixel spacing */
    private static final List<Vector2f> GRID = buildGrid();

    /**
     * A named screen-space triangle the ownership tables run every sample through.
     *
     * @param name what the triangle is, for a failure message
     * @param v0 the first vertex
     * @param v1 the second vertex
     * @param v2 the third vertex
     */
    private record Tri(@NotNull String name, @NotNull Vector2f v0, @NotNull Vector2f v1,
                       @NotNull Vector2f v2) {}

    /** Builds the shared half-pixel sample grid spanning every fixture triangle and its surround. */
    private static @NotNull List<Vector2f> buildGrid() {
        List<Vector2f> out = new ArrayList<>();
        for (int iy = -2; iy <= 14; iy++)
            for (int ix = -2; ix <= 21; ix++)
                out.add(new Vector2f(ix * 0.5f, iy * 0.5f));
        return List.copyOf(out);
    }

    /**
     * The fixture triangles - each winding of an axis-aligned and an off-lattice triangle, a sliver, a
     * triangle whose bottom edge is horizontal, and the two shapes of degeneracy.
     */
    private static @NotNull List<Tri> triangles() {
        return List.of(
            new Tri("axis-aligned, positive determinant",
                new Vector2f(0f, 0f), new Vector2f(4f, 0f), new Vector2f(0f, 4f)),
            new Tri("axis-aligned, negative determinant",
                new Vector2f(0f, 0f), new Vector2f(0f, 4f), new Vector2f(4f, 0f)),
            new Tri("off-lattice, positive determinant",
                new Vector2f(1.5f, 0.25f), new Vector2f(7.75f, 2.5f), new Vector2f(3f, 6.5f)),
            new Tri("off-lattice, negative determinant",
                new Vector2f(1.5f, 0.25f), new Vector2f(3f, 6.5f), new Vector2f(7.75f, 2.5f)),
            new Tri("sliver",
                new Vector2f(0.1f, 0.1f), new Vector2f(9.9f, 0.35f), new Vector2f(5f, 0.2f)),
            new Tri("horizontal bottom edge",
                new Vector2f(2f, 0f), new Vector2f(4f, 4f), new Vector2f(0f, 4f)),
            new Tri("collinear, degenerate",
                new Vector2f(0f, 0f), new Vector2f(2f, 2f), new Vector2f(4f, 4f)),
            new Tri("repeated vertex, degenerate",
                new Vector2f(1f, 1f), new Vector2f(1f, 1f), new Vector2f(5f, 3f))
        );
    }

    /** Returns the shared grid plus this triangle's own vertices and edge midpoints. */
    private static @NotNull List<Vector2f> samplesFor(@NotNull Tri t) {
        List<Vector2f> out = new ArrayList<>(GRID);
        out.add(t.v0());
        out.add(t.v1());
        out.add(t.v2());
        out.add(midpoint(t.v0(), t.v1()));
        out.add(midpoint(t.v1(), t.v2()));
        out.add(midpoint(t.v2(), t.v0()));
        return out;
    }

    private static @NotNull Vector2f midpoint(@NotNull Vector2f a, @NotNull Vector2f b) {
        return new Vector2f((a.x() + b.x()) * 0.5f, (a.y() + b.y()) * 0.5f);
    }

    /**
     * Evaluates the same ownership rule from the unfactored edge function, quantizing the vertices and
     * the sample the same way and applying the sign normalization as a multiply rather than as a
     * rewrite of stored coefficients. This is the independent half of the "exact factoring" claim.
     *
     * @param t the triangle
     * @param px the sample's x coordinate
     * @param py the sample's y coordinate
     * @return whether the triangle owns the sample under the top-left rule
     */
    private static boolean unfactoredInside(@NotNull Tri t, float px, float py) {
        long x0 = RasterMath.quantizeSample(t.v0().x());
        long y0 = RasterMath.quantizeSample(t.v0().y());
        long x1 = RasterMath.quantizeSample(t.v1().x());
        long y1 = RasterMath.quantizeSample(t.v1().y());
        long x2 = RasterMath.quantizeSample(t.v2().x());
        long y2 = RasterMath.quantizeSample(t.v2().y());

        long det = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
        if (det == 0L) return false;

        long sign = det < 0L ? -1L : 1L;
        long sx = RasterMath.quantizeSample(px);
        long sy = RasterMath.quantizeSample(py);
        long e12 = sign * crossEdge(x1, y1, x2, y2, sx, sy);
        long e20 = sign * crossEdge(x2, y2, x0, y0, sx, sy);
        long e01 = sign * crossEdge(x0, y0, x1, y1, sx, sy);

        if (e12 < 0L || e20 < 0L || e01 < 0L) return false;
        if (e12 == 0L && !topOrLeft(x1, y1, x2, y2, sign)) return false;
        if (e20 == 0L && !topOrLeft(x2, y2, x0, y0, sign)) return false;
        if (e01 == 0L && !topOrLeft(x0, y0, x1, y1, sign)) return false;
        return true;
    }

    /** The unfactored cross product of the directed edge {@code i -> j} against the sample. */
    private static long crossEdge(long xi, long yi, long xj, long yj, long sx, long sy) {
        return (xj - xi) * (sy - yi) - (yj - yi) * (sx - xi);
    }

    /** Classifies the directed edge, inverting the answer where the sign normalization reverses it. */
    private static boolean topOrLeft(long sx, long sy, long ex, long ey, long sign) {
        boolean classified = sy == ey ? ex < sx : ey < sy;
        return sign > 0L ? classified : !classified;
    }

    @Nested
    @DisplayName("quantizeSample")
    class Quantization {

        @Test
        @DisplayName("one screen pixel is 256 sub-pixel units")
        void onePixelIsTwoHundredFiftySixSubPixelUnits() {
            assertThat(RasterMath.quantizeSample(0f), equalTo(0L));
            assertThat(RasterMath.quantizeSample(1f), equalTo(256L));
            assertThat(RasterMath.quantizeSample(0.5f), equalTo(128L));
            assertThat(RasterMath.quantizeSample(-1f), equalTo(-256L));
            assertThat(RasterMath.quantizeSample(10.5f), equalTo(2688L));
        }

        @Test
        @DisplayName("a half sub-pixel unit rounds toward positive infinity on both signs")
        void halfUnitRoundsTowardPositiveInfinity() {
            // 1/512 is exactly half a sub-pixel unit, so it exercises the tie both ways round. The
            // helper is Math.round, which is floor(a + 0.5) - the positive tie rounds away from zero
            // and the negative tie rounds toward it, rather than the two being mirror images.
            float half = 1f / 512f;
            assertThat(RasterMath.quantizeSample(half), equalTo(1L));
            assertThat(RasterMath.quantizeSample(-half), equalTo(0L));
            assertThat(RasterMath.quantizeSample(3f * half), equalTo(2L));
            assertThat(RasterMath.quantizeSample(-3f * half), equalTo(-1L));
        }

    }

    @Nested
    @DisplayName("isInsideTriangle")
    class InsideTriangle {

        @Test
        @DisplayName("both overloads and an unfactored evaluation agree on every sample of every fixture")
        void bothOverloadsAgreeWithTheUnfactoredEdgeFunction() {
            for (Tri t : triangles()) {
                RasterMath.EdgeCoefficients ec = RasterMath.EdgeCoefficients.of(t.v0(), t.v1(), t.v2());
                for (Vector2f s : samplesFor(t)) {
                    boolean precomputed = RasterMath.isInsideTriangle(ec, s.x(), s.y());
                    boolean free = RasterMath.isInsideTriangle(t.v0(), t.v1(), t.v2(), s.x(), s.y());
                    String where = t.name() + " at " + s;
                    assertThat(where, free, equalTo(precomputed));
                    assertThat(where, unfactoredInside(t, s.x(), s.y()), equalTo(precomputed));
                }
            }
        }

        @Test
        @DisplayName("a pixel centre on a horizontal edge two faces share goes to the face above it alone")
        void aSampleOnASharedHorizontalEdgeGoesToTheFaceAboveIt() {
            // Image space with y running down. The two faces share the edge from (0.5, 2.5) to
            // (4.5, 2.5), the upper one reaching up to (2.5, 0.5) and the lower one down to (2.5, 4.5),
            // and the centre of pixel (1, 2) lies exactly on that edge and on no other. The GPU applies
            // its top-left rule in GL's window frame, where y runs up, and there the shared edge is the
            // top edge of the face that sits above it in image space, so that face takes the sample.
            Vector2f left = new Vector2f(0.5f, 2.5f);
            Vector2f right = new Vector2f(4.5f, 2.5f);
            Vector2f upperApex = new Vector2f(2.5f, 0.5f);
            Vector2f lowerApex = new Vector2f(2.5f, 4.5f);

            assertThat("inside the upper face", owns(left, right, upperApex, 2.5f, 1.5f), equalTo(true));
            assertThat("inside the lower face", owns(left, lowerApex, right, 2.5f, 3.5f), equalTo(true));

            boolean upper = owns(left, right, upperApex, 1.5f, 2.5f);
            boolean lower = owns(left, lowerApex, right, 1.5f, 2.5f);
            assertThat("the face above owns the shared sample", upper, equalTo(true));
            assertThat("the face below does not", lower, equalTo(false));
            assertThat("exactly one face owns it", upper ^ lower, equalTo(true));
        }

        @Test
        @DisplayName("a pixel centre on a vertical edge two faces share goes to the face on its right alone")
        void aSampleOnASharedVerticalEdgeGoesToTheFaceOnItsRight() {
            // The two faces share the edge from (2.5, 0.5) to (2.5, 4.5), the left one reaching out to
            // (0.5, 2.5) and the right one to (4.5, 2.5), and the centre of pixel (2, 1) lies exactly
            // on that edge and on no other. Flipping y leaves left and right where they are, so the
            // edge is the left edge of the face on its right in either frame.
            Vector2f top = new Vector2f(2.5f, 0.5f);
            Vector2f bottom = new Vector2f(2.5f, 4.5f);
            Vector2f leftApex = new Vector2f(0.5f, 2.5f);
            Vector2f rightApex = new Vector2f(4.5f, 2.5f);

            assertThat("inside the left face", owns(top, leftApex, bottom, 1.5f, 2.5f), equalTo(true));
            assertThat("inside the right face", owns(top, bottom, rightApex, 3.5f, 2.5f), equalTo(true));

            boolean leftFace = owns(top, leftApex, bottom, 2.5f, 1.5f);
            boolean rightFace = owns(top, bottom, rightApex, 2.5f, 1.5f);
            assertThat("the face on the right owns the shared sample", rightFace, equalTo(true));
            assertThat("the face on the left does not", leftFace, equalTo(false));
            assertThat("exactly one face owns it", leftFace ^ rightFace, equalTo(true));
        }

        /**
         * Answers whether the triangle owns the sample, after holding that both of its windings give
         * the same answer, so a face's ownership never depends on which way round it is submitted.
         *
         * @param v0 the first vertex
         * @param v1 the second vertex
         * @param v2 the third vertex
         * @param px the sample's x coordinate
         * @param py the sample's y coordinate
         * @return whether the triangle owns the sample
         */
        private static boolean owns(@NotNull Vector2f v0, @NotNull Vector2f v1, @NotNull Vector2f v2, float px, float py) {
            boolean forward = RasterMath.isInsideTriangle(v0, v1, v2, px, py);
            boolean reversed = RasterMath.isInsideTriangle(v0, v2, v1, px, py);
            assertThat("both windings at (" + px + ", " + py + ")", reversed, equalTo(forward));
            return forward;
        }

        @Test
        @DisplayName("a degenerate triangle owns nothing, its own vertices included")
        void degenerateTriangleOwnsNothing() {
            Vector2f a = new Vector2f(0f, 0f);
            Vector2f b = new Vector2f(2f, 2f);
            Vector2f c = new Vector2f(4f, 4f);

            assertThat(RasterMath.EdgeCoefficients.of(a, b, c).denom(), equalTo(0L));
            for (Vector2f s : GRID) {
                assertThat("collinear at " + s, RasterMath.isInsideTriangle(a, b, c, s.x(), s.y()),
                    equalTo(false));
            }
            assertThat(RasterMath.isInsideTriangle(a, b, c, 2f, 2f), equalTo(false));
        }

        @Test
        @DisplayName("exactly one triangle of a quad owns each sample on the diagonal they share")
        void sharedDiagonalSamplesAreOwnedOnce() {
            Vector2f a = new Vector2f(0f, 0f);
            Vector2f b = new Vector2f(4f, 0f);
            Vector2f c = new Vector2f(4f, 4f);
            Vector2f d = new Vector2f(0f, 4f);

            for (int i = 1; i <= 3; i++) {
                float p = i;
                boolean upper = RasterMath.isInsideTriangle(a, b, c, p, p);
                boolean lower = RasterMath.isInsideTriangle(a, c, d, p, p);
                assertThat("diagonal at " + p, upper ^ lower, equalTo(true));
            }

            // The diagonal's two ends are corners of the quad as well, where its outline decides them,
            // and both are owned by neither half. The top-left corner lies on the quad's top edge in
            // image space, which the face above it owns, and the far corner on its right edge, which the
            // face to its right owns - one such edge is enough to exclude the sample.
            assertThat(RasterMath.isInsideTriangle(a, b, c, 0f, 0f), equalTo(false));
            assertThat(RasterMath.isInsideTriangle(a, c, d, 0f, 0f), equalTo(false));
            assertThat(RasterMath.isInsideTriangle(a, b, c, 4f, 4f), equalTo(false));
            assertThat(RasterMath.isInsideTriangle(a, c, d, 4f, 4f), equalTo(false));
        }

    }

    @Nested
    @DisplayName("EdgeCoefficients.of")
    class Coefficients {

        @Test
        @DisplayName("reversing the winding keeps one edge slot and trades the other two, flags included")
        void reversingTheWindingTradesTwoSlots() {
            for (Tri t : triangles()) {
                RasterMath.EdgeCoefficients forward =
                    RasterMath.EdgeCoefficients.of(t.v0(), t.v1(), t.v2());
                if (forward.denom() == 0L) continue;
                assertReversalSwapsSlots(t);
            }
        }

        @Test
        @DisplayName("the two windings of one triangle own exactly the same samples")
        void windingDoesNotChangeOwnership() {
            for (Tri t : triangles()) {
                for (Vector2f s : samplesFor(t)) {
                    boolean forward = RasterMath.isInsideTriangle(t.v0(), t.v1(), t.v2(), s.x(), s.y());
                    boolean reversed = RasterMath.isInsideTriangle(t.v0(), t.v2(), t.v1(), s.x(), s.y());
                    assertThat(t.name() + " at " + s, reversed, equalTo(forward));
                }
            }
        }

        @Test
        @DisplayName("a degenerate triangle keeps a raw winding-dependent classification nothing reads")
        void degenerateCoefficientsAreNotWindingNeutral() {
            // Observed, not documented: the sign normalization only fires on a negative determinant, so
            // a zero one leaves both windings unnormalized and their flags disagree. It is unreadable -
            // a zero-area triangle is rejected on denom before any flag is consulted - but the
            // reversal identity above genuinely does not extend here, so it is scoped rather than
            // stated for all triangles.
            Vector2f a = new Vector2f(1f, 1f);
            Vector2f b = new Vector2f(1f, 1f);
            Vector2f c = new Vector2f(5f, 3f);
            RasterMath.EdgeCoefficients forward = RasterMath.EdgeCoefficients.of(a, b, c);
            RasterMath.EdgeCoefficients reversed = RasterMath.EdgeCoefficients.of(a, c, b);

            assertThat(forward.denom(), equalTo(0L));
            assertThat(reversed.denom(), equalTo(0L));
            assertThat(forward.topLeft12(), equalTo(false));
            assertThat(reversed.topLeft12(), equalTo(true));
            assertThat(reversed.a12(), equalTo(-forward.a12()));
        }

        /**
         * Asserts the reversed winding of {@code t} yields the same slot-12 coefficients and flag, and
         * the other two slots exchanged.
         */
        private void assertReversalSwapsSlots(@NotNull Tri t) {
            RasterMath.EdgeCoefficients forward =
                RasterMath.EdgeCoefficients.of(t.v0(), t.v1(), t.v2());
            RasterMath.EdgeCoefficients reversed =
                RasterMath.EdgeCoefficients.of(t.v0(), t.v2(), t.v1());
            String where = t.name();

            assertThat(where, reversed.denom(), equalTo(forward.denom()));

            // Slot 12 holds the v1 / v2 edge either way - walked in the opposite direction, then
            // sign-normalized back - so it survives the reversal untouched.
            assertThat(where, reversed.a12(), equalTo(forward.a12()));
            assertThat(where, reversed.b12(), equalTo(forward.b12()));
            assertThat(where, reversed.c12(), equalTo(forward.c12()));
            assertThat(where, reversed.topLeft12(), equalTo(forward.topLeft12()));
            assertThat(where, reversed.stepX12(), equalTo(forward.stepX12()));
            assertThat(where, reversed.stepY12(), equalTo(forward.stepY12()));

            // The other two slots trade places, flags and steps included. That trade is exactly the
            // antisymmetry the flag negation rests on - drop the negation and these stop matching.
            assertThat(where, reversed.a20(), equalTo(forward.a01()));
            assertThat(where, reversed.b20(), equalTo(forward.b01()));
            assertThat(where, reversed.c20(), equalTo(forward.c01()));
            assertThat(where, reversed.topLeft20(), equalTo(forward.topLeft01()));
            assertThat(where, reversed.stepX20(), equalTo(forward.stepX01()));
            assertThat(where, reversed.stepY20(), equalTo(forward.stepY01()));

            assertThat(where, reversed.a01(), equalTo(forward.a20()));
            assertThat(where, reversed.b01(), equalTo(forward.b20()));
            assertThat(where, reversed.c01(), equalTo(forward.c20()));
            assertThat(where, reversed.topLeft01(), equalTo(forward.topLeft20()));
            assertThat(where, reversed.stepX01(), equalTo(forward.stepX20()));
            assertThat(where, reversed.stepY01(), equalTo(forward.stepY20()));
        }

    }

    @Nested
    @DisplayName("the incremental edge walk")
    class IncrementalWalk {

        /** Seed of every randomized span fixture, fixed so a failure reproduces */
        private static final long SPAN_SEED = 0x5EED_5A11L;

        /** Number of randomized triangles the span walk runs, each in both windings */
        private static final int SPAN_TRIANGLES = 3000;

        /** Names of the three edges in the order {@link RasterMath#spanFits} takes their first-sample values */
        private static final List<String> EDGE_SLOTS = List.of("12", "20", "01");

        /** Slot of {@code seen} counting rows narrowed from both sides and left non-empty */
        private static final int SEEN_NARROWED = 0;

        /** Slot of {@code seen} counting rows an edge flat along the row empties */
        private static final int SEEN_FLAT = 1;

        /** Slot of {@code seen} counting walked samples with an edge value of exactly zero */
        private static final int SEEN_TIE = 2;

        /** Slot of {@code seen} counting degenerate triangles whose box the span walk ran over */
        private static final int SEEN_DEGENERATE = 3;

        @Test
        @DisplayName("each step field is its coefficient times one whole pixel")
        void stepFieldsAreTheCoefficientTimesOnePixel() {
            long onePixel = RasterMath.quantizeSample(1f);
            for (Tri t : triangles()) {
                RasterMath.EdgeCoefficients ec = RasterMath.EdgeCoefficients.of(t.v0(), t.v1(), t.v2());
                String where = t.name();
                assertThat(where, ec.stepX12(), equalTo(ec.a12() * onePixel));
                assertThat(where, ec.stepY12(), equalTo(ec.b12() * onePixel));
                assertThat(where, ec.stepX20(), equalTo(ec.a20() * onePixel));
                assertThat(where, ec.stepY20(), equalTo(ec.b20() * onePixel));
                assertThat(where, ec.stepX01(), equalTo(ec.a01() * onePixel));
                assertThat(where, ec.stepY01(), equalTo(ec.b01() * onePixel));
            }
        }

        @Test
        @DisplayName("an edge function evaluated at the vertex it does not touch is the determinant")
        void eachEdgeFunctionAtItsOppositeVertexIsTheDeterminant() {
            for (Tri t : triangles()) {
                RasterMath.EdgeCoefficients ec = RasterMath.EdgeCoefficients.of(t.v0(), t.v1(), t.v2());
                String where = t.name();
                assertThat(where, edgeAt(ec.a12(), ec.b12(), ec.c12(), t.v0()), equalTo(ec.denom()));
                assertThat(where, edgeAt(ec.a20(), ec.b20(), ec.c20(), t.v1()), equalTo(ec.denom()));
                assertThat(where, edgeAt(ec.a01(), ec.b01(), ec.c01(), t.v2()), equalTo(ec.denom()));
            }
        }

        @Test
        @DisplayName("each row's span holds exactly the columns where all three edge values are non-negative")
        void spanHoldsExactlyTheNonNegativeColumns() {
            int[] seen = new int[4];
            for (Tri t : triangles()) {
                walkSpans(t, 16, 16, 0, 16, seen);
                walkSpans(reversed(t), 16, 16, 0, 16, seen);
            }

            Random random = new Random(SPAN_SEED);
            for (int i = 0; i < SPAN_TRIANGLES; i++) {
                int width = 8 + random.nextInt(41);
                int height = 8 + random.nextInt(41);
                Tri t = randomTriangle(random, width, height);
                // Half the boxes walk one band of the canvas, the way a tile does, so their first rows
                // are clipped off rather than at the box's own top.
                int tileStart = random.nextBoolean() ? 0 : random.nextInt(height);
                int tileEnd = random.nextBoolean() ? height : tileStart + 1 + random.nextInt(height - tileStart);
                walkSpans(t, width, height, tileStart, tileEnd, seen);
                walkSpans(reversed(t), width, height, tileStart, tileEnd, seen);
            }

            // The fixtures reach every case the span has to get right, so none of these holds vacuously.
            assertThat("rows narrowed from both sides", seen[SEEN_NARROWED], greaterThan(0));
            assertThat("rows a flat edge empties", seen[SEEN_FLAT], greaterThan(0));
            assertThat("samples on an edge inside a span", seen[SEEN_TIE], greaterThan(0));
            assertThat("degenerate triangles walked", seen[SEEN_DEGENERATE], greaterThan(0));
        }

        @Test
        @DisplayName("stepping a crossing down a row lands on the crossing a fresh division computes")
        void steppedCrossingMatchesAFreshDivision() {
            Random random = new Random(SPAN_SEED);
            long[] steps = { 0L, 1L, -1L, 256L, -256L, 65_536L, -65_536L, 3L * 65_536L };
            for (int i = 0; i < 200_000; i++) {
                long stepX = random.nextBoolean()
                    ? steps[random.nextInt(steps.length)]
                    : random.nextLong(-(1L << 30), 1L << 30);
                long divisor = Math.max(1L, Math.abs(stepX));
                // Remainders at and beside zero and the divisor are where a carry is decided, so a third
                // of the cases are built on them rather than left to chance.
                long edge = random.nextLong(-(1L << 50), 1L << 50);
                long stepY = random.nextLong(-(1L << 30), 1L << 30);
                if (random.nextInt(3) == 0) {
                    long remainder = switch (random.nextInt(3)) {
                        case 0 -> 0L;
                        case 1 -> divisor - 1L;
                        default -> random.nextLong(divisor);
                    };
                    edge = random.nextLong(-(1L << 20), 1L << 20) * divisor + remainder;
                    long carry = switch (random.nextInt(3)) {
                        case 0 -> 0L;
                        case 1 -> Math.floorMod(divisor - remainder, divisor);
                        default -> Math.floorMod(divisor - remainder - 1L, divisor);
                    };
                    stepY = random.nextLong(-(1L << 8), 1L << 8) * divisor + carry;
                }

                long next = edge + stepY;
                long stepped = RasterMath.nextCrossing(RasterMath.crossingOf(edge, stepX),
                    RasterMath.crossingOf(stepY, stepX), next, stepX);
                long divided = Math.floorDiv(next, divisor);
                if (stepped != divided)
                    fail("edge " + edge + " + " + stepY + " over step " + stepX + ": stepped to " + stepped
                        + " where a fresh division reads " + divided);
            }
        }

        @Test
        @DisplayName("the span walk refuses a box whose edge values could leave a long")
        void spanFitsRefusesABoxWhoseEdgeValuesCouldWrap() {
            RasterMath.EdgeCoefficients ec = RasterMath.EdgeCoefficients.of(
                new Vector2f(0f, 0f), new Vector2f(4f, 0f), new Vector2f(0f, 4f));
            assertThat(RasterMath.spanFits(ec, 0L, 0L, 0L, 4096, 4096), equalTo(true));

            // Each edge's value at the box's first sample is bounded on its own: a value at the bound, of
            // either sign, refuses the box from any one of the three slots while the other two hold zero.
            for (int slot = 0; slot < EDGE_SLOTS.size(); slot++) {
                String where = "first-sample value of edge " + EDGE_SLOTS.get(slot);
                assertThat(where, spanFitsWithFirstSample(ec, slot, (1L << 60) - 1L), equalTo(true));
                assertThat(where, spanFitsWithFirstSample(ec, slot, -(1L << 60) + 1L), equalTo(true));
                assertThat(where, spanFitsWithFirstSample(ec, slot, 1L << 60), equalTo(false));
                assertThat(where, spanFitsWithFirstSample(ec, slot, -(1L << 60)), equalTo(false));
                assertThat(where, spanFitsWithFirstSample(ec, slot, Long.MIN_VALUE), equalTo(false));
            }

            // A corner 2^30 px below the first, with the third halfway down, puts a column step of 2^46 on
            // the edge spanning the whole drop and 2^45 on the other two, so that one edge alone takes a
            // box 2^14 columns wide to the bound and one a column narrower stays under it.
            assertOneEdgeBoundsTheBox(
                new Vector2f(0f, 0f), new Vector2f(1f, 0x1p30f), new Vector2f(2f, 0x1p29f), true);

            // The same corners laid across put the long step on the rows instead, against the row count.
            assertOneEdgeBoundsTheBox(
                new Vector2f(0f, 0f), new Vector2f(0x1p30f, 1f), new Vector2f(0x1p29f, 2f), false);
        }

        @Test
        @DisplayName("a crossing past the int range clamps to the box before it narrows to an int")
        void spanBoundsClampBeforeTheyNarrow() {
            // Edge 12 falls along the row, edge 20 rises and edge 01 lies flat at a non-negative value, so
            // each bound is set by one crossing alone. A crossing a few columns either side of 2^32 narrows
            // to a small int - a column inside the box - unless it is clamped first.
            RasterMath.EdgeCoefficients ec = new RasterMath.EdgeCoefficients(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L,
                false, false, false,
                -256L, 0L, 256L, 0L, 0L, 0L);
            int last = 40;
            long above = (1L << 32) + 3L;
            long below = (1L << 32) - 5L;
            assertThat("a falling edge crossing past the box",
                RasterMath.spanEnd(ec, above, 0L, 0L, last), equalTo(last));
            assertThat("a falling edge crossing before the box",
                RasterMath.spanEnd(ec, -below, 0L, 0L, last), equalTo(-1));
            assertThat("a rising edge crossing past the box",
                RasterMath.spanStart(ec, 0L, -above, 0L, last), equalTo(last + 1));
            assertThat("a rising edge crossing before the box",
                RasterMath.spanStart(ec, 0L, below, 0L, last), equalTo(0));
        }

        /**
         * Drives the span walk over one triangle's box the way the rasterizer runs it - each edge's
         * crossing taken on the first row and stepped down a row at a time, each row's span read off
         * the three crossings, the row's edge values reseeded at the span's first column and stepped
         * across it - and holds every row against a fresh evaluation of every column of the box.
         *
         * @param t the triangle
         * @param width the canvas width
         * @param height the canvas height
         * @param tileStart the first row of the band walked
         * @param tileEnd the row past the last of the band walked
         * @param seen the counts of the cases the walk reached, added to
         */
        private void walkSpans(@NotNull Tri t, int width, int height, int tileStart, int tileEnd, int @NotNull [] seen) {
            RasterMath.EdgeCoefficients ec = RasterMath.EdgeCoefficients.of(t.v0(), t.v1(), t.v2());
            int[] bounds = new int[4];
            RasterMath.triangleBoundsInto(t.v0(), t.v1(), t.v2(), width, height, bounds);
            int pyStart = Math.max(bounds[1], tileStart);
            int pyEnd = Math.min(bounds[3], tileEnd - 1);
            int last = bounds[2] - bounds[0];
            if (pyStart > pyEnd || last < 0) return;
            if (ec.denom() == 0L) seen[SEEN_DEGENERATE]++;

            long sxStart = RasterMath.quantizeSample(bounds[0] + 0.5f);
            long syStart = RasterMath.quantizeSample(pyStart + 0.5f);
            long row12 = ec.a12() * sxStart + ec.b12() * syStart + ec.c12();
            long row20 = ec.a20() * sxStart + ec.b20() * syStart + ec.c20();
            long row01 = ec.a01() * sxStart + ec.b01() * syStart + ec.c01();
            String name = t.name() + " on " + width + "x" + height + " rows " + tileStart + ".." + tileEnd;
            if (!RasterMath.spanFits(ec, row12, row20, row01, last + 1, pyEnd - pyStart + 1))
                fail(name + ": a canvas-scale box is refused the span walk");

            long crossing12 = RasterMath.crossingOf(row12, ec.stepX12());
            long crossing20 = RasterMath.crossingOf(row20, ec.stepX20());
            long crossing01 = RasterMath.crossingOf(row01, ec.stepX01());
            long rowStep12 = RasterMath.crossingOf(ec.stepY12(), ec.stepX12());
            long rowStep20 = RasterMath.crossingOf(ec.stepY20(), ec.stepX20());
            long rowStep01 = RasterMath.crossingOf(ec.stepY01(), ec.stepX01());

            for (int py = pyStart; py <= pyEnd; py++,
                    row12 += ec.stepY12(), row20 += ec.stepY20(), row01 += ec.stepY01()) {
                int lo = RasterMath.spanStart(ec, crossing12, crossing20, crossing01, last);
                int hi = RasterMath.spanEnd(ec, crossing12, crossing20, crossing01, last);
                crossing12 = RasterMath.nextCrossing(crossing12, rowStep12, row12 + ec.stepY12(), ec.stepX12());
                crossing20 = RasterMath.nextCrossing(crossing20, rowStep20, row20 + ec.stepY20(), ec.stepX20());
                crossing01 = RasterMath.nextCrossing(crossing01, rowStep01, row01 + ec.stepY01(), ec.stepX01());

                if (lo > 0 && hi < last && lo <= hi) seen[SEEN_NARROWED]++;
                if ((ec.stepX12() == 0L && row12 < 0L) || (ec.stepX20() == 0L && row20 < 0L)
                    || (ec.stepX01() == 0L && row01 < 0L))
                    seen[SEEN_FLAT]++;

                for (int k = 0; k <= last; k++) {
                    int px = bounds[0] + k;
                    long f12 = edgeAt(ec.a12(), ec.b12(), ec.c12(), px, py);
                    long f20 = edgeAt(ec.a20(), ec.b20(), ec.c20(), px, py);
                    long f01 = edgeAt(ec.a01(), ec.b01(), ec.c01(), px, py);
                    boolean nonNegative = f12 >= 0L && f20 >= 0L && f01 >= 0L;
                    boolean inSpan = lo <= k && k <= hi;
                    boolean owned = RasterMath.isInsideTriangle(ec, px + 0.5f, py + 0.5f);
                    String where = name + " at (" + px + ", " + py + ")";
                    if (nonNegative != inSpan)
                        fail(where + ": the span [" + lo + ", " + hi + "] of [0, " + last + "] "
                            + (inSpan ? "holds" : "misses") + " a column whose edge values are "
                            + f12 + ", " + f20 + ", " + f01);
                    if (owned && !inSpan)
                        fail(where + ": an owned sample lies outside the span [" + lo + ", " + hi + "]");
                    if (owned && ec.denom() == 0L)
                        fail(where + ": a degenerate triangle owns a sample");
                }
                if (lo > hi) continue;

                long e12 = row12 + lo * ec.stepX12();
                long e20 = row20 + lo * ec.stepX20();
                long e01 = row01 + lo * ec.stepX01();
                for (int px = bounds[0] + lo; px <= bounds[0] + hi; px++,
                        e12 += ec.stepX12(), e20 += ec.stepX20(), e01 += ec.stepX01()) {
                    long f12 = edgeAt(ec.a12(), ec.b12(), ec.c12(), px, py);
                    long f20 = edgeAt(ec.a20(), ec.b20(), ec.c20(), px, py);
                    long f01 = edgeAt(ec.a01(), ec.b01(), ec.c01(), px, py);
                    String where = name + " at (" + px + ", " + py + ")";
                    if (e12 != f12 || e20 != f20 || e01 != f01)
                        fail(where + ": the walk reseeded at column " + lo + " reads " + e12 + ", " + e20
                            + ", " + e01 + " where a fresh evaluation reads " + f12 + ", " + f20 + ", " + f01);

                    boolean walked = ec.denom() != 0L
                        && e12 >= 0L && e20 >= 0L && e01 >= 0L
                        && (e12 != 0L || ec.topLeft12())
                        && (e20 != 0L || ec.topLeft20())
                        && (e01 != 0L || ec.topLeft01());
                    if (walked != RasterMath.isInsideTriangle(ec, px + 0.5f, py + 0.5f))
                        fail(where + ": the walked inside test reads " + walked);
                    if (e12 == 0L || e20 == 0L || e01 == 0L) seen[SEEN_TIE]++;
                }
            }
        }

        /**
         * Answers whether the span walk reads a box one sample across with {@code value} as one edge's
         * value at its first sample and zero as the other two.
         *
         * @param ec the triangle's edge coefficients
         * @param slot the index into {@link #EDGE_SLOTS} of the edge holding {@code value}
         * @param value the edge value at the box's first sample
         * @return what {@link RasterMath#spanFits} answers
         */
        private static boolean spanFitsWithFirstSample(@NotNull RasterMath.EdgeCoefficients ec, int slot, long value) {
            long row12 = slot == 0 ? value : 0L;
            long row20 = slot == 1 ? value : 0L;
            long row01 = slot == 2 ? value : 0L;
            return RasterMath.spanFits(ec, row12, row20, row01, 1, 1);
        }

        /**
         * Holds that the long edge of three corners bounds a box on its own, from each of the three edge
         * slots in turn.
         * <p>
         * The corners are rotated through the three vertex slots, which carries the edge from {@code a}
         * to {@code b} through slots {@code 01}, {@code 20} and {@code 12}. In every rotation that edge's
         * step is the only one at {@code 2^46} and the other two are at {@code 2^45}, so a box
         * {@code 2^14} long on the stepped axis is refused and one a sample shorter is not - whichever
         * slot the bound has to be read from.
         *
         * @param a the first corner
         * @param b the corner {@code 2^30} px from {@code a} along the stepped axis
         * @param c the corner halfway between them along that axis
         * @param columns whether the long step is the column step, bounded by the column count, rather
         *     than the row step, bounded by the row count
         */
        private void assertOneEdgeBoundsTheBox(
            @NotNull Vector2f a,
            @NotNull Vector2f b,
            @NotNull Vector2f c,
            boolean columns
        ) {
            Vector2f[] corners = { a, b, c };
            int under = (1 << 14) - 1;
            int at = 1 << 14;
            List<String> bounding = new ArrayList<>();
            for (int rotation = 0; rotation < corners.length; rotation++) {
                RasterMath.EdgeCoefficients ec = RasterMath.EdgeCoefficients.of(
                    corners[rotation], corners[(rotation + 1) % 3], corners[(rotation + 2) % 3]);
                long[] steps = columns
                    ? new long[] { ec.stepX12(), ec.stepX20(), ec.stepX01() }
                    : new long[] { ec.stepY12(), ec.stepY20(), ec.stepY01() };
                String where = (columns ? "column" : "row") + " steps, rotation " + rotation;

                List<String> atBound = new ArrayList<>();
                for (int slot = 0; slot < steps.length; slot++) {
                    String edge = where + ", edge " + EDGE_SLOTS.get(slot);
                    if (Math.abs(steps[slot]) == 1L << 46)
                        atBound.add(EDGE_SLOTS.get(slot));
                    else
                        assertThat(edge, Math.abs(steps[slot]), equalTo(1L << 45));
                }
                assertThat(where + ": the edges whose step is at the bound", atBound.size(), equalTo(1));
                bounding.add(atBound.getFirst());

                boolean shortFits = columns
                    ? RasterMath.spanFits(ec, 0L, 0L, 0L, under, 1)
                    : RasterMath.spanFits(ec, 0L, 0L, 0L, 1, under);
                boolean atFits = columns
                    ? RasterMath.spanFits(ec, 0L, 0L, 0L, at, 1)
                    : RasterMath.spanFits(ec, 0L, 0L, 0L, 1, at);
                assertThat(where + ", a box one sample short of the bound", shortFits, equalTo(true));
                assertThat(where + ", a box at the bound", atFits, equalTo(false));
            }
            assertThat("the edge bounding the box in each rotation", bounding, equalTo(List.of("01", "20", "12")));
        }

        /** Returns the triangle submitted the other way round. */
        private static @NotNull Tri reversed(@NotNull Tri t) {
            return new Tri(t.name() + ", reversed", t.v0(), t.v2(), t.v1());
        }

        /**
         * Draws a triangle around a canvas - free, on the sub-pixel grid, on the half-pixel grid where
         * its edges run through pixel centres, with a horizontal or nearly horizontal edge, a sliver,
         * a degenerate one, or one reaching well off every side of the canvas.
         *
         * @param random the fixture's source of randomness
         * @param width the canvas width
         * @param height the canvas height
         * @return the triangle
         */
        private static @NotNull Tri randomTriangle(@NotNull Random random, int width, int height) {
            Vector2f a = new Vector2f(around(random, width), around(random, height));
            Vector2f b = new Vector2f(around(random, width), around(random, height));
            Vector2f c = new Vector2f(around(random, width), around(random, height));
            return switch (random.nextInt(7)) {
                case 0 -> new Tri("free", a, b, c);
                case 1 -> new Tri("sub-pixel grid", snap(a, 256), snap(b, 256), snap(c, 256));
                case 2 -> new Tri("half-pixel grid", snap(a, 2), snap(b, 2), snap(c, 2));
                case 3 -> {
                    Vector2f first = snap(a, 256);
                    Vector2f second = new Vector2f(snap(b, 256).x(), first.y() + (random.nextInt(5) - 2) / 256f);
                    yield new Tri("near-horizontal edge", first, second, snap(c, 256));
                }
                case 4 -> {
                    Vector2f first = snap(a, 256);
                    Vector2f second = snap(b, 256);
                    Vector2f middle = new Vector2f(
                        (first.x() + second.x()) * 0.5f + (random.nextInt(7) - 3) / 256f,
                        (first.y() + second.y()) * 0.5f + (random.nextInt(7) - 3) / 256f);
                    yield new Tri("sliver", first, second, snap(middle, 256));
                }
                case 5 -> {
                    Vector2f first = snap(a, 256);
                    Vector2f second = snap(b, 256);
                    Vector2f beyond = new Vector2f(2f * second.x() - first.x(), 2f * second.y() - first.y());
                    yield random.nextBoolean()
                        ? new Tri("collinear, degenerate", first, second, beyond)
                        : new Tri("repeated vertex, degenerate", first, first, second);
                }
                default -> new Tri("off every side",
                    new Vector2f(-width + random.nextFloat() * width, -height + random.nextFloat() * 3f * height),
                    new Vector2f(2f * width - random.nextFloat() * width, -height + random.nextFloat() * 3f * height),
                    new Vector2f(-width + random.nextFloat() * 3f * width, 2f * height - random.nextFloat() * height));
            };
        }

        /** Draws a coordinate from a quarter of the canvas before it to a quarter past it. */
        private static float around(@NotNull Random random, int extent) {
            return (random.nextFloat() * 1.5f - 0.25f) * extent;
        }

        /** Rounds a point to the {@code 1 / grid} lattice. */
        private static @NotNull Vector2f snap(@NotNull Vector2f p, int grid) {
            return new Vector2f(Math.round(p.x() * grid) / (float) grid, Math.round(p.y() * grid) / (float) grid);
        }

        /** Evaluates one factored edge function at a screen-space point. */
        private long edgeAt(long a, long b, long c, @NotNull Vector2f s) {
            return a * RasterMath.quantizeSample(s.x()) + b * RasterMath.quantizeSample(s.y()) + c;
        }

        /** Evaluates one factored edge function at the centre of pixel {@code (px, py)}. */
        private long edgeAt(long a, long b, long c, int px, int py) {
            return a * RasterMath.quantizeSample(px + 0.5f) + b * RasterMath.quantizeSample(py + 0.5f) + c;
        }

    }

    @Nested
    @DisplayName("barycentric coordinates")
    class Barycentric {

        @Test
        @DisplayName("the denominator is twice the signed area and flips sign with the winding")
        void denominatorIsTwiceTheSignedArea() {
            Vector2f v0 = new Vector2f(0f, 0f);
            Vector2f v1 = new Vector2f(4f, 0f);
            Vector2f v2 = new Vector2f(0f, 4f);

            // Legs of 4 give an area of 8, so twice the signed area is 16.
            assertThat(RasterMath.barycentricDenominator(v0, v1, v2), equalTo(16f));
            assertThat(RasterMath.barycentricDenominator(v0, v2, v1), equalTo(-16f));

            // It is the same determinant the coefficient factory takes the absolute value of, read on
            // pixel coordinates rather than sub-pixel ones - so on a lattice-aligned triangle the two
            // differ by one whole pixel squared.
            long onePixel = RasterMath.quantizeSample(1f);
            assertThat(RasterMath.EdgeCoefficients.of(v0, v1, v2).denom(),
                equalTo(16L * onePixel * onePixel));
            assertThat(RasterMath.EdgeCoefficients.of(v0, v2, v1).denom(),
                equalTo(16L * onePixel * onePixel));
        }

        @Test
        @DisplayName("each vertex weights itself with one and its neighbours with zero")
        void verticesCarryTheUnitWeight() {
            Vector2f v0 = new Vector2f(0f, 0f);
            Vector2f v1 = new Vector2f(4f, 0f);
            Vector2f v2 = new Vector2f(0f, 4f);
            float[] out = new float[3];

            RasterMath.barycentricInto(v0, v1, v2, v0.x(), v0.y(), out);
            assertThat(out[0], equalTo(1f));
            assertThat(out[1], equalTo(0f));
            assertThat(out[2], equalTo(0f));

            RasterMath.barycentricInto(v0, v1, v2, v1.x(), v1.y(), out);
            assertThat(out[1], equalTo(1f));

            RasterMath.barycentricInto(v0, v1, v2, v2.x(), v2.y(), out);
            assertThat(out[2], equalTo(1f));
        }

        @Test
        @DisplayName("the weights reconstruct the query point, outside the triangle included")
        void weightsReconstructTheQueryPoint() {
            float[] out = new float[3];
            for (Tri t : triangles()) {
                // A near-degenerate triangle is skipped rather than given a looser tolerance: its
                // weights run into the hundreds over a grid this wide and the reconstruction is then
                // a cancellation of large terms, which measures float width rather than the formula.
                if (Math.abs(RasterMath.barycentricDenominator(t.v0(), t.v1(), t.v2())) < 1f) continue;
                for (Vector2f s : GRID) {
                    RasterMath.barycentricInto(t.v0(), t.v1(), t.v2(), s.x(), s.y(), out);
                    float x = out[0] * t.v0().x() + out[1] * t.v1().x() + out[2] * t.v2().x();
                    float y = out[0] * t.v0().y() + out[1] * t.v1().y() + out[2] * t.v2().y();
                    assertThat(t.name() + " at " + s, (double) x, closeTo(s.x(), 1e-3));
                    assertThat(t.name() + " at " + s, (double) y, closeTo(s.y(), 1e-3));
                }
            }
        }

        @Test
        @DisplayName("a degenerate triangle writes three zeros rather than weights summing to one")
        void degenerateTriangleWritesZeros() {
            // Observed: the zero-denominator arm writes zeros, so the sum is zero and not one. A
            // caller reading the weights without checking the denominator gets a silent zero rather
            // than a division by zero.
            Vector2f a = new Vector2f(0f, 0f);
            Vector2f b = new Vector2f(2f, 2f);
            Vector2f c = new Vector2f(4f, 4f);
            float[] out = {9f, 9f, 9f};

            RasterMath.barycentricInto(a, b, c, 1f, 1f, out);
            assertThat(out[0], equalTo(0f));
            assertThat(out[1], equalTo(0f));
            assertThat(out[2], equalTo(0f));
        }

        @Test
        @DisplayName("a point outside the triangle carries a negative weight rather than being clamped")
        void pointsOutsideCarryNegativeWeights() {
            float[] out = new float[3];
            RasterMath.barycentricInto(new Vector2f(0f, 0f), new Vector2f(4f, 0f),
                new Vector2f(0f, 4f), 4f, 4f, out);

            // (4, 4) is the reflection of the origin across the hypotenuse, so it weights v0 by -1 and
            // each of the other two by +1.
            assertThat(out[0], equalTo(-1f));
            assertThat(out[1], equalTo(1f));
            assertThat(out[2], equalTo(1f));
        }

    }

    @Nested
    @DisplayName("triangleBoundsInto")
    class Bounds {

        @Test
        @DisplayName("the minimum floors and the maximum ceils, so the box is inclusive on both ends")
        void minimumFloorsAndMaximumCeils() {
            int[] out = new int[4];
            RasterMath.triangleBoundsInto(new Vector2f(1.2f, 2.7f), new Vector2f(5.8f, 2.7f),
                new Vector2f(3f, 6.1f), 100, 100, out);

            assertThat(out[0], equalTo(1));
            assertThat(out[1], equalTo(2));
            assertThat(out[2], equalTo(6));
            assertThat(out[3], equalTo(7));
        }

        @Test
        @DisplayName("a maximum already on a pixel boundary is not inflated by the ceil")
        void integerMaximumIsNotInflated() {
            int[] out = new int[4];
            RasterMath.triangleBoundsInto(new Vector2f(1f, 2f), new Vector2f(5f, 2f),
                new Vector2f(3f, 6f), 100, 100, out);

            assertThat(out[0], equalTo(1));
            assertThat(out[1], equalTo(2));
            assertThat(out[2], equalTo(5));
            assertThat(out[3], equalTo(6));
        }

        @Test
        @DisplayName("the box clamps to the canvas at zero and at the last pixel, not at the count")
        void boxClampsToTheLastPixelRatherThanTheCount() {
            int[] out = new int[4];
            RasterMath.triangleBoundsInto(new Vector2f(-3f, -4f), new Vector2f(50f, 60f),
                new Vector2f(200f, 300f), 16, 16, out);

            assertThat(out[0], equalTo(0));
            assertThat(out[1], equalTo(0));
            assertThat(out[2], equalTo(15));
            assertThat(out[3], equalTo(15));
        }

        @Test
        @DisplayName("a triangle wholly past the canvas yields an inverted range rather than a clamped one")
        void offCanvasTriangleYieldsAnInvertedRange() {
            // Observed: only the low end is clamped, and only against zero. A triangle past the right
            // or bottom edge therefore comes back with min above max, which the caller's inclusive
            // pixel loop walks zero times - the emptiness is carried by the inversion, not by a flag.
            int[] out = new int[4];
            RasterMath.triangleBoundsInto(new Vector2f(20f, 20f), new Vector2f(22f, 20f),
                new Vector2f(21f, 22f), 16, 16, out);

            assertThat(out[0], equalTo(20));
            assertThat(out[1], equalTo(20));
            assertThat(out[2], equalTo(15));
            assertThat(out[3], equalTo(15));
        }

    }

    @Nested
    @DisplayName("texelOf / exactTexelOf")
    class Texel {

        /** The baby fox body's top-face triangle at 26.1, its three snapped corners in emission order */
        private final Vector2f[] fox = {
            new Vector2f(26244 / 256f, 5758 / 256f),
            new Vector2f(11762 / 256f, 12999 / 256f),
            new Vector2f(29140 / 256f, 21688 / 256f)
        };

        /** Pixels on that triangle's first edge, which is the face's first row of texels on a 32-tall sheet */
        private final int[][] foxEdge = {
            { 98, 24 }, { 96, 25 }, { 94, 26 }, { 90, 28 }, { 86, 30 }, { 80, 33 }, { 56, 45 }, { 46, 50 }
        };

        @Test
        @DisplayName("a coordinate strictly inside a texel reads that texel")
        void insideReadsTheTexel() {
            assertThat(RasterMath.texelOf(10.3f), equalTo(10));
            assertThat(RasterMath.texelOf(0.5f), equalTo(0));
            assertThat(RasterMath.texelOf(31.999f), equalTo(31));
        }

        @Test
        @DisplayName("a coordinate exactly on a texel boundary reads the texel below it")
        void boundaryReadsTheTexelBelow() {
            assertThat(RasterMath.texelOf(10f), equalTo(9));
            assertThat(RasterMath.texelOf(32f), equalTo(31));
            assertThat(RasterMath.texelOf(0f), equalTo(-1));
        }

        @Test
        @DisplayName("the exact form reads the quotient's texel, and the texel below on a boundary")
        void exactFormReadsTheQuotient() {
            // weights 2/8, 3/8, 3/8 of corners at 10, 12 and 14 texels: 98/8 = 12.25
            assertThat(RasterMath.exactTexelOf(2, 3, 3, 8, 10, 12, 14), equalTo(12));
            // weights 4/8, 4/8, 0 of corners at 10, 10 and 14 texels: exactly 10, a boundary
            assertThat(RasterMath.exactTexelOf(4, 4, 0, 8, 10, 10, 14), equalTo(9));
        }

        @Test
        @DisplayName("an edge through pixel centres reads the row outside the face, as the GPU does")
        void edgeThroughPixelCentresReadsTheRowOutside() {
            // The reference GPU's coordinate at every one of these pixels is 0.31249994, two float
            // steps under the face's first row at 10/32, so vanilla draws row 9 along the whole edge.
            RasterMath.EdgeCoefficients ec = RasterMath.EdgeCoefficients.of(fox[0], fox[1], fox[2]);
            for (int[] p : foxEdge) {
                long sx = RasterMath.quantizeSample(p[0] + 0.5f);
                long sy = RasterMath.quantizeSample(p[1] + 0.5f);
                long e12 = ec.a12() * sx + ec.b12() * sy + ec.c12();
                long e20 = ec.a20() * sx + ec.b20() * sy + ec.c20();
                long e01 = ec.a01() * sx + ec.b01() * sy + ec.c01();
                assertThat("on the edge at " + p[0] + "," + p[1], e12 == 0 || e20 == 0 || e01 == 0, equalTo(true));
                assertThat("row at " + p[0] + "," + p[1],
                    RasterMath.exactTexelOf(e12, e20, e01, ec.denom(), 10, 10, 16), equalTo(9));
            }
        }

        @Test
        @DisplayName("the float coordinate lands above that boundary where the exact one sits on it")
        void floatCoordinateLandsAboveTheBoundary() {
            // Why the fetch reads the exact form wherever the corners sit on whole texels: at this
            // pixel the float barycentric route evaluates v a step above 10/32 and would read row 10.
            float[] bary = new float[3];
            RasterMath.barycentricInto(fox[0], fox[1], fox[2], 56.5f, 45.5f, bary);
            float v = bary[0] * 0.3125f + bary[1] * 0.3125f + bary[2] * 0.5f;
            assertThat(RasterMath.texelOf(v * 32), equalTo(10));
        }

    }

    @Nested
    @DisplayName("the rasterizer's pixel loop")
    class PixelLoopTraces {

        /** The rasterizer's source, read as text because what is checked is where a call sits */
        private static final Path RASTERIZER_SOURCE =
            Path.of("src/main/java/lib/minecraft/renderer/engine/raster/Rasterizer.java");

        /** What opens the tile walk's declaration */
        private static final String TILE_WALK = "private static void rasterizeTile(";

        /** The statement reading the tracing flag once per tile */
        private static final String TRACING_FLAG = "boolean tracing = DebugChannel.tracingPixels();";

        /** The receiver every per-pixel trace call is made on */
        private static final String TRACE_OWNER = "DebugChannel.";

        /** What every per-pixel trace call opens with */
        private static final String PIXEL_TRACE = TRACE_OWNER + "pixel";

        /** The two spellings of a statement guarded by the flag, one body line and a block */
        private static final List<String> GUARDS = List.of("if (tracing)", "if (tracing) {");

        /** The trace calls the pixel loop makes, one per way a sample ends */
        private static final Set<String> PIXEL_TRACES =
            Set.of("pixelSkipFill", "pixelSkipDepth", "pixelSkipAlpha", "pixelWrite");

        @Test
        @DisplayName("every pixel trace call in the tile walk sits behind the tracing flag the tile reads once")
        void everyPixelTraceSitsBehindTheTracingFlag() throws IOException {
            String body = tileWalkBody(Files.readString(RASTERIZER_SOURCE));
            int flag = body.indexOf(TRACING_FLAG);
            assertThat("the tile walk reads the tracing flag", flag, greaterThanOrEqualTo(0));

            Set<String> traced = new TreeSet<>();
            List<String> unguarded = new ArrayList<>();
            for (int at = body.indexOf(PIXEL_TRACE); at >= 0; at = body.indexOf(PIXEL_TRACE, at + 1)) {
                String call = body.substring(at + TRACE_OWNER.length(), body.indexOf('(', at));
                traced.add(call);
                String before = body.substring(0, at).stripTrailing();
                String previousLine = before.substring(before.lastIndexOf('\n') + 1).strip();
                if (at < flag || GUARDS.stream().noneMatch(before::endsWith))
                    unguarded.add(call + " after '" + previousLine + "'");
            }
            assertThat("the pixel trace calls the tile walk makes", traced, is(new TreeSet<>(PIXEL_TRACES)));
            assertThat("pixel trace calls the tracing flag does not guard", unguarded, is(empty()));
        }

        /**
         * Returns the tile walk's body with every comment dropped, from its opening brace to the brace
         * that closes it.
         *
         * @param source the rasterizer's source
         * @return the method body
         */
        private static @NotNull String tileWalkBody(@NotNull String source) {
            String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            int declared = code.indexOf(TILE_WALK);
            assertThat("the rasterizer declares the tile walk", declared, greaterThanOrEqualTo(0));
            int open = code.indexOf('{', declared);
            int depth = 0;
            for (int i = open; i < code.length(); i++) {
                char ch = code.charAt(i);
                if (ch == '{') depth++;
                else if (ch == '}' && --depth == 0) return code.substring(open, i + 1);
            }
            return fail("the tile walk's body never closes");
        }

    }

}
