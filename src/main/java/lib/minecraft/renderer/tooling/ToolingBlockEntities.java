package lib.minecraft.renderer.tooling;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.pipeline.AssetPipelineOptions;
import lib.minecraft.renderer.pipeline.client.ClientJarDownloader;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import lib.minecraft.renderer.pipeline.loader.BlockEntityLoader;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.blockentity.BlockListDiscovery;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.blockentity.InventoryTransformDecomposer;
import lib.minecraft.renderer.tooling.blockentity.Source;
import lib.minecraft.renderer.tooling.blockentity.SourceDiscovery;
import lib.minecraft.renderer.tooling.blockentity.TintDiscovery;
import lib.minecraft.renderer.tooling.blockentity.YAxis;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Entry point invoked by the {@code blockEntities} Gradle task.
 * <p>
 * Downloads the deobfuscated Minecraft client jar, parses block entity model classes via
 * {@link Parser}, and writes the result to
 * {@code src/main/resources/lib/minecraft/renderer/tile_entity_models.json}. The runtime pipeline reads
 * the JSON via {@link BlockEntityLoader}.
 */
@UtilityClass
public final class ToolingBlockEntities {

    /** Fixed output path for the bundled block-entity catalog resource. */
    private static final @NotNull Path OUTPUT_PATH = Path.of("src/main/resources/lib/minecraft/renderer/block_entities.json");

    /** Client-jar Minecraft version this generator targets; written to the JSON header for drift tracking. */
    private static final @NotNull String SOURCE_VERSION = "26.1";

