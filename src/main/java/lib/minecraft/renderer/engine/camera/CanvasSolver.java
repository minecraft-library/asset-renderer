package lib.minecraft.renderer.engine.camera;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.engine.geometry.Box;
import org.jetbrains.annotations.NotNull;

/**
 * Sizes a canvas from a silhouette something else measured - bounds and a camera in, a
 * {@link CanvasFit} out.
 * <p>
 * Every input is a value: the projected bounds, the camera they were projected through, and the
 * caller's own sizing numbers. Nothing here measures anything, resolves anything, or names the
 * subject being fitted, which is what lets one solver size a canvas for any of them.
 */
@UtilityClass
public class CanvasSolver {

    /**
     * Selects whether canvas sizing + silhouette centring measure one subject alone (base
     * model + non-{@code skipBounds} overlays unioned together) or also union across every
     * group member. Both modes share the same per-subject + overlay primitives, so the only
     * difference is whether the group loop runs.
     */
    public enum BoundsScope {

        /** The subject and its own overlays. */
        ENTITY_UNION,

        /** The subject, its overlays, and every member of its canvas group. */
        GROUP_UNION

    }

    /**
     * Sizes the output canvas + model-units-to-NDC scale from a pre-measured projected silhouette.
     * The caller measures the (alpha-tight, optionally group-unioned) {@code screenBounds} through the
     * render orientation ({@code Rasterizer.orient}); this is the pure sizing math the orthographic
     * entity path feeds into a {@link FitRequest#nativeScale(float, Box) NATIVE_SCALE} fit.
     *
     * <p>Native extent ({@code fixedOutputSize} false): take the tight screen-space extent in
     * entity-pixel-units, size the canvas to {@code (extent * pixelsPerBlock / 16)} pixels per axis plus
     * {@code 2 * padding} on each axis, then uniformly shrink so the longer side stays at or below
     * {@code maxCanvasSize}.
     *
     * <p>Fixed output ({@code fixedOutputSize} true): canvas is fixed at {@code canvasSize x
     * canvasSize}. Available silhouette area is {@code canvasSize - 2 * padding} on the longer axis;
     * the subject is scaled to fit.
     *
     * <p>The returned {@link CanvasFit#ndcScale} is the inverse of the rasterizer's own projection
     * ({@code screen_px = ndc * min(canvasW, canvasH) * projectionScale}), so applying it as the fit's
     * model-units-to-NDC scale produces the desired pixels-per-block ratio at rasterization.
     *
     * @param screenBounds the pre-measured projected silhouette bounds
     * @param camera the camera the silhouette was projected through, which supplies the projection scale
     * @param fixedOutputSize whether the canvas is pinned to {@code canvasSize} rather than sized from
     *     the silhouette
     * @param canvasSize the fixed canvas edge, read only under {@code fixedOutputSize}
     * @param padding the margin held clear on each side, in canvas pixels
     * @param pixelsPerBlock canvas pixels per vanilla block, read only when the canvas is sized from
     *     the silhouette
     * @param maxCanvasSize the cap the longer sized-from-silhouette axis is shrunk to
     * @return the canvas dimensions + model-units-to-NDC scale
     */
    public static @NotNull CanvasFit solve(
        @NotNull Box screenBounds,
        @NotNull Camera camera,
        boolean fixedOutputSize,
        int canvasSize,
        int padding,
        float pixelsPerBlock,
        int maxCanvasSize
    ) {
        float extentX = Math.max(0f, screenBounds.maxX() - screenBounds.minX());
        float extentY = Math.max(0f, screenBounds.maxY() - screenBounds.minY());
        int pad = Math.max(0, padding);
        float projectionScale = camera.lens().projectionScale();

        if (fixedOutputSize) {
            int size = Math.max(1, canvasSize);
            int avail = Math.max(1, size - 2 * pad);
            float extent = Math.max(Math.max(extentX, extentY), 1e-6f);
            float pxPerEntityUnit = avail / extent;
            float ndcScale = pxPerEntityUnit / (size * projectionScale);
            return new CanvasFit(size, size, ndcScale);
        }

        int cap = Math.max(1, maxCanvasSize);
        float pxPerEntityUnit = pixelsPerBlock / 16f;
        int rawW = Math.max(1, (int) Math.ceil(extentX * pxPerEntityUnit)) + 2 * pad;
        int rawH = Math.max(1, (int) Math.ceil(extentY * pxPerEntityUnit)) + 2 * pad;
        int longest = Math.max(rawW, rawH);
        float shrink = longest > cap ? (float) cap / longest : 1f;
        int canvasW = evenWidth(Math.max(1, (int) Math.ceil(rawW * shrink)));
        int canvasH = Math.max(1, (int) Math.ceil(rawH * shrink));
        float effectivePxPerEntityUnit = pxPerEntityUnit * shrink;
        int minDim = Math.min(canvasW, canvasH);
        float ndcScale = effectivePxPerEntityUnit / (minDim * projectionScale);
        return new CanvasFit(canvasW, canvasH, ndcScale);
    }

    /**
     * Rounds a canvas width up to the next even value, so the fit's anchor lands on a pixel boundary
     * rather than on a pixel centre.
     *
     * <p>A left-right symmetric subject's front vertical corner <b>is</b> the anchor the fit centres,
     * so it projects to exactly {@code width / 2} whatever the subject's extent - the content width
     * cancels out entirely. At an odd width that is a half-integer, which is exactly a pixel centre
     * and therefore exactly a sample point, and the screen edge where the corner's two faces meet
     * passes through it; the sample is then decided by which face the fill rule and the texel fetch
     * hand it to rather than by coverage. At an even width it is an integer - a pixel boundary no
     * sample can land on - so the tie never forms.
     *
     * <p>Only the width has such an axis, since a subject is symmetric left to right and not top to
     * bottom, so the height is left alone. The vanilla-reference-harness rounds its own canvas width
     * the same way in {@code EntitySweep}, so the reference and this render stay in lockstep; the
     * bump cannot move an already-even canvas, and it cannot exceed the caller's cap, which is itself
     * even by default.
     *
     * @param width the canvas width in pixels
     * @return the width, rounded up to the next even value
     */
    private static int evenWidth(int width) {
        return width + (width & 1);
    }

}
