package lib.minecraft.renderer.pipeline.dump;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pipeline.loader.PalettedPermutationLoader;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.pack.FormatRange;
import lib.minecraft.renderer.pipeline.pack.IndexedTexture;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResolvedTexture;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelNode;
import lib.minecraft.renderer.pipeline.pack.item.ItemNodeAccess;
import lib.minecraft.renderer.pipeline.pack.item.SpecialTransform;
import lib.minecraft.renderer.pipeline.pack.rule.BlockMatch;
import lib.minecraft.renderer.pipeline.pack.rule.CitOutput;
import lib.minecraft.renderer.pipeline.pack.rule.CitRule;
import lib.minecraft.renderer.pipeline.pack.rule.CtmExtras;
import lib.minecraft.renderer.pipeline.pack.rule.CtmRule;
import lib.minecraft.renderer.pipeline.pack.rule.CtmTarget;
import lib.minecraft.renderer.pipeline.pack.rule.IntRanges;
import lib.minecraft.renderer.pipeline.pack.rule.NbtLiteralText;
import lib.minecraft.renderer.pipeline.pack.rule.NbtPath;
import lib.minecraft.renderer.pipeline.pack.rule.NbtPredicate;
import lib.minecraft.renderer.pipeline.pack.rule.NbtRule;
import lib.minecraft.renderer.pipeline.pack.rule.RuleSet;
import lib.minecraft.renderer.pipeline.pack.rule.TileRef;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Canonical semantic dump of a fully-loaded pipeline + renderer context - the enforcement oracle for
 * the pipeline-cleanup phase gates.
 * <p>
 * The series is dozens of load-side refactors. Proving each one moved no pixel by re-rendering the
 * whole roster costs minutes per phase; proving the RENDER INPUTS are byte-identical costs seconds,
 * and implies the same thing whenever the phase's diff leaves the render path alone. This class
 * writes those inputs.
 * <p>
 * <b>Altitude.</b> The dump is taken at renderer-context level, not {@code Pipeline.Result} level.
 * Five loaders ({@code BlockModelLoader}, {@code BlockIndexLoader}, {@code ItemIndexLoader},
 * {@code EntityModelLoader}, {@code PalettedPermutationLoader}) run inside
 * {@link PipelineRendererContext#of} and their outputs exist nowhere else - and they are precisely
 * the loaders this series rewrites. A Result-level dump would green-light breaking every one of them.
 * <p>
 * <b>Vocabulary is frozen and deliberately NOT Java field names.</b> Keys here are schema constants.
 * When a DTO field is renamed, one accessor call in this file changes and zero output bytes move,
 * which is what lets the gate stay green across the planned renames instead of crying wolf on them.
 * <p>
 * <b>Never dumped:</b> decoded pixel buffers and the context's lazy texture cache (mutating,
 * probe-order-dependent), the atlas (non-deterministic by design), {@code packRoot} (machine-relative
 * and scheduled for removal), and file timestamps.
 * <p>
 * Usage: {@code PipelineParityDump <label>} - writes {@code cache/parity-dump/<label>/{vanilla,packs}/}.
 */
public final class PipelineParityDump {

    /** Fixture packs for the second configuration - the only ones that light up CIT/CTM, the {@code .cats} container, and the non-filename pack-id rungs. */
    private static final @NotNull List<String> PACK_FIXTURES = List.of(
        "defrosted", "hypixel-skyblock", "eureka.cats.zip"
    );

    private PipelineParityDump() {}

    /**
     * Loads each configuration and writes its dump.
     *
     * @param args the output label; defaults to {@code head} when absent or blank
     * @throws IOException if a section cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        String label = args.length > 0 && !args[0].isBlank() ? args[0] : "head";
        Path root = Path.of("cache", "parity-dump", label);

        long started = System.currentTimeMillis();
        dump(PipelineOptions.defaults(), root.resolve("vanilla"));

        List<File> fixtures = fixtures();
        if (fixtures.size() < PACK_FIXTURES.size()) {
            System.out.println("SKIPPED packs configuration: missing fixtures under cache/asset-renderer/packs/ "
                + "(want " + PACK_FIXTURES + ", found " + fixtures.stream().map(File::getName).toList() + "). "
                + "The F3/F4/F5/W5 phases have NO evidence without it.");
        } else {
            dump(PipelineOptions.builder().texturePacks(Concurrent.adoptList(fixtures)).build(), root.resolve("packs"));
        }

        System.out.println("parityDump '" + label + "' written in " + (System.currentTimeMillis() - started) + "ms");
    }

    /**
     * Returns the fixture packs that exist on this machine, in ascending priority order.
     *
     * @return the present fixtures
     */
    private static @NotNull List<File> fixtures() {
        List<File> present = new ArrayList<>();
        for (String name : PACK_FIXTURES) {
            File file = Path.of("cache", "asset-renderer", "packs", name).toFile();
            if (file.exists()) present.add(file);
        }
        return present;
    }

    /**
     * Loads one configuration and writes every section plus the manifest.
     *
     * @param options the configuration to load
     * @param directory the output directory for this configuration
     * @throws IOException if a section cannot be written
     */
    private static void dump(@NotNull PipelineOptions options, @NotNull Path directory) throws IOException {
        Pipeline.Result result = Pipeline.run(options);
        PipelineRendererContext context = PipelineRendererContext.of(result);
        PackStack stack = result.getStack();

        // An empty index does not fail anything downstream - every lookup just returns empty - so without
        // these the dump would write a well-formed, fully-populated-looking artifact of nothing at all,
        // and two of them would compare equal. A gate that passes by loading nothing is worse than no gate.
        if (stack.textureIndex().isEmpty())
            throw new IllegalStateException("texture index is empty - the dump would silently emit {} for every "
                + "texture-derived section; the stack was not built through withTextureIndex()");
        if (context.knownBlockIds().isEmpty() || context.knownItemIds().isEmpty())
            throw new IllegalStateException("block index (" + context.knownBlockIds().size() + ") or item index ("
                + context.knownItemIds().size() + ") is empty - the blocks/items sections and the id_order and "
                + "icon_gui probes would silently emit nothing; BlockIndexLoader/ItemIndexLoader produced no rows");

        Path base = Path.of("").toAbsolutePath().normalize();
        Map<String, JsonObject> sections = new LinkedHashMap<>();

        sections.put("run", run(options));
        sections.put("packs", packs(stack, base));
        sections.put("textures", textures(stack));
        sections.put("block-models", models(result.getBlockModels()));
        sections.put("item-models", models(result.getItemModels()));
        sections.put("blocks", blocks(context));

        // The same pure call PipelineRendererContext.of makes at build time, re-run because the context
        // keeps its block-entity map private with no accessor and declares no knownBlockEntityIds().
        // Walking knownBlockIds() + findBlockEntityEntry() instead would be silently incomplete: an
        // additive block entity that reaches neither a block model nor a blockstate exists in this map
        // and never in blockIndex. Today that set is empty by coincidence, not by invariant.
        BlockModelLoader.LoadResult blockEntities = BlockModelLoader.load(stack);
        sections.put("block-entities", blockEntities(blockEntities));

        // Likewise re-run: the context exposes only findEntity(id) and declares no knownEntityIds(), so
        // there is no way to enumerate the index it holds. This is the same call it made to build it.
        sections.put("entities", CanonicalJson.map(EntityModelLoader.load(), PipelineParityDump::entity));
        sections.put("items", items(context));
        sections.put("rules", rules(result.getRules()));
        sections.put("synthesis", synthesis(stack));
        sections.put("misc", misc(result));
        sections.put("probes", probes(context, stack));
        sections.put("trees", CanonicalJson.map(result.getItemTrees(), tree -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", tree.id().id());
            entry.add("root", node(tree.root()));
            return entry;
        }));

        for (Map.Entry<String, JsonObject> section : sections.entrySet())
            CanonicalJson.write(directory.resolve(section.getKey() + ".json"), section.getValue());

