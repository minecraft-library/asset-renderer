package lib.minecraft.renderer.engine.raster;

import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.tensor.Vector2f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Static helpers for the depth half of the rasterization math - the window-depth grid vanilla
 * resolves a fragment against, the depth test taken over it, and the unsnapped-plane re-read that
 * keeps the coverage snap from moving depth. (The 2D coverage math these pair with lives on
 * {@link RasterMath}.)
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
     * Re-reads each vertex's depth off the triangle's <b>unsnapped</b> plane at its <b>snapped</b>
     * screen position, so the coverage snap moves coverage without moving depth.
     *
     * <p>The rasterizer interpolates depth as {@code bary . z} with the barycentric weights taken from
     * the snapped vertices ({@link ModelEngine#snapToCoverageGrid}) while the {@code z} values belong to
     * the unsnapped ones. Depth is affine in screen space here (there is no perspective-correct depth
     * path - see the rasterizer's {@code depthVal}), so that pairing tilts every triangle's depth plane
     * by an amount computed from its own vertices. Two <em>genuinely coplanar</em> triangles therefore
     * stop agreeing: the worn-armour chestplate's torso box and its arm box overlap by two model units
     * at identical {@code z}, and the tilt put the arm a consistent 60 ULP in front, so it won a
     * contest vanilla resolves the other way - not as a tie the draw order breaks, but outright,
     * whichever order they were drawn in.
     *
     * <p>Substituting the plane's own value at the snapped vertex restores it: barycentric
     * interpolation of an affine function over the snapped triangle reproduces that function exactly,
     * so the depth sampled anywhere inside is the unsnapped plane's depth there, and coplanar
     * triangles agree again to within float rounding. The solve runs in {@code double} because its
     * whole purpose is to leave no systematic residue between two triangles of one plane. A triangle
     * with no unsnapped screen area has no plane to read, and keeps its vertex depths unchanged.
     *
     * @param r0 the first vertex's unsnapped screen position
     * @param r1 the second vertex's unsnapped screen position
     * @param r2 the third vertex's unsnapped screen position
     * @param s0 the first vertex's snapped screen position
     * @param s1 the second vertex's snapped screen position
     * @param s2 the third vertex's snapped screen position
     * @param z0 the first vertex's camera-space depth
     * @param z1 the second vertex's camera-space depth
     * @param z2 the third vertex's camera-space depth
     * @return the three raster depths, in vertex order
     */
    public static float @NotNull [] depthOnUnsnappedPlane(
        @NotNull Vector2f r0, @NotNull Vector2f r1, @NotNull Vector2f r2,
        @NotNull Vector2f s0, @NotNull Vector2f s1, @NotNull Vector2f s2,
        float z0, float z1, float z2
    ) {
        double dx1 = (double) r1.x() - r0.x();
        double dy1 = (double) r1.y() - r0.y();
        double dx2 = (double) r2.x() - r0.x();
        double dy2 = (double) r2.y() - r0.y();
        double denominator = dx1 * dy2 - dx2 * dy1;
        if (denominator == 0d) return new float[]{ z0, z1, z2 };

        double dz1 = (double) z1 - z0;
        double dz2 = (double) z2 - z0;
        double slopeX = (dz1 * dy2 - dz2 * dy1) / denominator;
        double slopeY = (dx1 * dz2 - dx2 * dz1) / denominator;
        return new float[]{
            planeDepth(z0, slopeX, slopeY, r0, s0),
            planeDepth(z0, slopeX, slopeY, r0, s1),
            planeDepth(z0, slopeX, slopeY, r0, s2)
        };
    }

    /**
     * Evaluates a depth plane, anchored at {@code origin} with the given screen-space slopes, at one
     * snapped screen position.
     *
     * @param anchorDepth the depth at {@code origin}
     * @param slopeX the plane's depth gradient along screen X
     * @param slopeY the plane's depth gradient along screen Y
     * @param origin the unsnapped screen position the plane is anchored at
     * @param at the snapped screen position to read the plane at
     * @return the plane's depth at {@code at}
     */
    private static float planeDepth(
        float anchorDepth, double slopeX, double slopeY, @NotNull Vector2f origin, @NotNull Vector2f at) {
        return (float) (anchorDepth
            + slopeX * ((double) at.x() - origin.x())
            + slopeY * ((double) at.y() - origin.y()));
    }

}
