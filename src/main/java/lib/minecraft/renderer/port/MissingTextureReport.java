package lib.minecraft.renderer.port;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The texture ids a substituting context has drawn the checkerboard for, each reported once for the
 * life of the process rather than once per face that names it.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
class MissingTextureReport {

    /** Every id already reported, so the ninetieth face naming one stays quiet. */
    private static final @NotNull ConcurrentSet<String> REPORTED = Concurrent.newSet();

    /**
     * Reports an unresolved texture id the first time it is seen and answers the sprite.
     *
     * @param textureId the texture id no pack supplied
     * @return the sprite
     */
    static @NotNull PixelBuffer substitute(@NotNull String textureId) {
        if (REPORTED.add(textureId))
            System.err.printf("Missing texture '%s' - drawing the checkerboard%n", textureId);

        return MissingSprite.sprite();
    }

}
