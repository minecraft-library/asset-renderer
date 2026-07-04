package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.ToolingException;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.tooling.blockentity.BlockListDiscovery;
import lib.minecraft.renderer.tooling.blockentity.InventoryTransformDecomposer;
import lib.minecraft.renderer.tooling.blockentity.Source;
import lib.minecraft.renderer.tooling.blockentity.SourceDiscovery;
import lib.minecraft.renderer.tooling.blockentity.TintDiscovery;
import lib.minecraft.renderer.tooling.blockentity.YAxis;
import lib.minecraft.renderer.tooling.parser.GeometryParser;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.JsonOptional;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Entry point invoked by the {@code blockModels} Gradle task.
 *
 * <p>Downloads the deobfuscated Minecraft client jar, parses every block-entity model class
 * via {@link GeometryParser}, and writes the result to
 * {@code src/main/resources/lib/minecraft/renderer/block_models.json}.
 *
 * <p>Output composition:
 * <ul>
 *   <li><b>Geometry</b> - a parent-relative bone/cube tree (the same schema as
 *       {@code entity_geometry.json}, composed at render time via {@code BlockGeometryKit.buildFromBones})
 *       decomposed from each block-entity model class's layer factory bytecode
 *       ({@code createBodyLayer} / {@code createSingleBodyLayer} / {@code createHeadLayer} /
 *       {@code createFootLayer} / {@code createFlagLayer}). Bones stay in their native source frame;
 *       the {@link YAxis YAxis} marker travels with them and the render presentation applies the
 *       source-to-block Y orientation.</li>
 *   <li><b>Inventory transform</b> - extracted from each renderer's static factory via
 *       {@link InventoryTransformDecomposer
 *       InventoryTransformDecomposer}.</li>
 *   <li><b>Block list</b> - per-family registry walk via
 *       {@link BlockListDiscovery
 *       BlockListDiscovery}.</li>
 *   <li><b>Tint marker</b> - applied to entries whose renderer bytecode invokes a known tint
 *       accessor (see
 *       {@link TintDiscovery TintDiscovery}).</li>
 *   <li><b>Per-block atlas/GUI fields</b> - {@code iconRotation} (beds), {@code additive}
 *       (bells), and per-block {@code tint} (banners) pattern-matched onto block entries by
 *       {@code applyPerBlockFamilyFields}; baked directly into the output JSON.</li>
 * </ul>
 *
 * <p>The runtime pipeline reads the JSON via {@link BlockModelLoader}; the ASM walker is
 * never on the production classpath.
 *
 * @see BlockModelLoader
 * @see GeometryParser
 */
@UtilityClass
public final class ToolingBlockModels {

    /**
     * Fixed output path for the bundled block-entity catalog resource.
     */
    private static final @NotNull Path OUTPUT_PATH = Path.of("src/main/resources/lib/minecraft/renderer/block_models.json");

    /**
     * Shared Gson carrying the renderer's registered type adapters. Pretty-printing only affects
     * writes; reads are format-agnostic, so a single instance serves both parse and output.
     */
    private static final @NotNull Gson PRETTY_GSON = GsonSettings.defaults().mutate().isPrettyPrint().isHtmlEscaping(false).build().create();

    /**
     * Client-jar Minecraft version this generator targets; written to the JSON header for drift tracking.
     */
    private static final @NotNull String SOURCE_VERSION = "26.1";

