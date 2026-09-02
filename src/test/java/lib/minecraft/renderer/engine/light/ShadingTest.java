package lib.minecraft.renderer.engine.light;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
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

import java.util.List;

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
 * {@link Shading#apply} is pinned on its quantization: it is vanilla's GLSL
 * {@code floor(v * 255 + 0.5)} with the tie point shifted a fortieth of a step down, which is what
 * absorbs the sub-LSB gap between one multiply here and a fragment's chain on a GPU. Both edges are
 * pinned below, because a rewrite that dropped the shift would take every near-tie channel a step high
 * and one that truncated instead would take every shaded channel about half a step low, and neither
 * fails anything coarser.
 * <p>
 * A face declaring {@link SurfaceTraits#directionalLight()} {@code false} is left full bright.
 * {@link Shading#relightForItems3d} writes {@code 1.0f} and never evaluates the Lambertian for it,
 * which is the same answer vanilla's {@code getShade(direction, false)} gives. The flag is what the
 * relight reads, so a face carrying it keeps its colour whatever scalar the kit happened to bake, and
 * {@link Shading#apply} sees only real {@code [0, 1]} factors.
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

    /**
     * The production tie point, written out rather than read from {@code Shading} so that a change to
     * the shift has to be made here as well and cannot pass unnoticed.
     *
     * @param v the shaded channel value
     * @return the channel rounded as {@code Shading} rounds it
     */
    private static int quantize(float v) {
        return (int) Math.floor(v + 0.5f - 0.024f);
    }

    /** The identity relight frame, where the shading transform is the bare GUI Y-flip */
    private static final LightingFrame FLAT = LightingFrame.tracking(EulerRotation.NONE);

    private static final PixelBuffer WHITE_1X1 = PixelBuffer.of(new int[]{0xFFFFFFFF}, 1, 1);
    private static final Vector2f UV = new Vector2f(0f, 0f);
    private static final Vector3f ORIGIN = new Vector3f(0f, 0f, 0f);

    @Test
    @DisplayName("apply scales only the colour channels and leaves the alpha byte alone")
    void applyPreservesAlpha() {
        assertThat(Shading.apply(0x40FFFFFF, ColorMath.WHITE, 0.5f), equalTo(0x407F7F7F));
        assertThat(Shading.apply(0x00FFFFFF, ColorMath.WHITE, 0f), equalTo(0x00000000));
        assertThat(Shading.apply(0x7B123456, ColorMath.WHITE, 0f), equalTo(0x7B000000));
    }

    @Test
    @DisplayName("apply quantizes at the shifted tie point, under half up and over truncation")
    void applyQuantizesAtTheShiftedTie() {
        // 255 * 0.5 is exactly 127.5, so it sits ON the tie: plain round-half-up answers 0x80 and the
        // shifted point answers 0x7F. This is the whole of what the shift does, and the only place a
        // rewrite that dropped it would show.
        assertThat(Shading.apply(0xFFFFFFFF, ColorMath.WHITE, 0.5f), equalTo(0xFF7F7F7F));

        // It is still a rounding and not a truncation: 100 * 0.526 is 52.6, which is past the shifted
        // tie and carries up to 53, where truncating toward zero would answer 52.
        assertThat(Shading.apply(0xFF646464, ColorMath.WHITE, 0.526f), equalTo(0xFF353535));
    }

    @Test
    @DisplayName("apply at unity is the identity on every channel")
    void applyAtUnityIsIdentity() {
        assertThat(Shading.apply(0xFF000000, ColorMath.WHITE, 1f), equalTo(0xFF000000));
        assertThat(Shading.apply(0x0089ABCD, ColorMath.WHITE, 1f), equalTo(0x0089ABCD));
        assertThat(Shading.apply(0xFFFFFFFF, ColorMath.WHITE, 1f), equalTo(0xFFFFFFFF));
    }

    @Test
    @DisplayName("apply clamps a factor that overshoots rather than wrapping the channel")
    void applyClampsAboveFullBright() {
        assertThat(Shading.apply(0xFF808080, ColorMath.WHITE, 4f), equalTo(0xFFFFFFFF));
        assertThat(Shading.apply(0xFF804020, ColorMath.WHITE, 2f), equalTo(0xFFFF8040));
    }

    @Test
    @DisplayName("a white tint is a bare shade, bit-for-bit")
    void whiteTintIsABareShade() {
        // One method serves a tinted surface and a plain one, which holds because 255 / 255f is
        // exactly 1.0f and a multiply by it is exact. Asserted against the bare arithmetic rather
        // than against another call of the same method, or it says nothing: folding the tint in
        // AFTER the texel instead would round differently here and move every untinted surface in
        // the corpus, and it would still agree with itself.
        for (int argb : new int[]{0xFF804020, 0x7B123456, 0xFF010101, 0xFFFFFFFF}) {
            for (float f : new float[]{0f, 0.4f, 0.5f, 0.6489f, 1f}) {
                int r = quantize(((argb >>> 16) & 0xFF) * f);
                int g = quantize(((argb >>> 8) & 0xFF) * f);
                int b = quantize((argb & 0xFF) * f);
                int expected = (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
                assertThat(Shading.apply(argb, ColorMath.WHITE, f), equalTo(expected));
            }
        }
    }

    @Test
    @DisplayName("tint and shade quantise once, not once each")
    void tintAndShadeQuantiseOnce() {
        // Vanilla folds the tint into the vertex colour, so a fragment is quantised at the framebuffer
        // write and nowhere else. Tinting the texel to an int first and shading that rounds twice: a
        // channel of 1 under a half tint and a half shade is 0.251, which is 0 once and 1 twice.
        assertThat(Shading.apply(0xFF010101, 0xFF808080, 0.5f), equalTo(0xFF000000));

        // The same fragment through the two-step arithmetic, spelled out - this is what the rasterizer
        // used to compute, and it is a whole channel step brighter.
        int tintedFirst = Math.round(1 * 128 / 255f);
        assertThat(Math.round(tintedFirst * 0.5f), equalTo(1));
    }

    @Test
    @DisplayName("relight leaves a non-directional face full bright rather than lighting it")
    void relightLeavesANonDirectionalFaceFullBright() {
        SurfaceTraits traits = SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false).withDirectionalLight(false);
        // The kit bakes 1.0 for such a face, so the relight has nothing to correct - it has to leave
        // the value alone. Handing it a shade it could not have baked is what tells "left alone" from
        // "recomputed and happened to land on 1.0": the Lambertian of this normal is not 0.25.
        VisibleTriangle unshaded = triangle(new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 1f),
            new Vector3f(1f, 0f, 0f), 0.25f, traits);
        VisibleTriangle relit = Shading.relightForItems3d(Concurrent.newList(unshaded), FLAT, true).getFirst();

        // Full bright, and the Lambertian is never evaluated for this face.
        assertThat(relit.shading(), equalTo(1.0f));

        // Which makes the pair an identity end to end: the face keeps the colour it sampled.
        assertThat(Shading.apply(0xFF804020, ColorMath.WHITE, relit.shading()), equalTo(0xFF804020));

        // Not an early exit from the cull composition - the forced flag still applies.
        assertThat(relit.traits().cullBackFaces(), is(true));

        // And the flag rides through, so a second pass over the same triangles answers the same way.
        assertThat(relit.traits().directionalLight(), is(false));
    }

    @Test
    @DisplayName("relight lights a directional face carrying the same baked shade")
    void relightLightsADirectionalFaceCarryingTheSameBakedShade() {
        // The same geometry and the same baked scalar as the non-directional case above, differing in
        // the flag alone - so the flag is what decides, not the shade the kit happened to bake.
        VisibleTriangle directional = triangle(new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 1f),
            new Vector3f(1f, 0f, 0f), 0.25f, SurfaceTraits.OPAQUE_BODY.withCullBackFaces(false));
        VisibleTriangle relit = Shading.relightForItems3d(Concurrent.newList(directional), FLAT, true).getFirst();

        assertThat(relit.shading(), not(equalTo(1.0f)));
        assertThat(relit.shading(), equalTo(0.4f));
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
        SurfaceTraits twoSidedGlinted = new SurfaceTraits(false, true, true, true, PassDeclaration.DEFAULT);
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

        // Only the cull bit moves inside the traits; the other four ride through.
        assertThat(got.traits().cullBackFaces(), is(true));
        assertThat(got.traits().translucent(), is(true));
        assertThat(got.traits().glinted(), is(true));
        assertThat(got.traits().directionalLight(), is(true));
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

    @Test
    @DisplayName("packAsSnormByte is the identity on a cardinal and quantizes a rotated normal")
    void snormIsIdentityOnCardinalsOnly() {
        // Every cardinal component lands exactly on the signed-byte grid, so a caller shading only
        // axis-aligned cube faces cannot observe whether the round trip ran at all. That is why its
        // absence on one relight path is invisible until a rotated plane goes through it.
        for (Vector3f cardinal : List.of(
            new Vector3f(1f, 0f, 0f), new Vector3f(0f, -1f, 0f), new Vector3f(0f, 0f, 1f)))
            assertThat(Shading.packAsSnormByte(cardinal), equalTo(cardinal));

        // A 45-degree plane is where it bites: 0.7071 * 127 truncates to 89, not 90.
        Vector3f tilted = Shading.packAsSnormByte(new Vector3f(0.70710677f, 0f, 0.70710677f));
        assertThat((double) tilted.x(), closeTo(89f / 127f, 1e-7));
        assertThat((double) tilted.z(), closeTo(89f / 127f, 1e-7));

        // Truncation toward zero, never rounding - the sign is carried, not folded in.
        assertThat(Shading.packAsSnormByte(new Vector3f(-0.6124f, 0f, 0f)).x(), equalTo(-77f / 127f));
    }

    @Test
    @DisplayName("guiNormalTransform negates Y alone, which is what tells it from the entity chain")
    void guiNormalTransformNegatesYAlone() {
        LightingFrame identity = LightingFrame.fixed(EulerRotation.NONE);

        // All three cardinals, because the triple is what identifies the scale. At the identity frame
        // this reduces to the PoseStack's scale(W, -H, W) upper-3x3, diag(1, -1, 1). Lighting
        // .resolveEntity shares this method's mirrorX line and ends scale(mirrorX, 1, -1) instead -
        // two signs away - so a refactor that unified the two on that shared line would move the Y and
        // the Z images here and nothing else in the suite would notice.
        assertThat(new Vector3f(1f, 0f, 0f).transformNormal(Shading.guiNormalTransform(identity)),
            equalTo(new Vector3f(1f, 0f, 0f)));
        assertThat(new Vector3f(0f, 1f, 0f).transformNormal(Shading.guiNormalTransform(identity)),
            equalTo(new Vector3f(0f, -1f, 0f)));
        assertThat(new Vector3f(0f, 0f, 1f).transformNormal(Shading.guiNormalTransform(identity)),
            equalTo(new Vector3f(0f, 0f, 1f)));

        // A HORIZONTAL frame swaps screen left / right by flipping the leading scale's X and leaves the
        // Y flip alone, so the two reflections compose rather than replacing one another.
        LightingFrame mirrored = LightingFrame.fixed(EulerRotation.NONE).mirroredHorizontally();
        assertThat(new Vector3f(1f, 0f, 0f).transformNormal(Shading.guiNormalTransform(mirrored)),
            equalTo(new Vector3f(-1f, 0f, 0f)));
        assertThat(new Vector3f(0f, 1f, 0f).transformNormal(Shading.guiNormalTransform(mirrored)),
            equalTo(new Vector3f(0f, -1f, 0f)));
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
