package lib.minecraft.renderer.asset;

import dev.simplified.annotations.EqualsAndHashCode;
import lib.minecraft.renderer.vanilla.TintSource;
import org.jetbrains.annotations.NotNull;

/**
 * A 256x256 biome colormap, stored as a raw ARGB byte array (256 KiB uncompressed - 65536 pixels
 * at 4 bytes each - though pack-sourced PNGs are typically a few KiB on disk so the serialized form
 * stays small).
 * <p>
 * Equality compares {@code pixels} by element rather than by reference identity.
 *
 * @param id the namespaced colormap texture id
 * @param packId the id of the texture pack this colormap was sourced from
 * @param type the tint target this colormap serves, always one declaring a
 *     {@link TintSource#colorMapName() colormap name}
 * @param pixels the raw 256x256 colormap pixels as a flat ARGB byte array, 4 bytes per pixel in
 *     row-major order
 */
@EqualsAndHashCode
public record ColorMap(
    @NotNull String id,
    @NotNull String packId,
    @NotNull TintSource type,
    byte @NotNull [] pixels
) {

    /**
     * Edge length of the square ARGB colormap. Every vanilla colormap ships as a 256x256 image, so
     * sampling indexes as {@code y * SIZE + x}.
     */
    private static final int SIZE = 256;

    /**
     * Upper index of the lookup coordinate in normalized space. Multiplying a clamped {@code [0, 1]}
     * temperature / downfall by this value maps it to a {@code [0, 255]} column or row.
     */
    private static final double COORD_MAX = 255.0;

    /**
     * Samples this colormap at the location described by a biome's temperature and downfall.
     * <p>
     * The sampling formula is byte-for-byte identical to vanilla's
     * {@code net.minecraft.world.level.ColorMapColorUtil.get(double, double, int[], int)} from the
     * MC 26.1 deobfuscated client, verified via {@code javap} disassembly:
     * <pre>{@code
     * adjTemp = (double) clamp(temperature, 0f, 1f)            // float clamp, then widened, as Biome.get*ColorFromTexture
     * adjRain = (double) clamp(downfall, 0f, 1f) * adjTemp    // double product, as ColorMapColorUtil.get
     * x = (int) ((1.0 - adjTemp) * 255.0)
     * y = (int) ((1.0 - adjRain) * 255.0)
     * index = (y << 8) | x
     * }</pre>
     * An index past the last pixel - a pack colormap holding fewer than 65,536 pixels - answers the
     * target's fallback constant, as vanilla's {@code GrassColor}, {@code FoliageColor} and
     * {@code DryFoliageColor} pass it to {@code ColorMapColorUtil.get}: {@code 0xFFFF00FF} for
     * grass, {@code 0xFF48B518} for foliage and {@code 0xFF5C3C32} for dry foliage.
     * <p>
     * The pixel is read straight out of {@link #pixels()} at its own offset. A colormap is 256x256,
     * so unpacking the whole thing first cost a 65,536-element {@code int[]} - 256 KiB - to hand
     * back one element. The four-byte big-endian read here is the same value bit for bit: the bytes
     * are what {@code ColorMapLoader} packed big-endian, which is also how the unpack read it back,
     * since {@code ByteBuffer.wrap} is big-endian by default. Each byte must be masked to
     * {@code 0xFF} - dropping the mask on any of the low three sign-extends and corrupts every pixel
     * whose channel reaches {@code 0x80}.
     *
     * @param temperature the biome temperature
     * @param downfall the biome downfall
     * @return the sampled ARGB pixel
     */
    public int sample(float temperature, float downfall) {
        double adjTemp = Math.clamp(temperature, 0f, 1f);
        double adjRain = Math.clamp(downfall, 0f, 1f) * adjTemp;

        int x = Math.clamp((int) ((1.0 - adjTemp) * COORD_MAX), 0, (int) COORD_MAX);
        int y = Math.clamp((int) ((1.0 - adjRain) * COORD_MAX), 0, (int) COORD_MAX);

        int index = y * SIZE + x;
        if (index >= this.pixels.length / Integer.BYTES)
            return outOfRangeArgb(this.type);

        int offset = index * Integer.BYTES;
        return ((this.pixels[offset] & 0xFF) << 24)
            | ((this.pixels[offset + 1] & 0xFF) << 16)
            | ((this.pixels[offset + 2] & 0xFF) << 8)
            | (this.pixels[offset + 3] & 0xFF);
    }

    /**
     * Answers the colour vanilla samples for an index past a short colormap's last pixel.
     *
     * @param type the tint target the colormap serves
     * @return the target's out-of-range ARGB
     * @throws IllegalStateException if the target names no colormap
     */
    private static int outOfRangeArgb(@NotNull TintSource type) {
        return switch (type) {
            case GRASS -> 0xFFFF00FF;
            case FOLIAGE -> 0xFF48B518;
            case DRY_FOLIAGE -> 0xFF5C3C32;
            case NONE, WATER, CONSTANT -> throw new IllegalStateException(String.format("Tint target '%s' names no colormap", type));
        };
    }

}