    /**
     * Runs the generator.
     *
     * @param args optional {@code --lenient} flag to continue past WARN-level diagnostics
     * @throws IOException if the client jar cannot be downloaded or the JSON file cannot be written
     * @throws ToolingException in strict mode (the default) when any parse diagnostic reaches WARN+ severity
     */
    public static void main(String @NotNull [] args) throws IOException {
        List<String> argList = Arrays.asList(args);
        boolean lenient = argList.contains("--lenient");

        PipelineOptions options = PipelineOptions.defaults();
        Path jarPath = Pipeline.downloadJarToCache(options);

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
            Set<String> tinted = TintDiscovery.discover(zip, sources, entityIdToRenderer, diagnostics);

            System.out.printf("Discovered %d sources; parsing...%n", sources.size());
            // Relative bone emission: every block entity carries a parent-relative bone tree
            // (the same schema as entity_geometry.json), composed at render time via
            // BlockGeometryKit#buildFromBones with a presentation transform. No absolute-flatten
            // step - the former element pipeline (BlockModelConverter) is gone.
            ConcurrentMap<String, JsonObject> models = GeometryParser.parse(jarPath, sources, diagnostics);
            System.out.printf("Parsed %d / %d sources%n", models.size(), sources.size());

            // No bbox-recenter pass: the only model it ever shifted (skull_dragon_head, tz) is
            // recentred at render by BlockRenderer.recenterAndFit (its composed extent exceeds one
            // block), which centres the bbox midpoint regardless of any inventory-transform
            // translation - so the tooling-side tz shift is redundant.

            // Lenient mode prints every diagnostic for manual inspection. Strict mode (default)
            // only prints and then fails so the output stays visible in CI logs before the error.
            for (String entry : diagnostics.entries())
                System.err.println("  " + entry);

            if (!lenient && diagnostics.strictFailingCount() > 0)
                throw new ToolingException(
                    "Strict mode: %d parse diagnostic(s) at WARN+ severity. Rerun with --lenient to continue.",
                    diagnostics.strictFailingCount()
                );

            // Block entities without a decomposed inventory transform (chest, bell, the new
            // copper_golem_statue) bake vanilla's entity-render flip directly. Whether they take
            // the full scale(-1, -1, 1) flip is read from the item icon's display.gui roll (180 =
            // flip, 0 = the chest's real-yaw path); ids whose icon is a flat sprite default to the
            // flip. See InventoryTransformDecomposer#resolveEntityRenderFlips.
            Set<String> noInventoryModelIds = new LinkedHashSet<>();
            for (String modelId : models.keySet())
                if (!inventoryTransforms.containsKey(modelId)) noInventoryModelIds.add(modelId);
            Map<String, Boolean> entityRenderFlips = InventoryTransformDecomposer.resolveEntityRenderFlips(zip, noInventoryModelIds);
            // The additive bell_body is the one BE whose renderer (BellRenderer.submit) draws its
            // model with the raw block PoseStack - no scale(-1, -1, 1) entity flip. Its model is
            // authored in block space (bell_body pivot (8, 12, 8) -> cube x=5..11), so the cx=-cx
            // half of the flip would push it to x=-11..-5, off the block. Its item icon is a flat
            // item/generated sprite (no display.gui), which the resolver would otherwise default to
            // the flip; force it off so the bell hangs centred under the bar.
            entityRenderFlips.put("minecraft:bell_body", Boolean.FALSE);

            Map<String, String> bannerTintByBlockId = BlockListDiscovery.bannerTintByBlockId(zip, diagnostics);
            merged = buildMergedOutput(models, entityRenderFlips, blockList, inventoryTransforms, tinted, bannerTintByBlockId);
        }

