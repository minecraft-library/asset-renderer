package dev.sbs.renderer.engine;

import dev.sbs.renderer.geometry.BlockFace;
import dev.sbs.renderer.geometry.PerspectiveParams;
import dev.sbs.renderer.tensor.Vector2f;
import dev.sbs.renderer.tensor.Vector3f;
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
        Vector2f out = new Vector2f();
        projectPerspectiveInto(point, scale, offsetX, offsetY, params, out);
        return out;
    }

    /**
     * Writes the perspective projection of {@code point} into {@code out}. Bit-identical to
     * {@link #projectPerspective(Vector3f, float, float, float, PerspectiveParams)} under
     * IEEE-754 round-to-nearest-even.
     * <p>
     * Hot callers (the rasterizer's per-vertex projection) lease a single {@link Vector2f}
     * scratch instance per {@code projectTriangle} invocation and call this variant to avoid
     * a fresh allocation per vertex.
     *
     * @param point the 3D point to project
     * @param scale the uniform screen-space scale factor
     * @param offsetX the horizontal screen offset to apply after scaling
     * @param offsetY the vertical screen offset to apply after scaling
     * @param params the perspective parameters
     * @param out the vector that receives the projected screen point
     */
    static void projectPerspectiveInto(
        @NotNull Vector3f point,
        float scale,
        float offsetX,
        float offsetY,
        @NotNull PerspectiveParams params,
        @NotNull Vector2f out
    ) {
        if (params.amount() <= 0f) {
            out.set(point.x() * scale + offsetX, -point.y() * scale + offsetY);
            return;
        }

        float denom = params.cameraDistance() - point.z();
        float perspectiveFactor = denom == 0f ? 1f : (params.focalLength() / denom);
        float blended = 1f + (perspectiveFactor - 1f) * params.amount();
        out.set(point.x() * scale * blended + offsetX, -point.y() * scale * blended + offsetY);
    }

    // --- inventory lighting ---

    /**
     * Computes the per-face shade factor for a world-space surface normal under vanilla's
     * standard {@code [30, 225, 0]} GUI pose. Delegates to {@link BlockFace#fromNormal} to pick
     * the dominant cardinal face and returns that face's pre-baked
     * {@link BlockFace#lighting() lighting} factor. See {@link BlockFace}'s class-level doc for
     * the rationale behind the reversed E/W vs N/S values (vanilla {@code Lighting.ITEMS_3D}
     * uses two directional lights offset in X, inverting world-block brightness).
     *
     * @param normal the world-space surface normal (should be normalized)
     * @return the shade factor for the face that best matches the normal
     */
    static float computeInventoryLighting(@NotNull Vector3f normal) {
        return BlockFace.fromNormal(normal).lighting();
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
            throw new IllegalArgumentException("Frame list must contain at least one frame");

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
