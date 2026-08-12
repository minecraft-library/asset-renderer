package lib.minecraft.renderer.face;

import lib.minecraft.renderer.tensor.Vector3f;
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
 * return {@code null}), each face's lowercase {@link Face#direction} JSON key, the pre-baked
 * inventory {@link Face#lighting} shade factors matching the vanilla {@code Lighting.ITEMS_3D}
 * bake (UP 1.0, DOWN 0.5, N/S 0.6, E/W 0.8 - E/W deliberately brighter than N/S), the outward
 * unit {@link Face#normal}s, the {@link Face#axis} read off each face's normal, and the
 * {@link Face#opposite} the declaration order makes one bit of the ordinal.
 * <p>
 * The {@link Face#fromNormal} cases pin the closest-cardinal resolver: it depends only on
 * component-magnitude ordering (so an un-normalized normal resolves the same face), ties break in
 * {@code Y > Z > X} order - load-bearing for exact 45-degree faces such as sculk tendrils where
 * {@code |x| == |z|} must snap to the Z cardinal - and a degenerate zero normal falls back to
 * {@link Face#UP}.
 */
@DisplayName("Face lookup, normals, and cardinal resolution")
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
    @DisplayName("inventory shade factors match the vanilla ITEMS_3D bake")
    void lighting() {
        assertThat(Face.UP.lighting(), equalTo(1.0f));
        assertThat(Face.DOWN.lighting(), equalTo(0.5f));
        assertThat(Face.NORTH.lighting(), equalTo(0.6f));
        assertThat(Face.SOUTH.lighting(), equalTo(0.6f));
        assertThat(Face.WEST.lighting(), equalTo(0.8f));
        assertThat(Face.EAST.lighting(), equalTo(0.8f));
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

    @Test
    @DisplayName("fromNormal resolves each cardinal direction")
    void fromNormalCardinals() {
        assertThat(Face.fromNormal(new Vector3f(0, 1, 0)), is(Face.UP));
        assertThat(Face.fromNormal(new Vector3f(0, -1, 0)), is(Face.DOWN));
        assertThat(Face.fromNormal(new Vector3f(0, 0, 1)), is(Face.SOUTH));
        assertThat(Face.fromNormal(new Vector3f(0, 0, -1)), is(Face.NORTH));
        assertThat(Face.fromNormal(new Vector3f(1, 0, 0)), is(Face.EAST));
        assertThat(Face.fromNormal(new Vector3f(-1, 0, 0)), is(Face.WEST));
    }

    @Test
    @DisplayName("fromNormal only cares about magnitude ordering, not normalisation")
    void fromNormalUnnormalised() {
        assertThat(Face.fromNormal(new Vector3f(0, 5, 0)), is(Face.UP));
        assertThat(Face.fromNormal(new Vector3f(-0.3f, 0, 0)), is(Face.WEST));
    }

    @Test
    @DisplayName("fromNormal ties resolve in Y > Z > X order (load-bearing for 45-degree faces)")
    void fromNormalTieBreak() {
        // |x| == |z|, no y: the Z axis wins over X.
        assertThat(Face.fromNormal(new Vector3f(0.7f, 0, 0.7f)), is(Face.SOUTH));
        assertThat(Face.fromNormal(new Vector3f(0.7f, 0, -0.7f)), is(Face.NORTH));
        // |y| == |z|: the Y axis wins over Z.
        assertThat(Face.fromNormal(new Vector3f(0, 1, 1)), is(Face.UP));
        assertThat(Face.fromNormal(new Vector3f(0, -1, 1)), is(Face.DOWN));
    }

    @Test
    @DisplayName("fromNormal falls back to UP for a degenerate zero normal")
    void fromNormalZero() {
        assertThat(Face.fromNormal(Vector3f.ZERO), is(Face.UP));
    }

}
