package lib.minecraft.renderer.engine.light;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.engine.camera.LightingFrame;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

/**
 * Applies a {@link Lighting} shade scalar to sampled texels and re-shades GUI geometry against the
 * lighting entry vanilla binds for it - {@code Lighting.ITEMS_3D} for a block icon,
 * {@code Lighting.ENTITY_IN_UI} for a humanoid. The scalar rides each {@link VisibleTriangle} - baked
 * at build time by the block and fluid kits, resolved by one of the relights here over a folded entity
 * or player stack; {@link #apply} multiplies it into the rasterized texel with vanilla's round-half-up
 * GLSL quantization.
 *
 * @see Lighting
 */
@UtilityClass
public class Shading {

    /**
     * The scalar geometry carries before a relight resolves one for it - the multiplicative identity, so
     * {@link #apply} on it returns the sampled texel unchanged.
     * <p>
     * An entity's whole stack is lit as one draw once its layers are folded, under the entry vanilla binds
     * per GUI entity, so every producer feeding that fold emits this in place of a shade of its own.
     * <p>
     * It does not identify an unlit surface at the rasterizer. {@link #relightForEntityInUi} answers the
     * same value for a face declaring no directional light, {@link Lighting.EntityLighting#shade} reaches
     * it whenever the Lambertian saturates, and {@code Lighting.inventory} bakes it onto every UP face,
     * so a triangle carrying it may have been lit three different ways or not at all.
     */
    public static final float UNLIT = 1.0f;

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
     * <p>
     * The {@link LightingFrame} supplies the rotation and an optional screen reflection. A
     * {@link LightingFrame.Mirror#HORIZONTAL} frame negates the X of the final shading normal - a
     * screen-space left / right swap, top / bottom unchanged - by flipping the leading Y-flip's X sign
     * ({@code diag(1,-1,1)} becomes {@code diag(-1,-1,1)}); {@link LightingFrame.Mirror#NONE} is the
     * plain pose-tracking relight, bit-for-bit.
     *
     * @param triangles the kit-built block triangles carrying baked cardinal shading
     * @param lighting the frame the shading is built from - the {@code display.gui} pose rotation and any mirror
     * @param forceCullBackFaces whether to force every triangle to cull back faces (plain block
     *     models) and snap shading normals to the nearest cardinal
     * @return a new list of re-shaded triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> relightForItems3d(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull LightingFrame lighting,
        boolean forceCullBackFaces
    ) {
        Matrix4f normalTransform = guiNormalTransform(lighting);
        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle t : triangles) {
            boolean cull = forceCullBackFaces || t.traits().cullBackFaces();
            // A face that takes no directional light is a "shade": false element: vanilla's
            // getShade(direction, false) returns 1.0, so render it full-bright rather than applying
            // the ITEMS_3D Lambertian. Cull / two-sided handling is unchanged; only the shade differs.
            if (!t.traits().directionalLight()) {
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
            Vector3f geometricNormal = t.position1()
                .subtract(t.position0())
                .cross(t.position2().subtract(t.position0()))
                .normalize();
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
            Vector3f shadeNormal = forceCullBackFaces ? Face.fromNormal(outwardNormal).normal() : outwardNormal;
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
                renderNormal = Turn.INVERT.apply(renderNormal);
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

    // --- entity-in-UI relighting (vanilla Lighting.ENTITY_IN_UI parity) ---

    /**
     * Re-shades every triangle with vanilla's {@code Lighting.ENTITY_IN_UI} two-directional Lambertian -
     * the entry the client binds once per GUI entity draw, before any layer is submitted, so a wearer and
     * everything it wears light under it alike. {@link Lighting#resolveEntity} carries the two light
     * directions into the kit frame; {@code intoKitFrame} is the turn taking the caller's own model normal
     * into that same frame, and it is the whole of what varies between two subjects lit by this entry.
     * {@link Turn#MIRROR_Y} serves geometry already in vanilla's Y-down model frame and
     * {@link Turn#MIRROR_Z} the player's upright frame, the two sitting a {@link Turn#HALF_X} apart.
     * <p>
     * A face declaring no directional light keeps the full-bright {@code 1.0f} scalar, as it does under
     * {@link #relightForItems3d}. Nothing is snapped to a cardinal and nothing is packed onto vanilla's
     * signed-byte grid by {@link #packAsSnormByte}: the Lambertian is continuous in the normal, so a
     * rotated bone shades off its own direction rather than off the nearest cube face.
     * <p>
     * {@link Lighting.EntityLighting#shade} reads the cull flag to pick a face's camera-facing
     * orientation, so a two-sided surface is shaded by whichever of its two orientations points at the
     * camera - the {@code PER_FACE_LIGHTING} front / back choice vanilla's fragment shader makes by
     * winding. Positions, UVs, texture, tint, authored normal and traits are carried through untouched.
     *
     * @param triangles the kit-built triangles carrying baked shading
     * @param lighting the frame the two light directions are resolved through
     * @param intoKitFrame the turn carrying a triangle's normal into the shading frame
     * @return a new list of re-shaded triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> relightForEntityInUi(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull LightingFrame lighting,
        @NotNull Turn intoKitFrame
    ) {
        Lighting.EntityLighting basis = Lighting.resolveEntity(lighting);
        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle t : triangles) {
            float shading = t.traits().directionalLight()
                ? basis.shade(intoKitFrame.apply(t.normal()), t.traits().cullBackFaces())
                : 1.0f;
            out.add(new VisibleTriangle(
                t.position0(), t.position1(), t.position2(),
                t.uv0(), t.uv1(), t.uv2(),
                t.texture(), t.tintArgb(), t.normal(),
                shading, t.traits(), t.debugTag()
            ));
        }
        return out;
    }

    /**
     * Builds the matrix carrying a model normal into the frame a GUI relight shades against - the
     * {@code display.gui} pose rotation behind the PoseStack's {@code scale(W, -H, W)} Y-flip, with a
     * {@link LightingFrame.Mirror#HORIZONTAL} frame flipping the leading scale's X sign for a screen
     * left / right swap.
     * <p>
     * The frame both GUI relights shade in - {@link #relightForItems3d} and
     * {@code ShieldKit.relightShield}. It is <b>not</b> {@link Lighting#resolveEntity}'s chain, which
     * looks alike at the {@code mirrorX} line and differs everywhere that matters: that one carries
     * vanilla's camera-frame lights into the kit frame through five ops and ends
     * {@code scale(mirrorX, 1, -1)}, two signs away from the {@code scale(mirrorX, -1, 1)} here.
     * Unifying on the shared line is how those two signs come to be flipped without anybody deciding to.
     *
     * @param lighting the frame the transform is built from
     * @return the model-normal to shading-frame transform
     */
    public static @NotNull Matrix4f guiNormalTransform(@NotNull LightingFrame lighting) {
        EulerRotation rotation = lighting.rotation();
        float mirrorX = lighting.mirror() == LightingFrame.Mirror.HORIZONTAL ? -1f : 1f;
        return Matrix4f.IDENTITY
            .scale(mirrorX, -1f, 1f)
            .rotate(Quaternionf.rotationXYZ(
                rotation.pitchRadians(),
                rotation.yawRadians(),
                rotation.rollRadians()
            ));
    }

    /**
     * Replicates vanilla's {@code BufferBuilder.normalIntValue} byte-packing followed by
     * the shader's SNORM unpacking. Each component {@code c} is mapped to
     * {@code (int)(clamp(c, -1, 1) * 127.0F) / 127.0F}, with the integer cast truncating
     * toward zero (so {@code 0.6124 -> 77/127 = 0.6063}, not {@code 78/127 = 0.6142}).
     * The result is not unit length - vanilla's shader doesn't renormalize either.
     * <p>
     * The identity on an axis-aligned normal, every cardinal component landing exactly on the grid, so
     * it moves nothing on a cube face and quantizes visibly on a rotated one - a 45-degree plane's
     * {@code 0.7071} truncates to {@code 89/127}, about a percent low.
     *
     * @param n the raw normal
     * @return the normal with each component on vanilla's signed-byte grid
     */
    public static @NotNull Vector3f packAsSnormByte(@NotNull Vector3f n) {
        return new Vector3f(
            ((int) (Math.clamp(n.x(), -1f, 1f) * 127.0f)) / 127.0f,
            ((int) (Math.clamp(n.y(), -1f, 1f) * 127.0f)) / 127.0f,
            ((int) (Math.clamp(n.z(), -1f, 1f) * 127.0f)) / 127.0f
        );
    }

}
