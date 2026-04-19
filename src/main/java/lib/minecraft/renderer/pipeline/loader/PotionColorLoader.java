package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.tooling.ToolingPotionColors;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A loader that reads the bundled vanilla potion effect colour table from the
 * {@code /renderer/potion_colors.json} classpath resource and produces a lookup map of effect
 * id to ARGB.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.world.effect.MobEffects} static initializer as parsed by
 * {@link ToolingPotionColors.Parser}. To refresh it on a Minecraft version bump, run the
 * {@code potionColors} Gradle task; the runtime pipeline never invokes the ASM walker directly.
 * <p>
 * Colours are stored as {@code 0x}-prefixed hex strings in the JSON because Gson cannot
 * round-trip {@code 0xFF000000}-class signed integers literally; they round-trip via
 * {@link Integer#parseUnsignedInt(String, int)}.
 */
@UtilityClass
public class PotionColorLoader {

    private static final @NotNull String RESOURCE_PATH = "/renderer/potion_colors.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads the bundled effect colour table.
     *
     * @return a map of namespaced effect id to ARGB colour
     * @throws AssetPipelineException if the classpath resource is missing or malformed
     */
    public static @NotNull ConcurrentMap<String, Integer> load() {
        try (InputStream stream = PotionColorLoader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null)
                throw new AssetPipelineException("Classpath resource '%s' not found - run the 'potionColors' Gradle task to generate it", RESOURCE_PATH);

            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json);
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to read classpath resource '%s'", RESOURCE_PATH);
        }
    }

    /**
     * Parses a {@code potion_colors.json}-shaped string into the colour map. Exposed for tests.
     *
     * @param json the JSON text to parse
     * @return a map of effect id to ARGB colour
     */
    static @NotNull ConcurrentMap<String, Integer> parse(@NotNull String json) {
        ConcurrentMap<String, Integer> colors = Concurrent.newMap();
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonArray effects = root.getAsJsonArray("effects");
            if (effects == null) return colors;

            for (JsonElement element : effects) {
                JsonObject entry = element.getAsJsonObject();
                String effectId = entry.get("effect").getAsString();
                String hex = entry.get("color").getAsString();
                int argb = Integer.parseUnsignedInt(stripHexPrefix(hex), 16);
                colors.put(effectId, argb);
            }
        } catch (JsonSyntaxException | IllegalStateException | NumberFormatException ex) {
            throw new AssetPipelineException(ex, "Malformed '%s' resource", RESOURCE_PATH);
        }
        return colors;
    }

    private static @NotNull String stripHexPrefix(@NotNull String hex) {
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }

}
