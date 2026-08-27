package lib.minecraft.renderer.pipeline.dump;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockStateKey;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.appearance.AppearanceGate;
import lib.minecraft.renderer.asset.appearance.Flag;
import lib.minecraft.renderer.asset.appearance.Size;
import lib.minecraft.renderer.asset.appearance.TextureAxis;
import lib.minecraft.renderer.asset.appearance.TintAxis;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.asset.pack.FormatRange;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.ResolvedTexture;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.item.ItemModelNode;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.asset.pack.item.ItemNodeAccess;
import lib.minecraft.renderer.asset.pack.item.SpecialTransform;
import lib.minecraft.renderer.asset.pack.rule.BlockMatch;
import lib.minecraft.renderer.asset.pack.rule.CitOutput;
import lib.minecraft.renderer.asset.pack.rule.CitRule;
import lib.minecraft.renderer.asset.pack.rule.CtmExtras;
import lib.minecraft.renderer.asset.pack.rule.CtmRule;
import lib.minecraft.renderer.asset.pack.rule.CtmTarget;
import lib.minecraft.renderer.asset.pack.rule.IntRanges;
import lib.minecraft.renderer.asset.pack.rule.NbtLiteralText;
import lib.minecraft.renderer.asset.pack.rule.NbtPath;
import lib.minecraft.renderer.asset.pack.rule.NbtPredicate;
import lib.minecraft.renderer.asset.pack.rule.NbtRule;
import lib.minecraft.renderer.asset.pack.rule.RuleSet;
import lib.minecraft.renderer.asset.pack.rule.TileRef;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PosePredicate;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.parity.CanonicalJson;
import lib.minecraft.renderer.parity.ParityJson;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.BlockDefaultsLoader;
import lib.minecraft.renderer.pipeline.loader.BlockItemsLoader;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pipeline.loader.GlintItemsLoader;
import lib.minecraft.renderer.pipeline.loader.PotionColorLoader;
import lib.minecraft.renderer.pipeline.pack.BannerPatternLoader;
import lib.minecraft.renderer.pipeline.pack.BlockTagLoader;
import lib.minecraft.renderer.pipeline.pack.ColorMapLoader;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import lib.minecraft.renderer.pipeline.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.pipeline.pack.ResolvedModels;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelTreeLoader;
import lib.minecraft.renderer.pipeline.util.BlockRendererOverrides;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <b>Altitude.</b> The dump is taken at renderer-context level, not {@code ClientAcquisition.Result} level.
 * Five loaders ({@code BlockModelLoader}, {@code BlockIndexBuilder}, {@code ItemIndexBuilder},
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
@UtilityClass
public final class PipelineParityDump {

    /** Fixture packs for the second configuration - the only ones that light up CIT/CTM, the {@code .cats} container, and the non-filename pack-id rungs. */
    private static final @NotNull List<String> PACK_FIXTURES = List.of(
        "defrosted", "hypixel-skyblock", "eureka.cats.zip"
    );

    /** The namespace every custom JVM flag in this repository lives under. */
    private static final @NotNull String ASSET_PREFIX = "asset.";

    /** The parity harness's own sub-namespace, which the run header records nothing from. */
    private static final @NotNull String PARITY_PREFIX = "asset.parity.";

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
        dump(ClientOptions.defaults(), root.resolve("vanilla"));

        List<File> fixtures = fixtures();
        if (fixtures.size() < PACK_FIXTURES.size()) {
            System.out.println("SKIPPED packs configuration: missing fixtures under cache/asset-renderer/packs/ "
                + "(want " + PACK_FIXTURES + ", found " + fixtures.stream().map(File::getName).toList() + "). "
                + "The F3/F4/F5/W5 phases have NO evidence without it.");
        } else {
            dump(ClientOptions.builder().texturePacks(Concurrent.adoptList(fixtures)).build(), root.resolve("packs"));
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
    private static void dump(@NotNull ClientOptions options, @NotNull Path directory) throws IOException {
        ClientAssets assets = ClientAcquisition.acquire(options);
        PackStack stack = PackAcquisition.acquire(assets);
        PipelineRendererContext context = PipelineRendererContext.of(assets);
        ResolvedModels resolvedModels = ResolvedModels.load(stack);

        // An empty index does not fail anything downstream - every lookup just returns empty - so without
        // these the dump would write a well-formed, fully-populated-looking artifact of nothing at all,
        // and two of them would compare equal. A gate that passes by loading nothing is worse than no gate.
        if (stack.textureIndex().isEmpty())
            throw new IllegalStateException("texture index is empty - the dump would silently emit {} for every "
                + "texture-derived section; the stack was not built through withTextureIndex()");
        if (context.knownBlockIds().isEmpty() || context.knownItemIds().isEmpty())
            throw new IllegalStateException("block index (" + context.knownBlockIds().size() + ") or item index ("
                + context.knownItemIds().size() + ") is empty - the blocks/items sections and the id_order and "
                + "icon_gui probes would silently emit nothing; BlockIndexBuilder/ItemIndexBuilder produced no rows");

        Path base = Path.of("").toAbsolutePath().normalize();
        Map<String, JsonObject> sections = new LinkedHashMap<>();

        sections.put("run", run(options));
        sections.put("packs", packs(stack, base));
        sections.put("textures", textures(stack));
        sections.put("block-models", models(resolvedModels.blocks()));
        sections.put("item-models", models(resolvedModels.items()));
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
        sections.put("rules", rules(stack.rules()));
        sections.put("synthesis", synthesis(stack));
        sections.put("misc", misc(stack));
        sections.put("probes", probes(context, stack));
        sections.put("trees", CanonicalJson.map(ItemModelTreeLoader.load(stack), tree -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", tree.id().id());
            entry.add("root", node(tree.root()));
            return entry;
        }));

        for (Map.Entry<String, JsonObject> section : sections.entrySet())
            ParityJson.write(directory.resolve(section.getKey() + ".json"), section.getValue());

        manifest(directory, sections.keySet());
    }

    /**
     * Returns the run header - the inputs the dump itself depends on. Without this a capture is not
     * reproducible: the JavaExec fork inherits its {@code asset.*} system properties from the
     * long-lived Gradle daemon rather than from the command line, so two captures with identical
     * command lines can disagree. Recording them makes that disagreement a visible diff.
     *
     * <p>The {@code asset.parity.*} namespace is deliberately excluded. It names where the parity
     * harness reads its references and writes its working root - plumbing this dump does not read -
     * and the build's global forwarder injects it into every fork, so recording it made the dumped
     * bytes a function of which {@code -PparityRoot} the capture happened to use. Measured: five
     * captures into five roots produced five different {@code run.json} digests while all thirteen
     * other sections were identical across all five.
     *
     * @param options the configuration that was loaded
     * @return the run section
     */
    private static @NotNull JsonObject run(@NotNull ClientOptions options) {
        JsonObject root = new JsonObject();
        root.addProperty("version", options.getVersion());
        root.add("texture_packs", CanonicalJson.ordered(
            options.getTexturePacks(), pack -> CanonicalJson.path(pack.toPath(), Path.of("").toAbsolutePath())));

        Properties properties = System.getProperties();
        Map<String, String> assetFlags = new TreeMap<>();
        for (String name : properties.stringPropertyNames())
            if (name.startsWith(ASSET_PREFIX) && !name.startsWith(PARITY_PREFIX))
                assetFlags.put(name, properties.getProperty(name));
        root.add("asset_flags", CanonicalJson.map(assetFlags, JsonPrimitive::new));

        return root;
    }

    /**
     * Returns the pack section - every pack in ascending priority order.
     * <p>
     * The pack list is ORDERED (it is the priority order, and element 0 is always vanilla), so it is
     * emitted verbatim. {@code capabilities} is a {@code Set.copyOf} whose iteration order is randomized
     * per JVM run, and {@code namespaces} is a sorted set; both are (re-)sorted here so the emit never
     * depends on a field's runtime iteration order. {@code primaryNamespace} is deterministic (the
     * natural-order first of that sorted set) but still NOT dumped - redundant with {@code namespaces}
     * and the {@code resolve_in} probe.
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
     * Returns the texture-index section, sourced STRICTLY from {@link PackStack}'s {@code textureIndex}.
     * <p>
     * It is never built by iterating ids through {@code resolve} / {@code resolveIn}: those mutate the
     * stack's ambiguity-logging set as a side effect, so a dump that walked them would be serializing an
     * object it was concurrently changing.
     * <p>
     * {@code path} is the owning-root-relative container path ({@code assets/<ns>/textures/<name>.png}),
     * reconstructed from the row's id: the merged {@code ResolvedTexture} stores the winning
     * root-prefixed entry (which the {@code resolution} probe records), so this section derives the
     * stable root-relative form the id already determines.
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
    private static @NotNull JsonObject texture(@NotNull ResolvedTexture texture) {
        JsonObject root = new JsonObject();
        root.addProperty("pack", texture.pack().value());
        root.addProperty("path", "assets/" + texture.id().namespace() + "/textures/" + texture.id().name() + ".png");
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
        CanonicalJson.put(root, "icon_gui", block.iconGui(), PipelineParityDump::transform);
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
        CanonicalJson.put(root, "constant", tint.constant().map(Color::getRGB), CanonicalJson::argb);
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
        // An unconditional part (Always) omits the "when" key, staying distinguishable from a
        // present-but-empty condition.
        if (!(part.when() instanceof Block.Multipart.When.Always)) root.add("when", condition(part.when()));
        return root;
    }

    /**
     * Returns a multipart {@code when} in canonical PARSED form - a three-case tagged union mirroring
     * {@link Block.Multipart.When}: {@link Block.Multipart.When.All} as {@code and},
     * {@link Block.Multipart.When.Any} as {@code or}, and {@link Block.Multipart.When.Match} as
     * {@code props}. Both the {@code and} and {@code or} branches recurse, so the form nests.
     * <p>
     * The condition is parsed at load, so this emits the loaded structure directly - the renderer's
     * AND-first / OR-second precedence and the {@code getAsString} coercion of a JSON {@code true} to
     * {@code "true"} already happened there. The {@code |} alternatives are pre-split in author order:
     * the match is a membership test so order carries no meaning, but the source is stable JSON and
     * sorting it would discard the signal of a parser that reordered them.
     * <p>
     * {@link Block.Multipart.When.Always} never reaches here - {@link #part} omits the {@code when} key
     * for an unconditional part, keeping it distinguishable from a present-but-empty condition.
     *
     * @param when the parsed condition
     * @return the canonical condition object
     */
    private static @NotNull JsonObject condition(@NotNull Block.Multipart.When when) {
        JsonObject root = new JsonObject();
        switch (when) {
            case Block.Multipart.When.All all ->
                root.add("and", CanonicalJson.ordered(all.terms(), PipelineParityDump::condition));
            case Block.Multipart.When.Any any ->
                root.add("or", CanonicalJson.ordered(any.terms(), PipelineParityDump::condition));
            case Block.Multipart.When.Match match -> {
                JsonObject props = new JsonObject();
                match.required().forEach((property, alternatives) -> {
                    JsonArray array = new JsonArray();
                    alternatives.forEach(array::add);
                    props.add(property, array);
                });
                root.add("props", props);
            }
            case Block.Multipart.When.Always ignored ->
                throw new IllegalStateException("Always is emitted as an absent when, not a condition");
        }
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
     * {@code icon_gui} reads the baked {@link Block#iconGui()} field rather than a resolver method: the
     * interface resolver was deleted in favour of baking the resolved {@code display.gui} transform at
     * index build, and this probe pins that the baked value equals what the resolver produced (the
     * byte-equality that proves the bake reproduces it).
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
            CanonicalJson.put(iconGui, id, context.findBlock(id).orElseThrow().iconGui(),
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
     * A resolved id's namespace is chosen by a within-pack search order that leads with the pack's
     * primary namespace. That lead was once a {@code findFirst} over a per-run-salted set - when two
     * namespaces normalize to the pack's id, which one led flapped between runs - so this probe formerly
     * omitted its samples for such packs. Now that {@code PackAcquisition.namespaces()} returns a sorted
     * set, {@code primaryNamespace} is deterministic and the samples are always emitted; the
     * {@code namespace_candidates} count is retained as a plain diagnostic (how many namespaces normalize
     * to the pack id).
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
    private static @NotNull JsonObject animation(@NotNull MCMeta.Animation animation) {
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
     * Returns the miscellaneous section - the loaded tables each too small to own a file. Each is
     * re-loaded from {@code stack} here (the context exposes no accessor for the raw maps); the loaders
     * are pure functions of the stack, so this reproduces exactly what the context built from.
     *
     * @param stack the resolved pack stack
     * @return the misc section
     */
    private static @NotNull JsonObject misc(@NotNull PackStack stack) {
        JsonObject root = new JsonObject();
        root.add("block_tints", CanonicalJson.map(BlockTintsLoader.load(), PipelineParityDump::tint));
        root.add("potion_effect_colors", CanonicalJson.map(PotionColorLoader.load(), CanonicalJson::argb));
        root.add("glint_items", CanonicalJson.strings(GlintItemsLoader.load()));

        root.add("block_default_state_keys", CanonicalJson.map(BlockDefaultsLoader.load(BlockRendererOverrides.gather(stack)), m -> new JsonPrimitive(BlockStateKey.join(m))));
        root.add("block_item_aliases", CanonicalJson.map(BlockItemsLoader.load(), JsonPrimitive::new));

        ConcurrentMap<String, ItemModelTree> itemTrees = ItemModelTreeLoader.load(stack);
        // Not the parsed item definitions the name suggests, and not every item: the loader keeps an
        // entry only where the tree's root is a plain model AND that model is a block-model ref, so this
        // is the block-item inventory-model projection. A dispatch-rooted item is absent by design.
        root.add("block_item_models", CanonicalJson.map(ItemModelTreeLoader.deriveBlockItemModels(itemTrees), JsonPrimitive::new));

        root.add("block_tags", CanonicalJson.map(BlockTagLoader.load(stack), tag -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", tag.id().id());
            // ORDERED: the loader appends in JSON encounter order, resolving nested #tag refs in place,
            // so the sequence is authored rather than hash-derived.
            entry.add("values", CanonicalJson.ordered(tag.values(), JsonPrimitive::new));
            return entry;
        }));

        root.add("banner_patterns", CanonicalJson.map(BannerPatternLoader.load(stack), pattern -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", pattern.id());
            entry.addProperty("asset_id", pattern.assetId());
            entry.addProperty("translation_key", pattern.translationKey());
            return entry;
        }));

        // ORDERED inner list: a tint's index IS its layer index.
        root.add("item_tints", CanonicalJson.map(ItemModelTreeLoader.deriveTints(itemTrees),
            tints -> CanonicalJson.ordered(tints, PipelineParityDump::tint)));

        // Sourced from the loader rather than probed back out of the context: the colormap decode path
        // applies sRGB gamma where the texture-resolution path does not, so the same PNG yields different
        // bytes through the two, and only these are the ones the renderer actually holds.
        root.add("color_maps", CanonicalJson.map(ColorMapLoader.load(stack), Enum::name, colorMap -> {
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
        CanonicalJson.put(root, "texture_ref", entity.textureRef(), JsonPrimitive::new);
        // Canvas-group membership: emitted only for genuine groups (size > 1) so singleton entities
        // stay byte-identical; the members set has no meaningful order, so sort for a canonical array.
        if (!entity.members().isEmpty()) root.add("members", CanonicalJson.strings(entity.members()));
        // The pose as a digest rather than in full: it is the largest thing an entity carries by a
        // wide margin, and what this section needs of it is whether it moved. Emitted only where
        // there is one, on the same terms as members, so the entities that pose nothing stay
        // byte-identical - which is most of the roster and all of the block-shaped subjects.
        if (!entity.pose().container().isEmpty() || !entity.pose().bones().isEmpty()
            || !entity.pose().clips().isEmpty() || entity.pose().refusal().isPresent())
            root.addProperty("pose", CanonicalJson.digest(pose(entity.pose())));
        return root;
    }

    /**
     * Returns one entity's pose. A refusal is emitted as the reason rather than as an absence, so a
     * model whose pose could not be read is not indistinguishable from one that poses nothing.
     *
     * @param pose the pose to emit
     * @return the pose object
     */
    private static @NotNull JsonObject pose(@NotNull EntityPose pose) {
        JsonObject root = new JsonObject();
        CanonicalJson.put(root, "refusal", pose.refusal(), JsonPrimitive::new);
        root.add("container", CanonicalJson.ordered(pose.container(),
            step -> CanonicalJson.map(step, PoseChannel::token, PipelineParityDump::poseExpr)));
        root.add("bones", CanonicalJson.map(pose.bones(),
            channels -> CanonicalJson.map(channels, PoseChannel::token, PipelineParityDump::poseExpr)));
        root.add("clips", CanonicalJson.ordered(pose.clips(), clip -> {
            JsonObject node = new JsonObject();
            node.addProperty("clip", clip.coordinate());
            node.addProperty("gate", clip.gate().token());
            node.add("args", CanonicalJson.ordered(clip.arguments(), PipelineParityDump::poseExpr));
            return node;
        }));
        return root;
    }

    /**
     * Returns one pose expression, as the text of its own GRAPH.
     *
     * <p>Written as text rather than as structure because this section only ever digests it: what a
     * reader of the dump wants to know is whether the arithmetic moved, and the shipped table is
     * where the arithmetic itself is legible.
     *
     * <p><b>A graph and not a tree, which is the difference between a line of text and no line at
     * all.</b> The table names a sub-expression once and uses it from everywhere that reaches it, and
     * the loader answers every one of those with the same record - so a writer that followed each
     * reference would put back the tree the table exists not to spell out, and a humanoid's arm comes
     * to twenty-two million nodes. A node already written is written as {@code #n} instead, numbered
     * in the order they were reached, which is a function of the expression rather than of the walk
     * that found it.
     *
     * @param expr the expression to emit
     * @return the expression as a string
     */
    private static @NotNull JsonPrimitive poseExpr(@NotNull PoseExpr expr) {
        StringBuilder text = new StringBuilder();
        poseNode(expr, new IdentityHashMap<>(), text);
        return new JsonPrimitive(text.toString());
    }

    /** One expression node, or a back reference to it when the graph has reached it before. */
    private static void poseNode(
        @NotNull PoseExpr expr, @NotNull Map<Object, Integer> written, @NotNull StringBuilder text) {

        if (alreadyWritten(expr, written, text)) return;
        switch (expr) {
            case PoseExpr.Const literal -> text.append(switch (literal.width()) {
                case FLOAT -> "const(" + (float) literal.value();
                case DOUBLE -> "dconst(" + literal.value();
                case INT -> "iconst(" + (int) literal.value();
            }).append(')');
            case PoseExpr.Input input -> text.append("input(").append(input.field()).append(')');
            case PoseExpr.BoneRead read -> text.append("bone(")
                .append(read.bone()).append(',').append(read.channel().token()).append(')');
            case PoseExpr.Op operation -> {
                text.append(operation.operator().token()).append('(');
                for (int index = 0; index < operation.operands().size(); index++) {
                    if (index > 0) text.append(',');
                    poseNode(operation.operands().get(index), written, text);
                }
                text.append(')');
            }
            case PoseExpr.Select select -> {
                text.append("select(");
                poseNode(select.condition(), written, text);
                text.append(',');
                poseNode(select.whenTrue(), written, text);
                text.append(',');
                poseNode(select.whenFalse(), written, text);
                text.append(')');
            }
        }
    }

    /** One condition node, written the same way an expression node is. */
    private static void poseNode(
        @NotNull PosePredicate predicate, @NotNull Map<Object, Integer> written, @NotNull StringBuilder text) {

        if (alreadyWritten(predicate, written, text)) return;
        text.append(predicate.comparison().token()).append('(');
        poseNode(predicate.left(), written, text);
        text.append(',');
        poseNode(predicate.right(), written, text);
        text.append(')');
    }

    /**
     * Whether this node has been written already, writing the back reference to it when it has.
     *
     * <p>Asked by IDENTITY, which is what the loader answers a reference with and is also the only
     * question that can be asked cheaply: these are records, so asking a map about one by value
     * hashes it by walking everything below it - the very tree neither side is willing to build.
     */
    private static boolean alreadyWritten(
        @NotNull Object node, @NotNull Map<Object, Integer> written, @NotNull StringBuilder text) {

        Integer at = written.get(node);
        if (at != null) {
            text.append('#').append(at);
            return true;
        }
        written.put(node, written.size());
        return false;
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
        root.add("state_textures", CanonicalJson.map(axes.state().options(), JsonPrimitive::new));
        CanonicalJson.put(root, "state_default", axes.state().declared(), JsonPrimitive::new);
        // One map where there were two, because a size form carries whichever of the two vanilla
        // mechanisms its subject uses - its own mesh, or the base at a multiplied scale - and the
        // sub-definition already holds both members. The declared size is a form like any other.
        root.add("sizes", CanonicalJson.map(axes.size().options(), Enum::name, PipelineParityDump::entity));
        CanonicalJson.put(root, "size_default", axes.size().declared(), size -> new JsonPrimitive(size.name()));
        root.add("variants", CanonicalJson.map(axes.variant().options(), PipelineParityDump::entity));
        CanonicalJson.put(root, "variant_default", axes.variant().declared(), JsonPrimitive::new);
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
        root.addProperty("humanoid_armor", layers.humanoidArmor().isPresent());
        root.add("equipment", CanonicalJson.ordered(layers.equipment(), equipment -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", equipment.slot());
            entry.add("model", entityModel(equipment.model()));
            entry.addProperty("layer_type", equipment.layerType().getId());
            entry.addProperty("default_material", equipment.defaultMaterial());
            entry.add("material_assets", CanonicalJson.map(equipment.materialAssets(),
                assetId -> new JsonPrimitive(assetId.id())));
            return entry;
        }));
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
        root.addProperty("emissive", overlay.pass().emissive());
        root.add("tint", CanonicalJson.argb(overlay.tintArgb()));
        root.addProperty("skip_bounds", overlay.skipBounds());
        root.addProperty("blend", overlay.pass().blend().name());
        root.add("alpha", CanonicalJson.number(overlay.pass().alpha()));
        CanonicalJson.put(root, "texture_ref", overlay.textureRef(), JsonPrimitive::new);
        CanonicalJson.put(root, "tint_by", overlay.tintBy().map(TintAxis::token), JsonPrimitive::new);
        CanonicalJson.put(root, "texture_by", overlay.textureBy().map(TextureAxis::token), JsonPrimitive::new);
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
        root.add("transform", matrix(overlay.transform()));
        if (overlay.attachedBone() != null) root.addProperty("attached_bone", overlay.attachedBone());
        return root;
    }

    /**
     * Returns a matrix as its four columns, in the storage order the type documents.
     *
     * <p>What this section can see of a block overlay's placement is its product, because the product is
     * what the row carries and the ops that composed it are a fact of the shipped table rather than of
     * the built index. That splits the two questions cleanly rather than blurring them: an op that moves
     * in the table moves {@code digest.shipped-tables} and is named there in its own vocabulary, and what
     * is left for this section to answer is whether the SAME ops still compose to the same bits - which
     * is the one thing folding at index build put at risk and the one thing a list of ops could not show.
     *
     * <p>Two different chains can alias onto one matrix here. They also render identically, so the
     * aliasing costs this section nothing it was measuring.
     *
     * @param matrix the matrix to emit
     * @return the four columns, in column-major order
     */
    private static @NotNull JsonArray matrix(@NotNull Matrix4f matrix) {
        JsonArray root = new JsonArray();
        for (int col = 1; col <= 4; col++) root.add(floats(matrix.column(col)));
        return root;
    }

    /**
     * Returns an appearance gate as a tagged record. Both switches are exhaustive over a sealed
     * interface, so a new gate arm or a new gateable axis is a compile error here rather than a
     * silent hole in the dump. A {@link AppearanceGate.Selected} is projected back into the table's
     * own {@code when} spelling per axis kind - {@code charged} for the creeper's flag row,
     * {@code flag} with the token and polarity for any other, {@code age} / {@code size} for the
     * shell alternates - so the stored bytes hold across the gate roster.
     *
     * @param gate the gate to emit
     * @return the gate object
     */
    private static @NotNull JsonObject gate(@NotNull AppearanceGate gate) {
        JsonObject root = new JsonObject();
        switch (gate) {
            case AppearanceGate.Selected selected -> {
                switch (selected.option()) {
                    case Age age -> {
                        root.addProperty("gate", "age");
                        root.addProperty("value", age.name());
                    }
                    case Size size -> {
                        root.addProperty("gate", "size");
                        root.addProperty("value", size.name());
                    }
                    case Flag flag -> {
                        if (flag == Flag.CHARGED && selected.expected())
                            root.addProperty("gate", "charged");
                        else {
                            root.addProperty("gate", "flag");
                            root.addProperty("flag", flag.name().toLowerCase(Locale.ROOT));
                            root.addProperty("value", selected.expected());
                        }
                    }
                }
            }
            case AppearanceGate.TintedGate tinted -> {
                root.addProperty("gate", "tinted");
                tinted.axis().ifPresent(axis -> root.addProperty("tint_by", axis.token()));
            }
        }
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
        // Emitted only where they stand away from their default, on the same terms as `parent`: what
        // a subject rests without is carried by the few bones a selection moves, and every other bone
        // draws and is moved by nothing.
        if (!bone.isVisible()) root.addProperty("visible", false);
        if (bone.getToggle() != null) root.addProperty("toggle", bone.getToggle());
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
     *
     * @param model the model to emit
     * @return the model object
     */
    private static @NotNull JsonObject model(@NotNull ModelData model) {
        JsonObject root = new JsonObject();
        root.addProperty("ambient_occlusion", model.isAmbientocclusion());
        root.add("textures", modelTextures(model));
        root.add("elements", CanonicalJson.ordered(model.getElements(), PipelineParityDump::element));
        root.add("display", CanonicalJson.map(model.getDisplay(), PipelineParityDump::transform));
        return root;
    }

    /**
     * Returns a model's texture slots as {@code slot -> {sprite, force_translucent}}.
     * <p>
     * The loaded form is one map: both authored shapes (bare string, and the 26.1
     * {@code {sprite, force_translucent}} object) parse to a {@link ModelTexture}, a string-form slot
     * carrying {@code force_translucent: false}. So the flag is loaded state, never omitted.
     *
     * @param model the model whose textures to emit
     * @return the texture-slot object
     */
    private static @NotNull JsonObject modelTextures(@NotNull ModelData model) {
        JsonObject root = new JsonObject();
        model.getTextures().forEach((slot, texture) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("sprite", texture.sprite());
            entry.addProperty("force_translucent", texture.forceTranslucent());
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