        manifest(directory, sections.keySet());
    }

    /**
     * Returns the run header - the inputs the dump itself depends on. Without this a capture is not
     * reproducible: the JavaExec fork inherits its {@code asset.*} system properties from the
     * long-lived Gradle daemon rather than from the command line, so two captures with identical
     * command lines can disagree. Recording them makes that disagreement a visible diff.
     *
     * @param options the configuration that was loaded
     * @return the run section
     */
    private static @NotNull JsonObject run(@NotNull PipelineOptions options) {
        JsonObject root = new JsonObject();
        root.addProperty("version", options.getVersion());
        root.add("texture_packs", CanonicalJson.ordered(
            options.getTexturePacks(), pack -> CanonicalJson.path(pack.toPath(), Path.of("").toAbsolutePath())));

        Properties properties = System.getProperties();
        Map<String, String> assetFlags = new TreeMap<>();
        for (String name : properties.stringPropertyNames())
            if (name.startsWith("asset.")) assetFlags.put(name, properties.getProperty(name));
        root.add("asset_flags", CanonicalJson.map(assetFlags, JsonPrimitive::new));

        return root;
    }

    /**
     * Returns the pack section - every pack in ascending priority order.
     * <p>
     * The pack list is ORDERED (it is the priority order, and element 0 is always vanilla), so it is
     * emitted verbatim. Everything hanging off a pack is not: {@code namespaces} and
     * {@code capabilities} are {@code Set.copyOf} instances whose iteration order is randomized per
     * JVM run, so they are sorted here. {@code primaryNamespace} is deliberately NOT dumped - it is
     * a {@code findFirst} over one of those randomized sets, so it can flap between runs.
     *
     * @param stack the resolved pack stack
     * @param base the directory to relativize container paths against
     * @return the packs section
     */
    private static @NotNull JsonObject packs(@NotNull PackStack stack, @NotNull Path base) {
        JsonObject root = new JsonObject();
        root.add("ascending", CanonicalJson.ordered(stack.ascending(), pack -> pack(pack, base)));
        return root;
    }

    /**
     * Returns one pack's canonical form.
     *
     * @param pack the pack to emit
     * @param base the directory to relativize the container path against
     * @return the pack object
     */
    private static @NotNull JsonObject pack(@NotNull ResourcePack pack, @NotNull Path base) {
        JsonObject root = new JsonObject();
        root.addProperty("id", pack.id().value());
        root.add("container", container(pack.container(), base));
        root.add("roots", CanonicalJson.ordered(pack.roots(), packRoot -> new JsonPrimitive(packRoot.prefix())));
        root.add("namespaces", CanonicalJson.strings(pack.namespaces()));
        root.add("capabilities", CanonicalJson.strings(pack.capabilities().stream().map(Enum::name).toList()));
        root.add("meta", meta(pack.meta()));
        return root;
    }

    /**
     * Returns a container's kind and backing path.
     *
     * @param container the container to emit
     * @param base the directory to relativize against
     * @return the container object
     */
    private static @NotNull JsonObject container(@NotNull PackContainer container, @NotNull Path base) {
        JsonObject root = new JsonObject();
        switch (container) {
            case PackContainer.Directory directory -> {
                root.addProperty("kind", "directory");
                root.add("path", CanonicalJson.path(directory.root(), base));
            }
            case PackContainer.Zip zip -> {
                root.addProperty("kind", "zip");
                root.add("path", CanonicalJson.path(zip.zip(), base));
            }
            case PackContainer.Cats cats -> {
                root.addProperty("kind", "cats");
                root.add("path", CanonicalJson.path(cats.source(), base));
            }
        }
        return root;
    }

    /**
     * Returns an mcmeta's canonical form. Regexes are emitted via {@link java.util.regex.Pattern#pattern}
     * - {@code Pattern} overrides neither {@code equals} nor {@code hashCode}, so anything derived
     * from its identity would be per-run garbage.
     *
     * @param meta the mcmeta to emit
     * @return the mcmeta object
     */
    private static @NotNull JsonObject meta(@NotNull MCMeta meta) {
        JsonObject root = new JsonObject();
        root.addProperty("id", meta.id().id());

        CanonicalJson.put(root, "pack", meta.pack(), pack -> {
            JsonObject section = new JsonObject();
            section.add("formats", formats(pack.formats()));
            section.addProperty("description", pack.description().plain());
            section.add("overlays", CanonicalJson.ordered(pack.overlays(), overlay -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("directory", overlay.directory());
                entry.add("formats", formats(overlay.formats()));
                return entry;
            }));
            section.add("filters", CanonicalJson.ordered(pack.filters(), filter -> {
                JsonObject entry = new JsonObject();
                CanonicalJson.put(entry, "namespace", filter.namespace(), pattern -> new JsonPrimitive(pattern.pattern()));
                CanonicalJson.put(entry, "path", filter.path(), pattern -> new JsonPrimitive(pattern.pattern()));
                return entry;
            }));
            return section;
        });

        CanonicalJson.put(root, "animation", meta.animation(), animation -> {
            JsonObject section = new JsonObject();
            section.addProperty("frametime", animation.frametime());
            section.addProperty("interpolate", animation.interpolate());
            section.addProperty("width", animation.width());
            section.addProperty("height", animation.height());
            section.add("frames", CanonicalJson.ordered(animation.frames(), frame -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("index", frame.index());
                entry.addProperty("time", frame.time());
                return entry;
            }));
            return section;
        });

        CanonicalJson.put(root, "gui", meta.gui(), gui -> {
            JsonObject section = new JsonObject();
            section.addProperty("type", gui.type().name());
            section.addProperty("width", gui.width());
            section.addProperty("height", gui.height());
            section.addProperty("stretch_inner", gui.stretchInner());
            return section;
        });

        return root;
    }

    /**
     * Returns a format range as {@code [[minMajor, minMinor], [maxMajor, maxMinor]]}.
     *
     * @param range the range to emit
     * @return the range array
     */
    private static @NotNull JsonArray formats(@NotNull FormatRange range) {
        JsonArray root = new JsonArray(2);
        JsonArray min = new JsonArray(2);
        min.add(range.min().major());
        min.add(range.min().minor());
        JsonArray max = new JsonArray(2);
        max.add(range.max().major());
        max.add(range.max().minor());
        root.add(min);
        root.add(max);
        return root;
    }

    /**
     * Returns the texture-index section, sourced STRICTLY from {@link PackStack#textureIndex}.
     * <p>
     * It is never built by iterating ids through {@code resolve} / {@code resolveIn}: those fabricate
     * synthetic rows with zero dimensions and no sidecar, and they mutate the stack's
     * ambiguity-logging set as a side effect - a dump that walked them would be serializing an object
     * it was concurrently changing.
     * <p>
     * {@code size} is sourced from the row's width/height, which are filled by a per-PNG decode at
     * index time and stay {@code 0} for an undecodable PNG ({@code 0} means unknown, not empty).
     * Those two fields are deleted when {@code IndexedTexture} merges into {@code ResolvedTexture},
     * at which point {@code size} leaves the dump through that phase's removal-only manifest.
     *
     * @param stack the resolved pack stack
     * @return the textures section
     */
    private static @NotNull JsonObject textures(@NotNull PackStack stack) {
        return CanonicalJson.map(stack.textureIndex(), ResourceId::id, PipelineParityDump::texture);
    }

    /**
     * Returns one texture-index row.
     *
     * @param texture the row to emit
     * @return the row object
     */
    private static @NotNull JsonObject texture(@NotNull IndexedTexture texture) {
        JsonObject root = new JsonObject();
        root.addProperty("pack", texture.pack().value());
        root.addProperty("path", texture.relativePath());
        root.add("size", size(texture.width(), texture.height()));
        CanonicalJson.put(root, "meta", texture.meta(), PipelineParityDump::meta);
        return root;
    }

    /**
     * Returns the block section, keyed by block id.
     * <p>
     * Enumerated through {@code knownBlockIds} because the context keeps {@code blockIndex} private
     * with no accessor. That list is complete (it is built from the index's own key set) but its ORDER
     * is not id order - it sorts by primary tag, whose tie-break reads a hash-ordered tag list - so it
     * is used strictly as a key source and the map is re-keyed by plain id here. Its order is pinned
     * separately, and deliberately, by the {@code id_order} probe.
     *
     * @param context the loaded renderer context
     * @return the blocks section
     */
    private static @NotNull JsonObject blocks(@NotNull PipelineRendererContext context) {
        JsonObject root = new JsonObject();
        for (String id : context.knownBlockIds())
            root.add(id, context.findBlock(id).map(PipelineParityDump::block).orElseThrow(
                () -> new IllegalStateException("knownBlockIds offered '" + id + "' but findBlock could not "
                    + "resolve it - the index and its key source disagree")));
        return root;
    }

    /**
     * Returns one block.
     * <p>
     * {@code model} is emitted as a content digest rather than inline: the loaded {@code ModelData} is
     * shared BY REFERENCE across many blocks, so inlining would repeat one model up to a thousand times.
     * The digest joins to the matching {@code digest} in {@code block-models.json}. A block whose model
     * id never resolved holds an element-less model that appears in no section - its digest still
     * identifies it exactly.
     * <p>
     * {@code default_state_key} is emitted even when {@code ""}: the empty string is loaded state (the
     * block has no properties), not absence.
     *
     * @param block the block to emit
     * @return the block object
     */
    private static @NotNull JsonObject block(@NotNull Block block) {
        JsonObject root = new JsonObject();
        root.addProperty("id", block.id().id());
        root.addProperty("model", CanonicalJson.digest(model(block.model())));
        root.add("textures", CanonicalJson.map(block.textures(), JsonPrimitive::new));
        root.add("variants", CanonicalJson.map(block.variants(), PipelineParityDump::variant));
        root.add("tags", CanonicalJson.strings(block.tags()));
        root.add("tint", tint(block.tint()));
        root.addProperty("source", block.source().name());
        root.addProperty("default_state_key", block.defaultStateKey());
        root.addProperty("item_block_id", block.itemBlockId().id());
        CanonicalJson.put(root, "multipart", block.multipart(), multipart ->
            CanonicalJson.ordered(multipart.parts(), PipelineParityDump::part));
        CanonicalJson.put(root, "entity", block.entity(), PipelineParityDump::blockEntity);
        return root;
    }

    /**
     * Returns a block's tint target. {@code constant} is an ARGB colour and is present only for the
     * {@code CONSTANT} target.
     *
     * @param tint the tint to emit
     * @return the tint object
     */
    private static @NotNull JsonObject tint(@NotNull Block.Tint tint) {
        JsonObject root = new JsonObject();
        root.addProperty("target", tint.target().name());
        CanonicalJson.put(root, "constant", tint.constant(), CanonicalJson::argb);
        return root;
    }

    /**
     * Returns one blockstate variant. Its geometry is a two-case union: a plain model, or a
     * block-entity bone mesh selected by state.
     *
     * @param variant the variant to emit
     * @return the variant object
     */
    private static @NotNull JsonObject variant(@NotNull Block.Variant variant) {
        JsonObject root = new JsonObject();
        root.addProperty("model_id", variant.modelId());
        root.addProperty("x", variant.x());
        root.addProperty("y", variant.y());
        root.addProperty("uvlock", variant.uvlock());
        switch (variant.geometry()) {
            case Block.ElementGeometry geometry -> {
                root.addProperty("geometry_kind", "element");
                root.addProperty("geometry_model", CanonicalJson.digest(model(geometry.model())));
            }
            case Block.BoneGeometry geometry -> {
                root.addProperty("geometry_kind", "bone");
                root.add("geometry_bone_model", boneModel(geometry.boneModel()));
            }
        }
        return root;
    }

    /**
     * Returns one multipart part. The parts list is ORDERED - the renderer walks it in order and
     * appends the triangles of every part that matches, so the sequence is paint priority.
     *
     * @param part the part to emit
     * @return the part object
     */
    private static @NotNull JsonObject part(@NotNull Block.Multipart.Part part) {
        JsonObject root = new JsonObject();
        root.add("apply", variant(part.apply()));
        if (part.when() != null) root.add("when", condition(part.when()));
        return root;
    }

    /**
     * Returns a multipart {@code when} in canonical PARSED form - a three-case tagged union.
     * <p>
     * The loaded field is a raw {@code JsonObject} held verbatim, so a naive dump would serialize the
     * author's JSON rather than the condition the renderer actually evaluates, and the gate would report
     * a diff on every cosmetic re-spelling while missing a real change in meaning. The matcher's own
     * precedence is reproduced here instead:
     * <ul>
     * <li><b>{@code AND} is checked first and returns</b> - a {@code when} carrying {@code AND} ignores
     * any sibling {@code OR} AND any sibling plain properties entirely. Exclusive and absorbing.</li>
     * <li><b>{@code OR} only when no {@code AND}</b>, and likewise absorbing over plain properties.</li>
     * <li><b>properties</b> only when neither key is present - an implicit AND across every entry.</li>
     * </ul>
     * Both branches recurse, so the form nests. Property values are read through {@code getAsString},
     * which makes a JSON {@code true} and the string {@code "true"} indistinguishable to the renderer -
     * they are coerced here for the same reason. {@code |} alternatives are pre-split, in author order:
     * the test is a membership check so order carries no meaning, but the source is stable JSON and
     * sorting it would discard the signal of a parser that reordered them.
     * <p>
     * A {@code when} that is absent is a key omitted by the caller, which stays distinguishable from a
     * {@code when} that is present but empty.
     *
     * @param when the raw condition object
     * @return the parsed condition object
     */
    private static @NotNull JsonObject condition(@NotNull JsonObject when) {
        JsonObject root = new JsonObject();
        if (when.has("AND")) {
            root.add("and", CanonicalJson.ordered(
                when.getAsJsonArray("AND").asList(), element -> condition(element.getAsJsonObject())));
            return root;
        }
        if (when.has("OR")) {
            root.add("or", CanonicalJson.ordered(
                when.getAsJsonArray("OR").asList(), element -> condition(element.getAsJsonObject())));
            return root;
        }

        JsonObject props = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : when.entrySet()) {
            String required = entry.getValue().getAsString();
            JsonArray alternatives = new JsonArray();
            for (String alternative : required.split("\\|"))
                alternatives.add(alternative);
            props.add(entry.getKey(), alternatives);
        }
        root.add("props", props);
        return root;
    }

    /** How many of a pack's own texture ids the {@code resolveIn} probe samples. */
    private static final int RESOLVE_IN_SAMPLES = 25;

    /**
     * Returns the behaviour probes - the derived answers the renderer actually asks for.
     * <p>
     * Index identity alone cannot pin resolution LOGIC: a phase can rewire the whole delegation chain and
     * leave every index byte-identical while changing what a lookup returns. These probes call the same
     * public methods the renderers call, so "probes identical" means "render inputs identical" even
     * across a rewrite that leaves no trace in any other section.
     * <p>
     * <b>Probing is safe and order-independent.</b> The only state a resolution mutates is the stack's
     * ambiguity-log set, which gates a stderr line and is read nowhere else, so no probe result depends
     * on any earlier probe having run. The mutating methods are avoided by construction: this walks
     * {@code stack.resolve} / {@code stack.resolveIn}, never the context's {@code resolveTexture}, which
     * decodes the PNG and writes the texture cache.
     * <p>
     * The parameter is the concrete context, not the {@code RendererContext} interface, and deliberately:
     * most of that interface's defaults return empty, but {@code resolveIconGui}'s default carries real
     * logic and falls back to the BLOCK model's gui when the item lookups come back empty. A stub would
     * not produce a loudly empty {@code icon_gui} - it would produce a populated, plausible, quietly
     * wrong one. A compile-time type rules that out and cannot rot the way an {@code instanceof} can.
     *
     * @param context the loaded renderer context
     * @param stack the resolved pack stack
     * @return the probes section
     */
    private static @NotNull JsonObject probes(@NotNull PipelineRendererContext context, @NotNull PackStack stack) {
        JsonObject root = new JsonObject();

        // Pins the base-first root walk: a row's path in the textures section is root-RELATIVE, so this
        // resolved, root-prefixed path is the only place the dump records which root won.
        JsonObject resolution = new JsonObject();
        for (ResourceId id : sortedIds(stack.textureIndex().keySet()))
            CanonicalJson.put(resolution, id.id(), stack.resolve(id), PipelineParityDump::resolved);
        root.add("resolution", resolution);

        JsonObject iconGui = new JsonObject();
        for (String id : context.knownBlockIds())
            CanonicalJson.put(iconGui, id, context.resolveIconGui(context.findBlock(id).orElseThrow()),
                PipelineParityDump::transform);
        root.add("icon_gui", iconGui);

        // VERBATIM arrays: this sequence IS the probe. It is sorted by primary tag, not by id, and it
        // feeds atlas layout - emitting it as a sorted set would destroy the only thing being pinned.
        JsonObject idOrder = new JsonObject();
        idOrder.add("blocks", CanonicalJson.ordered(context.knownBlockIds(), JsonPrimitive::new));
        idOrder.add("items", CanonicalJson.ordered(context.knownItemIds(), JsonPrimitive::new));
        root.add("id_order", idOrder);

        // Not a duplicate of the textures section's meta: between the two sits an adapter that rebuilds
        // the record with its fields in a different order, hopping two adjacent ints across a reference
        // parameter. A transposition there compiles clean and is invisible everywhere else in the dump.
        JsonObject animations = new JsonObject();
        for (ResourceId id : sortedIds(stack.textureIndex().keySet()))
            CanonicalJson.put(animations, id.id(), context.findAnimation(id.id()), PipelineParityDump::animation);
        root.add("animation", animations);

        root.add("resolve_in", resolveIn(stack));
        return root;
    }

    /**
     * Returns the pack-restricted resolution probe - a genuinely different code path from
     * {@code resolve}, which consults the texture index while this one probes the container directly.
     * <p>
     * Guarded rather than trusted. A resolved id's namespace is chosen by a within-pack search order
     * that leads with the pack's primary namespace, and that is a {@code findFirst} over a per-run-salted
     * set: when TWO namespaces normalize to the pack's id, which one leads flaps between runs. The dump
     * already refuses to emit {@code primaryNamespace} for exactly this reason, and this probe would
     * smuggle it back in through the resolved id. So the candidate count is emitted for every pack - it
     * is a count, always deterministic - and the samples are emitted only where that count cannot flap.
     * A pack that trips the guard says so in the artifact rather than diffing at random.
     *
     * @param stack the resolved pack stack
     * @return the resolve-in probe
     */
    private static @NotNull JsonObject resolveIn(@NotNull PackStack stack) {
        JsonObject root = new JsonObject();
        root.add("packs", CanonicalJson.ordered(stack.ascending(), pack -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("pack", pack.id().value());

            long candidates = pack.namespaces().stream()
                .filter(namespace -> PackId.normalize(namespace).filter(pack.id()::equals).isPresent())
                .count();
            entry.addProperty("namespace_candidates", candidates);

            if (candidates > 1) {
                entry.addProperty("samples_omitted", "primary namespace is ambiguous, so the resolved id's "
                    + "namespace is picked by a findFirst over a per-run-salted set and would flap");
                return entry;
            }

            JsonObject samples = new JsonObject();
            stack.textureIndex().entrySet().stream()
                .filter(row -> row.getValue().pack().equals(pack.id()))
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(ResourceId::id))
                .limit(RESOLVE_IN_SAMPLES)
                .forEach(id -> CanonicalJson.put(samples, id.id(), stack.resolveIn(pack.id(), id),
                    PipelineParityDump::resolved));
            entry.add("samples", samples);
            return entry;
        }));
        return root;
    }

    /**
     * Returns one resolved texture.
     * <p>
     * {@code bytes()} is never called, here or anywhere: the root-prefixed entry path IS the content
     * identity (the same path is the same file), so digesting would mean reading every PNG in the stack
     * for no added discriminating power, and would add a throw surface for an entry that vanished between
     * resolution and read. The container is not emitted either - it is the same object the packs section
     * already dumps for this pack.
     *
     * @param resolved the resolution to emit
     * @return the resolution object
     */
    private static @NotNull JsonObject resolved(@NotNull ResolvedTexture resolved) {
        JsonObject root = new JsonObject();
        root.addProperty("pack", resolved.pack().value());
        root.addProperty("id", resolved.id().id());
        root.addProperty("entry", resolved.path());
        return root;
    }

    /**
     * Returns one animation. Every {@code -1} here is loaded state - an inherited dimension, or a frame
     * deferring to the sheet's frametime - so all of them are emitted. Omission is reserved strictly for
     * {@code Optional.empty()}.
     *
     * @param animation the animation to emit
     * @return the animation object
     */
    private static @NotNull JsonObject animation(@NotNull AnimationData animation) {
        JsonObject root = new JsonObject();
        root.addProperty("frametime", animation.frametime());
        root.addProperty("interpolate", animation.interpolate());
        root.addProperty("width", animation.width());
        root.addProperty("height", animation.height());
        root.add("frames", CanonicalJson.ordered(animation.frames(), frame -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("index", frame.index());
            entry.addProperty("time", frame.time());
            return entry;
        }));
        return root;
    }

    /**
     * Returns resource ids in a total, deterministic order.
     *
     * @param ids the ids to sort
     * @return the sorted ids
     */
    private static @NotNull List<ResourceId> sortedIds(@NotNull Collection<ResourceId> ids) {
        return ids.stream().sorted(Comparator.comparing(ResourceId::id)).toList();
    }

    /**
     * Returns the miscellaneous section - the loaded families that are each too small to own a file.
     *
     * @param result the loaded pipeline result
     * @return the misc section
     */
    private static @NotNull JsonObject misc(Pipeline.@NotNull Result result) {
        JsonObject root = new JsonObject();
        root.add("block_tints", CanonicalJson.map(result.getBlockTints(), PipelineParityDump::tint));
        root.add("potion_effect_colors", CanonicalJson.map(result.getPotionEffectColors(), CanonicalJson::argb));
        root.add("glint_items", CanonicalJson.strings(result.getGlintItems()));
        root.add("block_default_state_keys", CanonicalJson.map(result.getBlockDefaultStateKeys(), JsonPrimitive::new));
        root.add("block_item_aliases", CanonicalJson.map(result.getBlockItemAliases(), JsonPrimitive::new));

        // Not the parsed item definitions the name suggests, and not every item: the loader keeps an
        // entry only where the tree's root is a plain model AND that model is a block-model ref, so this
        // is the block-item inventory-model projection. A dispatch-rooted item is absent by design.
        root.add("block_item_models", CanonicalJson.map(result.getItemDefinitions(), JsonPrimitive::new));

        root.add("block_tags", CanonicalJson.map(result.getBlockTags(), tag -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", tag.id().id());
            // ORDERED: the loader appends in JSON encounter order, resolving nested #tag refs in place,
            // so the sequence is authored rather than hash-derived.
            entry.add("values", CanonicalJson.ordered(tag.values(), JsonPrimitive::new));
            return entry;
        }));

        root.add("banner_patterns", CanonicalJson.map(result.getBannerPatterns(), pattern -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", pattern.id());
            entry.addProperty("asset_id", pattern.assetId());
            entry.addProperty("translation_key", pattern.translationKey());
            return entry;
        }));

        // ORDERED inner list: a tint's index IS its layer index.
        root.add("item_tints", CanonicalJson.map(result.getItemTints(),
            tints -> CanonicalJson.ordered(tints, PipelineParityDump::tint)));

        // Sourced from the loaded result rather than probed back out of the context: the colormap decode
        // path applies sRGB gamma where the texture-resolution path does not, so the same PNG yields
        // different bytes through the two, and only these are the ones the renderer actually holds.
        root.add("color_maps", CanonicalJson.map(result.getColorMaps(), Enum::name, colorMap -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", colorMap.id());
            entry.addProperty("pack", colorMap.packId());
            entry.add("pixels", CanonicalJson.bytes(colorMap.pixels()));
            return entry;
        }));

        return root;
    }

    /**
     * Returns the rule section - the MERGED pack rules.
     * <p>
     * There is no per-pack view to choose against: the merge builds the per-pack sets as a local, folds
     * them, and drops them, so the merged form is the only one that outlives loading. That is convenient,
     * because the per-pack sets carry filesystem-walk order while the merged lists are sorted by a total
     * comparator (weight, then filename, then pack priority, then id) and are walked first-match-wins at
     * render. So they are deterministic AND their order is semantic: emitted verbatim.
     * <p>
     * Empty of rules under the vanilla configuration, which ships no {@code optifine/} tree - this
     * section only has content under the packs configuration, which is why that configuration is not
     * optional evidence for any phase touching rule loading.
     *
     * @param rules the merged rule set
     * @return the rules section
     */
    private static @NotNull JsonObject rules(@NotNull RuleSet rules) {
        JsonObject root = new JsonObject();
        root.addProperty("pack", rules.pack().value());
        root.add("cit_rules", CanonicalJson.ordered(rules.citRules(), PipelineParityDump::citRule));
        root.add("ctm_rules", CanonicalJson.ordered(rules.ctmRules(), PipelineParityDump::ctmRule));

        JsonObject colors = new JsonObject();
        colors.addProperty("id", rules.colors().id().id());
        colors.addProperty("pack", rules.colors().pack().value());
        colors.add("overrides", CanonicalJson.map(rules.colors().overrides(), CanonicalJson::argb));
        root.add("colors", colors);

        CanonicalJson.put(root, "use_glint", rules.useGlint(), JsonPrimitive::new);
        return root;
    }

    /**
     * Returns one CIT rule. {@code filename} is derived but emitted anyway - it is the comparator's
     * tie-break, so it is part of why the list is in the order it is in.
     * <p>
     * {@code nbt_rules} is the awkward case: a List whose order is NOT semantic. It is appended while
     * iterating a properties key set, and the rules are ANDed at match time, so the order is both
     * arbitrary and meaningless - sorted by content here rather than emitted verbatim.
     *
     * @param rule the rule to emit
     * @return the rule object
     */
    private static @NotNull JsonObject citRule(@NotNull CitRule rule) {
        JsonObject root = new JsonObject();
        root.addProperty("id", rule.id().id());
        root.addProperty("pack", rule.pack().value());
        root.addProperty("type", rule.type().name());
        root.addProperty("hand", rule.hand().name());
        root.addProperty("weight", rule.weight());
        root.addProperty("filename", rule.filename());
        root.add("items", CanonicalJson.ordered(rule.items(), id -> new JsonPrimitive(id.id())));
        root.add("nbt_rules", CanonicalJson.sortedByContent(rule.nbtRules(), PipelineParityDump::nbtRule));
        root.add("output", citOutput(rule.output()));
        CanonicalJson.put(root, "stack_size", rule.stackSize(), PipelineParityDump::intRanges);
        CanonicalJson.put(root, "damage", rule.damage(), damage -> {
            JsonObject entry = new JsonObject();
            entry.add("ranges", intRanges(damage.ranges()));
            entry.addProperty("percent", damage.percent());
            entry.addProperty("mask", damage.mask());
            return entry;
        });
        CanonicalJson.put(root, "enchantments", rule.enchantments(), enchantments -> {
            JsonObject entry = new JsonObject();
            entry.add("ids", CanonicalJson.ordered(enchantments.ids(), id -> new JsonPrimitive(id.id())));
            CanonicalJson.put(entry, "levels", enchantments.levels(), PipelineParityDump::intRanges);
            return entry;
        });
        return root;
    }

    /**
     * Returns a CIT rule's output.
     *
     * @param output the output to emit
     * @return the output object
     */
    private static @NotNull JsonObject citOutput(@NotNull CitOutput output) {
        JsonObject root = new JsonObject();
        root.add("sub_textures", CanonicalJson.map(output.subTextures(), id -> new JsonPrimitive(id.id())));
        root.add("sub_models", CanonicalJson.map(output.subModels(), id -> new JsonPrimitive(id.id())));
        CanonicalJson.put(root, "texture", output.texture(), id -> new JsonPrimitive(id.id()));
        CanonicalJson.put(root, "model", output.model(), id -> new JsonPrimitive(id.id()));
        return root;
    }

    /**
     * Returns one NBT rule.
     *
     * @param rule the rule to emit
     * @return the rule object
     */
    private static @NotNull JsonObject nbtRule(@NotNull NbtRule rule) {
        JsonObject root = new JsonObject();
        root.addProperty("negated", rule.negated());
        root.add("path", CanonicalJson.ordered(rule.path().steps(), PipelineParityDump::nbtStep));
        root.add("predicate", nbtPredicate(rule.predicate()));
        return root;
    }

    /**
     * Returns one NBT path step as a tagged record.
     *
     * @param step the step to emit
     * @return the step object
     */
    private static @NotNull JsonObject nbtStep(NbtPath.@NotNull Step step) {
        JsonObject root = new JsonObject();
        switch (step) {
            case NbtPath.Key key -> {
                root.addProperty("step", "key");
                root.addProperty("name", key.name());
            }
            case NbtPath.Index index -> {
                root.addProperty("step", "index");
                root.addProperty("value", index.value());
            }
            case NbtPath.Wildcard ignored -> root.addProperty("step", "wildcard");
            case NbtPath.Count ignored -> root.addProperty("step", "count");
        }
        return root;
    }

    /**
     * Returns one NBT predicate as a tagged record.
     * <p>
     * Patterns carry their FLAGS as well as their source text. {@code Pattern.pattern()} returns only the
     * text, so a case-sensitive and a case-insensitive rule compiled from the same source would dump
     * identically - a rule change the gate would sail straight past. The glob form is already the
     * translated regex rather than the authored glob, which is stable and is what the matcher runs.
     *
     * @param predicate the predicate to emit
     * @return the predicate object
     */
    private static @NotNull JsonObject nbtPredicate(@NotNull NbtPredicate predicate) {
        JsonObject root = new JsonObject();
        switch (predicate) {
            case NbtPredicate.Exact exact -> {
                root.addProperty("predicate", "exact");
                root.addProperty("literal", NbtLiteralText.snbt(exact.literal()));
            }
            case NbtPredicate.Glob glob -> {
                root.addProperty("predicate", "glob");
                root.addProperty("pattern", glob.pattern().pattern());
                root.addProperty("flags", glob.pattern().flags());
            }
            case NbtPredicate.Regex regex -> {
                root.addProperty("predicate", "regex");
                root.addProperty("pattern", regex.pattern().pattern());
                root.addProperty("flags", regex.pattern().flags());
            }
            case NbtPredicate.Range range -> {
                root.addProperty("predicate", "range");
                root.add("ranges", intRanges(range.ranges()));
            }
            case NbtPredicate.Exists exists -> {
                root.addProperty("predicate", "exists");
                root.addProperty("expected", exists.expected());
            }
            case NbtPredicate.Raw raw -> {
                root.addProperty("predicate", "raw");
                root.add("inner", nbtPredicate(raw.inner()));
            }
        }
        return root;
    }

    /**
     * Returns an integer range set. The open bounds are the raw {@code Integer} extremes the parser
     * stores, emitted as-is: they are loaded state, and mapping them to null would make an authored
     * extreme indistinguishable from an open bound.
     *
     * @param ranges the ranges to emit
     * @return the ranges array
     */
    private static @NotNull JsonArray intRanges(@NotNull IntRanges ranges) {
        return CanonicalJson.ordered(ranges.entries(), range -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("min", range.min());
            entry.addProperty("max", range.max());
            return entry;
        });
    }

    /**
     * Returns one CTM rule.
     * <p>
     * {@code faces} is an {@code EnumSet}, so it iterates in ORDINAL order - already deterministic, and
     * NOT to be sorted as strings: that would reorder it into something the runtime never produces.
     * {@code tint_index} carries {@code -1} for unset, which is loaded state and is emitted.
     *
     * @param rule the rule to emit
     * @return the rule object
     */
    private static @NotNull JsonObject ctmRule(@NotNull CtmRule rule) {
        JsonObject root = new JsonObject();
        root.addProperty("id", rule.id().id());
        root.addProperty("pack", rule.pack().value());
        root.addProperty("method", rule.method().name());
        root.addProperty("weight", rule.weight());
        root.addProperty("filename", rule.filename());
        root.addProperty("tile_target", rule.isTileTarget());
        root.add("faces", CanonicalJson.ordered(rule.faces(), face -> new JsonPrimitive(face.name())));
        root.add("tiles", CanonicalJson.ordered(rule.tiles(), PipelineParityDump::tileRef));
        root.add("target", ctmTarget(rule.target()));
        root.add("stored", ctmExtras(rule.stored()));
        return root;
    }

    /**
     * Returns a CTM target as a tagged record.
     *
     * @param target the target to emit
     * @return the target object
     */
    private static @NotNull JsonObject ctmTarget(@NotNull CtmTarget target) {
        JsonObject root = new JsonObject();
        switch (target) {
            case CtmTarget.Tiles tiles -> {
                root.addProperty("target", "tiles");
                root.add("names", CanonicalJson.ordered(tiles.names(), JsonPrimitive::new));
            }
            case CtmTarget.Blocks blocks -> {
                root.addProperty("target", "blocks");
                root.add("blocks", CanonicalJson.ordered(blocks.blocks(), PipelineParityDump::blockMatch));
            }
        }
        return root;
    }

    /**
     * Returns one CTM block match.
     *
     * @param match the match to emit
     * @return the match object
     */
    private static @NotNull JsonObject blockMatch(@NotNull BlockMatch match) {
        JsonObject root = new JsonObject();
        root.addProperty("block", match.block().id());
        root.add("properties", CanonicalJson.map(match.properties(),
            values -> CanonicalJson.ordered(values, JsonPrimitive::new)));
        return root;
    }

    /**
     * Returns one tile reference as a tagged record.
     *
     * @param tile the tile to emit
     * @return the tile object
     */
    private static @NotNull JsonObject tileRef(@NotNull TileRef tile) {
        JsonObject root = new JsonObject();
        switch (tile) {
            case TileRef.Texture texture -> {
                root.addProperty("tile", "texture");
                root.addProperty("id", texture.id().id());
            }
            case TileRef.Skip ignored -> root.addProperty("tile", "skip");
            case TileRef.Default ignored -> root.addProperty("tile", "default");
        }
        return root;
    }

    /**
     * Returns a CTM rule's stored extras. {@code tint_index} {@code -1} and {@code width}/{@code height}
     * {@code 0} are all unset sentinels held as loaded state, so all three are emitted.
     *
     * @param extras the extras to emit
     * @return the extras object
     */
    private static @NotNull JsonObject ctmExtras(@NotNull CtmExtras extras) {
        JsonObject root = new JsonObject();
        root.addProperty("inner_seams", extras.innerSeams());
        root.addProperty("tint_index", extras.tintIndex());
        root.addProperty("width", extras.width());
        root.addProperty("height", extras.height());
        root.add("compact_replacements", CanonicalJson.map(
            extras.compactReplacements(), String::valueOf, JsonPrimitive::new));
        CanonicalJson.put(root, "tint_block", extras.tintBlock(), JsonPrimitive::new);
        CanonicalJson.put(root, "layer", extras.layer(), JsonPrimitive::new);
        return root;
    }

    /**
     * Returns the texture-synthesis section - the paletted-permutation SOURCES.
     * <p>
     * Sources, deliberately, and not the synthesizer's registry: the registry has no accessor, and it is
     * a derived cartesian expansion of these (one row per texture per permutation, ids namespace-
     * normalized, collisions resolved last-write-wins). Reproducing it here would mean copying that
     * expansion into the dump, where it could drift from the real one silently. These are the exact
     * inputs production hands the synthesizer, obtained by the exact expression production uses.
     * <p>
     * The list is sorted BY CONTENT rather than emitted verbatim, and that is load-bearing: it is
     * concatenated while iterating a namespace set that is built sorted and then rebuilt through
     * {@code Set.copyOf}, whose iteration order is randomized per JVM run. Its ORDER therefore flaps
     * between two runs of the same commit whenever a pack ships more than one namespace - and both
     * fixture packs do. Sorting by content pins the multiset, which is what the gate can honestly claim.
     * <p>
     * What that sort deliberately does NOT paper over: the same flapping order decides the registry's
     * last-write-wins collisions, so two sources colliding across namespaces can still resolve to
     * different sprites run-to-run in PRODUCTION. That is a real defect and it is tracked as such; it is
     * simply not one a serializer can fix, and pretending the dump covers it would be worse than saying
     * plainly that it does not.
     *
     * @param stack the resolved pack stack
     * @return the synthesis section
     */
    private static @NotNull JsonObject synthesis(@NotNull PackStack stack) {
        JsonObject root = new JsonObject();
        root.add("sources", CanonicalJson.sortedByContent(PalettedPermutationLoader.load(stack), source -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("palette_key", source.paletteKey());
            entry.add("permutations", CanonicalJson.map(source.permutations(), JsonPrimitive::new));
            entry.add("textures", CanonicalJson.ordered(source.textures(), JsonPrimitive::new));
            return entry;
        }));
        return root;
    }

    /**
     * Returns the item section, keyed by item id. Enumerated through {@code knownItemIds} for the same
     * reason blocks are: the index itself is private. That list is complete but its order is a
     * case-insensitive sort whose ties fall through to hash order, so it is re-keyed by plain id here.
     *
     * @param context the loaded renderer context
     * @return the items section
     */
    private static @NotNull JsonObject items(@NotNull PipelineRendererContext context) {
        JsonObject root = new JsonObject();
        for (String id : context.knownItemIds())
            root.add(id, context.findItem(id).map(PipelineParityDump::item).orElseThrow(
                () -> new IllegalStateException("knownItemIds offered '" + id + "' but findItem could not "
                    + "resolve it - the index and its key source disagree")));
        return root;
    }

    /**
     * Returns one item. {@code model} is a content digest joining to {@code item-models.json}, for the
     * same reason a block's is: the loaded model is shared by reference and carries no id.
     * <p>
     * {@code tints} is ORDERED - a tint's position IS its layer index, so sorting would reassign every
     * layer.
     *
     * @param item the item to emit
     * @return the item object
     */
    private static @NotNull JsonObject item(@NotNull Item item) {
        JsonObject root = new JsonObject();
        root.addProperty("id", item.id().id());
        root.addProperty("model", CanonicalJson.digest(model(item.model())));
        root.add("textures", CanonicalJson.map(item.textures(), JsonPrimitive::new));
        root.addProperty("max_durability", item.maxDurability());
        root.addProperty("always_glinted", item.alwaysGlinted());
        root.add("tints", CanonicalJson.ordered(item.tints(), PipelineParityDump::tint));
        return root;
    }

    /**
     * Returns one layer tint as a tagged record. Every case carries an ARGB colour, alpha-forced opaque
     * at parse.
     *
     * @param tint the tint to emit
     * @return the tint object
     */
    private static @NotNull JsonObject tint(@NotNull Item.LayerTint tint) {
        JsonObject root = new JsonObject();
        switch (tint) {
            case Item.LayerTint.Dye dye -> {
                root.addProperty("tint", "dye");
                root.add("default_color", CanonicalJson.argb(dye.defaultColor()));
            }
            case Item.LayerTint.Potion potion -> {
                root.addProperty("tint", "potion");
                root.add("default_color", CanonicalJson.argb(potion.defaultColor()));
            }
            case Item.LayerTint.Firework firework -> {
                root.addProperty("tint", "firework");
                root.add("default_color", CanonicalJson.argb(firework.defaultColor()));
            }
            case Item.LayerTint.Constant constant -> {
                root.addProperty("tint", "constant");
                root.add("argb", CanonicalJson.argb(constant.argb()));
            }
        }
        return root;
    }

    /**
     * Returns one item-model dispatch node, recursively.
     * <p>
     * The switch is exhaustive over the sealed interface with no default, so a new node type is a
     * compile error here rather than a node silently serialized as {@code {}}.
     * <p>
     * Nothing in this tree is {@code Optional}: an absent branch is the {@code Empty} singleton and an
     * absent string is {@code ""}. Both are loaded state and are emitted, never omitted.
     *
     * @param node the node to emit
     * @return the node object
     */
    private static @NotNull JsonObject node(@NotNull ItemModelNode node) {
        JsonObject root = new JsonObject();
        switch (node) {
            case ItemModelNode.Model model -> {
                root.addProperty("node", "model");
                root.addProperty("model", model.model());
                root.add("tints", CanonicalJson.ordered(model.tints(), PipelineParityDump::tint));
            }
            case ItemModelNode.Condition condition -> {
                root.addProperty("node", "condition");
                root.addProperty("property", condition.property());
                root.addProperty("component", condition.component());
                root.add("on_true", node(condition.onTrue()));
                root.add("on_false", node(condition.onFalse()));
            }
            case ItemModelNode.Select select -> {
                root.addProperty("node", "select");
                root.addProperty("property", select.property());
                root.addProperty("block_state_property", select.blockStateProperty());
                root.add("fallback", node(select.fallback()));
                root.add("cases", CanonicalJson.ordered(ItemNodeAccess.cases(select), entry -> {
                    JsonObject item = new JsonObject();
                    item.add("when", CanonicalJson.ordered(entry.when(), JsonPrimitive::new));
                    item.add("model", node(entry.model()));
                    return item;
                }));
            }
            case ItemModelNode.RangeDispatch dispatch -> {
                root.addProperty("node", "range_dispatch");
                root.addProperty("property", dispatch.property());
                root.addProperty("target", dispatch.target());
                root.add("scale", CanonicalJson.number(dispatch.scale()));
                root.add("fallback", node(dispatch.fallback()));
                root.add("entries", CanonicalJson.ordered(ItemNodeAccess.entries(dispatch), entry -> {
                    JsonObject item = new JsonObject();
                    item.add("threshold", CanonicalJson.number(entry.threshold()));
                    item.add("model", node(entry.model()));
                    return item;
                }));
            }
            case ItemModelNode.Composite composite -> {
                root.addProperty("node", "composite");
                root.add("models", CanonicalJson.ordered(composite.models(), PipelineParityDump::node));
            }
            case ItemModelNode.Special special -> {
                root.addProperty("node", "special");
                root.addProperty("kind", special.kind());
                root.addProperty("base", special.base());
                root.add("transform", specialTransform(special.transform()));
                // A Map.copyOf, so its iteration order is salt-randomized per JVM run. Values are
                // Strings, not JsonElements: the parser coerced every primitive through getAsString and
                // dropped the rest, so a JSON 0.5 is loaded as "0.5". Re-inferring a number here would
                // fabricate type information the pipeline threw away.
                root.add("fields", CanonicalJson.map(special.fields(), JsonPrimitive::new));
            }
            case ItemModelNode.Bundle ignored -> root.addProperty("node", "bundle");
            case ItemModelNode.Empty ignored -> root.addProperty("node", "empty");
        }
        return root;
    }

    /**
     * Returns a special-model transform. The arrays are positional, and their lengths are NOT validated
     * at parse (they are sized from whatever the source array held), so they are emitted by length
     * rather than assumed.
     *
     * @param transform the transform to emit
     * @return the transform object
     */
    private static @NotNull JsonObject specialTransform(@NotNull SpecialTransform transform) {
        JsonObject root = new JsonObject();
        root.add("left_rotation", floats(transform.leftRotation()));
        root.add("right_rotation", floats(transform.rightRotation()));
        root.add("scale", floats(transform.scale()));
        root.add("translation", floats(transform.translation()));
        return root;
    }

    /**
     * Returns one entity.
     * <p>
     * The index is 90 rows keyed by plain family id. No option axis is id-encoded, so no
     * {@code <id>_<option>} pseudo-id exists and no row is a rollup of another; the axes below resolve
     * at render instead.
     *
     * @param entity the entity to emit
     * @return the entity object
     */
    private static @NotNull JsonObject entity(@NotNull Entity entity) {
        JsonObject root = new JsonObject();
        root.addProperty("id", entity.id().id());
        root.add("model", entityModel(entity.model()));
        root.add("base_tint", CanonicalJson.argb(entity.baseTintArgb()));
        root.add("setup_yaw_addend", CanonicalJson.number(entity.setupYawAddend()));
        root.add("renderer_scale", CanonicalJson.number(entity.rendererScale()));
        root.add("overlays", CanonicalJson.ordered(entity.overlays(), PipelineParityDump::overlay));
        root.add("block_overlays", CanonicalJson.ordered(entity.blockOverlays(), PipelineParityDump::blockOverlay));
        root.add("layers", layers(entity.layers()));
        root.add("axes", axes(entity.axes()));
        root.add("bone_toggles", CanonicalJson.map(entity.boneToggles(), PipelineParityDump::boneToggle));
        CanonicalJson.put(root, "texture_ref", entity.textureRef(), JsonPrimitive::new);
        return root;
    }

    /**
     * Returns an entity's orthogonal option axes.
     * <p>
     * {@code variants} is a {@code Map.copyOf}, whose iteration order is randomized per JVM run from a
     * nanosecond-seeded salt - it is the one genuinely nondeterministic collection in this section, and
     * sorting it is what keeps the gate from flapping between two runs of the same commit. It is also
     * recursive in type but terminates at depth one: every variant row is built with empty axes.
     *
     * @param axes the axes to emit
     * @return the axes object
     */
    private static @NotNull JsonObject axes(@NotNull Entity.Axes axes) {
        JsonObject root = new JsonObject();
        root.add("state_textures", CanonicalJson.map(axes.stateTextures(), JsonPrimitive::new));
        root.add("size_models", CanonicalJson.map(axes.sizeModels(), Enum::name, PipelineParityDump::entityModel));
        root.add("size_scales", CanonicalJson.map(axes.sizeScales(), Enum::name, scale -> CanonicalJson.number(scale)));
        root.add("variants", CanonicalJson.map(axes.variants(), PipelineParityDump::entity));
        CanonicalJson.put(root, "baby_model", axes.babyModel(), PipelineParityDump::entityModel);
        CanonicalJson.put(root, "large_shape", axes.largeShape(), shape -> {
            JsonObject entry = new JsonObject();
            entry.add("model", entityModel(shape.model()));
            entry.add("overlays", CanonicalJson.ordered(shape.overlays(), PipelineParityDump::overlay));
            CanonicalJson.put(entry, "texture_ref", shape.textureRef(), JsonPrimitive::new);
            return entry;
        });
        return root;
    }

    /**
     * Returns an entity's conditional decoration layers.
     *
     * @param layers the layers to emit
     * @return the layers object
     */
    private static @NotNull JsonObject layers(@NotNull Entity.Layers layers) {
        JsonObject root = new JsonObject();
        root.addProperty("markings", layers.markings());
        root.addProperty("humanoid_armor", layers.humanoidArmor());
        root.add("equipment", CanonicalJson.ordered(layers.equipment(), equipment -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", equipment.slot());
            entry.add("model", entityModel(equipment.model()));
            entry.addProperty("texture_template", equipment.textureTemplate());
            entry.addProperty("default_material", equipment.defaultMaterial());
            return entry;
        }));
        CanonicalJson.put(root, "collar", layers.collar(), JsonPrimitive::new);
        return root;
    }

    /**
     * Returns one texture overlay. The overlay list is ORDERED - it is paint order.
     *
     * @param overlay the overlay to emit
     * @return the overlay object
     */
    private static @NotNull JsonObject overlay(@NotNull Entity.OverlayLayer overlay) {
        JsonObject root = new JsonObject();
        root.add("model", entityModel(overlay.model()));
        root.addProperty("emissive", overlay.emissive());
        root.add("tint", CanonicalJson.argb(overlay.tintArgb()));
        root.addProperty("skip_bounds", overlay.skipBounds());
        root.addProperty("blend", overlay.blend().name());
        root.add("alpha", CanonicalJson.number(overlay.alpha()));
        CanonicalJson.put(root, "texture_ref", overlay.textureRef(), JsonPrimitive::new);
        CanonicalJson.put(root, "tint_by", overlay.tintBy(), JsonPrimitive::new);
        CanonicalJson.put(root, "texture_by", overlay.textureBy(), JsonPrimitive::new);
        CanonicalJson.put(root, "gate", overlay.gate(), PipelineParityDump::gate);
        return root;
    }

    /**
     * Returns one block overlay - a block mesh carried by the entity, with its transform chain.
     * {@code attached_bone} is {@code @Nullable}, not {@code Optional}, so it is null-checked.
     *
     * @param overlay the overlay to emit
     * @return the block-overlay object
     */
    private static @NotNull JsonObject blockOverlay(@NotNull Entity.BlockOverlayLayer overlay) {
        JsonObject root = new JsonObject();
        root.addProperty("block_id", overlay.blockId());
        root.addProperty("selectable", overlay.selectable());
        root.add("transforms", CanonicalJson.ordered(overlay.transforms(), PipelineParityDump::transformOp));
        if (overlay.attachedBone() != null) root.addProperty("attached_bone", overlay.attachedBone());
        return root;
    }

    /**
     * Returns one transform op as a tagged record.
     * <p>
     * The ops are emitted rather than folded into the matrix they compose. A matrix probe would bake the
     * degrees-to-radians rounding into the gate, let two different chains alias onto one result, and hide
     * WHICH op moved when one did. Every op is a record with public components, so there is nothing
     * behavioural to lose by emitting them directly.
     *
     * @param op the op to emit
     * @return the op object
     */
    private static @NotNull JsonObject transformOp(@NotNull Entity.TransformOp op) {
        JsonObject root = new JsonObject();
        switch (op) {
            case Entity.TransformOp.Translate translate -> {
                root.addProperty("op", "translate");
                root.add("x", CanonicalJson.number(translate.x()));
                root.add("y", CanonicalJson.number(translate.y()));
                root.add("z", CanonicalJson.number(translate.z()));
            }
            case Entity.TransformOp.Scale scale -> {
                root.addProperty("op", "scale");
                root.add("x", CanonicalJson.number(scale.x()));
                root.add("y", CanonicalJson.number(scale.y()));
                root.add("z", CanonicalJson.number(scale.z()));
            }
            case Entity.TransformOp.RotateX rotate -> {
                root.addProperty("op", "rotate_x");
                root.add("degrees", CanonicalJson.number(rotate.degrees()));
            }
            case Entity.TransformOp.RotateY rotate -> {
                root.addProperty("op", "rotate_y");
                root.add("degrees", CanonicalJson.number(rotate.degrees()));
            }
            case Entity.TransformOp.RotateZ rotate -> {
                root.addProperty("op", "rotate_z");
                root.add("degrees", CanonicalJson.number(rotate.degrees()));
            }
        }
        return root;
    }

    /**
     * Returns an appearance gate as a tagged record. All seven cases are handled although the entity
     * reader can only produce three - the switch is exhaustive over the sealed interface, so a new case
     * is a compile error here rather than a silent hole in the dump.
     *
     * @param gate the gate to emit
     * @return the gate object
     */
    private static @NotNull JsonObject gate(@NotNull AppearanceGate gate) {
        JsonObject root = new JsonObject();
        switch (gate) {
            case AppearanceGate.StateGate state -> {
                root.addProperty("gate", "state");
                root.addProperty("value", state.value());
            }
            case AppearanceGate.FlagGate flag -> {
                root.addProperty("gate", "flag");
                root.addProperty("flag", flag.flag());
                root.addProperty("value", flag.value());
            }
            case AppearanceGate.ChargedGate ignored -> root.addProperty("gate", "charged");
            case AppearanceGate.TintedGate tinted -> {
                root.addProperty("gate", "tinted");
                root.addProperty("tint_by", tinted.tintBy());
            }
            case AppearanceGate.EquipmentGate equipment -> {
                root.addProperty("gate", "equipment");
                root.addProperty("slot", equipment.slot());
            }
            case AppearanceGate.MarkingsGate ignored -> root.addProperty("gate", "markings");
            case AppearanceGate.CollarColorGate ignored -> root.addProperty("gate", "collar_color");
        }
        return root;
    }

    /**
     * Returns one bone toggle.
     * <p>
     * Its {@code bones} map is emitted as an ARRAY because its order is semantic, which is not obvious:
     * the map is only ever read by key on the HIDE path, but the REVEAL path appends it wholesale into
     * the mesh's own bone map, and that map's sequence is the coplanar depth tie-break. Sorting here
     * would let a tooling change that reordered the source array pass the gate while changing which face
     * wins at tied depth in production.
     *
     * @param toggle the toggle to emit
     * @return the toggle object
     */
    private static @NotNull JsonObject boneToggle(@NotNull Entity.BoneToggle toggle) {
        JsonObject root = new JsonObject();
        root.addProperty("default_visible", toggle.defaultVisible());
        root.add("bones", CanonicalJson.orderedMap(toggle.bones(), "name", PipelineParityDump::bone));
        return root;
    }

    /**
     * Returns the block-entity section: the loaded models plus their state-conditional variants.
     *
     * @param loaded the block-entity load result
     * @return the block-entities section
     */
    private static @NotNull JsonObject blockEntities(@NotNull BlockModelLoader.LoadResult loaded) {
        JsonObject root = new JsonObject();
        root.add("models", CanonicalJson.map(loaded.models(), PipelineParityDump::blockEntity));
        root.add("variants", CanonicalJson.map(loaded.variants(),
            variants -> CanonicalJson.map(variants, PipelineParityDump::variant)));
        return root;
    }

    /**
     * Returns one block entity. {@code parts} is ORDERED - the list is filled from the source array and
     * walked in order at render.
     *
     * @param entity the block entity to emit
     * @return the block-entity object
     */
    private static @NotNull JsonObject blockEntity(@NotNull Block.Entity entity) {
        JsonObject root = new JsonObject();
        root.add("bone_model", boneModel(entity.boneModel()));
        root.addProperty("texture_id", entity.textureId());
        root.add("tint", CanonicalJson.argb(entity.tintArgb()));
        root.addProperty("icon_rotation", entity.iconRotation());
        root.addProperty("additive", entity.additive());
        root.add("parts", CanonicalJson.ordered(entity.parts(), part -> {
            JsonObject item = new JsonObject();
            item.add("bone_model", boneModel(part.boneModel()));
            item.addProperty("texture", part.texture());
            item.add("offset", floats(part.offset()));
            return item;
        }));
        return root;
    }

    /**
     * Returns a block-entity bone model. {@code inventory_transform} is a {@code @Nullable} array of
     * VARIABLE length (the trailing scale slot is optional), not an {@code Optional} - it is omitted
     * when null. Its sibling {@code inventoryYRotation} here is the LIVE block-path field, distinct
     * from the identically-named one on the entity mesh.
     * <p>
     * {@code presentation()} is deliberately absent: it is computed from the fields above, so dumping it
     * would double-count them and could mask a diff behind its own arithmetic.
     *
     * @param model the bone model to emit
     * @return the bone-model object
     */
    private static @NotNull JsonObject boneModel(@NotNull Block.Entity.BoneModel model) {
        JsonObject root = new JsonObject();
        root.add("model", entityModel(model.model()));
        root.addProperty("source_y_up", model.sourceYUp());
        root.add("inventory_y_rotation", CanonicalJson.number(model.inventoryYRotation()));
        root.addProperty("entity_flip", model.entityFlip());
        root.addProperty("tinted", model.tinted());
        if (model.inventoryTransform() != null) root.add("inventory_transform", floats(model.inventoryTransform()));
        return root;
    }

    /**
     * Returns an entity mesh.
     * <p>
     * {@code bones} is emitted as an ARRAY, not an object: the map is a {@code ConcurrentLinkedMap}
     * declared as its concrete class, so Gson builds it and JSON author order survives to runtime - and
     * that order is what assigns render priority and decides which face wins at tied depth. An object
     * would be key-sorted by the writer and the order would be gone.
     * <p>
     * {@code inventory_y_rotation} is emitted although it is {@code 0f} for every entity shipped today.
     * It is live-read (folded into the camera yaw at render), so a tooling change that started emitting
     * it would silently rotate every entity - exactly what this gate exists to catch.
     *
     * @param model the mesh to emit
     * @return the mesh object
     */
    private static @NotNull JsonObject entityModel(@NotNull EntityModelData model) {
        JsonObject root = new JsonObject();
        root.add("texture_size", size(model.getTextureWidth(), model.getTextureHeight()));
        root.add("inventory_y_rotation", CanonicalJson.number(model.getInventoryYRotation()));
        root.addProperty("cull", model.isCull());
        root.add("bones", CanonicalJson.orderedMap(model.getBones(), "name", PipelineParityDump::bone));
        return root;
    }

    /**
     * Returns one bone. {@code cubes} is ORDERED (declared order); {@code parent} is {@code @Nullable}
     * (null at a root bone) and is omitted when absent.
     *
     * @param bone the bone to emit
     * @return the bone object
     */
    private static @NotNull JsonObject bone(@NotNull EntityModelData.Bone bone) {
        JsonObject root = new JsonObject();
        root.add("pivot", vector(bone.getPivot()));
        root.add("rotation", euler(bone.getRotation()));
        root.add("bind_pose_rotation", euler(bone.getBindPoseRotation()));
        root.add("scale", CanonicalJson.number(bone.getScale()));
        root.add("cubes", CanonicalJson.ordered(bone.getCubes(), PipelineParityDump::cube));
        if (bone.getParent() != null) root.addProperty("parent", bone.getParent());
        return root;
    }

    /**
     * Returns one cube.
     * <p>
     * Cube expansion is emitted ONCE, as {@code grow}, sourced from the derived {@code getGrow()} rather
     * than from the {@code inflate} + {@code growAxis} pair that backs it. That is deliberate: a later
     * phase collapses the pair into a single stored vector, and {@code getGrow()} is a pure, total
     * function of exactly those two fields with no arithmetic in either branch (a per-axis vector when
     * present, otherwise the scalar broadcast), so the stored field reproduces these bytes exactly and
     * that phase diffs empty. Dumping the backing pair instead would break the moment it is deleted.
     *
     * @param cube the cube to emit
     * @return the cube object
     */
    private static @NotNull JsonObject cube(@NotNull EntityModelData.Cube cube) {
        JsonObject root = new JsonObject();
        root.add("origin", vector(cube.getOrigin()));
        root.add("size", vector(cube.getSize()));
        root.add("uv", vector(cube.getUv()));
        root.add("grow", vector(cube.getGrow()));
        root.addProperty("mirror", cube.isMirror());
        root.add("pivot", vector(cube.getPivot()));
        root.add("rotation", euler(cube.getRotation()));
        root.add("face_uv", CanonicalJson.map(cube.getFaceUv(), face -> {
            JsonObject entry = new JsonObject();
            entry.add("uv", vector(face.getUv()));
            entry.add("uv_size", vector(face.getUvSize()));
            return entry;
        }));
        return root;
    }

    /**
     * Returns a two-component vector.
     *
     * @param vector the vector to emit
     * @return the vector array
     */
    private static @NotNull JsonArray vector(@NotNull Vector2f vector) {
        JsonArray root = new JsonArray(2);
        root.add(CanonicalJson.number(vector.x()));
        root.add(CanonicalJson.number(vector.y()));
        return root;
    }

    /**
     * Returns a three-component vector.
     *
     * @param vector the vector to emit
     * @return the vector array
     */
    private static @NotNull JsonArray vector(@NotNull Vector3f vector) {
        return triple(vector.x(), vector.y(), vector.z());
    }

    /**
     * Returns a size as {@code [width, height]}.
     *
     * @param width the width
     * @param height the height
     * @return the size array
     */
    private static @NotNull JsonArray size(int width, int height) {
        JsonArray root = new JsonArray(2);
        root.add(width);
        root.add(height);
        return root;
    }

    /**
     * Returns a model section - the fully parent-resolved models, keyed by model id.
     * <p>
     * Resolved-only is forced rather than chosen: the parent chain is folded at the JSON level and the
     * merged object is what gets bound, so the loaded {@code ModelData} carries no parent id to dump.
     * <p>
     * Each model carries its own {@code digest} - the content id that {@code blocks.json} refers to it
     * by instead of inlining it once per owning block. It is what turns an opaque hash over there into
     * a grep away from the model that changed.
     *
     * @param models the resolved model index
     * @return the model section
     */
    private static @NotNull JsonObject models(@NotNull Map<String, ModelData> models) {
        return CanonicalJson.map(models, model -> {
            JsonObject entry = model(model);
            entry.addProperty("digest", CanonicalJson.digest(entry));
            return entry;
        });
    }

    /**
     * Returns one resolved model.
     * <p>
     * {@code gui_light_3d} is dumped despite being provably constant {@code false} - the field can never
     * bind (the JSON key is {@code gui_light} carrying {@code "front"}/{@code "side"}, and Gson is on
     * identity naming here) and nothing reads it. It stays in as a canary: it is the cheapest thing in
     * the dump that would move if a phase changed the Gson naming configuration underneath every DTO.
     *
     * @param model the model to emit
     * @return the model object
     */
    private static @NotNull JsonObject model(@NotNull ModelData model) {
        JsonObject root = new JsonObject();
        root.addProperty("ambient_occlusion", model.isAmbientocclusion());
        root.addProperty("gui_light_3d", model.isGuiLight3D());
        root.add("textures", modelTextures(model));
        root.add("elements", CanonicalJson.ordered(model.getElements(), PipelineParityDump::element));
        root.add("display", CanonicalJson.map(model.getDisplay(), PipelineParityDump::transform));
        return root;
    }

    /**
     * Returns a model's texture slots as {@code slot -> {sprite, force_translucent}}.
     * <p>
     * The loaded form is two maps, not one: the parser flattens every slot to its sprite string in
     * {@code textures}, and parks the 26.1 object form's flags in the sparse {@code textureObjects}
     * side-channel (populated ONLY for slots authored as objects - usually empty on a vanilla stack).
     * They are joined here so the pair reads as one value and the later merge of the two into a single
     * map is a no-op in the dump.
     * <p>
     * An absent {@code textureObjects} entry means the slot was authored in string form, which is
     * {@code force_translucent: false} - not unknown. So the flag is defaulted, never omitted.
     *
     * @param model the model whose textures to emit
     * @return the texture-slot object
     */
    private static @NotNull JsonObject modelTextures(@NotNull ModelData model) {
        Map<String, ModelTexture> objects = model.getTextureObjects();
        JsonObject root = new JsonObject();
        model.getTextures().forEach((slot, sprite) -> {
            ModelTexture object = objects.get(slot);
            JsonObject entry = new JsonObject();
            entry.addProperty("sprite", object == null ? sprite : object.sprite());
            entry.addProperty("force_translucent", object != null && object.forceTranslucent());
            root.add(slot, entry);
        });
        return root;
    }

    /**
     * Returns one model element. The element list is ORDERED (paint priority), but an element's
     * {@code faces} map is not: its {@code newLinkedMap} initializer is dead - the field is declared as
     * the {@code ConcurrentMap} interface, so Gson replaces the value with a hash-backed map and the
     * author order the field's javadoc promises no longer exists at runtime. Sorting it discards
     * nothing the loaded object still holds.
     *
     * @param element the element to emit
     * @return the element object
     */
    private static @NotNull JsonObject element(@NotNull ModelElement element) {
        JsonObject root = new JsonObject();
        root.add("from", floats(element.getFrom()));
        root.add("to", floats(element.getTo()));
        root.addProperty("shade", element.isShade());
        root.addProperty("light_emission", element.getLightEmission());
        root.add("faces", CanonicalJson.map(element.getFaces(), PipelineParityDump::face));
        CanonicalJson.put(root, "rotation", element.getRotation(), rotation -> {
            JsonObject entry = new JsonObject();
            entry.add("origin", floats(rotation.origin()));
            entry.addProperty("axis", rotation.axis());
            entry.add("angle", CanonicalJson.number(rotation.angle()));
            entry.addProperty("rescale", rotation.rescale());
            return entry;
        });
        return root;
    }

    /**
     * Returns one element face. {@code tint_index} is always emitted: its {@code -1} is loaded state
     * meaning untinted, and the dump reserves omission strictly for {@code Optional.empty()}.
     *
     * @param face the face to emit
     * @return the face object
     */
    private static @NotNull JsonObject face(@NotNull ModelFace face) {
        JsonObject root = new JsonObject();
        root.addProperty("texture", face.getTexture());
        root.addProperty("tint_index", face.getTintIndex());
        root.addProperty("rotation", face.getRotation());
        CanonicalJson.put(root, "cullface", face.getCullface(), JsonPrimitive::new);
        CanonicalJson.put(root, "uv", face.getUv(), PipelineParityDump::vector);
        return root;
    }

    /**
     * Returns a display-slot transform. {@code ModelTransform} exposes no array accessor for its
     * translation and scale, only per-component getters, so the triples are rebuilt here.
     *
     * @param transform the transform to emit
     * @return the transform object
     */
    private static @NotNull JsonObject transform(@NotNull ModelTransform transform) {
        JsonObject root = new JsonObject();
        root.add("rotation", euler(transform.getRotation()));
        root.add("translation", triple(transform.getTranslationX(), transform.getTranslationY(), transform.getTranslationZ()));
        root.add("scale", triple(transform.getScaleX(), transform.getScaleY(), transform.getScaleZ()));
        return root;
    }

    /**
     * Returns a Euler rotation with its components NAMED. The source JSON is a bare triple whose index
     * mapping is {@code [pitch, yaw, roll]}; emitting it as an anonymous array would leave a future
     * component-order swap indistinguishable from a value change.
     *
     * @param rotation the rotation to emit
     * @return the rotation object
     */
    private static @NotNull JsonObject euler(@NotNull EulerRotation rotation) {
        JsonObject root = new JsonObject();
        root.add("pitch", CanonicalJson.number(rotation.pitch()));
        root.add("yaw", CanonicalJson.number(rotation.yaw()));
        root.add("roll", CanonicalJson.number(rotation.roll()));
        return root;
    }

    /**
     * Returns a UV rect as {@code [minX, minY, maxX, maxY]}.
     *
     * @param uv the rect to emit
     * @return the rect array
     */
    private static @NotNull JsonArray vector(@NotNull Vector4f uv) {
        JsonArray root = new JsonArray(4);
        root.add(CanonicalJson.number(uv.x()));
        root.add(CanonicalJson.number(uv.y()));
        root.add(CanonicalJson.number(uv.z()));
        root.add(CanonicalJson.number(uv.w()));
        return root;
    }

    /**
     * Returns a float array in canonical form.
     *
     * @param values the array to emit
     * @return the array
     */
    private static @NotNull JsonArray floats(float @NotNull [] values) {
        JsonArray root = new JsonArray(values.length);
        for (float value : values)
            root.add(CanonicalJson.number(value));
        return root;
    }

    /**
     * Returns three components as an array.
     *
     * @param x the first component
     * @param y the second component
     * @param z the third component
     * @return the triple array
     */
    private static @NotNull JsonArray triple(float x, float y, float z) {
        JsonArray root = new JsonArray(3);
        root.add(CanonicalJson.number(x));
        root.add(CanonicalJson.number(y));
        root.add(CanonicalJson.number(z));
        return root;
    }

    /**
     * Writes {@code manifest.sha256} - one digest per section file, sorted, in the repo's established
     * {@code <hex> *<path>} form. It is the at-a-glance equality check; the section files themselves
     * are what make a failure diagnosable.
     *
     * @param directory the configuration's output directory
     * @param sections the section names written
     * @throws IOException if the manifest cannot be written
     */
    private static void manifest(@NotNull Path directory, @NotNull Iterable<String> sections) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String section : sections) {
            Path file = directory.resolve(section + ".json");
            lines.add(CanonicalJson.sha256(file) + " *" + section + ".json");
        }
        lines.sort(String::compareTo);
        Files.writeString(directory.resolve("manifest.sha256"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

}