        Files.createDirectories(OUTPUT_PATH.getParent());
        Files.writeString(OUTPUT_PATH,
            PRETTY_GSON.toJson(merged) + System.lineSeparator());
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
     *
     * @param zip the open client jar (unused directly here; reserved for future registry-walk resolution)
     * @param sources the discovered block-entity sources
     * @return an insertion-ordered {@code entityId -> rendererInternalName} map
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
     * Maps a single entity id to its renderer internal name for the inventory transform + tint
     * catalog sanity checks. This is the one place in the wire-up that statically names
     * renderers; a future PR could derive it from the same registry walk
     * {@link SourceDiscovery} already performs.
     *
     * @param entityId the block-entity model id (e.g. {@code minecraft:chest})
     * @return the renderer's internal (slash-separated) class name
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
     * Composes the unified {@code block_models.json} output. Parses the existing file (if
     * present) to preserve hand-curated fields ({@code blocks} variants, {@code parts}
     * shape) that are not yet auto-discovered, then overwrites the auto-derivable fields
     * ({@code model} geometry from the ASM parse, {@code y_axis} + {@code inventory_transform}
     * + {@code tinted} from the current Java literals) so re-running the task is idempotent.
     *
     * @param parsedEntityModels the parsed relative bone models keyed by entity-model id (carry
     *     {@code textureWidth}/{@code textureHeight}/{@code bones}/{@code y_axis}/{@code inventory_y_rotation})
     * @param entityRenderFlips the per-id entity-flip gate for inventory-transform-less models
     * @param blockList the per-entity block/parts catalog from {@link BlockListDiscovery}
     * @param inventoryTransforms the decomposed inventory transform tuples by id
     * @param tintedModelIds the ids flagged as tinted
     * @param bannerTintByBlockId the per-block-id dye map for banner {@code tint} fields
     * @return the merged root JSON object
     * @throws IOException if the existing output file cannot be read
     */
    private static @NotNull JsonObject buildMergedOutput(
        @NotNull ConcurrentMap<String, JsonObject> parsedEntityModels,
        @NotNull Map<String, Boolean> entityRenderFlips,
        @NotNull Map<String, BlockListDiscovery.EntityBlockMapping> blockList,
        @NotNull Map<String, float[]> inventoryTransforms,
        @NotNull Set<String> tintedModelIds,
        @NotNull Map<String, String> bannerTintByBlockId
    ) throws IOException {
        @Nullable JsonObject existing = null;
        if (Files.exists(OUTPUT_PATH)) {
            String raw = Files.readString(OUTPUT_PATH);
            try {
                existing = PRETTY_GSON.fromJson(raw, JsonObject.class);
            } catch (Exception ex) {
                System.err.println("  Warning: could not parse existing " + OUTPUT_PATH + " - writing fresh output");
            }
        }
        JsonObject existingModels = existing != null && existing.has("models")
            ? existing.getAsJsonObject("models")
            : new JsonObject();

        JsonObject root = new JsonObject();
        root.addProperty("//", mergedHeader());
        root.addProperty("source_version", SOURCE_VERSION);

        JsonObject models = new JsonObject();

        // Iterate in the existing file's key order when we have one (keeps diffs small across
        // regeneration passes); then append any newly discovered models that did not appear
        // in the existing file (e.g. a freshly added entity id from a MC version rev). The
        // blockList catalog is the authoritative source of which entity ids ship.
        LinkedHashSet<String> modelOrder = new LinkedHashSet<>();
        if (!existingModels.entrySet().isEmpty())
            modelOrder.addAll(existingModels.keySet());
        modelOrder.addAll(blockList.keySet());
        modelOrder.addAll(parsedEntityModels.keySet());

        for (String modelId : modelOrder) {
            if (modelId.equals("//")) continue;

            JsonObject parsedEntity = parsedEntityModels.get(modelId);
            if (parsedEntity == null) continue;

            // Every block entity emits its parent-relative bone tree, composed at render time via
            // buildFromBones; the render-time knobs (inventory_y_rotation, entity_flip, and the
            // inventory_transform tuple) travel as metadata rather than being folded into geometry.
            JsonObject modelOut = new JsonObject();
            modelOut.add("model", buildBonesSubobject(parsedEntity));

            String yAxis = parsedEntity.has("y_axis") ? parsedEntity.get("y_axis").getAsString() : "DOWN";
            modelOut.addProperty("y_axis", yAxis);
            modelOut.addProperty("inventory_y_rotation", JsonOptional.optFloat(parsedEntity, "inventory_y_rotation", 0f));
            modelOut.addProperty("entity_flip", entityRenderFlips.getOrDefault(modelId, Boolean.TRUE));

            float[] invTransform = inventoryTransforms.get(modelId);
            if (invTransform != null) {
                JsonArray arr = new JsonArray();
                for (float v : invTransform) arr.add(v);
                modelOut.add("inventory_transform", arr);
            }
            modelOut.addProperty("tinted", tintedModelIds.contains(modelId));

            // Block list + parts come from BlockListDiscovery; only fall back to existing
            // hand-curated arrays when discovery doesn't carry the entity.
            BlockListDiscovery.EntityBlockMapping catalogEntry = blockList.get(modelId);
            if (catalogEntry != null) {
                JsonArray parts = buildPartsArray(catalogEntry);
                if (parts != null) modelOut.add("parts", parts);
                JsonArray blocks = buildBlocksArray(catalogEntry, modelId, bannerTintByBlockId);
                if (blocks != null) modelOut.add("blocks", blocks);
            } else {
                JsonObject existingModel = existingModels.has(modelId) ? existingModels.getAsJsonObject(modelId) : null;
                if (existingModel != null) {
                    if (existingModel.has("parts")) modelOut.add("parts", existingModel.get("parts"));
                    if (existingModel.has("blocks")) modelOut.add("blocks", existingModel.get("blocks"));
                }
            }

            models.add(modelId, modelOut);
        }

        root.add("models", models);
        return root;
    }

