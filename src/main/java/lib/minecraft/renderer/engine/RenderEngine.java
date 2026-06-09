package lib.minecraft.renderer.engine;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.kit.BlockGeometryKit;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

/**
 * Baseline contract and shared static helpers for every rendering engine.
 *
 * <p>Every method that does not require instance state is bundled here as a {@code static} so
 * every concrete engine has direct access to:
 * <ul>
 *   <li><b>Projection</b> - {@code projectOrtho} and {@code projectPerspective} for the
 *       camera-to-screen transform.</li>
 *   <li><b>Lighting</b> - the {@code computeInventoryLighting} / {@code computeEntityInUiLighting}
 *       helpers that bake the vanilla {@code Lighting.Entry} dot product per-face or per-vertex
 *       at kit-build time, plus the precomputed {@link #ENTITY_IN_UI_LIGHT_0} and
 *       {@link #ENTITY_IN_UI_LIGHT_1} constants that drive entity rendering parity against the
 *       vanilla-reference harness.</li>
 *   <li><b>Shading</b> - {@code applyShading} multiplies a precomputed scalar into each ARGB
 *       channel with round-half-up matching vanilla GLSL.</li>
 *   <li><b>Output building</b> - {@code buildStaticOutput} / {@code buildAnimatedOutput} that
 *       wrap a {@link PixelBuffer} into the final
 *       {@link ImageData ImageData} record.</li>
 * </ul>
 *
 * <p>Instance state (pack resolution, biome sampling, etc.) lives on subclasses starting with
 * {@link TextureEngine}.
 *
 * @see TextureEngine
 * @see ModelEngine
 */
public interface RenderEngine {

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
    Vector3f BLOCK_ITEMS_3D_LIGHT_0 = new Vector3f(
        Float.intBitsToFloat(0xBF6EF5DF),  // -0.9334391952
        Float.intBitsToFloat(0xBE867FEC),  // -0.2626947165
        Float.intBitsToFloat(0xBE7A29D2)   // -0.2443001568
    );

