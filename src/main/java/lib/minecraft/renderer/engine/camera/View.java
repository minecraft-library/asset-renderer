package lib.minecraft.renderer.engine.camera;

import org.jetbrains.annotations.NotNull;

/**
 * A resolved render viewpoint - a {@link Camera} (pose + lens) paired with the {@link LightingFrame} it
 * is lit through. {@link Projection#resolve} produces one, defaulting the lighting to a
 * {@linkplain LightingFrame#tracking frame that tracks the resolved pose}; a caller pairs the same
 * camera with a different frame (e.g. a screen mirror, or a {@linkplain LightingFrame#fixed fixed}
 * borrowed angle) by constructing one directly, without disturbing the camera.
 *
 * <p>The {@link Camera} is what a {@code ModelEngine} rasterizes through; the {@link LightingFrame} is
 * read separately by the relight pass, so lighting stays an independent axis from pose and lens.
 *
 * @param camera the pose + lens the geometry rasterizes through
 * @param lighting the frame the relight shades through
 */
public record View(@NotNull Camera camera, @NotNull LightingFrame lighting) { }
