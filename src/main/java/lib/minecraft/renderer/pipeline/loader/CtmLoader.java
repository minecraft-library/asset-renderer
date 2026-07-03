package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.rule.CtmMethod;
import lib.minecraft.renderer.asset.rule.CtmRule;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Scans a texture pack's directory tree for Optifine and MCPatcher Connected Textures
 * {@code .properties} files and parses each into a {@link CtmRule}.
 * <p>
 * Both {@code assets/minecraft/optifine/ctm/**} and {@code assets/minecraft/mcpatcher/ctm/**}
 * are scanned, and the combined rule list is sorted by descending weight. A rule is discarded when
 * it declares no {@code tiles}, {@code matchBlocks}, or {@code matchTiles}. Only the context-free
 * subset of the CTM grammar is retained (see {@link CtmRule}).
 *
 * @see CtmRule
 */
@UtilityClass
public class CtmLoader {

    /**
     * The Optifine and MCPatcher CTM subtrees scanned by {@link #load(Path)}, in that order.
     */
    private static final @NotNull String[] CTM_ROOTS = {
        VanillaSourcePaths.OPTIFINE_CTM_DIR,
        VanillaSourcePaths.MCPATCHER_CTM_DIR
    };

    /**
     * Walks every asset root for CTM property files and returns the merged rule list, sorted by
     * descending weight. Each root is scanned independently via {@link #load(Path)}; ties on
     * weight preserve declaration order, so later-priority packs naturally win on weight-tied
     * collisions when {@code assetRoots} is in ascending-priority order.
     *
     * @param assetRoots the ordered asset roots
     * @return the parsed rules, weight-sorted
     */
    public static @NotNull ConcurrentList<CtmRule> load(@NotNull ConcurrentList<Path> assetRoots) {
        ArrayList<CtmRule> rules = new ArrayList<>();
        for (Path root : assetRoots) rules.addAll(load(root));
        rules.sort(Comparator.comparingInt(CtmRule::weight).reversed());
        return Concurrent.adoptList(rules);
    }

    /**
     * Walks {@code packRoot} looking for CTM property files and returns the parsed rule list,
     * sorted by descending weight.
     *
     * @param packRoot the texture pack root directory
     * @return the parsed rules, or an empty list when the pack has no CTM files
     */
    public static @NotNull ConcurrentList<CtmRule> load(@NotNull Path packRoot) {
        ArrayList<CtmRule> rules = new ArrayList<>();

        for (String root : CTM_ROOTS) {
            Path rootPath = packRoot.resolve(root);
            if (!Files.isDirectory(rootPath)) continue;

            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".properties"))
                    .forEach(p -> parseFile(p).ifPresent(rules::add));
            } catch (IOException ex) {
                throw new PipelineException(ex, "Failed to scan CTM directory '%s'", rootPath);
            }
        }

        rules.sort(Comparator.comparingInt(CtmRule::weight).reversed());
        return Concurrent.adoptList(rules);
    }

    /**
     * Parses one CTM {@code .properties} file into a {@link CtmRule}. Returns empty when the file
     * declares neither {@code tiles}, {@code matchBlocks}, nor {@code matchTiles}. The
     * {@code method} defaults to {@code fixed}; block ids are namespaced to {@code minecraft:} when
     * unqualified while tile ids are kept verbatim. Malformed {@code weights} tokens and unknown
     * {@code faces} names are dropped silently; an empty {@code faces} set falls back to
     * {@link CtmRule.Face#ALL}.
     *
     * @param file the CTM property file to parse
     * @return the parsed rule, or empty when the file matches nothing
     * @throws PipelineException if the file cannot be read
     */
    private static @NotNull Optional<CtmRule> parseFile(@NotNull Path file) {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            props.load(reader);
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read CTM file '%s'", file);
        }

        String methodString = props.getProperty("method", "fixed");
        CtmMethod method = CtmMethod.parse(methodString);
        int weight = parseIntOrDefault(props.getProperty("weight"), 0);

        ArrayList<String> matchedBlocks = new ArrayList<>();
        String blocksValue = props.getProperty("matchBlocks", "");
        for (String id : blocksValue.split("\\s+")) {
            if (!id.isBlank()) matchedBlocks.add(id.contains(":") ? id : VanillaSourcePaths.MINECRAFT_NAMESPACE + id);
        }

        ArrayList<String> matchedTiles = new ArrayList<>();
        String tilesMatch = props.getProperty("matchTiles", "");
        for (String tile : tilesMatch.split("\\s+")) {
            if (!tile.isBlank()) matchedTiles.add(tile);
        }

        ArrayList<String> tiles = new ArrayList<>();
        String tilesProperty = props.getProperty("tiles", "");
        for (String tile : tilesProperty.split("\\s+")) {
            if (!tile.isBlank()) tiles.add(tile);
        }
        if (tiles.isEmpty() && matchedBlocks.isEmpty() && matchedTiles.isEmpty()) return Optional.empty();

        ArrayList<Integer> weights = new ArrayList<>();
        String weightsProperty = props.getProperty("weights", "");
        for (String token : weightsProperty.split("\\s+")) {
            if (token.isBlank()) continue;
            try {
                weights.add(Integer.parseInt(token));
            } catch (NumberFormatException ignored) {
                // silently drop invalid weight entries
            }
        }

        EnumSet<CtmRule.Face> faces = EnumSet.noneOf(CtmRule.Face.class);
        String facesProperty = props.getProperty("faces", "all");
        for (String name : facesProperty.split("\\s+")) {
            if (name.isBlank()) continue;
            try {
                faces.add(CtmRule.Face.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // silently drop invalid face entries
            }
        }
        if (faces.isEmpty()) faces.add(CtmRule.Face.ALL);

        return Optional.of(new CtmRule(
            file.toString(),
            weight,
            method,
            Concurrent.adoptList(matchedBlocks),
            Concurrent.adoptList(matchedTiles),
            Concurrent.adoptList(tiles),
            Concurrent.adoptList(weights),
            faces
        ));
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
