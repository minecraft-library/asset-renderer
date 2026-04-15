package dev.sbs.renderer.pipeline.loader;

import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.pipeline.VanillaPaths;
import dev.sbs.renderer.pipeline.pack.CitRule;
import dev.sbs.renderer.pipeline.pack.IntRange;
import dev.sbs.renderer.pipeline.pack.NbtCondition;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Scans a texture pack's directory tree for Optifine and MCPatcher Custom Item Texture
 * {@code .properties} files and parses each into a {@link CitRule}.
 * <p>
 * The two supported layouts - {@code assets/minecraft/optifine/cit/**} and
 * {@code assets/minecraft/mcpatcher/cit/**} - share the same grammar, so rules from both paths
 * are merged into a single result list and sorted by descending weight.
 */
@UtilityClass
public class CitLoader {

    private static final @NotNull String[] CIT_ROOTS = {
        VanillaPaths.OPTIFINE_CIT_DIR,
        VanillaPaths.MCPATCHER_CIT_DIR
    };

    /**
     * Walks {@code packRoot} looking for CIT property files and returns the parsed rule list,
     * sorted by descending weight so the highest-priority rules appear first.
     *
     * @param packRoot the texture pack root directory
     * @return the parsed rules, or an empty list when the pack has no CIT files
     */
    public static @NotNull ConcurrentList<CitRule> load(@NotNull Path packRoot) {
        ConcurrentList<CitRule> rules = Concurrent.newList();

        for (String root : CIT_ROOTS) {
            Path rootPath = packRoot.resolve(root);
            if (!Files.isDirectory(rootPath)) continue;

            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".properties"))
                    .forEach(p -> parseFile(p).ifPresent(rules::add));
            } catch (IOException ex) {
                throw new RendererException(ex, "Failed to scan CIT directory '%s'", rootPath);
            }
        }

        rules.sort(Comparator.comparingInt(CitRule::weight).reversed());
        return rules;
    }

    private static @NotNull Optional<CitRule> parseFile(@NotNull Path file) {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            props.load(reader);
        } catch (IOException ex) {
            throw new RendererException(ex, "Failed to read CIT file '%s'", file);
        }

        String type = props.getProperty("type", "item");
        if (!"item".equalsIgnoreCase(type)) return Optional.empty();

        String texture = props.getProperty("texture");
        if (texture == null || texture.isBlank()) return Optional.empty();

        int weight = parseIntOrDefault(props.getProperty("weight"), 0);

        ConcurrentList<String> matchedItems = Concurrent.newList();
        String itemsProperty = props.getProperty("items", props.getProperty("matchItems", ""));
        for (String id : itemsProperty.split("\\s+")) {
            if (!id.isBlank()) matchedItems.add(id.contains(":") ? id : VanillaPaths.MINECRAFT_NAMESPACE + id);
        }
        if (matchedItems.isEmpty()) return Optional.empty();

        Optional<IntRange> damageRange = parseRange(props.getProperty("damage"));
        Optional<IntRange> stackSizeRange = parseRange(props.getProperty("stackSize"));

        ConcurrentList<String> enchantmentIds = Concurrent.newList();
        String enchantments = props.getProperty("enchantmentIDs", "");
        for (String id : enchantments.split("\\s+")) {
            if (!id.isBlank()) enchantmentIds.add(id.contains(":") ? id : VanillaPaths.MINECRAFT_NAMESPACE + id);
        }

        ConcurrentMap<String, IntRange> enchantmentLevels = Concurrent.newMap();
        String levels = props.getProperty("enchantmentLevels", "");
        for (String token : levels.split("\\s+")) {
            if (token.isBlank()) continue;
            int eq = token.indexOf('=');
            if (eq < 0) continue;
            String key = token.substring(0, eq);
            IntRange range = parseRange(token.substring(eq + 1)).orElse(IntRange.ANY);
            enchantmentLevels.put(key.contains(":") ? key : VanillaPaths.MINECRAFT_NAMESPACE + key, range);
        }

        ConcurrentMap<String, NbtCondition> nbtConditions = Concurrent.newMap();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith("nbt.")) continue;
            nbtConditions.put(name.substring("nbt.".length()), NbtCondition.parse(props.getProperty(name)));
        }

        return Optional.of(new CitRule(
            file.toString(),
            weight,
            matchedItems,
            damageRange,
            stackSizeRange,
            nbtConditions,
            enchantmentIds,
            enchantmentLevels,
            normalizeTextureId(texture)
        ));
    }

    private static @NotNull String normalizeTextureId(@NotNull String texture) {
        if (texture.contains(":")) return texture;
        if (texture.startsWith(VanillaPaths.TEXTURES_PREFIX)) {
            String trimmed = texture.substring(VanillaPaths.TEXTURES_PREFIX.length());
            if (trimmed.endsWith(".png")) trimmed = trimmed.substring(0, trimmed.length() - 4);
            return VanillaPaths.MINECRAFT_NAMESPACE + trimmed;
        }
        return VanillaPaths.MINECRAFT_NAMESPACE + texture;
    }

    private static @NotNull Optional<IntRange> parseRange(String expression) {
        if (expression == null || expression.isBlank()) return Optional.empty();
        try {
            return Optional.of(IntRange.parse(expression));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static int parseIntOrDefault(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

}
