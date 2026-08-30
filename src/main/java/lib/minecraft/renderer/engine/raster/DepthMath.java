package lib.minecraft.renderer.engine.raster;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.tensor.Vector2f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Static helpers for the depth half of the rasterization math - the window-depth grid vanilla
 * resolves a fragment against, the depth test taken over it, and the {@link Plane} a triangle's
 * fragment depths are read off. (The 2D coverage math these pair with lives on {@link RasterMath}.)
 */
@Parity(claim = "depth-immunity-limit")
@Parity(claim = "depth-immunity")
@UtilityClass
public class DepthMath {

    /**
     * Half-extent of the orthographic depth range the raster depth is resolved against - the span
     * vanilla's own picture-in-picture entity renderer projects through, {@code zNear = -1000} to
     * {@code zFar = +1000}.
     *
     * <p>It is what decides how far apart two surfaces have to be before vanilla can tell them apart.
     * Vanilla's viewport transform lands every window depth beside {@code 0.5}, where a {@code float}
     * step is {@code 2^-24}, so forming that value rounds away everything finer - about six bits, lost
     * purely to centring the range on {@code 0.5}. Two surfaces closer than one step are the same depth
     * to vanilla, and their order falls to whichever drew last. Comparing raw camera-space depth instead
     * resolves roughly two orders of magnitude finer than that, which sounds harmless and is not: it
     * decides coplanar contests outright that vanilla leaves to draw order.
     *
     * <p>Overridable via {@code -Dasset.depth.range=N} for empirical sweeps; {@code N <= 0} compares raw
     * camera-space depth. Swept over {@code 125} to {@code 4000}, fleet parity is a broad shallow basin
     * whose floor sits on this value.
     *
     * <p>Every {@code FrameRenderer} in the vanilla-reference-harness declares its own
     * {@code DEPTH_RANGE} holding this same value, which is what puts both sides of a comparison on
     * one window-depth grid. Changing it means editing this constant and each of theirs in one
     * commit. The harness's depth-quantum probe is deliberately outside that set - it drives two
     * ranges at once and refreshes no reference.
     */
    private static final float VANILLA_DEPTH_RANGE = Float.parseFloat(System.getProperty("asset.depth.range", "1000"));

    /**
     * The camera-space-to-window-depth scale a canvas rendered at the given projection scale resolves
     * its depth against.
     *
     * @param scale the projection scale (smaller canvas dimension times the lens projection scale)
     * @return the scale to round an interpolated depth through, or {@code 0} when the range is disabled
     */
    public static float gridFor(float scale) {
        return VANILLA_DEPTH_RANGE > 0f ? scale / (2f * VANILLA_DEPTH_RANGE) : 0f;
    }

    /**
     * Rounds one camera-space depth onto the window-depth grid vanilla resolves, and returns it in
     * camera-space units so every consumer downstream is unchanged.
     *
     * <p>The round trip is the point: {@code 0.5f - depth * k} is vanilla's own window depth, and forming
     * it in {@code float} rounds away everything finer than an ULP at {@code 0.5}. Undoing the map
     * afterwards is exact - subtracting two nearby values loses nothing - so what comes back is the
     * original depth carrying vanilla's resolution rather than this renderer's.
     *
     * @param depth the camera-space depth
     * @param k the camera-space-to-window-depth scale, {@code scale / (2 * range)}
     * @return the depth rounded onto vanilla's grid, in camera-space units
     */
    public static float onVanillaDepthGrid(float depth, float k) {
        float window = 0.5f - depth * k;
        return (0.5f - window) / k;
    }

