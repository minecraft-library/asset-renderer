package lib.minecraft.renderer.bake.mesh;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.bake.texture.BannerKit;
import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.draw.SurfaceTraits;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.AxisSigns;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.geometry.CornerPhase;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.geometry.FaceTextures;
import lib.minecraft.renderer.engine.geometry.ModelUnits;
import lib.minecraft.renderer.engine.geometry.Unwrap;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.light.LightingFrame;
import lib.minecraft.renderer.engine.light.Shading;
import lib.minecraft.renderer.engine.mesh.BoxKit;
import lib.minecraft.renderer.engine.raster.Rasterizer;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Vector2f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.math.Vector4f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.vanilla.DyeColor;
import lib.minecraft.renderer.vanilla.mesh.ShieldMesh;
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
 * an unwrap are independent choices, and this site needs one of each. The kit holds that
 * {@code display.gui} pose itself, so {@link #renderShield3D} is one call over a buffer and a tick.
 * <p>
 * A patterned banner or shield does not go through the {@code ShieldModel} at all:
 * {@link #buildBannerOrShield3D} carries the {@link BannerKit} composite onto the flat item slab, the
 * same slab a flat-sprite item's held view is drawn on.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class ShieldKit {

    /**
     * The shield's plain (no banner pattern) base texture id - the atlas the 3D
     * {@code ShieldModel} samples for an undyed shield.
     */
    private static final @NotNull String SHIELD_NOPATTERN_TEXTURE_ID = "minecraft:entity/shield/shield_base_nopattern";

    /**
     * The shield item model's {@code display.gui} rotation ({@code [15, -25, -5]} pitch/yaw/roll).
     */
    private static final @NotNull EulerRotation SHIELD_GUI_ROTATION = new EulerRotation(15f, -25f, -5f);

    /**
     * The shield item model's {@code display.gui} scale ({@code 0.65}).
     */
    private static final float SHIELD_GUI_DISPLAY_SCALE = 0.65f;

    /**
     * Model-space translation (block units) that aligns the rendered shield's silhouette 1:1 with
     * the vanilla reference. The shield's silhouette is the same size as vanilla's
     * (270x489 px at the parity render size) but the vanilla GUI item pipeline seats the model
     * origin off-centre relative to this renderer's centre-on-origin projection; this offset is
     * {@code R^T} (the inverse {@code display.gui} rotation) applied to the measured
     * {@code (-29, -9)} px screen offset, so {@code camera * translate(offset)} reproduces it as a
     * pure post-rotation screen shift.
     */
    private static final @NotNull Vector3f SHIELD_ALIGN_OFFSET = new Vector3f(-0.0839f, 0.0189f, 0.0305f);

    /**
     * Pure-orthographic projection for the GUI shield render. The projection scale is the shield
     * item model's {@code display.gui} scale ({@code 0.65}), mirroring how the block-icon path
     * folds {@code block/block.json}'s {@code 0.625} {@code display.gui.scale} into
     * {@link Lens#ISOMETRIC_BLOCK}.
     */
    private static final @NotNull Lens SHIELD_PERSPECTIVE = Lens.orthographic(SHIELD_GUI_DISPLAY_SCALE);

    /**
     * The GUI shield's camera - {@link #SHIELD_GUI_ROTATION} through {@link #SHIELD_PERSPECTIVE}.
     * Built once rather than per render: {@link Camera#fromPose} runs six trig evaluations to
     * assemble the pose quaternion, and both of its inputs are constants.
     */
    private static final @NotNull Camera SHIELD_CAMERA = Camera.fromPose(SHIELD_GUI_ROTATION, SHIELD_PERSPECTIVE);

    /**
     * The GUI shield's lighting frame, tracking its own {@code display.gui} rotation so the plate is
     * lit from the direction it is viewed from.
     */
    private static final @NotNull LightingFrame SHIELD_LIGHTING = LightingFrame.tracking(SHIELD_GUI_ROTATION);

    /**
     * The GUI shield's model transform - {@link #SHIELD_ALIGN_OFFSET} as a pure translation, which
     * the camera then turns into the measured post-rotation screen shift.
     */
    private static final @NotNull Matrix4f SHIELD_MODEL_TRANSFORM = Matrix4f.IDENTITY.translate(
        SHIELD_ALIGN_OFFSET.x(), SHIELD_ALIGN_OFFSET.y(), SHIELD_ALIGN_OFFSET.z());

    /**
     * The flat-sprite item slab in model space (block units) - a {@code 0.9}-block square on X and Y,
     * {@code 0.04} deep on Z. The square face is what carries the sprite; the depth is what stops it
     * being a zero-thickness plane.
     */
    public static final @NotNull Box FLAT_ITEM_SLAB = new Box(-0.45f, -0.45f, -0.02f, 0.45f, 0.45f, 0.02f);

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
        addBox(triangles, texture, ShieldMesh.PLATE);
        addBox(triangles, texture, ShieldMesh.HANDLE);
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
        return triangles.stream()
            .map(t -> {
                Vector3f rendered = t.normal().transformNormal(normalTransform).normalize();
                // Vanilla's signed-byte SNORM normal round-trip, which the shield needs and the
                // block-icon relight needs identically - the shield's plate and handle are
                // axis-aligned, but the pose rotation tilts every normal off its cardinal first.
                Vector3f packed = Shading.packAsSnormByte(rendered);
                float shading = Lighting.itemsFlat(packed);
                return new VisibleTriangle(
                    t.position0(), t.position1(), t.position2(),
                    t.uv0(), t.uv1(), t.uv2(),
                    t.texture(), t.tintArgb(), t.normal(), shading,
                    new SurfaceTraits(t.traits().cullBackFaces(), false, false, t.traits().directionalLight(),
                        PassDeclaration.DEFAULT.withEmissive(t.traits().pass().emissive()))
                );
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Renders the plain {@code minecraft:shield} item as its vanilla 3D {@code ShieldModel} into
     * {@code buffer}. Mirrors the block-icon path ({@code BlockRenderer.Isometric3D}):
     * {@link #buildShield3D} builds the plate + handle geometry, the {@code display.gui} pose drives
     * a {@code T * R * S} model transform (translation, then the {@code [15, -25, -5]} rotation, then
     * the {@code 0.65} scale - vanilla's {@code ItemTransform.apply} order), and
     * {@link Shading#relightForItems3d} re-shades each face with vanilla's {@code Lighting.ITEMS_3D}
     * Lambertian. Rendered through an identity-camera {@link Rasterizer} so the pose lives entirely
     * in the model transform.
     *
     * @param context the renderer context for texture resolution
     * @param buffer the output buffer (the freshly created GUI buffer the shared tail consumes)
     * @param options the render options, read for what an absent shield base texture means
     * @param tick the animation tick the shield base texture is sampled at
     */
    public static void renderShield3D(
        @NotNull RendererContext context,
        @NotNull PixelBuffer buffer,
        @NotNull ItemOptions options,
        int tick
    ) {
        Rasterizer engine = new Rasterizer(SHIELD_CAMERA);
        RendererContext textures = options.isSubstituteMissing()
            ? context.withMissingTexture()
            : context;
        PixelBuffer texture = Flipbook.atTick(textures.resolveTexture(SHIELD_NOPATTERN_TEXTURE_ID), textures.findFlipbook(SHIELD_NOPATTERN_TEXTURE_ID), tick)
            .orElseThrow(() -> new RenderException("No texture registered for id '%s'", SHIELD_NOPATTERN_TEXTURE_ID));
        ConcurrentList<VisibleTriangle> triangles = buildShield3D(texture);
        triangles = relightShield(triangles, SHIELD_LIGHTING);

        engine.rasterize(triangles, buffer, SHIELD_MODEL_TRANSFORM);
    }

    /**
     * Composites a fresh banner / shield texture via {@link BannerKit#composite2D} and folds it
     * into the 3D held-item render path. Banners and shields both fall back to a thin-Z-slab using
     * the composited texture so the HELD_3D view reflects the pattern stack. Using the composited
     * texture for all six slab faces mirrors the flat-sprite fallback already used for other item
     * kinds.
     *
     * @param context the renderer context that resolves the pattern textures
     * @param itemId the item id (used to pick the banner vs. shield atlas variant)
     * @param options the render options carrying {@code baseDye} + {@code bannerLayers}
     * @return the list of triangles ready for rasterisation
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildBannerOrShield3D(
        @NotNull RendererContext context,
        @NotNull String itemId,
        @NotNull ItemOptions options
    ) {
        DyeColor baseDye = options.getDecoration().getBaseDye().orElse(DyeColor.Vanilla.WHITE);
        boolean isShield = itemId.equals(BannerKit.SHIELD_ITEM_ID);
        BannerKit.Variant variant = isShield
            ? BannerKit.Variant.SHIELD_BLOCK_3D
            : BannerKit.Variant.BANNER_BLOCK_3D;

        PixelBuffer composite = BannerKit.composite2D(context, baseDye.argb(), options.getDecoration().getBannerLayers(), variant);

        return BoxKit.buildBox(
            FLAT_ITEM_SLAB,
            FaceTextures.uniform(composite),
            ColorMath.WHITE
        );
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
     * @param cube the vanilla-authored cube to emit
     */
    private static void addBox(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull PixelBuffer texture,
        @NotNull ShieldMesh.Cube cube
    ) {
        float mx = cube.originX(), my = cube.originY(), mz = cube.originZ();
        float sx = cube.sizeX(), sy = cube.sizeY(), sz = cube.sizeZ();
        float units = ModelUnits.PIXELS_PER_BLOCK;
        // scale(1, -1, -1) negates Y and Z (flipping the min/max on those axes), then /16 lands the
        // bounds in the block-model frame. X passes through unchanged.
        float x0 = mx / units;
        float x1 = (mx + sx) / units;
        float y0 = -(my + sy) / units;
        float y1 = -my / units;
        float z0 = -(mz + sz) / units;
        float z1 = -mz / units;
        Box box = new Box(x0, y0, z0, x1, y1, z1);

        Vector2f texOffs = new Vector2f(cube.texU(), cube.texV());
        Vector3f size = new Vector3f(sx, sy, sz);

        Unwrap.Atlas unwrap = new Unwrap.Atlas(texOffs, size, false);

        Face.forEach(face -> {
            Vector4f rect = unwrap.rect(AxisSigns.HALF_X.apply(face));
            Vector2f[] uv = CornerPhase.BAKERY.permuteUv(
                face, rect.toUvCorners(ShieldMesh.TEXTURE_SIZE, ShieldMesh.TEXTURE_SIZE, 0, false));
            Vector3f[] corners = CornerPhase.BAKERY.corners(face, box);
            Vector3f normal = face.normal();
            // Baked here for the rasterizer's contract; relightForItems3d recomputes it from the
            // ITEMS_3D lights, so the value only needs to be a valid placeholder.
            float shading = Lighting.inventory(normal);
            BoxKit.addQuad(out, corners, uv,
                texture, ColorMath.WHITE, normal, shading, SurfaceTraits.OPAQUE_BODY, null);
        });
    }

}
