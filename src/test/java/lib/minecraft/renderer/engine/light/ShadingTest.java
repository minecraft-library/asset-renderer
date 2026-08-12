package lib.minecraft.renderer.engine.light;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.camera.LightingFrame;
import lib.minecraft.renderer.engine.raster.PassDeclaration;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * The pixel-level shade application and the block-icon relight pass.
 * <p>
 * {@link Shading#apply} is pinned on its quantization: it rounds half up, as vanilla's GLSL
 * {@code floor(v * 255 + 0.5)} does, and a truncating rewrite would bias every shaded channel about
 * half a least-significant bit low without failing anything coarser.
 * <p>
 * {@link Shading#DISABLED} is read as full bright by both of its consumers, and both are asserted
 * here because they arrive at that answer differently. {@link Shading#relightForItems3d} rewrites the
 * sentinel to {@code 1.0f} and never evaluates the Lambertian for that face; {@link Shading#apply}
 * resolves it to unity directly, so a face that reaches the rasterizer still carrying the sentinel
 * keeps its colour instead of clamping to black. The second half is the one worth pinning: the value
 * is baked at quad-emit time and consumed several call sites away, and read as a bare multiplier it
 * would take every channel negative.
 * <p>
 * {@link Shading#relightForItems3d} carries four branches that are each easy to get wrong and none
 * of which a compiler protects: the outward normal is taken from the winding only when the winding
 * <i>contradicts</i> the authored normal; the cardinal snap and the forced cull ride the same
 * argument; a two-sided face is shaded by whichever of its two orientations faces the camera; and
 * the shading normal makes a signed-byte round trip before the Lambertian sees it, which moves an
 * off-cardinal shade by about {@code 0.003}. Every case below runs against the identity lighting
 * frame, where the shading transform reduces to the {@code diag(1, -1, 1)} GUI flip and a model
 * normal's render-frame image can be named directly.
 */
@DisplayName("Shading - texel shade application and the block-icon relight")
class ShadingTest {

    private static final double EPS = 1.0e-5;

    /** The identity relight frame, where the shading transform is the bare GUI Y-flip */
    private static final LightingFrame FLAT = LightingFrame.tracking(EulerRotation.NONE);

    private static final PixelBuffer WHITE_1X1 = PixelBuffer.of(new int[]{0xFFFFFFFF}, 1, 1);
    private static final Vector2f UV = new Vector2f(0f, 0f);
    private static final Vector3f ORIGIN = new Vector3f(0f, 0f, 0f);

    @Test
    @DisplayName("apply scales only the colour channels and leaves the alpha byte alone")
    void applyPreservesAlpha() {
        assertThat(Shading.apply(0x40FFFFFF, 0.5f), equalTo(0x40808080));
        assertThat(Shading.apply(0x00FFFFFF, 0f), equalTo(0x00000000));
        assertThat(Shading.apply(0x7B123456, 0f), equalTo(0x7B000000));
    }

    @Test
    @DisplayName("apply rounds half up rather than truncating toward zero")
    void applyRoundsHalfUp() {
        // 255 * 0.5 is exactly 127.5: rounding half up gives 0x80, truncation would give 0x7F.
        assertThat(Shading.apply(0xFFFFFFFF, 0.5f), equalTo(0xFF808080));

        // The extreme case of the same bias - a channel of 1 halves to exactly 0.5, which survives
        // as 1 and would truncate to 0, turning a dim texel fully black.
        assertThat(Shading.apply(0xFF010101, 0.5f), equalTo(0xFF010101));
    }

    @Test
    @DisplayName("apply at unity is the identity on every channel")
    void applyAtUnityIsIdentity() {
        assertThat(Shading.apply(0xFF000000, 1f), equalTo(0xFF000000));
        assertThat(Shading.apply(0x0089ABCD, 1f), equalTo(0x0089ABCD));
        assertThat(Shading.apply(0xFFFFFFFF, 1f), equalTo(0xFFFFFFFF));
    }

    @Test
    @DisplayName("apply clamps a factor that overshoots rather than wrapping the channel")
    void applyClampsAboveFullBright() {
        assertThat(Shading.apply(0xFF808080, 4f), equalTo(0xFFFFFFFF));
        assertThat(Shading.apply(0xFF804020, 2f), equalTo(0xFFFF8040));
    }

    @Test
    @DisplayName("apply reads the DISABLED sentinel as full bright rather than as the factor -1")
    void disabledSentinelIsFullBrightThroughApply() {
        assertThat(Shading.DISABLED, equalTo(-1f));

        // The sentinel means "this face skips directional darkening", and vanilla's
        // getShade(direction, false) answers 1.0 for it, so apply resolves it to unity and every
        // channel survives. Read as a bare multiplier it would take each channel negative and clamp
        // the face to black, which is what this pins against - the value is baked at quad-emit time
        // and consumed several call sites away.
        assertThat(Shading.apply(0xFF804020, Shading.DISABLED), equalTo(0xFF804020));
        assertThat(Shading.apply(0x40FFFFFF, Shading.DISABLED), equalTo(0x40FFFFFF));

        // Unity and the sentinel are the same answer, which is the whole claim.
        assertThat(Shading.apply(0xFF123456, Shading.DISABLED), equalTo(Shading.apply(0xFF123456, 1f)));
    }

    @Test
    @DisplayName("relight rewrites the DISABLED sentinel to full bright, which is where its identity comes from")
    void relightRewritesDisabledToFullBright() {
        VisibleTriangle unshaded = triangle(new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 1f),
            new Vector3f(1f, 0f, 0f), Shading.DISABLED, SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false));
        VisibleTriangle relit = Shading.relightForItems3d(Concurrent.newList(unshaded), FLAT, true).getFirst();

        // The sentinel is replaced outright - the Lambertian is never evaluated for this face.
        assertThat(relit.shading(), equalTo(1.0f));

        // Which is what makes the pair an identity end to end: the face keeps the colour it sampled.
        assertThat(Shading.apply(0xFF804020, relit.shading()), equalTo(0xFF804020));

        // The sentinel path is not an early exit from the cull composition - the forced flag applies.
        assertThat(relit.traits().cullBackFaces(), is(true));
    }

    @Test
    @DisplayName("relight takes the winding normal only when it contradicts the authored one")
    void relightPrefersTheWindingNormalOnlyWhenItContradicts() {
        Vector3f east = new Vector3f(1f, 0f, 0f);

        // Vertices wound so the cross product agrees with the authored normal: the authored one is used.
        VisibleTriangle agreeing = triangle(new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 1f),
            east, 0.5f, SurfaceTraits.OPAQUE_BODY);
        assertThat(shadeOf(agreeing, false), equalTo(0.4f));

        // The same authored normal over reversed winding - the inner-faces cube a spawner emits, whose
        // authored normals point the wrong way. The winding wins and the face lights from the far side.
        VisibleTriangle inverted = triangle(new Vector3f(0f, 0f, 1f), new Vector3f(0f, 1f, 0f),
            east, 0.5f, SurfaceTraits.OPAQUE_BODY);
        assertThat(shadeOf(inverted, false), equalTo(1.0f));
    }

    @Test
    @DisplayName("relight keeps the authored normal when the winding is degenerate")
    void relightKeepsTheAuthoredNormalForADegenerateWinding() {
        // Three coincident vertices cross to zero, which normalises to zero and dots to exactly zero
        // against anything. The test for contradiction is strict, so zero is not a contradiction and
        // the authored normal stands rather than the face going dark on a zero shading normal.
        VisibleTriangle east = triangle(ORIGIN, ORIGIN, new Vector3f(1f, 0f, 0f), 0.5f, SurfaceTraits.OPAQUE_BODY);
        VisibleTriangle west = triangle(ORIGIN, ORIGIN, new Vector3f(-1f, 0f, 0f), 0.5f, SurfaceTraits.OPAQUE_BODY);

        assertThat(shadeOf(east, false), equalTo(0.4f));
        assertThat(shadeOf(west, false), equalTo(1.0f));
    }

    @Test
    @DisplayName("relight snaps the shading normal to its nearest cardinal only when forcing the cull")
    void relightSnapsToTheCardinalOnlyWhenForcingTheCull() {
        // A gently tilted face - the shape a lectern's reading surface has. Vanilla's plain-block GUI
        // path lights every quad by its single baked direction, so the tilt is discarded and the face
        // shades as the flat cardinal it is nearest.
        VisibleTriangle tilted = facing(new Vector3f(0f, 0.2f, 1f).normalize(), SurfaceTraits.OPAQUE_BODY);

        assertThat((double) shadeOf(tilted, false), closeTo(0.62113035, EPS));
        assertThat((double) shadeOf(tilted, true), closeTo(0.51306784, EPS));

        // The forced answer is exactly the cardinal's own relit shade, not merely close to it.
        VisibleTriangle south = facing(new Vector3f(0f, 0f, 1f), SurfaceTraits.OPAQUE_BODY);
        assertThat(shadeOf(tilted, true), equalTo(shadeOf(south, false)));
    }

    @Test
    @DisplayName("relight shades a two-sided away-facing quad by its camera-facing side")
    void relightShadesTwoSidedQuadsByTheCameraFacingSide() {
        Vector3f awayFromCamera = new Vector3f(0f, 0f, -1f);

        // Two-sided: the away-facing orientation is flipped before lighting, so it lands on the shade
        // its camera-facing twin would get. Without the flip a banner or sign chain over-brightens
        // wherever the away-facing polygon wins the coplanar depth tie.
        VisibleTriangle twoSided = facing(awayFromCamera, SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false));
        assertThat((double) shadeOf(twoSided, false), closeTo(0.51306784, EPS));
        assertThat(shadeOf(twoSided, false),
            equalTo(shadeOf(facing(new Vector3f(0f, 0f, 1f), SurfaceTraits.OPAQUE_BODY), false)));

        // A culling face presents only its front side already, so it keeps its own normal and its own
        // distinctly different shade - the two Z faces are not symmetric under this light pair.
        VisibleTriangle culling = facing(awayFromCamera, SurfaceTraits.OPAQUE_BODY);
        assertThat((double) shadeOf(culling, false), closeTo(0.54658010, EPS));
    }

    @Test
    @DisplayName("relight round-trips the shading normal through a signed byte before lighting it")
    void relightRoundTripsTheShadingNormalThroughASignedByte() {
        // A cardinal survives the round trip untouched (127/127 is one), and this one has a zero Y so
        // the GUI flip leaves it alone too - the relit shade is exactly the bare Lambertian of it.
        Vector3f cardinal = new Vector3f(0f, 0f, 1f);
        assertThat(shadeOf(facing(cardinal, SurfaceTraits.OPAQUE_BODY), false),
            equalTo(Lighting.blockItems3d(cardinal)));

        // An off-cardinal does not: 0.7071 truncates to 89/127 = 0.7008, and the shade moves about
        // 0.0033 with it. That offset is the whole reason the step exists, so the relit value must
        // differ from the Lambertian of the un-packed render normal.
        Vector3f diagonal = new Vector3f(1f, 1f, 0f).normalize();
        Vector3f render = new Vector3f(diagonal.x(), -diagonal.y(), diagonal.z()).normalize();
        float relit = shadeOf(facing(diagonal, SurfaceTraits.OPAQUE_BODY), false);

        assertThat((double) Lighting.blockItems3d(render), closeTo(0.77039760, EPS));
        assertThat((double) relit, closeTo(0.76708734, EPS));
        assertThat(relit, not(equalTo(Lighting.blockItems3d(render))));
    }

    @Test
    @DisplayName("relight rebuilds each triangle changing only its shade and its cull flag")
    void relightCarriesEverythingElseThrough() {
        SurfaceTraits twoSidedGlinted = new SurfaceTraits(false, true, true, PassDeclaration.DEFAULT);
        VisibleTriangle input = new VisibleTriangle(
            ORIGIN, new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 1f),
            new Vector2f(0.1f, 0.2f), new Vector2f(0.3f, 0.4f), new Vector2f(0.5f, 0.6f),
            WHITE_1X1, 0xFF3366CC, new Vector3f(1f, 0f, 0f), 0.5f, twoSidedGlinted, "block:east");
        ConcurrentList<VisibleTriangle> source = Concurrent.newList(input);

        ConcurrentList<VisibleTriangle> out = Shading.relightForItems3d(source, FLAT, true);
        assertThat(out, hasSize(1));
        assertThat(out, not(sameInstance(source)));

        VisibleTriangle got = out.getFirst();
        assertThat(got.position0(), equalTo(input.position0()));
        assertThat(got.position1(), equalTo(input.position1()));
        assertThat(got.position2(), equalTo(input.position2()));
        assertThat(got.uv0(), equalTo(input.uv0()));
        assertThat(got.uv1(), equalTo(input.uv1()));
        assertThat(got.uv2(), equalTo(input.uv2()));
        assertThat(got.texture(), sameInstance(WHITE_1X1));
        assertThat(got.tintArgb(), equalTo(0xFF3366CC));
        assertThat(got.debugTag(), equalTo("block:east"));

        // The authored normal is carried forward, not the outward or shading normal derived from it -
        // downstream passes still need the direction the model declared.
        assertThat(got.normal(), equalTo(new Vector3f(1f, 0f, 0f)));

        // Only the cull bit moves inside the traits; the other three ride through.
        assertThat(got.traits().cullBackFaces(), is(true));
        assertThat(got.traits().translucent(), is(true));
        assertThat(got.traits().glinted(), is(true));
        assertThat(got.traits().pass(), sameInstance(PassDeclaration.DEFAULT));

        // The source list and its triangles are left as they were - the pass builds a new list.
        assertThat(source.getFirst(), sameInstance(input));
        assertThat(input.shading(), equalTo(0.5f));
        assertThat(input.traits().cullBackFaces(), is(false));

        // Unforced, a triangle keeps its own cull flag.
        assertThat(Shading.relightForItems3d(source, FLAT, false).getFirst().traits().cullBackFaces(), is(false));
    }

    /**
     * Relights a lone triangle against the identity frame and answers the shade it was assigned.
     *
     * @param subject the triangle to relight
     * @param forceCullBackFaces whether to force the cull and the cardinal snap
     * @return the recomputed shade factor
     */
    private static float shadeOf(VisibleTriangle subject, boolean forceCullBackFaces) {
        return Shading.relightForItems3d(Concurrent.newList(subject), FLAT, forceCullBackFaces)
            .getFirst()
            .shading();
    }

    /**
     * Builds a triangle whose winding agrees with {@code normal} - two unit vectors perpendicular to
     * it and to each other, ordered so their cross product is {@code normal} itself, laid out from the
     * origin.
     *
     * @param normal the authored and geometric normal the triangle presents
     * @param traits the surface traits the triangle carries
     * @return a triangle facing {@code normal}
     */
    private static VisibleTriangle facing(Vector3f normal, SurfaceTraits traits) {
        Vector3f seed = Math.abs(normal.y()) < 0.9f ? new Vector3f(0f, 1f, 0f) : new Vector3f(1f, 0f, 0f);
        Vector3f first = normal.cross(seed).normalize();
        return triangle(first, normal.cross(first), normal, 0.5f, traits);
    }

    /**
     * Builds a triangle from the origin through two further vertices, carrying an authored normal that
     * the caller may deliberately disagree with the winding.
     *
     * @param position1 the second vertex
     * @param position2 the third vertex
     * @param normal the authored surface normal
     * @param shading the baked shade the relight is expected to replace
     * @param traits the surface traits the triangle carries
     * @return the assembled triangle
     */
    private static VisibleTriangle triangle(
        Vector3f position1, Vector3f position2, Vector3f normal, float shading, SurfaceTraits traits) {
        return new VisibleTriangle(ORIGIN, position1, position2, UV, UV, UV, WHITE_1X1, 0xFFFFFFFF,
            normal, shading, traits);
    }

}
