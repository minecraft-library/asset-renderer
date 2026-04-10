package dev.sbs.renderer.pipeline;

import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.model.ColorMap;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads the three vanilla biome colormap PNGs ({@code grass.png}, {@code foliage.png},
 * {@code dry_foliage.png}) from {@code assets/minecraft/textures/colormap/} into
 * {@link ColorMap} entities.
 * <p>
 * Each colormap is a 256x256 indexed lookup table sampled at {@code (temperature, downfall)}.
 * The reader stores the raw ARGB pixels as a packed big-endian byte array on the entity so the
 * downstream {@link dev.sbs.renderer.engine.TextureEngine TextureEngine#sampleBiomeTint} path can
 * round-trip via {@link java.nio.ByteBuffer#asIntBuffer()}.
 * <p>
 * The reader is intentionally tolerant: if a colormap file is missing the corresponding entry is
 * skipped rather than throwing, since legitimate user packs may ship a partial colormap set.
 */
@UtilityClass
public class ColorMapReader {

    private static final @NotNull Reflection<ColorMap> COLOR_MAP_REFLECTION = new Reflection<>(ColorMap.class);
    private static final @NotNull FieldAccessor<String> COLOR_MAP_ID = COLOR_MAP_REFLECTION.getField("id");
    private static final @NotNull FieldAccessor<String> COLOR_MAP_PACK_ID = COLOR_MAP_REFLECTION.getField("packId");
    private static final @NotNull FieldAccessor<ColorMap.Type> COLOR_MAP_TYPE = COLOR_MAP_REFLECTION.getField("type");
    private static final @NotNull FieldAccessor<byte[]> COLOR_MAP_PIXELS = COLOR_MAP_REFLECTION.getField("pixels");

    /**
     * Loads every supported colormap PNG under the given pack root.
     *
     * @param packRoot the extracted pack root directory
     * @param packId the owning pack identifier (typically {@code "vanilla"})
     * @return a list of loaded colormap entities, one per file that was found
     */
    public static @NotNull ConcurrentList<ColorMap> load(@NotNull Path packRoot, @NotNull String packId) {
        ConcurrentList<ColorMap> colorMaps = Concurrent.newList();
        Path colormapDir = packRoot.resolve("assets/minecraft/textures/colormap");
        if (!Files.isDirectory(colormapDir)) return colorMaps;

        loadOne(colormapDir, packId, ColorMap.Type.GRASS, "grass.png").ifPresent(map -> colorMaps.add(map));
        loadOne(colormapDir, packId, ColorMap.Type.FOLIAGE, "foliage.png").ifPresent(map -> colorMaps.add(map));
        loadOne(colormapDir, packId, ColorMap.Type.DRY_FOLIAGE, "dry_foliage.png").ifPresent(map -> colorMaps.add(map));

        return colorMaps;
    }

    /**
     * Loads a single colormap PNG into a {@link ColorMap} entity, or returns empty when the file
     * is absent. Decoding errors abort the pipeline because a corrupt colormap means downstream
     * tint sampling would silently produce wrong colors.
     */
    private static @NotNull Optional<ColorMap> loadOne(
        @NotNull Path colormapDir,
        @NotNull String packId,
        @NotNull ColorMap.Type type,
        @NotNull String filename
    ) {
        Path file = colormapDir.resolve(filename);
        if (!Files.isRegularFile(file)) return Optional.empty();

        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to read colormap '%s'", file);
        }
        if (image == null)
            throw new AssetPipelineException("Colormap '%s' could not be decoded", file);

        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        ByteBuffer buffer = ByteBuffer.allocate(pixels.length * Integer.BYTES);
        buffer.asIntBuffer().put(pixels);

        ColorMap colorMap = new ColorMap();
        COLOR_MAP_ID.set(colorMap, packId + ":" + type.name().toLowerCase());
        COLOR_MAP_PACK_ID.set(colorMap, packId);
        COLOR_MAP_TYPE.set(colorMap, type);
        COLOR_MAP_PIXELS.set(colorMap, buffer.array());
        return Optional.of(colorMap);
    }

}
