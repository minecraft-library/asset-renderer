package lib.minecraft.renderer.engine.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * The generated missing-texture sprite - the checkerboard drawn where no pack supplies a texture id,
 * and where the file a pack supplies cannot be decoded.
 * <p>
 * It is a checker of four equal quadrants in two opaque colours, black on the leading diagonal and
 * magenta on the anti-diagonal, chosen per texel by {@code (y < height / 2) ^ (x < width / 2)}. Both
 * halves are integer divisions, so an odd dimension splits unevenly - a 17-texel span is eight texels
 * then nine. It is generated at class load and ships as no file.
 */
@UtilityClass
public class MissingSprite {

    /**
     * Edge length of the generated sprite, in texels.
     */
    public static final int SIZE = 16;

    /**
     * ARGB magenta of the anti-diagonal quadrants.
     */
    public static final int MAGENTA_ARGB = 0xFFF800F8;

    /**
     * ARGB black of the leading-diagonal quadrants.
     */
    public static final int BLACK_ARGB = 0xFF000000;

    /**
     * The one generated sprite, built at class load and handed to every caller.
     */
    private static final @NotNull PixelBuffer SPRITE = generate(SIZE, SIZE);

    /**
     * Hands out the shared sprite rather than a copy, the way a resolved pack texture is handed out,
     * so a caller reads it and never writes to it.
     *
     * @return the one generated sprite
     */
    public static @NotNull PixelBuffer sprite() {
        return SPRITE;
    }

    /**
     * Generates the checker at the given size - magenta where exactly one of the two halving
     * comparisons holds, black otherwise.
     *
     * @param width the sprite width in texels
     * @param height the sprite height in texels
     * @return the generated sprite
     */
    static @NotNull PixelBuffer generate(int width, int height) {
        PixelBuffer buffer = PixelBuffer.create(width, height);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++)
                buffer.setPixel(x, y, (y < height / 2) ^ (x < width / 2) ? MAGENTA_ARGB : BLACK_ARGB);
        }

        return buffer;
    }

}

