package lib.minecraft.renderer.engine.light;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Applies a {@link Lighting} shade scalar to sampled texels and re-shades block-icon geometry for
 * vanilla's {@code Lighting.ITEMS_3D} GUI path. The shade scalar is baked into each
 * {@link VisibleTriangle} at kit-build time; {@link #apply} multiplies it into the rasterized
 * texel with vanilla's round-half-up GLSL quantization.
 *
 * @see Lighting
 */
@UtilityClass
public class Shading {

    /**
     * Sentinel shade value marking a {@code "shade": false} model element (coral fans, cross/crop
     * plants, ladder, vine, tripwire, redstone dust, torches). Vanilla's
     * {@code getShade(direction, shade=false)} returns {@code 1.0} - the face skips directional
     * darkening - so {@link #relightForItems3d} renders these full-bright instead of applying the
     * {@code Lighting.ITEMS_3D} Lambertian. Block kits bake this scalar at quad-emit time.
     */
    public static final float DISABLED = -1f;

    // --- shading ---

    /**
     * Multiplies an ARGB pixel's RGB channels by a shading factor, preserving the alpha channel.
     *
     * @param argb the source ARGB pixel
     * @param factor the shading factor in {@code [0, 1]}
     * @return the shaded ARGB pixel
     */
    public static int apply(int argb, float factor) {
        // Vanilla GLSL quantizes via `floor(min(1, v) * 255 + 0.5)` (round-half-up), so match it with
        // Math.round. A plain (int) truncation would bias every shaded channel ~0.5 LSB low and leave
        // a single-LSB precision floor across un-tinted entities (goat / husk / zombie / skeleton etc).
        int a = (argb >>> 24) & 0xFF;
        int r = Math.round(((argb >>> 16) & 0xFF) * factor);
        int g = Math.round(((argb >>> 8) & 0xFF) * factor);
        int b = Math.round((argb & 0xFF) * factor);

        r = Math.clamp(r, 0, 255);
        g = Math.clamp(g, 0, 255);
        b = Math.clamp(b, 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // --- block-icon ITEMS_3D relighting (moved out of BlockRenderer) ---

    /**
     * Re-shades every triangle with vanilla's {@code Lighting.ITEMS_3D} Lambertian based on
     * the triangle's normal rotated through the block's {@code display.gui} pose and the
     * GUI PoseStack's Y-flip ({@code scale(W, -H, W)}). Replaces the cardinal-bucket
     * shading {@code BlockGeometryKit} bakes at quad-emit time.
     * <p>
     * The transform chain mirrors vanilla's render path exactly: vanilla submits each
     * quad's normal via {@code pose.transformNormal(quadNormal)}, where {@code pose} =
     * {@code translate(W/2,H/2,0) × scale(W,-H,W) × Q_{display.gui}}. Translation doesn't
     * affect direction; the upper-3x3 of the scale is {@code diag(1, -1, 1)} (up to magnitude,
     * which renormalises out for direction vectors); the gui rotation is a pure rotation.
     * So the per-vertex normal handed to the fragment shader is
     * {@code S(1,-1,1) × R_{gui} × n_model}, and that's what
     * {@link Lighting#blockItems3d} expects.
     * <p>
     * When {@code forceCullBackFaces} is set (plain block models, see the caller) every emitted
     * triangle is marked {@code cullBackFaces=true} regardless of its built-in flag, matching
     * vanilla's block render types, which all bind GL culling. {@code BlockGeometryKit} marks
     * zero-thickness ({@code block/cross}, crop, multiface) faces two-sided so both authored
     * sides survive; the rasterizer's winding cull then keeps only the camera-facing one - the
     * same single-sided result vanilla shows, without the away-facing mirror-UV overdraw. The
     * camera-facing flip below is therefore skipped for those faces (they cull instead). Passing
     * {@code false} (block-entity surfaces - signs, banner cloth, hanging-sign chains, which
     * vanilla renders with {@code entityCutoutNoCull}) keeps the two-sided faces and the flip.
     *
     * @param triangles the kit-built block triangles carrying baked cardinal shading
     * @param guiRotation the block's {@code display.gui} pose rotation
     * @param forceCullBackFaces whether to force every triangle to cull back faces (plain block
     *     models) and snap shading normals to the nearest cardinal
     * @return a new list of re-shaded triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> relightForItems3d(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull EulerRotation guiRotation,
        boolean forceCullBackFaces
    ) {
        Matrix4f normalTransform = Matrix4f.IDENTITY
            .scale(1f, -1f, 1f)
            .rotate(Quaternionf.rotationXYZ(
                guiRotation.pitchRadians(),
                guiRotation.yawRadians(),
                guiRotation.rollRadians()
            ));
        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle t : triangles) {
            boolean cull = forceCullBackFaces || t.traits().cullBackFaces();
            // DISABLED marks a "shade": false element (see the field doc): vanilla's
            // getShade(direction, false) returns 1.0, so render it full-bright rather than applying
            // the ITEMS_3D Lambertian. Cull / two-sided handling is unchanged; only the shade differs.
            if (t.shading() == DISABLED) {
                out.add(new VisibleTriangle(
                    t.position0(), t.position1(), t.position2(),
                    t.uv0(), t.uv1(), t.uv2(),
                    t.texture(), t.tintArgb(), t.normal(),
                    1.0f, t.traits().withCullBackFaces(cull), t.debugTag()
                ));
                continue;
            }
            // The outward facing of this quad. Normally the authored face normal ({@code
            // t.normal()}) - the cardinal pushed through the element-rotation matrix - but the
            // {@code spawner}/{@code trial_spawner}/{@code vault} inner-faces models emit a second
            // cube with INVERTED geometry ({@code from > to}, reversed winding) whose authored
            // normals point the wrong way; for those the winding (cross-product) normal is the
            // true facing. Use the winding normal only when it contradicts the authored one.
            Vector3f geometricNormal = t.position1().subtract(t.position0())
                .cross(t.position2().subtract(t.position0())).normalize();
            Vector3f outwardNormal = geometricNormal.dot(t.normal()) < 0f ? geometricNormal : t.normal();
            // Vanilla's plain-block GUI path ({@code BlockFeatureRenderer.putBakedQuad}) carries NO
            // per-vertex normal: every quad lights by its single {@code BakedQuad.direction =
            // requireNonNullElse(FaceBakery.calculateFacing(verts), UP)}, the cardinal nearest the
            // quad's geometric normal - not the continuous tilted normal. So lectern's -22.5deg
            // reading surface ((0, 0.924, -0.383)) lights as its nearest cardinal UP (full bright),
            // exactly as if it were a flat top, and the iso reference matches.
            //
            // Snap from {@code outwardNormal} (the authored normal pushed through the element-
            // rotation matrix) rather than {@code geometricNormal} (the cross of the tessellated
            // TRIANGLE, one of whose edges is the quad diagonal). At an exactly-45deg face the two
            // tied cardinals decide on the sub-ULP balance of the normal's components, and the
            // triangle-diagonal cross drifts one ULP off symmetry - e.g. on a sculk-sensor tendril
            // it pushes |x| past |z| and wrongly snaps EAST/WEST (shade 0.65/0.49) where the
            // reference shows the Z cardinal (0.40). The element-rotation matrix yields a bit-
            // symmetric authored normal ({@code |x| == |z|} to the raw int bits), so the tie falls
            // to the lower {@code Direction.values()} index and reproduces the reference shade.
            //
            // Block-entity surfaces (signs, banner cloth, hanging-sign chains) render through
            // vanilla's entity path ({@code entityCutoutNoCull} + {@code PER_FACE_LIGHTING}), not
            // {@code putBakedQuad}, so they keep the continuous normal and the camera-facing flip.
            Vector3f shadeNormal = forceCullBackFaces
                ? BlockFace.fromNormal(outwardNormal).normal()
                : outwardNormal;
            Vector3f renderNormal = shadeNormal.transformNormal(normalTransform).normalize();
            // Two-sided (no back-face cull) faces: shade by the camera-facing normal. Vanilla's
            // ENTITY_CUTOUT / sign pipeline composes withCull(false) + PER_FACE_LIGHTING, whose
            // fragment shader picks the front- or back-vertex colour by screen-space winding -
            // equivalent to shading against whichever normal points at the camera. A zero-depth
            // plane emits two coplanar polygons with opposite normals (sign chains, banner cloth,
            // item-frame backing); without this, the asset shades by whichever polygon wins the
            // coplanar depth tie (the away-facing one over-brightens to ~1.0 where vanilla shows
            // the camera-facing ~0.5). Visible iso faces point at +Z in this render frame, so an
            // away-facing (z < 0) two-sided normal is flipped before lighting. Faces that cull
            // (genuine cube faces, or plain block models under {@code forceCullBackFaces})
            // already present only their front side, so they are left untouched.
            if (!cull && renderNormal.z() < 0f)
                renderNormal = new Vector3f(-renderNormal.x(), -renderNormal.y(), -renderNormal.z());
            // Match vanilla's vertex-stream byte-packed normal: the shader receives the
            // normal after a signed-byte SNORM round-trip ({@code (int)(c * 127.0F) / 127.0F},
            // truncated toward zero). For the LEFT face of a default iso pose, this maps
            // unit (-0.7071, 0.3536, 0.6124) -> (-0.7008, 0.3465, 0.6063), magnitude 0.9894;
            // the resulting Lambertian shade drops from 0.6505 to 0.6490, matching vanilla's
            // empirical 0.647 within precision. Without this step every block shows the
            // visible-LEFT face's texels rounded ~1 LSB high.
            Vector3f packedNormal = packAsSnormByte(renderNormal);
            float shading = Lighting.blockItems3d(packedNormal);
            out.add(new VisibleTriangle(
                t.position0(), t.position1(), t.position2(),
                t.uv0(), t.uv1(), t.uv2(),
                t.texture(), t.tintArgb(), t.normal(),
                shading, t.traits().withCullBackFaces(cull), t.debugTag()
            ));
        }
        return out;
    }

    /**
     * Replicates vanilla's {@code BufferBuilder.normalIntValue} byte-packing followed by
     * the shader's SNORM unpacking. Each component {@code c} is mapped to
     * {@code (int)(clamp(c, -1, 1) * 127.0F) / 127.0F}, with the integer cast truncating
     * toward zero (so {@code 0.6124 -> 77/127 = 0.6063}, not {@code 78/127 = 0.6142}).
     * The result is not unit length - vanilla's shader doesn't renormalize either.
     */
    private static @NotNull Vector3f packAsSnormByte(@NotNull Vector3f n) {
        return new Vector3f(
            ((int) (Math.clamp(n.x(), -1f, 1f) * 127.0f)) / 127.0f,
            ((int) (Math.clamp(n.y(), -1f, 1f) * 127.0f)) / 127.0f,
            ((int) (Math.clamp(n.z(), -1f, 1f) * 127.0f)) / 127.0f
        );
    }

}
