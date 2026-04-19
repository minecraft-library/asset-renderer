package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.pipeline.VanillaPaths;
import lib.minecraft.renderer.pipeline.pack.CtmMethod;
import lib.minecraft.renderer.pipeline.pack.CtmRule;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * are scanned, and the combined rule list is sorted by descending weight.
 */
@UtilityClass
public class CtmLoader {

    private static final @NotNull String[] CTM_ROOTS = {
        VanillaPaths.OPTIFINE_CTM_DIR,
        VanillaPaths.MCPATCHER_CTM_DIR
    };

    /**
     * Walks {@code packRoot} looking for CTM property files and returns the parsed rule list,
     * sorted by descending weight.
     *
     * @param packRoot the texture pack root directory
     * @return the parsed rules, or an empty list when the pack has no CTM files
     */
    public static @NotNull ConcurrentList<CtmRule> load(@NotNull Path packRoot) {
        ConcurrentList<CtmRule> rules = Concurrent.newList();

        for (String root : CTM_ROOTS) {
            Path rootPath = packRoot.resolve(root);
            if (!Files.isDirectory(rootPath)) continue;

            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".properties"))
                    .forEach(p -> parseFile(p).ifPresent(rules::add));
            } catch (IOException ex) {
                throw new RendererException(ex, "Failed to scan CTM directory '%s'", rootPath);
            }
        }

        rules.sort(Comparator.comparingInt(CtmRule::weight).reversed());
        return rules;
    }

    private static @NotNull Optional<CtmRule> parseFile(@NotNull Path file) {
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            props.load(reader);
        } catch (IOException ex) {
            throw new RendererException(ex, "Failed to read CTM file '%s'", file);
        }

        String methodString = props.getProperty("method", "fixed");
        CtmMethod method = CtmMethod.parse(methodString);
        int weight = parseIntOrDefault(props.getProperty("weight"), 0);

        ConcurrentList<String> matchedBlocks = Concurrent.newList();
        String blocksValue = props.getProperty("matchBlocks", "");
        for (String id : blocksValue.split("\\s+")) {
            if (!id.isBlank()) matchedBlocks.add(id.contains(":") ? id : VanillaPaths.MINECRAFT_NAMESPACE + id);
        }

        ConcurrentList<String> matchedTiles = Concurrent.newList();
        String tilesMatch = props.getProperty("matchTiles", "");
        for (String tile : tilesMatch.split("\\s+")) {
            if (!tile.isBlank()) matchedTiles.add(tile);
        }

        ConcurrentList<String> tiles = Concurrent.newList();
        String tilesProperty = props.getProperty("tiles", "");
        for (String tile : tilesProperty.split("\\s+")) {
            if (!tile.isBlank()) tiles.add(tile);
        }
        if (tiles.isEmpty() && matchedBlocks.isEmpty() && matchedTiles.isEmpty()) return Optional.empty();

        ConcurrentList<Integer> weights = Concurrent.newList();
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
            matchedBlocks,
            matchedTiles,
            tiles,
            weights,
            faces
        ));
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
