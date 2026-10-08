package lib.minecraft.renderer.content.pack;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.exception.ColorMapException;
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
     * pack ships, or whose winning copy cannot be read or decoded, fails the load rather than leaving
     * the target without a map - vanilla's resource reload fails on it the same way, and samples no
     * colour from a colormap it could not read. The failure is a {@link ColorMapException}, the one
     * read a context load answers by loading the vanilla pack alone, as the client's reload does.
     *
     * @param stack the resolved pack stack carrying the texture index
     * @return the colormap entities keyed by target, one for every target naming a colormap, wrapped
     *     unmodifiable so downstream reads bypass the read lock
     * @throws ColorMapException if no pack ships a colormap a target names, or the copy the winning
     *     pack ships cannot be read or decoded
     */
    public static @NotNull ConcurrentMap<TintSource, ColorMap> load(@NotNull PackStack stack) {
        return Arrays.stream(TintSource.values())
            .filter(target -> target.colorMapName().isPresent())
            .map(target -> Map.entry(target, load(stack, target, target.colorMapName().get())))
            .collect(Concurrent.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Loads the colormap one tint target names from the pack that wins it.
     *
     * @param stack the resolved pack stack carrying the texture index
     * @param target the tint target the colormap serves
     * @param name the colormap's name under {@code textures/colormap/}
     * @return the decoded colormap, attributed to the pack that ships it
     * @throws ColorMapException if no pack ships the colormap, or the winning copy cannot be read or
     *     decoded
     */
    private static @NotNull ColorMap load(@NotNull PackStack stack, @NotNull TintSource target, @NotNull String name) {
        ResolvedTexture resolved = stack.resolve(new ResourceId("minecraft", "colormap/" + name))
            .orElseThrow(() -> new ColorMapException("No pack ships colormap '%s'", name));

        try {
            return new ColorMap(resolved.id().id(), resolved.pack().value(), target, decode(resolved.bytes()));
        } catch (ContentException ex) {
            throw new ColorMapException(ex, "Colormap '%s' from pack '%s' cannot be read: %s", name, resolved.pack().value(), ex.getMessage());
        }
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
