package lib.minecraft.renderer.engine.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.engine.RendererContext;
import org.jetbrains.annotations.NotNull;

/**
 * The generated missing-texture sprite, and the substituting texture lookups the block and item
 * renderers read it through.
 * <p>
 * The sprite is a checker of four equal quadrants in two opaque colours, black on the leading
 * diagonal and magenta on the anti-diagonal, chosen per texel by
 * {@code (y < height / 2) ^ (x < width / 2)}. Both halves are integer divisions, so an odd dimension
 * splits unevenly - a 17-texel span is eight texels then nine. It is generated at class load and
 * ships as no file.
 * <p>
 * {@link #texture} and {@link #textureAtTick} are the two lookups a face substitutes through. Each
 * reads the resolving arm of {@link RendererContext} and draws the checkerboard where no pack
 * supplies the id, so a face is drawn rather than dropped and the id that failed is reported once.
 * {@link BlockRenderer} and {@link ItemRenderer} are the only callers of the two, which is what
 * confines the checkerboard to block and item faces; every other caller reaches the refusing arm and
 * still refuses.
 */
@UtilityClass
public class MissingTexture {

    /** Edge length of the generated sprite, in texels. */
    public static final int SIZE = 16;

    /** ARGB magenta of the anti-diagonal quadrants. */
    public static final int MAGENTA_ARGB = 0xFFF800F8;

    /** ARGB black of the leading-diagonal quadrants. */
    public static final int BLACK_ARGB = 0xFF000000;

    /** The one generated sprite, built at class load and handed to every caller. */
    private static final @NotNull PixelBuffer SPRITE = generate(SIZE, SIZE);

    /** The ids already reported, so one unresolved id logs once rather than once per face. */
    private static final @NotNull ConcurrentSet<String> REPORTED = Concurrent.newSet();

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

    /**
     * Resolves a texture id, drawing the checkerboard when no pack supplies it.
     *
     * @param context the renderer context supplying the pack lookup
     * @param textureId the namespaced texture identifier
     * @return the decoded texture, or the checkerboard
     */
    public static @NotNull PixelBuffer texture(@NotNull RendererContext context, @NotNull String textureId) {
        return context.resolveTexture(textureId).orElseGet(() -> substitute(textureId));
    }

    /**
     * Resolves the frame a texture id shows at a tick, drawing the checkerboard when no pack supplies
     * the texture.
     *
     * @param context the renderer context supplying the pack lookup
     * @param textureId the namespaced texture identifier
     * @param tick the current animation tick (free-running, signed)
     * @return the frame to render at this tick, or the checkerboard
     */
    public static @NotNull PixelBuffer textureAtTick(@NotNull RendererContext context, @NotNull String textureId, int tick) {
        return context.resolveTextureAtTick(textureId, tick).orElseGet(() -> substitute(textureId));
    }

    /**
     * Reports an unresolved texture id the first time it is seen and answers the sprite.
     *
     * @param textureId the texture id no pack supplied
     * @return the sprite
     */
    private static @NotNull PixelBuffer substitute(@NotNull String textureId) {
        if (REPORTED.add(textureId))
            System.err.printf("Missing texture '%s' - drawing the checkerboard%n", textureId);

        return SPRITE;
    }

}