    /**
     * Runs the generator.
     *
     * @param args optional {@code --lenient} flag to continue past WARN-level diagnostics
     * @throws IOException if the client jar cannot be downloaded or the JSON file cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        List<String> argList = Arrays.asList(args);
        boolean lenient = argList.contains("--lenient");

        AssetPipelineOptions options = AssetPipelineOptions.defaults();
        Path jarPath = ClientJarDownloader.download(options, new HttpFetcher());

        System.out.println("Discovering block entity sources from client jar...");
        Diagnostics diagnostics = new Diagnostics();

        JsonObject merged;
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ConcurrentList<Source> allSources = SourceDiscovery.discover(zip, diagnostics);
            Map<String, BlockListDiscovery.EntityBlockMapping> blockList = BlockListDiscovery.discover(zip, diagnostics);
            // Whitelist by BlockListDiscovery's known entity-ids. SourceDiscovery emits sources
            // for every registered BlockEntityRenderer (including renderers like enchanting_table
            // and lectern whose entity-id has no block-list binding); this filter restricts the
            // output pipeline to the entity-ids that BlockListDiscovery actually handles.
            // TODO: future PR could expand BlockListDiscovery to cover additional BE renderers
            // (enchanting_table, lectern) so this filter becomes a no-op.
            ConcurrentList<Source> sources = Concurrent.newList();
            for (Source s : allSources)
                if (blockList.containsKey(s.entityId())) sources.add(s);

            Map<String, String> entityIdToRenderer = buildEntityIdToRendererMap(zip, sources);
            Map<String, float[]> inventoryTransforms = InventoryTransformDecomposer.decomposeAll(zip, entityIdToRenderer, diagnostics);
            mergeInventoryTransformOverrides(entityIdToRenderer.keySet(), inventoryTransforms, diagnostics);
            Set<String> tinted = TintDiscovery.discover(zip, sources, entityIdToRenderer, diagnostics);

            System.out.printf("Discovered %d sources; parsing...%n", sources.size());
            ConcurrentMap<String, JsonObject> models = Parser.parse(jarPath, sources, diagnostics);
            System.out.printf("Parsed %d / %d sources%n", models.size(), sources.size());

            // Lenient mode prints every diagnostic for manual inspection. Strict mode (default)
            // only prints and then fails so the output stays visible in CI logs before the error.
            for (String entry : diagnostics.entries())
                System.err.println("  " + entry);

            if (!lenient && diagnostics.strictFailingCount() > 0)
                throw new AssetPipelineException(
                    "Strict mode: %d parse diagnostic(s) at WARN+ severity. Rerun with --lenient to continue.",
                    diagnostics.strictFailingCount()
                );

            JsonObject blockModels = BlockModelConverter.convert(models, inventoryTransforms, tinted);
            merged = buildMergedOutput(blockModels, models, blockList, inventoryTransforms, tinted);
        }

        Files.createDirectories(OUTPUT_PATH.getParent());
        Files.writeString(OUTPUT_PATH,
            new GsonBuilder().setPrettyPrinting().create().toJson(merged) + System.lineSeparator());
        System.out.println("Wrote " + OUTPUT_PATH.toAbsolutePath());
    }

    /**
     * Derives an {@code entityId -> rendererInternalName} map from the discovered sources. For
     * each Source we look up which renderer class owns the entity id (by scanning the
     * registrations in {@code BlockEntityRenderers.<clinit>} via {@link SourceDiscovery}
     * internals). When the Source's target class is a renderer itself (e.g.
     * {@code BedRenderer.createHeadLayer}), that's the renderer. Otherwise we fall back to
     * the model class's name (the parser only uses this map for the tint + inventory-transform
     * catalog sanity checks - any model-class string would satisfy those).
     */
    private static @NotNull Map<String, String> buildEntityIdToRendererMap(@NotNull ZipFile zip, @NotNull ConcurrentList<Source> sources) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Source s : sources) {
            String internal = s.classEntry().replace(".class", "");
            if (internal.startsWith("net/minecraft/client/renderer/blockentity/")) {
                out.put(s.entityId(), internal);
                continue;
            }
            // Model-class source: derive the renderer from the entityId pattern. Several entity
            // ids point at renderers they don't live in (banner uses BannerRenderer, skull
            // variants use SkullBlockRenderer). This lookup is pragmatic - the catalogs only
            // use it for sanity-check drift warnings, not output geometry.
            out.put(s.entityId(), mapEntityIdToRenderer(s.entityId()));
        }
        return out;
    }

    /**
     * Small mapping of per-entity-id -> renderer internal name for use by the inventory
     * transform + tint catalog sanity checks. This is the one place in the wire-up that
     * statically names renderers; a future PR 5 could derive it from the same registry walk
     * {@link SourceDiscovery} already performs.
     */
    private static @NotNull String mapEntityIdToRenderer(@NotNull String entityId) {
        return switch (entityId) {
            case "minecraft:chest" -> "net/minecraft/client/renderer/blockentity/ChestRenderer";
            case "minecraft:banner", "minecraft:banner_flag", "minecraft:wall_banner", "minecraft:wall_banner_flag" -> "net/minecraft/client/renderer/blockentity/BannerRenderer";
            case "minecraft:shulker_box" -> "net/minecraft/client/renderer/blockentity/ShulkerBoxRenderer";
            case "minecraft:bell_body" -> "net/minecraft/client/renderer/blockentity/BellRenderer";
            case "minecraft:copper_golem_statue" -> "net/minecraft/client/renderer/blockentity/CopperGolemStatueBlockRenderer";
            case "minecraft:skull_head", "minecraft:skull_humanoid_head", "minecraft:skull_dragon_head", "minecraft:skull_piglin_head" -> "net/minecraft/client/renderer/blockentity/SkullBlockRenderer";
            default -> "net/minecraft/client/renderer/blockentity/BlockEntityRenderers";
        };
    }

    /**
     * Merges hand-curated inventory-transform overrides from
     * {@code block_entities_overrides.json} into the bytecode-decomposed map.
     *
     * <p>Used for the single entity id ({@code minecraft:skull_dragon_head}) whose
     * distinguishing {@code tz=1.25} comes from the {@code DragonHeadModel} geometry rather
     * than the {@code SkullBlockRenderer.createGroundTransformation} factory - the decomposer
     * emits the shared skull tuple {@code [8, 0, 8, 180, 0, 0]} for every skull variant, and
     * the dragon-specific override supplies the off-centre Z translate from the overrides
     * file. The loader is unchanged: at runtime it reads {@code inventory_transform}
     * straight from {@code block_entities.json} as the decomposer-plus-override merged value.
     *
     * <p>Entity ids present in {@code decomposed} keep their bytecode-extracted tuple unless
     * the overrides file explicitly sets a different {@code inventory_transform} field. Ids
     * missing from {@code decomposed} pull their tuple from overrides outright; ids missing
     * from both drop out of the final map and a {@code diag.warn} surfaces so the next MC
     * version bump can revisit.
     */
    private static void mergeInventoryTransformOverrides(
        @NotNull Set<String> entityIds,
        @NotNull Map<String, float[]> decomposed,
        @NotNull Diagnostics diag
    ) {
        Path overridesPath = Path.of("src/main/resources/lib/minecraft/renderer/block_entities_overrides.json");
        if (!Files.exists(overridesPath)) return;
        JsonObject overrides;
        try {
            overrides = new com.google.gson.Gson().fromJson(Files.readString(overridesPath), JsonObject.class);
        } catch (Exception ex) {
            diag.warn("inventory-transform: overrides file '%s' unreadable - %s", overridesPath, ex.getMessage());
            return;
        }
        if (overrides == null || !overrides.has("per_entity")) return;
        JsonObject perEntity = overrides.getAsJsonObject("per_entity");

        for (String entityId : entityIds) {
            if (!perEntity.has(entityId)) continue;
            JsonObject entry = perEntity.getAsJsonObject(entityId);
            if (!entry.has("inventory_transform")) continue;
            JsonArray arr = entry.getAsJsonArray("inventory_transform");
            float[] tuple = new float[arr.size()];
            for (int i = 0; i < tuple.length; i++) tuple[i] = arr.get(i).getAsFloat();
            // Overrides always win - the field is only present for entities the decomposer
            // cannot fully resolve (currently just minecraft:skull_dragon_head).
            decomposed.put(entityId, tuple);
        }
    }

    /**
     * Composes the unified {@code block_entities.json} output. Parses the existing file (if
     * present) to preserve hand-curated fields ({@code blocks} variants, {@code parts}
     * shape) that are not yet auto-discovered, then overwrites the auto-derivable fields
     * ({@code model} geometry from the ASM parse, {@code y_axis} + {@code inventory_transform}
     * + {@code tinted} from the current Java literals) so re-running the task is idempotent.
     */
    private static @NotNull JsonObject buildMergedOutput(
        @NotNull JsonObject blockModels,
        @NotNull ConcurrentMap<String, JsonObject> parsedEntityModels,
        @NotNull Map<String, BlockListDiscovery.EntityBlockMapping> blockList,
        @NotNull Map<String, float[]> inventoryTransforms,
        @NotNull Set<String> tintedModelIds
    ) throws IOException {
        @Nullable JsonObject existing = null;
        if (Files.exists(OUTPUT_PATH)) {
            String raw = Files.readString(OUTPUT_PATH);
            try {
                existing = new com.google.gson.Gson().fromJson(raw, JsonObject.class);
            } catch (Exception ex) {
                System.err.println("  Warning: could not parse existing " + OUTPUT_PATH + " - writing fresh output");
            }
        }
        JsonObject existingEntities = existing != null && existing.has("entities")
            ? existing.getAsJsonObject("entities")
            : new JsonObject();

        JsonObject root = new JsonObject();
        root.addProperty("//", mergedHeader());
        root.addProperty("source_version", SOURCE_VERSION);

        JsonObject entities = new JsonObject();

        // Iterate in the existing file's key order when we have one (keeps diffs small across
        // regeneration passes); then append any newly discovered models that did not appear
        // in the existing file (e.g. a freshly added entity id from a MC version rev). The
        // blockList catalog is the authoritative source of which entity ids ship.
        java.util.LinkedHashSet<String> entityOrder = new java.util.LinkedHashSet<>();
        if (!existingEntities.entrySet().isEmpty())
            entityOrder.addAll(existingEntities.keySet());
        entityOrder.addAll(blockList.keySet());
        entityOrder.addAll(parsedEntityModels.keySet());

        for (String modelId : entityOrder) {
            if (modelId.equals("//")) continue;

            JsonObject converted = blockModels.has(modelId) && blockModels.get(modelId).isJsonObject()
                ? blockModels.getAsJsonObject(modelId)
                : null;
            JsonObject parsedEntity = parsedEntityModels.get(modelId);
            if (converted == null && parsedEntity == null) continue;

            JsonObject entityOut = new JsonObject();
            if (converted != null)
                entityOut.add("model", buildModelSubobject(converted));

            String yAxis = parsedEntity != null && parsedEntity.has("y_axis")
                ? parsedEntity.get("y_axis").getAsString()
                : "DOWN";
            entityOut.addProperty("y_axis", yAxis);
            entityOut.addProperty("inventory_y_rotation", 0);

            float[] invTransform = inventoryTransforms.get(modelId);
            if (invTransform != null) {
                JsonArray arr = new JsonArray();
                for (float v : invTransform) arr.add(v);
                entityOut.add("inventory_transform", arr);
            }
            entityOut.addProperty("tinted", tintedModelIds.contains(modelId));

            // Block list + parts come from BlockListDiscovery; only fall back to existing
            // hand-curated arrays when discovery doesn't carry the entity.
            BlockListDiscovery.EntityBlockMapping catalogEntry = blockList.get(modelId);
            if (catalogEntry != null) {
                JsonArray parts = buildPartsArray(catalogEntry);
                if (parts != null) entityOut.add("parts", parts);
                JsonArray blocks = buildBlocksArray(catalogEntry);
                if (blocks != null) entityOut.add("blocks", blocks);
            } else {
                JsonObject existingEntity = existingEntities.has(modelId) ? existingEntities.getAsJsonObject(modelId) : null;
                if (existingEntity != null) {
                    if (existingEntity.has("parts")) entityOut.add("parts", existingEntity.get("parts"));
                    if (existingEntity.has("blocks")) entityOut.add("blocks", existingEntity.get("blocks"));
                }
            }

            entities.add(modelId, entityOut);
        }

        root.add("entities", entities);
        return root;
    }

    /**
     * Serialises {@code parts} entries to the JSON shape the loader expects. Entries with a
     * {@code null} offset and {@code null} texture emit just {@code {"model": ...}}; entries
     * with only an offset emit {@code {"model": ..., "offset": [x, y, z]}}; full entries emit
     * all three keys.
     */
    private static @Nullable JsonArray buildPartsArray(@NotNull BlockListDiscovery.EntityBlockMapping entry) {
        List<BlockListDiscovery.PartRef> parts = entry.parts();
        if (parts == null) return null;
        JsonArray arr = new JsonArray();
        for (BlockListDiscovery.PartRef p : parts) {
            JsonObject part = new JsonObject();
            part.addProperty("model", p.model());
            if (p.offset() != null) {
                JsonArray off = new JsonArray();
                for (int v : p.offset()) off.add(v);
                part.add("offset", off);
            }
            if (p.texture() != null)
                part.addProperty("texture", p.texture());
            arr.add(part);
        }
        return arr;
    }

    /**
     * Serialises {@code blocks} entries to the JSON shape the loader expects. Returns
     * {@code null} when the entry has no blocks; the caller omits the key entirely in that
     * case, matching how the previous hand-curated JSON was structured.
     */
    private static @Nullable JsonArray buildBlocksArray(@NotNull BlockListDiscovery.EntityBlockMapping entry) {
        List<BlockListDiscovery.BlockMapping> blocks = entry.blocks();
        if (blocks.isEmpty()) return null;
        JsonArray arr = new JsonArray();
        for (BlockListDiscovery.BlockMapping b : blocks) {
            JsonObject block = new JsonObject();
            block.addProperty("blockId", b.blockId());
            block.addProperty("textureId", b.textureId());
            arr.add(block);
        }
        return arr;
    }

    /**
     * Extracts the model-body subobject ({@code textureWidth}, {@code textureHeight},
     * {@code elements}) from a {@link BlockModelConverter#convert converted} entry.
     */
    private static @NotNull JsonObject buildModelSubobject(@NotNull JsonObject converted) {
        JsonObject model = new JsonObject();
        if (converted.has("textureWidth"))
            model.add("textureWidth", converted.get("textureWidth"));
        if (converted.has("textureHeight"))
            model.add("textureHeight", converted.get("textureHeight"));
        if (converted.has("elements"))
            model.add("elements", converted.get("elements"));
        return model;
    }

    /** Builds the human-readable header comment prepended to the generated JSON. */
    private static @NotNull String mergedHeader() {
        return "Generated by ToolingBlockEntities (tooling/blockEntities Gradle task). Unified "
            + "block-entity catalog keyed by entity-model id: each entry carries the ASM-extracted "
            + "geometry (elements from LayerDefinition bytecode), metadata (y_axis source "
            + "convention, inventory_y_rotation GUI-facing fix, inventory_transform decomposed "
            + "from the Renderer's PoseStack, tinted flag), optional sub-model parts with their "
            + "render offsets, and the list of block variants that render as this entity model "
            + "along with their entity-texture paths. Supersedes the former split between "
            + "tile_entity_models.json (generated geometry) and tile_entity_mappings.json "
            + "(hand-edited block bindings); both source files are now derived in one pass from "
            + "the 26.1 client jar. Hand-edited atlas/GUI fields (iconRotation, additive, "
            + "per-block tint, forced inventory_y_rotation) live in the sibling "
            + "block_entities_overrides.json and are merged at load time by BlockEntityLoader. "
            + "Run the tooling/blockEntities Gradle task to refresh; BlockEntitiesGoldenTest "
            + "guards against silent drift via a SHA-256 over the canonical JSON.";
    }

    /**
     * An ASM bytecode walker that extracts block entity model geometry from the deobfuscated
     * Minecraft 26.1 client jar. Parses the {@code createSingleBodyLayer()} / {@code createBodyLayer()}
     * methods of model classes to extract cube definitions, UV offsets, pivot points, and texture
     * dimensions into {@code EntityModelData}-compatible JSON.
     * <p>
     * The parser tracks a numeric literal stack and recognises the builder-chain pattern used by
     * vanilla model classes:
     * <pre><code>
     * root.addOrReplaceChild("name",
     *     CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d),
     *     PartPose.offset(px, py, pz));
     * </code></pre>
     * Each {@code addOrReplaceChild} call emits a bone with its cubes. The texture dimensions are
     * extracted from the final {@code LayerDefinition.create(mesh, texW, texH)} call.
     *
     * @see BlockEntityLoader
     */
    @UtilityClass
    static class Parser {

        private static final @NotNull String CUBE_LIST_BUILDER = "net/minecraft/client/model/geom/builders/CubeListBuilder";
        private static final @NotNull String PART_POSE = "net/minecraft/client/model/geom/PartPose";
        private static final @NotNull String PART_DEFINITION = "net/minecraft/client/model/geom/builders/PartDefinition";
        private static final @NotNull String LAYER_DEFINITION = "net/minecraft/client/model/geom/builders/LayerDefinition";

        // Block-entity sources are discovered by {@link SourceDiscovery} and passed through
        // {@link #parse} at runtime; the former hardcoded {@code SOURCES} list was removed
        // in PR 2 once {@code SourceDiscovery} demonstrated parity against the baseline.
        // The commented-out reference set below is retained as a quick-reference of what
        // vanilla 26.1 produces, for the maintainer diffing against a future MC version.
        /*
        private static final @NotNull List<Source> SOURCES = List.of(
            // ChestModel is authored in Y-up block-space (positive Y is up) rather than the
            // Y-down ModelPart convention used elsewhere - the lid sits at y=9..14 and the body
            // at y=0..10 in the raw data, which is how the chest block looks when rendered.
            // {@code ChestRenderer} yaws the rendering by {@code -NORTH.toYRot() = 180} to face
            // the lock toward the camera; that pose is carried through as {@code inventoryYRotation}.
            new Source("net/minecraft/client/model/object/chest/ChestModel.class", "createSingleBodyLayer", "minecraft:chest", YAxis.UP, 180f),

            // BannerModel.createBodyLayer(boolean isStanding) + BannerFlagModel.createFlagLayer(boolean isStanding)
            // - vanilla splits banner geometry across two model classes. BannerModel owns
            // pole + bar; BannerFlagModel owns the flag. Both select standing-vs-wall via a
            // boolean parameter that gates PartPose.offset and addBox values through ifeq/goto
            // chains. We parse each branch separately via {@code paramIntValues} so the parser
            // follows the control flow (see {@link #walkInstructions}'s ILOAD / IFEQ / GOTO
            // handling) and splits the standing and wall variants into their own model ids.
            // Each flag merges into its body via the `parts` field in tile_entity_mappings.json.
            //
            // Standing variant (paramIntValues=[1], ids {@code banner} + {@code banner_flag}):
            //   body  → pole (2×42×2 vertical post) + bar (20×2×2 crossbar at y=-44)
            //   flag  → pivot (0, -44, 0), cube (-10..10, 0..40, -2..-1) - hangs from bar
            //
            // Wall variant (paramIntValues=[0], ids {@code wall_banner} + {@code wall_banner_flag}):
            //   body  → just the bar (20×2×2 at y=-20.5, z=9.5..11.5) - no pole, since wall
            //           banners mount to a wall surface instead of a standing post
            //   flag  → pivot (0, -20.5, 10.5), same cube as standing - the wall-specific
            //           pivot places the flag hanging from the bar against the wall surface
            //
            // inventoryYRotation=180: vanilla's BannerRenderer places the flag on the +Z
            // (SOUTH) side of the pole, which under our standard iso gui rotation [30, 225, 0]
            // ends up BEHIND the pole (camera-facing side is NORTH / -Z). The banner item's
            // display.gui rotation is [30, 20, 0] - a ~180° yaw delta from our block default -
            // so the actual inventory icon the player sees has the flag facing the camera. Bake
            // a Y-rotation around block centre so the flag lands on -Z side and stays visible
            // under our iso pose (same pattern as chest).
            new Source("net/minecraft/client/model/object/banner/BannerModel.class", "createBodyLayer", "minecraft:banner", YAxis.DOWN, 180f, null, null, new int[]{ 1 }),
            new Source("net/minecraft/client/model/object/banner/BannerFlagModel.class", "createFlagLayer", "minecraft:banner_flag", YAxis.DOWN, 180f, null, null, new int[]{ 1 }),
            // Wall variants use the same 180° Y-rotation as standing variants. The prior analysis
            // thought the wall flag's entity-space z=10.5 pivot landed it on the camera-facing
            // side without a yaw, but empirically the resulting icon shows the flag facing away
            // from the iso camera (bar in front, flag hanging to the back) - the same failure
            // mode standing variants have without the 180° fix. Match the standing setup so wall
            // banners render with the flag facing the camera.
            new Source("net/minecraft/client/model/object/banner/BannerModel.class", "createBodyLayer", "minecraft:wall_banner", YAxis.DOWN, 180f, null, null, new int[]{ 0 }),
            new Source("net/minecraft/client/model/object/banner/BannerFlagModel.class", "createFlagLayer", "minecraft:wall_banner_flag", YAxis.DOWN, 180f, null, null, new int[]{ 0 }),
            new Source("net/minecraft/client/renderer/blockentity/BedRenderer.class", "createHeadLayer", "minecraft:bed_head", YAxis.DOWN, 0f),
            new Source("net/minecraft/client/renderer/blockentity/BedRenderer.class", "createFootLayer", "minecraft:bed_foot", YAxis.DOWN, 0f),
            new Source("net/minecraft/client/model/monster/shulker/ShulkerModel.class", "createShellMesh", "minecraft:shulker_box", YAxis.DOWN, 0f),
            new Source("net/minecraft/client/renderer/blockentity/StandingSignRenderer.class", "createSignLayer", "minecraft:sign", YAxis.DOWN, 0f),
            new Source("net/minecraft/client/renderer/blockentity/HangingSignRenderer.class", "createHangingSignLayer", "minecraft:hanging_sign", YAxis.DOWN, 0f),
            new Source("net/minecraft/client/renderer/blockentity/ConduitRenderer.class", "createShellLayer", "minecraft:conduit", YAxis.DOWN, 0f),

            // BellModel.createBodyLayer - bell-cup geometry that hangs from the bar built by
            // {@code block/bell_floor.json} / {@code block/bell_ceiling.json} / etc. Authored in
            // Y-UP block space (pivot at y=12, cube spans y=6..13 - i.e. dangling below the bar
            // at y=13-14 in vanilla world coords). Marked {@link YAxis#UP} so the parser pre-flips
            // to the canonical Y-DOWN form; with no INVENTORY_TRANSFORMS entry the default
            // {@code cy = -cy} unflip restores the original block-space positions exactly.
            // <p>
            // Wired as an {@linkplain lib.minecraft.renderer.asset.Block.Entity#additive() additive}
            // mapping so the bar+post primary geometry from the four bell variant blocks
            // ({@code bell_floor}, {@code bell_ceiling}, {@code bell_wall}, {@code bell_between_walls})
            // is preserved and the bell cup is layered on top at render time.
            new Source("net/minecraft/client/model/object/bell/BellModel.class", "createBodyLayer", "minecraft:bell_body", YAxis.UP, 0f),

            // DecoratedPotRenderer authors its cubes in block-space Y-up (cube y=17..20 for the
            // neck rim sits above the block top, lid/base decals at y=16 / y=0), so the default
            // Y-flip would bury everything below the block floor. The neutral INVENTORY_TRANSFORMS
            // entry below skips the flip and leaves positions as-authored.
            //
            // The pot needs TWO layers because vanilla's decorated_pot block.json has no elements -
            // the whole pot comes from the renderer:
            //   - createBaseLayer produces the neck/lid/base (neck bone with two cubes, plus
            //     top + bottom flat decals sharing one pre-built CubeListBuilder via astore_4 +
            //     aload_4).
            //   - createSidesLayer produces the four wall panels (front/back/left/right) that
            //     form the pot body, each rotated around its pivot to face a different side.
            // Both layers are linked via `parts` in tile_entity_mappings.json so the loader
            // merges them into a single pot model.
            new Source("net/minecraft/client/renderer/blockentity/DecoratedPotRenderer.class", "createBaseLayer", "minecraft:decorated_pot", YAxis.DOWN, 0f),
            new Source("net/minecraft/client/renderer/blockentity/DecoratedPotRenderer.class", "createSidesLayer", "minecraft:decorated_pot_sides", YAxis.DOWN, 0f),

            // CopperGolemStatueBlockRenderer bakes four Copper Golem poses
            // (COPPER_GOLEM / _RUNNING / _SITTING / _STAR from ModelLayers); picks one per
            // blockstate pose at runtime. For the atlas we pick COPPER_GOLEM (the default
            // standing pose) via CopperGolemModel.createBodyLayer - same geometry as the
            // copper golem mob, just rendered as a static block.
            new Source("net/minecraft/client/model/animal/golem/CopperGolemModel.class", "createBodyLayer", "minecraft:copper_golem_statue", YAxis.DOWN, 0f),

            // Skulls. MC 26.1 skulls use the new items/*.json "minecraft:special" +
            // "minecraft:head" type which our pipeline doesn't consume, so the only way to
            // get them in the atlas is to register block-entity geometry and let the block-
            // item redirect in ItemRenderer pick them up.
            //
            // SkullModel.createHeadModel (MeshDefinition) builds the shared 8x8x8 head cube
            // at origin=(-4,-8,-4). Its two callers - createMobHeadLayer (tex 64x32, used for
            // skeleton / wither_skeleton / creeper) and createHumanoidHeadLayer (tex 64x64,
            // adds a 0.25-inflated "hat" overlay, used for zombie / player) - aren't parseable
            // directly because they call createHeadModel via invokestatic-follow into the
            // MeshDefinition builder. Parse createHeadModel directly and override tex
            // dimensions per variant.
            //
            // inventoryYRotation=180: vanilla item/template_skull's display.gui.rotation is
            // [30, 45, 0] (yaw 45) while our renderer uses the default [30, 225, 0] (yaw 225).
            // Same 180° delta as chest - bake a Y-rotation here so the skull's front face is
            // camera-facing under our gui pose.
            //
            // Two parses of SkullModel.createHeadModel are needed because different skull
            // variants bind textures with different heights: mob skulls (skeleton, wither_skeleton,
            // creeper) use 64x32 entity textures, while humanoid skulls (zombie, player) use
            // 64x64 player-skin textures where the head occupies the same top-left 32x16 region.
            // The block-model UV system normalises to (0..16) against the texture's actual
            // dimensions at render time, so the converter needs to scale V differently per
            // target texture height. Same geometry, two UV calibrations.
            new Source("net/minecraft/client/model/object/skull/SkullModel.class", "createHeadModel", "minecraft:skull_head", YAxis.DOWN, 180f, 64, 32),
            new Source("net/minecraft/client/model/object/skull/SkullModel.class", "createHeadModel", "minecraft:skull_humanoid_head", YAxis.DOWN, 180f, 64, 64),

            // DragonHeadModel.createHeadLayer - "head" bone with 6 cubes (upper_lip, upper_head,
            // scale x2, nostril x2) built via addBox(String,FFF,IIIII) inline-UV variant, wrapped
            // in PartPose.offset(FFF).scaled(0.75f). Plus a "jaw" child bone with one cube via the
            // standard texOffs+addBox(FFFFFF) pattern. Texture is 256x256 (LayerDefinition.create).
            new Source("net/minecraft/client/model/object/skull/DragonHeadModel.class", "createHeadLayer", "minecraft:skull_dragon_head", YAxis.DOWN, 180f),

            // PiglinHeadModel.createHeadModel - returns a MeshDefinition populated by invokestatic
            // AbstractPiglinModel.addHead(CubeDeformation.NONE, mesh). The addHead static is
            // declared on AbstractPiglinModel but invoked via PiglinModel.addHead (JVM walks
            // superclass chain). Parser follows net/minecraft/client/model/ invokestatic calls
            // outside the /geom/ builder package to pick up this pattern.
            new Source("net/minecraft/client/model/object/skull/PiglinHeadModel.class", "createHeadModel", "minecraft:skull_piglin_head", YAxis.DOWN, 180f, 64, 64)
        );
        */

        /**
         * Parses block entity model classes from the supplied client jar and returns the
         * extracted models as serialised JSON objects keyed by entity id. The sources list is
         * produced by {@link SourceDiscovery#discover} - see that class for the bytecode walk
         * that drives it.
         *
         * @param jarPath the deobfuscated client jar (MC 26.1+)
         * @param sources the sources to parse (one per entity id)
         * @param diagnostics diagnostic sink
         * @return a map of entity id to model JSON
         */
        public static @NotNull ConcurrentMap<String, JsonObject> parse(@NotNull Path jarPath, @NotNull List<Source> sources, @NotNull Diagnostics diagnostics) {
            ConcurrentMap<String, JsonObject> results = Concurrent.newMap();

            try (ZipFile zip = new ZipFile(jarPath.toFile())) {
                for (Source source : sources) {
                    String internalName = stripClassSuffix(source.classEntry());
                    ClassNode classNode = AsmKit.loadClass(zip, internalName);
                    if (classNode == null) {
                        diagnostics.error("%s: class '%s' not found in client jar (renamed in MC version bump?)", source.entityId(), source.classEntry());
                        continue;
                    }

                    try {
                        MethodNode method = AsmKit.findMethod(classNode, source.methodName());

                        if (method == null) {
                            diagnostics.error("%s: method '%s' not found on class '%s' (renamed in MC version bump?)", source.entityId(), source.methodName(), source.classEntry());
                            continue;
                        }

                        JsonObject model = parseLayerMethod(method.instructions, zip, source, diagnostics);
                        if (model != null) {
                            // Source overrides apply when the parsed method doesn't call
                            // LayerDefinition.create itself (e.g. SkullModel.createHeadModel returns
                            // a MeshDefinition; the caller supplies the texture dimensions).
                            if (source.texWidthOverride() != null)
                                model.addProperty("textureWidth", source.texWidthOverride());
                            if (source.texHeightOverride() != null)
                                model.addProperty("textureHeight", source.texHeightOverride());
                            if (source.yAxis() == YAxis.UP)
                                flipToYDown(model);
                            model.addProperty("y_axis", source.yAxis().name());
                            if (source.inventoryYRotation() != 0f)
                                model.addProperty("inventory_y_rotation", source.inventoryYRotation());
                            results.put(source.entityId(), model);
                        }

                    } catch (Exception ex) {
                        diagnostics.error("%s: parse failure - %s", source.entityId(), ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                throw new AssetPipelineException(ex, "Failed to read client jar '%s'", jarPath);
            }

            return results;
        }

        /**
         * Post-processes a Y-up block entity model into the canonical Y-down form. For each
         * bone, negates the pivot's Y so the {@code PartPose} offset flips into the Y-down
         * frame; for each cube, mirrors the {@code origin.y} about the pivot's XZ plane. Because
         * {@code origin} is the <b>min</b> corner and {@code size} is an unsigned extent, the
         * new min Y is the negated former max: {@code origin.y = -origin.y - size.y}. X, Z, and
         * size are unaffected.
         */
        private static void flipToYDown(@NotNull JsonObject model) {
            JsonObject bones = model.getAsJsonObject("bones");
            if (bones == null) return;

            for (Map.Entry<String, JsonElement> entry : bones.entrySet()) {
                JsonObject bone = entry.getValue().getAsJsonObject();

                JsonArray pivot = bone.getAsJsonArray("pivot");
                if (pivot != null && pivot.size() == 3)
                    pivot.set(1, new JsonPrimitive(-pivot.get(1).getAsFloat()));

                JsonArray cubes = bone.getAsJsonArray("cubes");
                if (cubes == null) continue;

                for (JsonElement cubeElement : cubes) {
                    JsonObject cube = cubeElement.getAsJsonObject();
                    JsonArray origin = cube.getAsJsonArray("origin");
                    JsonArray size = cube.getAsJsonArray("size");
                    if (origin == null || size == null || origin.size() != 3 || size.size() != 3)
                        continue;

                    float oy = origin.get(1).getAsFloat();
                    float sy = size.get(1).getAsFloat();
                    origin.set(1, new JsonPrimitive(-oy - sy));
                }
            }
        }

        /**
         * Parses a single layer-creation method's bytecode and extracts the model geometry.
         * Invokestatic calls targeting other model-building methods (not in the builder/geom
         * package) are followed recursively so chains like
         * {@code PiglinHeadModel.createHeadModel -> PiglinModel.addHead} resolve without
         * needing a dedicated source entry per delegate.
         */
        private static @Nullable JsonObject parseLayerMethod(@NotNull InsnList instructions, @NotNull ZipFile zip, @NotNull Source source, @NotNull Diagnostics diagnostics) {
            ParseState state = new ParseState();
            state.paramIntValues = source.paramIntValues();
            state.currentSource = source;
            state.diagnostics = diagnostics;
            walkInstructions(instructions, state, zip);

            // Literals left on the numeric stack after a parse usually mean a method-owner
            // descriptor we didn't recognise pushed arguments we never consumed. Kept at INFO
            // severity (does not fail strict mode) because end-of-method leftovers don't
            // corrupt output - only underflow does, and that has its own strict-failing
            // diagnostic at every addBox / PartPose site. The three 26.1 sources that
            // currently hit this ({@code decorated_pot}, {@code copper_golem_statue},
            // {@code skull_dragon_head}) all produce correct geometry; the leftovers are
            // just accounting gaps in the parser's method-owner dispatch.
            if (!state.numStack.isEmpty())
                diagnostics.info("%s: %d leftover literal(s) on numStack after parse - unhandled method-owner descriptor?", source.entityId(), state.numStack.size());

            if (state.bones.isEmpty()) return null;

            JsonObject model = new JsonObject();
            model.addProperty("textureWidth", state.texWidth);
            model.addProperty("textureHeight", state.texHeight);
            model.add("bones", state.bones);
            return model;
        }

        /**
         * Walks an instruction list, accumulating numeric literals on a stack and matching
         * builder-chain patterns. Recurses via {@link #handleMethodInsn}'s invokestatic-follow
         * branch so a single {@link ParseState} spans the entire dispatch chain.
         */
        private static void walkInstructions(@NotNull InsnList instructions, @NotNull ParseState state, @NotNull ZipFile zip) {
            for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
                Number literal = readNumericLiteral(node);
                if (literal != null) {
                    state.numStack.add(literal);
                    if (state.numStack.size() > 16) {
                        // First overflow in this parse surfaces a warning; subsequent drops are
                        // silent so a truly broken source doesn't spam 1000 copies. If the cap
                        // is ever hit on real sources it's a bug in the parser's pop accounting,
                        // not in the source bytecode - the stack shouldn't grow unbounded.
                        if (state.diagnostics != null && !state.overflowWarned && state.currentSource != null) {
                            state.diagnostics.warn("%s: numStack overflow (>16 literals) - oldest literals being dropped, pop accounting may be broken", state.currentSource.entityId());
                            state.overflowWarned = true;
                        }
                        state.numStack.removeFirst();
                    }
                    continue;
                }

                int opcode = node.getOpcode();

                // Conditional / unconditional jumps. Only followed when {@code paramIntValues}
                // is supplied - without a known parameter value, the parser falls back to its
                // default linear walk (which picks up LDCs on both branches of an if/else).
                // GOTO always jumps (target is determined statically by javac); IFEQ / IFNE
                // consume the top of {@link ParseState#branchStack} so the taken branch is
                // decided by the ILOAD slot's known value. Other jump opcodes (icmp, ifnull,
                // etc.) aren't emitted by the layer-build patterns we parse and are treated
                // as no-ops for now.
                if (state.paramIntValues != null && node instanceof JumpInsnNode jumpInsn) {
                    if (opcode == Opcodes.GOTO) {
                        node = jumpInsn.label;
                        continue;
                    }
                    if ((opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) && !state.branchStack.isEmpty()) {
                        int value = state.branchStack.remove(state.branchStack.size() - 1);
                        boolean jump = opcode == Opcodes.IFEQ ? value == 0 : value != 0;
                        if (jump) {
                            node = jumpInsn.label;
                            continue;
                        }
                    }
                }

                // Task 19: TABLESWITCH / LOOKUPSWITCH evaluation. Follows the same
                // {@code paramIntValues}-driven branch-evaluation gate as IFEQ / IFNE - when the
                // top of {@link ParseState#branchStack} holds a concrete value (put there by a
                // preceding ILOAD of a paramIntValues-registered slot), jump to the matching case
                // label. Otherwise the parser falls through linearly to preserve pre-Task 19
                // behaviour, and - when {@code paramIntValues} is set but the switch value is
                // unknown - surfaces a {@code WARN:} so the maintainer knows an unmodelled
                // dispatch slipped through.
                if (node instanceof TableSwitchInsnNode tableSwitch) {
                    if (state.paramIntValues != null && !state.branchStack.isEmpty()) {
                        int value = state.branchStack.remove(state.branchStack.size() - 1);
                        node = value >= tableSwitch.min && value <= tableSwitch.max
                            ? tableSwitch.labels.get(value - tableSwitch.min)
                            : tableSwitch.dflt;
                        continue;
                    }
                    if (state.paramIntValues != null && state.diagnostics != null && state.currentSource != null)
                        state.diagnostics.warn("%s: TABLESWITCH encountered with unknown value - falling through linearly, case bodies may corrupt numStack", state.currentSource.entityId());
                }
                if (node instanceof LookupSwitchInsnNode lookupSwitch) {
                    if (state.paramIntValues != null && !state.branchStack.isEmpty()) {
                        int value = state.branchStack.remove(state.branchStack.size() - 1);
                        int idx = lookupSwitch.keys.indexOf(value);
                        node = idx >= 0 ? lookupSwitch.labels.get(idx) : lookupSwitch.dflt;
                        continue;
                    }
                    if (state.paramIntValues != null && state.diagnostics != null && state.currentSource != null)
                        state.diagnostics.warn("%s: LOOKUPSWITCH encountered with unknown value - falling through linearly, case bodies may corrupt numStack", state.currentSource.entityId());
                }

                // ILOAD N: if the source declared a value for slot N, push it onto the
                // branch stack so the upcoming IFEQ / IFNE / switch can evaluate the
                // conditional. If the slot is NOT in {@code paramIntValues} (or the source
                // didn't supply any values), push a {@link NonLiteralMarker} onto
                // {@link ParseState#numStack} instead - when a downstream addBox / PartPose
                // consumes it, {@link #popIntWithDiagnostics} surfaces a {@code WARN:} so the
                // silent-zero failure mode doesn't get baked into the output cube.
                if (node instanceof VarInsnNode varInsn && opcode == Opcodes.ILOAD) {
                    int slot = varInsn.var;
                    boolean resolved = state.paramIntValues != null && slot >= 0 && slot < state.paramIntValues.length;
                    if (resolved)
                        state.branchStack.add(state.paramIntValues[slot]);
                    else
                        state.numStack.add(NON_LITERAL);
                }

                // FLOAD / DLOAD / LLOAD: the value comes from a local variable the parser
                // can't resolve. Push {@link #NON_LITERAL} so the next {@link #popFloatWithDiagnostics}
                // / {@link #popIntWithDiagnostics} surfaces the attribution instead of silently
                // consuming a stale zero off an earlier literal or a fresh zero from an empty
                // stack.
                if (node instanceof VarInsnNode
                    && (opcode == Opcodes.FLOAD || opcode == Opcodes.DLOAD || opcode == Opcodes.LLOAD))
                    state.numStack.add(NON_LITERAL);

                switch (node) {
                    case FieldInsnNode fieldInsn when opcode == Opcodes.GETSTATIC -> {
                        if (fieldInsn.owner.equals(PART_POSE) && fieldInsn.name.equals("ZERO")) {
                            state.pendingPivot = new float[]{ 0, 0, 0 };
                            state.pendingRotation = new float[]{ 0, 0, 0 };
                            state.pendingScale = 1f;
                        }
                    }
                    case MethodInsnNode methodInsn -> handleMethodInsn(methodInsn, opcode, state, zip);
                    // Track local-variable slot -> bone mapping so child bones inherit their
                    // parent's pivot + scale. Vanilla models use
                    // {@code head = root.addOrReplaceChild("head", ...); head.addOrReplaceChild("jaw", ...);}
                    // which compiles to {@code invokevirtual; astore_N; aload_N;} around the child's
                    // builder chain - so astore-after-flush and aload-before-chain are our hooks.
                    // Additionally, slots may hold a pre-built CubeListBuilder that multiple
                    // addOrReplaceChild calls share (DecoratedPotRenderer stores one builder and
                    // reuses it for both {@code top} and {@code bottom} bones). Snapshot pending
                    // cubes into {@link ParseState#slotToCubes} so a later aload_N can re-hydrate
                    // them for the next bone without re-reading the same addBox literals.
                    case VarInsnNode varInsn when opcode == Opcodes.ASTORE -> {
                        if (state.lastFlushedBone != null) {
                            state.localSlotBone.put(varInsn.var, state.lastFlushedBone);
                            state.lastFlushedBone = null;
                        } else if (!state.pendingCubes.isEmpty()) {
                            ConcurrentList<float[]> snapshot = Concurrent.newList();
                            for (float[] c : state.pendingCubes) snapshot.add(c.clone());
                            state.slotToCubes.put(varInsn.var, snapshot);
                            state.pendingCubes = Concurrent.newList();
                            state.pendingUv = new int[]{ 0, 0 };
                        }
                    }
                    case VarInsnNode varInsn when opcode == Opcodes.ALOAD -> {
                        String parent = state.localSlotBone.get(varInsn.var);
                        if (parent != null)
                            state.nextParent = parent;
                        ConcurrentList<float[]> savedCubes = state.slotToCubes.get(varInsn.var);
                        if (savedCubes != null) {
                            for (float[] c : savedCubes) state.pendingCubes.add(c.clone());
                        }
                    }
                    case LdcInsnNode ldc when ldc.cst instanceof String s ->
                        state.pendingPartName = s;
                    default -> { }
                }
            }
        }

        /**
         * Dispatches a {@code MethodInsnNode} by owner: builder chains (CubeListBuilder,
         * PartPose), bone-finalising ({@code PartDefinition.addOrReplaceChild}), texture dim
         * extraction ({@code LayerDefinition.create}), and model-package invokestatic-follow for
         * cross-class delegation patterns like
         * {@code PiglinHeadModel.createHeadModel -> PiglinModel.addHead}.
         */
        private static void handleMethodInsn(@NotNull MethodInsnNode methodInsn, int opcode, @NotNull ParseState state, @NotNull ZipFile zip) {
            if (methodInsn.owner.equals(CUBE_LIST_BUILDER)) {
                handleCubeListBuilder(methodInsn, state);
                return;
            }
            if (methodInsn.owner.equals(PART_POSE)) {
                handlePartPose(methodInsn, state);
                return;
            }
            if (methodInsn.owner.equals(PART_DEFINITION) && methodInsn.name.equals("addOrReplaceChild")) {
                flushPendingBone(state);
                return;
            }
            if (methodInsn.owner.equals(LAYER_DEFINITION) && methodInsn.name.equals("create")) {
                requireStack(state, 2, "LayerDefinition.create(mesh,II)");
                state.texHeight = popIntWithDiagnostics(state, "LayerDefinition.create(mesh,II) texHeight");
                state.texWidth = popIntWithDiagnostics(state, "LayerDefinition.create(mesh,II) texWidth");
                return;
            }
            // Invokestatic-follow: recurse into model-building statics outside the builder/geom
            // package (e.g. PiglinHeadModel.createHeadModel -> PiglinModel.addHead). The JVM
            // resolves invokestatic through the superclass chain, so {@link AsmKit#findMethodInHierarchy}
            // walks {@code superName} until the method is found.
            if (opcode == Opcodes.INVOKESTATIC
                && methodInsn.owner.startsWith("net/minecraft/client/model/")
                && !methodInsn.owner.startsWith("net/minecraft/client/model/geom/")) {
                MethodNode inlined = AsmKit.findMethodInHierarchy(zip, methodInsn.owner, methodInsn.name, methodInsn.desc);
                if (inlined != null)
                    walkInstructions(inlined.instructions, state, zip);
            }
        }

        /**
         * Warns when {@code state.numStack} has fewer than {@code required} entries at a
         * builder-dispatch site. The pop still proceeds with zero-fill (via
         * {@link #popInt} / {@link #popFloat}'s empty-stack fallback), but the diagnostic
         * surfaces the underflow so a bogus-coord cube doesn't silently ship.
         */
        private static void requireStack(@NotNull ParseState state, int required, @NotNull String where) {
            if (state.diagnostics == null || state.currentSource == null) return;
            int have = state.numStack.size();
            if (have < required)
                state.diagnostics.warn(
                    "%s at %s: numStack underflow (need %d, have %d) - output coords likely wrong",
                    state.currentSource.entityId(), where, required, have
                );
        }

        /**
         * Handles {@code CubeListBuilder.create / texOffs / addBox / mirror} calls, consuming
         * literals off {@link ParseState#numStack} and emitting pending cubes. Four addBox
         * variants are recognised - see the inline comment for the per-variant pop order.
         */
        private static void handleCubeListBuilder(@NotNull MethodInsnNode methodInsn, @NotNull ParseState state) {
            switch (methodInsn.name) {
                case "create" -> {
                    // CubeListBuilder.create() opens a builder chain. Snapshot the outer bone
                    // name (the ldc String pushed before the chain) into {@code boneName} so
                    // inner ldc Strings from per-cube addBox(String, ...) variants don't
                    // overwrite the addOrReplaceChild key. Also snapshot the parent captured
                    // from the most recent aload (typically the slot holding the parent
                    // PartDefinition returned by an earlier addOrReplaceChild).
                    // Clear {@code lastFlushedBone} since a new builder is now on the operand
                    // stack - any astore_N that follows stores the builder, not a stale
                    // PartDefinition the caller already discarded via {@code pop}.
                    if (state.pendingPartName != null)
                        state.boneName = state.pendingPartName;
                    state.parentBone = state.nextParent;
                    state.nextParent = null;
                    state.lastFlushedBone = null;
                }
                case "texOffs" -> {
                    if (methodInsn.desc.startsWith("(II")) {
                        requireStack(state, 2, "CubeListBuilder.texOffs(II)");
                        state.pendingUv[1] = popIntWithDiagnostics(state, "CubeListBuilder.texOffs(II) v");
                        state.pendingUv[0] = popIntWithDiagnostics(state, "CubeListBuilder.texOffs(II) u");
                    }
                }
                case "addBox" -> {
                    // Four addBox variants observed in vanilla (names/CubeDeformation args don't
                    // land on numStack, so only the numeric literals drive the pop order):
                    //  1. (FFFFFF) or (FFFFFF + CubeDeformation) - origin xyz + size whd; uses current texOffs.
                    //  2. (Ljava/lang/String;FFFFFF) - named single-cube, uses current texOffs. Dragon's jaw bone.
                    //  3. (Ljava/lang/String;FFFIIIII) - named multi-cube with inline (w,h,d,u,v) ints. Dragon's head bone
                    //     stacks 6 cubes this way, each with its own UV.
                    if (methodInsn.desc.startsWith("(Ljava/lang/String;FFFIIIII")) {
                        requireStack(state, 8, "CubeListBuilder.addBox(name,FFFIIIII)");
                        int v = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) v");
                        int u = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) u");
                        int d = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) d");
                        int h = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) h");
                        int w = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) w");
                        float z = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) z");
                        float y = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) y");
                        float x = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) x");
                        state.pendingCubes.add(new float[]{ x, y, z, w, h, d, u, v });
                    } else if (methodInsn.desc.startsWith("(FFFFFF") || methodInsn.desc.startsWith("(Ljava/lang/String;FFFFFF")) {
                        requireStack(state, 6, "CubeListBuilder.addBox(FFFFFF)");
                        float d = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) d");
                        float h = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) h");
                        float w = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) w");
                        float z = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) z");
                        float y = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) y");
                        float x = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) x");
                        state.pendingCubes.add(new float[]{ x, y, z, w, h, d, state.pendingUv[0], state.pendingUv[1] });
                    }
                }
                case "mirror" -> {
                    // mirror(Z) flips face-UVs on subsequent addBox cubes in vanilla. The atlas
                    // pipeline doesn't model per-cube mirror yet; pop the boolean to keep the
                    // literal stack aligned so it doesn't leak into the next builder call.
                    if (methodInsn.desc.startsWith("(Z")) {
                        requireStack(state, 1, "CubeListBuilder.mirror(Z)");
                        popIntWithDiagnostics(state, "CubeListBuilder.mirror(Z)");
                    }
                }
                default -> { }
            }
        }

        /**
         * Handles {@code PartPose.offset / rotation / offsetAndRotation / scaled} calls,
         * consuming literals off {@link ParseState#numStack} and storing the result on
         * {@link ParseState#pendingPivot} / {@link ParseState#pendingRotation} /
         * {@link ParseState#pendingScale} for the next {@code addOrReplaceChild} flush.
         */
        private static void handlePartPose(@NotNull MethodInsnNode methodInsn, @NotNull ParseState state) {
            switch (methodInsn.name) {
                case "offset" -> {
                    if (methodInsn.desc.startsWith("(FFF")) {
                        requireStack(state, 3, "PartPose.offset(FFF)");
                        float pz = popFloatWithDiagnostics(state, "PartPose.offset(FFF) z");
                        float py = popFloatWithDiagnostics(state, "PartPose.offset(FFF) y");
                        float px = popFloatWithDiagnostics(state, "PartPose.offset(FFF) x");
                        state.pendingPivot = new float[]{ px, py, pz };
                        state.pendingRotation = new float[]{ 0, 0, 0 };
                    }
                }
                case "rotation" -> {
                    // PartPose.rotation(rx, ry, rz) - rotation only, pivot stays at origin.
                    // Used by BedRenderer legs.
                    if (methodInsn.desc.startsWith("(FFF")) {
                        requireStack(state, 3, "PartPose.rotation(FFF)");
                        float rz = popFloatWithDiagnostics(state, "PartPose.rotation(FFF) z");
                        float ry = popFloatWithDiagnostics(state, "PartPose.rotation(FFF) y");
                        float rx = popFloatWithDiagnostics(state, "PartPose.rotation(FFF) x");
                        state.pendingPivot = new float[]{ 0, 0, 0 };
                        state.pendingRotation = new float[]{
                            (float) Math.toDegrees(rx),
                            (float) Math.toDegrees(ry),
                            (float) Math.toDegrees(rz)
                        };
                    }
                }
                case "offsetAndRotation" -> {
                    if (methodInsn.desc.startsWith("(FFFFFF")) {
                        requireStack(state, 6, "PartPose.offsetAndRotation(FFFFFF)");
                        float rz = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) rz");
                        float ry = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) ry");
                        float rx = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) rx");
                        float pz = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) pz");
                        float py = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) py");
                        float px = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) px");
                        state.pendingPivot = new float[]{ px, py, pz };
                        state.pendingRotation = new float[]{
                            (float) Math.toDegrees(rx),
                            (float) Math.toDegrees(ry),
                            (float) Math.toDegrees(rz)
                        };
                    }
                }
                case "scaled" -> {
                    // PartPose.scaled(F) - uniform scale around pivot at render time. Vanilla's
                    // render order is translate(pivot) * rotation * scale * cube, so applying
                    // scale to each cube's origin + size (before rotation + pivot) reproduces it.
                    // Baked in {@link #flushPendingBone} so the scale is tied to the cubes it
                    // applies to and resets when the next addOrReplaceChild finalises the bone.
                    if (methodInsn.desc.startsWith("(F") && !methodInsn.desc.startsWith("(FF")) {
                        requireStack(state, 1, "PartPose.scaled(F)");
                        state.pendingScale = popFloatWithDiagnostics(state, "PartPose.scaled(F)");
                    }
                }
                default -> { }
            }
        }

        /**
         * Closes the current pending bone: composes parent pivot + scale with the child's local
         * values (vanilla renders children with {@code T(parent.pivot) * S(parent.scale) *
         * T(child.pivot) * S(child.scale) * cube}), builds the bone JSON, records meta for
         * future children, then resets all pending state for the next {@code addOrReplaceChild}.
         */
        private static void flushPendingBone(@NotNull ParseState state) {
            // Prefer the snapshot taken at CubeListBuilder.create(); fall back to pendingPartName
            // for models that set the name immediately before addOrReplaceChild (no builder
            // chain - rare, but cheap to support).
            String name = state.boneName != null ? state.boneName : state.pendingPartName;
            if (name != null && !state.pendingCubes.isEmpty()) {
                // Flatten parent-child hierarchy at parse time: a child bone's world pivot
                // and world scale fold in the parent's already-flattened values. Vanilla renders
                // children with pose T(parent.pivot) * S(parent.scale) * T(child.pivot) * S(child.scale)
                // then draws child cubes. Ignoring parent rotation (none of our current sources use
                // a rotated parent with children), that collapses to
                //   world_pivot = parent.world_pivot + parent.world_scale * child.local_pivot
                //   world_scale = parent.world_scale * child.local_scale
                float[] worldPivot = state.pendingPivot;
                float worldScale = state.pendingScale;
                if (state.parentBone != null) {
                    BoneMeta parent = state.boneMeta.get(state.parentBone);
                    if (parent != null) {
                        worldPivot = new float[]{
                            parent.pivot[0] + parent.scale * state.pendingPivot[0],
                            parent.pivot[1] + parent.scale * state.pendingPivot[1],
                            parent.pivot[2] + parent.scale * state.pendingPivot[2]
                        };
                        worldScale = parent.scale * state.pendingScale;
                    }
                }
                state.bones.add(name, buildBone(worldPivot, state.pendingRotation, worldScale, state.pendingCubes));
                state.boneMeta.put(name, new BoneMeta(worldPivot, worldScale));
                state.lastFlushedBone = name;
            }
            state.pendingPartName = null;
            state.boneName = null;
            state.parentBone = null;
            state.pendingCubes = Concurrent.newList();
            state.pendingPivot = new float[]{ 0, 0, 0 };
            state.pendingRotation = new float[]{ 0, 0, 0 };
            state.pendingUv = new int[]{ 0, 0 };
            state.pendingScale = 1f;
        }

        /**
         * Strips the trailing {@code .class} suffix from a zip entry path to recover the
         * corresponding JVM internal name.
         */
        private static @NotNull String stripClassSuffix(@NotNull String classEntry) {
            return classEntry.endsWith(".class") ? classEntry.substring(0, classEntry.length() - ".class".length()) : classEntry;
        }

        /** Mutable parse state threaded through one top-level method parse (plus any inlined invokestatic targets). */
        private static final class ParseState {

            final @NotNull ConcurrentList<Number> numStack = Concurrent.newList();

            /**
             * Int values to substitute for {@code ILOAD_N} parameters when evaluating branches.
             * {@code paramIntValues[N]} is pushed onto {@link #branchStack} whenever an iload
             * references slot {@code N}, so the subsequent {@code IFEQ} / {@code IFNE} pops a
             * concrete value and jumps (or not). {@code null} disables branch evaluation -
             * the parser falls back to its default linear walk and lets both sides of any
             * conditional land on {@link #numStack}.
             */
            int @Nullable [] paramIntValues;

            /** Pushed by ILOAD when the slot maps to a paramIntValues entry; consumed by IFEQ / IFNE. */
            final @NotNull ConcurrentList<Integer> branchStack = Concurrent.newList();

            /** Most recent ldc String - tracks both bone names and inner cube names. */
            @Nullable String pendingPartName;

            /** Snapshot of {@link #pendingPartName} at CubeListBuilder.create(); preserved across inner ldc Strings from addBox(String, ...) variants. */
            @Nullable String boneName;

            /** Parent bone name captured from {@link #nextParent} at CubeListBuilder.create(). */
            @Nullable String parentBone;

            /** Parent bone captured from the most recent aload_N; consumed by CubeListBuilder.create(). */
            @Nullable String nextParent;

            /** Most recently flushed bone; the next astore_N after flush binds it to that slot. */
            @Nullable String lastFlushedBone;

            /** JVM local-variable slot -> bone name that was stored there via astore_N. */
            final @NotNull ConcurrentMap<Integer, String> localSlotBone = Concurrent.newMap();

            /** JVM local-variable slot -> captured CubeListBuilder cubes, for builders reused by multiple addOrReplaceChild calls. */
            final @NotNull ConcurrentMap<Integer, ConcurrentList<float[]>> slotToCubes = Concurrent.newMap();

            /** Flattened pivot + scale for each flushed bone, used to resolve child inheritance. */
            final @NotNull ConcurrentMap<String, BoneMeta> boneMeta = Concurrent.newMap();

            /** Cubes accumulated for the current builder chain, flushed by the next {@code addOrReplaceChild}. */
            @NotNull ConcurrentList<float[]> pendingCubes = Concurrent.newList();

            /** Current {@code texOffs(u, v)} values used by subsequent {@code addBox} variants that omit inline UV. */
            int @NotNull [] pendingUv = { 0, 0 };

            /** Current {@code PartPose} pivot for the next bone flush; defaults to origin. */
            float @NotNull [] pendingPivot = { 0, 0, 0 };

            /** Current {@code PartPose} rotation (Euler degrees) for the next bone flush. */
            float @NotNull [] pendingRotation = { 0, 0, 0 };

            /** Uniform scale from {@code PartPose.scaled}; {@code 1f} when no scale was applied. */
            float pendingScale = 1f;

            /** Accumulated per-bone JSON objects keyed by bone name. Written to the final model. */
            final @NotNull JsonObject bones = new JsonObject();

            /** Texture width extracted from {@code LayerDefinition.create(mesh, W, H)}; defaults to 64. */
            int texWidth = 64;

            /** Texture height extracted from {@code LayerDefinition.create(mesh, W, H)}; defaults to 64. */
            int texHeight = 64;

            /** The top-level source whose bytecode is being parsed. Used to tag diagnostics. */
            @Nullable Source currentSource;

            /** Diagnostics sink for strict-mode surfacing of silent failures. */
            @Nullable Diagnostics diagnostics;

            /** Set after the first overflow warn so a single parse doesn't spam the log. */
            boolean overflowWarned;

        }

        /** Parent lookup data: the bone's pivot and scale in world-flattened form. */
        private record BoneMeta(float @NotNull [] pivot, float scale) {}

        /**
         * Builds the JSON object for one bone from its flattened pivot, rotation, scale, and
         * cube list. The output shape matches what {@code EntityModelData}'s Gson binding expects.
         */
        private static @NotNull JsonObject buildBone(float @NotNull [] pivot, float @NotNull [] rotation, float scale, @NotNull ConcurrentList<float[]> cubes) {
            JsonObject bone = new JsonObject();
            bone.add("pivot", floatArray(pivot));
            bone.add("rotation", floatArray(rotation));
            if (scale != 1f)
                bone.addProperty("scale", scale);

            JsonArray cubeArray = new JsonArray();
            for (float[] c : cubes) {
                JsonObject cube = new JsonObject();
                cube.add("origin", floatArray(c[0], c[1], c[2]));
                cube.add("size", floatArray(c[3], c[4], c[5]));

                JsonArray uv = new JsonArray();
                uv.add((int) c[6]);
                uv.add((int) c[7]);
                cube.add("uv", uv);

                cube.addProperty("inflate", 0.0);
                cube.addProperty("mirror", false);
                cube.add("face_uv", new JsonObject());
                cubeArray.add(cube);
            }
            bone.add("cubes", cubeArray);
            return bone;
        }

        /** Builds a {@link JsonArray} from a variadic float list. */
        private static @NotNull JsonArray floatArray(float @NotNull ... values) {
            JsonArray arr = new JsonArray();
            for (float v : values) arr.add(v);
            return arr;
        }

        /**
         * Decodes an int or float literal from the instruction, returning the boxed numeric
         * value or {@code null} when the node is not a compile-time numeric push. The geometry
         * walker tracks these on a single {@code Number}-typed stack so a downstream
         * {@code addBox(FFFFFF)} can pop floats from the same list that earlier collected ints
         * for an {@code addBox(name,FFFIIIII)} variant.
         */
        private static @Nullable Number readNumericLiteral(@NotNull AbstractInsnNode node) {
            Integer asInt = AsmKit.readIntLiteral(node);
            if (asInt != null) return asInt;
            return AsmKit.readFloatLiteral(node);
        }

        /**
         * Sentinel value pushed onto {@link ParseState#numStack} for {@code FLOAD} / {@code DLOAD}
         * / {@code LLOAD} and for {@code ILOAD} slots without a known {@code paramIntValues}
         * entry. When a builder-dispatch site pops one of these via
         * {@link #popIntWithDiagnostics} / {@link #popFloatWithDiagnostics}, the parser surfaces
         * a {@code WARN:} identifying the entity id and pop site - the marker resolves to {@code 0}
         * but the developer sees that a computed local slipped through the literal-only
         * assumption instead of silently baking a zero into the output cube.
         * <p>
         * {@link Number#intValue()} / {@link Number#floatValue()} return {@code 0} so any caller
         * that pops without going through the diagnostics-aware helpers still gets the same
         * zero-fill behaviour as before Task 20.
         */
        private static final @NotNull Number NON_LITERAL = new NonLiteralMarker();

        /**
         * {@link Number} subclass that stands in on {@link ParseState#numStack} for values the
         * parser cannot resolve to a compile-time literal. Numeric accessors return zero so
         * callers that bypass the diagnostics-aware pops still see the pre-existing zero-fill
         * behaviour.
         */
        private static final class NonLiteralMarker extends Number {
            @Override public int intValue() { return 0; }
            @Override public long longValue() { return 0L; }
            @Override public float floatValue() { return 0f; }
            @Override public double doubleValue() { return 0d; }
            @Override public @NotNull String toString() { return "<non-literal>"; }
        }

        /**
         * {@link #popInt} with a {@link NonLiteralMarker} check that surfaces a {@code WARN:}
         * through {@link ParseState#diagnostics} identifying the entity id and the dispatch
         * site. Used by builder handlers whose coord/uv arg is expected to be a literal; when
         * a method was compiled with the value in a local variable populated by computation,
         * the resulting zero-fill is called out instead of silently baked.
         */
        private static int popIntWithDiagnostics(@NotNull ParseState state, @NotNull String where) {
            if (state.numStack.isEmpty()) return 0;
            Number top = state.numStack.remove(state.numStack.size() - 1);
            if (top instanceof NonLiteralMarker) {
                warnNonLiteral(state, where);
                return 0;
            }
            return top.intValue();
        }

        /** Float-typed counterpart of {@link #popIntWithDiagnostics}. */
        private static float popFloatWithDiagnostics(@NotNull ParseState state, @NotNull String where) {
            if (state.numStack.isEmpty()) return 0f;
            Number top = state.numStack.remove(state.numStack.size() - 1);
            if (top instanceof NonLiteralMarker) {
                warnNonLiteral(state, where);
                return 0f;
            }
            return top.floatValue();
        }

        /**
         * Emits the {@code WARN:} entry shared by {@link #popIntWithDiagnostics} and
         * {@link #popFloatWithDiagnostics} when a {@link NonLiteralMarker} is consumed. Silent
         * no-op when no diagnostic sink is attached.
         */
        private static void warnNonLiteral(@NotNull ParseState state, @NotNull String where) {
            if (state.diagnostics == null || state.currentSource == null) return;
            state.diagnostics.warn(
                "%s at %s: non-literal argument consumed - a local variable populated from a computation, resolved to 0",
                state.currentSource.entityId(), where
            );
        }

    }

    /**
     * Converts parsed entity model JSON (bones/cubes with box UV) into block model elements
     * JSON (from/to/faces with per-face UV).
     * <p>
     * The conversion is fully derived from the vanilla {@code ModelPart$Cube} polygon layout:
     * each cube produces eight corner positions in entity space, paired with the per-vertex UVs
     * vanilla assigns to each of the six entity faces (the per-vertex UV map is fixed by
     * {@code ModelPart$Polygon}'s vertex-to-UV order). The full transform chain
     * (bone rotation + pivot + inventory transform, or a Y-flip when no inventory transform is
     * present) is applied to the eight corners; the resulting axis-aligned block-space face is
     * resolved by snapping the transformed face normal to the closest cardinal direction. Each
     * surviving entity face emits a single block face whose UV rectangle and rotation tag are
     * derived from where {@code (uMin, vMin)} lands among the block face's TL/BL/BR/TR corners.
     * <p>
     * No per-face direction tables or hardcoded rotations are required - the algorithm handles
     * arbitrary axis-aligned rotations of bones and inventory transforms uniformly.
     */
    @UtilityClass
    static class BlockModelConverter {

        // The per-model inventory transform tuples and tinted-id set were removed in PR 2 -
        // they now come from {@link InventoryTransformDecomposer} (bytecode-driven) and
        // {@link TintDiscovery} respectively. The block-level constants below are kept as a
        // diff-friendly reference of the 26.1 shapes, but are not evaluated at runtime.
        /*
        private static final @NotNull java.util.Set<String> TINTED_MODEL_IDS = java.util.Set.of(
            "minecraft:banner_flag",
            "minecraft:wall_banner_flag"
        );

        private static final @NotNull Map<String, float[]> INVENTORY_TRANSFORMS = Map.ofEntries(
            // BedRenderer: translate(0, 9, 0) * Rx(90°) in model units
            Map.entry("minecraft:bed_head", new float[]{ 0, 9, 0, 90, 0, 0 }),
            Map.entry("minecraft:bed_foot", new float[]{ 0, 9, 0, 90, 0, 0 }),
            // ShulkerBoxRenderer: translate(0.5, 0.5, 0.5) * scale(1, -1, -1) * translate(0, -1, 0)
            // in block units. scale(1, -1, -1) is Rx(180), and in our "Rx then translate" form the
            // two translates fold into translate(8, 24, 8): +8 on all axes to shift from the
            // centered frame back to block-corner-at-origin, and an extra +16 on Y because
            // vanilla's inner translate(0, -1, 0) is applied before the flip (post-flip this
            // becomes +16 px, which together with the +8 centering yields +24).
            Map.entry("minecraft:shulker_box", new float[]{ 8, 24, 8, 180, 0, 0 }),
            // SkullBlockRenderer: translate(0.5, 0, 0.5) * scale(-1, -1, 1) * translate(-0.5, 0, -0.5).
            // scale(-1, -1, 1) ≡ Rz(180), which combined with the translate pair centres the
            // skull at x/z block-centre with Y flipping the Y-DOWN source to Y-UP. Our converter's
            // inv-transform path uses Rx, not Rz, but Rx(180) is the equivalent for a cube
            // centred on the X axis: flips Y and Z. Since the head cube spans z=-4..4 (symmetric
            // around z=0), the Z-flip is a no-op on the bbox, leaving a clean Y-flip. The
            // translate(+8, 0, +8) then centres the head at (4..12, 0..8, 4..12).
            //
            // Critical: this must live in INVENTORY_TRANSFORMS (not rely on the default Y-flip)
            // because the default Y-flip doesn't translate X/Z and would leave the cube at
            // (-4..4, 0..8, -4..4), escaping the 0..16 block bbox and triggering the runtime
            // recenterAndFit that compresses the tile to one-face visibility.
            Map.entry("minecraft:skull_head", new float[]{ 8, 0, 8, 180, 0, 0 }),
            Map.entry("minecraft:skull_humanoid_head", new float[]{ 8, 0, 8, 180, 0, 0 }),
            // Piglin skull: same Rx(180) + translate(+8, 0, +8) as the simple skull - head cube
            // and ears all stay inside the block bbox.
            Map.entry("minecraft:skull_piglin_head", new float[]{ 8, 0, 8, 180, 0, 0 }),
            // Dragon skull: tz=1.25 instead of 8 shifts the whole model +6.75 in post-invYRot
            // block-space Z so the bbox midpoint (snout extending to z=-10 + head cube at z=0.5..12.5
            // under the simple {8,0,8,180,0,0} transform) lands at block centre 8. Without this
            // the bbox midpoint is ~1.25 and recenterAndFit's recentering pushes the head to the
            // back corner of the tile, with the snout clipping off the near corner. Post-shift
            // the bbox is symmetric around block centre; recenterAndFit only scales by ~0.99 and
            // doesn't shift, so head + snout render centred in the atlas tile with the snout
            // naturally extending toward +z (camera-facing) like vanilla's inventory icon.
            Map.entry("minecraft:skull_dragon_head", new float[]{ 8, 0, 1.25f, 180, 0, 0 }),
            // DecoratedPotRenderer authors cubes in block-space Y-up (neck rim at y=17..20,
            // lid/base decals at y=16/y=0), and its runtime modelTransformation is just a Y-rotation
            // around block center for facing - no translate or Y-flip. A neutral inventory transform
            // (all zeros) skips the default {@code cy = -cy} reflection path so cubes land where
            // vanilla renders them. The neck rim extending past y=16 triggers the multi-block
            // recenterAndFit pass at render time.
            Map.entry("minecraft:decorated_pot", new float[]{ 0, 0, 0, 0, 0, 0 }),
            Map.entry("minecraft:decorated_pot_sides", new float[]{ 0, 0, 0, 0, 0, 0 }),
            // ConduitRenderer: translate(0.5, 0.5, 0.5) + rotateY(activeRotation), no Y-flip
            // (conduit authored as 6x6x6 cube centred at origin). Baking pitch=0 skips the
            // default {@code cy = -cy} reflection (cube is symmetric around origin so a flip is
            // a no-op) and translates to block centre so the shell lands at (5..11) on each axis.
            Map.entry("minecraft:conduit", new float[]{ 8, 8, 8, 0, 0, 0 }),
            // AbstractSignRenderer (StandingSignRenderer): translate(0.5, 0.5, 0.5) *
            // rotateY(-yaw) * scale(2/3, -2/3, -2/3) in block units. The 2/3 scale shrinks the
            // authored sign (24-wide board, taller than a block) to fit a single tile. The
            // negative Y/Z scales compose with a uniform positive scale into {@code Rx(180) *
            // scale(2/3)} - baking uniform 2/3 in slot 6 plus pitch=180 + translate(8,8,8)
            // matches vanilla's matrix composition exactly. Yaw=0 for the default iso render.
            Map.entry("minecraft:sign", new float[]{ 8, 8, 8, 180, 0, 0, 0.6666667f }),
            // HangingSignRenderer: translate(0.5, 0.9375, 0.5) * rotateY(-yaw) *
            // translate(0, -0.3125, 0) * scale(1, -1, -1). Folds to translate(0.5, 0.625, 0.5)
            // for yaw=0, i.e. model-units translate(8, 10, 8), plus Rx(180) for the Y/Z flips.
            // No uniform shrink - hanging sign is authored to fit within a block already.
            Map.entry("minecraft:hanging_sign", new float[]{ 8, 10, 8, 180, 0, 0 }),
            // BannerRenderer.modelTransformation: Transformation(MODEL_TRANSLATION,
            // Axis.YP.rotationDegrees(-yaw), MODEL_SCALE, null) where MODEL_TRANSLATION =
            // (0.5, 0, 0.5) and MODEL_SCALE = (2/3, -2/3, -2/3). Same decomposition as the
            // standing sign: positive uniform 2/3 + Rx(180) for the Y/Z sign flips, with
            // translate(8, 0, 8) to land at block centre on X/Z (Y stays at 0 because vanilla
            // doesn't translate the banner up - the pole extends from y=0 down to y=-42 in
            // model units, flipped up post-Rx). Same transform for pole+bar (`minecraft:banner`)
            // and the flag (`minecraft:banner_flag`) since both are rendered under the same
            // PoseStack in BannerRenderer.submitBanner.
            Map.entry("minecraft:banner", new float[]{ 8, 0, 8, 180, 0, 0, 0.6666667f }),
            Map.entry("minecraft:banner_flag", new float[]{ 8, 0, 8, 180, 0, 0, 0.6666667f }),
            // Wall banner variants share the same pose decomposition - the BannerRenderer
            // runs wall and standing variants through identical MODEL_SCALE + MODEL_TRANSLATION.
            // The wall-specific geometry difference (no pole, flag pivoted at (0, -20.5, 10.5)
            // instead of (0, -44, 0)) comes from the BannerModel / BannerFlagModel branch we
            // parse, not from a separate render transform.
            Map.entry("minecraft:wall_banner", new float[]{ 8, 0, 8, 180, 0, 0, 0.6666667f }),
            Map.entry("minecraft:wall_banner_flag", new float[]{ 8, 0, 8, 180, 0, 0, 0.6666667f })
        );
        */

        /**
         * Converts all parsed entity models into a JSON object containing block model elements
         * keyed by entity model id. The {@code inventoryTransforms} and {@code tintedIds}
         * parameters come from {@link InventoryTransformDecomposer} (merged with the overrides
         * file for {@code skull_dragon_head}) and {@link TintDiscovery} respectively - see
         * those classes for provenance.
         */
        static @NotNull JsonObject convert(
            @NotNull ConcurrentMap<String, JsonObject> entityModels,
            @NotNull Map<String, float[]> inventoryTransforms,
            @NotNull Set<String> tintedIds
        ) {
            JsonObject result = new JsonObject();
            result.addProperty("//", "Generated block model elements from entity model geometry. Run tooling/blockEntities to refresh.");

            for (Map.Entry<String, JsonObject> entry : entityModels.entrySet()) {
                String modelId = entry.getKey();
                JsonObject entityModel = entry.getValue();

                int texW = entityModel.has("textureWidth") ? entityModel.get("textureWidth").getAsInt() : 64;
                int texH = entityModel.has("textureHeight") ? entityModel.get("textureHeight").getAsInt() : 64;

                JsonObject bones = entityModel.getAsJsonObject("bones");
                if (bones == null) continue;

                float[] invTransform = inventoryTransforms.get(modelId);

                // Vanilla's per-type BlockEntityRenderer applies a yaw before drawing the model -
                // ChestRenderer's modelTransformation rotates around the BlockState facing, but the
                // chest item's display.gui transform is [30, 45, 0] (yaw 45) instead of the default
                // [30, 225, 0] (yaw 225), which puts the chest's +Z (model SOUTH) face on the
                // camera-facing side - that's where the lock cube lives. Our renderer uses the
                // standard [30, 225, 0] (block.json default), so we bake an equivalent +180 yaw
                // into the chest model here. Lock at z=14..15 lands at z=0..1, NORTH-visible.
                float invYRot = entityModel.has("inventory_y_rotation") ? entityModel.get("inventory_y_rotation").getAsFloat() : 0f;

                // For Y-UP source models (chest), the parser pre-flipped to Y-DOWN so the rest of
                // the pipeline sees a uniform convention - but that flip swaps which corner of the
                // cube vanilla calls "v19" (its yMin in source coords, which is the cube's
                // render-bottom corner and carries a specific UV via the SOUTH/NORTH/etc polygon
                // assignments). Our entityCorners array indexes vertices by the post-flip Y values,
                // so for Y-UP source we swap yLo <-> yHi to recover vanilla's labels.
                boolean isYUpSource = "UP".equals(entityModel.has("y_axis") ? entityModel.get("y_axis").getAsString() : "DOWN");
                boolean emitTintIndex = tintedIds.contains(modelId);

                JsonArray elements = new JsonArray();
                for (Map.Entry<String, JsonElement> boneEntry : bones.entrySet()) {
                    JsonObject bone = boneEntry.getValue().getAsJsonObject();
                    CubeTransform transform = CubeTransform.of(bone, invTransform, invYRot);

                    JsonArray cubes = bone.getAsJsonArray("cubes");
                    if (cubes == null) continue;

                    for (JsonElement cubeEl : cubes)
                        elements.add(buildElement(CubeDef.of(cubeEl.getAsJsonObject()), transform, isYUpSource, texW, texH, emitTintIndex));
                }

                JsonObject modelOutput = new JsonObject();
                modelOutput.addProperty("textureWidth", texW);
                modelOutput.addProperty("textureHeight", texH);
                modelOutput.add("elements", elements);
                result.add(modelId, modelOutput);
            }

            return result;
        }

        /**
         * Builds the eight entity-space corners (matching vanilla {@code ModelPart$Cube}'s vertex
         * ordering v19..v26), pushes them through the bone + inventory transform chain, then emits
         * a block element by mapping each entity face to a block face via per-vertex UV tracking.
         * <p>
         * When the bone rotation conjugated through the inventory + invYRot chain collapses to a
         * single block-axis rotation (piglin ears tilt Rz(±30°) under Rz(180)-composed inventory
         * transform = block Rz(±30°)), the element is emitted as an axis-aligned AABB with a
         * {@code rotation} directive so the tilt is preserved at render time instead of
         * axis-aligning the rotated cube into a bigger AABB that loses the tilt.
         */
        private static @NotNull JsonObject buildElement(@NotNull CubeDef cube, @NotNull CubeTransform transform, boolean isYUpSource, int texW, int texH, boolean emitTintIndex) {
            float[][] entityCorners = cube.entityCorners(isYUpSource);
            ElementRotationInfo blockRot = transform.computeBlockRotation();

            // When the bone rotation maps cleanly onto one of the block axes we output the
            // unrotated cube (positions run through scale + pivot + inv + invYRot, but NOT bone
            // rotation) and let the renderer apply the rotation at runtime. Otherwise we fall
            // back to the AABB of the fully-rotated cube - correct for 90°-symmetric cases like
            // bed legs where rotation + cube stay axis-aligned, but sacrifices tilt for
            // asymmetric cubes with non-90° bone rotations.
            float[][] blockCorners = new float[8][3];
            for (int i = 0; i < 8; i++)
                blockCorners[i] = blockRot != null
                    ? transform.applyNoBoneRot(entityCorners[i])
                    : transform.apply(entityCorners[i]);

            Box box = Box.of(blockCorners);
            // Block-model UV uses a 0..16 range independent of texture size; at render time the
            // runtime multiplies u by texW/16 and v by texH/16 to recover pixel coords. So our
            // pixel-space UVs must be scaled by 16/texW on the u axis and 16/texH on the v axis.
            // Non-square textures (e.g. SkullModel's 64x32) break when the V scale uses 16/texW.
            float scaleU = 16.0f / texW;
            float scaleV = 16.0f / texH;

            JsonObject faces = new JsonObject();
            for (ModelPartPolygonFace layout : ModelPartPolygonFace.values())
                emitBlockFace(layout.faceFor(cube, scaleU, scaleV), blockCorners, box, faces, emitTintIndex);

            JsonObject element = new JsonObject();
            JsonArray from = new JsonArray(); from.add(round2(box.minX())); from.add(round2(box.minY())); from.add(round2(box.minZ()));
            JsonArray to = new JsonArray(); to.add(round2(box.maxX())); to.add(round2(box.maxY())); to.add(round2(box.maxZ()));
            element.add("from", from);
            element.add("to", to);
            if (blockRot != null) {
                // Rotation origin is the bone pivot translated through the non-rotation chain,
                // matching how vanilla's ModelPart renders: translate(pivot) * R_bone * ...cube
                // - the pivot lands at the same point whether or not we apply the bone rotation.
                float[] origin = transform.applyNoBoneRot(new float[]{ 0f, 0f, 0f });
                JsonObject rotObj = new JsonObject();
                JsonArray originArr = new JsonArray();
                originArr.add(round2(origin[0])); originArr.add(round2(origin[1])); originArr.add(round2(origin[2]));
                rotObj.add("origin", originArr);
                rotObj.addProperty("axis", blockRot.axis);
                rotObj.addProperty("angle", blockRot.angle);
                element.add("rotation", rotObj);
            }
            element.add("faces", faces);
            return element;
        }

        /** Axis-aligned rotation the renderer should apply to an element - one of x/y/z in degrees. */
        private record ElementRotationInfo(@NotNull String axis, float angle) {}

        /**
         * Determines which block face the four transformed vertices of an entity face land on,
         * matches them to that block face's TL/BL/BR/TR corners (per
         * {@link BlockFace}'s vertex-index conventions), and emits a
         * single block face entry whose UV rectangle and rotation tag reproduce the per-vertex UVs.
         */
        private static void emitBlockFace(
            @NotNull EntityFace face,
            float @NotNull [] @NotNull [] blockCorners,
            @NotNull Box box,
            @NotNull JsonObject facesOut,
            boolean emitTintIndex
        ) {
            float[] p0 = blockCorners[face.vertexIndices[0]];
            float[] p1 = blockCorners[face.vertexIndices[1]];
            float[] p2 = blockCorners[face.vertexIndices[2]];
            float[] p3 = blockCorners[face.vertexIndices[3]];

            // Cross product of two edges gives the face normal; snapping to the cardinal axis
            // tells us which of the six block-face slots the polygon belongs to.
            float[] e1 = { p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2] };
            float[] e2 = { p3[0] - p0[0], p3[1] - p0[1], p3[2] - p0[2] };
            float[] normal = {
                e1[1] * e2[2] - e1[2] * e2[1],
                e1[2] * e2[0] - e1[0] * e2[2],
                e1[0] * e2[1] - e1[1] * e2[0]
            };
            // Zero-thickness cubes (flat decals like decorated_pot's lid/base) collapse 4 of the
            // 6 entity faces into degenerate line segments with zero-magnitude normals. Emitting
            // them would let the axis-snapping default to blockFaceIdx=0 (down) and clobber the
            // actual DOWN face's UV with a blank rectangle. Skip any face whose normal vanishes.
            float normalLenSq = normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2];
            if (normalLenSq < 1e-6f) return;
            float aNX = Math.abs(normal[0]), aNY = Math.abs(normal[1]), aNZ = Math.abs(normal[2]);
            int blockFaceIdx;
            if (aNY >= aNX && aNY >= aNZ) blockFaceIdx = normal[1] > 0 ? 1 : 0;
            else if (aNZ >= aNX) blockFaceIdx = normal[2] > 0 ? 3 : 2;
            else blockFaceIdx = normal[0] > 0 ? 5 : 4;

            Vector3f[] blockFaceCorners = BlockFace.values()[blockFaceIdx].corners(box);

            // For each transformed vertex of the entity face, look up the (uMin/uMax, vMin/vMax)
            // it carries (per the four-corner UV order: v[0]→(u1,v0), v[1]→(u0,v0), v[2]→(u0,v1), v[3]→(u1,v1)),
            // then attach that UV to whichever block-face corner the vertex landed at.
            float[][] entityFaceUv = {
                { face.u1, face.v0 },
                { face.u0, face.v0 },
                { face.u0, face.v1 },
                { face.u1, face.v1 }
            };
            float[][] blockCornerUv = new float[4][2];
            float[][] entityFacePos = { p0, p1, p2, p3 };
            for (int i = 0; i < 4; i++) {
                int blockCorner = matchCorner(entityFacePos[i], blockFaceCorners);
                blockCornerUv[blockCorner] = entityFaceUv[i];
            }

            UvRect uvRect = resolveUvRotation(blockCornerUv);

            // Zero-thickness cubes (decorated_pot's side panels) emit both entity NORTH and
            // SOUTH faces from the same quad - vanilla uses {@code EnumSet.of(Direction.NORTH)}
            // to render only one side but we don't model that filter. The "extra" face's UV
            // wraps past the texture edge (SOUTH formula gives u1 = u + 2w, which lands beyond
            // the 0..16 block-UV range for anything wider than half the texture). Skip those
            // out-of-bounds faces so we don't spray garbage texels onto the back of panels.
            if (uvRect.u0 < -0.01f || uvRect.u1 < -0.01f || uvRect.u0 > 16.01f || uvRect.u1 > 16.01f
                || uvRect.v0 < -0.01f || uvRect.v1 < -0.01f || uvRect.v0 > 16.01f || uvRect.v1 > 16.01f)
                return;

            JsonObject blockFace = new JsonObject();
            blockFace.addProperty("texture", "#entity");
            JsonArray uvArr = new JsonArray();
            uvArr.add(round2(uvRect.u0)); uvArr.add(round2(uvRect.v0)); uvArr.add(round2(uvRect.u1)); uvArr.add(round2(uvRect.v1));
            blockFace.add("uv", uvArr);
            if (uvRect.rotation != 0) blockFace.addProperty("rotation", uvRect.rotation);
            if (emitTintIndex) blockFace.addProperty("tintindex", 0);
            facesOut.add(BlockFace.values()[blockFaceIdx].direction(), blockFace);
        }

        /**
         * Resolves the four per-corner UVs at TL/BL/BR/TR (one of D4's eight orientations of a
         * UV rectangle) into a ({@code u0, v0, u1, v1}) rectangle plus a 0/90/180/270 rotation
         * tag. Implicit u/v flips are expressed by allowing {@code u0 > u1} or {@code v0 > v1}.
         */
        private static @NotNull UvRect resolveUvRotation(float @NotNull [] @NotNull [] blockCornerUv) {
            // For each candidate rotation R in {0, 90, 180, 270}, undo R by cyclic-shifting back
            // (the "old" canonical corners). If the undone corners satisfy vanilla's uvCorners
            // shape (TL/BL share u, TR/BR share u, TL/TR share v, BL/BR share v), the rotation
            // is correct - emit (u0=TL_old.u, v0=TL_old.v, u1=BR_old.u, v1=BR_old.v) and tag.
            for (int r = 0; r < 4; r++) {
                float[] tlOld = blockCornerUv[(0 - r + 4) % 4];
                float[] blOld = blockCornerUv[(1 - r + 4) % 4];
                float[] brOld = blockCornerUv[(2 - r + 4) % 4];
                float[] trOld = blockCornerUv[(3 - r + 4) % 4];
                if (approxEqual(tlOld[0], blOld[0]) && approxEqual(trOld[0], brOld[0])
                    && approxEqual(tlOld[1], trOld[1]) && approxEqual(blOld[1], brOld[1]))
                    return new UvRect(tlOld[0], tlOld[1], brOld[0], brOld[1], r * 90);
            }
            return new UvRect(0, 0, 0, 0, 0);
        }

        /** Returns the index (0=TL, 1=BL, 2=BR, 3=TR) of {@code blockFaceCorners} closest to {@code position}. */
        private static int matchCorner(float @NotNull [] position, @NotNull Vector3f @NotNull [] blockFaceCorners) {
            int best = 0;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float dx = position[0] - blockFaceCorners[i].x();
                float dy = position[1] - blockFaceCorners[i].y();
                float dz = position[2] - blockFaceCorners[i].z();
                float dist = dx * dx + dy * dy + dz * dz;
                if (dist < bestDist) { bestDist = dist; best = i; }
            }
            return best;
        }

        /** {@code true} when two floats are within {@code 1e-4} of each other. */
        private static boolean approxEqual(float a, float b) {
            return Math.abs(a - b) < 1e-4f;
        }

        /** Multiplies two 3x3 matrices, returning {@code a * b}. */
        private static double[][] matMul3(double[][] a, double[][] b) {
            double[][] r = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            return r;
        }

        /** Rounds {@code v} to 2 decimal places for readable JSON output. */
        private static float round2(double v) {
            return (float) (Math.round(v * 100.0) / 100.0);
        }

        /** A cube's origin, size, and UV offset as parsed from one entry of {@code bones[].cubes[]}. */
        private record CubeDef(float ox, float oy, float oz, float sw, float sh, float sd, int u, int v) {

            static @NotNull CubeDef of(@NotNull JsonObject cube) {
                JsonArray originArr = cube.getAsJsonArray("origin");
                JsonArray sizeArr = cube.getAsJsonArray("size");
                JsonArray uvArr = cube.getAsJsonArray("uv");
                return new CubeDef(
                    originArr.get(0).getAsFloat(), originArr.get(1).getAsFloat(), originArr.get(2).getAsFloat(),
                    sizeArr.get(0).getAsFloat(), sizeArr.get(1).getAsFloat(), sizeArr.get(2).getAsFloat(),
                    uvArr.get(0).getAsInt(), uvArr.get(1).getAsInt()
                );
            }

            /**
             * Returns the eight entity-space corners of this cube in vanilla {@code v19..v26}
             * bit-pattern order (xMax?, yMax?, zMax? selecting one of 8 corners).
             * <p>
             * Vanilla labels {@code v19..v22} as the "yMin" vertices and {@code v23..v26} as the
             * "yMax" vertices BY THE SOURCE Y CONVENTION. For Y-DOWN source the post-flip Y
             * coords match (yMin in source == yMin in our cube origin), but for Y-UP source the
             * parser pre-flipped Y and the meaning of yMin/yMax is inverted - swap yLo/yHi to
             * recover vanilla's labels.
             */
            float @NotNull [] @NotNull [] entityCorners(boolean yUpSource) {
                float yLo = yUpSource ? oy + sh : oy;
                float yHi = yUpSource ? oy      : oy + sh;
                return new float[][]{
                    { ox,      yLo, oz      },
                    { ox + sw, yLo, oz      },
                    { ox + sw, yHi, oz      },
                    { ox,      yHi, oz      },
                    { ox,      yLo, oz + sd },
                    { ox + sw, yLo, oz + sd },
                    { ox + sw, yHi, oz + sd },
                    { ox,      yHi, oz + sd }
                };
            }
        }

        /**
         * The full entity-space → block-space transform for one bone's cubes: the bone's
         * {@code Rz · Ry · Rx} rotation matrix, a uniform scale applied to cube-local positions
         * (from {@code PartPose.scaled}, flattened with any parent scale at parse time), the
         * pivot offset, the model's optional inventory transform (translate + X rotation), and
         * the inventory yaw applied around block center.
         * <p>
         * When no inventory transform is present the model is Y-flipped ({@code cy = -cy}) so
         * entity Y-down coordinates end up Y-up in block space. The inventory yaw (used by the
         * chest) is applied last around block center {@code (8, 8, 8)}.
         * <p>
         * The scale applies only to positions - cube {@code size} stays unscaled in the parsed
         * JSON so the vanilla {@code ModelPart$Polygon} UV layout (which indexes texture pixels
         * by the cube's pre-scale dimensions) continues to produce the correct texture region.
         */
        private record CubeTransform(
            double @Nullable [][] boneRot,
            float scale,
            float px, float py, float pz,
            float @Nullable [] invTransform,
            float invYRot
        ) {

            static @NotNull CubeTransform of(@NotNull JsonObject bone, float @Nullable [] invTransform, float invYRot) {
                JsonArray pivotArr = bone.getAsJsonArray("pivot");
                float px = pivotArr != null ? pivotArr.get(0).getAsFloat() : 0f;
                float py = pivotArr != null ? pivotArr.get(1).getAsFloat() : 0f;
                float pz = pivotArr != null ? pivotArr.get(2).getAsFloat() : 0f;

                JsonArray rotArr = bone.getAsJsonArray("rotation");
                float brx = 0, bry = 0, brz = 0;
                if (rotArr != null && rotArr.size() == 3) {
                    brx = rotArr.get(0).getAsFloat();
                    bry = rotArr.get(1).getAsFloat();
                    brz = rotArr.get(2).getAsFloat();
                }
                boolean hasBoneRot = brx != 0 || bry != 0 || brz != 0;

                float scale = bone.has("scale") ? bone.get("scale").getAsFloat() : 1f;

                // Bone rotation matrix: Rz * Ry * Rx (matches vanilla's Quaternionf.rotationZYX,
                // which applies X first, then Y, then Z).
                double rxR = Math.toRadians(brx), ryR = Math.toRadians(bry), rzR = Math.toRadians(brz);
                double[][] mRx = {{ 1, 0, 0 }, { 0, Math.cos(rxR), -Math.sin(rxR) }, { 0, Math.sin(rxR), Math.cos(rxR) }};
                double[][] mRy = {{ Math.cos(ryR), 0, Math.sin(ryR) }, { 0, 1, 0 }, { -Math.sin(ryR), 0, Math.cos(ryR) }};
                double[][] mRz = {{ Math.cos(rzR), -Math.sin(rzR), 0 }, { Math.sin(rzR), Math.cos(rzR), 0 }, { 0, 0, 1 }};
                double[][] boneRot = hasBoneRot ? matMul3(matMul3(mRz, mRy), mRx) : null;

                return new CubeTransform(boneRot, scale, px, py, pz, invTransform, invYRot);
            }

            /** Applies scale + pivot + inventory transform (or Y-flip) + inventory yaw, skipping bone rotation. */
            float @NotNull [] applyNoBoneRot(float @NotNull [] corner) {
                return applyChain(corner, false);
            }

            /** Applies scale, bone rotation, pivot, inventory transform (or Y-flip), then inventory yaw. */
            float @NotNull [] apply(float @NotNull [] corner) {
                return applyChain(corner, true);
            }

            private float @NotNull [] applyChain(float @NotNull [] corner, boolean withBoneRot) {
                float cx = corner[0] * scale, cy = corner[1] * scale, cz = corner[2] * scale;

                if (withBoneRot && boneRot != null) {
                    double rx2 = boneRot[0][0]*cx + boneRot[0][1]*cy + boneRot[0][2]*cz;
                    double ry2 = boneRot[1][0]*cx + boneRot[1][1]*cy + boneRot[1][2]*cz;
                    double rz2 = boneRot[2][0]*cx + boneRot[2][1]*cy + boneRot[2][2]*cz;
                    cx = (float) rx2; cy = (float) ry2; cz = (float) rz2;
                }

                cx += px; cy += py; cz += pz;

                if (invTransform != null) {
                    // Optional uniform scale at index 6 (defaults to 1). Applied BEFORE the
                    // Rx rotation + translate so it matches vanilla's matrix composition
                    // {@code translate * rotate * scale} exactly - signs use scale(2/3, -2/3, -2/3)
                    // which we decompose into uniform 2/3 + Rx(180) for Y/Z sign flips.
                    float invScale = invTransform.length > 6 && invTransform[6] != 0f ? invTransform[6] : 1f;
                    if (invScale != 1f) {
                        cx *= invScale; cy *= invScale; cz *= invScale;
                    }
                    float pitch = (float) Math.toRadians(invTransform[3]);
                    float cosP = (float) Math.cos(pitch), sinP = (float) Math.sin(pitch);
                    float ry = cy * cosP - cz * sinP;
                    float rz = cy * sinP + cz * cosP;
                    cy = ry + invTransform[1];
                    cz = rz + invTransform[2];
                    cx += invTransform[0];
                } else {
                    cy = -cy;
                }

                // Inventory yaw applied around the block center (8, 8, 8). For invYRot=180 this maps
                // (cx, cy, cz) -> (16-cx, cy, 16-cz), so the chest's lock cube at z=14..15 lands at
                // z=0..1 - the camera-facing side under the standard [30, 225, 0] gui rotation.
                if (invYRot != 0f) {
                    double yawR = Math.toRadians(invYRot);
                    double cosY = Math.cos(yawR), sinY = Math.sin(yawR);
                    float dx = cx - 8f, dz = cz - 8f;
                    cx = (float) (dx * cosY + dz * sinY) + 8f;
                    cz = (float) (-dx * sinY + dz * cosY) + 8f;
                }

                return new float[]{ cx, cy, cz };
            }

            /**
             * Conjugates the bone rotation through the linear part of the inventory + invYRot
             * chain, returning the resulting block-space rotation if and only if its axis lands
             * on x/y/z. Diagonal axes (bed legs use Rx(90)·Rz(90) ≡ 120° around (1,1,1)/√3) and
             * pure reflections return {@code null}, falling back to the axis-aligned-bbox path.
             * <p>
             * The linear transform is {@code T = R_invYRot · R_inv}, where {@code R_inv} is
             * {@code Rx(invPitch)} when an inventory transform is present, or the identity when
             * not (the {@code cy = -cy} reflection path is skipped here - pure reflections don't
             * cleanly conjugate into an axis-aligned rotation and fall through to the AABB path).
             * Bone rotation {@code R_bone} becomes block rotation {@code R_block = T · R_bone · T^T}.
             */
            @Nullable ElementRotationInfo computeBlockRotation() {
                if (boneRot == null || invTransform == null) return null;

                double pitch = Math.toRadians(invTransform[3]);
                double yaw = Math.toRadians(invYRot);
                double cp = Math.cos(pitch), sp = Math.sin(pitch);
                double cy = Math.cos(yaw), sy = Math.sin(yaw);
                double[][] rInv = {{ 1, 0, 0 }, { 0, cp, -sp }, { 0, sp, cp }};
                double[][] rYaw = {{ cy, 0, sy }, { 0, 1, 0 }, { -sy, 0, cy }};
                double[][] t = matMul3(rYaw, rInv);
                double[][] tT = { { t[0][0], t[1][0], t[2][0] }, { t[0][1], t[1][1], t[2][1] }, { t[0][2], t[1][2], t[2][2] } };
                double[][] rBlock = matMul3(matMul3(t, boneRot), tT);

                // Axis-angle via the standard trace formula. Angle is in [0, π]; the sign is
                // recovered from whichever principal axis the rotation axis vector aligns with.
                double trace = rBlock[0][0] + rBlock[1][1] + rBlock[2][2];
                double cosAngle = Math.max(-1.0, Math.min(1.0, (trace - 1.0) * 0.5));
                double angle = Math.acos(cosAngle);
                if (Math.abs(angle) < 1e-4) return null;

                double sinAngle = Math.sin(angle);
                if (Math.abs(sinAngle) < 1e-4) {
                    // angle near 180°: rotation axis found from the largest diagonal element
                    // of R + I. Rare; no model in our sources currently needs this, so punt.
                    return null;
                }
                double ax = (rBlock[2][1] - rBlock[1][2]) / (2 * sinAngle);
                double ay = (rBlock[0][2] - rBlock[2][0]) / (2 * sinAngle);
                double az = (rBlock[1][0] - rBlock[0][1]) / (2 * sinAngle);

                double aAbsX = Math.abs(ax), aAbsY = Math.abs(ay), aAbsZ = Math.abs(az);
                double axisTol = 1e-2;
                float degAngle = (float) Math.toDegrees(angle);
                if (aAbsX > 1 - axisTol && aAbsY < axisTol && aAbsZ < axisTol)
                    return new ElementRotationInfo("x", ax > 0 ? degAngle : -degAngle);
                if (aAbsY > 1 - axisTol && aAbsX < axisTol && aAbsZ < axisTol)
                    return new ElementRotationInfo("y", ay > 0 ? degAngle : -degAngle);
                if (aAbsZ > 1 - axisTol && aAbsX < axisTol && aAbsY < axisTol)
                    return new ElementRotationInfo("z", az > 0 ? degAngle : -degAngle);
                return null;
            }
        }

        /** Vanilla's per-face vertex-to-UV pairing, per {@code ModelPart$Polygon} constructor. */
        @FunctionalInterface
        private interface UvFormula {
            float @NotNull [] compute(int u, int v, @NotNull CubeDef c);
        }

        /**
         * The six faces of a vanilla Java {@code ModelPart$Cube}, each carrying its four vertex
         * indices into the shared 8-corner box layout and the UV formula that computes the face's
         * {@code (u0, v0, u1, v1)} rectangle from the cube's UV origin and dimensions. The formula
         * expresses vanilla's per-face box-UV layout (width {@code d+w+d+w} across, height
         * {@code d+h} down, with the two cap faces sharing the top strip).
         * <p>
         * The vertex ordering mirrors vanilla's {@code ModelPart$Polygon} constructor: {@code v[0]}
         * pairs with UV {@code (u1, v0)} (top-right of the texture rect), {@code v[1]} with
         * {@code (u0, v0)}, {@code v[2]} with {@code (u0, v1)}, {@code v[3]} with {@code (u1, v1)}.
         * This is a <i>different</i> convention from {@link BlockFace#corners(Box)}, whose indices
         * start at the face's top-left corner to feed {@code GeometryKit.addQuad}'s
         * {@code (topLeft, bottomLeft, bottomRight, topRight)} parameter order.
         * <p>
         * Both conventions index the same 8-corner box and are CCW; they differ by a 1-position
         * shift whose direction flips between {@code UP}/{@code DOWN} and the four vertical faces
         * because the two conventions disagree about which world corner is "first" per face. The
         * two cannot be unified - {@link BlockFace} serves block-model rendering via
         * {@code GeometryKit.addQuad}, while {@code ModelPartPolygonFace} serves the
         * bytecode-to-block-model conversion in {@link BlockModelConverter}, which must round-trip
         * vanilla's per-vertex UVs exactly.
         * <p>
         * The UV strip layout encoded here is also <i>different</i> from the Bedrock Edition
         * geo.json strip layout encoded in
         * {@link BlockFace#defaultUv(int[], float[], float, float, boolean)}. Those are two
         * distinct Mojang conventions and must stay separate.
         */
        private enum ModelPartPolygonFace {
            // DOWN: vertices v24, v23, v19, v20; UV (u+d, v, u+d+w, v+d)
            DOWN (new int[]{ 5, 4, 0, 1 }, (u, v, c) -> new float[]{ u + c.sd,                     v,                u + c.sd + c.sw,               v + c.sd }),
            // UP: vertices v21, v22, v26, v25; UV (u+d+w, v+d, u+d+w+w, v) - note v0 > v1
            UP   (new int[]{ 2, 3, 7, 6 }, (u, v, c) -> new float[]{ u + c.sd + c.sw,              v + c.sd,         u + c.sd + c.sw + c.sw,        v }),
            // NORTH: vertices v20, v19, v22, v21; UV (u+d, v+d, u+d+w, v+d+h)
            NORTH(new int[]{ 1, 0, 3, 2 }, (u, v, c) -> new float[]{ u + c.sd,                     v + c.sd,         u + c.sd + c.sw,               v + c.sd + c.sh }),
            // SOUTH: vertices v23, v24, v25, v26; UV (u+d+w+d, v+d, u+d+w+d+w, v+d+h)
            SOUTH(new int[]{ 4, 5, 6, 7 }, (u, v, c) -> new float[]{ u + c.sd + c.sw + c.sd,       v + c.sd,         u + c.sd + c.sw + c.sd + c.sw, v + c.sd + c.sh }),
            // WEST: vertices v19, v23, v26, v22; UV (u, v+d, u+d, v+d+h)
            WEST (new int[]{ 0, 4, 7, 3 }, (u, v, c) -> new float[]{ u,                            v + c.sd,         u + c.sd,                      v + c.sd + c.sh }),
            // EAST: vertices v24, v20, v21, v25; UV (u+d+w, v+d, u+d+w+d, v+d+h)
            EAST (new int[]{ 5, 1, 2, 6 }, (u, v, c) -> new float[]{ u + c.sd + c.sw,              v + c.sd,         u + c.sd + c.sw + c.sd,        v + c.sd + c.sh });

            private final int @NotNull [] vertexIndices;
            private final @NotNull UvFormula uvFormula;

            ModelPartPolygonFace(int @NotNull [] vertexIndices, @NotNull UvFormula uvFormula) {
                this.vertexIndices = vertexIndices;
                this.uvFormula = uvFormula;
            }

            /** Instantiates an {@link EntityFace} with the four UV edges scaled to texture-relative coordinates. */
            @NotNull EntityFace faceFor(@NotNull CubeDef c, float scaleU, float scaleV) {
                float[] uv = uvFormula.compute(c.u, c.v, c);
                return new EntityFace(vertexIndices, uv[0] * scaleU, uv[1] * scaleV, uv[2] * scaleU, uv[3] * scaleV);
            }
        }

        /**
         * One of the six entity faces of a cube: the four vertex indices into the shared
         * 8-corner box layout and the four UV-rectangle edges. The UVs
         * {@code (u1, v0), (u0, v0), (u0, v1), (u1, v1)} are paired with the four vertices
         * starting at the <b>texture top-right</b> corner and walking CCW, matching vanilla's
         * {@code ModelPart$Polygon} constructor - <i>not</i> {@link BlockFace}'s top-left-first
         * CCW convention. See {@link ModelPartPolygonFace} for why the two conventions
         * coexist.
         */
        private record EntityFace(int @NotNull [] vertexIndices, float u0, float v0, float u1, float v1) {}

        /** The resolved UV rectangle plus a rotation tag from per-corner UV sampling. */
        private record UvRect(float u0, float v0, float u1, float v1, int rotation) {}

    }

}
