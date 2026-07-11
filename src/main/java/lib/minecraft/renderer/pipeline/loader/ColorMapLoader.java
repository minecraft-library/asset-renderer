package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.pipeline.load.V2Document;
import lib.minecraft.renderer.pipeline.load.V2Resources;
import lib.minecraft.renderer.tooling.ToolingColorMaps;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * A loader that reads pre-generated biome colormap data from the bundled {@code v2/color_maps.json}
 * classpath resource.
 * <p>
 * The JSON is produced by the {@code colorMaps2} Gradle task via {@link ToolingColorMaps}
 * {@code .Parser}, which reads the vanilla colormap PNGs and encodes their raw ARGB pixels as Base64.
 * This loader decodes the pixels at runtime so the renderer can sample biome tint colors without the
 * original PNG files. Each row is keyed by its {@link ColorMap.Type} and given a synthetic
 * {@code vanilla:<type>} id.
 *
 * @see ColorMap
 */
@UtilityClass
public class ColorMapLoader {

    /**
     * Bundled colormap snapshot's resource file name.
     */
    private static final @NotNull String RESOURCE_NAME = "color_maps.json";

    /**
     * Loads all colormaps natively from v2, indexed by their {@link ColorMap.Type}. Returns an empty
     * map when the resource is absent from the classpath.
     *
     * @return the colormap entities keyed by type, wrapped unmodifiable so downstream reads bypass
     *     the read lock
     */
    public static @NotNull ConcurrentMap<ColorMap.Type, ColorMap> load() {
        return loadNative(Diagnostics.root("colorMaps", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the colormap table from {@code v2/color_maps.json} natively through the shared read layer,
     * resolving {@code type} through {@link ColorMap.Type} with an unknown value skipped-with-diagnostic
     * rather than aborting the load, and ignoring the v2-only per-map {@code source} provenance. A
     * missing or empty {@code maps} array yields an empty map. Exposed for tests.
     *
     * @param diagnostics the scope envelope and type warnings are recorded to
     * @return the colormap entities keyed by type, wrapped unmodifiable
     */
    static @NotNull ConcurrentMap<ColorMap.Type, ColorMap> loadNative(@NotNull Diagnostics diagnostics) {
        Optional<V2Document> document = V2Resources.read(RESOURCE_NAME, V2Resources.MissingPolicy.GRACEFUL_EMPTY, diagnostics);
        List<V2MapRow> maps = document.map(doc -> doc.as(V2ColorMaps.class).maps()).orElse(null);
        return toColorMaps(maps, diagnostics);
    }

    /**
     * Maps the parsed v2 colormap rows into the runtime lookup map, resolving {@code type} through
     * {@link ColorMap.Type} with an unknown value skipped-with-diagnostic and decoding each row's
     * Base64 {@code pixels}. A {@code null} row list (absent or empty {@code maps}) yields an empty
     * map - the guard fixing the historical null-array NPE. Exposed for tests.
     *
     * @param rows the parsed v2 colormap rows, or {@code null} when the resource omits {@code maps}
     * @param diagnostics the scope type warnings are recorded to
     * @return the colormap entities keyed by type, wrapped unmodifiable
     */
    static @NotNull ConcurrentMap<ColorMap.Type, ColorMap> toColorMaps(@Nullable List<V2MapRow> rows, @NotNull Diagnostics diagnostics) {
        HashMap<ColorMap.Type, ColorMap> colorMaps = new HashMap<>();
        if (rows == null) return Concurrent.adoptMap(colorMaps).toUnmodifiable();

        for (V2MapRow row : rows) {
            ColorMap.Type type;
            try {
                type = ColorMap.Type.valueOf(row.type());
            } catch (IllegalArgumentException ex) {
                diagnostics.warn("unknown colormap type '%s' - skipping row", row.type());
                continue;
            }
            byte[] pixels = Base64.getDecoder().decode(row.pixels());
            String id = "vanilla:" + type.name().toLowerCase();
            colorMaps.put(type, new ColorMap(id, "vanilla", type, pixels));
        }
        return Concurrent.adoptMap(colorMaps).toUnmodifiable();
    }

    /** The v2 {@code color_maps.json} payload: the ordered colormap rows. */
    record V2ColorMaps(@Nullable List<V2MapRow> maps) {}

    /**
     * One v2 colormap row ({@code source} provenance is ignored).
     *
     * @param type the {@link ColorMap.Type} enum name
     * @param pixels the Base64-encoded raw ARGB pixel bytes
     */
    record V2MapRow(@NotNull String type, @NotNull String pixels) {}

}
