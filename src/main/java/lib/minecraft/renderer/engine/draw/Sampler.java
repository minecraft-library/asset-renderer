package lib.minecraft.renderer.engine.draw;

import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.ColorMath;
import org.jetbrains.annotations.NotNull;

/**
 * The state a texture is read by - its filter and its address mode - as the client's sampler holds them: one filter
 * for magnifying and minifying alike, and one address mode for both axes, which is every sampler a plain texture is
 * given.
 * <p>
 * A plain texture's sampler is the one its sidecar's texture section selects ({@link #of}), and
 * {@link #NEAREST_REPEAT} where the sidecar sets neither flag or ships no section, which the missing texture carries
 * too, being built with none. An atlas sprite is read by its atlas's sampler whatever its own sidecar sets.
 * <p>
 * A {@link FilterMode#LINEAR} sample reads the four texels about the coordinate less half a texel, each addressed by
 * the mode, and blends them on straight colour, every channel alike, rounding the result to a byte. A
 * {@link FilterMode#NEAREST} sample reads the one texel under the coordinate.
 *
 * @param filterMode how a coordinate between two texel centres is read
 * @param addressMode how a texel past either edge of the texture is read
 */
public record Sampler(@NotNull FilterMode filterMode, @NotNull AddressMode addressMode) {

    /** The sampler of a texture whose sidecar neither blurs nor clamps, or ships no texture section - the missing texture's. */
    public static final @NotNull Sampler NEAREST_REPEAT = new Sampler(FilterMode.NEAREST, AddressMode.REPEAT);

    /**
     * Answers the sampler a texture section's two flags select, as the client builds a texture's sampler when its
     * contents are applied.
     *
     * @param blur whether the section blurs, selecting {@link FilterMode#LINEAR}
     * @param clamp whether the section clamps, selecting {@link AddressMode#CLAMP_TO_EDGE}
     * @return the sampler
     */
    public static @NotNull Sampler of(boolean blur, boolean clamp) {
        return new Sampler(blur ? FilterMode.LINEAR : FilterMode.NEAREST, clamp ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT);
    }

    /**
     * Answers the texel a fetch reads on one axis for an index that may lie past either edge: wrapped into the texture
     * under {@link AddressMode#REPEAT}, held at the nearer edge under {@link AddressMode#CLAMP_TO_EDGE}.
     * <p>
     * A repeating axis whose size is a positive power of two keeps the index's low bits, which is
     * {@code Math.floorMod} for every {@code int}, negative and overflowed indices included; any other size takes
     * {@code Math.floorMod} itself. Zero is no power of two, so an empty texture fails in {@code Math.floorMod} rather
     * than reading outside its pixels.
     *
     * @param texel the texel index, unbounded
     * @param size the texture's size on the axis, in texels
     * @return the texel read, from {@code 0} to {@code size - 1}
     */
    public int address(int texel, int size) {
        return switch (this.addressMode) {
            case REPEAT -> size > 0 && (size & (size - 1)) == 0 ? texel & (size - 1) : Math.floorMod(texel, size);
            case CLAMP_TO_EDGE -> Math.clamp(texel, 0, size - 1);
        };
    }

    /**
     * Samples a texture at a normalised coordinate, as a shader's texture read does: the coordinate times the
     * texture's size is the point in texels, {@code 0} the first texel's leading edge and {@code 1} the last one's
     * trailing edge on each axis, and a point past either edge is read through the address mode.
     *
     * @param texture the texture
     * @param u the horizontal coordinate, normalised to the texture's width
     * @param v the vertical coordinate, normalised to the texture's height
     * @return the sampled colour, straight
     */
    public int sample(@NotNull PixelBuffer texture, float u, float v) {
        int width = texture.width();
        int height = texture.height();
        return switch (this.filterMode) {
            case NEAREST -> texture.getPixel(this.nearest(u, width), this.nearest(v, height));
            case LINEAR -> {
                float x = u * width - 0.5f;
                float y = v * height - 0.5f;
                int x0 = (int) Math.floor(x);
                int y0 = (int) Math.floor(y);
                yield this.interpolate(texture, x0, x - x0, y0, y - y0);
            }
        };
    }

