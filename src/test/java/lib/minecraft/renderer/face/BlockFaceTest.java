package lib.minecraft.renderer.face;

import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies {@link BlockFace} - the six cardinal block-model faces and their metadata.
 * <p>
 * Covers name lookup ({@link BlockFace#fromName} is case-insensitive and null-safe, unknown names
 * return {@code null}), each face's lowercase {@link BlockFace#direction} JSON key, the pre-baked
 * inventory {@link BlockFace#lighting} shade factors matching the vanilla {@code Lighting.ITEMS_3D}
 * bake (UP 1.0, DOWN 0.5, N/S 0.6, E/W 0.8 - E/W deliberately brighter than N/S), and the outward
 * unit {@link BlockFace#normal}s.
 * <p>
 * The {@link BlockFace#fromNormal} cases pin the closest-cardinal resolver: it depends only on
 * component-magnitude ordering (so an un-normalized normal resolves the same face), ties break in
 * {@code Y > Z > X} order - load-bearing for exact 45-degree faces such as sculk tendrils where
 * {@code |x| == |z|} must snap to the Z cardinal - and a degenerate zero normal falls back to
 * {@link BlockFace#UP}.
 */
@DisplayName("BlockFace lookup, normals, and cardinal resolution")
class BlockFaceTest {

    @Test
    @DisplayName("fromName is case-insensitive and null-safe")
    void fromName() {
        assertThat(BlockFace.fromName("down"), is(BlockFace.DOWN));
        assertThat(BlockFace.fromName("UP"), is(BlockFace.UP));
        assertThat(BlockFace.fromName("North"), is(BlockFace.NORTH));
        assertThat(BlockFace.fromName(null), is(nullValue()));
        assertThat(BlockFace.fromName("sideways"), is(nullValue()));
    }

    @Test
    @DisplayName("each face exposes its lowercase direction name")
    void directionNames() {
        assertThat(BlockFace.DOWN.direction(), equalTo("down"));
        assertThat(BlockFace.EAST.direction(), equalTo("east"));
    }

    @Test
    @DisplayName("inventory shade factors match the vanilla ITEMS_3D bake")
    void lighting() {
        assertThat(BlockFace.UP.lighting(), equalTo(1.0f));
        assertThat(BlockFace.DOWN.lighting(), equalTo(0.5f));
        assertThat(BlockFace.NORTH.lighting(), equalTo(0.6f));
        assertThat(BlockFace.SOUTH.lighting(), equalTo(0.6f));
        assertThat(BlockFace.WEST.lighting(), equalTo(0.8f));
        assertThat(BlockFace.EAST.lighting(), equalTo(0.8f));
    }

    @Test
    @DisplayName("outward normals point along the expected axis")
    void normals() {
        assertThat(BlockFace.UP.normal(), equalTo(new Vector3f(0, 1, 0)));
        assertThat(BlockFace.DOWN.normal(), equalTo(new Vector3f(0, -1, 0)));
        assertThat(BlockFace.NORTH.normal(), equalTo(new Vector3f(0, 0, -1)));
        assertThat(BlockFace.SOUTH.normal(), equalTo(new Vector3f(0, 0, 1)));
        assertThat(BlockFace.EAST.normal(), equalTo(new Vector3f(1, 0, 0)));
        assertThat(BlockFace.WEST.normal(), equalTo(new Vector3f(-1, 0, 0)));
    }

    @Test
    @DisplayName("fromNormal resolves each cardinal direction")
    void fromNormalCardinals() {
        assertThat(BlockFace.fromNormal(new Vector3f(0, 1, 0)), is(BlockFace.UP));
        assertThat(BlockFace.fromNormal(new Vector3f(0, -1, 0)), is(BlockFace.DOWN));
        assertThat(BlockFace.fromNormal(new Vector3f(0, 0, 1)), is(BlockFace.SOUTH));
        assertThat(BlockFace.fromNormal(new Vector3f(0, 0, -1)), is(BlockFace.NORTH));
        assertThat(BlockFace.fromNormal(new Vector3f(1, 0, 0)), is(BlockFace.EAST));
        assertThat(BlockFace.fromNormal(new Vector3f(-1, 0, 0)), is(BlockFace.WEST));
    }

    @Test
    @DisplayName("fromNormal only cares about magnitude ordering, not normalisation")
    void fromNormalUnnormalised() {
        assertThat(BlockFace.fromNormal(new Vector3f(0, 5, 0)), is(BlockFace.UP));
        assertThat(BlockFace.fromNormal(new Vector3f(-0.3f, 0, 0)), is(BlockFace.WEST));
    }

    @Test
    @DisplayName("fromNormal ties resolve in Y > Z > X order (load-bearing for 45-degree faces)")
    void fromNormalTieBreak() {
        // |x| == |z|, no y: the Z axis wins over X.
        assertThat(BlockFace.fromNormal(new Vector3f(0.7f, 0, 0.7f)), is(BlockFace.SOUTH));
        assertThat(BlockFace.fromNormal(new Vector3f(0.7f, 0, -0.7f)), is(BlockFace.NORTH));
        // |y| == |z|: the Y axis wins over Z.
        assertThat(BlockFace.fromNormal(new Vector3f(0, 1, 1)), is(BlockFace.UP));
        assertThat(BlockFace.fromNormal(new Vector3f(0, -1, 1)), is(BlockFace.DOWN));
    }

    @Test
    @DisplayName("fromNormal falls back to UP for a degenerate zero normal")
    void fromNormalZero() {
        assertThat(BlockFace.fromNormal(Vector3f.ZERO), is(BlockFace.UP));
    }

}
