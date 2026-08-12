package lib.minecraft.renderer.engine.light;

import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The three inventory lighting entries {@link Lighting} reproduces and the dual-light Lambertian all
 * of them evaluate.
 * <p>
 * Four contracts are pinned here, each of which a plausible simplification would break silently:
 * <ul>
 *   <li><b>The transcribed constants.</b> {@link Lighting#BLOCK_ITEMS_3D_LIGHT_0} and
 *       {@link Lighting#BLOCK_ITEMS_3D_LIGHT_1} are bit patterns copied out of vanilla's own
 *       pre-rotated lights, so they are asserted as raw {@code int} bits - a decimal rewrite that
 *       reads identically in source can still land on a different {@code float}.</li>
 *   <li><b>Per-light clamping.</b> The shader clamps <i>each</i> dot product at zero and then sums,
 *       never the other way round. A face and its opposite therefore carry {@code |d0| + |d1|}
 *       rather than {@code |d0 + d1|}, which is what makes the two Z faces differ by more than the
 *       sum of their lights would allow.</li>
 *   <li><b>The entries are three different functions.</b> {@link Lighting#inventory} buckets a
 *       normal to one of four cardinal scalars, {@link Lighting#blockItems3d} is continuous under
 *       the block-icon lights, and {@link Lighting#itemsFlat} is continuous under a different pose
 *       whose front face reaches full bright where the block-icon entry does not.</li>
 *   <li><b>The {@code [0.4, 1.0]} envelope.</b> Both rails are exact floats - the ambient floor is
 *       reached whenever both lights face away, and the ceiling is a hard clamp rather than a
 *       normalisation.</li>
 * </ul>
 * <p>
 * {@link Lighting.EntityLighting#shade} is exercised against synthetic bases built in the test so the
 * {@code PER_FACE_LIGHTING} branch, the two rails and the per-light clamp are each isolated; the
 * shipped {@code [210, 45, 0]} frame's own resolution is covered elsewhere.
 */
@DisplayName("Lighting - the inventory lighting entries and their shared Lambertian")
class LightingTest {

    private static final double EPS = 1.0e-5;

    private static final Vector3f RIGHT = new Vector3f(1f, 0f, 0f);
    private static final Vector3f LEFT = new Vector3f(-1f, 0f, 0f);
    private static final Vector3f UP = new Vector3f(0f, 1f, 0f);
    private static final Vector3f DOWN = new Vector3f(0f, -1f, 0f);
    private static final Vector3f TOWARD_CAMERA = new Vector3f(0f, 0f, 1f);
    private static final Vector3f AWAY_FROM_CAMERA = new Vector3f(0f, 0f, -1f);

    /** The unrotated GL view vector the synthetic entity bases below are expressed against */
    private static final Vector3f VIEW = new Vector3f(0f, 0f, -1f);

    @Test
    @DisplayName("the diffuse power and ambient floor are vanilla's GLSL constants exactly")
    void shaderConstantsMatchVanilla() {
        assertThat(Lighting.MINECRAFT_LIGHT_POWER, equalTo(0.6f));
        assertThat(Lighting.MINECRAFT_AMBIENT_LIGHT, equalTo(0.4f));
    }

    @Test
    @DisplayName("the ITEMS_3D lights are the transcribed bit patterns, not a decimal rewrite")
    void blockItems3dLightsAreTranscribedBitPatterns() {
        // Copied from vanilla's pre-rotated Lighting UBO upload. A source rewrite to decimal
        // literals reads the same and lands on a different float, which flips rounding at the
        // LEFT face's shade boundary - so the raw bits are the assertion.
        assertThat(Float.floatToRawIntBits(Lighting.BLOCK_ITEMS_3D_LIGHT_0.x()), equalTo(0xBF6EF5DF));
        assertThat(Float.floatToRawIntBits(Lighting.BLOCK_ITEMS_3D_LIGHT_0.y()), equalTo(0xBE867FEC));
        assertThat(Float.floatToRawIntBits(Lighting.BLOCK_ITEMS_3D_LIGHT_0.z()), equalTo(0xBE7A29D2));
        assertThat(Float.floatToRawIntBits(Lighting.BLOCK_ITEMS_3D_LIGHT_1.x()), equalTo(0xBDD41D3A));
        assertThat(Float.floatToRawIntBits(Lighting.BLOCK_ITEMS_3D_LIGHT_1.y()), equalTo(0xBF7A02E7));
        assertThat(Float.floatToRawIntBits(Lighting.BLOCK_ITEMS_3D_LIGHT_1.z()), equalTo(0x3E40F819));

        // Vanilla normalises before uploading, so both arrive unit length.
        assertThat((double) Lighting.BLOCK_ITEMS_3D_LIGHT_0.length(), closeTo(1.0, EPS));
        assertThat((double) Lighting.BLOCK_ITEMS_3D_LIGHT_1.length(), closeTo(1.0, EPS));
    }

    @Test
    @DisplayName("blockItems3d rests on the ambient floor and the unity ceiling, both exactly")
    void blockItems3dEnvelopeIsExact() {
        // Both ITEMS_3D lights have negative x and negative y, so a render-frame normal pointing
        // along +X or +Y has both dot products clamped away and lands on ambient with nothing added.
        assertThat(Lighting.blockItems3d(RIGHT), equalTo(0.4f));
        assertThat(Lighting.blockItems3d(UP), equalTo(0.4f));

        // The opposite normals overshoot unity (the raw sums are 1.022 and 1.144), so the ceiling is
        // a hard clamp - the result is exactly 1.0f rather than a renormalised near-miss.
        assertThat(Lighting.blockItems3d(LEFT), equalTo(1.0f));
        assertThat(Lighting.blockItems3d(DOWN), equalTo(1.0f));
    }

    @Test
    @DisplayName("blockItems3d clamps each light separately rather than clamping their sum")
    void blockItems3dClampsEachLightSeparately() {
        // On Z the two lights disagree in sign: light 0 contributes -0.2443 and light 1 +0.1884.
        // Clamping per light keeps whichever is positive on each side, so both Z faces sit well
        // above ambient and differ by the full 0.6 * (|d0| + |d1|) spread.
        assertThat((double) Lighting.blockItems3d(TOWARD_CAMERA), closeTo(0.51306784, EPS));
        assertThat((double) Lighting.blockItems3d(AWAY_FROM_CAMERA), closeTo(0.54658010, EPS));

        // Had the clamp been applied to the sum instead, +Z would have fallen to bare ambient (its
        // combined dot is negative) and -Z would have reached only 0.4335.
        assertThat((double) Lighting.blockItems3d(TOWARD_CAMERA), not(closeTo(0.4, 1.0e-3)));
        assertThat((double) Lighting.blockItems3d(AWAY_FROM_CAMERA), not(closeTo(0.43351, 1.0e-3)));
    }

    @Test
    @DisplayName("inventory buckets a normal to its dominant cardinal's pre-baked scalar")
    void inventoryBucketsToTheDominantCardinal() {
        // A lectern's -22.5 degree reading surface: the dominant axis is Y, so it lights as a flat
        // top rather than as the tilted plane it is. This bucketing is the whole point of the entry.
        Vector3f tilted = new Vector3f(0f, 0.92388f, -0.38268f);
        assertThat(Lighting.inventory(tilted), equalTo(Face.UP.lighting()));
        assertThat(Lighting.inventory(tilted), equalTo(1.0f));

        // An exact |x| == |z| tie takes the Z cardinal, so it shades 0.6 and not the 0.8 the X
        // cardinals carry - the two are not interchangeable and the tie is load-bearing.
        assertThat(Lighting.inventory(new Vector3f(0.7f, 0f, 0.7f)), equalTo(0.6f));

        // A degenerate normal has no winning axis and falls back to the UP scalar rather than to
        // ambient or to zero.
        assertThat(Lighting.inventory(Vector3f.ZERO), equalTo(1.0f));
    }

    @Test
    @DisplayName("inventory is a four-bucket approximation, not the ITEMS_3D Lambertian")
    void inventoryIsNotTheContinuousLambertian() {
        // Both claim to reproduce the same vanilla entry; only one of them is continuous. Collapsing
        // block and fluid kits onto blockItems3d would move every shipped block shade.
        assertThat(Lighting.inventory(TOWARD_CAMERA), equalTo(0.6f));
        assertThat((double) Lighting.blockItems3d(TOWARD_CAMERA), not(closeTo(0.6, 1.0e-3)));
    }

    @Test
    @DisplayName("the ITEMS_FLAT lights are vanilla's diffuse pair under the flat-item pose")
    void itemsFlatLightsCarryTheFlatItemPose() {
        // Derived at class load from normalize(0.2, 1, -0.7) and normalize(-0.2, 1, 0.7) under
        // rotationY(-pi/8).rotateX(3pi/4), so the components are checked to tolerance rather than
        // to the bit.
        assertThat((double) Lighting.ITEMS_FLAT_LIGHT_0.x(), closeTo(-0.2225190, EPS));
        assertThat((double) Lighting.ITEMS_FLAT_LIGHT_0.y(), closeTo(-0.1714986, EPS));
        assertThat((double) Lighting.ITEMS_FLAT_LIGHT_0.z(), closeTo(0.9597258, EPS));
        assertThat((double) Lighting.ITEMS_FLAT_LIGHT_1.x(), closeTo(-0.2150121, EPS));
        assertThat((double) Lighting.ITEMS_FLAT_LIGHT_1.y(), closeTo(-0.9718253, EPS));
        assertThat((double) Lighting.ITEMS_FLAT_LIGHT_1.z(), closeTo(0.0965678, EPS));

        // The derive re-normalises after the pose.
        assertThat((double) Lighting.ITEMS_FLAT_LIGHT_0.length(), closeTo(1.0, EPS));
        assertThat((double) Lighting.ITEMS_FLAT_LIGHT_1.length(), closeTo(1.0, EPS));

        // The pose is a rigid rotation, so the angle between the pair survives it: the raw literals
        // subtend the same 0.30719 the transformed pair does. A pose carrying a scale or a shear
        // would move this even though both lights stayed unit length.
        assertThat((double) Lighting.ITEMS_FLAT_LIGHT_0.dot(Lighting.ITEMS_FLAT_LIGHT_1),
            closeTo(0.30718954, EPS));
    }

    @Test
    @DisplayName("itemsFlat lights the camera-facing front full-bright where blockItems3d does not")
    void itemsFlatIsADifferentEntryFromBlockItems3d() {
        // The flat-item pose points both lights down the +Z of the render frame, so the face a
        // shield presents to the camera saturates while the same normal under the block-icon entry
        // sits near half-bright. Swapping one entry for the other is visible on every 3D item.
        assertThat(Lighting.itemsFlat(TOWARD_CAMERA), equalTo(1.0f));
        assertThat((double) Lighting.blockItems3d(TOWARD_CAMERA), closeTo(0.51306784, EPS));

        // Its side and back faces darken all the way to ambient, and one side stays lit.
        assertThat(Lighting.itemsFlat(RIGHT), equalTo(0.4f));
        assertThat(Lighting.itemsFlat(AWAY_FROM_CAMERA), equalTo(0.4f));
        assertThat((double) Lighting.itemsFlat(LEFT), closeTo(0.66251866, EPS));

        // The two entries do not share a light: swapping the constants would be caught here.
        assertThat(Lighting.ITEMS_FLAT_LIGHT_0, not(equalTo(Lighting.BLOCK_ITEMS_3D_LIGHT_0)));
        assertThat(Lighting.ITEMS_FLAT_LIGHT_1, not(equalTo(Lighting.BLOCK_ITEMS_3D_LIGHT_1)));
    }

    @Test
    @DisplayName("EntityLighting.shade rests on the ambient floor and the unity ceiling, both exactly")
    void entityShadeEnvelopeIsExact() {
        Lighting.EntityLighting basis = basis(TOWARD_CAMERA, TOWARD_CAMERA);

        // A culling face turned away from both lights takes neither contribution and sits on ambient.
        assertThat(basis.shade(AWAY_FROM_CAMERA, true), equalTo(0.4f));

        // Both lights aligned raise the raw sum to 1.6, so the ceiling is a clamp.
        assertThat(basis.shade(TOWARD_CAMERA, true), equalTo(1.0f));
    }

    @Test
    @DisplayName("EntityLighting.shade clamps each light separately rather than clamping their sum")
    void entityShadeClampsEachLightSeparately() {
        // A half-length first light and an opposed second one: the record takes whatever directions
        // it is handed, and these place the answer clear of both rails. Clamping per light keeps the
        // 0.5 and drops the -1, landing on 0.7; clamping the sum would drop both and land on ambient.
        Lighting.EntityLighting basis = basis(new Vector3f(0f, 0f, 0.5f), AWAY_FROM_CAMERA);

        assertThat((double) basis.shade(TOWARD_CAMERA, true), closeTo(0.7, EPS));
        assertThat((double) basis.shade(TOWARD_CAMERA, true), not(closeTo(0.4, 1.0e-3)));
    }

    @Test
    @DisplayName("EntityLighting.shade flips an away-facing normal only when the face does not cull")
    void entityShadeFlipsOnlyForNoCullFaces() {
        Lighting.EntityLighting basis = basis(TOWARD_CAMERA, Vector3f.ZERO);

        // A no-cull face the camera sees from behind is lit by the orientation that faces the camera,
        // which is vanilla's per-pixel front / back colour choice; a culling face keeps its own normal
        // and goes dark. One flag, both rails apart.
        assertThat(basis.shade(AWAY_FROM_CAMERA, true), equalTo(0.4f));
        assertThat(basis.shade(AWAY_FROM_CAMERA, false), equalTo(1.0f));

        // A normal already facing the camera is never flipped, so the flag makes no difference to it.
        assertThat(basis.shade(TOWARD_CAMERA, true), equalTo(1.0f));
        assertThat(basis.shade(TOWARD_CAMERA, false), equalTo(1.0f));
    }

    @Test
    @DisplayName("EntityLighting.shade treats an exactly edge-on normal as away-facing")
    void entityShadeTreatsEdgeOnAsAwayFacing() {
        // The test is a strict dot < 0, so a normal perpendicular to the view - dot exactly zero -
        // falls on the away-facing side and a no-cull face lights by its opposite.
        Lighting.EntityLighting basis = basis(LEFT, Vector3f.ZERO);

        assertThat(basis.shade(RIGHT, false), equalTo(1.0f));
        assertThat(basis.shade(RIGHT, true), equalTo(0.4f));
    }

    /**
     * Builds an entity lighting basis around the unrotated view vector so a test can name its two
     * light directions directly.
     *
     * @param light0 the first diffuse light direction
     * @param light1 the second diffuse light direction
     * @return the basis to shade against
     */
    private static Lighting.EntityLighting basis(Vector3f light0, Vector3f light1) {
        return new Lighting.EntityLighting(VIEW, light0, light1);
    }

}
