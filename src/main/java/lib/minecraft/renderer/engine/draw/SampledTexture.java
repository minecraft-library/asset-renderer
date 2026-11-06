package lib.minecraft.renderer.engine.draw;

import dev.simplified.image.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * A texture as a draw reads it - its pixels and the sampler they are read through.
 * <p>
 * A plain texture carries the sampler its sidecar's texture section selects, and the missing texture standing in
 * for one carries {@link Sampler#NEAREST_REPEAT}. An atlas sprite a 3D kit draws as a texture of its own, a buffer a
 * caller builds, and a picture this renderer cuts or pastes out of a plain texture before it draws it - a box face's
 * crop, a composite of equipment layers - carry {@link Sampler#NEAREST_REPEAT} through {@link #of}; a GUI sprite
 * carries its atlas's sampler, nearest and clamped to the edge.
 *
 * @param pixels the texture's pixels
 * @param sampler the sampler the pixels are read through
 */
public record SampledTexture(@NotNull PixelBuffer pixels, @NotNull Sampler sampler) {

    /**
     * Answers a texture read nearest and repeating - an atlas sprite, a buffer a caller builds, a picture cut or
     * pasted out of a plain texture, or a texture whose sidecar selects no other sampler.
     *
     * @param pixels the texture's pixels
     * @return the texture, read through {@link Sampler#NEAREST_REPEAT}
     */
    public static @NotNull SampledTexture of(@NotNull PixelBuffer pixels) {
        return new SampledTexture(pixels, Sampler.NEAREST_REPEAT);
    }

    /**
     * Samples the texture at a normalised coordinate through its sampler.
     *
     * @param u the horizontal coordinate, normalised to the texture's width
     * @param v the vertical coordinate, normalised to the texture's height
     * @return the sampled colour, straight
     * @see Sampler#sample
     */
    public int sample(float u, float v) {
        return this.sampler.sample(this.pixels, u, v);
    }

}
