package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.binding.BannerPattern;
import lib.minecraft.renderer.pipeline.VanillaPaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * A loader that reads vanilla banner pattern JSON files from
 * {@code data/minecraft/banner_pattern/} and produces {@link BannerPattern} entries for every
 * pattern in the registry. Banner and shield pattern rendering share the same registry since
 * 1.19.4.
 * <p>
 * Each pattern file is a two-field JSON object:
 * <pre>
 * { "asset_id": "minecraft:creeper", "translation_key": "block.minecraft.banner.creeper" }
 * </pre>
 * The loader derives the pattern id from the file path relative to the registry root, matching
 * how the vanilla registry keys patterns.
 */
@UtilityClass
public class BannerPatternLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads every banner pattern definition from the given extracted pack root. Returns an
     * empty map when the pattern directory is absent (older versions or stripped jars).
     *
     * @param packRoot the extracted pack root directory
     * @return a map of pattern id to pattern descriptor
     */
    public static @NotNull ConcurrentMap<String, BannerPattern> load(@NotNull Path packRoot) {
        Path patternDir = packRoot.resolve("data/minecraft/banner_pattern");
        ConcurrentMap<String, BannerPattern> result = Concurrent.newMap();
        if (!Files.isDirectory(patternDir)) return result;

        try (Stream<Path> files = Files.walk(patternDir)) {
            files.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(file -> parsePattern(patternDir, file, result));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to walk banner pattern directory " + patternDir, ex);
        }
        return result;
    }

    private static void parsePattern(
        @NotNull Path patternDir,
        @NotNull Path file,
        @NotNull ConcurrentMap<String, BannerPattern> result
    ) {
        String relative = patternDir.relativize(file).toString().replace('\\', '/');
        String patternId = VanillaPaths.MINECRAFT_NAMESPACE + relative.substring(0, relative.length() - ".json".length());
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null || !root.has("asset_id")) return;

            String assetId = root.get("asset_id").getAsString();
            String translationKey = root.has("translation_key") ? root.get("translation_key").getAsString() : "";
            result.put(patternId, new BannerPattern(patternId, assetId, translationKey));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read banner pattern " + file, ex);
        }
    }

}
