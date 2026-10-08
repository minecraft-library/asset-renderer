package lib.minecraft.renderer.content.pack;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

/**
 * A loader that resolves the three biome colormaps ({@code textures/colormap/{grass,foliage,dry_foliage}.png})
 * through the pack stack like any other texture, so a user pack's colormap override wins over vanilla's.
 * The bytecode-derived snapshots (tints, potion colours, glint, block-entity geometry)
 * stay bundled; only the colormaps - which ARE standard pack assets - join the stack.
 * <p>
 * Each PNG is decoded via {@link BufferedImage#getRGB} then packed big-endian, exactly as the bundled
 * {@code color_maps.json} snapshot was generated ({@code ColorMapsFlow}), so a vanilla-only stack
 * decodes byte-identical pixels to that snapshot - and NOT via the texture
 * decode path, whose grayscale-gamma bypass would diverge.
 *
 * @see ColorMap
 */
@UtilityClass
public class ColorMapLoader {

    /**
     * Resolves the three colormaps, indexed by the tint target each serves. Every target naming a
     * {@link TintSource#colorMapName() colormap} is resolved through the stack, and one whose PNG no
     * pack ships fails the load rather than leaving the target without a map - vanilla's resource
     * reload fails on it the same way, and samples no colour from a colormap it could not read.
     *
     * @param stack the resolved pack stack carrying the texture index
     * @return the colormap entities keyed by target, one for every target naming a colormap, wrapped
     *     unmodifiable so downstream reads bypass the read lock
     * @throws ContentException if no pack ships a colormap a target names, or one cannot be decoded
     */
    public static @NotNull ConcurrentMap<TintSource, ColorMap> load(@NotNull PackStack stack) {
        return Arrays.stream(TintSource.values())
            .filter(target -> target.colorMapName().isPresent())
            .map(target -> stack.resolve(new ResourceId("minecraft", "colormap/" + target.colorMapName().get()))
                .map(resolved -> Map.entry(target, new ColorMap(resolved.id().id(), resolved.pack().value(), target, decode(resolved.bytes()))))
                .orElseThrow(() -> new ContentException("No pack ships colormap '%s'", target.colorMapName().get())))
            .collect(Concurrent.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Decodes a colormap PNG to the row-major, big-endian ARGB byte array {@code ColorMap.pixels}
     * carries - {@link BufferedImage#getRGB} to sRGB ARGB ints, packed big-endian 4 bytes/px. Kept
     * bit-identical to {@code ColorMapsFlow} so the stack decode round-trips the bundled snapshot.
     * Reads the PNG bytes through {@link ImageIO} - the deliberate gamma-applying path, not the texture
     * decode path whose grayscale-gamma bypass would diverge - fed a {@link ByteArrayInputStream} so the
     * source is container-agnostic (a materialized directory, zip, or {@code .cats} pack all decode
     * identically).
     *
     * @param bytes the raw colormap PNG bytes
     * @return the raw ARGB pixel bytes
     * @throws ContentException if the PNG cannot be read or decoded
     */
    static byte @NotNull [] decode(byte @NotNull [] bytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            throw new ContentException(ex, "Failed to read colormap bytes");
        }
        if (image == null)
            throw new ContentException("Colormap bytes could not be decoded");

        int[] pixels = new int[image.getWidth() * image.getHeight()];
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
        ByteBuffer buffer = ByteBuffer.allocate(pixels.length * Integer.BYTES);
        buffer.asIntBuffer().put(pixels);
        return buffer.array();
    }

}
