package lib.minecraft.renderer.engine.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.RenderException;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Function;

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
 * {@link #texture} and {@link #textureAtTick} are the two lookups a face reads its texture through,
 * and {@link #faces} is the resolver an element walk loads a whole model through. Each takes the
 * render's own {@code substituting} answer and picks an arm of {@link RendererContext} with it: the
 * resolving arm, drawing the checkerboard where no pack supplies the id so a face is drawn rather than
 * dropped and the id that failed is reported once, or the refusing arm, raising as it always did.
 * <p>
 * <b>The flag is a parameter here rather than a rule anywhere upstream, and it has to be.</b> The
 * substitution cannot be keyed on the texture id, because the id is not what separates the callers who
 * want it: {@link BlockRenderer}'s per-face load and {@link ItemRenderer}'s layer walk see the same
 * strings an entity's carried-block overlay sees off the same block model, and that overlay must read
 * an empty so it can be dropped. Passing the answer in from the render is what lets one id have two,
 * and it is why these three are the only substituting lookups in the renderer - every other caller
 * reaches the port directly and still refuses.
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
     * Resolves a texture id, drawing the checkerboard when no pack supplies it and the render asked
     * for one, and raising through the port's own refusing arm when it did not.
     *
     * @param context the renderer context supplying the pack lookup
     * @param textureId the namespaced texture identifier
     * @param substituting whether an absent texture draws the checkerboard rather than raising
     * @return the decoded texture, or the checkerboard
     * @throws RenderException where the render is not substituting and no pack supplies the id
     */
    public static @NotNull PixelBuffer texture(
        @NotNull RendererContext context, @NotNull String textureId, boolean substituting) {
        if (!substituting) return context.requireTexture(textureId);
        return context.resolveTexture(textureId).orElseGet(() -> substitute(textureId));
    }

    /**
     * Resolves the frame a texture id shows at a tick, drawing the checkerboard when no pack supplies
     * the texture and the render asked for one, and raising through the port's own refusing arm when
     * it did not.
     *
     * @param context the renderer context supplying the pack lookup
     * @param textureId the namespaced texture identifier
     * @param tick the current animation tick (free-running, signed)
     * @param substituting whether an absent texture draws the checkerboard rather than raising
     * @return the frame to render at this tick, or the checkerboard
     * @throws RenderException where the render is not substituting and no pack supplies the id
     */
    public static @NotNull PixelBuffer textureAtTick(
        @NotNull RendererContext context, @NotNull String textureId, int tick, boolean substituting) {
        if (!substituting) return context.requireTextureAtTick(textureId, tick);
        return context.resolveTextureAtTick(textureId, tick).orElseGet(() -> substitute(textureId));
    }

    /**
     * The per-face resolver a model's element walk loads its textures through, sampling each at
     * {@code tick}.
     * <p>
     * It answers present for every id it is asked about, because both arms are total: one draws the
     * checkerboard and the other raises. Nothing here answers empty, and that is the point - an empty
     * would have the walk drop the face and the render come out with a hole in it, where a render that
     * is not substituting asked to be refused instead.
     *
     * @param context the renderer context supplying the pack lookup
     * @param tick the animation tick each face is sampled at
     * @param substituting whether an absent texture draws the checkerboard rather than raising
     * @return the resolver
     */
    public static @NotNull Function<String, Optional<PixelBuffer>> faces(
        @NotNull RendererContext context, int tick, boolean substituting) {
        return textureId -> Optional.of(textureAtTick(context, textureId, tick, substituting));
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
