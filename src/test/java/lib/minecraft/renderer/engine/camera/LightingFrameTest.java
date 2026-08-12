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
 * Coverage of {@link LightingFrame}'s factory contract and of the
 * {@linkplain LightingFrame.Mirror#HORIZONTAL horizontal mirror}'s effect through
 * {@link Shading#relightForItems3d}: the light swaps its left and right lit sides while leaving the
 * top / bottom unchanged, decoupled from the geometry.
 */
@DisplayName("LightingFrame - decoupled inventory relight frame")
class LightingFrameTest {

    private static final float EPS = 1.0e-5f;
    private static final PixelBuffer WHITE_1X1 = PixelBuffer.of(new int[]{0xFFFFFFFF}, 1, 1);

    @Test
    @DisplayName("factories carry the right mirror and preserve the rotation")
    void factoriesCarryMirrorAndRotation() {
        EulerRotation r = new EulerRotation(30f, 225f, 0f);
        assertEquals(LightingFrame.Mirror.NONE, LightingFrame.tracking(r).mirror());
        assertEquals(r, LightingFrame.tracking(r).rotation());

        LightingFrame mirrored = LightingFrame.tracking(r).mirroredHorizontally();
        assertEquals(LightingFrame.Mirror.HORIZONTAL, mirrored.mirror());
        assertEquals(r, mirrored.rotation(), "mirroredHorizontally keeps the rotation");

        // Deriving a mirror leaves the original frame untouched (records are immutable).
        LightingFrame base = LightingFrame.tracking(r);
        base.mirroredHorizontally();
        assertEquals(LightingFrame.Mirror.NONE, base.mirror());
    }

    @Test
    @DisplayName("rotated() composes for a tracking frame and no-ops for a fixed frame")
    void rotatedComposesOnlyWhenTracking() {
        EulerRotation base = new EulerRotation(30f, 225f, 0f);
        EulerRotation delta = new EulerRotation(10f, -20f, 5f);

        // Tracking follows the view: the delta composes into the angle.
        LightingFrame tracked = LightingFrame.tracking(base).rotated(delta);
        assertEquals(new EulerRotation(40f, 205f, 5f), tracked.rotation(), "tracking composes the rotation");

        // Fixed stays put: the delta is ignored.
        LightingFrame fixed = LightingFrame.fixed(base).rotated(delta);
        assertEquals(base, fixed.rotation(), "fixed ignores the rotation");

        // A zero delta is a no-op for either policy.
        assertEquals(base, LightingFrame.tracking(base).rotated(EulerRotation.NONE).rotation(), "tracking zero-delta");
        assertEquals(base, LightingFrame.fixed(base).rotated(EulerRotation.NONE).rotation(), "fixed zero-delta");

        // The mirror survives a rotation.
        assertEquals(LightingFrame.Mirror.HORIZONTAL,
            LightingFrame.tracking(base).mirroredHorizontally().rotated(delta).mirror(), "mirror preserved");
    }

    @Test
    @DisplayName("HORIZONTAL mirror swaps the left/right lit sides, top unchanged")
    void mirrorSwapsLeftRight() {
        LightingFrame none = LightingFrame.tracking(EulerRotation.NONE);
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
     * Builds a triangle whose winding (geometric) normal equals {@code n} - positions {@code (0, e1, e2)}
     * for a pair {@code e1}, {@code e2} each perpendicular to {@code n}, chosen so their cross product
     * {@code e1 x e2} is {@code n}. The relight then shades against {@code n} directly (its authored
     * normal, which agrees with the geometry).
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
