package lib.minecraft.renderer.tensor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link Vector3f}'s arithmetic and geometry: component-wise add/subtract/multiply/divide/
 * negate, index-based {@link Vector3f#get(int)} access (with the out-of-range guard), length and
 * length-squared, dot and right-hand-rule cross products, {@link Vector3f#normalize()} including its
 * degenerate-vector guard below {@link Vector3f#NORMALIZE_EPSILON}, and {@link Vector3f#lerp} endpoint
 * and midpoint interpolation. These exercise the scalar contract; the transparent Vector-API
 * dispatch on {@code transform}/{@code transformNormal} is out of scope here.
 */
@DisplayName("Vector3f arithmetic + geometry")
class Vector3fTest {

    @Test
    @DisplayName("component-wise arithmetic")
    void arithmetic() {
        Vector3f a = new Vector3f(1, 2, 3);
        Vector3f b = new Vector3f(4, 5, 6);
        assertThat(a.add(b), equalTo(new Vector3f(5, 7, 9)));
        assertThat(b.subtract(a), equalTo(new Vector3f(3, 3, 3)));
        assertThat(a.multiply(2), equalTo(new Vector3f(2, 4, 6)));
        assertThat(new Vector3f(2, 4, 6).divide(2), equalTo(new Vector3f(1, 2, 3)));
        assertThat(a.negate(), equalTo(new Vector3f(-1, -2, -3)));
    }

    @Test
    @DisplayName("get() indexes components and rejects out-of-range axes")
    void get() {
        Vector3f v = new Vector3f(7, 8, 9);
        assertThat(v.get(0), equalTo(7f));
        assertThat(v.get(1), equalTo(8f));
        assertThat(v.get(2), equalTo(9f));
        assertThrows(IndexOutOfBoundsException.class, () -> v.get(3));
    }

    @Test
    @DisplayName("length and lengthSquared")
    void length() {
        Vector3f v = new Vector3f(3, 4, 0);
        assertThat(v.lengthSquared(), equalTo(25f));
        assertThat(v.length(), equalTo(5f));
    }

    @Test
    @DisplayName("dot product")
    void dot() {
        assertThat(new Vector3f(1, 2, 3).dot(new Vector3f(4, 5, 6)), equalTo(32f));
        assertThat(new Vector3f(1, 0, 0).dot(new Vector3f(0, 1, 0)), equalTo(0f));
    }

    @Test
    @DisplayName("cross product follows the right-hand rule")
    void cross() {
        assertThat(new Vector3f(1, 0, 0).cross(new Vector3f(0, 1, 0)), equalTo(new Vector3f(0, 0, 1)));
        assertThat(new Vector3f(0, 1, 0).cross(new Vector3f(0, 0, 1)), equalTo(new Vector3f(1, 0, 0)));
    }

    @Test
    @DisplayName("normalize scales to unit length and guards the degenerate case")
    void normalize() {
        assertThat(new Vector3f(0, 3, 0).normalize(), equalTo(new Vector3f(0, 1, 0)));
        assertThat(Vector3f.ZERO.normalize(), is(Vector3f.ZERO));
        // Length 1e-9 < NORMALIZE_EPSILON (1e-8): treated as degenerate, so normalize() short-
        // circuits to ZERO rather than dividing by a near-zero magnitude.
        assertThat(new Vector3f(1e-9f, 0, 0).normalize(), is(Vector3f.ZERO));
    }

    @Test
    @DisplayName("lerp interpolates between endpoints")
    void lerp() {
        Vector3f a = new Vector3f(0, 0, 0);
        Vector3f b = new Vector3f(10, -4, 2);
        assertThat(a.lerp(b, 0f), equalTo(a));
        assertThat(a.lerp(b, 1f), equalTo(b));
        assertThat(a.lerp(b, 0.5f), equalTo(new Vector3f(5, -2, 1)));
    }

}
