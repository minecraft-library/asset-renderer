package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import lib.minecraft.renderer.engine.texture.PalettedPermutationSource;
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
     * The atlas subtree every pack in the stack contributes. Routing through the shared walk is what
     * gives this loader the {@code filter.block} erase it never had - the client hides the whole
     * atlas file before it is read, so a filtered atlas contributes no sources at all rather than
     * having its sources filtered afterwards, which is the only level a list-shaped accumulator with
     * no id keyspace can honour.
     */
    private static final @NotNull PackSubtree.Subtree ATLASES =
        PackSubtree.Subtree.of(VanillaSourcePaths.ATLASES_SUBDIR, ".json");

    /**
     * Loads and concatenates the paletted-permutation atlas sources across the whole pack stack,
     * ascending.
     *
     * @param stack the resolved pack stack
     * @return the ordered paletted-permutation sources (lower packs first)
     */
    public static @NotNull ConcurrentList<PalettedPermutationSource> load(@NotNull PackStack stack) {
        ArrayList<PalettedPermutationSource> sources = new ArrayList<>();
        for (PackSubtree.Entry entry : PackSubtree.walk(stack, ATLASES))
            parseAtlas(entry.container(), entry.entryPath(), sources);
        return Concurrent.adoptList(sources).toUnmodifiable();
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
