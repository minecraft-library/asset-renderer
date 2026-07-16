package lib.minecraft.renderer.engine.camera;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins {@link LightingFrame}'s factory contract and the {@linkplain LightingFrame.Mirror#HORIZONTAL
 * horizontal mirror}'s effect through {@link Shading#relightForItems3d}: the light swaps its left and
 * right lit sides while leaving the top / bottom unchanged, decoupled from the geometry.
 */
@DisplayName("LightingFrame - decoupled inventory relight frame")
class LightingFrameTest {

    private static final float EPS = 1.0e-5f;
    private static final PixelBuffer WHITE_1X1 = PixelBuffer.of(new int[]{0xFFFFFFFF}, 1, 1);

    @Test
    @DisplayName("factories carry the right mirror and preserve the rotation")
    void factories() {
        EulerRotation r = new EulerRotation(30f, 225f, 0f);
        assertEquals(LightingFrame.Mirror.NONE, LightingFrame.trackingPose(r).mirror());
        assertEquals(LightingFrame.Mirror.NONE, LightingFrame.fixed(r).mirror());
        assertEquals(r, LightingFrame.trackingPose(r).rotation());

        LightingFrame mirrored = LightingFrame.trackingPose(r).mirroredHorizontally();
        assertEquals(LightingFrame.Mirror.HORIZONTAL, mirrored.mirror());
        assertEquals(r, mirrored.rotation(), "mirroredHorizontally keeps the rotation");

        // Deriving a mirror leaves the original frame untouched (records are immutable).
        LightingFrame base = LightingFrame.trackingPose(r);
        base.mirroredHorizontally();
        assertEquals(LightingFrame.Mirror.NONE, base.mirror());
    }

    @Test
    @DisplayName("HORIZONTAL mirror swaps the left/right lit sides, top unchanged")
    void mirrorSwapsLeftRight() {
        LightingFrame none = LightingFrame.trackingPose(EulerRotation.NONE);
        LightingFrame mirrored = none.mirroredHorizontally();
        Vector3f rightX = new Vector3f(1f, 0f, 0f);
        Vector3f leftX = new Vector3f(-1f, 0f, 0f);
        Vector3f topY = new Vector3f(0f, 1f, 0f);

        // A right-facing normal lit through the mirror shades exactly as a left-facing normal lit
        // straight, and vice versa - the light's left and right sides swap.
        assertEquals(shade(none, leftX), shade(mirrored, rightX), EPS, "right(mirror) == left(plain)");
        assertEquals(shade(none, rightX), shade(mirrored, leftX), EPS, "left(mirror) == right(plain)");

        // The top is on the mirror axis (X = 0), so it is unaffected.
        assertEquals(shade(none, topY), shade(mirrored, topY), EPS, "top unchanged by the mirror");

        // Guard against a vacuous swap: the ITEMS_3D lights are not left/right symmetric, so mirroring
        // genuinely changes a horizontal face's shade.
        assertNotEquals(shade(none, rightX), shade(mirrored, rightX), 1.0e-3f, "mirror changes the lit side");
    }

    /** Relights a single triangle facing {@code normal} with {@code frame} and returns its recomputed shade. */
    private static float shade(LightingFrame frame, Vector3f normal) {
        return Shading.relightForItems3d(Concurrent.newList(triangleFacing(normal)), frame, false)
            .getFirst().shading();
    }

    /**
     * A triangle whose winding (geometric) normal equals {@code n} - positions {@code (0, e1, e2)} for a
     * perpendicular pair {@code e1 ⊥ n}, {@code e2 = n × e1}, so {@code e1 × e2 = n}. The relight then
     * shades against {@code n} directly (its authored normal, which agrees with the geometry).
     */
    private static VisibleTriangle triangleFacing(Vector3f n) {
        Vector3f seed = Math.abs(n.y()) < 0.9f ? new Vector3f(0f, 1f, 0f) : new Vector3f(1f, 0f, 0f);
        Vector3f e1 = n.cross(seed).normalize();
        Vector3f e2 = n.cross(e1);
        Vector3f origin = new Vector3f(0f, 0f, 0f);
        Vector2f uv = new Vector2f(0f, 0f);
        return new VisibleTriangle(origin, e1, e2, uv, uv, uv, WHITE_1X1, 0xFFFFFFFF, n, 1.0f, SurfaceTraits.OPAQUE_BODY);
    }

}
