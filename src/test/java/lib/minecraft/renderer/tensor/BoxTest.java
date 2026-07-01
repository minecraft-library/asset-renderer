package lib.minecraft.renderer.tensor;

import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies {@link Box}'s AABB factories and extent accessor: the direct min/max copies
 * ({@link Box#of(Vector3f, Vector3f)}, {@link Box#of(float[], float[])}), the tight-enclosing
 * point-cloud factories ({@link Box#of(float[][])}, {@link Box#of(Vector3f[])}) that reduce a set
 * of points to their bounding box, and {@link Box#maxExtent()} returning the largest per-axis span.
 */
@DisplayName("Box factories + extent")
class BoxTest {

    @Test
    @DisplayName("of(Vector3f, Vector3f) copies the min/max corners")
    void ofVectors() {
        Box box = Box.of(new Vector3f(1, 2, 3), new Vector3f(4, 5, 6));
        assertThat(box, equalTo(new Box(1, 2, 3, 4, 5, 6)));
    }

    @Test
    @DisplayName("of(float[], float[]) reads [x,y,z] arrays")
    void ofArrays() {
        Box box = Box.of(new float[]{ 0, -1, 2 }, new float[]{ 3, 4, 5 });
        assertThat(box, equalTo(new Box(0, -1, 2, 3, 4, 5)));
    }

    @Test
    @DisplayName("of(points) computes the tight enclosing AABB from [x,y,z] rows")
    void ofPointRows() {
        Box box = Box.of(new float[][]{
            { 1, 1, 1 }, { -2, 3, 0 }, { 5, -4, 2 }
        });
        assertThat(box, equalTo(new Box(-2, -4, 0, 5, 3, 2)));
    }

    @Test
    @DisplayName("of(Vector3f[]) computes the tight enclosing AABB")
    void ofVectorPoints() {
        Box box = Box.of(new Vector3f[]{
            new Vector3f(1, 1, 1), new Vector3f(-2, 3, 0), new Vector3f(5, -4, 2)
        });
        assertThat(box, equalTo(new Box(-2, -4, 0, 5, 3, 2)));
    }

    @Test
    @DisplayName("maxExtent returns the largest per-axis span")
    void maxExtent() {
        assertThat(new Box(0, 0, 0, 2, 5, 1).maxExtent(), equalTo(5f));
        assertThat(new Box(-1, -1, -1, 1, 1, 1).maxExtent(), equalTo(2f));
    }

}
