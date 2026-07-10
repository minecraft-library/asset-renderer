package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.ArgbHex;
import lib.minecraft.renderer.pipeline.load.V2Document;
import lib.minecraft.renderer.pipeline.load.V2Resources;
import lib.minecraft.renderer.tooling.ToolingPotionColors;
import lib.minecraft.renderer.tooling2.bridge.LegacyBridge;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;

/**
 * A loader that reads the bundled vanilla potion effect colour table from the
 * {@code v2/potion_colors.json} classpath resource and produces a lookup map of effect id to ARGB.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.world.effect.MobEffects} static initializer as parsed by
 * {@link ToolingPotionColors} {@code .Parser}. To refresh it on a Minecraft version bump, run the
 * {@code potionColors2} Gradle task; the runtime pipeline never invokes the ASM walker directly.
 * <p>
 * Colours are stored as {@code 0x}-prefixed hex strings in the JSON because Gson cannot round-trip
 * {@code 0xFF000000}-class signed integers literally; the native read decodes them through
 * {@link ArgbHex}. Under {@code -Dasset.tooling2.bridge} the legacy-shaped tree is parsed the legacy
 * way instead, kept as the rollback floor until the bridge is retired.
 */
@UtilityClass
public class PotionColorLoader {

    /**
     * Bundled effect colour snapshot's resource file name.
     */
    private static final @NotNull String RESOURCE_NAME = "potion_colors.json";

    /**
     * Shared Gson configured with the project defaults, used by the bridge-path parse.
     */
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads the bundled effect colour table, natively from v2 or - under
     * {@code -Dasset.tooling2.bridge} - from the materialized legacy-shaped tree.
     *
     * @return a map of namespaced effect id to ARGB colour
     * @throws PipelineException if the classpath resource is missing or malformed
     */
    public static @NotNull ConcurrentMap<String, Integer> load() {
        if (LegacyBridge.active())
            return parse(GSON.toJson(LegacyBridge.materialize(RESOURCE_NAME).toGson()));
        return loadNative(Diagnostics.root("potionColors", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the effect colour table from {@code v2/potion_colors.json} natively through the shared
     * read layer, decoding each row's {@code color} through {@link ArgbHex}. Exposed for tests.
     *
     * @param diagnostics the scope envelope and colour warnings are recorded to
     * @return a map of namespaced effect id to ARGB colour
     * @throws PipelineException if the resource is missing or malformed
     */
    static @NotNull ConcurrentMap<String, Integer> loadNative(@NotNull Diagnostics diagnostics) {
        V2Document document = V2Resources.read(RESOURCE_NAME, V2Resources.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        HashMap<String, Integer> colors = new HashMap<>();
        for (V2Effect effect : document.as(V2PotionColors.class).effects())
            colors.put(effect.effect(), ArgbHex.parse(effect.color(), diagnostics));
        return Concurrent.adoptMap(colors).toUnmodifiable();
    }

    /**
     * Parses a legacy {@code potion_colors.json}-shaped string into the colour map - the bridge
     * (flag-on) path. Each entry's {@code color} is a {@code 0x}-prefixed hex string decoded via
     * {@link Integer#parseUnsignedInt(String, int)}. A missing {@code effects} array yields an empty
     * map. Exposed for tests.
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
            throw new PipelineException(ex, "Malformed '%s' resource", RESOURCE_NAME);
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

    /** The v2 {@code potion_colors.json} payload: the ordered effect-colour rows. */
    private record V2PotionColors(@NotNull List<V2Effect> effects) {}

    /**
     * One v2 effect-colour row.
     *
     * @param effect the namespaced effect id
     * @param color the {@code 0xAARRGGBB} hex colour
     */
    private record V2Effect(@NotNull String effect, @NotNull String color) {}

}
