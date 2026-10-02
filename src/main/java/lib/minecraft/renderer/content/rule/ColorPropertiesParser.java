package lib.minecraft.renderer.content.rule;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.rule.ColorProperties;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads a pack's {@code optifine/color.properties} (or {@code mcpatcher/} twin) body into a
 * {@link ColorProperties}. Parse-all-store-all with a forgiving hex parser: a blank or malformed
 * value is skipped per-key rather than failing the whole file, and an unknown key stays stored and
 * harmless.
 */
@UtilityClass
@Parity(claim = "asset-layer")
@Parity(claim = "pack-rule-layer")
public class ColorPropertiesParser {

    /**
     * Parses a {@code color.properties} file body. Each value is read through the forgiving hex parser
     * ({@code 0x} / {@code #} / bare hex, alpha forced opaque); a blank or malformed value is skipped
     * per-key rather than failing the whole file.
     *
     * @param content the file body
     * @param id the pack-relative source id
     * @param pack the owning pack
     * @return the parsed color properties
     * @throws ContentException if the body cannot be read as a properties stream
     */
    public static @NotNull ColorProperties parse(@NotNull String content, @NotNull ResourceId id, @NotNull PackId pack) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (IOException ex) {
            throw new ContentException(ex, "Failed to read color.properties '%s'", id);
        }

        return new ColorProperties(id, pack, props.stringPropertyNames()
            .stream()
            .flatMap(key -> parseColor(props.getProperty(key)).stream().map(color -> Map.entry(key, color)))
            .collect(Concurrent.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * Parses a hex colour value ({@code 0x} / {@code #} / bare hex), forcing the alpha channel opaque -
     * {@code color.properties} values carry only RGB. Empty for a blank or unparseable value, so the
     * caller skips the key rather than failing the file.
     */
    private static @NotNull Optional<Integer> parseColor(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String trimmed = value.trim();
        try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X"))
                return Optional.of(0xFF000000 | (int) Long.parseLong(trimmed.substring(2), 16));
            if (trimmed.startsWith("#"))
                return Optional.of(0xFF000000 | (int) Long.parseLong(trimmed.substring(1), 16));
            return Optional.of(0xFF000000 | (int) Long.parseLong(trimmed, 16));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

}
