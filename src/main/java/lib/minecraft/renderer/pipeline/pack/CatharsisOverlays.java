package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
@Parity(claim = "catharsis-selection")
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
    public static @NotNull ConcurrentList<String> activeOverlayDirectories(
        @NotNull JsonTree mcmetaRoot, @NotNull CatharsisConfig config, @NotNull CatharsisTarget target
    ) {
        Optional<JsonTree> overlays = mcmetaRoot.findObject(FABRIC_OVERLAYS);
        if (overlays.isEmpty()) return Concurrent.newUnmodifiableList();
        Optional<JsonTree> entries = overlays.get().findArray("entries");
        if (entries.isEmpty()) return Concurrent.newUnmodifiableList();

        return entries.get()
            .elements()
            .filter(JsonTree::isObject)
            .filter(entry -> isString(entry.find("directory").orElse(null)))
            .filter(entry -> entry.findObject("condition")
                .filter(condition -> CatharsisCondition.parse(condition).holds(config, target))
                .isPresent())
            .map(entry -> entry.findString("directory").orElse(null))
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Resolves the config-option defaults for a pack. This is a whole-source replacement, not a
     * per-key merge, matching the Catharsis mod: the root {@code config.catharsis.json} wins outright
     * when it contributes at least one option default, and only when it is absent or contributes none
     * does the {@code catharsis:pack/v1.config} block in the mcmeta supply the defaults. Empty when
     * neither carries an option.
     *
     * @param configFile the parsed {@code config.catharsis.json} JSON, if the pack ships one
     * @param mcmetaRoot the pack mcmeta JSON root, carrying the fallback {@code catharsis:pack/v1.config}
     * @return the resolved config defaults
     */
    public static @NotNull CatharsisConfig loadConfig(@NotNull Optional<JsonTree> configFile, @NotNull JsonTree mcmetaRoot) {
        return configFile.map(CatharsisConfig::parse)
            .filter(config -> !config.isEmpty())
            .orElseGet(() -> mcmetaConfig(mcmetaRoot).map(CatharsisConfig::parse).orElse(CatharsisConfig.EMPTY));
    }

    private static @NotNull Optional<JsonTree> mcmetaConfig(@NotNull JsonTree mcmetaRoot) {
        for (Map.Entry<String, JsonTree> entry : mcmetaRoot.members().toList()) {
            if (entry.getKey().startsWith(CATHARSIS_PACK_PREFIX) && entry.getValue().isObject()) {
                JsonTree catharsisPack = entry.getValue();
                if (catharsisPack.has("config")) return catharsisPack.find("config");
            }
        }
        return Optional.empty();
    }

    private static boolean isString(@Nullable JsonTree element) {
        return element != null && element.isPrimitive() && element.asBool().isEmpty() && element.asInt().isEmpty();
    }

}