    /**
     * Serialises {@code parts} entries to the JSON shape the loader expects. Entries with a
     * {@code null} offset and {@code null} texture emit just {@code {"model": ...}}; entries
     * with only an offset emit {@code {"model": ..., "offset": [x, y, z]}}; full entries emit
     * all three keys.
     *
     * @param entry the entity's block/parts catalog entry
     * @return the {@code parts} JSON array, or {@code null} when the entry carries no parts
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
     *
     * <p>Beyond the bare {@code blockId}/{@code textureId} pair, emits three per-block fields
     * derived from the entity-id family (which is itself bytecode-derived by
     * {@link BlockListDiscovery}'s family adapters):
     * <ul>
     *   <li>{@code iconRotation: 90} when {@code entityId == "minecraft:bed_head"}.</li>
     *   <li>{@code additive: true} when {@code entityId == "minecraft:bell_body"}.</li>
     *   <li>{@code tint: <DYE>} when the block id appears in {@code bannerTintByBlockId} (the
     *       map walked by {@link BlockListDiscovery#bannerTintByBlockId} from each banner /
     *       wall-banner block's {@code (Wall)BannerBlock(DyeColor, Properties)} constructor in
     *       {@code Blocks.<clinit>}).</li>
     * </ul>
     *
     * @param entry the entity's block/parts catalog entry
     * @param entityId the entity-model id (drives the per-block family fields)
     * @param bannerTintByBlockId the per-block-id dye map for banner {@code tint} fields
     * @return the {@code blocks} JSON array, or {@code null} when the entry has no blocks
     */
    private static @Nullable JsonArray buildBlocksArray(
        @NotNull BlockListDiscovery.EntityBlockMapping entry,
        @NotNull String entityId,
        @NotNull Map<String, String> bannerTintByBlockId
    ) {
        List<BlockListDiscovery.BlockMapping> blocks = entry.blocks();
        if (blocks.isEmpty()) return null;
        JsonArray arr = new JsonArray();
        for (BlockListDiscovery.BlockMapping b : blocks) {
            JsonObject block = new JsonObject();
            block.addProperty("blockId", b.blockId());
            block.addProperty("textureId", b.textureId());
            // A state-conditional model lists its blocks under the blockstate key that selects it
            // (the ceiling hanging sign's straight-chain mesh under {@code attached=true}); absent
            // for a block's default (primary) geometry.
            if (b.variant() != null) block.addProperty("variant", b.variant());
            applyPerBlockFamilyFields(block, b.blockId(), entityId, bannerTintByBlockId);
            arr.add(block);
        }
        return arr;
    }

