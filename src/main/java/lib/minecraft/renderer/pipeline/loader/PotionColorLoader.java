package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.tooling.ToolingPotionColors;
import lib.minecraft.renderer.tooling2.bridge.LegacyBridge;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * A loader that reads the bundled vanilla potion effect colour table from the
 * {@code /lib/minecraft/renderer/potion_colors.json} classpath resource and produces a lookup map of effect
 * id to ARGB.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.world.effect.MobEffects} static initializer as parsed by
 * {@link ToolingPotionColors} {@code .Parser}. To refresh it on a Minecraft version bump, run the
 * {@code potionColors} Gradle task; the runtime pipeline never invokes the ASM walker directly.
 * <p>
 * Colours are stored as {@code 0x}-prefixed hex strings in the JSON because Gson cannot
 * round-trip {@code 0xFF000000}-class signed integers literally; they round-trip via
 * {@link Integer#parseUnsignedInt(String, int)}.
 */
@UtilityClass
public class PotionColorLoader {

    /**
     * Classpath location of the bundled effect colour snapshot.
     */
    private static final @NotNull String RESOURCE_PATH = "/lib/minecraft/renderer/potion_colors.json";

    /**
     * Shared Gson configured with the project defaults, used to parse the colour table.
     */
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads the bundled effect colour table.
     *
     * @return a map of namespaced effect id to ARGB colour
     * @throws PipelineException if the classpath resource is missing or malformed
     */
    public static @NotNull ConcurrentMap<String, Integer> load() {
        try (InputStream stream = PotionColorLoader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null && !LegacyBridge.active())
                throw new PipelineException("Classpath resource '%s' not found - run the 'potionColors' Gradle task to generate it", RESOURCE_PATH);

            // tooling2 bridge seam (10-bridge SS2.3): parse() consumes a string, so the materialized
            // tree is re-serialised under the fork-lifetime -Dasset.tooling2.bridge flag; canonical-SHA
            // equality makes the round-trip semantically inert.
            String json = LegacyBridge.active()
                ? GSON.toJson(LegacyBridge.materialize("potion_colors.json").toGson())
                : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json);
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read classpath resource '%s'", RESOURCE_PATH);
        }
    }

    /**
     * Parses a {@code potion_colors.json}-shaped string into the colour map. Each entry's
     * {@code color} is a {@code 0x}-prefixed hex string decoded via
     * {@link Integer#parseUnsignedInt(String, int)}. A missing {@code effects} array yields an
     * empty map. Exposed for tests.
     *
     * @param json the JSON text to parse
     * @return a map of namespaced effect id to ARGB colour
     * @throws PipelineException if the JSON is malformed or a colour fails to parse
     */
    static @NotNull ConcurrentMap<String, Integer> parse(@NotNull String json) {
        HashMap<String, Integer> colors = new HashMap<>();
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonArray effects = root.getAsJsonArray("effects");
            if (effects == null) return Concurrent.adoptMap(colors);

            for (JsonElement element : effects) {
                JsonObject entry = element.getAsJsonObject();
                String effectId = entry.get("effect").getAsString();
                String hex = entry.get("color").getAsString();
                int argb = Integer.parseUnsignedInt(stripHexPrefix(hex), 16);
                colors.put(effectId, argb);
            }
        } catch (JsonSyntaxException | IllegalStateException | NumberFormatException ex) {
            throw new PipelineException(ex, "Malformed '%s' resource", RESOURCE_PATH);
        }
        return Concurrent.adoptMap(colors).toUnmodifiable();
    }

    /**
     * Strips a leading {@code 0x} or {@code 0X} prefix from a hex string, leaving already-bare
     * strings untouched so {@link Integer#parseUnsignedInt(String, int)} can consume the digits.
     *
     * @param hex the colour string, with or without a {@code 0x} prefix
     * @return the prefix-free hex digits
     */
    private static @NotNull String stripHexPrefix(@NotNull String hex) {
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }

}
