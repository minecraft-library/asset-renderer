package lib.minecraft.renderer.pipeline.pack.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves which Catharsis {@code fabric:overlays} an offline renderer activates. Catharsis
 * piggybacks on Fabric's overlay system: the pack mcmeta carries a
 * {@code fabric:overlays.entries} array, each entry pairing an overlay {@code directory} with a
 * {@link CatharsisCondition condition}. With no user config store the baseline is each option's
 * declared default, so this evaluates every entry's condition against the {@link CatharsisConfig}
 * defaults and the {@link CatharsisTarget}, returning the active directories in entry order (later
 * entries stack on top - vanilla {@code CompositePackResources} order).
 *
 * <p>Owning only the evaluator: the pack layer stacks the returned directories over the pack root
 * exactly as it stacks vanilla format-gated overlays.
 */
@UtilityClass
public class CatharsisOverlays {

    private static final @NotNull String FABRIC_OVERLAYS = "fabric:overlays";
    private static final @NotNull String CATHARSIS_PACK_PREFIX = "catharsis:pack";

    /**
     * The active overlay directories, in {@code fabric:overlays.entries} order. An entry with no
     * directory or no condition, or whose condition fails, is skipped (degrades to root content).
     *
     * @param mcmetaRoot the pack mcmeta JSON root
     * @param config the pack's config-option defaults
     * @param target the renderer's version / pack-format target
     * @return the active overlay directory names, in entry order
     */
    public static @NotNull List<String> activeOverlayDirectories(
        @NotNull JsonObject mcmetaRoot, @NotNull CatharsisConfig config, @NotNull CatharsisTarget target
    ) {
        if (!mcmetaRoot.has(FABRIC_OVERLAYS) || !mcmetaRoot.get(FABRIC_OVERLAYS).isJsonObject()) return List.of();
        JsonObject overlays = mcmetaRoot.getAsJsonObject(FABRIC_OVERLAYS);
        if (!overlays.has("entries") || !overlays.get("entries").isJsonArray()) return List.of();

        List<String> active = new ArrayList<>();
        for (JsonElement entryElement : overlays.getAsJsonArray("entries")) {
            if (!entryElement.isJsonObject()) continue;
            JsonObject entry = entryElement.getAsJsonObject();
            if (!isString(entry.get("directory")) || !entry.has("condition") || !entry.get("condition").isJsonObject()) continue;
            if (CatharsisCondition.parse(entry.getAsJsonObject("condition")).holds(config, target))
                active.add(entry.get("directory").getAsString());
        }
        return active;
    }

    /**
     * Resolves the config-option defaults for a pack: the root {@code config.catharsis.json} fully
     * overrides any {@code catharsis:pack/v1.config} in the mcmeta (treated as full
     * replacement - the merge-vs-replace detail is untested upstream). Empty when neither is present.
     *
     * @param configFile the parsed {@code config.catharsis.json} JSON, if the pack ships one
     * @param mcmetaRoot the pack mcmeta JSON root, carrying the fallback {@code catharsis:pack/v1.config}
     * @return the resolved config defaults
     */
    public static @NotNull CatharsisConfig loadConfig(@NotNull Optional<JsonElement> configFile, @NotNull JsonObject mcmetaRoot) {
        if (configFile.isPresent()) return CatharsisConfig.parse(configFile.get());
        return mcmetaConfig(mcmetaRoot).map(CatharsisConfig::parse).orElse(CatharsisConfig.EMPTY);
    }

    private static @NotNull Optional<JsonElement> mcmetaConfig(@NotNull JsonObject mcmetaRoot) {
        for (Map.Entry<String, JsonElement> entry : mcmetaRoot.entrySet()) {
            if (entry.getKey().startsWith(CATHARSIS_PACK_PREFIX) && entry.getValue().isJsonObject()) {
                JsonObject catharsisPack = entry.getValue().getAsJsonObject();
                if (catharsisPack.has("config")) return Optional.of(catharsisPack.get("config"));
            }
        }
        return Optional.empty();
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

}
