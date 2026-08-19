package lib.minecraft.renderer.asset;

import dev.simplified.annotations.EqualsAndHashCode;
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
 *     {@link Block.TintTarget#colorMapName() colormap name}
 * @param pixels the raw 256x256 colormap pixels as a flat ARGB byte array, 4 bytes per pixel in
 *     row-major order
 */
@EqualsAndHashCode
public record ColorMap(
    @NotNull String id,
    @NotNull String packId,
    Block.@NotNull TintTarget type,
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
    private static final float COORD_MAX = 255f;

    /**
     * Samples this colormap at the location described by a biome's temperature and downfall.
     * <p>
     * The sampling formula is byte-for-byte identical to vanilla's
     * {@code net.minecraft.world.level.ColorMapColorUtil.get(double, double, int[], int)} from the
     * MC 26.1 deobfuscated client, verified via {@code javap} disassembly:
     * <pre>{@code
     * adjTemp = clamp(temperature, 0, 1)   // vanilla clamps in Biome.getGrassColorFromTexture
     * adjRain = clamp(downfall, 0, 1) * adjTemp
     * x = floor((1 - adjTemp) * 255)
     * y = floor((1 - adjRain) * 255)
     * index = (y << 8) | x
     * }</pre>
     * Vanilla returns a magenta fallback ({@code 0xFFFF00FF}) when the index is out of bounds;
     * this clamps instead for defensive parity with malformed colormaps.
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
        float adjTemp = Math.clamp(temperature, 0f, 1f);
        float adjRain = Math.clamp(downfall, 0f, 1f) * adjTemp;

        int x = Math.clamp((int) ((1.0f - adjTemp) * COORD_MAX), 0, (int) COORD_MAX);
        int y = Math.clamp((int) ((1.0f - adjRain) * COORD_MAX), 0, (int) COORD_MAX);

        int offset = (y * SIZE + x) * Integer.BYTES;
        return ((this.pixels[offset] & 0xFF) << 24)
            | ((this.pixels[offset + 1] & 0xFF) << 16)
            | ((this.pixels[offset + 2] & 0xFF) << 8)
            | (this.pixels[offset + 3] & 0xFF);
    }

}
