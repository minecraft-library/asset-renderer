package lib.minecraft.renderer.engine.camera;

import org.jetbrains.annotations.NotNull;

/**
 * A resolved render viewpoint - a {@link Camera} (pose + lens) paired with the {@link LightingFrame} it
 * is lit through. {@link Projection#resolve} produces one, defaulting the lighting to a
 * {@linkplain LightingFrame#tracking frame that tracks the resolved pose}; a caller substitutes or
 * derives a different frame with {@link #withLighting} (e.g. a screen mirror, or a
 * {@linkplain LightingFrame#fixed fixed} borrowed angle) without disturbing the camera.
 *
 * <p>The {@link Camera} is what a {@code ModelEngine} rasterizes through; the {@link LightingFrame} is
 * read separately by the relight pass, so lighting stays an independent axis from pose and lens.
 *
 * @param camera the pose + lens the geometry rasterizes through
 * @param lighting the frame the relight shades through
 */
public record View(@NotNull Camera camera, @NotNull LightingFrame lighting) {

    /**
     * Returns a copy of this view with a different {@link LightingFrame}, keeping the camera. The seam for
     * decoupling the light from the pose - mirror it, fix it at a borrowed angle, or leave it tracking.
     *
     * @param lighting the replacement lighting frame
     * @return a view with this camera and the given lighting
     */
    public @NotNull View withLighting(@NotNull LightingFrame lighting) {
        return new View(this.camera, lighting);
    }

}
