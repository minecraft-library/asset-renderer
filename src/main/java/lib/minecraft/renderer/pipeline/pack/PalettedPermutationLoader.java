package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.engine.texture.PalettedPermutationSource;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.pack.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
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
            JsonObject json = GSON.fromJson(new String(container.bytes(entry).orElseThrow(), StandardCharsets.UTF_8), JsonObject.class);
            if (json == null || !json.has("sources") || !json.get("sources").isJsonArray()) return;
            for (JsonElement element : json.getAsJsonArray("sources")) {
                if (!element.isJsonObject()) continue;
                JsonObject source = element.getAsJsonObject();
                if (!PALETTED_PERMUTATIONS_TYPE.equals(string(source, "type"))) continue;
                parseSource(source).ifPresent(out::add);
            }
        } catch (JsonSyntaxException ex) {
            System.err.printf("Skipping malformed atlas '%s': %s%n", entry, ex.getMessage());
        }
    }

    /** Parses a single {@code paletted_permutations} source object, or empty when it is malformed. */
    private static @NotNull Optional<PalettedPermutationSource> parseSource(@NotNull JsonObject source) {
        String paletteKey = string(source, "palette_key");
        if (paletteKey == null || !source.has("permutations") || !source.get("permutations").isJsonObject()
            || !source.has("textures") || !source.get("textures").isJsonArray())
            return Optional.empty();

        Map<String, String> permutations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject("permutations").entrySet())
            if (entry.getValue().isJsonPrimitive()) permutations.put(entry.getKey(), entry.getValue().getAsString());

        List<String> textures = new ArrayList<>();
        for (JsonElement element : source.getAsJsonArray("textures"))
            if (element.isJsonPrimitive()) textures.add(element.getAsString());

        if (permutations.isEmpty() || textures.isEmpty()) return Optional.empty();
        return Optional.of(new PalettedPermutationSource(paletteKey, Map.copyOf(permutations), List.copyOf(textures)));
    }

    private static String string(@NotNull JsonObject object, @NotNull String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

}