    /**
     * Blends the four texels from {@code (x0, y0)} toward the next on each axis, as the linear filter does: each texel
     * addressed by this sampler's mode, and every channel, alpha among them, blended along the row by {@code dx}, then
     * down the column by {@code dy}, on straight colour, and rounded to a byte.
     *
     * @param texture the texture
     * @param x0 the first column, unbounded
     * @param dx the weight of the column after it, from {@code 0} up to {@code 1}
     * @param y0 the first row, unbounded
     * @param dy the weight of the row after it, from {@code 0} up to {@code 1}
     * @return the blended colour, straight
     */
    public int interpolate(@NotNull PixelBuffer texture, int x0, float dx, int y0, float dy) {
        int xa = this.address(x0, texture.width());
        int xb = this.address(x0 + 1, texture.width());
        int ya = this.address(y0, texture.height());
        int yb = this.address(y0 + 1, texture.height());

        int p00 = texture.getPixel(xa, ya);
        int p10 = texture.getPixel(xb, ya);
        int p01 = texture.getPixel(xa, yb);
        int p11 = texture.getPixel(xb, yb);
        return ColorMath.pack(
            bilerp(ColorMath.alpha(p00), ColorMath.alpha(p10), ColorMath.alpha(p01), ColorMath.alpha(p11), dx, dy),
            bilerp(ColorMath.red(p00), ColorMath.red(p10), ColorMath.red(p01), ColorMath.red(p11), dx, dy),
            bilerp(ColorMath.green(p00), ColorMath.green(p10), ColorMath.green(p01), ColorMath.green(p11), dx, dy),
            bilerp(ColorMath.blue(p00), ColorMath.blue(p10), ColorMath.blue(p01), ColorMath.blue(p11), dx, dy)
        );
    }

    /**
     * Answers the texel under a normalised coordinate on one axis, by this sampler's address mode. Under
     * {@link AddressMode#REPEAT} the fraction is taken before the scale, so a coordinate any distance off the texture
     * lands on it.
     *
     * @param coordinate the normalised coordinate, any value
     * @param size the texture's size on the axis, in texels
     * @return the texel, from {@code 0} to {@code size - 1}
     */
    private int nearest(float coordinate, int size) {
        return switch (this.addressMode) {
            case REPEAT -> Math.clamp((int) ((coordinate - (float) Math.floor(coordinate)) * size), 0, size - 1);
            case CLAMP_TO_EDGE -> Math.clamp((long) Math.floor(coordinate * size), 0, size - 1);
        };
    }

    /**
     * Blends one channel of four texels along the row, then down the column, rounded to a byte.
     *
     * @param c00 the first row's first texel
     * @param c10 the first row's second texel
     * @param c01 the second row's first texel
     * @param c11 the second row's second texel
     * @param dx the weight of the second column
     * @param dy the weight of the second row
     * @return the blended channel, from {@code 0} to {@code 255}
     */
    private static int bilerp(int c00, int c10, int c01, int c11, float dx, float dy) {
        float top = c00 + (c10 - c00) * dx;
        float bottom = c01 + (c11 - c01) * dx;
        return Math.clamp(Math.round(top + (bottom - top) * dy), 0, 255);
    }

    /** How a coordinate between two texel centres is read - the client's two filter modes. */
    public enum FilterMode {

        /** The one texel under the coordinate. */
        NEAREST,

        /** The four texels about the coordinate, blended by how near each is. */
        LINEAR

    }

    /** How a texel past either edge of a texture is read - the two address modes the client gives a plain texture. */
    public enum AddressMode {

        /** The texture repeats, so a texel past one edge is read in from the other. */
        REPEAT,

        /** The edge texel stands for every texel past it. */
        CLAMP_TO_EDGE

    }

}
