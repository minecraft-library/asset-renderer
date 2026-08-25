package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.FaceTextures;
import lib.minecraft.renderer.option.FluidOptions;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the fan diagonal every quad this renderer emits is split on, and of the UV slot each
 * vertex is paired with.
 * <p>
 * A quad becomes two triangles {@code (0, 1, 2)} and {@code (0, 2, 3)}, so the pair meets along the
 * corner-0 / corner-2 diagonal. Two coplanar quads split on opposite diagonals fight along the seam
 * they share - an equal depth passes ({@code GL_LEQUAL}, last-drawn-wins) and the winner falls to
 * emission order - so the diagonal decides a tied contest rather than being a matter of taste.
 * {@link GeometryKit#addQuad} is the one place it is chosen, and these tests hold the four
 * subjects that reach it to the same answer.
 * <p>
 * The invariant is checkable on a finished triangle list without knowing which face is which: for each
 * emitted pair, the shared diagonal is the first triangle's {@code position0} and {@code position2},
 * which the second re-uses as its {@code position0} and {@code position1}. That form also covers the
 * one quad which does <b>not</b> route through the emitter - a fluid's sloped top, whose four corners
 * are not coplanar - so its agreement is pinned rather than assumed.
 */
class BlockGeometryKitQuadFanTest {

    /** ARGB tint threaded through each build so a triangle can be attributed to its call. */
    private static final int TINT_ARGB = 0xFFAABBCC;

    @Test
    @DisplayName("addQuad splits on the corner-0 / corner-2 diagonal and pairs uv[i] with corners[i]")
    void addQuad_splitsOnCornerZeroToTwoDiagonal() {
        Vector3f[] corners = {
            new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 0f),
            new Vector3f(1f, 0f, 0f), new Vector3f(1f, 1f, 0f)
        };
        Vector2f[] uv = {
            new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), new Vector2f(1f, 0f)
        };
        ConcurrentList<VisibleTriangle> out = Concurrent.newList();

        GeometryKit.addQuad(out, corners, uv, texture1x1(), TINT_ARGB,
            Face.SOUTH.normal(), 0.75f, SurfaceTraits.OPAQUE_BODY, "quad:south");

        assertThat(out.size(), equalTo(2));
        VisibleTriangle first = out.getFirst();
        VisibleTriangle second = out.getLast();

        assertThat(first.position0(), sameInstance(corners[0]));
        assertThat(first.position1(), sameInstance(corners[1]));
        assertThat(first.position2(), sameInstance(corners[2]));
        assertThat(second.position0(), sameInstance(corners[0]));
        assertThat(second.position1(), sameInstance(corners[2]));
        assertThat(second.position2(), sameInstance(corners[3]));

        assertThat(first.uv0(), sameInstance(uv[0]));
        assertThat(first.uv1(), sameInstance(uv[1]));
        assertThat(first.uv2(), sameInstance(uv[2]));
        assertThat(second.uv0(), sameInstance(uv[0]));
        assertThat(second.uv1(), sameInstance(uv[2]));
        assertThat(second.uv2(), sameInstance(uv[3]));
    }

    @Test
    @DisplayName("both triangles of a quad share one normal, one shade and one traits instance")
    void addQuad_sharesPerQuadValuesAcrossBothTriangles() {
        Vector3f normal = Face.UP.normal();
        SurfaceTraits traits = SurfaceTraits.WORN_SHELL;
        ConcurrentList<VisibleTriangle> out = Concurrent.newList();

        GeometryKit.addQuad(out, unitQuadCorners(), unitQuadUv(), texture1x1(), TINT_ARGB,
            normal, 0.5f, traits, null);

        for (VisibleTriangle triangle : out) {
            assertThat(triangle.normal(), sameInstance(normal));
            assertThat(triangle.traits(), sameInstance(traits));
            assertThat(triangle.shading(), equalTo(0.5f));
        }
    }

    @Test
    @DisplayName("every box quad takes that diagonal")
    void buildBox_takesTheSharedDiagonal() {
        ConcurrentList<VisibleTriangle> triangles = GeometryKit.buildBox(
            new Box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f),
            FaceTextures.uniform(texture1x1()),
            TINT_ARGB);

        assertThat(triangles.size(), equalTo(12));
        assertFansOnSharedDiagonal(triangles);
    }

    @Test
    @DisplayName("every shield quad takes that diagonal")
    void buildShield3D_takesTheSharedDiagonal() {
        ConcurrentList<VisibleTriangle> triangles = ShieldKit.buildShield3D(texture1x1());

        // Two vanilla-authored cubes (plate and handle), six faces each, two triangles per face.
        assertThat(triangles.size(), equalTo(24));
        assertFansOnSharedDiagonal(triangles);
    }

    @Test
    @DisplayName("every fluid quad takes that diagonal, the non-planar sloped top included")
    void buildFluidCube_takesTheSharedDiagonalOnTheSlopedTopToo() {
        // Four distinct corner heights make the top genuinely non-planar, so its two triangles carry
        // different normals and different shades - the one quad with no shared per-quad value to hand
        // the emitter. A present flow angle also drives the sides' UV-rotation branch.
        ConcurrentList<VisibleTriangle> triangles = FluidGeometryKit.buildFluidCube(
            new FluidOptions.CornerHeights(0.9f, 0.7f, 0.5f, 0.3f),
            texture1x1(), texture1x1(), Optional.of(1.25f), TINT_ARGB);

        // Sloped top, flat bottom, four sides.
        assertThat(triangles.size(), equalTo(12));
        assertFansOnSharedDiagonal(triangles);
    }

    /**
     * Asserts every consecutive triangle pair in {@code triangles} is one quad split on the corner-0 /
     * corner-2 diagonal - the second triangle re-using the first's {@code position0} as its own and the
     * first's {@code position2} as its {@code position1}, with the UV slots paired the same way.
     */
    private static void assertFansOnSharedDiagonal(@NotNull ConcurrentList<VisibleTriangle> triangles) {
        assertThat(triangles.size() % 2, equalTo(0));
        for (int i = 0; i < triangles.size(); i += 2) {
            VisibleTriangle first = triangles.get(i);
            VisibleTriangle second = triangles.get(i + 1);
            assertThat(second.position0(), sameInstance(first.position0()));
            assertThat(second.position1(), sameInstance(first.position2()));
            assertThat(second.uv0(), sameInstance(first.uv0()));
            assertThat(second.uv1(), sameInstance(first.uv2()));
        }
    }

    private static @NotNull Vector3f @NotNull [] unitQuadCorners() {
        return new Vector3f[] {
            new Vector3f(0f, 0f, 1f), new Vector3f(0f, 0f, 0f),
            new Vector3f(1f, 0f, 0f), new Vector3f(1f, 0f, 1f)
        };
    }

    private static @NotNull Vector2f @NotNull [] unitQuadUv() {
        return new Vector2f[] {
            new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), new Vector2f(1f, 0f)
        };
    }

    private static @NotNull PixelBuffer texture1x1() {
        return PixelBuffer.of(new int[] {0xFFFFFFFF}, 1, 1);
    }

}
