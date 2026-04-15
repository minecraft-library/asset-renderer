package dev.sbs.renderer.pipeline.pack;

import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.pipeline.VanillaPaths;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * MCPatcher and Optifine {@code color.properties} override file, carrying pack-level colour
 * overrides for biome tints, particle colours, map colours, XP orbs, and similar hardcoded values.
 * <p>
 * The file is optional - packs that do not ship one are represented as
 * {@link #EMPTY}. Callers look up individual overrides via {@link #get(String)} with the raw
 * property key (e.g. {@code "grass.plains"} or {@code "particle.water"}).
 *
 * @param overrides the key-to-ARGB colour map parsed from the properties file
 */
public record ColorProperties(@NotNull ConcurrentMap<String, Integer> overrides) {

    /** The empty color properties containing no overrides. */
    public static final @NotNull ColorProperties EMPTY = new ColorProperties(Concurrent.newMap());

    /**
     * Loads a {@code color.properties} file from the given texture pack root, trying both the
     * Optifine and MCPatcher well-known locations.
     *
     * @param packRoot the texture pack root directory
     * @return the parsed overrides, or {@link #EMPTY} when no file is present
     */
    public static @NotNull ColorProperties loadFrom(@NotNull Path packRoot) {
        Path[] candidates = {
            packRoot.resolve(VanillaPaths.OPTIFINE_COLOR_PROPS),
            packRoot.resolve(VanillaPaths.MCPATCHER_COLOR_PROPS)
        };

        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;

            Properties props = new Properties();
            try (var reader = Files.newBufferedReader(candidate)) {
                props.load(reader);
            } catch (IOException ex) {
                throw new RendererException(ex, "Failed to read color.properties '%s'", candidate);
            }

            ConcurrentMap<String, Integer> overrides = Concurrent.newMap();
            for (String key : props.stringPropertyNames()) {
                Integer color = parseColor(props.getProperty(key));
                if (color != null) overrides.put(key, color);
            }
            return new ColorProperties(overrides);
        }

        return EMPTY;
    }

    /**
     * Returns the colour override for a property key, if present.
     *
     * @param key the property key
     * @return the ARGB colour, or empty if no override exists
     */
    public @NotNull Optional<Integer> get(@NotNull String key) {
        return Optional.ofNullable(this.overrides.get(key));
    }

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
