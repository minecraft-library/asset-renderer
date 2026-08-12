package lib.minecraft.renderer.engine.raster;

import lib.minecraft.renderer.tensor.Vector2f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

/**
 * Coverage of the fixed-point coverage math the rasterizer runs per pixel - the sub-pixel sample
 * quantization, the Pineda edge-function factoring, the top-left fill rule, and the barycentric and
 * bounding-box helpers that ride alongside them.
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
 * <li><b>The incremental walk lands on the same value.</b> The {@code stepX} / {@code stepY} fields are
 * the coefficients times the sub-pixel precision, and stepping an edge value across a bounding box has
 * to reach what a fresh evaluation computes - that walk is what the engine's inner loop runs.</li>
 * </ul>
 * <p>
 * <b>Observed rather than documented.</b> The free-function overload of
 * {@code isInsideTriangle} builds the coefficients and delegates to the precomputed overload, so the
 * "bit-identical inside-test outcome" its javadoc credits to the algebra is true by construction
 * instead. The overload comparison is kept as a fence against a future hand-written second
 * implementation, and the factoring claim is pinned against an independently unfactored evaluation.
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
        boolean classified = sy == ey ? ex > sx : ey < sy;
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
        @DisplayName("a top or left edge owns its samples; a right edge and the far vertices do not")
        void topAndLeftEdgesOwnTheirSamplesAndTheHypotenuseDoesNot() {
            // Screen space with y running down: v0 is the top-left corner, v0 -> v1 the top edge,
            // v2 -> v0 the left edge, and v1 -> v2 the downward-running hypotenuse.
            Vector2f v0 = new Vector2f(0f, 0f);
            Vector2f v1 = new Vector2f(4f, 0f);
            Vector2f v2 = new Vector2f(0f, 4f);

            assertThat(RasterMath.isInsideTriangle(v0, v1, v2, 1f, 1f), equalTo(true));
            assertThat(RasterMath.isInsideTriangle(v0, v1, v2, 5f, 5f), equalTo(false));
            assertThat(RasterMath.isInsideTriangle(v0, v1, v2, 2f, 0f), equalTo(true));
            assertThat(RasterMath.isInsideTriangle(v0, v1, v2, 0f, 2f), equalTo(true));
            assertThat(RasterMath.isInsideTriangle(v0, v1, v2, 2f, 2f), equalTo(false));

            // The corner where the two owned edges meet is owned; the other two are not, each sitting
            // on the hypotenuse the rule excludes.
            assertThat(RasterMath.isInsideTriangle(v0, v1, v2, 0f, 0f), equalTo(true));
            assertThat(RasterMath.isInsideTriangle(v0, v1, v2, 4f, 0f), equalTo(false));
            assertThat(RasterMath.isInsideTriangle(v0, v1, v2, 0f, 4f), equalTo(false));
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

            for (int i = 0; i <= 3; i++) {
                float p = i;
                boolean upper = RasterMath.isInsideTriangle(a, b, c, p, p);
                boolean lower = RasterMath.isInsideTriangle(a, c, d, p, p);
                assertThat("diagonal at " + p, upper ^ lower, equalTo(true));
            }

            // The quad's far corner is owned by neither half: each meets it across at least one right
            // or bottom edge, and one such edge is enough to exclude the sample.
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
        @DisplayName("walking the bbox by the step fields lands on a fresh evaluation at every pixel")
        void steppingReachesWhatAFreshEvaluationComputes() {
            int[] bounds = new int[4];
            for (Tri t : triangles()) {
                RasterMath.EdgeCoefficients ec = RasterMath.EdgeCoefficients.of(t.v0(), t.v1(), t.v2());
                RasterMath.triangleBoundsInto(t.v0(), t.v1(), t.v2(), 16, 16, bounds);
                long sxStart = RasterMath.quantizeSample(bounds[0] + 0.5f);
                long syStart = RasterMath.quantizeSample(bounds[1] + 0.5f);
                long row12 = ec.a12() * sxStart + ec.b12() * syStart + ec.c12();
                long row20 = ec.a20() * sxStart + ec.b20() * syStart + ec.c20();
                long row01 = ec.a01() * sxStart + ec.b01() * syStart + ec.c01();

                for (int py = bounds[1]; py <= bounds[3]; py++,
                        row12 += ec.stepY12(), row20 += ec.stepY20(), row01 += ec.stepY01()) {
                    long e12 = row12;
                    long e20 = row20;
                    long e01 = row01;
                    for (int px = bounds[0]; px <= bounds[2]; px++,
                            e12 += ec.stepX12(), e20 += ec.stepX20(), e01 += ec.stepX01()) {
                        Vector2f centre = new Vector2f(px + 0.5f, py + 0.5f);
                        String where = t.name() + " at " + centre;
                        assertThat(where, e12, equalTo(edgeAt(ec.a12(), ec.b12(), ec.c12(), centre)));
                        assertThat(where, e20, equalTo(edgeAt(ec.a20(), ec.b20(), ec.c20(), centre)));
                        assertThat(where, e01, equalTo(edgeAt(ec.a01(), ec.b01(), ec.c01(), centre)));

                        boolean walked = ec.denom() != 0L
                            && e12 >= 0L && e20 >= 0L && e01 >= 0L
                            && (e12 != 0L || ec.topLeft12())
                            && (e20 != 0L || ec.topLeft20())
                            && (e01 != 0L || ec.topLeft01());
                        assertThat(where, walked,
                            equalTo(RasterMath.isInsideTriangle(ec, centre.x(), centre.y())));
                    }
                }
            }
        }

        /** Evaluates one factored edge function at a screen-space point. */
        private long edgeAt(long a, long b, long c, @NotNull Vector2f s) {
            return a * RasterMath.quantizeSample(s.x()) + b * RasterMath.quantizeSample(s.y()) + c;
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

}
