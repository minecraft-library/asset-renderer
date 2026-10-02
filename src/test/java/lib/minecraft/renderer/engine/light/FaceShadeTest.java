package lib.minecraft.renderer.engine.light;

import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.math.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * The shade a cardinal face takes, and the cardinal a normal resolves to.
 * <p>
 * Covers the pre-baked inventory {@link FaceShade#of} shade factors matching the vanilla
 * {@code Lighting.ITEMS_3D} bake (UP 1.0, DOWN 0.5, N/S 0.6, E/W 0.8 - E/W deliberately brighter
 * than N/S).
 * <p>
 * The {@link FaceShade#fromNormal} cases pin the closest-cardinal resolver: it depends only on
 * component-magnitude ordering (so an un-normalized normal resolves the same face), ties break in
 * {@code Y > Z > X} order - load-bearing for exact 45-degree faces such as sculk tendrils where
 * {@code |x| == |z|} must snap to the Z cardinal - and a degenerate zero normal falls back to
 * {@link Face#UP}.
 */
@DisplayName("FaceShade factors and cardinal resolution")
class FaceShadeTest {

    @Test
    @DisplayName("inventory shade factors match the vanilla ITEMS_3D bake")
    void lighting() {
        assertThat(FaceShade.of(Face.UP), equalTo(1.0f));
        assertThat(FaceShade.of(Face.DOWN), equalTo(0.5f));
        assertThat(FaceShade.of(Face.NORTH), equalTo(0.6f));
        assertThat(FaceShade.of(Face.SOUTH), equalTo(0.6f));
        assertThat(FaceShade.of(Face.WEST), equalTo(0.8f));
        assertThat(FaceShade.of(Face.EAST), equalTo(0.8f));
    }

    @Test
    @DisplayName("fromNormal resolves each cardinal direction")
    void fromNormalCardinals() {
        assertThat(FaceShade.fromNormal(new Vector3f(0, 1, 0)), is(Face.UP));
        assertThat(FaceShade.fromNormal(new Vector3f(0, -1, 0)), is(Face.DOWN));
        assertThat(FaceShade.fromNormal(new Vector3f(0, 0, 1)), is(Face.SOUTH));
        assertThat(FaceShade.fromNormal(new Vector3f(0, 0, -1)), is(Face.NORTH));
        assertThat(FaceShade.fromNormal(new Vector3f(1, 0, 0)), is(Face.EAST));
        assertThat(FaceShade.fromNormal(new Vector3f(-1, 0, 0)), is(Face.WEST));
    }

    @Test
    @DisplayName("fromNormal only cares about magnitude ordering, not normalisation")
    void fromNormalUnnormalised() {
        assertThat(FaceShade.fromNormal(new Vector3f(0, 5, 0)), is(Face.UP));
        assertThat(FaceShade.fromNormal(new Vector3f(-0.3f, 0, 0)), is(Face.WEST));
    }

    @Test
    @DisplayName("fromNormal ties resolve in Y > Z > X order (load-bearing for 45-degree faces)")
    void fromNormalTieBreak() {
        // |x| == |z|, no y: the Z axis wins over X.
        assertThat(FaceShade.fromNormal(new Vector3f(0.7f, 0, 0.7f)), is(Face.SOUTH));
        assertThat(FaceShade.fromNormal(new Vector3f(0.7f, 0, -0.7f)), is(Face.NORTH));
        // |y| == |z|: the Y axis wins over Z.
        assertThat(FaceShade.fromNormal(new Vector3f(0, 1, 1)), is(Face.UP));
        assertThat(FaceShade.fromNormal(new Vector3f(0, -1, 1)), is(Face.DOWN));
    }

    @Test
    @DisplayName("fromNormal falls back to UP for a degenerate zero normal")
    void fromNormalZero() {
        assertThat(FaceShade.fromNormal(Vector3f.ZERO), is(Face.UP));
    }

}
