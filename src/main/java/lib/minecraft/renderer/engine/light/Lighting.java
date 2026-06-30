package lib.minecraft.renderer.engine.light;

import lib.minecraft.renderer.engine.camera.Camera;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Vanilla-parity inventory lighting: the pre-rotated diffuse light directions and the
 * {@code light.glsl#minecraft_mix_light_separate} dual-light Lambertian that bakes a per-face or
 * per-vertex shade scalar at kit-build time. Four entries reproduce vanilla's
 * {@code Lighting.Entry} setups - {@code ITEMS_3D} (block-in-inventory icon), {@code ENTITY_IN_UI}
 * (mob portraits), {@code ITEMS_FLAT} (3D special-model items), and the four-cardinal-bucket
 * block/fluid approximation via {@link BlockFace}.
 *
 * @see Shading
 */
@UtilityClass
public class Lighting {

    // --- block-icon inventory lighting constants (vanilla Lighting.Entry.ITEMS_3D parity) ---

    /**
     * First {@code Lighting.Entry.ITEMS_3D} light direction, pre-rotated by vanilla's
     * {@code item3DPose} chain. Bit-exact FP32 values copied from JOML's
     * {@code new Matrix4f().scaling(1,-1,1).rotateYXZ(1.0821041, 3.2375858, 0).rotateYXZ(-pi/8,
     * 3pi/4, 0).transformDirection(normalize(0.2, 1, -0.7))} - which is precisely what vanilla
     * uploads to the Lighting UBO at startup. Hardcoded as bit patterns so we don't depend
     * on JOML at runtime and don't drift from vanilla due to differences between JOML's direct
     * cos/sin matrix path and our Quaternionf-routed equivalent (the latter loses ~4 ULPs on
     * the Y component of L0, enough to flip rounding at the LEFT face's 0.6489 shade boundary
     * on the gunpowder gray texel).
     * <p>
     * Vanilla source: {@code blaze3d/platform/Lighting.java} item3DPose chain.
     */
    public static final @NotNull Vector3f BLOCK_ITEMS_3D_LIGHT_0 = new Vector3f(
        Float.intBitsToFloat(0xBF6EF5DF),  // -0.9334391952
        Float.intBitsToFloat(0xBE867FEC),  // -0.2626947165
        Float.intBitsToFloat(0xBE7A29D2)   // -0.2443001568
    );

    /**
     * Second {@code Lighting.Entry.ITEMS_3D} light direction; pre-rotated by the same
     * {@code item3DPose} chain as {@link #BLOCK_ITEMS_3D_LIGHT_0} from vanilla's
     * {@code DIFFUSE_LIGHT_1 = normalize(-0.2, 1, 0.7)}. Bit-exact FP32 values from JOML.
     */
    public static final @NotNull Vector3f BLOCK_ITEMS_3D_LIGHT_1 = new Vector3f(
        Float.intBitsToFloat(0xBDD41D3A),  // -0.1035713702
        Float.intBitsToFloat(0xBF7A02E7),  // -0.9766067863
        Float.intBitsToFloat(0x3E40F819)   //  0.1884464175
    );

    // --- entity inventory lighting constants (vanilla Lighting.ENTITY_IN_UI parity) ---

    /**
     * First diffuse light direction for vanilla's {@code Lighting.Entry.ENTITY_IN_UI} entry,
     * pre-rotated by vanilla's iso transform chain so the kit-time dot product against a
     * kit-frame (post-Y-flip, pre-engine-camera) bone-chain normal gives the same shade as
     * vanilla's fragment-shader dot against a post-camera-frame normal.
     * <p>
     * Vanilla source: {@code INVENTORY_DIFFUSE_LIGHT_0 = normalize(0.2, -1, 1)} in camera frame
     * (post-iso, Y-down). Our kit dots lights against a normal that is only Y-flipped (not iso-
     * rotated). Solving:
     * <pre>
     * dot(L_kit, diag(1,-1,1) &times; n_model) = dot(L_camera, M_view &times; n_model)  for all n_model
     * </pre>
     * yields {@code L_kit = diag(1,-1,1) × M_view^T × L_camera}, where {@code M_view =
     * scale(1,1,-1) × R_X(210°) × R_Y(45°) × R_X(180°)} (col-form) is the harness LER chain.
     * Verified to give identical shade on all six cardinal-axis normals (cod-style entities). For
     * rotated bones the dot agrees per-vertex with vanilla's post-iso fragment shader, removing
     * the per-quadrant signed-luma signature that lingered after A1. Pairs with
     * {@link Camera#forEntityIcon}'s camera chain.
     * <p>
     * The previous value {@code normalize(0.2, 1, 1)} was a naive Y-flip of vanilla's source
     * (matched +Y and -Y axes exactly but diverged 0.04 / 0.07 / 0.13 / 0.17 on &plusmn;X / &plusmn;Z). A
     * Round 8 attempt at this fix regressed cardinal-shaded entities; root cause unverified,
     * but the per-face math has since been re-derived from scratch against the post-A1 chain
     * and the cardinal-axis match is now bit-stable.
     *
     * @see <a href="https://github.com/Mojang/blaze3d/blob/main/src/main/java/com/mojang/blaze3d/platform/Lighting.java">com.mojang.blaze3d.platform.Lighting</a>
     */
    public static final @NotNull Vector3f ENTITY_IN_UI_LIGHT_0 = calibrateEntityLight(
        deriveEntityInUiLightKit(0.2f, -1f, 1f),
        0,
        0f,
        0.0015f,
        0f
    );

    /**
     * Second diffuse light direction; pre-rotated by the same {@code diag(1,-1,1) × M_view^T} as
     * {@link #ENTITY_IN_UI_LIGHT_0} from vanilla's {@code INVENTORY_DIFFUSE_LIGHT_1 =
     * normalize(-0.2, -1, 0)}, plus the same empirical GPU calibration.
     */
    public static final @NotNull Vector3f ENTITY_IN_UI_LIGHT_1 = calibrateEntityLight(
        deriveEntityInUiLightKit(-0.2f, -1f, 0f),
        1,
        0f,
        0f,
        0.005f
    );

    /**
     * Applies a small empirical GPU-calibration offset (plus any {@code -Dasset.entity.L<idx>d{x,y,z}}
     * sweep override) to a derived kit-frame light direction, then re-normalises.
     * <p>
     * The lighting GLSL formula and the raw {@code INVENTORY_DIFFUSE_LIGHT} directions are
     * bit-matched to vanilla, and {@link #entityInUi} reproduces the ideal Lambertian
     * shade exactly. But vanilla rasterises on the GPU and we on the CPU, so the per-face shade still
     * drifts ~0.003 from the harness - invisible on dark textures, but {@code +/-1} channel across
     * near-white entities (goat 0.63, copper_golem, husk, illager family, pig). A fleet sweep
     * (tunable via the {@code -Dasset.entity.L<idx>d{x,y,z}} knobs this method reads, forwarded to the
     * parity fork) found that nudging {@code L0.y} by {@code +0.0015} and {@code L1.z} by
     * {@code +0.005} in kit frame pulls the per-face shades toward the GPU output: 58 entities
     * improved, 5 within-bucket regressions, goat {@code 0.63 -> 0.48}, entity buckets
     * {@code 88/98/99/100 -> 88/99/99/100}. Block lighting uses its own
     * {@link #BLOCK_ITEMS_3D_LIGHT_0 ITEMS_3D} directions and is unaffected. The knobs default to 0
     * so the production lights are the baked calibration; pass overrides to re-sweep.
     */
    private static @NotNull Vector3f calibrateEntityLight(@NotNull Vector3f light, int idx, float baseDx, float baseDy, float baseDz) {
        float dx = baseDx + Float.parseFloat(System.getProperty("asset.entity.L" + idx + "dx", "0"));
        float dy = baseDy + Float.parseFloat(System.getProperty("asset.entity.L" + idx + "dy", "0"));
        float dz = baseDz + Float.parseFloat(System.getProperty("asset.entity.L" + idx + "dz", "0"));
        if (dx == 0f && dy == 0f && dz == 0f) return light;
        return new Vector3f(light.x() + dx, light.y() + dy, light.z() + dz).normalize();
    }

    /**
     * Diffuse contribution scale matching vanilla's GLSL {@code MINECRAFT_LIGHT_POWER} constant.
     * The shader uses the value to scale the dot-product sum before the ambient floor is added.
     */
    public static final float MINECRAFT_LIGHT_POWER = 0.6f;

    /**
     * Constant ambient contribution matching vanilla's GLSL {@code MINECRAFT_AMBIENT_LIGHT}
     * constant. Sets the floor brightness when both diffuse dot products clamp to zero.
     */
    public static final float MINECRAFT_AMBIENT_LIGHT = 0.4f;

    // --- inventory lighting ---

    /**
     * Computes the per-face shade factor for a world-space surface normal under vanilla's
     * standard {@code [30, 225, 0]} GUI pose. Delegates to {@link BlockFace#fromNormal} to pick
     * the dominant cardinal face and returns that face's pre-baked
     * {@link BlockFace#lighting() lighting} factor. See {@link BlockFace}'s class-level doc for
     * the rationale behind the reversed E/W vs N/S values (vanilla {@code Lighting.ITEMS_3D}
     * uses two directional lights offset in X, inverting world-block brightness).
     * <p>
     * Used by block + fluid kits to bake a per-triangle shading scalar at geometry-build time;
     * the rasterizer then applies the result to the sampled texel. Entity rendering uses
     * {@link #entityInUi} instead so the output matches vanilla's
     * {@code Lighting.ENTITY_IN_UI} dual-light shader rather than the four-cardinal-bucket
     * approximation.
     *
     * @param normal the world-space surface normal (should be normalized)
     * @return the shade factor for the face that best matches the normal
     */
    public static float inventory(@NotNull Vector3f normal) {
        return BlockFace.fromNormal(normal).lighting();
    }

    // --- entity inventory lighting (vanilla Lighting.ENTITY_IN_UI parity) ---

    /**
     * Derives a kit-frame diffuse light direction from a vanilla camera-frame
     * {@code INVENTORY_DIFFUSE_LIGHT_N = normalize(x, y, z)} literal, using the same Matrix4f
     * chain the per-vertex shader composes for the iso pose. The result is bit-identical to
     * {@code diag(1,-1,1) × M_view^T × L_camera} computed via our column-vector
     * {@link Matrix4f} / {@link Quaternionf} ops - matching whatever sub-ULP drift our matrix
     * math has against vanilla's per-vertex GLSL chain. Replaces the 6-decimal hardcoded
     * constants with values produced by the same float chain that runs at render-time.
     */
    private static @NotNull Vector3f deriveEntityInUiLightKit(float cameraX, float cameraY, float cameraZ) {
        // L_camera_normalized via our Vector3f.normalize (same code path as runtime normals)
        Vector3f lCamera = new Vector3f(cameraX, cameraY, cameraZ).normalize();

        // M_view = scale(1,1,-1) × R_X(210°) × R_Y(45°) × R_X(180°) col-form
        // M_view^T = R_X(-180°) × R_Y(-45°) × R_X(-210°) × scale(1,1,-1)
        // diag(1,-1,1) × M_view^T = diag(1,-1,1) × (above)
        // Built via fluent ops to match vanilla's PoseStack composition exactly.
        Matrix4f viewToKit = Matrix4f.IDENTITY
            .scale(1f, -1f, 1f)
            .rotate(Quaternionf.rotationXYZ((float) -Math.PI, 0f, 0f))
            .rotate(Quaternionf.rotationXYZ(0f, (float) Math.toRadians(-45.0), 0f))
            .rotate(Quaternionf.rotationXYZ((float) Math.toRadians(-210.0), 0f, 0f))
            .scale(1f, 1f, -1f);
        Vector3f kitDir = lCamera.transformNormal(viewToKit);
        return kitDir.normalize();
    }

    /**
     * Computes the dual-directional Lambertian shade factor for a world-space surface normal
     * under vanilla's {@code Lighting.Entry#ENTITY_IN_UI} entry - the lighting setup used for
     * mob portraits in containers and the inventory screen. Implements vanilla's
     * {@code light.glsl#minecraft_mix_light_separate} verbatim:
     * <pre>
     * shading = min(1, (max(0, dot(L0, n)) + max(0, dot(L1, n))) * 0.6 + 0.4)
     * </pre>
     * <p>
     * Two directional lights provide diffuse contributions (clamped at zero so back-facing
     * surfaces do not subtract); their sum is scaled by {@link #MINECRAFT_LIGHT_POWER} and added
     * to {@link #MINECRAFT_AMBIENT_LIGHT}, then clamped to {@code [0, 1]}. The result is
     * <b>continuous in the surface normal</b> rather than bucketed to one of six cardinal-face
     * constants - critical for matching the refharness output on rotated bones (running zombie
     * legs, leashed bees, etc.) where the normal is no longer axis-aligned and a per-face lookup
     * collapses neighbouring faces to the same shade.
     *
     * @param normal the world-space surface normal (should be normalized)
     * @return the shade factor in {@code [0.4, 1.0]} - never below ambient, never above unity
     */
    public static float entityInUi(@NotNull Vector3f normal) {
        float dot0 = Math.max(0f, ENTITY_IN_UI_LIGHT_0.dot(normal));
        float dot1 = Math.max(0f, ENTITY_IN_UI_LIGHT_1.dot(normal));
        return Math.min(1f, (dot0 + dot1) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
    }

    // --- block-icon inventory lighting (vanilla Lighting.Entry.ITEMS_3D parity) ---

    /**
     * Computes the dual-directional Lambertian shade factor for a render-frame surface normal
     * under vanilla's {@code Lighting.Entry#ITEMS_3D} entry - the lighting setup used for the
     * block-in-inventory icon. Implements vanilla's
     * {@code light.glsl#minecraft_mix_light_separate} verbatim with the
     * {@link #BLOCK_ITEMS_3D_LIGHT_0} / {@link #BLOCK_ITEMS_3D_LIGHT_1} pre-rotated lights.
     * <p>
     * The {@code normal} argument must already be in render frame - rotated through the
     * block's {@code display.gui} pose and Y-flipped to match vanilla's PoseStack
     * {@code scale(W, -H, W)}. Callers in {@link lib.minecraft.renderer.BlockRenderer
     * BlockRenderer.Isometric3D} compose this transform with the block's per-model gui rotation
     * before calling.
     *
     * @param normal the render-frame surface normal (should be normalized)
     * @return the shade factor in {@code [0.4, 1.0]} - never below ambient, never above unity
     */
    public static float blockItems3d(@NotNull Vector3f normal) {
        float dot0 = Math.max(0f, BLOCK_ITEMS_3D_LIGHT_0.dot(normal));
        float dot1 = Math.max(0f, BLOCK_ITEMS_3D_LIGHT_1.dot(normal));
        return Math.min(1f, (dot0 + dot1) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
    }

    // --- flat-item inventory lighting (vanilla Lighting.Entry.ITEMS_FLAT parity) ---

    /**
     * First {@code Lighting.Entry.ITEMS_FLAT} light direction: vanilla's
     * {@code DIFFUSE_LIGHT_0 = normalize(0.2, 1, -0.7)} transformed by the flat-item pose
     * {@code new Matrix4f().rotationY(-pi/8).rotateX(3pi/4)} (the
     * {@code rotationY(-0.3926991f).rotateX(2.3561945f)} chain in
     * {@code com.mojang.blaze3d.platform.Lighting}'s static initialiser).
     * <p>
     * Vanilla lights 3D <b>special-model</b> items (the shield's {@code ShieldModel}) with this
     * {@code ITEMS_FLAT} entry rather than the {@code ITEMS_3D} entry used for block-as-item icons,
     * so a camera-facing front face shades near full-bright while the side faces darken.
     */
    public static final @NotNull Vector3f ITEMS_FLAT_LIGHT_0 = deriveFlatItemLight(0.2f, 1.0f, -0.7f);

    /**
     * Second {@code Lighting.Entry.ITEMS_FLAT} light direction; vanilla's
     * {@code DIFFUSE_LIGHT_1 = normalize(-0.2, 1, 0.7)} under the same flat-item pose as
     * {@link #ITEMS_FLAT_LIGHT_0}.
     */
    public static final @NotNull Vector3f ITEMS_FLAT_LIGHT_1 = deriveFlatItemLight(-0.2f, 1.0f, 0.7f);

    /**
     * Transforms a raw vanilla {@code DIFFUSE_LIGHT} direction by the {@code ITEMS_FLAT} pose
     * {@code rotationY(-0.3926991) * rotateX(2.3561945)} and re-normalises, reproducing the light
     * direction vanilla uploads to the Lighting UBO for the flat-item entry.
     */
    private static @NotNull Vector3f deriveFlatItemLight(float x, float y, float z) {
        Vector3f raw = new Vector3f(x, y, z).normalize();
        Matrix4f pose = Matrix4f.IDENTITY
            .rotate(Quaternionf.rotationXYZ(0f, -0.3926991f, 0f))
            .rotate(Quaternionf.rotationXYZ(2.3561945f, 0f, 0f));
        return raw.transformNormal(pose).normalize();
    }

    /**
     * Computes the dual-directional Lambertian shade factor for a render-frame surface normal
     * under vanilla's {@code Lighting.Entry#ITEMS_FLAT} entry, with the same
     * {@code light.glsl#minecraft_mix_light_separate} formula as
     * {@link #blockItems3d}. The {@code normal} must already be in render frame -
     * transformed through the item's {@code display.gui} pose and the GUI PoseStack's
     * {@code scale(W, -H, W)} Y-flip.
     *
     * @param normal the render-frame surface normal (should be normalized)
     * @return the shade factor in {@code [0.4, 1.0]}
     */
    public static float itemsFlat(@NotNull Vector3f normal) {
        float dot0 = Math.max(0f, ITEMS_FLAT_LIGHT_0.dot(normal));
        float dot1 = Math.max(0f, ITEMS_FLAT_LIGHT_1.dot(normal));
        return Math.min(1f, (dot0 + dot1) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
    }

}