    /**
     * Tests whether a fragment fails the depth test against the existing depth-buffer value at its
     * pixel - vanilla's {@code GL_LEQUAL}, with no tolerance either way.
     *
     * <p>A fragment passes when it is at or in front of the stored depth, so a coplanar later fragment
     * overwrites the earlier one and the last drawn wins, reproducing the way a model's overlay element
     * paints over an identically-shaped base (grass_block's tinted {@code #overlay} over its dirt
     * {@code #side}).
     *
     * <p>Emissive fragments once carried a slack here, so an overlay drawn at - or a shade behind - the
     * base it sits on still blended rather than being culled. Nothing needs it: the overlays it was
     * written for are pushed clear of their base by the loader's depth clearance, and a pass vanilla
     * registers write-disabled skips the depth <em>write</em>, so a stack of its fragments accumulates
     * against the opaque depth behind whatever order they arrive in. Swept across four orders of
     * magnitude the slack decided two rows in the corpus, both charged auras, and both are closer
     * without it.
     *
     * @param depthVal the candidate fragment's depth
     * @param existingDepth the depth currently stored at this pixel
     * @return {@code true} if the fragment should be rejected
     */
    public static boolean depthFails(float depthVal, float existingDepth) {
        return depthVal < existingDepth;
    }

    /**
     * The plane a triangle's fragment depths are read off - the one its three <b>unsnapped</b> screen
     * positions and camera-space depths define, anchored at the first of them.
     *
     * <p>Depth is affine in screen space here (there is no perspective-correct depth path - see the
     * rasterizer's {@code depthVal}), so three vertices fix it exactly, and reading it off the
     * unsnapped positions is what keeps the {@code 1/400} coverage snap
     * ({@link ModelEngine#snapToCoverageGrid}) from moving depth. A plane solved from the snapped
     * vertices instead is tilted by how far each of them moved, which is a different tilt per
     * triangle: two <em>genuinely coplanar</em> triangles - the worn-armour chestplate's torso box
     * and its arm box overlap by two model units at identical {@code z} - stop agreeing, and one
     * wins a contest outright that vanilla leaves to draw order.
     *
     * <p>Solved and carried in {@code double} for the same reason, and read straight at the sample
     * point. Two triangles of one plane agree only as closely as the arithmetic between the solve
     * and the depth test leaves them, and the window grid
     * ({@link DepthMath#onVanillaDepthGrid}) resolves finer than a {@code float} chain of that
     * length: rounding the solve into three vertex depths, re-solving those into gradients and
     * evaluating from a vertex anchor is five roundings the two triangles take differently, and it
     * separates by one to two quanta a pair the grid would otherwise tie. The vertices themselves
     * are {@code float}, so what is left is the model's own rounding rather than the raster's.
     *
     * @param anchorX the first vertex's unsnapped screen X
     * @param anchorY the first vertex's unsnapped screen Y
     * @param anchorDepth the camera-space depth at the anchor
     * @param slopeX the plane's depth gradient along screen X
     * @param slopeY the plane's depth gradient along screen Y
     */
    public record Plane(double anchorX, double anchorY, double anchorDepth, double slopeX, double slopeY) {

        /**
         * Solves the plane through three unsnapped screen positions and their camera-space depths.
         *
         * @param r0 the first vertex's unsnapped screen position
         * @param r1 the second vertex's unsnapped screen position
         * @param r2 the third vertex's unsnapped screen position
         * @param z0 the first vertex's camera-space depth
         * @param z1 the second vertex's camera-space depth
         * @param z2 the third vertex's camera-space depth
         * @return the plane, or {@code null} when the triangle has no unsnapped screen area to read one off
         */
        public static @Nullable Plane of(
            @NotNull Vector2f r0, @NotNull Vector2f r1, @NotNull Vector2f r2, float z0, float z1, float z2) {
            double dx1 = (double) r1.x() - r0.x();
            double dy1 = (double) r1.y() - r0.y();
            double dx2 = (double) r2.x() - r0.x();
            double dy2 = (double) r2.y() - r0.y();
            double denominator = dx1 * dy2 - dx2 * dy1;
            if (denominator == 0d) return null;

            double dz1 = (double) z1 - z0;
            double dz2 = (double) z2 - z0;
            return new Plane(r0.x(), r0.y(), z0,
                (dz1 * dy2 - dz2 * dy1) / denominator,
                (dx1 * dz2 - dx2 * dz1) / denominator);
        }

        /**
         * Reads the plane's depth at one screen position.
         *
         * @param x the screen X to read at
         * @param y the screen Y to read at
         * @return the camera-space depth there
         */
        public float depthAt(float x, float y) {
            return (float) (this.anchorDepth + this.slopeX * (x - this.anchorX) + this.slopeY * (y - this.anchorY));
        }

    }

}