    /**
     * Second {@code Lighting.Entry.ITEMS_3D} light direction; pre-rotated by the same
     * {@code item3DPose} chain as {@link #BLOCK_ITEMS_3D_LIGHT_0} from vanilla's
     * {@code DIFFUSE_LIGHT_1 = normalize(-0.2, 1, 0.7)}. Bit-exact FP32 values from JOML.
     */
    Vector3f BLOCK_ITEMS_3D_LIGHT_1 = new Vector3f(
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
     * dot(L_kit, diag(1,-1,1) × n_model) = dot(L_camera, M_view × n_model)  for all n_model
     * </pre>
     * yields {@code L_kit = diag(1,-1,1) × M_view^T × L_camera}, where {@code M_view =
     * scale(1,1,-1) × R_X(210°) × R_Y(45°) × R_X(180°)} (col-form) is the harness LER chain.
     * Verified to give identical shade on all six cardinal-axis normals (cod-style entities). For
     * rotated bones the dot agrees per-vertex with vanilla's post-iso fragment shader, removing
     * the per-quadrant signed-luma signature that lingered after A1. Pairs with
     * {@link IsometricEngine#forEntityIcon}'s camera chain.
     * <p>
     * The previous value {@code normalize(0.2, 1, 1)} was a naive Y-flip of vanilla's source
     * (matched +Y and -Y axes exactly but diverged 0.04 / 0.07 / 0.13 / 0.17 on ±X / ±Z). A
     * Round 8 attempt at this fix regressed cardinal-shaded entities; root cause unverified,
     * but the per-face math has since been re-derived from scratch against the post-A1 chain
     * and the cardinal-axis match is now bit-stable.
     *
     * @see <a href="https://github.com/Mojang/blaze3d/blob/main/src/main/java/com/mojang/blaze3d/platform/Lighting.java">com.mojang.blaze3d.platform.Lighting</a>
     */
    Vector3f ENTITY_IN_UI_LIGHT_0 = calibrateEntityLight(deriveEntityInUiLightKit(0.2f, -1f, 1f), 0, 0f, 0.0015f, 0f);

    /**
     * Second diffuse light direction; pre-rotated by the same {@code diag(1,-1,1) × M_view^T} as
     * {@link #ENTITY_IN_UI_LIGHT_0} from vanilla's {@code INVENTORY_DIFFUSE_LIGHT_1 =
     * normalize(-0.2, -1, 0)}, plus the same empirical GPU calibration.
     */
    Vector3f ENTITY_IN_UI_LIGHT_1 = calibrateEntityLight(deriveEntityInUiLightKit(-0.2f, -1f, 0f), 1, 0f, 0f, 0.005f);

    /**
     * Applies a small empirical GPU-calibration offset (plus any {@code -Dentity.L<idx>d{x,y,z}}
     * sweep override) to a derived kit-frame light direction, then re-normalises.
     * <p>
     * The lighting GLSL formula and the raw {@code INVENTORY_DIFFUSE_LIGHT} directions are
     * bit-matched to vanilla, and {@link #computeEntityInUiLighting} reproduces the ideal Lambertian
     * shade exactly. But vanilla rasterises on the GPU and we on the CPU, so the per-face shade still
     * drifts ~0.003 from the harness - invisible on dark textures, but {@code +/-1} channel across
     * near-white entities (goat 0.63, copper_golem, husk, illager family, pig). A fleet sweep
     * (tunable via the {@code -Dentity.L<idx>d{x,y,z}} knobs this method reads, forwarded to the
     * parity fork) found that nudging {@code L0.y} by {@code +0.0015} and {@code L1.z} by
     * {@code +0.005} in kit frame pulls the per-face shades toward the GPU output: 58 entities
     * improved, 5 within-bucket regressions, goat {@code 0.63 -> 0.48}, entity buckets
     * {@code 88/98/99/100 -> 88/99/99/100}. Block lighting uses its own
     * {@link #BLOCK_ITEMS_3D_LIGHT_0 ITEMS_3D} directions and is unaffected. The knobs default to 0
     * so the production lights are the baked calibration; pass overrides to re-sweep.
     */
    private static @NotNull Vector3f calibrateEntityLight(@NotNull Vector3f light, int idx, float baseDx, float baseDy, float baseDz) {
        float dx = baseDx + Float.parseFloat(System.getProperty("entity.L" + idx + "dx", "0"));
        float dy = baseDy + Float.parseFloat(System.getProperty("entity.L" + idx + "dy", "0"));
        float dz = baseDz + Float.parseFloat(System.getProperty("entity.L" + idx + "dz", "0"));
        if (dx == 0f && dy == 0f && dz == 0f) return light;
        return new Vector3f(light.x() + dx, light.y() + dy, light.z() + dz).normalize();
    }

    /**
     * Diffuse contribution scale matching vanilla's GLSL {@code MINECRAFT_LIGHT_POWER} constant.
     * The shader uses the value to scale the dot-product sum before the ambient floor is added.
     */
    float MINECRAFT_LIGHT_POWER = 0.6f;

    /**
     * Constant ambient contribution matching vanilla's GLSL {@code MINECRAFT_AMBIENT_LIGHT}
     * constant. Sets the floor brightness when both diffuse dot products clamp to zero.
     */
    float MINECRAFT_AMBIENT_LIGHT = 0.4f;

    // --- projection ---

    /**
     * Projects a model-space point onto 2D screen coordinates using a pure orthographic camera.
     *
     * @param point the 3D point to project
     * @param scale the uniform screen-space scale factor
     * @param offsetX the horizontal screen offset to apply after scaling
     * @param offsetY the vertical screen offset to apply after scaling
     * @return the projected 2D point
     */
    static @NotNull Vector2f projectOrtho(@NotNull Vector3f point, float scale, float offsetX, float offsetY) {
        return new Vector2f(point.x() * scale + offsetX, -point.y() * scale + offsetY);
    }

    /**
     * Projects a model-space point onto 2D screen coordinates using a blend of orthographic and
     * perspective projection. When {@code params.amount() == 0} this is equivalent to
     * {@link #projectOrtho(Vector3f, float, float, float) projectOrtho}.
     *
     * @param point the 3D point to project
     * @param scale the uniform screen-space scale factor
     * @param offsetX the horizontal screen offset to apply after scaling
     * @param offsetY the vertical screen offset to apply after scaling
     * @param params the perspective parameters
     * @return the projected 2D point
     */
    static @NotNull Vector2f projectPerspective(
        @NotNull Vector3f point,
        float scale,
        float offsetX,
        float offsetY,
        @NotNull PerspectiveParams params
    ) {
        if (params.amount() <= 0f)
            return projectOrtho(point, scale, offsetX, offsetY);

        float denom = params.cameraDistance() - point.z();
        float perspectiveFactor = denom == 0f ? 1f : (params.focalLength() / denom);
        float blended = 1f + (perspectiveFactor - 1f) * params.amount();
        return new Vector2f(point.x() * scale * blended + offsetX, -point.y() * scale * blended + offsetY);
    }

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
     * {@link #computeEntityInUiLighting} instead so the output matches vanilla's
     * {@code Lighting.ENTITY_IN_UI} dual-light shader rather than the four-cardinal-bucket
     * approximation.
     *
     * @param normal the world-space surface normal (should be normalized)
     * @return the shade factor for the face that best matches the normal
     */
    static float computeInventoryLighting(@NotNull Vector3f normal) {
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
    static float computeEntityInUiLighting(@NotNull Vector3f normal) {
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
    static float computeBlockItems3dLighting(@NotNull Vector3f normal) {
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
    Vector3f ITEMS_FLAT_LIGHT_0 = deriveFlatItemLight(0.2f, 1.0f, -0.7f);

    /**
     * Second {@code Lighting.Entry.ITEMS_FLAT} light direction; vanilla's
     * {@code DIFFUSE_LIGHT_1 = normalize(-0.2, 1, 0.7)} under the same flat-item pose as
     * {@link #ITEMS_FLAT_LIGHT_0}.
     */
    Vector3f ITEMS_FLAT_LIGHT_1 = deriveFlatItemLight(-0.2f, 1.0f, 0.7f);

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
     * {@link #computeBlockItems3dLighting}. The {@code normal} must already be in render frame -
     * transformed through the item's {@code display.gui} pose and the GUI PoseStack's
     * {@code scale(W, -H, W)} Y-flip.
     *
     * @param normal the render-frame surface normal (should be normalized)
     * @return the shade factor in {@code [0.4, 1.0]}
     */
    static float computeItemsFlatLighting(@NotNull Vector3f normal) {
        float dot0 = Math.max(0f, ITEMS_FLAT_LIGHT_0.dot(normal));
        float dot1 = Math.max(0f, ITEMS_FLAT_LIGHT_1.dot(normal));
        return Math.min(1f, (dot0 + dot1) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
    }

    // --- shading ---

    /**
     * Multiplies an ARGB pixel's RGB channels by a shading factor, preserving the alpha channel.
     *
     * @param argb the source ARGB pixel
     * @param factor the shading factor in {@code [0, 1]}
     * @return the shaded ARGB pixel
     */
    static int applyShading(int argb, float factor) {
        // Vanilla GLSL quantizes via `floor(min(1, v) * 255 + 0.5)` (round-half-up); truncating
        // here biases every shaded channel ~0.5 LSB low and leaves a single-LSB precision floor
        // across un-tinted entities (goat / husk / zombie / skeleton family etc).
        int a = (argb >>> 24) & 0xFF;
        int r = Math.round(((argb >>> 16) & 0xFF) * factor);
        int g = Math.round(((argb >>> 8) & 0xFF) * factor);
        int b = Math.round((argb & 0xFF) * factor);

        r = Math.clamp(r, 0, 255);
        g = Math.clamp(g, 0, 255);
        b = Math.clamp(b, 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // --- output ---

    /**
     * Wraps a list of rendered frames as an {@link ImageData} instance.
     * <p>
     * A single-frame list becomes a {@link StaticImageData}. Multi-frame lists become an
     * {@link AnimatedImageData} where every frame shares the same delay.
     *
     * @param frames the ordered frame list
     * @param frameDelayMs the per-frame display duration in milliseconds
     * @return the wrapped image data
     */
    static @NotNull ImageData wrapFrames(@NotNull ConcurrentList<PixelBuffer> frames, int frameDelayMs) {
        if (frames.isEmpty())
            throw new RenderException("Frame list must contain at least one frame");

        if (frames.size() == 1)
            return StaticImageData.of(frames.getFirst().toBufferedImage());

        AnimatedImageData.Builder builder = AnimatedImageData.builder();
        for (PixelBuffer frame : frames)
            builder.withFrame(ImageFrame.of(frame, frameDelayMs));

        return builder.build();
    }

    /**
     * Wraps a pixel buffer as a single-frame static {@link ImageData}. Shared convenience for
     * every renderer that needs to emit exactly one frame without glint or animation.
     *
     * @param buffer the pixel buffer that becomes the static frame
     * @return the wrapped image data
     */
    static @NotNull ImageData staticFrame(@NotNull PixelBuffer buffer) {
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();
        frames.add(buffer);
        return wrapFrames(frames, 0);
    }

    // --- block-icon ITEMS_3D relighting (moved out of BlockRenderer) ---

    /**
     * Re-shades every triangle with vanilla's {@code Lighting.ITEMS_3D} Lambertian based on
     * the triangle's normal rotated through the block's {@code display.gui} pose and the
     * GUI PoseStack's Y-flip ({@code scale(W, -H, W)}). Replaces the cardinal-bucket
     * shading {@link BlockGeometryKit} bakes at quad-emit time.
     * <p>
     * The transform chain mirrors vanilla's render path exactly: vanilla submits each
     * quad's normal via {@code pose.transformNormal(quadNormal)}, where {@code pose} =
     * {@code translate(W/2,H/2,0) × scale(W,-H,W) × Q_{display.gui}}. Translation doesn't
     * affect direction; the upper-3x3 of the scale is {@code diag(1, -1, 1)} (up to magnitude,
     * which renormalises out for direction vectors); the gui rotation is a pure rotation.
     * So the per-vertex normal handed to the fragment shader is
     * {@code S(1,-1,1) × R_{gui} × n_model}, and that's what
     * {@link #computeBlockItems3dLighting} expects.
     * <p>
     * When {@code forceCullBackFaces} is set (plain block models, see the caller) every emitted
     * triangle is marked {@code cullBackFaces=true} regardless of its built-in flag, matching
     * vanilla's block render types, which all bind GL culling. {@link BlockGeometryKit} marks
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
    static @NotNull ConcurrentList<VisibleTriangle> relightForItems3d(
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
            boolean cull = forceCullBackFaces || t.cullBackFaces();
            // A {@code "shade": false} model element (coral fans, cross/crop plants, ladder,
            // vine, tripwire, redstone dust, torches) carries {@link BlockGeometryKit#SHADE_DISABLED}.
            // Vanilla's {@code getShade(direction, shade=false)} returns 1.0 - the face skips the
            // directional darkening entirely - so render it full-bright instead of applying the
            // {@code Lighting.ITEMS_3D} Lambertian. (The cull / two-sided handling is unchanged;
            // only the shade factor differs.)
            if (t.shading() == BlockGeometryKit.SHADE_DISABLED) {
                out.add(new VisibleTriangle(
                    t.position0(), t.position1(), t.position2(),
                    t.uv0(), t.uv1(), t.uv2(),
                    t.texture(), t.tintArgb(), t.normal(),
                    1.0f, cull, t.emissive(), t.translucent(), t.debugTag()
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
            float shading = computeBlockItems3dLighting(packedNormal);
            out.add(new VisibleTriangle(
                t.position0(), t.position1(), t.position2(),
                t.uv0(), t.uv1(), t.uv2(),
                t.texture(), t.tintArgb(), t.normal(),
                shading, cull, t.emissive(), t.translucent(), t.debugTag()
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
