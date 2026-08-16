package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.camera.LightingFrame;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.raster.PassDeclaration;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.CornerPhase;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import org.jetbrains.annotations.NotNull;

/**
 * Builds rasterizer-ready triangles for the vanilla {@code minecraft:shield} item's 3D model.
 * <p>
 * Vanilla renders the shield item through {@code ShieldSpecialRenderer} -&gt; the entity-style
 * {@code ShieldModel} (two cubes - a flat plate and a handle) at the item's {@code display.gui}
 * pose with {@code Lighting.ITEMS_3D}. The vanilla model authors its cubes in the entity Y-down
 * frame (64x64 texture atlas) with the special {@code transformation} {@code scale(1, -1, -1)}
 * applied around the whole model.
 * <p>
 * This kit bakes that special transform - which is a proper {@code 180}-degree rotation about X
 * ({@code det = +1}, so triangle winding is preserved) - directly into each box's axis-aligned
 * bounds, producing geometry in the same Y-up block-model frame {@link BlockGeometryKit} emits.
 * Per-face geometry winding comes from {@link CornerPhase#BAKERY} (so the block-icon
 * {@link Shading#relightForItems3d} pass and the shared rasterizer handle culling and
 * lighting unchanged), while per-face UV rectangles come from the vanilla entity-cube
 * {@link Unwrap.Atlas atlas unwrap} (so the single 64x64 texture maps onto the plate and handle the
 * way vanilla's {@code ModelPart.Cube} does). Mixing the two is not an anomaly - a corner phase and
 * an unwrap are independent choices, and this site needs one of each. The caller supplies the
 * {@code display.gui} pose, scale, and translation as the rasterizer's model transform.
 */
@UtilityClass
public class ShieldKit {

    /**
     * The shield model's texture atlas dimension in pixels (vanilla {@code ShieldModel} declares a
     * 64x64 {@code LayerDefinition}). Drives UV normalisation independent of the resolved texture's
     * actual resolution.
     */
    private static final float SHIELD_TEXTURE_SIZE = 64f;

