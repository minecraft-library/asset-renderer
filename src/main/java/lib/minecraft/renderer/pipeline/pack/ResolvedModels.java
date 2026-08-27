package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The resolved block and item model sets: every pack's {@code assets/<namespace>/models/} JSON parsed
 * into {@link ModelData} with parent chains eagerly merged, so the DTOs carry everything needed for
 * rendering without further resolution at render time.
 * <p>
 * Parent chain merging is deep: child textures and elements win on conflicting keys. Vanilla chains
 * are acyclic and shallow (at most 3 deep), so no cycle detection is needed.
 * <p>
 * The raw merge runs over the {@link PackStack} effective file set: for each model id the winning
 * pack's bytes, with that pack's {@code pack.mcmeta filter.block} erasing matching lower-pack rows
 * before its own merge in (via {@link MCMeta.Pack#hidesFile}). Raw JSON merges later-wins on the resolved
 * model id <em>before</em> parent-chain inheritance runs, so a higher-priority child model still
 * inherits from a vanilla parent that lives only in the base pack, and a pack parent retro-affects
 * every vanilla child - exactly the vanilla client's per-file resolution against the effective set
 * followed by baking. The merge is <em>attributed</em>: every winning file carries its origin
 * {@link ResourcePack}, so a non-vanilla winner that trips {@link ModelData#rendersNothing} (or fails
 * to parse) is diagnosed by pack name rather than vanishing silently. A vanilla-only stack scans
 * exactly {@code assets/minecraft/}.
 *
 * @param blocks resolved block models keyed by model id ({@code "minecraft:block/grass_block"})
 * @param items resolved item models keyed by model id ({@code "minecraft:item/diamond_sword"})
 */
public record ResolvedModels(
    @NotNull ConcurrentMap<String, ModelData> blocks,
    @NotNull ConcurrentMap<String, ModelData> items
) {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads and resolves every block and item model across the stack.
     *
     * @param stack the resolved pack stack
     * @return the resolved block + item model sets
     */
    public static @NotNull ResolvedModels load(@NotNull PackStack stack) {
        return new ResolvedModels(
            resolveModels(stack, VanillaSourcePaths.MODELS_BLOCK_SUBDIR, VanillaSourcePaths.BLOCK_KIND, false),
            resolveModels(stack, VanillaSourcePaths.MODELS_ITEM_SUBDIR, VanillaSourcePaths.ITEM_KIND, true)
        );
    }

    /**
     * Runs the attributed raw merge then the parent-chain resolution for one model kind.
     *
     * @param stack the resolved pack stack
     * @param subdir the assets subtree ({@code models/block} or {@code models/item})
     * @param kind the model-id kind segment ({@code block} or {@code item})
     * @param isItem whether these are item models (drives the {@link ModelData#rendersNothing} check)
     * @return the resolved model map, unmodifiable
     */
    private static @NotNull ConcurrentMap<String, ModelData> resolveModels(
        @NotNull PackStack stack, @NotNull String subdir, @NotNull String kind, boolean isItem
    ) {
        ConcurrentMap<String, Attributed> raw = mergeRawAcrossStack(stack, subdir, kind);
        ConcurrentMap<String, JsonObject> rawJson = raw.entrySet()
            .stream()
            .collect(Concurrent.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().json()));

        return raw.entrySet()
            .parallelStream()
            .collect(Concurrent.toMap(
                Map.Entry::getKey,
                entry -> resolveModel(entry.getKey(), entry.getValue(), rawJson, kind, isItem)
            )).toUnmodifiable();
    }

    /**
     * Merges raw model JSON across the whole stack into an attributed, later-wins map keyed by
     * resolved model id. Packs are visited ascending; before each pack's rows merge in, its
     * {@code filter.block} patterns erase matching accumulated rows, then every {@code (root x
     * namespace)} subtree it owns is scanned. Every winning entry carries its origin pack so
     * diagnostics can name it.
     */
    private static @NotNull ConcurrentMap<String, Attributed> mergeRawAcrossStack(
        @NotNull PackStack stack, @NotNull String subdir, @NotNull String kind
    ) {
        // The shared walk enumerates and filters serially (container walks do not split well), then
        // the byte read + Gson parse parallelise across the FJP common pool. map() preserves
        // encounter order, so the sequential merge below still sees resolution order - later roots
        // and later packs last, and therefore winning.
        return PackSubtree.walk(stack, PackSubtree.Subtree.of(subdir, ".json"))
            .parallelStream()
            .map(entry -> parseModelFile(entry, kind))
            .flatMap(Optional::stream)
            .collect(Concurrent.toUnmodifiableLinkedMap(Map.Entry::getKey, Map.Entry::getValue, (lower, higher) -> higher));
    }

    /**
     * Resolves one raw entry into a {@link ModelData}: walks its parent chain against the merged raw
     * map, Gson-reparses the merged JSON (the {@link ModelTexture} adapter reads both the string and
     * the 26.1 object texture forms), and warns when a non-vanilla winner renders nothing (the drop
     * itself stays downstream in the index loaders).
     */
    private static @NotNull ModelData resolveModel(
        @NotNull String id, @NotNull Attributed attributed, @NotNull Map<String, JsonObject> rawJson,
        @NotNull String kindPrefix, boolean isItem
    ) {
        JsonObject merged = mergeParentChain(attributed.json(), rawJson, kindPrefix);
        ModelData model = GSON.fromJson(merged, ModelData.class);

        if (!attributed.origin().equals(PackId.VANILLA)
            && model.rendersNothing(isItem))
            System.err.printf("Model '%s' from pack '%s' renders blank (empty template); it is dropped from the "
                + "atlas index unless it is a block-entity-backed or special-item id that renders through a code path%n",
                id, attributed.origin());

        return model;
    }

    /**
     * Parses a single model file into an attributed raw JSON entry keyed by resolved model id.
     * Empty for an unreadable, empty-parse or malformed file so the caller can drop it and the merge
     * falls back to a lower pack's copy; a malformed winning copy is reported with its owning pack so
     * that fall-back is traceable.
     *
     * @param entry the model file the subtree walk resolved
     * @param kind the model-id kind segment ({@code block} or {@code item})
     * @return the id-to-attributed-JSON pair, or empty when the file yields nothing usable
     */
    private static @NotNull Optional<Map.Entry<String, Attributed>> parseModelFile(
        @NotNull PackSubtree.Entry entry, @NotNull String kind
    ) {
        String id = VanillaSourcePaths.modelIdPrefix(entry.namespace(), kind) + entry.stem();
        Optional<byte[]> bytes = entry.container().bytes(entry.entryPath());
        if (bytes.isEmpty()) return Optional.empty();

        try {
            JsonObject json = GSON.fromJson(new String(bytes.get(), StandardCharsets.UTF_8), JsonObject.class);
            return json == null ? Optional.empty() : Optional.of(Map.entry(id, new Attributed(json, entry.pack().id())));
        } catch (JsonSyntaxException ex) {
            // Resource packs occasionally ship malformed or pathologically-nested model JSON. Skip
            // so the merge falls back to a lower-priority pack's version.
            System.err.printf("Skipping malformed model '%s' from pack '%s': %s%n",
                entry.entryPath(), entry.pack().id(), ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Recursively merges a model's parent chain, returning a fresh JSON object whose textures and
     * elements inherit from every ancestor. Child keys override parent keys, except {@code textures}
     * which is deep-merged (child variables win per key). Returns a deep copy of {@code model} when it
     * declares no parent or its parent lives outside this tree (e.g. {@code minecraft:builtin/generated});
     * otherwise the result is a fresh deep copy so ancestors are never mutated. Cycle detection is not needed - vanilla chains are
     * acyclic and shallow (at most 3 deep). The {@code kindPrefix} is preserved for future use in
     * fully-qualifying ambiguous parent ids; today every parent reference already carries its kind
     * segment ({@code block/} or {@code item/}).
     */
    private static @NotNull JsonObject mergeParentChain(
        @NotNull JsonObject model,
        @NotNull Map<String, JsonObject> raw,
        @NotNull String kindPrefix
    ) {
        JsonElement parent = model.get("parent");
        if (parent == null || !parent.isJsonPrimitive()) return model.deepCopy();

        String parentId = parent.getAsString();
        String fqParent = parentId.contains(":") ? parentId : VanillaSourcePaths.MINECRAFT_NAMESPACE + parentId;
        JsonObject parentJson = raw.get(fqParent);
        if (parentJson == null) {
            // Parent lives outside this tree (e.g. minecraft:builtin/generated) - keep the reference
            // and stop walking.
            return model.deepCopy();
        }
        JsonObject merged = mergeParentChain(parentJson, raw, kindPrefix);

        // Child values override parent for keys present on both sides. Deep-copy every child value
        // folded in so the returned object shares no mutable node with the raw map, honouring the
        // "ancestors are never mutated" contract even though this method mutates the merged copy.
        for (Map.Entry<String, JsonElement> entry : model.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (key.equals("textures") && merged.has("textures") && value.isJsonObject()) {
                JsonObject mergedTextures = merged.getAsJsonObject("textures").deepCopy();
                for (Map.Entry<String, JsonElement> texture : value.getAsJsonObject().entrySet())
                    mergedTextures.add(texture.getKey(), texture.getValue().deepCopy());
                merged.add("textures", mergedTextures);
            } else {
                merged.add(key, value.deepCopy());
            }
        }

        return merged;
    }

    /**
     * One winning raw model file plus the {@link ResourcePack} that supplied it, so a merged entry
     * can be diagnosed by pack name.
     *
     * @param json the raw model JSON
     * @param origin the id of the pack whose copy won
     */
    private record Attributed(@NotNull JsonObject json, @NotNull PackId origin) {}

}
