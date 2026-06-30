package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.EntityFace;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.SurfaceTraits;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lombok.experimental.UtilityClass;
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
 * Per-face geometry winding and normals come from {@link BlockFace} (so the block-icon
 * {@link Shading#relightForItems3d} pass and the shared rasterizer handle culling and
 * lighting unchanged), while per-face UV rectangles come from the vanilla entity-cube atlas
 * unwrap on {@link EntityFace} (so the single 64x64 texture maps onto the plate and handle the
 * way vanilla's {@code ModelPart.Cube} does). The caller supplies the {@code display.gui} pose,
 * scale, and translation as the rasterizer's model transform.
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
     * pose, scale, and translation and runs the {@link Shading#relightForItems3d} pass.
     *
     * @param texture the resolved {@code entity/shield/shield_base_nopattern} atlas
     * @return the shield triangle list, ready for the block-icon relight + rasterize path
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
     *
     * @param triangles the shield triangles from {@link #buildShield3D}
     * @param guiRotation the shield item model's {@code display.gui} pose
     * @return a new list of re-shaded triangles
     */
    public static @NotNull ConcurrentList<VisibleTriangle> relightShield(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull EulerRotation guiRotation
    ) {
        Matrix4f normalTransform = Matrix4f.IDENTITY
            .scale(1f, -1f, 1f)
            .rotate(Quaternionf.rotationXYZ(
                guiRotation.pitchRadians(), guiRotation.yawRadians(), guiRotation.rollRadians()));
        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle t : triangles) {
            Vector3f rendered = t.normal().transformNormal(normalTransform).normalize();
            // Match vanilla's signed-byte SNORM normal round-trip (see Shading.packAsSnormByte).
            Vector3f packed = new Vector3f(snorm(rendered.x()), snorm(rendered.y()), snorm(rendered.z()));
            float shading = Lighting.itemsFlat(packed);
            out.add(new VisibleTriangle(
                t.position0(), t.position1(), t.position2(),
                t.uv0(), t.uv1(), t.uv2(),
                t.texture(), t.tintArgb(), t.normal(), shading,
                new SurfaceTraits(t.traits().cullBackFaces(), t.traits().emissive(), false, false)
            ));
        }
        return out;
    }

    private static float snorm(float component) {
        return ((int) (Math.clamp(component, -1f, 1f) * 127f)) / 127f;
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

        for (BlockFace face : BlockFace.CACHED_VALUES) {
            Vector4f rect = entityFaceFor(face).defaultUv(texOffs, size);
            Vector2f[] uv = rect.toUvCorners(SHIELD_TEXTURE_SIZE, SHIELD_TEXTURE_SIZE, 0, false);
            Vector3f[] corners = face.corners(box);
            Vector3f normal = face.normal();
            // Baked here for the rasterizer's contract; relightForItems3d recomputes it from the
            // ITEMS_3D lights, so the value only needs to be a valid placeholder.
            float shading = Lighting.inventory(normal);
            out.add(new VisibleTriangle(
                corners[0], corners[1], corners[2],
                uv[0], uv[1], uv[2],
                texture, ColorMath.WHITE, normal, shading, SurfaceTraits.OPAQUE_BODY
            ));
            out.add(new VisibleTriangle(
                corners[0], corners[2], corners[3],
                uv[0], uv[2], uv[3],
                texture, ColorMath.WHITE, normal, shading, SurfaceTraits.OPAQUE_BODY
            ));
        }
    }

    /**
     * Maps a block-model face to the vanilla entity-cube face that supplies its UV strip, after the
     * special {@code scale(1, -1, -1)} ({@code 180}-degree X rotation) baked into the geometry. The
     * X rotation swaps front/back ({@code NORTH}/{@code SOUTH}) and top/bottom ({@code UP}/
     * {@code DOWN}) while leaving the {@code EAST}/{@code WEST} sides on their own strip.
     */
    private static @NotNull EntityFace entityFaceFor(@NotNull BlockFace face) {
        return switch (face) {
            case SOUTH -> EntityFace.NORTH;
            case NORTH -> EntityFace.SOUTH;
            case UP -> EntityFace.DOWN;
            case DOWN -> EntityFace.UP;
            case EAST -> EntityFace.EAST;
            case WEST -> EntityFace.WEST;
        };
    }

}