    /**
     * Dispatches per-block atlas / tint fields off the bytecode-derived entity-id family. The
     * three render-pipeline policy fields ({@code iconRotation} on the bed family,
     * {@code additive} on the bell family) are emitted by family membership rather than by
     * lexical block-id matching; the data-derived {@code tint} field is read directly from the
     * banner-block {@code DyeColor} constructor-argument map walked by
     * {@link BlockListDiscovery#bannerTintByBlockId}.
     *
     * @param block the block JSON object mutated in place with the family fields
     * @param blockId the block id (looked up in {@code bannerTintByBlockId} for the tint field)
     * @param entityId the entity-model id whose family selects which fields apply
     * @param bannerTintByBlockId the per-block-id dye map for banner {@code tint} fields
     */
    private static void applyPerBlockFamilyFields(
        @NotNull JsonObject block,
        @NotNull String blockId,
        @NotNull String entityId,
        @NotNull Map<String, String> bannerTintByBlockId
    ) {
        if (entityId.equals("minecraft:bed_head")) {
            block.addProperty("iconRotation", 90);
            return;
        }
        if (entityId.equals("minecraft:bell_body")) {
            block.addProperty("additive", true);
            return;
        }
        if (entityId.equals("minecraft:banner") || entityId.equals("minecraft:wall_banner")) {
            String dye = bannerTintByBlockId.get(blockId);
            if (dye != null) block.addProperty("tint", dye);
        }
    }

    /**
     * Extracts the model-body subobject ({@code textureWidth}, {@code textureHeight}, {@code bones})
     * from a relative-parsed entity model for a bone-format family. Drops the parse-level
     * {@code y_axis} (which is re-emitted at entry level) and carries the parent-relative bone tree
     * verbatim - the same schema {@code entity_geometry.json} uses, consumed at load into
     * {@code EntityModelData}.
     *
     * @param relativeModel one relative-parsed entity model ({@code textureWidth}/{@code textureHeight}/{@code bones})
     * @return the model-body subobject carrying the texture dimensions and the relative bone tree
     */
    private static @NotNull JsonObject buildBonesSubobject(@NotNull JsonObject relativeModel) {
        JsonObject model = new JsonObject();
        if (relativeModel.has("textureWidth"))
            model.add("textureWidth", relativeModel.get("textureWidth"));
        if (relativeModel.has("textureHeight"))
            model.add("textureHeight", relativeModel.get("textureHeight"));
        if (relativeModel.has("bones"))
            model.add("bones", relativeModel.get("bones"));
        return model;
    }

    /**
     * Builds the human-readable header comment prepended to the generated JSON.
     *
     * @return the {@code "//"} header string describing the generator, layout, and golden-test guard
     */
    private static @NotNull String mergedHeader() {
        return "Generated by ToolingBlockModels (tooling/blockModels Gradle task). Unified "
            + "block-entity catalog keyed by entity-model id: each entry carries the ASM-extracted "
            + "geometry (a parent-relative bone/cube tree, the same schema as entity_geometry.json, "
            + "composed at render time via BlockGeometryKit.buildFromBones), metadata (y_axis source "
            + "convention, inventory_y_rotation GUI-facing yaw, entity_flip gate, inventory_transform "
            + "decomposed from the Renderer's PoseStack, tinted flag) that the render presentation "
            + "applies around the bones, optional sub-model parts with their render offsets, and the "
            + "list of block variants that render as this entity model along with their entity-texture "
            + "paths. Supersedes the former split between tile_entity_models.json (generated geometry) "
            + "and tile_entity_mappings.json (hand-edited block bindings); both source files are now "
            + "derived in one pass from the 26.1 client jar. Per-block atlas/GUI fields (iconRotation "
            + "on beds, additive on bells, per-block tint on banners) are pattern-matched onto block "
            + "entries by applyPerBlockFamilyFields at tooling time. "
            + "Run the tooling/blockModels Gradle task to refresh; BlockModelsGoldenTest "
            + "guards against silent drift via a SHA-256 over the canonical JSON.";
    }

}
