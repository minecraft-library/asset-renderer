package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A loader that reads blockstate JSON files from every pack's {@code assets/<namespace>/blockstates/}
 * subtree and produces both variant-based and multipart-based blockstate data in their <em>raw</em>
 * form - each apply carried as an {@link ApplyDto} model reference, not yet joined to its geometry.
 * <p>
 * Minecraft defines two blockstate formats. The {@code "variants"} format maps block property
 * combinations (e.g. {@code "facing=north,half=bottom"}) to a single apply. The {@code "multipart"}
 * format assembles multiple conditional model parts into a composite block, where each part applies
 * when its {@code "when"} condition matches the block's properties. Both formats are read into raw
 * DTOs and returned together as a {@link BlockStates}; the {@code modelId -> }{@link Block.Variant}
 * geometry bake and the property-key parse run downstream in the block index builder, which owns the
 * resolved model set.
 * <p>
 * <b>Weighted arrays - first entry (normative, both sites).</b> When a {@code "variants"} value or a
 * multipart part's {@code "apply"} value is an array (a weighted-random set), only the FIRST entry is
 * used. This is a parity requirement, not a simplification: the vanilla-reference harness forces
 * {@code FirstVariantRandomSource.nextInt -> 0}, so every ground-truth icon renders the first array
 * entry. Both array sites therefore share one deterministic rule, folded into {@link ApplyDto.Adapter}.
 * <p>
 * The merge runs over the {@link PackStack} effective file set: packs ascending, each pack's
 * {@code filter.block} erasing matching lower-pack ids before its own subtrees merge in, and a
 * higher pack's blockstate fully replacing (variants&harr;multipart included) any lower pack's for
 * the same id - matching the vanilla client's per-file topmost-pack-wins semantics. A vanilla-only
 * stack scans exactly {@code assets/minecraft/blockstates}.
 *
 * @see Block.Variant
 * @see Block.Multipart
 */
