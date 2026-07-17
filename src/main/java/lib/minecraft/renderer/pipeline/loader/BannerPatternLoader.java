package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;

/**
 * A loader that reads vanilla banner pattern JSON files from
 * {@code data/minecraft/banner_pattern/} and produces one {@link BannerPattern} entry per pattern
 * file that carries an {@code asset_id}. Banner and shield pattern rendering share the same
 * registry since 1.19.4.
 * <p>
 * Each pattern file is a two-field JSON object:
 * <pre>{@code
 * { "asset_id": "minecraft:creeper", "translation_key": "block.minecraft.banner.creeper" }
 * }</pre>
 * The loader derives the pattern id from the file path relative to the registry root (with the
 * {@code minecraft:} namespace prepended), matching how the vanilla registry keys patterns. Files
 * that fail to parse or lack an {@code asset_id} are skipped; a missing {@code translation_key}
 * defaults to the empty string.
 *
 * @see BannerPattern
 */
@UtilityClass
public class BannerPatternLoader {

    /**
     * Shared Gson configured with the project defaults, used to parse pattern files.
     */
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads every banner pattern definition from the given extracted pack root. Returns an
     * empty map when the pattern directory is absent (older versions or stripped jars).
     *
     * @param packRoot the extracted pack root directory
     * @return a map of pattern id to pattern descriptor
     */
    public static @NotNull ConcurrentMap<String, BannerPattern> load(@NotNull Path packRoot) {
        HashMap<String, BannerPattern> merged = new HashMap<>();
        scanRoot(new PackContainer.Directory(packRoot), "", merged);
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Loads banner pattern definitions across the whole pack stack. Packs are visited ascending,
     * each over its base and overlay roots; per pattern id, a higher root replaces lower roots'
     * definition. A vanilla-only stack scans exactly the vanilla root.
     *
     * @param stack the resolved pack stack
     * @return a map of pattern id to pattern descriptor
     */
    public static @NotNull ConcurrentMap<String, BannerPattern> load(@NotNull PackStack stack) {
        HashMap<String, BannerPattern> merged = new HashMap<>();
        for (ResourcePack pack : stack.ascending()) {
            PackContainer container = pack.container();
            for (PackRoot root : pack.roots())
                scanRoot(container, root.prefix(), merged);
        }
        return Concurrent.adoptMap(merged).toUnmodifiable();
    }

    /**
     * Scans one root's {@code data/minecraft/banner_pattern/} subtree and merges its pattern entries
     * into the running map. Caller provides the map so multiple roots can layer later-wins without
     * intermediate copies. No-op when the subtree is absent.
     *
     * @param container the pack container to read through
     * @param prefix the container root prefix ({@code ""} for base, {@code "<overlay>/"} for an overlay)
     * @param result the running map that accumulates parsed patterns across roots
     * @throws RuntimeException if the pattern directory cannot be walked
     */
    private static void scanRoot(@NotNull PackContainer container, @NotNull String prefix, @NotNull HashMap<String, BannerPattern> result) {
        String patternPrefix = prefix + "data/minecraft/banner_pattern";
        container.entries(patternPrefix)
            .filter(p -> p.endsWith(".json"))
            .forEach(entry -> parsePattern(container, patternPrefix, entry, result));
    }

    /**
     * Parses a single pattern file and stores the resulting {@link BannerPattern} under its
     * derived registry id. The id is the entry path relative to {@code patternPrefix} (with the
     * {@code .json} suffix stripped and the {@code minecraft:} namespace prepended). Files with no
     * {@code asset_id} field are skipped silently; a missing {@code translation_key} yields the empty
     * string.
     *
     * @param container the pack container to read through
     * @param patternPrefix the registry root the entry is relativized against for id derivation
     * @param entry the pattern JSON entry path to parse
     * @param result the running map that receives the parsed entry
     * @throws RuntimeException if the file cannot be read
     */
    private static void parsePattern(
        @NotNull PackContainer container,
        @NotNull String patternPrefix,
        @NotNull String entry,
        @NotNull HashMap<String, BannerPattern> result
    ) {
        String relative = entry.substring(patternPrefix.length() + 1);
        String patternId = VanillaSourcePaths.MINECRAFT_NAMESPACE + relative.substring(0, relative.length() - ".json".length());
        JsonObject root = GSON.fromJson(new String(container.bytes(entry).orElseThrow(), StandardCharsets.UTF_8), JsonObject.class);
        if (root == null || !root.has("asset_id")) return;

        String assetId = root.get("asset_id").getAsString();
        String translationKey = root.has("translation_key") ? root.get("translation_key").getAsString() : "";
        result.put(patternId, new BannerPattern(patternId, assetId, translationKey));
    }

}
