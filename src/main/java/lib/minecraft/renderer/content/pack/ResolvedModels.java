package lib.minecraft.renderer.content.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.read.PackSubtree;
import lib.minecraft.renderer.vanilla.VanillaPaths;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Every model file under every pack's {@code assets/<namespace>/models/} tree, at any depth, parsed
 * into {@link ModelData} with its parent chain eagerly merged, so the DTOs carry everything needed for
 * rendering without further resolution at render time.
 * <p>
 * A model's id is its namespace and its path under {@code models/} without the {@code .json}
 * extension, as vanilla's model lister keys it: {@code assets/hplus/models/skyblock/a/b.json} is
 * {@code hplus:skyblock/a/b}, and {@code block/} and {@code item/} are only the first segment of that
 * path. The models under those two
 * are also held apart, as {@link #blocks()} and {@link #items()}, because they are the two sets the block
 * and item indexes iterate. {@link #all()} holds every model, those two sets included, and is what
 * {@link #find(String)} answers from.
 * <p>
 * Parent chain merging is deep: child textures and elements win on conflicting keys, and the display
 * resolves per slot, each slot taking the nearest file up the chain that declares it, as vanilla's
 * {@code findTopTransform} walks it. A parent resolves against the whole tree whatever its path, so an
 * item model whose parent is a block model, or a model at a namespace's root, inherits that parent's
 * elements, textures and display slots.
 * <ul>
 *   <li><b>A parent the tree does not hold</b>, one no pack ships or whose winning file failed to
 *   load, resolves to vanilla's missing model, held under {@code minecraft:builtin/missing}: one full
 *   cube whose every face and {@code particle} bind {@code minecraft:missingno}, with no display. The
 *   child inherits that cube wherever it does not override it, and each child naming such a parent is
 *   reported once.</li>
 *   <li><b>{@code minecraft:builtin/generated}</b> ends the chain, keeping the layers the chain declares,
 *   since the layer loop is this renderer's rendition of vanilla's generated-item model.</li>
 *   <li><b>A parent cycle</b> drops every model whose chain reaches it, each reported once,
 *   as vanilla ignores a model whose parents never resolve.</li>
 * </ul>
 * <p>
 * The raw merge runs over the {@link PackStack} effective file set: for each model id the winning
 * pack's bytes, with that pack's {@code pack.mcmeta filter.block} erasing matching lower-pack rows
 * before its own merge in (via {@link MCMeta.Pack#hidesFile}). Raw JSON merges later-wins on the model
 * id <em>before</em> parent-chain inheritance runs, so a higher-priority child model still inherits
 * from a vanilla parent that lives only in the base pack, and a pack parent retro-affects every vanilla
 * child - exactly the vanilla client's per-file resolution against the effective set followed by
 * baking. The merge is <em>attributed</em>: every winning file carries its origin
 * {@link ResourcePack}, so every report names the pack. A vanilla-only stack scans exactly
 * {@code assets/minecraft/}.
 * <p>
 * A model that fails to load leaves its id absent, and is reported by pack name:
 * <ul>
 *   <li>a winning file that does not read as a JSON object. Vanilla reads only the top pack's copy of
 *   an id, so a lower pack's copy never stands in for it.</li>
 *   <li>a merged chain the typed read rejects. Vanilla rejects a file before any merge, where this
 *   rejects the merged chain, so a broken parent takes its children with it here while vanilla parents
 *   them on the missing model.</li>
 * </ul>
 * <p>
 * A file that is not a Java model, such as a Bedrock {@code .geo.json}, loads as an empty model, as
 * vanilla's model reader reads every member behind a presence test. A non-vanilla {@code block/} or
 * {@code item/} winner that trips {@link ModelData#rendersNothing} is reported as well, because the
 * indexes drop it; no index iterates the other models, so a blank one among them is not reported.
 *
 * @param blocks resolved {@code block/} models keyed by model id ({@code "minecraft:block/grass_block"})
 * @param items resolved {@code item/} models keyed by model id ({@code "minecraft:item/diamond_sword"})
 * @param all every resolved model keyed by model id, the {@code block/} and {@code item/} ones and the
 *     missing model included
 */
public record ResolvedModels(
    @NotNull ConcurrentMap<String, ModelData> blocks,
    @NotNull ConcurrentMap<String, ModelData> items,
    @NotNull ConcurrentMap<String, ModelData> all
) {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The whole {@code models/} tree of every namespace, at any depth, as vanilla's model lister lists it. */
    private static final @NotNull PackSubtree.Subtree MODELS = PackSubtree.Subtree.of("models", ".json");

    /** The id vanilla holds its missing model under, ahead of any file a pack ships at that id. */
    private static final @NotNull String MISSING_MODEL_ID = "minecraft:builtin/missing";

    /** The id of vanilla's generated-item model, which ends a parent chain rather than joining it. */
    private static final @NotNull String GENERATED_MODEL_ID = "minecraft:builtin/generated";

    /**
     * Vanilla's missing model as model JSON: one full cube, each face culled on its own side and bound
     * to the {@code missingno} slot, which {@code particle} references too, and no display.
     */
    private static final @NotNull String MISSING_MODEL_JSON = """
        {
          "textures": {"particle": "#missingno", "missingno": "minecraft:missingno"},
          "elements": [{
            "from": [0, 0, 0],
            "to": [16, 16, 16],
            "faces": {
              "down": {"uv": [0, 0, 16, 16], "texture": "#missingno", "cullface": "down"},
              "up": {"uv": [0, 0, 16, 16], "texture": "#missingno", "cullface": "up"},
              "north": {"uv": [0, 0, 16, 16], "texture": "#missingno", "cullface": "north"},
              "south": {"uv": [0, 0, 16, 16], "texture": "#missingno", "cullface": "south"},
              "west": {"uv": [0, 0, 16, 16], "texture": "#missingno", "cullface": "west"},
              "east": {"uv": [0, 0, 16, 16], "texture": "#missingno", "cullface": "east"}
            }
          }]
        }""";

    /**
     * Looks up a model by id, reading a bare id as a {@code minecraft:} one, as vanilla parses an
     * identifier.
     *
     * @param modelId the model id, namespaced or bare ({@code minecraft:item/bow}, {@code item/bow})
     * @return the resolved model, or empty when no model loaded under that id
     */
    public @NotNull Optional<ModelData> find(@NotNull String modelId) {
        return this.all.getOptional(ResourceId.parse(modelId).id());
    }

    /**
     * Loads and resolves every model under every pack's {@code models/} tree across the stack.
     *
     * @param stack the resolved pack stack
     * @return the resolved models, held whole and as the two sets the indexes iterate
     */
    public static @NotNull ResolvedModels load(@NotNull PackStack stack) {
        ConcurrentMap<String, Attributed> files = readModelFiles(stack);
        ConcurrentMap<String, Attributed> blocks = only(files, Kind.BLOCK);
        ConcurrentMap<String, Attributed> items = only(files, Kind.ITEM);
        // Vanilla seeds its model map with the missing model before it looks any file up, so a pack's
        // file at that id never answers in its place.
        Attributed missing = new Attributed(GSON.fromJson(MISSING_MODEL_JSON, JsonObject.class), PackId.VANILLA);
        ConcurrentMap<String, Attributed> others = Stream.concat(
                only(files, Kind.OTHER).entrySet().stream().filter(entry -> !entry.getKey().equals(MISSING_MODEL_ID)),
                Stream.of(Map.entry(MISSING_MODEL_ID, missing)))
            .collect(Concurrent.toUnmodifiableLinkedMap(Map.Entry::getKey, Map.Entry::getValue));

        // One map, as vanilla lists every model file into one: a parent resolves whatever its path. The
        // three parts are disjoint, since each id keeps its first path segment.
        ConcurrentMap<String, JsonObject> raw = Stream.of(blocks, items, others)
            .flatMap(part -> part.entrySet().stream())
            .collect(Concurrent.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().json()));
        reportAbsentParents(Stream.of(blocks, items, others).flatMap(part -> part.entrySet().stream()), raw);

        ConcurrentMap<String, ModelData> resolvedBlocks = resolveModels(blocks, raw, Kind.BLOCK);
        ConcurrentMap<String, ModelData> resolvedItems = resolveModels(items, raw, Kind.ITEM);
        ConcurrentMap<String, ModelData> resolvedOthers = resolveModels(others, raw, Kind.OTHER);
        ConcurrentMap<String, ModelData> all = Stream.of(resolvedBlocks, resolvedItems, resolvedOthers)
            .flatMap(part -> part.entrySet().stream())
            .collect(Concurrent.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return new ResolvedModels(resolvedBlocks, resolvedItems, all);
    }

    /**
     * Reads every model file across the whole stack into an attributed, later-wins map keyed by model
     * id. Packs are visited ascending; before each pack's rows merge in, its {@code filter.block}
     * patterns erase matching accumulated rows, then every {@code (root x namespace)} subtree it owns
     * is scanned. Only the winning file per id counts, so an id whose winning file does not read as a
     * JSON object is reported and left out, never answered by a lower pack's copy.
     *
     * @param stack the resolved pack stack
     * @return the winning file per model id, keyed in the order the walk first listed each id
     */
    private static @NotNull ConcurrentMap<String, Attributed> readModelFiles(@NotNull PackStack stack) {
        // The shared walk enumerates and filters serially (container walks do not split well), then
        // the byte read + Gson parse parallelise across the FJP common pool. map() preserves
        // encounter order, so the sequential merge below still sees resolution order - later roots
        // and later packs last, and therefore winning.
        ConcurrentMap<String, ModelFile> winners = PackSubtree.walk(stack.ascending(), MODELS)
            .parallelStream()
            .map(ResolvedModels::readModelFile)
            .collect(Concurrent.toUnmodifiableLinkedMap(ModelFile::id, Function.identity(), (lower, higher) -> higher));

        ConcurrentMap<String, Attributed> read = Concurrent.newLinkedMap();
        for (ModelFile file : winners.values()) {
            file.json().ifPresentOrElse(
                json -> read.put(file.id(), new Attributed(json, file.origin())),
                () -> System.err.printf("Failed to load model '%s' from pack '%s': %s%n", file.id(), file.origin(), file.failure()));
        }
        return read;
    }

    /**
     * Reads one listed model file, keyed by the namespace it lives in and its path under
     * {@code models/}.
     *
     * @param entry the model file the subtree walk listed
     * @return the file, read or carrying why it did not read
     */
    private static @NotNull ModelFile readModelFile(@NotNull PackSubtree.Entry entry) {
        String id = VanillaPaths.namespacePrefix(entry.namespace()) + entry.stem();
        PackId origin = entry.pack().id();
        Optional<byte[]> bytes = entry.container().bytes(entry.entryPath());
        if (bytes.isEmpty()) return new ModelFile(id, origin, Optional.empty(), "the file could not be read");

        try {
            // Resource packs occasionally ship malformed or pathologically-nested model JSON.
            JsonObject json = GSON.fromJson(new String(bytes.get(), StandardCharsets.UTF_8), JsonObject.class);
            return json == null
                ? new ModelFile(id, origin, Optional.empty(), "the file is empty")
                : new ModelFile(id, origin, Optional.of(json), "");
        } catch (JsonParseException ex) {
            return new ModelFile(id, origin, Optional.empty(), String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Returns the models of one part of the tree, in the order the whole map holds them.
     *
     * @param files the winning file per model id
     * @param kind the part to keep
     * @return the part's models, unmodifiable
     */
    private static @NotNull ConcurrentMap<String, Attributed> only(
        @NotNull ConcurrentMap<String, Attributed> files, @NotNull Kind kind
    ) {
        return files.entrySet()
            .stream()
            .filter(entry -> Kind.of(entry.getKey()) == kind)
            .collect(Concurrent.toUnmodifiableLinkedMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Reports each model naming a parent the tree does not hold, once per model, as vanilla warns of a
     * missing model it resolves in that parent's place.
     *
     * @param models every attributed raw model, in the order to report them
     * @param raw every raw model, keyed by model id
     */
    private static void reportAbsentParents(
        @NotNull Stream<Map.Entry<String, Attributed>> models, @NotNull Map<String, JsonObject> raw
    ) {
        models.forEach(entry -> namedParent(entry.getValue().json())
            .filter(parent -> !parent.equals(GENERATED_MODEL_ID) && !raw.containsKey(parent))
            .ifPresent(parent -> System.err.printf(
                "Model '%s' from pack '%s' names parent '%s', which no pack ships or which failed to load - it inherits the missing model%n",
                entry.getKey(), entry.getValue().origin(), parent)));
    }

    /**
     * Runs the parent-chain resolution for one part of the tree against every raw model, leaving out
     * each model that fails to resolve.
     *
     * @param models the part's attributed raw models, keyed by model id
     * @param raw every raw model, keyed by model id
     * @param kind the part, which decides whether a blank model is reported
     * @return the resolved model map, unmodifiable
     */
    private static @NotNull ConcurrentMap<String, ModelData> resolveModels(
        @NotNull ConcurrentMap<String, Attributed> models, @NotNull Map<String, JsonObject> raw, @NotNull Kind kind
    ) {
        return models.entrySet()
            .parallelStream()
            .flatMap(entry -> resolveModel(entry.getKey(), entry.getValue(), raw, kind)
                .map(model -> Map.entry(entry.getKey(), model))
                .stream())
            .collect(Concurrent.toMap(Map.Entry::getKey, Map.Entry::getValue))
            .toUnmodifiable();
    }

    /**
     * Resolves one raw entry into a {@link ModelData}: walks its parent chain against the merged raw
     * map, Gson-reparses the merged JSON (the {@link ModelTexture} adapter reads both the string and
     * the 26.1 object texture forms), and warns when a non-vanilla {@code block/} or {@code item/}
     * winner renders nothing (the drop itself stays downstream in the index loaders).
     *
     * @param id the model's id
     * @param attributed the model's raw file and the pack it came from
     * @param raw every raw model, keyed by model id
     * @param kind the part of the tree the model sits in
     * @return the resolved model, or empty when its chain reaches a cycle or the merged chain fails the
     *     typed read
     */
    private static @NotNull Optional<ModelData> resolveModel(
        @NotNull String id, @NotNull Attributed attributed, @NotNull Map<String, JsonObject> raw, @NotNull Kind kind
    ) {
        Optional<ConcurrentList<JsonObject>> chain = chainOf(id, attributed.json(), raw);
        if (chain.isEmpty()) {
            System.err.printf("Model '%s' from pack '%s' is ignored - its parent chain is cyclic%n", id, attributed.origin());
            return Optional.empty();
        }

        ModelData model;
        try {
            JsonObject merged = mergeParentChain(chain.get());
            resolveDisplay(chain.get()).ifPresent(display -> merged.add("display", display));
            model = GSON.fromJson(merged, ModelData.class);
        } catch (RuntimeException ex) {
            System.err.printf("Failed to load model '%s' from pack '%s': %s%n", id, attributed.origin(), ex.getMessage());
            return Optional.empty();
        }

        if (kind != Kind.OTHER
            && !attributed.origin().equals(PackId.VANILLA)
            && model.rendersNothing(kind == Kind.ITEM))
            System.err.printf("Model '%s' from pack '%s' renders blank (empty template); it is dropped from the "
                + "atlas index unless it is a block-entity-backed or special-item id that renders through a code path%n",
                id, attributed.origin());

        return Optional.of(model);
    }

    /**
     * Returns a model's parent chain, the model itself first and its furthest ancestor last. A parent
     * the tree does not hold resolves to the missing model, and {@code minecraft:builtin/generated}
     * ends the chain.
     *
     * @param id the model's id
     * @param model the model's raw JSON
     * @param raw every raw model, keyed by model id
     * @return the chain, or empty when it reaches a cycle
     */
    private static @NotNull Optional<ConcurrentList<JsonObject>> chainOf(
        @NotNull String id, @NotNull JsonObject model, @NotNull Map<String, JsonObject> raw
    ) {
        ConcurrentList<JsonObject> chain = Concurrent.newList(model);
        ConcurrentSet<String> visited = Concurrent.newSet(id);

        for (Optional<String> parent = parentOf(model, raw); parent.isPresent(); parent = parentOf(chain.getLast(), raw)) {
            if (!visited.add(parent.get())) return Optional.empty();
            chain.add(raw.get(parent.get()));
        }

        return Optional.of(chain);
    }

    /**
     * Returns the id of the file a model's parent resolves to: the parent it names where the tree holds
     * it, else the missing model.
     *
     * @param model the model file
     * @param raw every raw model, keyed by model id
     * @return the parent's id, or empty when the file names no parent or names
     *     {@code minecraft:builtin/generated}
     */
    private static @NotNull Optional<String> parentOf(@NotNull JsonObject model, @NotNull Map<String, JsonObject> raw) {
        return namedParent(model)
            .filter(parent -> !parent.equals(GENERATED_MODEL_ID))
            .map(parent -> raw.containsKey(parent) ? parent : MISSING_MODEL_ID);
    }

    /**
     * Returns the parent a model file names, a bare id read as a {@code minecraft:} one.
     *
     * @param model the model file
     * @return the named parent's id, or empty when the file names none
     */
    private static @NotNull Optional<String> namedParent(@NotNull JsonObject model) {
        JsonElement parent = model.get("parent");
        if (parent == null || !parent.isJsonPrimitive()) return Optional.empty();
        return Optional.of(ResourceId.parse(parent.getAsString()).id());
    }

    /**
     * Merges a model's parent chain into a fresh JSON object whose textures and elements inherit from
     * every ancestor. Child keys override parent keys, except {@code textures}, which is deep-merged
     * (child variables win per key); the {@code display} this leaves is replaced by
     * {@link #resolveDisplay}'s per-slot answer wherever the chain declares one. Every value folded in
     * is a deep copy, so no file in the chain is mutated.
     *
     * @param chain the model's parent chain, the model itself first
     * @return the merged JSON
     */
    private static @NotNull JsonObject mergeParentChain(@NotNull ConcurrentList<JsonObject> chain) {
        Iterator<JsonObject> downward = chain.reversed().iterator();
        JsonObject merged = downward.next().deepCopy();

        while (downward.hasNext()) {
            for (Map.Entry<String, JsonElement> entry : downward.next().entrySet()) {
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
        }

        return merged;
    }

    /**
     * Resolves a model's {@code display} per slot, as vanilla's {@code ResolvedModel.findTopTransform}
     * walks it: climbing the parent chain from the model itself, each slot takes the first file that
     * declares it. Each file's own slots are read as vanilla's {@code ItemTransforms} deserializer reads
     * them, a left hand the file leaves out taking that file's right hand before the walk looks further.
     *
     * @param chain the model's parent chain, the model itself first
     * @return the resolved slots, or empty when no file up the chain declares a display object
     */
    private static @NotNull Optional<JsonObject> resolveDisplay(@NotNull ConcurrentList<JsonObject> chain) {
        JsonObject display = new JsonObject();
        boolean declared = false;
        for (JsonObject file : chain) {
            JsonElement own = file.get("display");
            if (own == null || !own.isJsonObject()) continue;
            declared = true;
            for (Map.Entry<String, JsonElement> slot : withHandsFilled(own.getAsJsonObject()).entrySet())
                if (!display.has(slot.getKey())) display.add(slot.getKey(), slot.getValue().deepCopy());
        }
        return declared ? Optional.of(display) : Optional.empty();
    }

    /**
     * Returns one file's display slots with each left hand it leaves out taken from its own right hand,
     * the third-person pair then the first-person one, as vanilla's deserializer fills them.
     *
     * @param display the file's own display object
     * @return a copy holding the file's slots and the filled hands
     */
    private static @NotNull JsonObject withHandsFilled(@NotNull JsonObject display) {
        JsonObject filled = display.deepCopy();
        if (!filled.has("thirdperson_lefthand") && filled.has("thirdperson_righthand"))
            filled.add("thirdperson_lefthand", filled.get("thirdperson_righthand").deepCopy());
        if (!filled.has("firstperson_lefthand") && filled.has("firstperson_righthand"))
            filled.add("firstperson_lefthand", filled.get("firstperson_righthand").deepCopy());
        return filled;
    }

    /** The part of the tree a model id falls in, by the first segment of its path. */
    private enum Kind {

        /** A {@code block/} model, which the block index iterates. */
        BLOCK,

        /** An {@code item/} model, which the item index iterates. */
        ITEM,

        /** Any other model, which only a lookup or a parent reference reaches. */
        OTHER;

        /**
         * Returns the part a model id falls in.
         *
         * @param id the namespaced model id
         * @return the part
         */
        static @NotNull Kind of(@NotNull String id) {
            String path = id.substring(id.indexOf(':') + 1);
            if (path.startsWith(VanillaPaths.BLOCK_KIND + "/")) return BLOCK;
            if (path.startsWith(VanillaPaths.ITEM_KIND + "/")) return ITEM;
            return OTHER;
        }

    }

    /**
     * One model file as the walk listed it: its JSON where it read as an object, else why it did not.
     *
     * @param id the model id the file keys
     * @param origin the id of the pack the file was listed in
     * @param json the file's JSON object, or empty when the file did not read as one
     * @param failure why the file did not read as a JSON object, or {@code ""} when it did
     */
    private record ModelFile(
        @NotNull String id, @NotNull PackId origin, @NotNull Optional<JsonObject> json, @NotNull String failure
    ) {}

    /**
     * One winning raw model file plus the {@link ResourcePack} that supplied it, so a merged entry
     * can be diagnosed by pack name.
     *
     * @param json the raw model JSON
     * @param origin the id of the pack whose copy won
     */
    private record Attributed(@NotNull JsonObject json, @NotNull PackId origin) {}

}
