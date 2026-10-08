package lib.minecraft.renderer.bake.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * The refusal a texture lookup that answered no pixels raises, worded by the state it answered - an id
 * no pack serves as unregistered, and a texture that is there but yields no pixels as one that could not
 * be read - a file that does not decode, a sidecar that does not parse, or an animation strip its frame
 * size does not divide.
 * <p>
 * Every reader that draws nothing without its texture refuses through here, so an unreadable file is
 * never reported as a missing one. A {@link RenderException} is a renderer exception, which a batch
 * caller such as the atlas catches per subject and skips.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public final class TextureRefusal {

    /**
     * Answers a texture lookup's pixels, refusing either value-less answer in its own words.
     *
     * @param texture the lookup's answer
     * @param textureId the namespaced texture id the answer is for
     * @return the pixels
     * @throws RenderException if the answer holds no pixels
     */
    public static @NotNull PixelBuffer require(@NotNull Possible<PixelBuffer> texture, @NotNull String textureId) {
        return require(texture, textureId, () -> new RenderException("No texture registered for id '%s'", textureId));
    }

    /**
     * Answers a texture lookup's pixels, refusing an id no pack serves with the caller's own refusal and
     * a file that yields no pixels in this helper's words.
     *
     * @param texture the lookup's answer
     * @param textureId the namespaced texture id the answer is for
     * @param absent the refusal for an id no pack serves
     * @return the pixels
     * @throws RenderException if the answer holds no pixels
     */
    public static @NotNull PixelBuffer require(
        @NotNull Possible<PixelBuffer> texture, @NotNull String textureId, @NotNull Supplier<RenderException> absent) {
        return switch (texture.getState()) {
            case PRESENT -> texture.get();
            case EMPTY -> throw new RenderException("Texture '%s' could not be read", textureId);
            case ABSENT -> throw absent.get();
        };
    }

}
