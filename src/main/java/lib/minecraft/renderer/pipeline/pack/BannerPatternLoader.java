package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.BannerPattern;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;

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
     * The banner-pattern registry subtree. A {@code data} subtree rather than an {@code assets} one,
     * and scanned at the vanilla namespace alone - the registry ships in the client jar and no
     * resource pack in this stack carries a {@code data} root.
     */
    private static final @NotNull PackSubtree.Subtree BANNER_PATTERNS = PackSubtree.Subtree.data(
        VanillaSourcePaths.MINECRAFT_NAMESPACE_DIR, "banner_pattern", ".json");

    /**
     * Loads banner pattern definitions across the whole pack stack. Packs are visited ascending,
     * each over its base and overlay roots; per pattern id, a higher root replaces lower roots'
     * definition. A vanilla-only stack scans exactly the vanilla root.
     *
     * @param stack the resolved pack stack
     * @return a map of pattern id to pattern descriptor
     */
    public static @NotNull ConcurrentMap<String, BannerPattern> load(@NotNull PackStack stack) {
        return PackSubtree.walk(stack, BANNER_PATTERNS)
            .stream()
            .map(BannerPatternLoader::parsePattern)
            .flatMap(Optional::stream)
            .collect(Concurrent.toUnmodifiableMap(BannerPattern::id, Function.identity(), (lower, higher) -> higher));
    }

    /**
     * Parses a single pattern file into a {@link BannerPattern} carrying its derived registry id. The
     * id is the entry's namespace prefix joined to its stem, so the {@code .json} suffix is dropped
     * and the namespace prepended. A file with no {@code asset_id} field yields nothing; a missing
     * {@code translation_key} yields the empty string.
     *
     * @param entry the pattern JSON entry to parse
     * @return the parsed pattern, or empty when the file carries no {@code asset_id}
     * @throws RuntimeException if the file cannot be read
     */
    private static @NotNull Optional<BannerPattern> parsePattern(@NotNull PackSubtree.Entry entry) {
        String patternId = VanillaSourcePaths.namespacePrefix(entry.namespace()) + entry.stem();
        PatternDoc doc = GSON.fromJson(new String(entry.container().bytes(entry.entryPath()).orElseThrow(), StandardCharsets.UTF_8), PatternDoc.class);
        if (doc == null || doc.assetId() == null) return Optional.empty();

        String translationKey = doc.translationKey() == null ? "" : doc.translationKey();
        return Optional.of(new BannerPattern(patternId, doc.assetId(), translationKey));
    }

    /**
     * One banner pattern file's payload. A file with no {@code asset_id} (null here) is skipped; a
     * missing {@code translation_key} defaults to the empty string.
     *
     * @param assetId the pattern {@code asset_id}, or {@code null} when the file omits it
     * @param translationKey the pattern {@code translation_key}, or {@code null} when omitted
     */
    record PatternDoc(@SerializedName("asset_id") @Nullable String assetId,
                      @SerializedName("translation_key") @Nullable String translationKey) {}

}
