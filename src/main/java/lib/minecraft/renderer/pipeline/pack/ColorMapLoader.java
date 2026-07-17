package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;

/**
 * A loader that resolves the three biome colormaps ({@code textures/colormap/{grass,foliage,dry_foliage}.png})
 * through the pack stack like any other texture, so a user pack's colormap override wins over vanilla's.
 * The bytecode-derived snapshots (tints, potion colours, glint, block-entity geometry)
 * stay bundled; only the colormaps - which ARE standard pack assets - join the stack.
 * <p>
 * Each PNG is decoded via {@link BufferedImage#getRGB} then packed big-endian, exactly as the bundled
 * {@code color_maps.json} snapshot was generated ({@code ToolingColorMaps}), so a vanilla-only stack
 * decodes byte-identical pixels to that snapshot - and NOT via the texture
 * decode path, whose grayscale-gamma bypass would diverge.
 *
 * @see ColorMap
 */
@UtilityClass
public class ColorMapLoader {

    /**
     * Resolves every colormap the stack supplies, indexed by its {@link ColorMap.Type}. A type whose
     * PNG no pack supplies is skipped (graceful).
     *
     * @param stack the resolved pack stack carrying the texture index
     * @return the colormap entities keyed by type, wrapped unmodifiable so downstream reads bypass the
     *     read lock
     */
    public static @NotNull ConcurrentMap<ColorMap.Type, ColorMap> load(@NotNull PackStack stack) {
        HashMap<ColorMap.Type, ColorMap> colorMaps = new HashMap<>();
        for (ColorMap.Type type : ColorMap.Type.values()) {
            ResourceId id = new ResourceId("minecraft", "colormap/" + type.name().toLowerCase(Locale.ROOT));
            stack.resolve(id).ifPresent(resolved ->
                colorMaps.put(type, new ColorMap(resolved.id().id(), resolved.pack().value(), type, decode(resolved.bytes()))));
        }
        return Concurrent.adoptMap(colorMaps).toUnmodifiable();
    }

    /**
     * Decodes a colormap PNG to the row-major, big-endian ARGB byte array {@code ColorMap.pixels}
     * carries - {@link BufferedImage#getRGB} to sRGB ARGB ints, packed big-endian 4 bytes/px. Kept
     * bit-identical to {@code ToolingColorMaps} so the stack decode round-trips the bundled snapshot.
     * Reads the PNG bytes through {@link ImageIO} - the deliberate gamma-applying path, not the texture
     * decode path whose grayscale-gamma bypass would diverge - fed a {@link ByteArrayInputStream} so the
     * source is container-agnostic (a materialized directory, zip, or {@code .cats} pack all decode
     * identically).
     *
     * @param bytes the raw colormap PNG bytes
     * @return the raw ARGB pixel bytes
     * @throws PipelineException if the PNG cannot be read or decoded
     */
    static byte @NotNull [] decode(byte @NotNull [] bytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read colormap bytes");
        }
        if (image == null)
            throw new PipelineException("Colormap bytes could not be decoded");

        int[] pixels = new int[image.getWidth() * image.getHeight()];
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
        ByteBuffer buffer = ByteBuffer.allocate(pixels.length * Integer.BYTES);
        buffer.asIntBuffer().put(pixels);
        return buffer.array();
    }

}
