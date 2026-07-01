package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.asset.rule.IntRange;
import lib.minecraft.renderer.asset.rule.NbtCondition;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Scans a texture pack's directory tree for Optifine and MCPatcher Custom Item Texture
 * {@code .properties} files and parses each into a {@link CitRule}.
 * <p>
 * The two supported layouts - {@code assets/minecraft/optifine/cit/**} and
 * {@code assets/minecraft/mcpatcher/cit/**} - share the same grammar, so rules from both paths
 * are merged into a single result list and sorted by descending weight. Only {@code type=item}
 * rules are kept; a rule is discarded when it names no output {@code texture} or matches no items.
 *
 * @see CitRule
 */
@UtilityClass
public class CitLoader {

    /**
     * The Optifine and MCPatcher CIT subtrees scanned by {@link #load(Path)}, in that order.
     */
    private static final @NotNull String[] CIT_ROOTS = {
        VanillaSourcePaths.OPTIFINE_CIT_DIR,
        VanillaSourcePaths.MCPATCHER_CIT_DIR
    };

    /**
     * Walks {@code packRoot} looking for CIT property files and returns the parsed rule list,
     * sorted by descending weight so the highest-priority rules appear first.
     *
     * @param packRoot the texture pack root directory
     * @return the parsed rules, or an empty list when the pack has no CIT files
     */
    public static @NotNull ConcurrentList<CitRule> load(@NotNull Path packRoot) {
        ArrayList<CitRule> rules = new ArrayList<>();

        for (String root : CIT_ROOTS) {
            Path rootPath = packRoot.resolve(root);
            if (!Files.isDirectory(rootPath)) continue;

            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".properties"))
                    .forEach(p -> parseFile(p).ifPresent(rules::add));
            } catch (IOException ex) {
                throw new PipelineException(ex, "Failed to scan CIT directory '%s'", rootPath);
            }
        }

        rules.sort(Comparator.comparingInt(CitRule::weight).reversed());
        return Concurrent.adoptList(rules);
    }

    /**
     * Parses one CIT {@code .properties} file into a {@link CitRule}. Returns empty when the file
     * is not a {@code type=item} rule, names no {@code texture}, or matches no items. Item and
     * enchantment ids are namespaced to {@code minecraft:} when unqualified. Enchantment levels
     * come from the {@code enchantmentLevels} property as space-separated {@code id=range} tokens;
     * per-key ranges default to {@link IntRange#ANY} when the range fails to parse. Any property
     * prefixed {@code nbt.} becomes an {@link NbtCondition} keyed by the path after the prefix.
     *
     * @param file the CIT property file to parse
     * @return the parsed rule, or empty when the file does not describe a usable item rule
     * @throws PipelineException if the file cannot be read
     */
    private static @NotNull Optional<CitRule> parseFile(@NotNull Path file) {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            props.load(reader);
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read CIT file '%s'", file);
        }

        String type = props.getProperty("type", "item");
        if (!"item".equalsIgnoreCase(type)) return Optional.empty();

        String texture = props.getProperty("texture");
        if (texture == null || texture.isBlank()) return Optional.empty();

        int weight = parseIntOrDefault(props.getProperty("weight"), 0);

        ArrayList<String> matchedItems = new ArrayList<>();
        String itemsProperty = props.getProperty("items", props.getProperty("matchItems", ""));
        for (String id : itemsProperty.split("\\s+")) {
            if (!id.isBlank()) matchedItems.add(id.contains(":") ? id : VanillaSourcePaths.MINECRAFT_NAMESPACE + id);
        }
        if (matchedItems.isEmpty()) return Optional.empty();

        Optional<IntRange> damageRange = parseRange(props.getProperty("damage"));
        Optional<IntRange> stackSizeRange = parseRange(props.getProperty("stackSize"));

        ArrayList<String> enchantmentIds = new ArrayList<>();
        String enchantments = props.getProperty("enchantmentIDs", "");
        for (String id : enchantments.split("\\s+")) {
            if (!id.isBlank()) enchantmentIds.add(id.contains(":") ? id : VanillaSourcePaths.MINECRAFT_NAMESPACE + id);
        }

        HashMap<String, IntRange> enchantmentLevels = new HashMap<>();
        String levels = props.getProperty("enchantmentLevels", "");
        for (String token : levels.split("\\s+")) {
            if (token.isBlank()) continue;
            int eq = token.indexOf('=');
            if (eq < 0) continue;
            String key = token.substring(0, eq);
            IntRange range = parseRange(token.substring(eq + 1)).orElse(IntRange.ANY);
            enchantmentLevels.put(key.contains(":") ? key : VanillaSourcePaths.MINECRAFT_NAMESPACE + key, range);
        }

        HashMap<String, NbtCondition> nbtConditions = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith("nbt.")) continue;
            nbtConditions.put(name.substring("nbt.".length()), NbtCondition.parse(props.getProperty(name)));
        }

        return Optional.of(new CitRule(
            file.toString(),
            weight,
            Concurrent.adoptList(matchedItems),
            damageRange,
            stackSizeRange,
            Concurrent.adoptMap(nbtConditions),
            Concurrent.adoptList(enchantmentIds),
            Concurrent.adoptMap(enchantmentLevels),
            normalizeTextureId(texture)
        ));
    }

    /**
     * Normalises a raw {@code texture} property value into a namespaced texture id. An already
     * namespaced value is returned unchanged; an {@code assets/minecraft/textures/} path is
     * stripped to {@code minecraft:<path>} with any {@code .png} suffix removed; anything else is
     * prefixed with {@code minecraft:}.
     *
     * @param texture the raw texture property value
     * @return the namespaced texture id
     */
    private static @NotNull String normalizeTextureId(@NotNull String texture) {
        if (texture.contains(":")) return texture;
        if (texture.startsWith(VanillaSourcePaths.TEXTURES_PREFIX)) {
            String trimmed = texture.substring(VanillaSourcePaths.TEXTURES_PREFIX.length());
            if (trimmed.endsWith(".png")) trimmed = trimmed.substring(0, trimmed.length() - 4);
            return VanillaSourcePaths.MINECRAFT_NAMESPACE + trimmed;
        }
        return VanillaSourcePaths.MINECRAFT_NAMESPACE + texture;
    }

    /**
     * Parses a numeric-range expression into an {@link IntRange}, tolerating a null or blank input
     * (empty result) and a malformed expression (empty result rather than propagating).
     *
     * @param expression the range expression, or null
     * @return the parsed range, or empty when absent or unparseable
     */
    private static @NotNull Optional<IntRange> parseRange(String expression) {
        if (expression == null || expression.isBlank()) return Optional.empty();
        try {
            return Optional.of(IntRange.parse(expression));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Parses a base-10 integer, falling back to {@code fallback} for null, blank, or non-numeric
     * input.
     *
     * @param value the string to parse, or null
     * @param fallback the value returned when parsing fails
     * @return the parsed integer, or {@code fallback}
     */
    private static int parseIntOrDefault(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

}
