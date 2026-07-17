package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.spec.DyeColor;
import lib.minecraft.renderer.pipeline.pack.PackId;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * A pack's {@code optifine/color.properties} (or {@code mcpatcher/} twin) - the parsed key-to-ARGB
 * override map. It uses a parse-all-store-all model with a forgiving hex parser, carries the
 * index-DTO {@link #id} / {@link #pack}, provides typed accessors for the render-relevant families,
 * and (via {@link RuleSet}) merges per-key across the pack stack.
 *
 * <p>Two families are live today through {@code RendererContext.findColorOverride}: the biome-tint pack
 * keys ({@code grass.*} / {@code foliage.*} / {@code water.*}) and {@code redstone.<power>}. The rest
 * ({@code potion.*}, {@code collar.*}, spawn-egg layers, ...) are parsed and reachable but not yet
 * consumed; unknown keys stay stored and harmless.
 *
 * @param id the pack-relative source id
 * @param pack the owning pack
 * @param overrides the parsed key-to-opaque-ARGB map
 */
public record ColorProperties(
    @NotNull ResourceId id,
    @NotNull PackId pack,
    @NotNull ConcurrentMap<String, Integer> overrides
) {

    /** The empty color properties - a pack that ships no {@code color.properties}. */
    public static final @NotNull ColorProperties EMPTY = new ColorProperties(
        new ResourceId("minecraft", "color.properties"), PackId.VANILLA, Concurrent.newMap());

    /**
     * Parses a {@code color.properties} file body. Each value is read through the forgiving hex parser
     * ({@code 0x} / {@code #} / bare hex, alpha forced opaque); a blank or malformed value is skipped
     * per-key rather than failing the whole file.
     *
     * @param content the file body
     * @param id the pack-relative source id
     * @param pack the owning pack
     * @return the parsed color properties
     * @throws PipelineException if the body cannot be read as a properties stream
     */
    public static @NotNull ColorProperties parse(@NotNull String content, @NotNull ResourceId id, @NotNull PackId pack) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read color.properties '%s'", id);
        }

        ConcurrentMap<String, Integer> overrides = Concurrent.newMap();
        for (String key : props.stringPropertyNames()) {
            Integer color = parseColor(props.getProperty(key));
            if (color != null) overrides.put(key, color);
        }
        return new ColorProperties(id, pack, overrides);
    }

    /**
     * The override for a raw property key, if present.
     *
     * @param key the property key
     * @return the opaque ARGB override, or empty when the key is absent
     */
    public @NotNull Optional<Integer> get(@NotNull String key) {
        return this.overrides.getOptional(key);
    }

    /**
     * The redstone-wire tint override for a power level - {@code redstone.<power>}.
     *
     * @param power the redstone power level, {@code 0..15}
     * @return the override, or empty when the pack does not supply it
     */
    public @NotNull Optional<Integer> redstoneTint(int power) {
        return get("redstone." + power);
    }

    /**
     * The potion-liquid tint override for an effect - {@code potion.<effect>}.
     *
     * @param effect the effect id
     * @return the override, or empty when the pack does not supply it
     */
    public @NotNull Optional<Integer> potionColor(@NotNull ResourceId effect) {
        return get("potion." + effect.name());
    }

    /**
     * The wolf-collar tint override for a dye - {@code collar.<dye>}.
     *
     * @param dye the vanilla dye
     * @return the override, or empty when the pack does not supply it
     */
    public @NotNull Optional<Integer> collarColor(@NotNull DyeColor.Vanilla dye) {
        return get("collar." + dye.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this carries no overrides.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return this.overrides.isEmpty();
    }

    /**
     * Parses a hex colour value ({@code 0x} / {@code #} / bare hex), forcing the alpha channel opaque -
     * {@code color.properties} values carry only RGB. Returns {@code null} for a blank or unparseable
     * value so the caller skips the key rather than failing the file.
     */
    private static Integer parseColor(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X"))
                return 0xFF000000 | (int) Long.parseLong(trimmed.substring(2), 16);
            if (trimmed.startsWith("#"))
                return 0xFF000000 | (int) Long.parseLong(trimmed.substring(1), 16);
            return 0xFF000000 | (int) Long.parseLong(trimmed, 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
