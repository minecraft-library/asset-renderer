package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.engine.texture.PalettedPermutationSource;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collects the {@code minecraft:paletted_permutations} entries from every pack's
 * {@code assets/<ns>/atlases/*.json} into an ordered {@link PalettedPermutationSource} list for the
 * {@code TextureSynthesizer}. Atlas {@code sources} lists <b>concatenate</b> across
 * packs ascending (vanilla atlas semantics - additive, not replace; the one concatenation-merge file
 * kind), so a higher pack extends the synthesizable set without dropping the vanilla
 * trim overlays.
 *
 * <p>Vanilla ships the trim overlays in {@code atlases/items.json} (helmet / chestplate / leggings /
 * boots trim base patterns permuted by the twelve trim materials); a vanilla-only stack yields exactly
 * those, which the item renderer's explicit {@code TrimKit} branch already serves before resolution,
 * so synthesis is inert on vanilla.
 */
@UtilityClass
public class PalettedPermutationLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private static final @NotNull String PALETTED_PERMUTATIONS_TYPE = "minecraft:paletted_permutations";

    /**
     * Loads and concatenates the paletted-permutation atlas sources across the whole pack stack,
     * ascending.
     *
     * @param stack the resolved pack stack
     * @return the ordered paletted-permutation sources (lower packs first)
     */
    public static @NotNull List<PalettedPermutationSource> load(@NotNull PackStack stack) {
        List<PalettedPermutationSource> sources = new ArrayList<>();
        for (ResourcePack pack : stack.ascending()) {
            PackContainer container = pack.container();
            for (PackRoot root : pack.roots())
                for (String namespace : pack.namespaces()) {
                    String atlasesPrefix = root.prefix() + VanillaSourcePaths.assetSubdir(namespace, VanillaSourcePaths.ATLASES_SUBDIR);
                    scanRoot(container, atlasesPrefix, sources);
                }
        }
        return List.copyOf(sources);
    }

    /** Scans one {@code (root x namespace)} atlases subtree in sorted path order, appending its permutation sources. */
    private static void scanRoot(@NotNull PackContainer container, @NotNull String atlasesPrefix, @NotNull List<PalettedPermutationSource> out) {
        List<String> files = container.entries(atlasesPrefix)
            .filter(p -> p.endsWith(".json"))
            .sorted()
            .toList();

        for (String file : files) parseAtlas(container, file, out);
    }

    /** Parses one atlas file, appending each {@code paletted_permutations} source it declares. */
    private static void parseAtlas(@NotNull PackContainer container, @NotNull String entry, @NotNull List<PalettedPermutationSource> out) {
        try {
            AtlasFile atlas = GSON.fromJson(new String(container.bytes(entry).orElseThrow(), StandardCharsets.UTF_8), AtlasFile.class);
            if (atlas == null || atlas.sources() == null) return;
            for (SourceDto source : atlas.sources()) {
                if (source == null || !PALETTED_PERMUTATIONS_TYPE.equals(source.type())) continue;
                parseSource(source).ifPresent(out::add);
            }
        } catch (JsonSyntaxException ex) {
            System.err.printf("Skipping malformed atlas '%s': %s%n", entry, ex.getMessage());
        }
    }

    /** Builds a {@link PalettedPermutationSource} from a source object, or empty when it is malformed. */
    private static @NotNull Optional<PalettedPermutationSource> parseSource(@NotNull SourceDto source) {
        if (source.paletteKey() == null
            || source.permutations() == null || source.permutations().isEmpty()
            || source.textures() == null || source.textures().isEmpty())
            return Optional.empty();
        return Optional.of(new PalettedPermutationSource(source.paletteKey(), Map.copyOf(source.permutations()), List.copyOf(source.textures())));
    }

    /** One atlas file's payload: the {@code sources} list ({@code type}-tagged entries). */
    record AtlasFile(@Nullable List<SourceDto> sources) {}

    /**
     * One atlas {@code sources[]} entry; only {@code minecraft:paletted_permutations} entries are kept.
     *
     * @param type the source type discriminator
     * @param paletteKey the palette-key texture the permutation recolours against
     * @param permutations the suffix to palette-swap texture map
     * @param textures the base texture list the permutations apply to
     */
    record SourceDto(@Nullable String type, @SerializedName("palette_key") @Nullable String paletteKey,
                     @Nullable Map<String, String> permutations, @Nullable List<String> textures) {}
}
