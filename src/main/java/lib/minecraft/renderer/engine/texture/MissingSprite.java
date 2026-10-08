package lib.minecraft.renderer.engine.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
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
 * <p>
 * A reader sampling a texture across a face's normalised UV space draws the sprite as vanilla draws its
 * own, at whatever size the sheet declares. A reader cropping a sheet by texel coordinates - a skin, an
 * armour sheet, a cape, a banner mask - would find a sixteen-texel sprite covering only the sheet's
 * corner, so it reads the texture through {@link #stretchedTo}, which lays the sprite across the whole
 * sheet first.
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
     * The sprite stretched to each sheet size a cropping reader has asked for, keyed by width in the
     * high half and height in the low, so every face of every render shares one buffer per size.
     */
    private static final @NotNull ConcurrentMap<Long, PixelBuffer> STRETCHED = Concurrent.newMap();

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
     * Whether a buffer is the sprite itself. Compared by identity, since a substituting context hands
     * out the one shared buffer: a texture a pack ships that happens to hold the same texels is a
     * texture, never the stand-in.
     *
     * @param buffer the buffer to test
     * @return {@code true} when it is the shared sprite
     */
    public static boolean isSprite(@NotNull PixelBuffer buffer) {
        return buffer == SPRITE;
    }

    /**
     * Answers a texture as a reader cropping a sheet of the given size by texel coordinates sees it: the
     * sprite laid across the whole sheet, each texel nearest-neighbour sampled at its centre, which is
     * where vanilla's own sprite lands under a face's normalised UVs - and any other texture itself,
     * untouched.
     *
     * @param texture the texture the reader was handed
     * @param width the width the sheet declares, in texels
     * @param height the height the sheet declares, in texels
     * @return the sprite stretched to {@code width x height} when {@code texture} is the sprite, else
     *     {@code texture}
     */
    public static @NotNull PixelBuffer stretchedTo(@NotNull PixelBuffer texture, int width, int height) {
        if (!isSprite(texture) || width <= 0 || height <= 0 || (width == SIZE && height == SIZE)) return texture;
        return STRETCHED.computeIfAbsent(((long) width << 32) | height, key -> stretch(width, height));
    }

    /**
     * Lays the sprite across a sheet, each destination texel taking the sprite texel under its centre.
     *
     * @param width the sheet width in texels
     * @param height the sheet height in texels
     * @return the stretched sprite
     */
    private static @NotNull PixelBuffer stretch(int width, int height) {
        PixelBuffer buffer = PixelBuffer.create(width, height);

        for (int y = 0; y < height; y++) {
            int sy = (2 * y + 1) * SIZE / (2 * height);
            for (int x = 0; x < width; x++)
                buffer.setPixel(x, y, SPRITE.getPixel((2 * x + 1) * SIZE / (2 * width), sy));
        }

        return buffer;
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