@UtilityClass
public class BlockStateLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads blockstate JSON files across the whole pack stack. Packs are visited ascending; before
     * each pack's rows merge in, its {@code filter.block} patterns erase matching accumulated ids
     * from both the variant and multipart maps, then every {@code (root x namespace)} blockstate
     * subtree it owns is scanned. Per block id, a higher pack's blockstate fully replaces any lower
     * pack's variants or multipart - matching Minecraft, which loads exactly one blockstate file per
     * id from the topmost matching pack.
     * <p>
     * Each apply is returned as a raw {@link ApplyDto}: the {@code modelId -> }{@link Block.Variant}
     * geometry join runs in the block index builder, which holds the resolved model set, so this
     * loader stays a pure read plus pack merge.
     *
     * @param stack the resolved pack stack
     * @return the raw blockstate data
     */
    public static @NotNull BlockStates load(@NotNull PackStack stack) {
        HashMap<String, ConcurrentMap<String, ApplyDto>> variants = new HashMap<>();
        HashMap<String, ConcurrentList<MultipartPart>> multiparts = new HashMap<>();

        for (ResourcePack pack : stack.ascending()) {
            pack.meta().pack().ifPresent(section -> {
                variants.keySet().removeIf(id -> section.hides(ResourceId.parse(id)));
                multiparts.keySet().removeIf(id -> section.hides(ResourceId.parse(id)));
            });
            PackContainer container = pack.container();

            for (PackRoot root : pack.roots())
                for (String namespace : pack.namespaces()) {
                    String blockstatesPrefix = root.prefix() + VanillaSourcePaths.assetSubdir(namespace, VanillaSourcePaths.BLOCKSTATES_SUBDIR);
                    mergeDir(container, blockstatesPrefix, namespace, variants, multiparts);
                }
        }

        return new BlockStates(
            Concurrent.adoptMap(variants).toUnmodifiable(),
            Concurrent.adoptMap(multiparts).toUnmodifiable()
        );
    }

    /**
     * Scans one {@code (root x namespace)} blockstate directory and merges its parsed entries into
     * the running per-id variant / multipart maps. A higher root's variant entry deletes any earlier
     * multipart entry for the same id (and vice versa) - this preserves "exactly one blockstate file
     * wins" even when packs convert a block from variants to multipart or back. No-op when the
     * directory is absent (a pack simply lacks that namespace's blockstates).
     */
    private static void mergeDir(
        @NotNull PackContainer container,
        @NotNull String blockstatesPrefix,
        @NotNull String namespace,
        @NotNull HashMap<String, ConcurrentMap<String, ApplyDto>> variants,
        @NotNull HashMap<String, ConcurrentList<MultipartPart>> multiparts
    ) {
        // Two-phase walk: serial path enumeration, then parallel JSON parse per file. Per-file
        // work is CPU-bound (Gson parse of a small blockstate JSON) plus a tiny byte read; the
        // parallel stream scales it across cores. Each file produces at most one Parsed record;
        // the partition into variants/multiparts happens in a sequential pass over the gathered
        // list so neither map pays per-element write-lock cost. Blockstate files are flat under
        // blockstates/ - direct children only, matching the old one-level list.
        List<String> files = container.entries(blockstatesPrefix)
            .filter(p -> p.endsWith(".json"))
            .filter(p -> p.indexOf('/', blockstatesPrefix.length() + 1) < 0)
            .toList();

        List<Parsed> parsedAll = files.parallelStream()
            .map(file -> parseBlockstateFile(container, file, blockstatesPrefix, namespace))
            .flatMap(Optional::stream)
            .toList();

        for (Parsed p : parsedAll) {
            switch (p) {
                case Parsed.Variants v -> {
                    variants.put(v.blockId(), v.variants());
                    multiparts.remove(v.blockId());
                }
                case Parsed.Multipart m -> {
                    multiparts.put(m.blockId(), m.multipart());
                    variants.remove(m.blockId());
                }
                case Parsed.Shadow s -> {
                    // A valid but non-renderable higher-pack file still shadows the lower pack's entry -
                    // vanilla presents only the topmost file, so its presence must erase the lower rows.
                    variants.remove(s.blockId());
                    multiparts.remove(s.blockId());
                }
            }
        }
    }

    /**
     * Parses one blockstate JSON file into a {@link Parsed} record. The block id is the file name
     * with its {@code .json} suffix stripped and the {@code <namespace>:} prefix prepended (the
     * owning namespace, so a pack's {@code assets/<ns>/blockstates} keys are namespace-qualified).
     * A {@code "variants"} object yields the variant branch, a {@code "multipart"} array the
     * multipart branch, each only when non-empty. A file that parses to valid JSON but yields no
     * renderable definition (neither key, or empty variants/multipart) returns a <em>shadowing</em>
     * {@link Parsed} carrying neither branch, so a higher pack's presence still erases the lower
     * pack's entry (vanilla topmost-file-wins). Only a non-object root or a read / parse failure
     * resolves to empty - falling back to a lower pack's copy, the deliberate malformed-file behaviour.
     *
     * @param entry the blockstate JSON file entry path
     * @param namespace the owning namespace, prepended to the derived block id
     * @return the parsed variant / multipart / shadow record, or empty on a non-object or malformed file
     */
    private static @NotNull Optional<Parsed> parseBlockstateFile(@NotNull PackContainer container, @NotNull String entry, @NotNull String blockstatesPrefix, @NotNull String namespace) {
        String fileName = entry.substring(blockstatesPrefix.length() + 1);
        String blockName = fileName.substring(0, fileName.length() - 5);
        String blockId = VanillaSourcePaths.namespacePrefix(namespace) + blockName;

        try {
            BlockStateFile file = GSON.fromJson(new String(container.bytes(entry).orElseThrow(), StandardCharsets.UTF_8), BlockStateFile.class);
            // A non-object root (null literal, array, scalar) falls back to a lower pack's copy.
            if (file == null) return Optional.empty();

            if (file.variants() != null) {
                ConcurrentMap<String, ApplyDto> parsed = cleanVariants(file.variants());
                return Optional.of(parsed.isEmpty() ? new Parsed.Shadow(blockId) : new Parsed.Variants(blockId, parsed));
            }
            if (file.multipart() != null) {
                ConcurrentList<MultipartPart> parsed = cleanParts(file.multipart());
                return Optional.of(parsed.isEmpty() ? new Parsed.Shadow(blockId) : new Parsed.Multipart(blockId, parsed));
            }
            // Valid JSON, nothing renderable: shadow the lower pack's entry (whole-file replace).
            return Optional.of(new Parsed.Shadow(blockId));
        } catch (JsonSyntaxException ex) {
            // Malformed: fall back to a lower pack's copy (no shadow).
            return Optional.empty();
        }
    }

    /**
     * Drops the variant entries whose value skipped the weighted-first rule (a non-object value, an
     * empty array, or an array whose first element is not an object all decode to {@code null}), then
     * freezes the survivors. The result is empty when the {@code "variants"} object carried no usable
     * apply - the shadow signal.
     *
     * @param raw the deserialised {@code "variants"} map, values {@code null} where skipped
     * @return the non-null variant applies, unmodifiable
     */
    private static @NotNull ConcurrentMap<String, ApplyDto> cleanVariants(@NotNull Map<String, ApplyDto> raw) {
        HashMap<String, ApplyDto> result = new HashMap<>();
        raw.forEach((key, apply) -> {
            if (apply != null) result.put(key, apply);
        });
        return Concurrent.adoptMap(result).toUnmodifiable();
    }

    /**
     * Drops the multipart parts a non-object array element ({@code null}) or an absent / empty
     * {@code "apply"} ({@link MultipartPart#apply() apply} {@code null}) skipped, preserving author
     * order. The result is empty when no part carried a usable apply - the shadow signal.
     *
     * @param raw the deserialised {@code "multipart"} list, elements {@code null} or apply-less where skipped
     * @return the renderable parts in author order, unmodifiable
     */
    private static @NotNull ConcurrentList<MultipartPart> cleanParts(@NotNull List<MultipartPart> raw) {
        ArrayList<MultipartPart> result = new ArrayList<>();
        for (MultipartPart part : raw)
            if (part != null && part.apply() != null) result.add(part);
        return Concurrent.adoptList(result).toUnmodifiable();
    }

    /**
     * One parsed blockstate file, routed by the merge pass to the matching per-id map. A file is
     * exactly one of three states, each named rather than encoded in nullable fields: a {@link Variants}
     * or {@link Multipart} definition, or a {@link Shadow} - a valid but non-renderable file that must
     * still erase the lower pack's rows for its block id (vanilla topmost-file-wins).
     */
    private sealed interface Parsed permits Parsed.Variants, Parsed.Multipart, Parsed.Shadow {

        /**
         * The namespaced block id derived from the file name.
         *
         * @return the block id
         */
        @NotNull String blockId();

        /**
         * A variant-form file: a {@code variantKey -> }{@link ApplyDto} map.
         *
         * @param blockId the namespaced block id
         * @param variants the raw variant applies keyed by property-combination string
         */
        record Variants(@NotNull String blockId, @NotNull ConcurrentMap<String, ApplyDto> variants) implements Parsed {}

        /**
         * A multipart-form file: the ordered raw conditional parts.
         *
         * @param blockId the namespaced block id
         * @param multipart the raw parts in author order
         */
        record Multipart(@NotNull String blockId, @NotNull ConcurrentList<MultipartPart> multipart) implements Parsed {}

        /**
         * A valid but non-renderable file (no renderable definition), retained so a higher pack's
         * presence still erases the lower pack's rows for its block id.
         *
         * @param blockId the namespaced block id
         */
        record Shadow(@NotNull String blockId) implements Parsed {}
    }

    /**
     * The raw blockstate data: the variant-form and multipart-form applies across the stack, each
     * still a model reference awaiting its geometry bake in the block index builder. A block appears
     * in at most one of the two maps.
     *
     * @param variants block id to its {@code variantKey -> }{@link ApplyDto} map (variant-form files)
     * @param multiparts block id to its ordered {@link MultipartPart} list (multipart-form files)
     */
    public record BlockStates(
        @NotNull ConcurrentMap<String, ConcurrentMap<String, ApplyDto>> variants,
        @NotNull ConcurrentMap<String, ConcurrentList<MultipartPart>> multiparts
    ) {}

    /**
     * One blockstate {@code "apply"} - the model reference plus whole-block rotation and UV-lock flag,
     * shared by the variant and multipart branches. Read through {@link Adapter}, which folds in the
     * weighted-first rule: an array value yields its first entry, a bare object yields itself, and a
     * value with no usable object (a scalar, an empty array, or an array whose first element is not an
     * object) decodes to {@code null} so the loader drops it. The {@code modelId -> } geometry bake and
     * the property-key parse run downstream in the block index builder.
     *
     * @param model the namespaced model reference (e.g. {@code "minecraft:block/furnace"}), blank when absent
     * @param x the whole-model X rotation in degrees (0, 90, 180, or 270)
     * @param y the whole-model Y rotation in degrees (0, 90, 180, or 270)
     * @param uvlock whether UVs should be locked to the block grid during rotation
     * @param weighted the authored array's usable entries in declaration order, or empty when the value
     *     was a bare object or a one-entry array. Carried raw and uninterpreted: which entry a render
     *     draws is vanilla's choice to make, and it is made downstream once the pack stack has merged.
     *     The members above stay the first entry either way, so the weighted-first rule is unchanged
     */
    @JsonAdapter(ApplyDto.Adapter.class)
    public record ApplyDto(@NotNull String model, int x, int y, boolean uvlock,
                           @NotNull ConcurrentList<ApplyDto> weighted) {

        /**
         * Constructs an apply from one authored object, reading each member through the shared
         * primitive-or-default accessors.
         *
         * @param object the authored apply object
         * @param weighted the sibling entries this object was picked from, empty when there are none
         */
        ApplyDto(@NotNull JsonObject object, @NotNull ConcurrentList<ApplyDto> weighted) {
            this(string(object, "model"), integer(object, "x"), integer(object, "y"), bool(object, "uvlock"), weighted);
        }

        /**
         * Reads one apply, applying the weighted-first rule (harness {@code FirstVariantRandomSource -> 0}
         * parity): an array yields its first element, a bare object yields itself, anything else yields
         * {@code null} for the loader to drop. Field defaults match the former hand parse - a blank
         * {@code model}, zero rotations, {@code uvlock} off - and non-primitive members fall back to
         * those defaults rather than failing the read.
         * <p>
         * A streaming {@link TypeAdapter} (not a {@link JsonDeserializer}) so it composes as a map value:
         * Gson 2.10.1 misreads a {@code Map<String, X>} key when {@code X}'s adapter is a
         * {@code JsonDeserializer}, but a {@code TypeAdapter} value (as {@link ModelTexture} uses) reads
         * the map cleanly. It buffers the current value into a tree so the object read reuses the shared
         * primitive-or-default accessors.
         */
        static final class Adapter extends TypeAdapter<ApplyDto> {

            @Override
            public void write(@NotNull JsonWriter out, @Nullable ApplyDto value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                // An apply that kept its siblings writes back as the array it was read from, so a round
                // trip recovers the same authored list rather than collapsing it to the first entry.
                if (!value.weighted().isEmpty()) {
                    out.beginArray();
                    for (ApplyDto entry : value.weighted()) writeObject(out, entry);
                    out.endArray();
                    return;
                }
                writeObject(out, value);
            }

            /**
             * Writes one apply's scalar members as a JSON object.
             *
             * @param out the writer to emit to
             * @param value the apply to write
             * @throws IOException if the underlying writer fails
             */
            private static void writeObject(@NotNull JsonWriter out, @NotNull ApplyDto value) throws IOException {
                out.beginObject();
                out.name("model").value(value.model());
                out.name("x").value(value.x());
                out.name("y").value(value.y());
                out.name("uvlock").value(value.uvlock());
                out.endObject();
            }

            @Override
            public @Nullable ApplyDto read(@NotNull JsonReader in) throws IOException {
                JsonElement json = JsonParser.parseReader(in);
                JsonElement candidate = json.isJsonArray()
                    ? (json.getAsJsonArray().isEmpty() ? null : json.getAsJsonArray().get(0))
                    : json;
                if (candidate == null || !candidate.isJsonObject()) return null;

                return new ApplyDto(candidate.getAsJsonObject(), weighted(json));
            }

            /**
             * The authored array's usable entries in declaration order, or an empty list for a bare
             * object or a one-entry array. A lone entry is the weighted-first pick itself, so recording
             * it would only duplicate the scalar members.
             *
             * @param json the raw apply value
             * @return the authored entries, empty when the value offers no choice
             */
            private static @NotNull ConcurrentList<ApplyDto> weighted(@NotNull JsonElement json) {
                if (!json.isJsonArray() || json.getAsJsonArray().size() < 2) return Concurrent.newList();

                ConcurrentList<ApplyDto> entries = Concurrent.newList();
                for (JsonElement entry : json.getAsJsonArray())
                    if (entry.isJsonObject()) entries.add(new ApplyDto(entry.getAsJsonObject(), Concurrent.newList()));

                return entries.size() < 2 ? Concurrent.newList() : entries.toUnmodifiable();
            }
        }
    }

    /**
     * One multipart part - a {@code "when"} condition and the apply it renders when the condition
     * matches. Read through {@link Adapter}, which decodes a non-object array element to {@code null}
     * (for the loader to drop), an absent {@code "when"} to {@link Block.Multipart.When.Always}, and
     * the {@code "apply"} through {@link ApplyDto.Adapter}.
     *
     * @param when the parsed condition, {@link Block.Multipart.When.Always} for an unconditional part
     * @param apply the raw apply, {@code null} when the part carried no usable apply (dropped by the loader)
     */
    @JsonAdapter(MultipartPart.Adapter.class)
    public record MultipartPart(@NotNull Block.Multipart.When when, @Nullable ApplyDto apply) {

        /**
         * Reads one multipart part. A non-object element decodes to {@code null}; an object reads its
         * {@code "when"} (absent yields {@link Block.Multipart.When.Always}) and its {@code "apply"}
         * through the shared apply adapter, both via the deserialization context.
         */
        static final class Adapter implements JsonDeserializer<MultipartPart> {

            @Override
            public @Nullable MultipartPart deserialize(@NotNull JsonElement json, @NotNull Type type, @NotNull JsonDeserializationContext context) {
                if (!json.isJsonObject()) return null;
                JsonObject object = json.getAsJsonObject();
                Block.Multipart.When when = object.has("when")
                    ? context.deserialize(object.get("when"), Block.Multipart.When.class)
                    : new Block.Multipart.When.Always();
                ApplyDto apply = object.has("apply") ? context.deserialize(object.get("apply"), ApplyDto.class) : null;
                return new MultipartPart(when, apply);
            }
        }
    }

    /**
     * The string member under {@code key} when it is a JSON primitive, or {@code ""} - matching the
     * former {@code getString(key, "")} hand parse (a non-primitive member falls back to blank).
     */
    private static @NotNull String string(@NotNull JsonObject object, @NotNull String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /**
     * The int member under {@code key} when it is a JSON primitive, or {@code 0} - matching the former
     * {@code getInt(key, 0)} hand parse.
     */
    private static int integer(@NotNull JsonObject object, @NotNull String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
    }

    /**
     * The boolean member under {@code key} when it is a JSON primitive, or {@code false} - matching the
     * former {@code getBool(key, false)} hand parse.
     */
    private static boolean bool(@NotNull JsonObject object, @NotNull String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    /**
     * The {@code "variants"} / {@code "multipart"} envelope of a blockstate file. At most one member is
     * non-null: {@code "variants"} wins when both keys are present (matching the vanilla precedence),
     * and a valid file with neither renderable key leaves both null (a shadow).
     * <p>
     * Read through {@link Deserializer} rather than reflectively: Gson 2.10.1 misresolves a record
     * component of type {@code Map<String, X>} whose value type carries a custom adapter, so the
     * {@code "variants"} object is walked by hand and each apply deserialised through the context.
     *
     * @param variants the {@code "variants"} object with per-key applies, or {@code null} when the key
     *     is absent (or a {@code "multipart"} file)
     * @param multipart the {@code "multipart"} array, or {@code null} when the key is absent (or a
     *     {@code "variants"} file)
     */
    @JsonAdapter(BlockStateFile.Deserializer.class)
    record BlockStateFile(@Nullable Map<String, ApplyDto> variants, @Nullable List<MultipartPart> multipart) {

        /**
         * Reads the blockstate envelope, walking the {@code "variants"} object or the {@code "multipart"}
         * array directly ({@code "variants"} wins when both are present, as vanilla does). A non-object
         * root, or a present {@code "variants"} / {@code "multipart"} whose value is the wrong JSON kind,
         * yields {@code null} so the caller falls back to a lower pack's copy - the former hand parse's
         * malformed-file behaviour.
         */
        static final class Deserializer implements JsonDeserializer<BlockStateFile> {

            @Override
            public @Nullable BlockStateFile deserialize(@NotNull JsonElement json, @NotNull Type type, @NotNull JsonDeserializationContext context) {
                if (!json.isJsonObject()) return null;
                JsonObject root = json.getAsJsonObject();

                if (root.has("variants")) {
                    JsonElement variants = root.get("variants");
                    if (!variants.isJsonObject()) return null;
                    LinkedHashMap<String, ApplyDto> applies = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> entry : variants.getAsJsonObject().entrySet())
                        applies.put(entry.getKey(), context.deserialize(entry.getValue(), ApplyDto.class));
                    return new BlockStateFile(applies, null);
                }
                if (root.has("multipart")) {
                    JsonElement multipart = root.get("multipart");
                    if (!multipart.isJsonArray()) return null;
                    ArrayList<MultipartPart> parts = new ArrayList<>();
                    for (JsonElement element : multipart.getAsJsonArray())
                        parts.add(context.deserialize(element, MultipartPart.class));
                    return new BlockStateFile(null, parts);
                }
                return new BlockStateFile(null, null);
            }
        }
    }

}
