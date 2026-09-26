package lib.minecraft.renderer.engine.geometry;

import lib.minecraft.renderer.math.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * The six cardinal faces and their metadata.
 * <p>
 * Covers name lookup ({@link Face#fromName} is case-insensitive and null-safe, unknown names
 * return {@code null}), each face's lowercase {@link Face#direction} JSON key, the outward unit
 * {@link Face#normal}s, the {@link Face#axis} read off each face's normal, and the
 * {@link Face#opposite} the declaration order makes one bit of the ordinal.
 */
@DisplayName("Face lookup, direction names and normals")
class FaceTest {

    @Test
    @DisplayName("fromName is case-insensitive and null-safe")
    void fromName() {
        assertThat(Face.fromName("down"), is(Face.DOWN));
        assertThat(Face.fromName("UP"), is(Face.UP));
        assertThat(Face.fromName("North"), is(Face.NORTH));
        assertThat(Face.fromName(null), is(nullValue()));
        assertThat(Face.fromName("sideways"), is(nullValue()));
    }

    @Test
    @DisplayName("each face exposes its lowercase direction name")
    void directionNames() {
        assertThat(Face.DOWN.direction(), equalTo("down"));
        assertThat(Face.EAST.direction(), equalTo("east"));
    }

    @Test
    @DisplayName("outward normals point along the expected axis")
    void normals() {
        assertThat(Face.UP.normal(), equalTo(new Vector3f(0, 1, 0)));
        assertThat(Face.DOWN.normal(), equalTo(new Vector3f(0, -1, 0)));
        assertThat(Face.NORTH.normal(), equalTo(new Vector3f(0, 0, -1)));
        assertThat(Face.SOUTH.normal(), equalTo(new Vector3f(0, 0, 1)));
        assertThat(Face.EAST.normal(), equalTo(new Vector3f(1, 0, 0)));
        assertThat(Face.WEST.normal(), equalTo(new Vector3f(-1, 0, 0)));
    }

    @Test
    @DisplayName("each face reads its own axis off its normal's one non-zero component")
    void axes() {
        assertThat(Face.DOWN.axis(), is(1));
        assertThat(Face.UP.axis(), is(1));
        assertThat(Face.NORTH.axis(), is(2));
        assertThat(Face.SOUTH.axis(), is(2));
        assertThat(Face.WEST.axis(), is(0));
        assertThat(Face.EAST.axis(), is(0));
    }

    @Test
    @DisplayName("opposite pairs each face with the other face on its own axis")
    void opposites() {
        assertThat(Face.DOWN.opposite(), is(Face.UP));
        assertThat(Face.UP.opposite(), is(Face.DOWN));
        assertThat(Face.NORTH.opposite(), is(Face.SOUTH));
        assertThat(Face.SOUTH.opposite(), is(Face.NORTH));
        assertThat(Face.WEST.opposite(), is(Face.EAST));
        assertThat(Face.EAST.opposite(), is(Face.WEST));
    }

}
