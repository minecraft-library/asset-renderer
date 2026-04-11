package dev.sbs.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.sbs.renderer.model.ColorMap;
import dev.sbs.renderer.pipeline.parser.ColorMapParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
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

            String id = "vanilla:" + type.name().toLowerCase();
            colorMaps.add(new ColorMap(id, "vanilla", type, pixels));
        }

        return colorMaps;
    }

}
