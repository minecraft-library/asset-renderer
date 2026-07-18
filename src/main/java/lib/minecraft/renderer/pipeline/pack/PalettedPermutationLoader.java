package lib.minecraft.renderer.pipeline.pack;

import lib.minecraft.renderer.engine.texture.PalettedPermutationSource;
import lib.minecraft.renderer.json.JsonException;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.pack.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            JsonNode json = JsonNode.parse(container.bytes(entry).orElseThrow());
            Optional<JsonNode> sources = json.findArray("sources");
            if (sources.isEmpty()) return;
            for (JsonNode element : sources.get().elements()) {
                if (!element.isObject()) continue;
                if (!PALETTED_PERMUTATIONS_TYPE.equals(element.getString("type"))) continue;
                parseSource(element).ifPresent(out::add);
            }
        } catch (JsonException ex) {
            System.err.printf("Skipping malformed atlas '%s': %s%n", entry, ex.getMessage());
        }
    }

    /** Parses a single {@code paletted_permutations} source object, or empty when it is malformed. */
    private static @NotNull Optional<PalettedPermutationSource> parseSource(@NotNull JsonNode source) {
        String paletteKey = source.getString("palette_key");
        Optional<JsonNode> permutationsNode = source.findObject("permutations");
        if (paletteKey == null || permutationsNode.isEmpty() || source.findArray("textures").isEmpty())
            return Optional.empty();

        Map<String, String> permutations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : permutationsNode.get().members())
            if (entry.getValue().isPrimitive()) permutations.put(entry.getKey(), entry.getValue().stringValue().orElseThrow());

        List<String> textures = source.getStrings("textures");

        if (permutations.isEmpty() || textures.isEmpty()) return Optional.empty();
        return Optional.of(new PalettedPermutationSource(paletteKey, Map.copyOf(permutations), List.copyOf(textures)));
    }

}
