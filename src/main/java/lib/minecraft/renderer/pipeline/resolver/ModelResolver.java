package lib.minecraft.renderer.pipeline.resolver;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.util.Models;
import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loader and resolver that walks every pack's {@code assets/<namespace>/models/} subtree, parses
 * every block and item JSON file into {@link ModelData}, and eagerly merges parent chains so the
 * resulting DTOs carry everything needed for rendering without further resolution at render time.
 * <p>
 * Parent chain merging is deep: child textures and elements win on conflicting keys, and the
 * merged result records the original parent id in its {@code parent} field for introspection.
 * Vanilla chains are acyclic and shallow (at most 3 deep), so no cycle detection is needed.
 * <p>
 * The raw merge runs over the {@link PackStack} effective file set: for each model id the winning
 * pack's bytes, with that pack's {@code pack.mcmeta filter.block} erasing matching lower-pack rows
 * before its own merge in (via {@link lib.minecraft.renderer.pipeline.pack.MCMeta.Pack#hides}). Raw
 * JSON merges later-wins on the resolved model id <em>before</em> parent-chain inheritance runs, so
 * a higher-priority child model still inherits from a vanilla parent that lives only in the base
 * pack, and a pack parent retro-affects every vanilla child - exactly the vanilla client's
 * per-file resolution against the effective set followed by baking. The merge is
 * <em>attributed</em>: every winning file carries its origin {@link ResourcePack}, so a non-vanilla
 * winner that trips {@link Models#rendersNothing} (or fails to parse) is diagnosed by pack name
 * rather than vanishing silently. A vanilla-only stack scans exactly {@code assets/minecraft/} and
 * merges identically to the former single-root walk.
 *
 * @see ModelData
 * @see PackStack
 * @see PipelineRendererContext
 */
@UtilityClass
public class ModelResolver {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads every block model JSON under {@code assets/<ns>/models/block} across the stack and
     * returns them keyed by resolved model id ({@code "minecraft:block/grass_block"}).
     *
     * @param stack the resolved pack stack
     * @return a map of model id to resolved block model data
     */
    public static @NotNull ConcurrentMap<String, ModelData> loadBlockModels(@NotNull PackStack stack) {
        return resolveModels(stack, VanillaSourcePaths.MODELS_BLOCK_SUBDIR, VanillaSourcePaths.BLOCK_KIND, false);
    }

    /**
     * Loads every item model JSON under {@code assets/<ns>/models/item} across the stack and
     * returns them keyed by resolved model id ({@code "minecraft:item/diamond_sword"}).
     *
     * @param stack the resolved pack stack
     * @return a map of model id to resolved item model data
     */
    public static @NotNull ConcurrentMap<String, ModelData> loadItemModels(@NotNull PackStack stack) {
        return resolveModels(stack, VanillaSourcePaths.MODELS_ITEM_SUBDIR, VanillaSourcePaths.ITEM_KIND, true);
    }

    /**
     * Runs the attributed raw merge then the parent-chain resolution for one model kind.
     *
     * @param stack the resolved pack stack
     * @param subdir the assets subtree ({@code models/block} or {@code models/item})
     * @param kind the model-id kind segment ({@code block} or {@code item})
     * @param isItem whether these are item models (drives the {@link Models#rendersNothing} check)
     * @return the resolved model map, unmodifiable
     */
    private static @NotNull ConcurrentMap<String, ModelData> resolveModels(
        @NotNull PackStack stack, @NotNull String subdir, @NotNull String kind, boolean isItem
    ) {
        LinkedHashMap<String, Attributed> raw = mergeRawAcrossStack(stack, subdir, kind);
        HashMap<String, JsonObject> rawJson = new HashMap<>(raw.size());
        raw.forEach((id, attributed) -> rawJson.put(id, attributed.json()));

        return raw.entrySet().parallelStream().collect(Concurrent.toMap(
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
    private static @NotNull LinkedHashMap<String, Attributed> mergeRawAcrossStack(
        @NotNull PackStack stack, @NotNull String subdir, @NotNull String kind
    ) {
        LinkedHashMap<String, Attributed> merged = new LinkedHashMap<>();
        for (ResourcePack pack : stack.ascending()) {
            pack.meta().pack().ifPresent(section -> merged.keySet().removeIf(id -> section.hides(ResourceId.parse(id))));
            if (!(pack.container() instanceof PackContainer.Directory dir)) continue;

            for (PackRoot root : pack.roots()) {
                for (String namespace : pack.namespaces()) {
                    Path modelsDir = dir.root().resolve(root.prefix()).resolve(VanillaSourcePaths.assetSubdir(namespace, subdir));
                    String idPrefix = VanillaSourcePaths.modelIdPrefix(namespace, kind);
                    scanJsonFiles(modelsDir, idPrefix, pack.id()).forEach((id, json) -> merged.put(id, new Attributed(json, pack.id())));
                }
            }
        }
        return merged;
    }

    /**
     * Resolves one raw entry into a {@link ModelData}: walks its parent chain against the merged raw
     * map, captures 26.1 object-form texture flags, Gson-reparses the flattened JSON, and warns when
     * a non-vanilla winner renders nothing (the drop itself stays downstream in the index loaders).
     */
    private static @NotNull ModelData resolveModel(
        @NotNull String id, @NotNull Attributed attributed, @NotNull Map<String, JsonObject> rawJson,
        @NotNull String kindPrefix, boolean isItem
    ) {
        JsonObject merged = mergeParentChain(attributed.json(), rawJson, kindPrefix);
        ConcurrentMap<String, ModelTexture> textureObjects = normalizeTextures(merged);
        ModelData model = GSON.fromJson(merged, ModelData.class);
        model.setTextureObjects(textureObjects);

        if (!attributed.origin().equals(PackId.VANILLA)
            && Models.rendersNothing(model.getElements(), model.getTextures(), isItem))
            System.err.printf("Model '%s' from pack '%s' renders blank (empty template); it is dropped from the "
                + "atlas index unless it is a block-entity-backed or special-item id that renders through a code path%n",
                id, attributed.origin());

        return model;
    }

    /**
     * Scans one model subtree into a raw JSON map keyed by resolved model id. Missing directories
     * yield an empty map so a pack that omits a namespace's {@code models/item} (or the whole
     * subtree) is tolerated rather than fatal.
     */
    private static @NotNull ConcurrentMap<String, JsonObject> scanJsonFiles(@NotNull Path directory, @NotNull String idPrefix, @NotNull PackId packId) {
        if (!Files.isDirectory(directory)) return Concurrent.newMap();

        // Two-phase walk: collect paths serially (Files.walk spliterators don't split well for
        // parallel work), then parallelise readString + Gson parse across the FJP common pool.
        // Concurrent.toMap collects per-shard HashMaps lock-free and adopts the merged result.
        List<Path> files;
        try (Stream<Path> stream = Files.walk(directory)) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .toList();
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to scan model directory '%s'", directory);
        }

        return files.parallelStream()
            .map(p -> parseModelFile(p, directory, idPrefix, packId))
            .flatMap(Optional::stream)
            .collect(Concurrent.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Parses a single model file into a raw JSON entry keyed by resolved model id (the path
     * relative to {@code directory}, sans {@code .json}, under {@code idPrefix}, with {@code \}
     * normalised to {@code /}). Empty for non-JSON, empty-parse, or malformed input so the caller
     * can drop the entry; an I/O read failure is fatal. A malformed winning copy is reported with
     * its owning pack so the silent fall-back to a lower pack is traceable.
     */
    private static @NotNull Optional<Map.Entry<String, JsonObject>> parseModelFile(
        @NotNull Path p, @NotNull Path directory, @NotNull String idPrefix, @NotNull PackId packId
    ) {
        String relative = directory.relativize(p).toString().replace('\\', '/');
        if (!relative.endsWith(".json")) return Optional.empty();
        String id = idPrefix + relative.substring(0, relative.length() - ".json".length());
        try {
            String content = Files.readString(p);
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            return json == null ? Optional.empty() : Optional.of(Map.entry(id, json));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to read model '%s'", p);
        } catch (JsonSyntaxException ex) {
            // Resource packs occasionally ship malformed or pathologically-nested model JSON. Skip
            // so the merge falls back to a lower-priority pack's version.
            System.err.printf("Skipping malformed model '%s' from pack '%s': %s%n", p, packId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Recursively merges a model's parent chain, returning a fresh JSON object whose textures and
     * elements inherit from every ancestor. Child keys override parent keys, except {@code textures}
     * which is deep-merged (child variables win per key). Returns a deep copy of {@code model} when it
     * declares no parent or its parent lives outside this tree (e.g. {@code minecraft:builtin/generated});
     * otherwise the result is a fresh deep copy so ancestors are never mutated - which is what makes
     * the result safe to normalise in place. Cycle detection is not needed - vanilla chains are
     * acyclic and shallow (at most 3 deep). The {@code kindPrefix} is preserved for future use in
     * fully-qualifying ambiguous parent ids; today every parent reference already carries its kind
     * segment ({@code block/} or {@code item/}).
     */
    private static @NotNull JsonObject mergeParentChain(
        @NotNull JsonObject model,
        @NotNull Map<String, JsonObject> raw,
        @NotNull String kindPrefix
    ) {
        Optional<String> parentId = Optional.ofNullable(model.get("parent")).map(JsonElement::getAsString);
        if (parentId.isEmpty()) return model.deepCopy();

        String fqParent = parentId.get().contains(":") ? parentId.get() : VanillaSourcePaths.MINECRAFT_NAMESPACE + parentId.get();
        JsonObject parentJson = raw.get(fqParent);
        if (parentJson == null) {
            // Parent lives outside this tree (e.g. minecraft:builtin/generated) - keep the reference
            // and stop walking.
            return model.deepCopy();
        }
        JsonObject merged = mergeParentChain(parentJson, raw, kindPrefix);

        // Child values override parent for keys present on both sides. Deep-copy every child value
        // folded in so the returned object shares no mutable node with the raw map - the parallel
        // resolve mutates each merged copy in place (normalizeTextures), and an aliased raw texture
        // object would otherwise be mutated under the shared map. This honours the "ancestors are
        // never mutated" contract.
        for (String key : model.keySet()) {
            if (key.equals("textures") && merged.has("textures") && model.get("textures").isJsonObject()) {
                JsonObject mergedTextures = merged.getAsJsonObject("textures").deepCopy();
                JsonObject childTextures = model.getAsJsonObject("textures");
                for (String tKey : childTextures.keySet())
                    mergedTextures.add(tKey, childTextures.get(tKey).deepCopy());
                merged.add("textures", mergedTextures);
            } else {
                merged.add(key, model.get(key).deepCopy());
            }
        }

        return merged;
    }

    /**
     * Flattens object-valued texture entries to their {@code sprite} string in place (byte-identical
     * to the former {@code normalizeTextureEntries}: the render path consumes the string map) and
     * returns the retained {@link ModelTexture} objects for the flags it would otherwise discard.
     * MC 26.1 uses {@code {"force_translucent": true, "sprite": "minecraft:block/glass"}} for
     * translucent blocks; the sprite string stays the sole render input, the flag is retained on the
     * side channel. Runs once on the fully-merged object, so every model - including a parent-less
     * one - normalises consistently.
     *
     * @return the object-form entries keyed by texture variable, or an empty map when none were objects
     */
    private static @NotNull ConcurrentMap<String, ModelTexture> normalizeTextures(@NotNull JsonObject model) {
        if (!model.has("textures") || !model.get("textures").isJsonObject()) return Concurrent.newMap();

        JsonObject textures = model.getAsJsonObject("textures");
        HashMap<String, String> flattened = new HashMap<>();
        HashMap<String, ModelTexture> objects = new HashMap<>();

        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject() && value.getAsJsonObject().has("sprite")) {
                JsonObject object = value.getAsJsonObject();
                String sprite = object.get("sprite").getAsString();
                // A pack may ship a non-boolean force_translucent; read it defensively so a malformed
                // flag flattens the sprite instead of crashing the whole model load.
                JsonElement flag = object.get("force_translucent");
                boolean forceTranslucent = flag != null && flag.isJsonPrimitive()
                    && flag.getAsJsonPrimitive().isBoolean() && flag.getAsBoolean();
                flattened.put(entry.getKey(), sprite);
                objects.put(entry.getKey(), new ModelTexture(sprite, forceTranslucent));
            }
        }

        for (Map.Entry<String, String> entry : flattened.entrySet())
            textures.addProperty(entry.getKey(), entry.getValue());

        return objects.isEmpty() ? Concurrent.newMap() : Concurrent.adoptMap(objects).toUnmodifiable();
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
