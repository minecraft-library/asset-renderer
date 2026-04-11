package dev.sbs.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.sbs.renderer.model.ColorMap;
import dev.sbs.renderer.pipeline.parser.ColorMapParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A loader that reads pre-generated biome colormap data from the bundled
 * {@code /renderer/color_maps.json} classpath resource.
 * <p>
 * The JSON is produced by the {@code generateColorMaps} Gradle task via
 * {@link ColorMapParser}, which reads the vanilla colormap PNGs and encodes their raw ARGB
 * pixels as Base64. This loader decodes the pixels at runtime so the renderer can sample biome
 * tint colors without needing the original PNG files.
 *
 * @see ColorMapParser
 * @see ColorMap
 */
@UtilityClass
public class ColorMapLoader {

    private static final @NotNull String RESOURCE_PATH = "/renderer/color_maps.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private static final @NotNull Reflection<ColorMap> COLOR_MAP_REFLECTION = new Reflection<>(ColorMap.class);
    private static final @NotNull FieldAccessor<String> COLOR_MAP_ID = COLOR_MAP_REFLECTION.getField("id");
    private static final @NotNull FieldAccessor<String> COLOR_MAP_PACK_ID = COLOR_MAP_REFLECTION.getField("packId");
    private static final @NotNull FieldAccessor<ColorMap.Type> COLOR_MAP_TYPE = COLOR_MAP_REFLECTION.getField("type");
    private static final @NotNull FieldAccessor<byte[]> COLOR_MAP_PIXELS = COLOR_MAP_REFLECTION.getField("pixels");

    /**
     * Loads all colormaps from the bundled JSON resource.
     *
     * @return the list of colormap entities
     */
    public static @NotNull ConcurrentList<ColorMap> load() {
        InputStream stream = ColorMapLoader.class.getResourceAsStream(RESOURCE_PATH);
        if (stream == null)
            return Concurrent.newList();

        JsonObject root = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
        JsonArray entries = root.getAsJsonArray("color_maps");
        ConcurrentList<ColorMap> colorMaps = Concurrent.newList();

        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            ColorMap.Type type = ColorMap.Type.valueOf(entry.get("type").getAsString());
            byte[] pixels = Base64.getDecoder().decode(entry.get("pixels").getAsString());

            ColorMap colorMap = new ColorMap();
            COLOR_MAP_ID.set(colorMap, "vanilla:" + type.name().toLowerCase());
            COLOR_MAP_PACK_ID.set(colorMap, "vanilla");
            COLOR_MAP_TYPE.set(colorMap, type);
            COLOR_MAP_PIXELS.set(colorMap, pixels);
            colorMaps.add(colorMap);
        }

        return colorMaps;
    }

}
