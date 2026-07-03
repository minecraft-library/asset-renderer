package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.request.EulerRotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit coverage for {@link Facing} - the horizontal-mirror / vertical-flip view reflections and their
 * byte-identical {@link Facing#DEFAULT} no-op.
 */
@DisplayName("Facing view reflections")
class FacingTest {

    private static final float EPS = 1e-5f;

    @Test
    @DisplayName("DEFAULT is a no-op on pose and lens (returns the same instance)")
    void defaultIsNoOp() {
        EulerRotation pose = new EulerRotation(15f, 205f, 0f);
        assertSame(pose, Facing.DEFAULT.apply(pose));
        Lens oblique = Projection.CAVALIER.lens();
        assertSame(oblique, Facing.DEFAULT.apply(oblique));
    }

    @Test
    @DisplayName("MIRRORED reflects yaw (360 - yaw); FLIPPED negates pitch; roll untouched")
    void poseReflections() {
        EulerRotation base = new EulerRotation(15f, 205f, 30f);

        EulerRotation mirrored = Facing.MIRRORED.apply(base);
        assertEquals(15f, mirrored.pitch(), EPS);
        assertEquals(360f - 205f, mirrored.yaw(), EPS);
        assertEquals(30f, mirrored.roll(), EPS);

        EulerRotation flipped = Facing.FLIPPED.apply(base);
        assertEquals(-15f, flipped.pitch(), EPS);
        assertEquals(205f, flipped.yaw(), EPS);
        assertEquals(30f, flipped.roll(), EPS);

        EulerRotation both = Facing.MIRRORED_FLIPPED.apply(base);
        assertEquals(-15f, both.pitch(), EPS);
        assertEquals(360f - 205f, both.yaw(), EPS);
        assertEquals(30f, both.roll(), EPS);
    }

    @Test
    @DisplayName("MIRRORED flips an oblique lens's shear cosine; FLIPPED flips its sine; depth/scale/kind kept")
    void obliqueShearFlip() {
        Lens base = Projection.CAVALIER.lens();   // OBLIQUE, receding angle -45
        double baseCos = Math.cos(base.obliqueAngleRadians());
        double baseSin = Math.sin(base.obliqueAngleRadians());

        Lens mirrored = Facing.MIRRORED.apply(base);
        assertEquals(Lens.Kind.OBLIQUE, mirrored.kind());
        assertEquals(base.obliqueDepthFactor(), mirrored.obliqueDepthFactor(), EPS);
        assertEquals(base.projectionScale(), mirrored.projectionScale(), EPS);
        assertEquals(-baseCos, Math.cos(mirrored.obliqueAngleRadians()), EPS);   // cosine (x-shear) flipped
        assertEquals(baseSin, Math.sin(mirrored.obliqueAngleRadians()), EPS);    // sine (y-shear) kept

        Lens flipped = Facing.FLIPPED.apply(base);
        assertEquals(baseCos, Math.cos(flipped.obliqueAngleRadians()), EPS);     // cosine kept
        assertEquals(-baseSin, Math.sin(flipped.obliqueAngleRadians()), EPS);    // sine flipped
    }

    @Test
    @DisplayName("Non-oblique lenses pass through unchanged (same instance)")
    void nonObliqueUnchanged() {
        Lens ortho = Projection.ISOMETRIC.lens();       // ORTHOGRAPHIC
        Lens perspective = Projection.PORTRAIT.lens();   // PERSPECTIVE
        assertSame(ortho, Facing.MIRRORED.apply(ortho));
        assertSame(perspective, Facing.MIRRORED_FLIPPED.apply(perspective));
    }

    @Test
    @DisplayName("resolve(r) equals resolve(r, DEFAULT) - the default path stays byte-identical")
    void resolveDefaultMatchesPlain() {
        EulerRotation r = new EulerRotation(10f, 20f, 5f);
        Camera plain = Projection.PORTRAIT.resolve(r);
        Camera defaulted = Projection.PORTRAIT.resolve(r, Facing.DEFAULT);
        for (int col = 1; col <= 4; col++)
            for (int row = 1; row <= 4; row++)
                assertEquals(plain.pose().get(col, row), defaulted.pose().get(col, row), 0f,
                    "pose(" + col + "," + row + ")");
        assertEquals(plain.lens(), defaulted.lens());
        assertEquals(plain.lightingPose(), defaulted.lightingPose());
    }

    @Test
    @DisplayName("with* derive a new facing without mutating")
    void withers() {
        assertEquals(Facing.MIRRORED, Facing.DEFAULT.withMirrored(true));
        assertEquals(Facing.FLIPPED, Facing.DEFAULT.withFlipped(true));
        assertEquals(Facing.MIRRORED_FLIPPED, Facing.MIRRORED.withFlipped(true));
    }

    @Test
    @DisplayName("MIRRORED / FLIPPED are involutions on a pose (apply twice = identity)")
    void poseInvolution() {
        EulerRotation base = new EulerRotation(15f, 205f, 30f);
        assertEquals(base, Facing.MIRRORED.apply(Facing.MIRRORED.apply(base)));
        assertEquals(base, Facing.FLIPPED.apply(Facing.FLIPPED.apply(base)));
        assertEquals(base, Facing.MIRRORED_FLIPPED.apply(Facing.MIRRORED_FLIPPED.apply(base)));
    }

    @Test
    @DisplayName("resolve(NONE, facing) equals fromPose(facing.apply(base), facing.apply(lens)) - the entity render/bounds contract")
    void resolveAppliesFacingToBasePoseAndLens() {
        Projection p = Projection.CAVALIER;   // oblique, so the lens shear reflection is exercised too
        for (Facing f : new Facing[]{Facing.DEFAULT, Facing.MIRRORED, Facing.FLIPPED, Facing.MIRRORED_FLIPPED}) {
            Camera resolved = p.resolve(EulerRotation.NONE, f);
            Camera expected = Camera.fromPose(f.apply(p.basePose()), f.apply(p.lens()));
            for (int col = 1; col <= 4; col++)
                for (int row = 1; row <= 4; row++)
                    assertEquals(expected.pose().get(col, row), resolved.pose().get(col, row), EPS,
                        f + " pose(" + col + "," + row + ")");
            assertEquals(expected.lens(), resolved.lens(), f + " lens");
        }
    }

}