    /**
     * Builds the plate + handle triangles for a plain (no-pattern) shield, textured with the
     * supplied {@code shield_base_nopattern} atlas. Output is in the Y-up block-model frame (the
     * special {@code scale(1, -1, -1)} already baked in); the caller applies the {@code display.gui}
     * pose, scale, and translation and runs {@link #relightShield}, which lights through
     * {@link Lighting#itemsFlat} rather than the block icon's {@code ITEMS_3D}.
     *
     * @param texture the resolved {@code entity/shield/shield_base_nopattern} atlas
     * @return the shield triangle list, ready for the relight + rasterize path
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildShield3D(@NotNull PixelBuffer texture) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        // Vanilla ShieldModel.createLayer():
        //   plate  = texOffs(0, 0).addBox(-6, -11, -2, 12, 22, 1)
        //   handle = texOffs(26, 0).addBox(-1, -3, -1, 2, 6, 6)
        addBox(triangles, texture, -6f, -11f, -2f, 12f, 22f, 1f, 0f, 0f);
        addBox(triangles, texture, -1f, -3f, -1f, 2f, 6f, 6f, 26f, 0f);
        return triangles;
    }

    /**
     * Re-shades the shield triangles with vanilla's {@code Lighting.Entry#ITEMS_FLAT} dual-light
     * Lambertian. Vanilla lights the shield's special {@code ShieldModel} with the {@code ITEMS_FLAT}
     * entry (not the block {@code ITEMS_3D} entry), so a camera-facing front face shades
     * near-full-bright (~0.93) while the side faces darken.
     * <p>
     * The normal is transformed through the same render frame the block-icon relight uses -
     * {@code scale(1, -1, 1) * R(display.gui)}, i.e. the {@code display.gui} pose plus the GUI
     * framebuffer's {@code scale(W, -H, W)} Y-flip - only the light directions differ
     * ({@link Lighting#itemsFlat} vs the {@code ITEMS_3D} variant). Measured
     * against the vanilla reference the front plate matches to ~0.004 and the {@code +X} edge to
     * ~0.002.
     * <p>
     * A {@link LightingFrame.Mirror#HORIZONTAL} frame negates the final normal's screen-X (a left /
     * right swap) by flipping the leading Y-flip's X sign, exactly as {@code Shading.relightForItems3d}
     * does; {@link LightingFrame.Mirror#NONE} is the plain pose-tracking relight, bit-for-bit.
     *
     * @param triangles the shield triangles from {@link #buildShield3D}
     * @param lighting the frame the shield lights through - the {@code display.gui} pose rotation and any mirror
     * @return a new list of re-shaded triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> relightShield(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull LightingFrame lighting
    ) {
        Matrix4f normalTransform = Shading.guiNormalTransform(lighting);
        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle t : triangles) {
            Vector3f rendered = t.normal().transformNormal(normalTransform).normalize();
            // Vanilla's signed-byte SNORM normal round-trip, which the shield needs and the block-icon
            // relight needs identically - the shield's plate and handle are axis-aligned, but the pose
            // rotation tilts every normal off its cardinal before this runs.
            Vector3f packed = Shading.packAsSnormByte(rendered);
            float shading = Lighting.itemsFlat(packed);
            out.add(new VisibleTriangle(
                t.position0(), t.position1(), t.position2(),
                t.uv0(), t.uv1(), t.uv2(),
                t.texture(), t.tintArgb(), t.normal(), shading,
                new SurfaceTraits(t.traits().cullBackFaces(), false, false, t.traits().directionalLight(),
                    PassDeclaration.DEFAULT.withEmissive(t.traits().pass().emissive()))
            ));
        }
        return out;
    }

    /**
     * Appends the twelve triangles (two per face) of one vanilla-authored cube to {@code out}.
     * Converts the cube from the entity Y-down frame to the block-model Y-up frame by applying the
     * special {@code scale(1, -1, -1)} (a {@code 180}-degree X rotation) and the {@code /16}
     * model-units normalisation to the axis-aligned bounds, then unwraps each block face's UV from
     * the matching vanilla entity face.
     *
     * @param out the triangle list to append to
     * @param texture the shield atlas
     * @param mx the cube origin X (vanilla entity-frame, pixels)
     * @param my the cube origin Y (vanilla entity-frame, pixels)
     * @param mz the cube origin Z (vanilla entity-frame, pixels)
     * @param sx the cube extent along X (pixels)
     * @param sy the cube extent along Y (pixels)
     * @param sz the cube extent along Z (pixels)
     * @param texU the cube's atlas U origin ({@code texOffs} U)
     * @param texV the cube's atlas V origin ({@code texOffs} V)
     */
    private static void addBox(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull PixelBuffer texture,
        float mx, float my, float mz,
        float sx, float sy, float sz,
        float texU, float texV
    ) {
        float units = BlockGeometryKit.VANILLA_PIXEL_UNITS_PER_BLOCK;
        // scale(1, -1, -1) negates Y and Z (flipping the min/max on those axes), then /16 lands the
        // bounds in the block-model frame. X passes through unchanged.
        float x0 = mx / units;
        float x1 = (mx + sx) / units;
        float y0 = -(my + sy) / units;
        float y1 = -my / units;
        float z0 = -(mz + sz) / units;
        float z1 = -mz / units;
        Box box = new Box(x0, y0, z0, x1, y1, z1);

        Vector2f texOffs = new Vector2f(texU, texV);
        Vector3f size = new Vector3f(sx, sy, sz);

        Unwrap.Atlas unwrap = new Unwrap.Atlas(texOffs, size, false);

        for (Face face : Face.CACHED_VALUES) {
            Vector4f rect = unwrap.rect(Turn.HALF_X.apply(face));
            Vector2f[] uv = CornerPhase.BAKERY.permuteUv(
                face, rect.toUvCorners(SHIELD_TEXTURE_SIZE, SHIELD_TEXTURE_SIZE, 0, false));
            Vector3f[] corners = CornerPhase.BAKERY.corners(face, box);
            Vector3f normal = face.normal();
            // Baked here for the rasterizer's contract; relightForItems3d recomputes it from the
            // ITEMS_3D lights, so the value only needs to be a valid placeholder.
            float shading = Lighting.inventory(normal);
            BlockGeometryKit.addQuad(out, corners, uv,
                texture, ColorMath.WHITE, normal, shading, SurfaceTraits.OPAQUE_BODY, null);
        }
    }

}
