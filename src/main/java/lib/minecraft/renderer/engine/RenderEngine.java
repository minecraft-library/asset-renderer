package lib.minecraft.renderer.engine;

import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * Baseline contract and shared static helpers for every rendering engine.
 * <p>
 * Every method that does not require instance state is bundled here as a {@code static} so every
 * concrete engine has direct access to projection, shading, and output helpers without an
 * instance lookup. Instance state (pack resolution, biome sampling, etc.) lives on subclasses
 * starting with {@link TextureEngine}.
 */
public interface RenderEngine {

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
     * First diffuse light direction for vanilla's {@code Lighting.Entry.ENTITY_IN_UI} entry,
     * Y-flipped from vanilla's source value of {@code (0.2, -1, 1)} so this code can dot
     * against our screen-frame Y-up normals without the kits or callers needing to negate the
     * Y component on every call. Vanilla expresses the same lighting in its Y-down model
     * frame; flipping both sides of the dot product is identity.
     *
     * <p><b>Known divergence vs vanilla harness</b>: the asset-renderer iso pose
     * {@link lib.minecraft.renderer.geometry.EulerRotation#STANDARD_ISO_BLOCK} is
     * {@code (30°, 225°, 0°)} (the MC block-icon pose) but the vanilla-reference-harness uses
     * {@code (210°, 45°, 0°)} for entity rendering - a 90° yaw rotation difference. With our
     * lights expressed in the model frame and vanilla's lights expressed in the camera frame,
     * the 90° iso difference rotates which face each light's X / Z components illuminate. The
     * {@code TestEntityParityVanilla} {@code diff_panel.png} surfaces this as opposite-direction
     * per-quadrant luma deltas (TL too bright + BR too dark on cod, etc). Fixing fully needs
     * either aligning the entity-pipeline iso pose with the harness or rotating the lights by
     * the 90° iso difference; both are tracked as future work.
     *
     * @see <a href="https://github.com/Mojang/blaze3d/blob/main/src/main/java/com/mojang/blaze3d/platform/Lighting.java">com.mojang.blaze3d.platform.Lighting</a>
     */
    Vector3f ENTITY_IN_UI_LIGHT_0 = Vector3f.normalize(new Vector3f(0.2f, 1f, 1f));

    /** Second diffuse light direction; Y-flipped from vanilla's {@code (-0.2, -1, 0)}. See {@link #ENTITY_IN_UI_LIGHT_0}. */
    Vector3f ENTITY_IN_UI_LIGHT_1 = Vector3f.normalize(new Vector3f(-0.2f, 1f, 0f));

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
        float dot0 = Math.max(0f, Vector3f.dot(ENTITY_IN_UI_LIGHT_0, normal));
        float dot1 = Math.max(0f, Vector3f.dot(ENTITY_IN_UI_LIGHT_1, normal));
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
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >>> 16) & 0xFF) * factor);
        int g = (int) (((argb >>> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);

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
    static @NotNull ImageData output(@NotNull ConcurrentList<PixelBuffer> frames, int frameDelayMs) {
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
        return output(frames, 0);
    }

}
