package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.model.EntityModelData.Bone;
import lib.minecraft.renderer.asset.model.EntityModelData.Cube;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.ToolingException;
import lib.minecraft.renderer.geometry.BlockFace;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.EntityFace;
import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.loader.BlockEntityLoader;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Quaternionf;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import lib.minecraft.renderer.tooling.blockentity.BlockListDiscovery;
import lib.minecraft.renderer.tooling.blockentity.InventoryTransformDecomposer;
import lib.minecraft.renderer.tooling.blockentity.Source;
import lib.minecraft.renderer.tooling.blockentity.SourceDiscovery;
import lib.minecraft.renderer.tooling.blockentity.TintDiscovery;
import lib.minecraft.renderer.tooling.blockentity.YAxis;
import lib.minecraft.renderer.tooling.entity.EntityLayerDefinitionResolver;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.FastTrig;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

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
 * Entry point invoked by the {@code blockEntities} Gradle task.
 *
 * <p>Downloads the deobfuscated Minecraft client jar, parses every block-entity model class
 * via {@link Parser}, and writes the result to
 * {@code src/main/resources/lib/minecraft/renderer/block_entities.json}.
 *
 * <p>Output composition:
 * <ul>
 *   <li><b>Geometry</b> - decomposed from each block-entity model class's
 *       {@code createBodyLayer} / {@code createSingleHeadLayer} bytecode. Y-axis normalised
 *       to the canonical Y-down convention via
 *       {@link YAxis YAxis}.</li>
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
 *       {@code applyPerBlockPatternFields}; baked directly into the output JSON.</li>
 * </ul>
 *
 * <p>The runtime pipeline reads the JSON via {@link BlockEntityLoader}; the ASM walker is
 * never on the production classpath.
 *
 * @see BlockEntityLoader
 * @see Parser
 */
@UtilityClass
public final class ToolingBlockEntities {

    /**
     * Fixed output path for the bundled block-entity catalog resource.
     */
    private static final @NotNull Path OUTPUT_PATH = Path.of("src/main/resources/lib/minecraft/renderer/block_entities.json");

    /**
     * Client-jar Minecraft version this generator targets; written to the JSON header for drift tracking.
     */
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
            ConcurrentMap<String, JsonObject> models = Parser.parse(jarPath, sources, diagnostics);
            System.out.printf("Parsed %d / %d sources%n", models.size(), sources.size());

            // Geometry-aware recenter pass: the InventoryTransformDecomposer extracts the bytecode
            // tuple of each BlockEntityRenderer.modelTransformation (e.g. skull_dragon_head shares
            // SkullBlockRenderer's {8, 0, 8, 180, 0, 0} with the simple skulls), but some models
            // bake an asymmetric extent into the LayerDefinition (dragon head's snout extending
            // to z=-10) that pushes the post-transform bbox off block centre. This pass walks the
            // parsed cubes, computes the bbox under the current tuple, and shifts tx/tz so the
            // bbox midpoint lands at (8, ?, 8) - replacing the historic hand-edited override.
            recenterInventoryTransformsByBbox(models, inventoryTransforms, diagnostics);

            // Lenient mode prints every diagnostic for manual inspection. Strict mode (default)
            // only prints and then fails so the output stays visible in CI logs before the error.
            for (String entry : diagnostics.entries())
                System.err.println("  " + entry);

            if (!lenient && diagnostics.strictFailingCount() > 0)
                throw new ToolingException(
                    "Strict mode: %d parse diagnostic(s) at WARN+ severity. Rerun with --lenient to continue.",
                    diagnostics.strictFailingCount()
                );

            JsonObject blockModels = BlockModelConverter.convert(models, inventoryTransforms, tinted);
            Map<String, String> bannerTintByBlockId = BlockListDiscovery.bannerTintByBlockId(zip, diagnostics);
            merged = buildMergedOutput(blockModels, models, blockList, inventoryTransforms, tinted, bannerTintByBlockId);
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
     * Recenter threshold in block units. Bbox midpoint deviations smaller than this stay put;
     * larger ones get a tx/tz shift to land the bbox at block centre. Empirically tuned: bed /
     * conduit / decorated_pot / signs / banners all land within ~1 of (8, ?, 8) under their
     * bytecode-derived transforms; skull_dragon_head's snout protrusion pushes it 6.75 units
     * off centre - a generous 1.0 threshold cleanly separates the two regimes.
     */
    private static final float INVENTORY_TRANSFORM_RECENTER_THRESHOLD = 1.0f;

    /**
     * Block bbox half-extent in model units. The recenter only fires when the bbox actually
     * escapes the {@code [0, 16]} block bbox on the deviating axis (i.e. an extent beyond the
     * standard cube). Wall-mounted entities (wall_banner, wall_banner_flag) have intentionally
     * off-centre bboxes that stay within the block - they should NOT be recentered.
     */
    private static final float BLOCK_BBOX_MAX = 16f;

    /**
     * Bbox-aware post-pass on the decomposer's {@code inventory_transform} tuples. The
     * decomposer walks {@code <Block>EntityRenderer.modelTransformation} bytecode to extract
     * the tuple {@code [tx, ty, tz, pitch, yaw, roll]} that vanilla applies before the standard
     * block atlas pose - but some entity models bake an asymmetric extent into their
     * {@code LayerDefinition} (dragon head's snout reaching z=-10 in model space) that the
     * renderer's symmetric transform alone cannot recover. This pass walks the parsed bone/
     * cube tree, applies the current tuple to all cube corners (scale, bone rotation, pivot,
     * uniform inv-scale, Rx(pitch), translate - skipping the {@code invYRot} step since
     * rotation around the block centre does not shift the bbox centroid), computes the
     * bbox midpoint, and if X or Z deviation from {@code 8} exceeds
     * {@link #INVENTORY_TRANSFORM_RECENTER_THRESHOLD} shifts the tuple's {@code tx}/{@code tz}
     * so the centroid lands at block centre.
     *
     * <p>For {@code minecraft:skull_dragon_head} this produces {@code tz = 1.25} from the
     * decomposer's shared-skull {@code tz = 8}, matching the historic hand-edited override.
     * For all other entities the deviation stays well below threshold and no shift applies -
     * verified against bed_head, bed_foot, shulker_box, the three other skulls, decorated_pot,
     * decorated_pot_sides, conduit, and the sign / hanging-sign family.
     */
    private static void recenterInventoryTransformsByBbox(
        @NotNull ConcurrentMap<String, JsonObject> parsedModels,
        @NotNull Map<String, float[]> inventoryTransforms,
        @NotNull Diagnostics diag
    ) {
        final float blockCentre = 8f;
        for (Map.Entry<String, float[]> entry : inventoryTransforms.entrySet()) {
            String entityId = entry.getKey();
            float[] tuple = entry.getValue();
            if (tuple.length < 4) continue;
            JsonObject model = parsedModels.get(entityId);
            if (model == null || !model.has("bones")) continue;

            float[] bbox = computeBboxAfterInventoryTransform(model, tuple);
            if (bbox == null) continue;

            float xMid = (bbox[0] + bbox[3]) * 0.5f;
            float zMid = (bbox[2] + bbox[5]) * 0.5f;
            float deltaX = blockCentre - xMid;
            float deltaZ = blockCentre - zMid;
            // Only recenter when the bbox actually escapes the block bbox on the deviating axis.
            // Wall-mounted entities (wall_banner, wall_banner_flag) have intentionally off-centre
            // bboxes that stay within [0, 16] and should keep their decomposer-derived tuple.
            boolean xEscapes = bbox[0] < 0 || bbox[3] > BLOCK_BBOX_MAX;
            boolean zEscapes = bbox[2] < 0 || bbox[5] > BLOCK_BBOX_MAX;

            float applyDx = Math.abs(deltaX) > INVENTORY_TRANSFORM_RECENTER_THRESHOLD && xEscapes ? deltaX : 0f;
            float applyDz = Math.abs(deltaZ) > INVENTORY_TRANSFORM_RECENTER_THRESHOLD && zEscapes ? deltaZ : 0f;

            if (applyDx != 0f || applyDz != 0f) {
                tuple[0] += applyDx;
                tuple[2] += applyDz;
                diag.info("inventory-transform recenter '%s': bbox X[%.2f,%.2f] Z[%.2f,%.2f] -> tx=%.2f tz=%.2f (delta x=%.2f z=%.2f)",
                    entityId, bbox[0], bbox[3], bbox[2], bbox[5], tuple[0], tuple[2], applyDx, applyDz);
            }
        }
    }

    /**
     * Returns {@code [xMin, yMin, zMin, xMax, yMax, zMax]} of all cube corners of {@code model}
     * after the bone scale + Rz·Ry·Rx rotation + pivot chain and the inventory transform
     * tuple's uniform-scale + Rx(pitch) + translate. Skips the {@code invYRot} step (it
     * rotates around the block centre and does not shift the bbox centroid). Returns
     * {@code null} when no cubes are present. Mirrors {@link BlockModelConverter.CubeTransform#applyChain}
     * for the corresponding chain ordering.
     */
    private static float @Nullable [] computeBboxAfterInventoryTransform(
        @NotNull JsonObject model,
        @NotNull float[] invTransform
    ) {
        JsonObject bones = model.getAsJsonObject("bones");
        if (bones == null) return null;

        float xMin = Float.POSITIVE_INFINITY, yMin = Float.POSITIVE_INFINITY, zMin = Float.POSITIVE_INFINITY;
        float xMax = Float.NEGATIVE_INFINITY, yMax = Float.NEGATIVE_INFINITY, zMax = Float.NEGATIVE_INFINITY;
        boolean anyCube = false;

        float invScale = invTransform.length > 6 && invTransform[6] != 0f ? invTransform[6] : 1f;
        float pitch = (float) Math.toRadians(invTransform[3]);
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);

        for (Map.Entry<String, JsonElement> boneEntry : bones.entrySet()) {
            if (!boneEntry.getValue().isJsonObject()) continue;
            JsonObject bone = boneEntry.getValue().getAsJsonObject();
            JsonArray cubes = bone.has("cubes") && bone.get("cubes").isJsonArray() ? bone.getAsJsonArray("cubes") : null;
            if (cubes == null || cubes.isEmpty()) continue;

            float boneScale = bone.has("scale") ? bone.get("scale").getAsFloat() : 1f;
            JsonArray pivotArr = bone.has("pivot") ? bone.getAsJsonArray("pivot") : null;
            float bpx = pivotArr != null ? pivotArr.get(0).getAsFloat() : 0f;
            float bpy = pivotArr != null ? pivotArr.get(1).getAsFloat() : 0f;
            float bpz = pivotArr != null ? pivotArr.get(2).getAsFloat() : 0f;

            JsonArray rotArr = bone.has("rotation") ? bone.getAsJsonArray("rotation") : null;
            double[][] boneRotMatrix = null;
            if (rotArr != null && rotArr.size() == 3) {
                double brx = Math.toRadians(rotArr.get(0).getAsFloat());
                double bry = Math.toRadians(rotArr.get(1).getAsFloat());
                double brz = Math.toRadians(rotArr.get(2).getAsFloat());
                if (brx != 0 || bry != 0 || brz != 0) boneRotMatrix = rotationZYX(brx, bry, brz);
            }

            for (JsonElement cubeEl : cubes) {
                if (!cubeEl.isJsonObject()) continue;
                JsonObject cube = cubeEl.getAsJsonObject();
                JsonArray oArr = cube.has("origin") ? cube.getAsJsonArray("origin") : null;
                JsonArray sArr = cube.has("size") ? cube.getAsJsonArray("size") : null;
                if (oArr == null || sArr == null) continue;
                float ox = oArr.get(0).getAsFloat(), oy = oArr.get(1).getAsFloat(), oz = oArr.get(2).getAsFloat();
                float sw = sArr.get(0).getAsFloat(), sh = sArr.get(1).getAsFloat(), sd = sArr.get(2).getAsFloat();

                for (int i = 0; i < 8; i++) {
                    float cx = (i & 1) == 0 ? ox : ox + sw;
                    float cy = (i & 2) == 0 ? oy : oy + sh;
                    float cz = (i & 4) == 0 ? oz : oz + sd;
                    cx *= boneScale; cy *= boneScale; cz *= boneScale;
                    if (boneRotMatrix != null) {
                        double nx = boneRotMatrix[0][0]*cx + boneRotMatrix[0][1]*cy + boneRotMatrix[0][2]*cz;
                        double ny = boneRotMatrix[1][0]*cx + boneRotMatrix[1][1]*cy + boneRotMatrix[1][2]*cz;
                        double nz = boneRotMatrix[2][0]*cx + boneRotMatrix[2][1]*cy + boneRotMatrix[2][2]*cz;
                        cx = (float) nx; cy = (float) ny; cz = (float) nz;
                    }
                    cx += bpx; cy += bpy; cz += bpz;
                    cx *= invScale; cy *= invScale; cz *= invScale;
                    float ry = cy * cosP - cz * sinP;
                    float rz = cy * sinP + cz * cosP;
                    cy = ry; cz = rz;
                    cx += invTransform[0]; cy += invTransform[1]; cz += invTransform[2];

                    if (cx < xMin) xMin = cx; if (cx > xMax) xMax = cx;
                    if (cy < yMin) yMin = cy; if (cy > yMax) yMax = cy;
                    if (cz < zMin) zMin = cz; if (cz > zMax) zMax = cz;
                    anyCube = true;
                }
            }
        }
        return anyCube ? new float[]{ xMin, yMin, zMin, xMax, yMax, zMax } : null;
    }

    /**
     * Builds the {@code Rz · Ry · Rx} rotation matrix matching vanilla's
     * {@code Quaternionf.rotationZYX}. Duplicates {@link BlockModelConverter.CubeTransform#of}'s
     * matrix construction so the recenter pass does not depend on the inner class.
     */
    private static double @NotNull [] @NotNull [] rotationZYX(double rxR, double ryR, double rzR) {
        double[][] mRx = {{ 1, 0, 0 }, { 0, Math.cos(rxR), -Math.sin(rxR) }, { 0, Math.sin(rxR), Math.cos(rxR) }};
        double[][] mRy = {{ Math.cos(ryR), 0, Math.sin(ryR) }, { 0, 1, 0 }, { -Math.sin(ryR), 0, Math.cos(ryR) }};
        double[][] mRz = {{ Math.cos(rzR), -Math.sin(rzR), 0 }, { Math.sin(rzR), Math.cos(rzR), 0 }, { 0, 0, 1 }};
        return mat3Mul(mat3Mul(mRz, mRy), mRx);
    }

    /**
     * 3x3 matrix multiply returning {@code a · b}.
     */
    private static double @NotNull [] @NotNull [] mat3Mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i][j] = a[i][0]*b[0][j] + a[i][1]*b[1][j] + a[i][2]*b[2][j];
        return r;
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
        @NotNull Set<String> tintedModelIds,
        @NotNull Map<String, String> bannerTintByBlockId
    ) throws IOException {
        @Nullable JsonObject existing = null;
        if (Files.exists(OUTPUT_PATH)) {
            String raw = Files.readString(OUTPUT_PATH);
            try {
                existing = new Gson().fromJson(raw, JsonObject.class);
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
        LinkedHashSet<String> entityOrder = new LinkedHashSet<>();
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
                JsonArray blocks = buildBlocksArray(catalogEntry, modelId, bannerTintByBlockId);
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

    /**
     * Builds the human-readable header comment prepended to the generated JSON.
     */
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
            + "the 26.1 client jar. Atlas/GUI fields (iconRotation, additive, per-block tint, "
            + "forced inventory_y_rotation) are pattern-matched onto block entries by "
            + "applyPerBlockPatternFields at tooling time. "
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

        private static final @NotNull String LAYER_DEFINITION = VanillaSourceClasses.LAYER_DEFINITION;
        private static final @NotNull String MESH_TRANSFORMER_DESC = "L" + VanillaSourceClasses.MESH_TRANSFORMER + ";";

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
            // Wired as an {@code additive}
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
                throw new ToolingException(ex, "Failed to read client jar '%s'", jarPath);
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
            state.paramFloatValues = source.paramFloatValues();
            // Pre-seed pendingInflate from the source's defaultInflate so factory methods that
            // take a {@code CubeDeformation} arg (instead of constructing one inline) emit cubes
            // with the call-site-provided inflate. The composite-overlay flow uses this for
            // {@code DROWNED_OUTER_LAYER} -> {@code DrownedModel.createBodyLayer(new
            // CubeDeformation(0.25F))}: the parser starts inside createBodyLayer where the 0.25
            // is invisible, but the synthetic Source carries it through {@code defaultInflate}.
            state.defaultInflate = source.defaultInflate();
            state.pendingInflate = source.defaultInflate();
            // Pre-seed meshTransformerScale from the resolver-captured chain on the synthetic
            // Source. The resolver picks up LayerDefinitions-level {@code .apply(MeshTransformer)}
            // chains that don't appear inline in the factory body (cat / horse) - the F lives
            // on a class-level static field or a local slot in {@code createRoots}, not in the
            // model's own {@code createBodyLayer}. Folds with inline {@code MeshTransformer.scaling}
            // captures during the walk so both layers compose correctly.
            state.meshTransformerScale = source.appliedMeshTransformerScale();
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

            applyRetainedNamesFilter(state);
            applyClearedBonesFilter(state);
            applyMeshTransformerScaling(state);

            if (state.bones.isEmpty()) return null;

            JsonObject model = new JsonObject();
            model.addProperty("textureWidth", state.texWidth);
            model.addProperty("textureHeight", state.texHeight);
            model.add("bones", state.bones);
            return model;
        }

        /**
         * Resolves a {@code GETSTATIC <owner>.<name> : MeshTransformer} reference back to the
         * scaling factor F by walking the owning class's {@code <clinit>}. Matches the canonical
         * pattern
         * <pre>
         *   ldc F
         *   invokestatic MeshTransformer.scaling(F)MeshTransformer
         *   putstatic &lt;name&gt; : MeshTransformer
         * </pre>
         * Tracks a tiny synthetic stack: an {@code LDC} of a {@code Float} pushes the float; an
         * {@code INVOKESTATIC} on {@code MeshTransformer.scaling} consumes the float and pushes a
         * sentinel "scaled" marker carrying F; a {@code PUTSTATIC} of a {@code MeshTransformer}
         * field consumes the marker and records {@code field -> F}. Any other instruction that
         * mutates a slot the walker tracks clears the marker - so non-canonical initialisers
         * (combined transformers from {@code invokedynamic}, math on F, etc.) are simply not
         * captured and the caller defaults to no scale.
         * <p>
         * Cached on {@link ParseState#resolvedMeshTransformers} keyed by
         * {@code "owner.name"} so repeat references inside one parse don't re-walk.
         *
         * @return F when the field's {@code <clinit>} initialiser is a literal
         *     {@code MeshTransformer.scaling(F)}; {@code null} for unhandled patterns
         */
        private static @Nullable Float resolveStaticMeshTransformer(
            @NotNull String owner, @NotNull String name, @NotNull ParseState state, @NotNull ZipFile zip
        ) {
            String key = owner + "." + name;
            if (state.resolvedMeshTransformers.containsKey(key))
                return state.resolvedMeshTransformers.get(key);

            ClassNode cls = AsmKit.loadClass(zip, owner);
            MethodNode clinit = cls != null ? AsmKit.findMethod(cls, AsmKit.CLINIT) : null;
            if (clinit == null) {
                state.resolvedMeshTransformers.put(key, null);
                return null;
            }

            Float pendingFloat = null;
            Float pendingScaled = null;
            for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
                int op = in.getOpcode();
                if (op < 0) continue; // labels / line numbers / frame
                if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Float f) {
                    pendingFloat = f;
                    pendingScaled = null;
                } else if (in instanceof MethodInsnNode mi
                    && op == Opcodes.INVOKESTATIC
                    && VanillaSourceClasses.MESH_TRANSFORMER.equals(mi.owner)
                    && "scaling".equals(mi.name)
                    && ("(F)" + MESH_TRANSFORMER_DESC).equals(mi.desc)
                    && pendingFloat != null) {
                    pendingScaled = pendingFloat;
                    pendingFloat = null;
                } else if (in instanceof FieldInsnNode fi
                    && op == Opcodes.PUTSTATIC
                    && MESH_TRANSFORMER_DESC.equals(fi.desc)
                    && fi.owner.equals(owner)) {
                    state.resolvedMeshTransformers.put(owner + "." + fi.name, pendingScaled);
                    pendingScaled = null;
                    pendingFloat = null;
                } else {
                    // Any unrelated instruction clears the synthetic stack so we don't accidentally
                    // bind a stale F to a putstatic that's preceded by other initialisation work.
                    pendingFloat = null;
                    // Keep pendingScaled across no-op-ish instructions so the canonical
                    // ldc/invokestatic/putstatic triplet still binds.
                }
            }

            // After the walk, the key is set if its putstatic was canonical; otherwise mark null.
            state.resolvedMeshTransformers.putIfAbsent(key, null);
            return state.resolvedMeshTransformers.get(key);
        }

        /**
         * Resolves a static {@code MeshTransformer} field whose {@code <clinit>} initialises it
         * via an {@code invokedynamic apply -> lambda$static$N} pair to the underlying
         * {@code modifyMesh(PartDefinition)V} method the lambda invokes. This is the canonical
         * vanilla pattern for transformers that mutate a {@link
         * net.minecraft.client.model.geom.builders.MeshDefinition} rather than wrap it with a
         * uniform scale - {@code DonkeyModel.DONKEY_TRANSFORMER} is the only example in vanilla
         * 26.1: it appends taller ear bones and adds {@code left_chest} / {@code right_chest}
         * to the base AbstractEquineModel mesh before the per-renderer scale is applied.
         *
         * <p>The expected {@code <clinit>} shape is
         * <pre>
         *   invokedynamic apply -&gt; lambda$static$N (LambdaMetafactory)
         *   putstatic     &lt;fieldName&gt; : MeshTransformer
         * </pre>
         * with {@code lambda$static$N} body
         * <pre>
         *   aload_0                    // MeshDefinition
         *   invokevirtual getRoot      // -&gt; PartDefinition
         *   invokestatic  modifyMesh   // (PartDefinition)V
         *   aload_0
         *   areturn
         * </pre>
         * Returns the {@code modifyMesh} {@link MethodNode} the caller can feed to
         * {@link #inlineStaticMethodBody}. Returns {@code null} for any non-matching shape
         * (different bootstrap, no lambda body, lambda doesn't invoke a
         * {@code (PartDefinition)V} static).
         */
        private static @Nullable MethodNode findStaticModifyMeshTarget(
            @NotNull String owner, @NotNull String fieldName, @NotNull ZipFile zip
        ) {
            ClassNode cls = AsmKit.loadClass(zip, owner);
            MethodNode clinit = cls != null ? AsmKit.findMethod(cls, AsmKit.CLINIT) : null;
            if (clinit == null) return null;

            InvokeDynamicInsnNode pendingIndy = null;
            for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
                if (AsmKit.isPseudoNode(in)) continue;
                if (in instanceof InvokeDynamicInsnNode indy && AsmKit.isLambdaInvokeDynamic(indy)) {
                    pendingIndy = indy;
                    continue;
                }
                if (in instanceof FieldInsnNode fi
                    && in.getOpcode() == Opcodes.PUTSTATIC
                    && MESH_TRANSFORMER_DESC.equals(fi.desc)
                    && fi.owner.equals(owner)
                    && fi.name.equals(fieldName)
                    && pendingIndy != null) {
                    Handle handle = AsmKit.extractLambdaHandle(pendingIndy);
                    if (handle == null
                        || handle.getTag() != Opcodes.H_INVOKESTATIC
                        || !handle.getOwner().equals(owner)) return null;
                    MethodNode lambda = AsmKit.findMethod(cls, handle.getName(), handle.getDesc());
                    if (lambda == null) return null;
                    // The lambda body is the canonical `mesh.getRoot(); modifyMesh(...);
                    // aload_0; areturn` pattern. Find the first INVOKESTATIC whose descriptor is
                    // (Lnet/.../PartDefinition;)V - that's the modifyMesh-style callback.
                    for (AbstractInsnNode body = lambda.instructions.getFirst(); body != null; body = body.getNext()) {
                        if (AsmKit.isPseudoNode(body)) continue;
                        if (body instanceof MethodInsnNode mi
                            && mi.getOpcode() == Opcodes.INVOKESTATIC
                            && mi.desc.startsWith("(L" + VanillaSourceClasses.PART_DEFINITION + ";)V")) {
                            return AsmKit.findMethodInHierarchy(zip, mi.owner, mi.name, mi.desc);
                        }
                    }
                    return null;
                }
                pendingIndy = null;
            }
            return null;
        }

        /**
         * Bakes the captured {@link ParseState#meshTransformerScale} (from
         * {@code MeshTransformer.scaling(F)} call(s) on the {@code LayerDefinition}) into every
         * emitted bone. Vanilla's expansion is, per {@code PartPose},
         * {@code pose.scaled(F).translated(0, 24.016*(1-F), 0)} - scales bone pivots uniformly
         * around the entity's feet anchor at {@code y=24.016 pixels} (= {@code 1.501 blocks * 16
         * px/block}, the LER chain's {@code translate(0, -1.501, 0)}) and multiplies the bone's
         * {@code PartPose.scale} field by F. Both halves land here together; the kit's
         * {@link EntityGeometryKit#buildTriangles} consumes the
         * {@code scale} field to multiply local cube vertices by F at the pivot translate, which
         * is algebraically equivalent to vanilla's per-vertex {@code poseStack.scale(F)} call
         * sitting AFTER the pivot translate and BEFORE the cube render.
         * <p>
         * Cubes ({@code origin}, {@code size}, {@code inflate}, {@code uv}) are left untouched -
         * the kit applies the scale to cube vertices at render time without affecting UV
         * resolution. No-op when {@code meshTransformerScale == 1f} (the common case) so
         * byte-stable legacy + non-scaling entity parses stay byte-stable.
         */
        private static void applyMeshTransformerScaling(@NotNull ParseState state) {
            float f = state.meshTransformerScale;
            if (f == 1f) return;
            float dy = 24.016f * (1f - f);
            for (Map.Entry<String, JsonElement> entry : state.bones.entrySet()) {
                JsonObject bone = entry.getValue().getAsJsonObject();
                JsonArray pivot = bone.getAsJsonArray("pivot");
                if (pivot != null && pivot.size() == 3) {
                    float px = pivot.get(0).getAsFloat();
                    float py = pivot.get(1).getAsFloat();
                    float pz = pivot.get(2).getAsFloat();
                    JsonArray scaled = new JsonArray();
                    scaled.add(f * px);
                    scaled.add(f * py + dy);
                    scaled.add(f * pz);
                    bone.add("pivot", scaled);
                }
                float existing = bone.has("scale") ? bone.get("scale").getAsFloat() : 1f;
                float combined = existing * f;
                if (combined == 1f) {
                    bone.remove("scale");
                } else {
                    bone.addProperty("scale", combined);
                }
            }
        }

        /**
         * Drops bones whose ancestor chain (self -> root) contains no name in
         * {@link ParseState#retainedNames}. Mirrors the effect of
         * {@code PartDefinition.retainPartsAndChildren(Set)} on the mesh root: vanilla replaces
         * every non-retained bone's cubes with empty (recursing through children, leaving
         * subtrees rooted at a retained name fully intact). Since {@link #flushPendingBone}
         * skips JSON emission for cube-less bones, removing the JSON entry produces the same
         * render output as vanilla's "strip cubes, keep empty placeholder" path.
         *
         * <p>No-op when {@code retainedNames} is null (no filter requested) or empty (vanishingly
         * unusual, would drop every bone). Walks via {@link ParseState#boneParents} populated
         * during {@link #flushPendingBone}.
         */
        private static void applyRetainedNamesFilter(@NotNull ParseState state) {
            Set<String> retained = state.retainedNames;
            if (retained == null) return;
            List<String> toRemove = new java.util.ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : state.bones.entrySet()) {
                if (!hasRetainedAncestor(entry.getKey(), retained, state.boneParents))
                    toRemove.add(entry.getKey());
            }
            for (String name : toRemove) state.bones.remove(name);
        }

        /**
         * Drops every bone named in {@link ParseState#clearedBones} along with every descendant
         * (walked via {@link ParseState#boneParents}). Mirrors {@code PartDefinition.clearChild}'s
         * cascading delete - removing a child from a PartDefinition orphans its sub-tree, which
         * vanilla then renders nothing for. The canonical case (AdultPiglinModel) clears a leaf
         * ("hat"), so the descendant walk is just a safety net for future models that might
         * prune a non-leaf.
         *
         * <p>No-op when {@link ParseState#clearedBones} is empty.
         */
        private static void applyClearedBonesFilter(@NotNull ParseState state) {
            if (state.clearedBones.isEmpty()) return;
            Set<String> toRemove = new LinkedHashSet<>(state.clearedBones);
            // Expand to include descendants: any bone whose parent chain hits a cleared name.
            for (String candidate : state.boneParents.keySet()) {
                if (toRemove.contains(candidate)) continue;
                String cursor = state.boneParents.get(candidate);
                Set<String> seen = new LinkedHashSet<>();
                while (cursor != null && seen.add(cursor)) {
                    if (toRemove.contains(cursor)) {
                        toRemove.add(candidate);
                        break;
                    }
                    cursor = state.boneParents.get(cursor);
                }
            }
            for (String name : toRemove) state.bones.remove(name);
        }

        /**
         * Returns {@code true} if {@code name} or any of its ancestors (walked via
         * {@code parents}) appears in {@code retained}. Traversal stops at the first cycle or
         * when the parent chain bottoms out at {@code null} (root).
         */
        private static boolean hasRetainedAncestor(
            @NotNull String name,
            @NotNull Set<String> retained,
            @NotNull Map<String, String> parents
        ) {
            Set<String> seen = new LinkedHashSet<>();
            String cursor = name;
            while (cursor != null && seen.add(cursor)) {
                if (retained.contains(cursor)) return true;
                cursor = parents.get(cursor);
            }
            return false;
        }

        /**
         * Walks an instruction list, accumulating numeric literals on a stack and matching
         * builder-chain patterns. Recurses via {@link #handleMethodInsn}'s invokestatic-follow
         * branch so a single {@link ParseState} spans the entire dispatch chain.
         *
         * <p>Thin wrapper around {@link #walkRange}; the per-instruction logic lives in
         * {@link #handleInstruction}. Split into three methods so for-loop unrolling can
         * recursively re-enter the same per-instruction dispatch over a sub-range of the
         * same {@code InsnList} (see Phase 20).
         */
        private static void walkInstructions(@NotNull InsnList instructions, @NotNull ParseState state, @NotNull ZipFile zip) {
            walkRange(instructions, instructions.getFirst(), null, state, zip);
        }

        /**
         * Walks {@code [first, endExclusive)} of {@code instructions} via repeated
         * {@link #handleInstruction} dispatch. When {@code endExclusive} is {@code null} the
         * walk continues until {@code node.getNext()} returns null (i.e. end of the list).
         *
         * <p>{@link #handleInstruction} returns the node to advance from - normally that's the
         * original {@code node} (caller advances to its next), but for taken jumps / switch
         * branches it's the jump target so the caller advances past it.
         */
        private static void walkRange(
            @NotNull InsnList instructions,
            @Nullable AbstractInsnNode first,
            @Nullable AbstractInsnNode endExclusive,
            @NotNull ParseState state,
            @NotNull ZipFile zip
        ) {
            AbstractInsnNode node = first;
            while (node != null && node != endExclusive) {
                AbstractInsnNode advanceFrom = handleInstruction(instructions, node, state, zip);
                node = advanceFrom.getNext();
            }
        }

        /**
         * Processes a single instruction node, accumulating numeric literals, advancing builder
         * chains, and dispatching to {@link #handleMethodInsn} / {@link #handlePartPose} / etc.
         *
         * <p>Returns the {@link AbstractInsnNode} to advance from: normally the original
         * {@code node} (caller does {@code node.getNext()} to step linearly), but for a taken
         * jump or switch branch it's the jump target so the caller advances past the target's
         * label rather than the jump opcode.
         */
        private static @NotNull AbstractInsnNode handleInstruction(
            @NotNull InsnList instructions,
            @NotNull AbstractInsnNode node,
            @NotNull ParseState state,
            @NotNull ZipFile zip
        ) {
            // Canonical javac for-loop unrolling: when {@code node} opens the
            // {@code <init>; ISTORE slot; ILOAD slot; <bound>; IF_ICMPGE exit ; body ;
            // IINC slot, +step; GOTO test ; exit:} pattern, replay the body N times with
            // {@code paramIntValues[slot] = i} so the body's {@code ILOAD slot} substitution
            // (see the ILOAD handler below) folds to the literal iteration index. Vanilla
            // procedural-loop entity factories (squid / blaze / ghast / magma_cube / guardian
            // / silverfish / endermite / ender_dragon / elder_guardian) all use this exact
            // shape to emit N tentacles / segments / spikes per loop. Skipped when
            // {@code paramFloatValues == null} so legacy block-entity literal-stack walkers
            // (which never opted into arithmetic evaluation) keep their byte-stable linear walk.
            //
            // <p>The body range {@code [firstBodyInsn, firstInsnAfterLoop)} naturally contains
            // the trailing {@code IINC} (unhandled - no IINC handler exists, so iterator slot
            // stays at our injected value) and the closing backward {@code GOTO test} (the
            // GOTO handler only follows forward jumps, so it falls through linearly past the
            // exit label and walkRange stops at {@code endExclusive = firstInsnAfterLoop}).
            // Return {@code firstInsnAfterLoop.getPrevious()} so the outer walkRange's
            // {@code .getNext()} lands on {@code firstInsnAfterLoop} - i.e. the parser
            // resumes at the first real instruction after the loop.
            if (state.paramFloatValues != null) {
                AsmKit.IntForLoop loop = AsmKit.detectIntForLoop(node);
                if (loop != null) {
                    int slot = loop.iteratorSlot();
                    int[] previousInts = state.paramIntValues;
                    int[] working = ensureIntSlotCapacity(previousInts, slot);
                    int savedAtSlot = working[slot];
                    Number savedNumericLocal = state.numericLocals.remove(slot);
                    state.paramIntValues = working;
                    try {
                        for (int i = loop.initValue(); i < loop.boundExclusive(); i += loop.step()) {
                            working[slot] = i;
                            // Wipe any numericLocals entry the body may have STORE'd into the
                            // iterator slot in a prior iteration; otherwise the next iteration's
                            // ILOAD <slot> would read the stale captured value and shadow the
                            // freshly-injected paramIntValues[slot] = i.
                            state.numericLocals.remove(slot);
                            walkRange(instructions, loop.firstBodyInsn(), loop.firstInsnAfterLoop(), state, zip);
                        }
                    } finally {
                        working[slot] = savedAtSlot;
                        state.paramIntValues = previousInts;
                        if (savedNumericLocal != null) {
                            state.numericLocals.put(slot, savedNumericLocal);
                        } else {
                            state.numericLocals.remove(slot);
                        }
                    }
                    AbstractInsnNode resumeAt = loop.firstInsnAfterLoop().getPrevious();
                    return resumeAt != null ? resumeAt : loop.firstInsnAfterLoop();
                }
            }

            Number literal = readNumericLiteral(node);
            if (literal != null) {
                // LiteralStack auto-evicts the oldest on capacity overflow; surface the first
                // overflow as a WARN so a true accounting bug surfaces (subsequent overflows
                // stay silent to avoid spamming when a broken source pushes 1000 literals).
                boolean willOverflow = state.numStack.size() >= ParseState.NUM_STACK_CAPACITY;
                state.numStack.push(literal);
                if (willOverflow && state.diagnostics != null && !state.overflowWarned && state.currentSource != null) {
                    state.diagnostics.warn("%s: numStack overflow (>%d literals) - oldest literals being dropped, pop accounting may be broken", state.currentSource.entityId(), ParseState.NUM_STACK_CAPACITY);
                    state.overflowWarned = true;
                }
                return node;
            }

            int opcode = node.getOpcode();

            // Conditional / unconditional jumps + their JVM-stack-pop accounting. The pop
            // accounting is gated on {@code paramFloatValues != null} (Java pipeline opt-in)
            // so legacy literal-stack walkers keep their literal-only walk. Branch-following
            // remains gated on {@code paramIntValues != null} - without a known parameter
            // value the parser falls through linearly. Decoupling the two gates means Java
            // entities at the top-level Source (where {@code paramIntValues == null} but
            // {@code paramFloatValues != null}) still pop the comparison values, preventing
            // the leftover-literal warnings produced by for-loop {@code IF_ICMPGE} etc.
            if (node instanceof JumpInsnNode jumpInsn) {
                boolean canFollow = state.paramIntValues != null;
                switch (opcode) {
                    case Opcodes.GOTO -> {
                        // Forward GOTO only - skips the not-taken branch of an if/else
                        // (vanilla model factories use this for variant dispatch). Backward
                        // GOTOs (loop tails, e.g. the spike loop in
                        // {@code GuardianModel.createBodyMesh}) would loop the linear walker
                        // forever, so they fall through linearly, walking the loop body once.
                        if (canFollow && isForwardJump(instructions, node, jumpInsn.label)) {
                            return jumpInsn.label;
                        }
                    }
                    case Opcodes.IFEQ, Opcodes.IFNE,
                         Opcodes.IFLT, Opcodes.IFGE,
                         Opcodes.IFGT, Opcodes.IFLE -> {
                        // Unary int comparison: pops 1 int. Java pipeline pops from
                        // numStack via {@link AsmKit.LiteralStack#popLiteralNumber} (which
                        // returns null when the popped entry is the non-literal sentinel,
                        // distinguishing "real compile-time literal" from "marker"); legacy
                        // pipeline pops from branchStack (where ILOAD-of-paramIntValues lives,
                        // used by the banner standing/wall split).
                        Integer value = null;
                        if (state.paramFloatValues != null && !state.numStack.isEmpty()) {
                            Number popped = state.numStack.popLiteralNumber();
                            if (popped != null) value = popped.intValue();
                        } else if (canFollow && !state.branchStack.isEmpty()) {
                            value = state.branchStack.remove(state.branchStack.size() - 1);
                        }
                        // Branch-following for all six unary comparisons when the value is a
                        // resolved literal. Patterns: IFLE / IF_ICMPGE inside an unrolled loop
                        // body (e.g. MagmaCubeModel's per-iteration `if (i > 0 && i < 4)`)
                        // need full follow so each iteration takes the correct branch.
                        if (canFollow && value != null
                            && AsmKit.evaluateIntComparison(opcode, value, 0)
                            && isForwardJump(instructions, node, jumpInsn.label)) {
                            return jumpInsn.label;
                        }
                    }
                    case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
                         Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                         Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                        // Binary int comparison: pops 2 ints. Used by for-loop exit checks
                        // ({@code iload_N; bipush <limit>; if_icmpge end}) which the parser
                        // doesn't follow back to the loop top, so the operands need cleaning
                        // up to keep numStack aligned for the post-loop code.
                        //
                        // <p>When both operands are resolved literals (e.g. the iterator slot's
                        // injected value vs a literal bound during unrolling), the comparison
                        // is evaluated and the branch followed when satisfied. Non-literal
                        // operands fall through linearly with the JVM stack still aligned -
                        // popLiteralNumber consumes the entry regardless.
                        Integer rhs = null;
                        Integer lhs = null;
                        if (state.paramFloatValues != null) {
                            if (!state.numStack.isEmpty()) {
                                Number poppedB = state.numStack.popLiteralNumber();
                                if (poppedB != null) rhs = poppedB.intValue();
                            }
                            if (!state.numStack.isEmpty()) {
                                Number poppedA = state.numStack.popLiteralNumber();
                                if (poppedA != null) lhs = poppedA.intValue();
                            }
                        }
                        if (canFollow && lhs != null && rhs != null
                            && AsmKit.evaluateIntComparison(opcode, lhs, rhs)
                            && isForwardJump(instructions, node, jumpInsn.label)) {
                            return jumpInsn.label;
                        }
                    }
                    case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
                         Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                        // Object-reference comparisons. Object refs aren't tracked on
                        // numStack, so nothing to pop here.
                    }
                    default -> { /* not a jump opcode we model */ }
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
            if (node instanceof TableSwitchInsnNode tableSwitch && state.paramIntValues != null) {
                Integer value = popIntForBranch(state);
                if (value != null) {
                    LabelNode target = value >= tableSwitch.min && value <= tableSwitch.max
                        ? tableSwitch.labels.get(value - tableSwitch.min)
                        : tableSwitch.dflt;
                    if (isForwardJump(instructions, node, target)) {
                        return target;
                    }
                }
                if (state.diagnostics != null && state.currentSource != null)
                    state.diagnostics.warn("%s: TABLESWITCH encountered with unknown value - falling through linearly, case bodies may corrupt numStack", state.currentSource.entityId());
            }
            if (node instanceof LookupSwitchInsnNode lookupSwitch && state.paramIntValues != null) {
                Integer value = popIntForBranch(state);
                if (value != null) {
                    int idx = lookupSwitch.keys.indexOf(value);
                    LabelNode target = idx >= 0 ? lookupSwitch.labels.get(idx) : lookupSwitch.dflt;
                    if (isForwardJump(instructions, node, target)) {
                        return target;
                    }
                }
                if (state.diagnostics != null && state.currentSource != null)
                    state.diagnostics.warn("%s: LOOKUPSWITCH encountered with unknown value - falling through linearly, case bodies may corrupt numStack", state.currentSource.entityId());
            }

            // ILOAD N: if the source declared a value for slot N, push it onto the
            // branch stack so the upcoming IFEQ / IFNE / switch can evaluate the
            // conditional. If the slot is NOT in {@code paramIntValues} (or the source
            // didn't supply any values), call {@code state.numStack.pushNonLiteral()} to
            // mark the entry as non-literal on {@link ParseState#numStack} - when a
            // downstream addBox / PartPose consumes it, {@link #popIntWithDiagnostics}
            // surfaces a {@code WARN:} so the silent-zero failure mode doesn't get baked
            // into the output cube.
            if (node instanceof VarInsnNode varInsn && opcode == Opcodes.ILOAD) {
                int slot = varInsn.var;
                // numericLocals first: an in-method ISTORE captured a precise value (overrides
                // any param-table default for the same slot). Java pipeline only - legacy
                // literal-stack walkers don't STORE into numericLocals.
                Number local = state.paramFloatValues != null ? state.numericLocals.get(slot) : null;
                if (local != null) {
                    state.numStack.push(local.intValue());
                } else {
                    boolean resolved = state.paramIntValues != null && slot >= 0 && slot < state.paramIntValues.length;
                    if (resolved) {
                        // Java pipeline (paramFloatValues != null) routes ILOAD through numStack
                        // so call-site-propagated literals (pig's {@code legSize=6}) feed the
                        // subsequent {@code 18 - legSize} {@link Opcodes#ISUB} arithmetic. The
                        // matching IFEQ / IFNE / switch consumer above pops from numStack in
                        // the same gated branch. Legacy pipeline keeps the legacy branchStack
                        // path so banner standing/wall and similar paramIntValues uses are
                        // unaffected.
                        if (state.paramFloatValues != null)
                            state.numStack.push(state.paramIntValues[slot]);
                        else
                            state.branchStack.add(state.paramIntValues[slot]);
                    } else {
                        state.numStack.pushNonLiteral();
                    }
                }
            }

            // FLOAD / DLOAD / LLOAD: the value comes from a local variable the parser
            // can't resolve. Call {@code state.numStack.pushNonLiteral()} so the next
            // {@link #popFloatWithDiagnostics} / {@link #popIntWithDiagnostics} surfaces
            // the attribution instead of silently consuming a stale zero off an earlier
            // literal or a fresh zero from an empty stack.
            //
            // When {@code paramFloatValues} is supplied (Java-derived entity sources opt in
            // for arithmetic evaluation), {@code FLOAD slot} substitutes the known value
            // so adjacent {@code FADD}/{@code FMUL}/etc. ops can fold in the parameter.
            if (node instanceof VarInsnNode varInsn
                && (opcode == Opcodes.FLOAD || opcode == Opcodes.DLOAD || opcode == Opcodes.LLOAD)) {
                int slot = varInsn.var;
                // numericLocals first: an in-method FSTORE / DSTORE / LSTORE captured a precise
                // value (overrides any param-table default for the same slot).
                Number local = state.paramFloatValues != null ? state.numericLocals.get(slot) : null;
                if (local != null) {
                    state.numStack.push(local);
                } else if (state.paramFloatValues != null && slot >= 0 && slot < state.paramFloatValues.length) {
                    state.numStack.push(state.paramFloatValues[slot]);
                } else {
                    state.numStack.pushNonLiteral();
                }
            }

            // ISTORE / FSTORE / DSTORE / LSTORE: consume the value that the matching
            // LDC / arithmetic op pushed onto numStack so the JVM stack and our symbolic
            // stack stay in sync. Without this, a {@code ldc <value>; fstore_N} sequence
            // (e.g. {@code WitherBossModel.createBodyLayer}'s {@code RIBCAGE_X_ROT_OFFSET = 0.20420352f})
            // leaks the LDC value, which then sits at the bottom of every subsequent pop
            // and surfaces as a "leftover literal" warning at end-of-parse. Gated on
            // {@code paramFloatValues != null} for byte-stability - legacy literal-stack
            // walkers use {@code ASTORE} (handled in the switch below) for their bone slot
            // tracking, never primitive STOREs in {@code createBodyLayer}-shaped code.
            // ASTORE is intentionally NOT included here; its bone-slot tracking remains in
            // the existing switch case below.
            if (state.paramFloatValues != null
                && (opcode == Opcodes.ISTORE || opcode == Opcodes.FSTORE
                    || opcode == Opcodes.DSTORE || opcode == Opcodes.LSTORE)
                && node instanceof VarInsnNode storeInsn) {
                // popNumber returns null when the stack is empty - guard so we still drop a
                // stale captured-local entry on the slot rather than holding onto a value the
                // bytecode just overwrote with an unknown computation.
                Number popped = state.numStack.isEmpty() ? null : state.numStack.popNumber();
                if (popped != null) {
                    state.numericLocals.put(storeInsn.var, popped);
                } else {
                    state.numericLocals.remove(storeInsn.var);
                }
            }

            // Explicit stack pops: {@code POP} discards 1 category-1 slot (int / float /
            // ref); {@code POP2} discards 1 category-2 slot (long / double) or 2 category-1
            // slots. Our numStack treats long / double as single Number entries, so POP2
            // of a wide value pops 1 entry. javac never emits POP2 for two narrow values
            // (it uses POP; POP), so the single-entry pop is correct in practice.
            if (state.paramFloatValues != null
                && (opcode == Opcodes.POP || opcode == Opcodes.POP2)
                && !state.numStack.isEmpty()) {
                state.numStack.pop();
            }

            // Array load / store / metadata ops. The JVM stack effects of these aren't
            // visible to the literal walk so the index ints and any result ints leak as
            // leftovers - silverfish's {@code BODY_SIZES[i][j]} pattern produces 9
            // {@code AALOAD; ICONST j; IALOAD} chains, every one leaving the {@code i}
            // and the {@code IALOAD} result hanging.
            //
            // <ul>
            //   <li>AALOAD pops 1 ref + 1 int, pushes 1 ref. numStack effect: pop 1 int.</li>
            //   <li>IALOAD / BALOAD / SALOAD / CALOAD: pop 1 ref + 1 int, push 1 int.
            //       numStack effect: pop 1 int, push 1 NL int.</li>
            //   <li>FALOAD / DALOAD / LALOAD: same shape with float / double / long result -
            //       still represented as a single non-literal marker on numStack.</li>
            //   <li>ARRAYLENGTH: pop 1 ref, push 1 int. Push NL.</li>
            // </ul>
            // Gated on {@code paramFloatValues != null} for byte-stability.
            //
            // <p>For {@code IALOAD} / {@code FALOAD} the parser also tries
            // {@link #tryFoldStaticArrayRead}, which walks back over the prior real
            // instructions to detect the canonical static-array index patterns
            // {@code GETSTATIC <[[I>; ILOAD; AALOAD; <int literal>; IALOAD},
            // {@code GETSTATIC <[I>; ILOAD; IALOAD}, and
            // {@code GETSTATIC <[F>; ILOAD; FALOAD}. On match, the resolved literal cell value
            // is pushed instead of the non-literal marker, so vanilla's silverfish / endermite
            // {@code BODY_SIZES[i][j]} / {@code BODY_TEXS[i][j]} reads and guardian's
            // {@code SPIKE_X[i]} / {@code SPIKE_Y[i]} / {@code SPIKE_Z[i]} +
            // {@code SPIKE_*_ROT[i]} reads fold to compile-time constants per unrolled
            // iteration.
            if (state.paramFloatValues != null) {
                if (opcode == Opcodes.AALOAD) {
                    if (!state.numStack.isEmpty()) state.numStack.pop();
                } else if (opcode == Opcodes.IALOAD || opcode == Opcodes.FALOAD) {
                    Number resolved = tryFoldStaticArrayRead(node, state, zip);
                    if (!state.numStack.isEmpty()) state.numStack.pop();
                    if (resolved != null) state.numStack.push(resolved);
                    else state.numStack.pushNonLiteral();
                } else if (opcode == Opcodes.BALOAD || opcode == Opcodes.SALOAD
                        || opcode == Opcodes.CALOAD || opcode == Opcodes.DALOAD
                        || opcode == Opcodes.LALOAD) {
                    if (!state.numStack.isEmpty()) state.numStack.pop();
                    state.numStack.pushNonLiteral();
                } else if (opcode == Opcodes.ARRAYLENGTH) {
                    state.numStack.pushNonLiteral();
                } else if (opcode == Opcodes.NEWARRAY) {
                    // Pop 1 int (length); push ref (refs aren't tracked on numStack). Also
                    // captures the length + element-type on {@link ParseState#pendingFreshArrayLength}
                    // so the next {@code ASTORE} can bind the array to a slot and the parser
                    // can subsequently fold {@code FASTORE} writes and {@code FALOAD} reads
                    // against the tracked local array. Silverfish's {@code float[7]}
                    // cumulative-pivot cache uses this; other vanilla model factories don't
                    // currently allocate local primitive arrays.
                    Number lengthN = state.numStack.isEmpty() ? null : state.numStack.popLiteralNumber();
                    if (lengthN != null && node instanceof IntInsnNode arrInsn) {
                        int length = lengthN.intValue();
                        if (length >= 0 && length < 1024) {
                            if (arrInsn.operand == Opcodes.T_FLOAT) {
                                state.pendingFreshArrayLength = length;
                                state.pendingFreshArrayType = 'F';
                            } else if (arrInsn.operand == Opcodes.T_INT) {
                                state.pendingFreshArrayLength = length;
                                state.pendingFreshArrayType = 'I';
                            }
                        }
                    }
                } else if (opcode == Opcodes.ANEWARRAY) {
                    // Pop 1 int (length); push ref (refs aren't tracked on numStack).
                    if (!state.numStack.isEmpty()) state.numStack.pop();
                } else if (opcode == Opcodes.IASTORE || opcode == Opcodes.BASTORE
                        || opcode == Opcodes.SASTORE || opcode == Opcodes.CASTORE
                        || opcode == Opcodes.DASTORE || opcode == Opcodes.LASTORE) {
                    // Array element store (non-float): JVM pops ref + int + value. numStack
                    // effect: pop value (1 entry) + index (1 int).
                    if (!state.numStack.isEmpty()) state.numStack.pop();
                    if (!state.numStack.isEmpty()) state.numStack.pop();
                } else if (opcode == Opcodes.FASTORE) {
                    // {@code FASTORE} writes into a {@code float[]}. Pops value + index +
                    // array-ref. Walks back over the prior real instructions to find the
                    // {@code ALOAD <slot>} that pushed the array ref; when the slot has a
                    // tracked {@link ParseState#localFloatArrays} entry AND both popped
                    // operands are real literals, writes {@code arr[idx] = value}. Otherwise
                    // just pops to keep the JVM stack aligned. SilverfishModel's
                    // {@code aload_2; iload i; fload f; fastore} cumulative-pivot pattern
                    // matches this exactly.
                    Number value = state.numStack.isEmpty() ? null : state.numStack.popLiteralNumber();
                    Number idx = state.numStack.isEmpty() ? null : state.numStack.popLiteralNumber();
                    if (value != null && idx != null) {
                        // Walk back: prev1 = value insn, prev2 = idx insn, prev3 = ALOAD slot.
                        AbstractInsnNode v = AsmKit.previousReal(node);
                        AbstractInsnNode i = v != null ? AsmKit.previousReal(v) : null;
                        AbstractInsnNode a = i != null ? AsmKit.previousReal(i) : null;
                        if (a instanceof VarInsnNode aload && aload.getOpcode() == Opcodes.ALOAD) {
                            float[] arr = state.localFloatArrays.get(aload.var);
                            int idxInt = idx.intValue();
                            if (arr != null && idxInt >= 0 && idxInt < arr.length) {
                                arr[idxInt] = value.floatValue();
                            }
                        }
                    }
                } else if (opcode == Opcodes.AASTORE) {
                    // Array reference store: JVM pops ref + int + ref. numStack effect:
                    // pop index only (the value ref isn't on numStack).
                    if (!state.numStack.isEmpty()) state.numStack.pop();
                }
            }

            // Comparison ops that push an int result: {@code LCMP} (long / long),
            // {@code FCMPL} / {@code FCMPG} (float / float), {@code DCMPL} / {@code DCMPG}
            // (double / double). Each pops two operands and pushes -1 / 0 / 1 onto the JVM
            // stack. Our walker can't statically know the result so push a non-literal
            // marker - the next IFEQ / IFNE / IF_ICMP* handler above pops it and falls
            // through linearly without taking the branch.
            if (state.paramFloatValues != null
                && (opcode == Opcodes.LCMP || opcode == Opcodes.FCMPL || opcode == Opcodes.FCMPG
                    || opcode == Opcodes.DCMPL || opcode == Opcodes.DCMPG)) {
                if (!state.numStack.isEmpty()) state.numStack.pop();
                if (!state.numStack.isEmpty()) state.numStack.pop();
                state.numStack.pushNonLiteral();
            }

            // Binary integer arithmetic: pops two ints, pushes the result. Same
            // {@code paramFloatValues != null} gate as the float / double block below so
            // legacy literal-stack walkers keep the legacy literal-stack-only walk. Vanilla
            // shares parameterised quadruped construction in {@code QuadrupedModel
            // .createBodyMesh(int legSize, ...)} which computes head/body Y as
            // {@code bipush 18; iload_0; isub; i2f}; without this block the {@code isub}
            // is a no-op and pig head ends up at world Y=18 instead of 12.
            if (state.paramFloatValues != null
                && (opcode == Opcodes.IADD || opcode == Opcodes.ISUB
                    || opcode == Opcodes.IMUL || opcode == Opcodes.IDIV
                    || opcode == Opcodes.IREM)
                && state.numStack.size() >= 2) {
                int b = state.numStack.popNumber().intValue();
                int a = state.numStack.popNumber().intValue();
                int r = switch (opcode) {
                    case Opcodes.IADD -> a + b;
                    case Opcodes.ISUB -> a - b;
                    case Opcodes.IMUL -> a * b;
                    case Opcodes.IDIV -> b == 0 ? 0 : a / b;
                    case Opcodes.IREM -> b == 0 ? 0 : a % b;
                    default -> 0;
                };
                state.numStack.push(r);
            }

            // Unary numeric negation: pops 1, pushes 1. INEG = -i, FNEG = -f, etc.
            if (state.paramFloatValues != null
                && (opcode == Opcodes.INEG || opcode == Opcodes.FNEG
                    || opcode == Opcodes.DNEG || opcode == Opcodes.LNEG)
                && !state.numStack.isEmpty()) {
                Number top = state.numStack.popNumber();
                Number negated = switch (opcode) {
                    case Opcodes.INEG -> -top.intValue();
                    case Opcodes.FNEG -> -top.floatValue();
                    case Opcodes.DNEG -> -top.doubleValue();
                    case Opcodes.LNEG -> -top.longValue();
                    default -> top;
                };
                state.numStack.push(negated);
            }

            // Binary float / double arithmetic: only fires when the source opted into
            // arithmetic evaluation via {@code paramFloatValues != null}. Legacy
            // sources never set this so the legacy linear walk is preserved unchanged.
            // For Java-side sources, this fixes patterns like
            // {@code HumanoidModel.createMesh}'s arm pivot {@code 2 + yOffset} where
            // yOffset is a parameter and the {@code FADD} would otherwise leave the stack
            // mis-aligned. Non-literal markers are treated as zero during the operation.
            if (state.paramFloatValues != null
                && (opcode == Opcodes.FADD || opcode == Opcodes.FSUB || opcode == Opcodes.FMUL || opcode == Opcodes.FDIV || opcode == Opcodes.FREM
                    || opcode == Opcodes.DADD || opcode == Opcodes.DSUB || opcode == Opcodes.DMUL || opcode == Opcodes.DDIV || opcode == Opcodes.DREM)) {
                if (state.numStack.size() >= 2) {
                    Number bN = state.numStack.popNumber();
                    Number aN = state.numStack.popNumber();
                    // JVM float / double arithmetic opcodes interleave (FADD=98, DADD=99,
                    // FSUB=102, DSUB=103, FMUL=106, DMUL=107, FDIV=110, DDIV=111, FREM=114,
                    // DREM=115) - a {@code >= DADD && <= DDIV} range check would misclassify
                    // FSUB / FMUL / FDIV / FREM as double, causing the switch below to fall
                    // to its zero-return default. Use explicit equality.
                    boolean isDouble = opcode == Opcodes.DADD || opcode == Opcodes.DSUB
                        || opcode == Opcodes.DMUL || opcode == Opcodes.DDIV
                        || opcode == Opcodes.DREM;
                    if (isDouble) {
                        double a = aN.doubleValue();
                        double b = bN.doubleValue();
                        double r = switch (opcode) {
                            case Opcodes.DADD -> a + b;
                            case Opcodes.DSUB -> a - b;
                            case Opcodes.DMUL -> a * b;
                            case Opcodes.DDIV -> b == 0.0 ? 0.0 : a / b;
                            case Opcodes.DREM -> b == 0.0 ? 0.0 : a % b;
                            default -> 0.0;
                        };
                        state.numStack.push(r);
                    } else {
                        float a = aN.floatValue();
                        float b = bN.floatValue();
                        float r = switch (opcode) {
                            case Opcodes.FADD -> a + b;
                            case Opcodes.FSUB -> a - b;
                            case Opcodes.FMUL -> a * b;
                            case Opcodes.FDIV -> b == 0f ? 0f : a / b;
                            case Opcodes.FREM -> b == 0f ? 0f : a % b;
                            default -> 0f;
                        };
                        state.numStack.push(r);
                    }
                }
            }

            // Type-conversion ops between numeric stack slots. Mirrored on the literal
            // stack so subsequent arithmetic / argument-pop sees the correct precision.
            // Gated on paramFloatValues for the same byte-stability reason as the
            // arithmetic block above.
            if (state.paramFloatValues != null
                && (opcode == Opcodes.I2F || opcode == Opcodes.I2D || opcode == Opcodes.F2D
                    || opcode == Opcodes.D2F || opcode == Opcodes.F2I || opcode == Opcodes.D2I)
                && !state.numStack.isEmpty()) {
                Number top = state.numStack.popNumber();
                Number converted = switch (opcode) {
                    case Opcodes.I2F -> (float) top.intValue();
                    case Opcodes.I2D -> (double) top.intValue();
                    case Opcodes.F2D -> (double) top.floatValue();
                    case Opcodes.D2F -> (float) top.doubleValue();
                    case Opcodes.F2I -> (int) top.floatValue();
                    case Opcodes.D2I -> (int) top.doubleValue();
                    default -> top;
                };
                state.numStack.push(converted);
            }

            switch (node) {
                case FieldInsnNode fieldInsn when opcode == Opcodes.GETSTATIC -> {
                    if (fieldInsn.owner.equals(VanillaSourceClasses.PART_POSE) && fieldInsn.name.equals("ZERO")) {
                        state.pendingPivot = new float[]{ 0, 0, 0 };
                        state.pendingRotation = new float[]{ 0, 0, 0 };
                        state.pendingScale = 1f;
                    }
                    // {@code GETSTATIC <field>: MeshTransformer} - vanilla static-field pattern
                    // for layer-level scale wraps that don't appear inline in the factory body.
                    // {@code GuardianModel.createElderGuardianLayer} reads
                    // {@code ELDER_GUARDIAN_SCALE} (a private static final MeshTransformer
                    // initialised in {@code <clinit>} as
                    // {@code MeshTransformer.scaling(2.35f)}) via {@code getstatic} then
                    // {@code LayerDefinition.apply(MeshTransformer)} - the parser sees the
                    // getstatic but not the scaling literal. Lazily walk the field owner's
                    // {@code <clinit>} for the matching {@code putstatic} and fold the captured
                    // F into {@link ParseState#meshTransformerScale}; the subsequent
                    // {@code apply()} call is then a no-op for our tracking (we don't track
                    // LayerDefinition refs anyway). Cached per-class so repeated parses of the
                    // same source don't reload. Gated on {@code paramFloatValues != null} so
                    // legacy literal-stack walkers keep their byte-stable behaviour.
                    //
                    // <p>Fallback for non-scaling transformers: when the field is initialised
                    // via {@code invokedynamic apply -> lambda$static$N} that calls
                    // {@code <Owner>.modifyMesh(MeshDefinition.getRoot())} (the
                    // {@code DonkeyModel.DONKEY_TRANSFORMER} pattern), inline that
                    // {@code modifyMesh} method into the current parse so the
                    // {@code addOrReplaceChild} calls inside it land in our bone tree before
                    // the subsequent {@code .apply(MeshTransformer.scaling(F))} bakes the
                    // per-renderer scale across every bone. modifyMesh's
                    // {@code body.addOrReplaceChild("left_chest", ...)} relies on the
                    // {@link ParseState#boneMeta} entries already populated by
                    // {@code AbstractEquineModel.createBodyMesh}, which flow through
                    // {@link #inlineStaticMethodBody}'s save/restore set untouched.
                    else if (state.paramFloatValues != null
                        && MESH_TRANSFORMER_DESC.equals(fieldInsn.desc)) {
                        Float f = resolveStaticMeshTransformer(fieldInsn.owner, fieldInsn.name, state, zip);
                        if (f != null) {
                            state.meshTransformerScale *= f;
                        } else {
                            MethodNode modifyMesh = findStaticModifyMeshTarget(fieldInsn.owner, fieldInsn.name, zip);
                            if (modifyMesh != null) inlineStaticMethodBody(modifyMesh, null, state, zip);
                        }
                    }
                }
                case MethodInsnNode methodInsn -> handleMethodInsn(methodInsn, opcode, state, zip);
                // {@code invokedynamic makeConcatWithConstants:(I)Ljava/lang/String;} - javac
                // emits this for inline {@code "name" + i} expressions. Pop the int from
                // numStack, apply the bootstrap recipe (with {@code \u0001} as the dynamic
                // placeholder), and stash the result in {@code pendingPartName} so the
                // surrounding {@code addOrReplaceChild} flush picks it up as the bone name.
                // Vanilla procedural-loop factories (ghast tentacle scaling, etc.) emit the
                // indy directly; helper-wrapped variants (squid's createTentacleName, blaze's
                // getPartName) are resolved in {@link #handleMethodInsn}'s invokestatic-follow.
                case InvokeDynamicInsnNode indy when state.paramFloatValues != null
                    && indy.desc.equals("(I)Ljava/lang/String;")
                    && !state.numStack.isEmpty() -> {
                    String recipe = AsmKit.resolveStringConcatRecipe(indy);
                    if (recipe != null) {
                        int i = state.numStack.popNumber().intValue();
                        state.pendingPartName = AsmKit.applyStringConcatRecipeWithInt(recipe, i);
                    }
                }
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
                    if (state.pendingFreshArrayLength != null && state.pendingFreshArrayType != '\0') {
                        // The previous {@code NEWARRAY <type>} captured a length + element-type
                        // on the pending fields; bind a tracked array to this slot now so
                        // subsequent {@code FASTORE} writes and {@code FALOAD} reads can fold
                        // against it. SilverfishModel's {@code bipush 7; newarray float;
                        // astore_2} cumulative-pivot cache hits this exactly.
                        int len = state.pendingFreshArrayLength;
                        if (state.pendingFreshArrayType == 'F') {
                            state.localFloatArrays.put(varInsn.var, new float[len]);
                        }
                        state.pendingFreshArrayLength = null;
                        state.pendingFreshArrayType = '\0';
                    } else if (state.pendingRandomSource != null) {
                        // The previous {@code RandomSource.createThreadLocalInstance(J)}
                        // captured a seeded {@link java.util.Random}; bind it to this slot so
                        // subsequent {@code aload <slot>; <bound>; invokeinterface nextInt}
                        // calls can step it. GhastModel's {@code ldc2_w 1660L;
                        // invokestatic createThreadLocalInstance; astore_2} hits this exactly.
                        state.localRandomSources.put(varInsn.var, state.pendingRandomSource);
                        state.pendingRandomSource = null;
                    } else if (state.pendingFreshDeformationInflate != null) {
                        state.cubeDeformationSlots.put(varInsn.var, state.pendingFreshDeformationInflate);
                        state.pendingFreshDeformationInflate = null;
                        // The fresh deformation just got stashed into a slot for later
                        // reuse; it's no longer the "active" inflate. Reset to the
                        // factory default so the next addBox(...,CubeDeformation) picks up
                        // its own arg (via ALOAD slot lookup) or the call-site default,
                        // not the leftover constructor value. AdultFelineModel triggers
                        // this: {@code CubeDeformation tail_g = new CubeDeformation(-0.02F)}
                        // followed immediately by {@code addBox("main", ..., g)} where
                        // {@code g} is the parameter (call-site default), would otherwise
                        // emit the head main cube with the stale -0.02 instead of 0.
                        state.pendingInflate = state.defaultInflate;
                    } else if (state.lastFlushedBone != null) {
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
                    Float deformationInflate = state.cubeDeformationSlots.get(varInsn.var);
                    if (deformationInflate != null)
                        state.pendingInflate = deformationInflate;
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
            return node;
        }

        /**
         * Dispatches a {@code MethodInsnNode} by owner: builder chains (CubeListBuilder,
         * PartPose), bone-finalising ({@code PartDefinition.addOrReplaceChild}), texture dim
         * extraction ({@code LayerDefinition.create}), and model-package invokestatic-follow for
         * cross-class delegation patterns like
         * {@code PiglinHeadModel.createHeadModel -> PiglinModel.addHead}.
         */
        private static void handleMethodInsn(@NotNull MethodInsnNode methodInsn, int opcode, @NotNull ParseState state, @NotNull ZipFile zip) {
            if (methodInsn.owner.equals(VanillaSourceClasses.CUBE_LIST_BUILDER)) {
                handleCubeListBuilder(methodInsn, state);
                return;
            }
            if (methodInsn.owner.equals(VanillaSourceClasses.PART_POSE)) {
                handlePartPose(methodInsn, state);
                return;
            }
            if (methodInsn.owner.equals(VanillaSourceClasses.PART_DEFINITION) && methodInsn.name.equals("addOrReplaceChild")) {
                flushPendingBone(state);
                return;
            }
            // PartDefinition.getChild(String name) returns the named child PartDefinition.
            // The preceding LDC pushed the child name into {@link ParseState#pendingPartName};
            // re-aim {@link ParseState#lastFlushedBone} at it so the following ASTORE associates
            // the local slot with the correct bone (the named child), not the most-recently-
            // created bone. Without this, patterns like
            // {@code PartDefinition nose = head.getChild("nose"); nose.addOrReplaceChild("mole", ...);}
            // (WitchModel) would attribute "mole"'s parent to whatever bone happened to be
            // flushed last - in witch's case "hat4", landing mole's pivot accumulated through
            // the wrong rotation chain.
            if (methodInsn.owner.equals(VanillaSourceClasses.PART_DEFINITION) && methodInsn.name.equals("getChild")) {
                if (state.pendingPartName != null) {
                    state.lastFlushedBone = state.pendingPartName;
                    state.pendingPartName = null;
                }
                return;
            }
            // Java pipeline filters: {@code retainPartsAndChildren(Set)} on a PartDefinition strips
            // cubes from any bone whose ancestor chain doesn't contain a name in the set (vanilla
            // recurses through children, replacing cubes with empty along the way; subtrees rooted
            // at a retained name are left untouched). Captured here on the trailing
            // {@code retainPartsAndChildren} dispatch using {@link ParseState#pendingRetainSet}
            // populated by the preceding {@code Set.of} branch below; consumed post-walk in
            // {@link #parseLayerMethod} once the full bone tree has flushed. Gated on
            // {@code paramFloatValues != null} so legacy literal-stack walkers (which never call
            // retainPartsAndChildren) keep their byte-stable output.
            if (methodInsn.owner.equals(VanillaSourceClasses.PART_DEFINITION)
                && methodInsn.name.equals("retainPartsAndChildren")
                && state.paramFloatValues != null) {
                if (state.pendingRetainSet != null) {
                    state.retainedNames = state.pendingRetainSet;
                    state.pendingRetainSet = null;
                }
                return;
            }
            // Post-build pruning: {@code <parent>.clearChild("<name>")} drops the named child
            // (and its sub-tree) from the parent's PartDefinition. The previous {@code ALOAD}
            // identified the parent slot and the previous {@code LDC} pushed the child name into
            // {@link ParseState#pendingPartName}; record the name and let
            // {@link #applyClearedBonesFilter} drop it (and any descendants) from {@link #bones}
            // after the walk completes. The parent identification is informational only - bone
            // names are globally unique within a model, so name-keyed removal is sufficient.
            // Canonical case: {@code AdultPiglinModel.createBodyLayer} inherits "hat" from
            // {@code PlayerModel.createMesh}, then prunes it via {@code head.clearChild("hat")}.
            // Gated on {@code paramFloatValues != null} so legacy block-entity walkers (which
            // never see clearChild) keep their byte-stable output.
            if (methodInsn.owner.equals(VanillaSourceClasses.PART_DEFINITION)
                && methodInsn.name.equals("clearChild")
                && state.paramFloatValues != null) {
                if (state.pendingPartName != null) {
                    state.clearedBones.add(state.pendingPartName);
                    state.pendingPartName = null;
                }
                return;
            }
            // Set.of(name, ...) immediately precedes retainPartsAndChildren in vanilla model
            // factories ({@code BreezeModel.createBodyLayer} -> {@code Set.of("head", "rods")},
            // {@code BreezeModel.createWindLayer} -> {@code Set.of("wind_body")}, etc). Walks
            // back from the methodInsn collecting the N preceding LDC strings (where N is the
            // descriptor's ref-arg count) and stashes them on {@link ParseState#pendingRetainSet}
            // for the next {@code retainPartsAndChildren} dispatch. Skips line / frame / label
            // pseudo-nodes during the walkback. The varargs {@code Set.of([Ljava/lang/Object;)}
            // overload would need an anewarray walker; not used by any vanilla entity factory
            // observed so far so the implementation is deferred. Gated on
            // {@code paramFloatValues != null}.
            if (methodInsn.owner.equals("java/util/Set")
                && methodInsn.name.equals("of")
                && opcode == Opcodes.INVOKESTATIC
                && state.paramFloatValues != null) {
                state.pendingRetainSet = collectSetOfStringArgs(methodInsn);
                return;
            }
            if (methodInsn.owner.equals(LAYER_DEFINITION) && methodInsn.name.equals("create")) {
                requireStack(state, 2, "LayerDefinition.create(mesh,II)");
                state.texHeight = popIntWithDiagnostics(state, "LayerDefinition.create(mesh,II) texHeight");
                state.texWidth = popIntWithDiagnostics(state, "LayerDefinition.create(mesh,II) texWidth");
                return;
            }
            // {@code invokestatic <Owner>.<helper>(I)Ljava/lang/String;} where the helper body
            // is a thin wrapper around an inline {@code "prefix" + i} concat (e.g.
            // {@code SquidModel.createTentacleName} - {@code "tentacle" + i};
            // {@code BlazeModel.getPartName} - {@code "part" + i}). Walks the helper's
            // instructions for the inner {@code makeConcatWithConstants} invokedynamic via
            // {@link AsmKit#findStringConcatRecipeIn}, pops the int from numStack, applies the
            // recipe's dynamic-placeholder substitution and stashes the result in
            // {@code pendingPartName}. The general helper-walk approach subsumes the
            // PartNames-specific hack below (the helper name was the literal recipe prefix)
            // while also working for SquidModel / BlazeModel-style helpers where the helper
            // name ({@code createTentacleName} / {@code getPartName}) differs from the recipe
            // prefix ({@code tentacle} / {@code part}).
            //
            // <p>Falls through to the PartNames-name-equals-prefix legacy path below when the
            // helper has no {@code makeConcatWithConstants} indy (e.g. {@code PartNames}'s
            // static methods just return a constant String field via {@code areturn}).
            if (opcode == Opcodes.INVOKESTATIC
                && methodInsn.desc.equals("(I)Ljava/lang/String;")
                && state.paramFloatValues != null
                && !state.numStack.isEmpty()) {
                MethodNode helper = AsmKit.findMethodInHierarchy(zip, methodInsn.owner, methodInsn.name, methodInsn.desc);
                if (helper != null) {
                    String recipe = AsmKit.findStringConcatRecipeIn(helper);
                    if (recipe != null) {
                        int i = state.numStack.popNumber().intValue();
                        state.pendingPartName = AsmKit.applyStringConcatRecipeWithInt(recipe, i);
                        return;
                    }
                }
            }
            // PartNames is a vanilla utility class with String constants and indexed name
            // generators ({@code tentacle(int)}, etc.). The indexed methods compile to
            // {@code makeConcatWithConstants} which the parser can't follow; intercept the
            // call and synthesise the {@code "name" + i} the JVM produces, so subsequent
            // {@code addOrReplaceChild} flushes pick up a name. The HappyGhastModel uses
            // {@code PartNames.tentacle(0)}..{@code (8)} for its 9 explicit tentacle bones.
            if (methodInsn.owner.equals(VanillaSourceClasses.PART_NAMES)
                && opcode == Opcodes.INVOKESTATIC
                && methodInsn.desc.startsWith("(I)") && methodInsn.desc.endsWith("Ljava/lang/String;")
                && !state.numStack.isEmpty()) {
                int i = state.numStack.popNumber().intValue();
                state.pendingPartName = methodInsn.name + i;
                return;
            }
            if (methodInsn.owner.equals(VanillaSourceClasses.CUBE_DEFORMATION)) {
                handleCubeDeformation(methodInsn, state);
                return;
            }
            // MeshTransformer.scaling(F): some entity models append a uniform scale to the final
            // {@code LayerDefinition.create(...).apply(MeshTransformer.scaling(N))} chain
            // (PolarBearModel = 1.2, GhastModel = 4.5, HappyGhastModel = 4.0, etc). Vanilla
            // expands this per {@code PartPose} as {@code pose.scaled(F).translated(0,
            // 24.016*(1-F), 0)} - scales pivots around the entity's feet anchor (y=24.016) AND
            // multiplies the bone's {@code PartPose.scale} field by F, which the kit consumes
            // via {@link Bone#getScale()}
            // when emitting the bone's cubes. We capture F here, then re-walk the emitted bone
            // tree in {@link #applyMeshTransformerScaling} post-walk so the math agrees with
            // vanilla's apply-after-build semantics. Multiplies into any existing capture so
            // sequential {@code .apply(scaling(a)).apply(scaling(b))} chains compose (none
            // observed in vanilla 26.1, but cheap to support). Gated on
            // {@code paramFloatValues != null} so legacy literal-stack walkers, which never call
            // MeshTransformer, are unaffected.
            if (state.paramFloatValues != null
                && opcode == Opcodes.INVOKESTATIC
                && methodInsn.owner.equals("net/minecraft/client/model/geom/builders/MeshTransformer")
                && methodInsn.name.equals("scaling")
                && methodInsn.desc.equals("(F)Lnet/minecraft/client/model/geom/builders/MeshTransformer;")
                && !state.numStack.isEmpty()) {
                float f = state.numStack.popNumber().floatValue();
                // Vanilla never calls {@code scaling(0)}; a captured 0 means the synthetic
                // {@link Source}'s {@code paramFloatValues} didn't supply the {@code createBodyLayer}
                // float parameter that this site references via {@code fload_0}. Donkey / mule hit
                // this: their {@code createBodyLayer(float)} reads the renderer's per-variant scale,
                // which our tooling-side source builder doesn't currently populate. Skip the capture
                // so the bone tree stays unscaled rather than collapsing to a flat plane; the static-
                // field {@code DONKEY_TRANSFORMER} side (also unhandled) remains an open A5 gap.
                if (f == 0f) {
                    if (state.diagnostics != null && state.currentSource != null)
                        state.diagnostics.info("%s: MeshTransformer.scaling(0) skipped - synthetic source missing paramFloatValues", state.currentSource.entityId());
                    return;
                }
                state.meshTransformerScale *= f;
                return;
            }
            // Mth.cos(D)F / Mth.sin(D)F: vanilla model factories occasionally precompute bind-pose
            // offsets via inline trig - e.g. WitherBossModel.createBodyLayer's tail pivot
            // (6.9 + Mth.cos(0.20420352F) * 10 for Y, -0.5 + Mth.sin(0.20420352F) * 10 for Z).
            // Pop the top double from numStack, compute the result via the FastTrig table lookup,
            // push the float so the surrounding FMUL / FADD chain folds correctly. Gated on
            // paramFloatValues != null so legacy literal-stack walkers keep their byte-stable
            // parse - none observed call Mth.cos / sin during their layer build.
            //
            // Vanilla's Mth.cos(double) / Mth.sin(double) are 65536-entry sin-table lookups,
            // NOT Math.cos / Math.sin. The table values differ from libm by up to 1.8e-5 (table
            // granularity 2*PI/65536). Substituting Math.cos here would compute the right
            // rotation but a slightly different float result, enough to flip the wither tail
            // pivot Y across a canvas-pixel rounding boundary (Math: 16.6922283, Mth: 16.6924076).
            // FastTrig.cos / sin reproduce vanilla's bytecode bit-for-bit.
            if (state.paramFloatValues != null
                && opcode == Opcodes.INVOKESTATIC
                && methodInsn.owner.equals("net/minecraft/util/Mth")
                && (methodInsn.name.equals("cos") || methodInsn.name.equals("sin"))
                && methodInsn.desc.equals("(D)F")
                && !state.numStack.isEmpty()) {
                double arg = state.numStack.popNumber().doubleValue();
                float result = methodInsn.name.equals("cos")
                    ? FastTrig.cos(arg)
                    : FastTrig.sin(arg);
                state.numStack.push(result);
                return;
            }
            // Math.cos(D)D / Math.sin(D)D: libm-precision sibling of the Mth handler above.
            // Vanilla {@code SquidModel.createBodyLayer} uses {@code Math.cos / Math.sin} on the
            // doubles {@code i * 2*PI / 8} for tentacle pivots (NOT {@code Mth.cos / sin} which
            // returns float via a 65536-entry table lookup). The values differ by up to 1.8e-5
            // - enough to flip a pivot across a canvas-pixel rounding boundary - so this handler
            // returns {@code Math.cos / sin} double directly. The subsequent
            // {@code D2F / DMUL / DADD / DSUB} arithmetic in the body folds the double back to
            // the target precision. Gated on {@code paramFloatValues != null} so legacy
            // literal-stack walkers, which never see Math.cos / sin in their createBodyLayer
            // bodies, keep their byte-stable output.
            if (state.paramFloatValues != null
                && opcode == Opcodes.INVOKESTATIC
                && methodInsn.owner.equals("java/lang/Math")
                && (methodInsn.name.equals("cos") || methodInsn.name.equals("sin"))
                && methodInsn.desc.equals("(D)D")
                && !state.numStack.isEmpty()) {
                double arg = state.numStack.popNumber().doubleValue();
                double result = methodInsn.name.equals("cos")
                    ? Math.cos(arg)
                    : Math.sin(arg);
                state.numStack.push(result);
                return;
            }
            // {@code RandomSource.createThreadLocalInstance(J)} - seeded factory. Mojang's
            // {@code SingleThreadedRandomSource} ctor calls {@code setSeed} with the same LCG
            // as {@link java.util.Random#setSeed} (multiplier {@code 25214903917L}, increment
            // {@code 11L}, modulus mask {@code (1L << 48) - 1}). The subsequent
            // {@code BitRandomSource#nextInt(int)} default method is also identical to
            // {@code java.util.Random#nextInt(int)} - same power-of-2 fast path, same
            // rejection-sampling loop for non-power-of-2 bounds. So substituting
            // {@code new java.util.Random(seed)} produces bit-identical results. Pops the
            // long seed from numStack, stashes a fresh Random on
            // {@link ParseState#pendingRandomSource}; the next {@code ASTORE} binds it to a
            // local slot.
            //
            // <p>GhastModel.createBodyLayer uses seed {@code 1660L} to deterministically
            // produce 9 tentacle heights via repeated {@code nextInt(7) + 8} calls.
            if (state.paramFloatValues != null
                && opcode == Opcodes.INVOKESTATIC
                && methodInsn.owner.equals(VanillaSourceClasses.RANDOM_SOURCE)
                && methodInsn.name.equals("createThreadLocalInstance")
                && methodInsn.desc.equals("(J)Lnet/minecraft/util/RandomSource;")) {
                // The long seed isn't tracked on numStack (the parser's literal walk handles
                // int / float / double only). Walk back to the preceding {@code LDC2_W} or
                // {@code LCONST_0} / {@code LCONST_1} directly via {@link AsmKit#readLongLiteral}.
                AbstractInsnNode seedNode = AsmKit.previousReal(methodInsn);
                Long seed = seedNode != null ? AsmKit.readLongLiteral(seedNode) : null;
                if (seed != null) {
                    state.pendingRandomSource = new java.util.Random(seed);
                }
                return;
            }
            // {@code RandomSource.nextInt(I)I} via invokeinterface. Walks back over preceding
            // real instructions to find the {@code ALOAD <slot>} that pushed the random
            // reference; when the slot has a tracked {@link java.util.Random} AND the bound
            // operand is a real literal, steps the random and pushes the literal int result.
            // Otherwise pops the bound and pushes a non-literal marker so the JVM stack stays
            // aligned.
            if (state.paramFloatValues != null
                && opcode == Opcodes.INVOKEINTERFACE
                && methodInsn.owner.equals(VanillaSourceClasses.RANDOM_SOURCE)
                && methodInsn.name.equals("nextInt")
                && methodInsn.desc.equals("(I)I")
                && !state.numStack.isEmpty()) {
                Number bound = state.numStack.popLiteralNumber();
                if (bound != null) {
                    AbstractInsnNode boundNode = AsmKit.previousReal(methodInsn);
                    AbstractInsnNode aloadNode = boundNode != null ? AsmKit.previousReal(boundNode) : null;
                    if (aloadNode instanceof VarInsnNode aload && aload.getOpcode() == Opcodes.ALOAD) {
                        java.util.Random rng = state.localRandomSources.get(aload.var);
                        if (rng != null && bound.intValue() > 0) {
                            state.numStack.push(rng.nextInt(bound.intValue()));
                            return;
                        }
                    }
                }
                state.numStack.pushNonLiteral();
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
                if (inlined != null) inlineStaticMethodBody(inlined, methodInsn.desc, state, zip);
            }
        }

        /**
         * Walks {@code inlined}'s instructions on {@code state}, saving and restoring every
         * call-frame-local field (slot maps, pending pose, pending mirror, paramIntValues /
         * paramFloatValues) around the call. Output containers ({@link ParseState#bones},
         * {@link ParseState#boneMeta}, {@link ParseState#boneParents}) flow through unchanged
         * so bones flushed inside the callee land in the caller's geometry.
         *
         * <p>JVM local-variable slots are method-scoped: {@code astore_2} inside the callee
         * writes to a slot independent of the caller's slot 2, so the caller's {@code aload_2}
         * after the helper returns must not pick up the callee's bone bindings. Without this
         * reset, the callee's last flushed bone (e.g. {@code left_front_leg}) leaks into the
         * caller's slot map and the caller's next {@code addOrReplaceChild} mis-parents to a
         * leg instead of the mesh root. Builder-level {@link ParseState#pendingMirror} is
         * scoped to the current CubeListBuilder chain - without saving it across the recurse,
         * a callee that internally toggles mirror (HumanoidModel.createMesh's left-arm /
         * left-leg .mirror() calls) leaks the flag into the caller's continuation.
         *
         * <p>When {@code callDesc} is non-null and {@link ParseState#paramFloatValues} is
         * non-null, the call-site's numeric literals are captured into the callee's parameter
         * slots via {@link #captureInlineParams}. Vanilla shares quadruped construction in
         * {@code QuadrupedModel.createBodyMesh(int legSize, ...)} - the call site pushes
         * literal ints (pig's {@code bipush 6, iconst_1, iconst_0}); without this the inlined
         * method's {@code iload_0} resolves to a non-literal marker and downstream
         * {@code 18 - legSize} arithmetic produces 18 instead of 12. Pass {@code null} for
         * synthetic call sites (e.g. lambda-mediated MeshTransformer invokes) that have no
         * on-stack numeric args.
         */
        private static void inlineStaticMethodBody(
            @NotNull MethodNode inlined,
            @Nullable String callDesc,
            @NotNull ParseState state,
            @NotNull ZipFile zip
        ) {
            int[] previousInts = state.paramIntValues;
            float[] previousFloats = state.paramFloatValues;
            if (callDesc != null && state.paramFloatValues != null) {
                InlineParams params = captureInlineParams(callDesc, state, inlined.maxLocals);
                state.paramIntValues = params.ints;
                state.paramFloatValues = params.floats;
            }
            ConcurrentMap<Integer, String> savedLocalSlotBone = state.localSlotBone;
            ConcurrentMap<Integer, ConcurrentList<float[]>> savedSlotToCubes = state.slotToCubes;
            // JVM scoping: callee's ISTORE / FSTORE / DSTORE writes its own slot N, not the
            // caller's slot N. Swap a fresh numericLocals map for the callee and restore the
            // caller's on exit so a static helper's locals don't leak into the surrounding
            // factory's slot table.
            ConcurrentMap<Integer, Number> savedNumericLocals = state.numericLocals;
            // Same scoping applies to local primitive-array slots - the callee can't
            // see / write the caller's tracked float[] / int[] arrays.
            ConcurrentMap<Integer, float[]> savedLocalFloatArrays = state.localFloatArrays;
            Integer savedPendingFreshArrayLength = state.pendingFreshArrayLength;
            char savedPendingFreshArrayType = state.pendingFreshArrayType;
            ConcurrentMap<Integer, java.util.Random> savedLocalRandomSources = state.localRandomSources;
            java.util.Random savedPendingRandomSource = state.pendingRandomSource;
            String savedPendingPartName = state.pendingPartName;
            String savedBoneName = state.boneName;
            String savedParentBone = state.parentBone;
            String savedNextParent = state.nextParent;
            String savedLastFlushedBone = state.lastFlushedBone;
            ConcurrentList<float[]> savedPendingCubes = state.pendingCubes;
            int[] savedPendingUv = state.pendingUv;
            float[] savedPendingPivot = state.pendingPivot;
            float[] savedPendingRotation = state.pendingRotation;
            float savedPendingScale = state.pendingScale;
            boolean savedPendingMirror = state.pendingMirror;
            state.localSlotBone = Concurrent.newMap();
            state.slotToCubes = Concurrent.newMap();
            state.numericLocals = Concurrent.newMap();
            state.localFloatArrays = Concurrent.newMap();
            state.pendingFreshArrayLength = null;
            state.pendingFreshArrayType = '\0';
            state.localRandomSources = Concurrent.newMap();
            state.pendingRandomSource = null;
            state.pendingPartName = null;
            state.boneName = null;
            state.parentBone = null;
            state.nextParent = null;
            state.lastFlushedBone = null;
            state.pendingCubes = Concurrent.newList();
            state.pendingUv = new int[]{ 0, 0 };
            state.pendingPivot = new float[]{ 0, 0, 0 };
            state.pendingRotation = new float[]{ 0, 0, 0 };
            state.pendingScale = 1f;
            try {
                walkInstructions(inlined.instructions, state, zip);
            } finally {
                state.paramIntValues = previousInts;
                state.paramFloatValues = previousFloats;
                state.localSlotBone = savedLocalSlotBone;
                state.slotToCubes = savedSlotToCubes;
                state.numericLocals = savedNumericLocals;
                state.localFloatArrays = savedLocalFloatArrays;
                state.pendingFreshArrayLength = savedPendingFreshArrayLength;
                state.pendingFreshArrayType = savedPendingFreshArrayType;
                state.localRandomSources = savedLocalRandomSources;
                state.pendingRandomSource = savedPendingRandomSource;
                state.pendingPartName = savedPendingPartName;
                state.boneName = savedBoneName;
                state.parentBone = savedParentBone;
                state.nextParent = savedNextParent;
                state.lastFlushedBone = savedLastFlushedBone;
                state.pendingCubes = savedPendingCubes;
                state.pendingUv = savedPendingUv;
                state.pendingPivot = savedPendingPivot;
                state.pendingRotation = savedPendingRotation;
                state.pendingScale = savedPendingScale;
                state.pendingMirror = savedPendingMirror;
            }
        }

        /**
         * Snapshot of the captured inlined-method parameters; just a pair of arrays sized to
         * the callee's local-variable count.
         */
        private record InlineParams(int @NotNull [] ints, float @NotNull [] floats) {}

        /**
         * Captures call-site numeric literals as parameter slot values for an inlined static
         * method. Walks the descriptor's arg types in reverse, popping one numeric off
         * {@link ParseState#numStack} per primitive arg, and writes the value into the
         * matching slot of two parallel arrays sized to {@code maxLocals}. Reference args
         * (CubeDeformation, PartDefinition, etc.) skip the pop since they never appear on
         * {@code numStack}. Long / double args occupy two slots per JVM convention; only the
         * first slot receives the captured value.
         *
         * <p>Mirrors what the JVM does at an actual {@code invokestatic}: the args are popped
         * from the operand stack in reverse, then bound to slots 0..N for the callee. The
         * parser's symbolic stack carries only numeric values, so the capture pops only the
         * numeric subset and leaves slot bindings for refs as zero (the inlined method's
         * {@code aload} for a ref slot already doesn't touch {@code numStack}, so leaving the
         * slot zero is harmless).
         */
        private static @NotNull InlineParams captureInlineParams(
            @NotNull String descriptor,
            @NotNull ParseState state,
            int maxLocals
        ) {
            int slots = Math.max(maxLocals, 8);
            int[] ints = new int[slots];
            float[] floats = new float[slots];
            char[] argTypes = parseArgTypes(descriptor);
            int slotCursor = 0;
            int[] slotPerArg = new int[argTypes.length];
            for (int i = 0; i < argTypes.length; i++) {
                slotPerArg[i] = slotCursor;
                slotCursor += (argTypes[i] == 'D' || argTypes[i] == 'J') ? 2 : 1;
            }
            // Pop numeric args from top (last) to bottom (first) - matches stack reverse order.
            for (int i = argTypes.length - 1; i >= 0; i--) {
                char t = argTypes[i];
                if (t != 'L' && t != '[') {
                    // popNumber returns null on empty / non-numeric top; treat both as zero
                    // (matches the previous NON_LITERAL fallback's silent-zero arithmetic).
                    Number popped = state.numStack.popNumber();
                    int slot = slotPerArg[i];
                    if (slot < slots) {
                        ints[slot] = popped == null ? 0 : popped.intValue();
                        floats[slot] = popped == null ? 0f : popped.floatValue();
                    }
                }
            }
            return new InlineParams(ints, floats);
        }

        /**
         * Returns the arg-type characters from a JVM method descriptor in source order.
         * E.g. {@code (IZZLnet/minecraft/X;)V} yields {@code ['I', 'Z', 'Z', 'L']}. Reference
         * types collapse to {@code 'L'}; arrays collapse to {@code '['}. Used by
         * {@link #captureInlineParams} to decide which args are primitives that pop a numeric
         * off {@link ParseState#numStack}.
         */
        private static char @NotNull [] parseArgTypes(@NotNull String descriptor) {
            int paren = descriptor.indexOf('(');
            int close = descriptor.indexOf(')');
            if (paren < 0 || close < 0) return new char[0];
            java.util.List<Character> out = new java.util.ArrayList<>();
            int i = paren + 1;
            while (i < close) {
                char c = descriptor.charAt(i);
                if (c == 'L') {
                    out.add('L');
                    int end = descriptor.indexOf(';', i);
                    if (end < 0) return new char[0];
                    i = end + 1;
                } else if (c == '[') {
                    out.add('[');
                    while (i < close && descriptor.charAt(i) == '[') i++;
                    if (i < close && descriptor.charAt(i) == 'L') {
                        int end = descriptor.indexOf(';', i);
                        if (end < 0) return new char[0];
                        i = end + 1;
                    } else {
                        i++;
                    }
                } else {
                    out.add(c);
                    i++;
                }
            }
            char[] arr = new char[out.size()];
            for (int j = 0; j < out.size(); j++) arr[j] = out.get(j);
            return arr;
        }

        /**
         * Collects the string-typed args of an immediately-preceding {@code Set.of(...)} call.
         * Walks backwards from {@code methodInsn} through the InsnList, skipping pseudo-nodes
         * (line numbers, frames, labels) until {@code expectedCount} {@link LdcInsnNode} String
         * loads have been gathered or a non-string instruction is hit. Source order is preserved
         * (oldest LDC first). Returns {@code null} when the expected count couldn't be matched
         * - the caller treats null as "no filter" so a malformed walk doesn't drop every bone.
         *
         * <p>Used by the {@code Set.of} dispatch in {@link #handleMethodInsn} to capture the
         * retained bone names ahead of a {@code retainPartsAndChildren} call. Only the
         * fixed-arity overloads ({@code Set.of()} through {@code Set.of(Object x10)}) are
         * recognised; the varargs {@code Set.of(Object[])} overload would need an
         * {@code anewarray}/{@code aastore} walker and isn't used by any vanilla entity factory
         * observed so far.
         */
        private static @Nullable Set<String> collectSetOfStringArgs(@NotNull MethodInsnNode methodInsn) {
            char[] argTypes = parseArgTypes(methodInsn.desc);
            // Varargs Set.of([Ljava/lang/Object;) collapses to a single '[' arg - skip it for now.
            for (char t : argTypes) if (t != 'L') return null;
            int expectedCount = argTypes.length;
            Set<String> names = new LinkedHashSet<>();
            java.util.Deque<String> collected = new java.util.ArrayDeque<>();
            AbstractInsnNode prev = methodInsn.getPrevious();
            while (prev != null && collected.size() < expectedCount) {
                if (prev instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                    collected.addFirst(s);
                } else if (AsmKit.isPseudoNode(prev)) {
                    // line number / frame / label nodes - skip silently
                } else {
                    return null;
                }
                prev = prev.getPrevious();
            }
            if (collected.size() != expectedCount) return null;
            names.addAll(collected);
            return names;
        }

        /**
         * Warns when {@code state.numStack} has fewer than {@code required} entries at a
         * builder-dispatch site. The pop still proceeds with zero-fill (via
         * {@link #popIntWithDiagnostics} / {@link #popFloatWithDiagnostics}'s empty-stack
         * fallback), but the diagnostic surfaces the underflow so a bogus-coord cube doesn't
         * silently ship.
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
                    //
                    // The parentBone snapshot is conditional on having a pendingPartName so
                    // shared-builder factories (DonkeyModel.modifyMesh pre-builds one chest
                    // CubeListBuilder and reuses it for left_chest + right_chest) don't
                    // capture stale {@code nextParent} from a previous bone group. The
                    // shared-builder pattern is: create() with no preceding ldc String, then
                    // later aload(parent), aload(builder_slot), ldc(name), PartPose,
                    // addOrReplaceChild. By the time the flush fires, the parent has been
                    // re-aload'd into {@code nextParent} so resolvedParent picks it up
                    // through the nextParent fallback in {@link #flushPendingBone}.
                    if (state.pendingPartName != null) {
                        state.boneName = state.pendingPartName;
                        state.parentBone = state.nextParent;
                    }
                    state.nextParent = null;
                    state.lastFlushedBone = null;
                    // Each new builder starts fresh - clear the mirror flag so it doesn't leak
                    // from a previous bone's CubeListBuilder.mirror() call. Vanilla constructs a
                    // new CubeListBuilder per addOrReplaceChild via create(), and the new
                    // builder's mirror starts false.
                    state.pendingMirror = false;
                }
                case "texOffs" -> {
                    if (methodInsn.desc.startsWith("(II")) {
                        requireStack(state, 2, "CubeListBuilder.texOffs(II)");
                        state.pendingUv[1] = popIntWithDiagnostics(state, "CubeListBuilder.texOffs(II) v");
                        state.pendingUv[0] = popIntWithDiagnostics(state, "CubeListBuilder.texOffs(II) u");
                    }
                }
                case "addBox" -> {
                    // Five addBox variants observed in vanilla (names/CubeDeformation refs don't
                    // land on numStack, so only the numeric literals + boolean mirror flag drive
                    // the pop order):
                    //  1. (FFFFFF) or (FFFFFF + CubeDeformation) - origin xyz + size whd; uses current texOffs.
                    //  2. (FFFFFFZ) or (FFFFFFZ + CubeDeformation) - origin + size + mirror flag.
                    //     {@code GuardianModel.createBodyLayer}'s third head cube uses this with
                    //     {@code mirror=true}; without popping the boolean, the numStack top
                    //     consumed as the d-size is the mirror int (1), not the actual depth.
                    //  3. (Ljava/lang/String;FFFFFF) - named single-cube, uses current texOffs.
                    //  4. (Ljava/lang/String;FFFIIIII) - named multi-cube with inline (w,h,d,u,v) ints.
                    //     Dragon's head bone stacks 6 cubes this way, each with its own UV.
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
                        emitCube(state, x, y, z, w, h, d, u, v);
                    } else if (methodInsn.desc.startsWith("(Ljava/lang/String;FFFIII")
                            && methodInsn.desc.contains("CubeDeformation;II")) {
                        // Variant: addBox(name, F, F, F, I, I, I, CubeDeformation, I, I) - named
                        // multi-cube with int dimensions (w, h, d) plus per-cube UV (u, v) split by
                        // a CubeDeformation arg. Used by AdultFelineModel.createBodyMesh's nose /
                        // ear / tail bones (cat / ocelot family). The CubeDeformation is an object
                        // ref, not on numStack; pop the 5 ints + 3 floats around it.
                        requireStack(state, 8, "CubeListBuilder.addBox(name,FFFIII,CubeDeformation,II)");
                        int v = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) v");
                        int u = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) u");
                        int d = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) d");
                        int h = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) h");
                        int w = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) w");
                        float z = popFloatWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) z");
                        float y = popFloatWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) y");
                        float x = popFloatWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) x");
                        emitCube(state, x, y, z, w, h, d, u, v);
                    } else if (methodInsn.desc.startsWith("(FFFFFFZ") || methodInsn.desc.startsWith("(Ljava/lang/String;FFFFFFZ")) {
                        // Mirror-flagged addBox: pop the trailing boolean first so the float
                        // pop order isn't shifted by one. {@code mirror=true} flips face UVs on
                        // this cube only (does not affect the builder's pendingMirror state for
                        // subsequent cubes).
                        requireStack(state, 7, "CubeListBuilder.addBox(FFFFFFZ)");
                        int cubeMirror = popIntWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) mirror");
                        float d = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) d");
                        float h = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) h");
                        float w = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) w");
                        float z = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) z");
                        float y = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) y");
                        float x = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) x");
                        boolean savedMirror = state.pendingMirror;
                        if (state.paramFloatValues != null) state.pendingMirror = cubeMirror != 0;
                        emitCube(state, x, y, z, w, h, d, state.pendingUv[0], state.pendingUv[1]);
                        state.pendingMirror = savedMirror;
                    } else if (methodInsn.desc.startsWith("(FFFFFF") || methodInsn.desc.startsWith("(Ljava/lang/String;FFFFFF")) {
                        requireStack(state, 6, "CubeListBuilder.addBox(FFFFFF)");
                        float d = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) d");
                        float h = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) h");
                        float w = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) w");
                        float z = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) z");
                        float y = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) y");
                        float x = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) x");
                        emitCube(state, x, y, z, w, h, d, state.pendingUv[0], state.pendingUv[1]);
                    }
                }
                case "mirror" -> {
                    // CubeListBuilder.mirror(Z) sets the builder's mirror flag explicitly.
                    // CubeListBuilder.mirror() is a no-arg shortcut for mirror(true) - vanilla's
                    // AbstractEquineModel.createBodyMesh / similar use this for the right-side
                    // legs / right ear to flip UVs so the leg's outer face draws the same texture
                    // region as the left leg's outer face. Captured into
                    // {@link ParseState#pendingMirror} so it propagates to the cube's emitted
                    // {@code mirror} field; the kit's UV resolution then flips face UVs
                    // horizontally for those cubes (already wired via
                    // {@code rect.toUvCorners(..., mirror)}).
                    if (methodInsn.desc.startsWith("(Z")) {
                        requireStack(state, 1, "CubeListBuilder.mirror(Z)");
                        int mirrorVal = popIntWithDiagnostics(state, "CubeListBuilder.mirror(Z)");
                        if (state.paramFloatValues != null)
                            state.pendingMirror = mirrorVal != 0;
                    } else if (methodInsn.desc.startsWith("()")) {
                        // No-arg mirror() is the equivalent of mirror(true).
                        if (state.paramFloatValues != null)
                            state.pendingMirror = true;
                    }
                }
                default -> { }
            }
        }

        /**
         * Appends one cube to {@link ParseState#pendingCubes}, capturing the current
         * {@link ParseState#pendingInflate} as the cube's inflate scalar, then resets the
         * pending inflate to {@code 0f} so it doesn't leak into the next addBox in the same
         * builder chain. Cube layout is {@code [x, y, z, w, h, d, u, v, inflate]}.
         */
        private static void emitCube(@NotNull ParseState state, float x, float y, float z, float w, float h, float d, int u, int v) {
            // Cube layout slot 9: mirror flag (0f = not mirrored, 1f = mirrored). Per-cube
            // mirror-flagged addBox variants pre-set {@code state.pendingMirror} and restore it
            // after; builder-level {@code mirror(Z)} state persists until changed.
            state.pendingCubes.add(new float[]{
                x, y, z, w, h, d, u, v, state.pendingInflate, state.pendingMirror ? 1f : 0f
            });
            // Reset to the source's defaultInflate (zero for normal entity sources, non-zero for
            // composite-overlay sources whose factory takes a {@code CubeDeformation} arg) so
            // every cube in the chain picks up the call-site's deformation by default. Inline
            // {@code new CubeDeformation(F)} / {@code .extend(F)} per-cube overrides still run
            // through their handlers and replace pendingInflate before the next emitCube fires.
            state.pendingInflate = state.defaultInflate;
            // Clear the inline-deformation marker too - if a {@code new CubeDeformation(F)} was
            // consumed inline by THIS addBox (no intervening ASTORE), it shouldn't leak into the
            // next ASTORE and accidentally tag an unrelated slot.
            state.pendingFreshDeformationInflate = null;
        }

        /**
         * Handles {@code CubeDeformation.extend(F)} / {@code .extend(FFF)} which consume float
         * inflate args from the operand stack adjacent to a subsequent {@code CubeListBuilder
         * .addBox}. Without this, the inflate arg leaks onto {@link ParseState#numStack} and
         * shifts the addBox pop order, producing garbage cube dimensions (HumanoidModel's hat
         * bone is the canonical example: a {@code .extend(0.5f)} on the deformation arg
         * leaves {@code 0.5} above the addBox's d-arg, so addBox sees d=0.5 and h/w/z/y/x
         * shifted one slot down).
         *
         * <p>Constructor variants ({@code CubeDeformation.<init>(F)} / {@code <init>(FFF)})
         * are gated behind {@code paramFloatValues != null} for byte-stability - the existing
         * leftover-at-bottom behaviour for inline-constructed deformations doesn't corrupt
         * subsequent addBox pops in the legacy patterns we currently parse, but the new
         * Java-side sources benefit from the cleaner numStack.
         */
        private static void handleCubeDeformation(@NotNull MethodInsnNode methodInsn, @NotNull ParseState state) {
            if ("extend".equals(methodInsn.name)) {
                if (methodInsn.desc.startsWith("(FFF")) {
                    requireStack(state, 3, "CubeDeformation.extend(FFF)");
                    float ez = popFloatWithDiagnostics(state, "CubeDeformation.extend(FFF) z");
                    float ey = popFloatWithDiagnostics(state, "CubeDeformation.extend(FFF) y");
                    float ex = popFloatWithDiagnostics(state, "CubeDeformation.extend(FFF) x");
                    if (state.paramFloatValues != null)
                        state.pendingInflate = state.pendingInflate + (ex + ey + ez) / 3f;
                } else if (methodInsn.desc.startsWith("(F")) {
                    requireStack(state, 1, "CubeDeformation.extend(F)");
                    float e = popFloatWithDiagnostics(state, "CubeDeformation.extend(F)");
                    if (state.paramFloatValues != null) state.pendingInflate = state.pendingInflate + e;
                }
                return;
            }
            if (AsmKit.INIT.equals(methodInsn.name) && state.paramFloatValues != null) {
                if (methodInsn.desc.startsWith("(FFF")) {
                    requireStack(state, 3, "CubeDeformation.<init>(FFF)");
                    float dz = popFloatWithDiagnostics(state, "CubeDeformation.<init>(FFF) z");
                    float dy = popFloatWithDiagnostics(state, "CubeDeformation.<init>(FFF) y");
                    float dx = popFloatWithDiagnostics(state, "CubeDeformation.<init>(FFF) x");
                    state.pendingInflate = (dx + dy + dz) / 3f;
                    state.pendingFreshDeformationInflate = state.pendingInflate;
                } else if (methodInsn.desc.startsWith("(F")) {
                    requireStack(state, 1, "CubeDeformation.<init>(F)");
                    state.pendingInflate = popFloatWithDiagnostics(state, "CubeDeformation.<init>(F)");
                    state.pendingFreshDeformationInflate = state.pendingInflate;
                }
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
            if (name != null) {
                // Flatten parent-child hierarchy at parse time. Vanilla renders children with
                // pose T(parent.pivot) * R(parent.rot) * S(parent.scale) * T(child.local_pivot)
                // * R(child.local_rot) * S(child.scale), then draws child cubes from the bone's
                // local frame. To present the entity_geometry JSON consumer with a flat
                // (world_pivot, world_rotation, world_scale) per bone, fold the parent's
                // already-flattened transform into the child's:
                //   world_pivot = parent.world_pivot + parent.world_rot * (parent.world_scale * child.local_pivot)
                //   world_rot   = parent.world_rot * R_zyx(child.local_rot)
                //   world_scale = parent.world_scale * child.local_scale
                // For parents with no rotation (every legacy literal-stack walker) this collapses
                // back to the legacy additive-translation behaviour, so unrotated parents are a
                // no-op. Java entity factories like FoxModel.createBodyLayer DO have rotated
                // parents (body 90deg pitch with tail / legs as children) - flattening with
                // rotation propagation is what places the tail behind the body instead of
                // pointing straight down at the unrotated body.pivot + tail.local_pivot location.
                // Fall back to nextParent when parentBone wasn't captured at a
                // CubeListBuilder.create() call. Vanilla's pre-built-builder pattern
                // ({@code AdultAxolotlModel.createBodyLayer} pre-builds gill / leg cube lists into
                // local slots 5-9 before reusing them across multiple {@code addOrReplaceChild}
                // calls) doesn't fire {@code create()} between the parent's {@code aload} and the
                // child's flush, so parentBone stays null. nextParent is still set from the most
                // recent {@code aload} of the parent's PartDefinition slot, so it's the right
                // fallback. For the standard chain (where create() captures parentBone) nextParent
                // is null at flush time so this fallback is a no-op.
                String resolvedParent = state.parentBone != null ? state.parentBone : state.nextParent;
                float[] worldPivot = state.pendingPivot;
                float[] worldRotation = state.pendingRotation;
                Matrix4f worldRotMatrix = eulerZyxToMatrix(state.pendingRotation);
                float worldScale = state.pendingScale;
                if (resolvedParent != null) {
                    BoneMeta parent = state.boneMeta.get(resolvedParent);
                    if (parent != null) {
                        float[] scaledLocal = {
                            parent.scale * state.pendingPivot[0],
                            parent.scale * state.pendingPivot[1],
                            parent.scale * state.pendingPivot[2]
                        };
                        float[] rotatedLocal = rotateVec(parent.rotMatrix, scaledLocal);
                        worldPivot = new float[]{
                            parent.pivot[0] + rotatedLocal[0],
                            parent.pivot[1] + rotatedLocal[1],
                            parent.pivot[2] + rotatedLocal[2]
                        };
                        worldScale = parent.scale * state.pendingScale;
                        // Column-vector composition: parent rotation applies AFTER child's local
                        // rotation, so it's leftmost in the multiply chain. v_world = parent *
                        // (worldRotMatrix * v_local).
                        worldRotMatrix = parent.rotMatrix.multiply(worldRotMatrix);
                        worldRotation = matrixToEulerZyx(worldRotMatrix);
                    }
                }
                // Pose-only parent bones (e.g. wolf "head" / "tail" - holds the pivot for cube-
                // bearing children "real_head" / "real_tail") are flushed with empty cubes. They
                // still need a {@link BoneMeta} entry so the next child's flatten can find them
                // through the parent chain; just skip the JSON emission since a cube-less bone
                // contributes no triangles. Without this, child bones that name a pose-only parent
                // miss the boneMeta lookup and inherit a world pivot of (0, 0, 0).
                if (!state.pendingCubes.isEmpty())
                    state.bones.add(name, buildBone(worldPivot, worldRotation, worldScale, state.pendingCubes));
                state.boneMeta.put(name, new BoneMeta(worldPivot, worldScale, worldRotMatrix));
                // Record the resolved parent so the post-walk retainedNames filter
                // ({@link #parseLayerMethod}) can chase the ancestor chain. Root-level bones
                // (children of the mesh root, no PartDefinition parent) map to a null parent.
                state.boneParents.put(name, resolvedParent);
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

        /**
         * Mutable parse state threaded through one top-level method parse (plus any inlined invokestatic targets).
         */
        private static final class ParseState {

            /**
             * Bounded retention for the symbolic operand stack: 16 entries is comfortably above
             * the deepest single-expression stack vanilla bytecode pushes (PartPose six-float
             * factory + a few coercions) while still being a hard cap that surfaces parser
             * accounting bugs as overflow warnings rather than runaway growth.
             */
            private static final int NUM_STACK_CAPACITY = 16;

            final @NotNull AsmKit.LiteralStack numStack = new AsmKit.LiteralStack(NUM_STACK_CAPACITY);

            /**
             * Int values to substitute for {@code ILOAD_N} parameters when evaluating branches.
             * {@code paramIntValues[N]} is pushed onto {@link #branchStack} whenever an iload
             * references slot {@code N}, so the subsequent {@code IFEQ} / {@code IFNE} pops a
             * concrete value and jumps (or not). {@code null} disables branch evaluation -
             * the parser falls back to its default linear walk and lets both sides of any
             * conditional land on {@link #numStack}.
             */
            int @Nullable [] paramIntValues;

            /**
             * Float values to substitute for {@code FLOAD slot} parameter loads when
             * evaluating arithmetic. {@code null} disables float param substitution AND
             * arithmetic evaluation entirely (the legacy behaviour). When non-null,
             * see {@link Source#paramFloatValues()} for the substitution rules.
             */
            float @Nullable [] paramFloatValues;

            /**
             * Pushed by ILOAD when the slot maps to a paramIntValues entry; consumed by IFEQ / IFNE.
             */
            final @NotNull ConcurrentList<Integer> branchStack = Concurrent.newList();

            /**
             * Most recent ldc String - tracks both bone names and inner cube names.
             */
            @Nullable String pendingPartName;

            /**
             * Snapshot of {@link #pendingPartName} at CubeListBuilder.create(); preserved across inner ldc Strings from addBox(String, ...) variants.
             */
            @Nullable String boneName;

            /**
             * Parent bone name captured from {@link #nextParent} at CubeListBuilder.create().
             */
            @Nullable String parentBone;

            /**
             * Parent bone captured from the most recent aload_N; consumed by CubeListBuilder.create().
             */
            @Nullable String nextParent;

            /**
             * Most recently flushed bone; the next astore_N after flush binds it to that slot.
             */
            @Nullable String lastFlushedBone;

            /**
             * JVM local-variable slot -> bone name that was stored there via astore_N.
             */
            @NotNull ConcurrentMap<Integer, String> localSlotBone = Concurrent.newMap();

            /**
             * JVM local-variable slot -> captured CubeListBuilder cubes, for builders reused by multiple addOrReplaceChild calls.
             */
            @NotNull ConcurrentMap<Integer, ConcurrentList<float[]>> slotToCubes = Concurrent.newMap();

            /**
             * JVM local-variable slot -> last numeric value stored to that slot by ISTORE /
             * FSTORE / DSTORE / LSTORE. Read back on ILOAD / FLOAD / DLOAD / LLOAD before the
             * {@link #paramIntValues} / {@link #paramFloatValues} fallback, so a
             * {@code ldc <value>; fstore <slot>; ...; fload <slot>} sequence (vanilla
             * {@code BlazeModel.createBodyLayer}'s rolling-angle accumulator slot 2;
             * {@code SquidModel.createBodyLayer}'s reused-angle double slot 7) folds back to the
             * literal value instead of the param-table default. Reset by
             * {@link #inlineStaticMethodBody} so JVM scoping (callee locals don't leak into
             * caller) is preserved.
             */
            @NotNull ConcurrentMap<Integer, Number> numericLocals = Concurrent.newMap();

            /**
             * JVM local-variable slot -> tracked {@code float[]} created by a
             * {@code NEWARRAY float; ASTORE <slot>} pair earlier in the method. Vanilla's
             * {@code SilverfishModel.createBodyLayer} uses this for its cumulative-pivot
             * {@code float[7]}: each loop iteration writes {@code f[i] = currentF} via
             * {@code FASTORE} and the post-loop layer bones read back via
             * {@code FALOAD}. Cleared in {@link #inlineStaticMethodBody} so the callee can't
             * leak its local arrays into the caller's scope.
             */
            @NotNull ConcurrentMap<Integer, float[]> localFloatArrays = Concurrent.newMap();

            /**
             * JVM local-variable slot -> tracked {@link java.util.Random} created by a seeded
             * {@code RandomSource.createThreadLocalInstance(J)} factory + {@code ASTORE} pair.
             * Each subsequent {@code aload <slot>; <bound>; invokeinterface
             * RandomSource.nextInt(I)I} sequence steps the random and pushes the literal
             * result. Vanilla's {@code GhastModel.createBodyLayer} uses seed {@code 1660L} to
             * deterministically produce tentacle heights; the parser substitutes the same
             * {@link java.util.Random} algorithm (Mojang's {@code BitRandomSource} matches the
             * standard LCG bit-for-bit). Cleared in {@link #inlineStaticMethodBody}.
             */
            @NotNull ConcurrentMap<Integer, java.util.Random> localRandomSources = Concurrent.newMap();

            /**
             * Random instance captured by a {@code RandomSource.createThreadLocalInstance(J)}
             * invokestatic that hasn't yet been bound to a slot via the subsequent
             * {@code ASTORE}. Reset on consumption. Non-null only across that single
             * createThreadLocalInstance-then-ASTORE pair.
             */
            @Nullable java.util.Random pendingRandomSource;

            /**
             * Length captured by a {@code NEWARRAY <T>} instruction that hasn't yet been bound
             * to a slot via the subsequent {@code ASTORE}. Reset on consumption. Non-null only
             * across a single {@code NEWARRAY -> ASTORE} pair.
             */
            @Nullable Integer pendingFreshArrayLength;

            /**
             * Element-type tag for the pending {@code NEWARRAY} - {@code 'F'} for float,
             * {@code 'I'} for int, {@code '\0'} when no pending. Mirrors {@code IntInsnNode
             * .operand} via {@code T_FLOAT} / {@code T_INT}.
             */
            char pendingFreshArrayType;

            /**
             * Flattened pivot + scale for each flushed bone, used to resolve child inheritance.
             */
            final @NotNull ConcurrentMap<String, BoneMeta> boneMeta = Concurrent.newMap();

            /**
             * Child-bone -> resolved-parent-bone, populated by {@link #flushPendingBone}. Walks
             * with {@link #retainedNames} to decide which bones to keep after a
             * {@code PartDefinition.retainPartsAndChildren} call. Root-level bones (children of
             * the mesh root, no PartDefinition parent) map to {@code null}.
             */
            final @NotNull Map<String, String> boneParents = new LinkedHashMap<>();

            /**
             * Bone names captured from a {@code Set.of(...)} call immediately preceding a
             * {@link #retainedNames}-bound {@code retainPartsAndChildren} dispatch. Walked back
             * from the {@code Set.of} {@code MethodInsnNode} since the parser doesn't carry a
             * reference stack. Null when no pending capture is active.
             */
            @Nullable Set<String> pendingRetainSet;

            /**
             * Bones to keep when filtering after the parse. Populated by
             * {@code PartDefinition.retainPartsAndChildren(Set)} from {@link #pendingRetainSet}.
             * After {@link #walkInstructions} returns, every emitted bone whose ancestor chain
             * (self -> root) contains no name in this set is dropped from {@link #bones}. Null
             * means no filter was applied. Vanilla's {@code retainPartsAndChildren} replaces a
             * non-retained bone's cubes with empty (recursing into its children); since
             * {@link #flushPendingBone} skips JSON emission for cube-less bones, "strip cubes"
             * and "drop bone from JSON" produce the same render output. Only set when
             * {@code paramFloatValues != null} so legacy walker parses are unaffected.
             */
            @Nullable Set<String> retainedNames;

            /**
             * Bone names removed via {@code PartDefinition.clearChild(String)} after the bone
             * was already flushed (vanilla pattern: build a sub-tree via a shared helper, then
             * post-prune unwanted children). Canonical case: {@code AdultPiglinModel.createBodyLayer}
             * inherits a "hat" bone via {@code PlayerModel.createMesh}, then calls
             * {@code head.clearChild("hat")} to drop it. Applied in
             * {@link #applyClearedBonesFilter} after the walk, which also drops descendants
             * (since {@code clearChild} cascades through the child's sub-tree in vanilla).
             * Only populated when {@code paramFloatValues != null}.
             */
            final @NotNull Set<String> clearedBones = new LinkedHashSet<>();

            /**
             * Cubes accumulated for the current builder chain, flushed by the next {@code addOrReplaceChild}.
             */
            @NotNull ConcurrentList<float[]> pendingCubes = Concurrent.newList();

            /**
             * Current {@code texOffs(u, v)} values used by subsequent {@code addBox} variants that omit inline UV.
             */
            int @NotNull [] pendingUv = { 0, 0 };

            /**
             * Current {@code PartPose} pivot for the next bone flush; defaults to origin.
             */
            float @NotNull [] pendingPivot = { 0, 0, 0 };

            /**
             * Current {@code PartPose} rotation (Euler degrees) for the next bone flush.
             */
            float @NotNull [] pendingRotation = { 0, 0, 0 };

            /**
             * Uniform scale from {@code PartPose.scaled}; {@code 1f} when no scale was applied.
             */
            float pendingScale = 1f;

            /**
             * Whole-layer uniform scale captured from {@code MeshTransformer.scaling(F)} call(s)
             * on the {@code LayerDefinition}; {@code 1f} when no MeshTransformer is applied.
             * Re-walked into the emitted bone tree by {@link #applyMeshTransformerScaling} after
             * {@link #walkInstructions} returns. Multiplies on subsequent calls so
             * {@code .apply(scaling(a)).apply(scaling(b))} composes as {@code a * b}.
             */
            float meshTransformerScale = 1f;

            /**
             * Cache of {@code <clinit>}-resolved {@code static final MeshTransformer} field values
             * keyed by {@code "owner/internal/Name.FIELD_NAME"}. Populated lazily by
             * {@link #resolveStaticMeshTransformer} when the body walker hits a {@code GETSTATIC}
             * on a MeshTransformer field. Stores {@code null} for fields whose {@code <clinit>}
             * initialiser uses a non-scaling MeshTransformer factory (combined, invokedynamic) so
             * we don't re-walk those repeatedly.
             */
            final @NotNull ConcurrentMap<String, Float> resolvedMeshTransformers = Concurrent.newMap();

            /**
             * Uniform inflate captured from the most recent {@code new CubeDeformation(F)} or
             * {@code .extend(F)} call; consumed by the next {@code addBox} variant and reset
             * to {@code 0f} after the cube emits. Asymmetric {@code (FFF)} variants average
             * the three components since {@link Cube}
             * only carries a scalar inflate. Only populated when {@code paramFloatValues != null}
             * (the Java pipeline opts in); legacy block-entity sources never set the
             * gating field so existing parses emit {@code inflate: 0} unchanged.
             */
            float pendingInflate = 0f;

            /**
             * Current builder-level mirror flag set by {@code CubeListBuilder.mirror(true)} -
             * applies to every subsequent {@code addBox} cube until the builder hits a
             * {@code mirror(false)} call or the chain ends. Captured on the
             * {@code CubeListBuilder.mirror(Z)} dispatch (the boolean is popped into this slot
             * instead of being discarded). The {@code addBox(..., Z, ...)} mirror-flagged
             * variants override this on a per-cube basis. Vanilla's
             * {@code AbstractEquineModel.createBodyMesh} flips {@code mirror=true} for the
             * right-side legs / right ear so the leg's outer-face UV draws the same texture
             * region as the left leg's outer face rather than its mirror; without propagating
             * this through to the kit, both right legs render facing the wrong way (the
             * skeleton-horse user report). The kit already consumes
             * {@link EntityModelData.Cube#isMirror()} via {@code rect.toUvCorners(..., mirror)}.
             */
            boolean pendingMirror = false;

            /**
             * The factory's default {@code CubeDeformation} inflate, captured at the call site
             * in {@link EntityLayerDefinitionResolver} (e.g. {@code 0.25} for
             * {@code DROWNED_OUTER_LAYER}'s {@code DrownedModel.createBodyLayer(new
             * CubeDeformation(0.25F))}). {@link #pendingInflate} resets to this value after every
             * {@code emitCube} so all cubes in the factory pick up the call-site-provided inflate
             * by default, while inline {@code new CubeDeformation(F)} / {@code .extend(F)}
             * per-cube overrides still take precedence on the next addBox. Zero for normal entity
             * sources whose factory takes no {@code CubeDeformation} arg.
             */
            float defaultInflate = 0f;

            /**
             * JVM local-variable slot -> CubeDeformation inflate value, populated when a
             * {@code new CubeDeformation(F); <init>} is immediately followed by {@code astore N}
             * and the slot is then re-loaded later via {@code aload N} before a subsequent
             * {@code addBox(..., CubeDeformation)} call. Vanilla's
             * {@code AdultBeeModel.createBodyLayer} stores a {@code CubeDeformation(0.001F)} into
             * a local slot once and reuses it for BOTH wing addBox calls; without slot tracking,
             * the second addBox emits {@code inflate=0} because {@link #pendingInflate} resets
             * to {@link #defaultInflate} after each {@code emitCube}. Re-hydrated by the ALOAD
             * handler so the next addBox picks up the correct inflate. Only populated when
             * {@code paramFloatValues != null}.
             */
            final @NotNull java.util.Map<Integer, Float> cubeDeformationSlots = new java.util.HashMap<>();

            /**
             * Inflate value of the most recent {@code new CubeDeformation(F); <init>(F)} that
             * hasn't yet been ASTORE'd or consumed by an inline addBox. ASTORE captures into
             * {@link #cubeDeformationSlots}; emitCube clears it. Null when no fresh deformation
             * is pending.
             */
            @Nullable Float pendingFreshDeformationInflate;

            /**
             * Accumulated per-bone JSON objects keyed by bone name. Written to the final model.
             */
            final @NotNull JsonObject bones = new JsonObject();

            /**
             * Texture width extracted from {@code LayerDefinition.create(mesh, W, H)}; defaults to 64.
             */
            int texWidth = 64;

            /**
             * Texture height extracted from {@code LayerDefinition.create(mesh, W, H)}; defaults to 64.
             */
            int texHeight = 64;

            /**
             * The top-level source whose bytecode is being parsed. Used to tag diagnostics.
             */
            @Nullable Source currentSource;

            /**
             * Diagnostics sink for strict-mode surfacing of silent failures.
             */
            @Nullable Diagnostics diagnostics;

            /**
             * Set after the first overflow warn so a single parse doesn't spam the log.
             */
            boolean overflowWarned;

        }

        /**
         * Parent lookup data: the bone's pivot, scale, and accumulated rotation in
         * world-flattened form. The rotation matrix carries the entire parent-chain composition
         * (Z * Y * X applied right-to-left, matching {@link
         * net.minecraft.client.model.geom.PartPose}'s convention) so child bones can rotate
         * their local pivots into the parent's frame before adding the parent's translation.
         * Legacy literal-stack walkers never set a non-identity rotation on a bone with
         * children, so {@code rotMatrix} stays identity and the math collapses to the legacy
         * additive-translation behaviour for them.
         */
        private record BoneMeta(float @NotNull [] pivot, float scale, @NotNull Matrix4f rotMatrix) {}

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

                // Legacy block-entity sources never set paramFloatValues so their cubes
                // carry length-8 arrays with no inflate / mirror slot - write 0 / false in that
                // case to keep the wire format identical. Java sources go through emitCube which
                // captures the CubeDeformation inflate at index 8 and the {@code mirror} flag at
                // index 9 (propagated from CubeListBuilder.mirror or per-cube addBox(...Z...)).
                cube.addProperty("inflate", c.length >= 9 ? c[8] : 0.0f);
                cube.addProperty("mirror", c.length >= 10 && c[9] != 0f);
                cube.add("face_uv", new JsonObject());
                cubeArray.add(cube);
            }
            bone.add("cubes", cubeArray);
            return bone;
        }

        /**
         * Builds a {@link JsonArray} from a variadic float list.
         */
        private static @NotNull JsonArray floatArray(float @NotNull ... values) {
            JsonArray arr = new JsonArray();
            for (float v : values) arr.add(v);
            return arr;
        }

        /**
         * Builds a column-vector rotation matrix from Euler angles in degrees, applied as
         * {@code R = Rz(roll) * Ry(yaw) * Rx(pitch)} - the same Z * Y * X order vanilla Java's
         * {@code Matrix4f.rotateZYX} uses for {@link
         * net.minecraft.client.model.geom.PartPose#offsetAndRotation PartPose.offsetAndRotation}.
         * Input array is {@code [pitch_deg, yaw_deg, roll_deg]}. Routes through
         * {@link Quaternionf#rotationZYX} so the result is bit-identical to vanilla's
         * quaternion-derived rotation matrix.
         */
        private static @NotNull Matrix4f eulerZyxToMatrix(float @NotNull [] eulerDegrees) {
            return Quaternionf.rotationZYX(
                (float) Math.toRadians(eulerDegrees[2]),
                (float) Math.toRadians(eulerDegrees[1]),
                (float) Math.toRadians(eulerDegrees[0])
            ).toMatrix4f();
        }

        /**
         * Rotates a 3-vector by a {@link Matrix4f} rotation as {@code m * v_col}.
         */
        private static float @NotNull [] rotateVec(@NotNull Matrix4f m, float @NotNull [] v) {
            Vector3f r = Vector3f.transformNormal(new Vector3f(v[0], v[1], v[2]), m);
            return new float[]{ r.x(), r.y(), r.z() };
        }

        /**
         * Decomposes a column-vector rotation matrix back into {@code [pitch_deg, yaw_deg,
         * roll_deg]} for the Z * Y * X convention. The closed-form recovery reads the matrix's
         * third row: {@code -sin(yaw) = m.get(1, 3)},
         * {@code pitch = atan2(m.get(2, 3), m.get(3, 3))},
         * {@code roll  = atan2(m.get(1, 2), m.get(1, 1))}. Agrees with the inverse of
         * {@link #eulerZyxToMatrix} on every input that doesn't sit at the
         * {@code yaw = +/- 90deg} gimbal-lock pole. None of the entity factories observed compose
         * rotations near that pole (vanilla animations stay in single-axis pitches like body
         * 90deg X), so the canonical decomposition is used; if a future model lands at the pole
         * the recovered Euler triple still represents the same rotation, just split differently
         * between yaw and roll.
         */
        private static float @NotNull [] matrixToEulerZyx(@NotNull Matrix4f m) {
            float syNeg = m.get(1, 3);
            float clamped = Math.clamp(syNeg, -1f, 1f);
            double yaw = -Math.asin(clamped);
            double pitch;
            double roll;
            if (Math.abs(clamped) > 0.9999f) {
                // Gimbal-lock fallback: pitch and roll merge; pin roll to 0 and put the
                // combined rotation on pitch via atan2 of the now-decoupled (3, 2) / (2, 2) cell.
                pitch = Math.atan2(-m.get(3, 2), m.get(2, 2));
                roll = 0f;
            } else {
                pitch = Math.atan2(m.get(2, 3), m.get(3, 3));
                roll = Math.atan2(m.get(1, 2), m.get(1, 1));
            }
            return new float[]{
                (float) Math.toDegrees(pitch),
                (float) Math.toDegrees(yaw),
                (float) Math.toDegrees(roll)
            };
        }

        /**
         * Returns whether {@code target} occurs after {@code source} in {@code instructions}.
         * The walker follows forward jumps to skip the not-taken branch of an if/else; backward
         * jumps (loop tails) would loop the linear walker forever, so this guard returns
         * {@code false} for them and the caller falls through linearly. {@code InsnList.indexOf}
         * caches indices after first call, so the lookup is amortised O(1) per method.
         */
        private static boolean isForwardJump(@NotNull InsnList instructions, @NotNull AbstractInsnNode source, @Nullable LabelNode target) {
            return target != null && instructions.indexOf(target) > instructions.indexOf(source);
        }

        /**
         * Pops an int from whichever stack the current parser config feeds branch evaluators.
         * When {@code paramFloatValues != null} (Java pipeline) ints flow through {@code numStack}
         * so the call-site-propagated literal can also feed {@code IADD/ISUB/...} arithmetic;
         * when {@code paramFloatValues == null} (legacy literal-stack walkers) the legacy
         * branchStack consumer is preserved. Returns {@code null} when neither stack has a
         * value, signalling the caller to fall through linearly.
         */
        private static @Nullable Integer popIntForBranch(@NotNull ParseState state) {
            if (state.paramFloatValues != null && !state.numStack.isEmpty())
                return state.numStack.popNumber().intValue();
            if (!state.branchStack.isEmpty())
                return state.branchStack.removeLast();
            return null;
        }

        /**
         * Returns a {@code paramIntValues}-compatible {@code int[]} of at least {@code slot+1}
         * entries, allocating a new array (and copying existing values) when {@code current}
         * is {@code null} or too small. Used by the for-loop unroller in
         * {@link #handleInstruction} to inject the iterator's per-iteration value into a slot
         * that might not have been pre-sized by the {@link Source}'s {@code paramIntValues}
         * (top-level Java entity sources don't set {@code paramIntValues} at all, so the slot
         * is unallocated until the first loop fires).
         */
        private static int @NotNull [] ensureIntSlotCapacity(int @Nullable [] current, int slot) {
            if (current != null && slot < current.length)
                return current;
            int currentLength = current == null ? 0 : current.length;
            int newLength = Math.max(slot + 1, Math.max(currentLength * 2, 16));
            int[] resized = new int[newLength];
            if (current != null)
                System.arraycopy(current, 0, resized, 0, currentLength);
            return resized;
        }

        /**
         * Folds a static-array index expression at an {@code IALOAD} / {@code FALOAD} site by
         * walking back over preceding real instructions to detect the canonical javac shapes:
         * <ul>
         *   <li>{@code GETSTATIC <[[I>; ILOAD slot; AALOAD; <int literal>; IALOAD} -
         *       silverfish / endermite's {@code BODY_SIZES[i][j]} / {@code BODY_TEXS[i][j]}</li>
         *   <li>{@code GETSTATIC <[I>; ILOAD slot; IALOAD} - 1D int array lookup</li>
         *   <li>{@code GETSTATIC <[F>; ILOAD slot; FALOAD} - guardian's {@code SPIKE_*[i]} +
         *       {@code SPIKE_*_ROT[i]}</li>
         * </ul>
         * Returns the resolved literal value when all three pieces match AND the row slot
         * resolves to a literal via {@link ParseState#numericLocals} or
         * {@link ParseState#paramIntValues}. Returns {@code null} otherwise (caller falls back
         * to the non-literal marker).
         *
         * <p>The {@code <clinit>} initializer is walked once per (owner, field) pair via the
         * {@link AsmKit#readStaticIntArray1D} / {@link AsmKit#readStaticIntArray2D} /
         * {@link AsmKit#readStaticFloatArray1D} helpers; caching is left to the JVM (each call
         * re-walks the ClassNode but the per-class ClassNode is itself cached at
         * {@link AsmKit#loadClass}-level by callers that need it - this fold's cost is one
         * small linear walk per array access, negligible against the surrounding parser cost).
         */
        private static @Nullable Number tryFoldStaticArrayRead(
            @NotNull AbstractInsnNode loadNode,
            @NotNull ParseState state,
            @NotNull ZipFile zip
        ) {
            int loadOp = loadNode.getOpcode();
            AbstractInsnNode prev1 = AsmKit.previousReal(loadNode);
            if (prev1 == null) return null;

            if (loadOp == Opcodes.IALOAD) {
                // 2D shape: <field>:[[I; <row expr>; AALOAD; <int lit col>; IALOAD
                Integer colIdx = AsmKit.readIntLiteral(prev1);
                if (colIdx != null) {
                    AbstractInsnNode aaload = AsmKit.previousReal(prev1);
                    if (aaload != null && aaload.getOpcode() == Opcodes.AALOAD) {
                        AbstractInsnNode beforeAaload = AsmKit.previousReal(aaload);
                        RowResolution rr = resolveRowExpression(beforeAaload, state);
                        if (rr != null) {
                            AbstractInsnNode getstaticNode = AsmKit.previousReal(rr.startNode());
                            if (getstaticNode instanceof FieldInsnNode field
                                && field.getOpcode() == Opcodes.GETSTATIC
                                && "[[I".equals(field.desc)) {
                                int[][] arr = AsmKit.readStaticIntArray2D(zip, field.owner, field.name);
                                if (arr != null && rr.value() >= 0 && rr.value() < arr.length
                                    && arr[rr.value()] != null && colIdx >= 0 && colIdx < arr[rr.value()].length) {
                                    return arr[rr.value()][colIdx];
                                }
                            }
                        }
                    }
                }
                // 1D shape: <field>:[I; <row expr>; IALOAD
                RowResolution rr1d = resolveRowExpression(prev1, state);
                if (rr1d != null) {
                    AbstractInsnNode getstaticNode = AsmKit.previousReal(rr1d.startNode());
                    if (getstaticNode instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && "[I".equals(field.desc)) {
                        int[] arr = AsmKit.readStaticIntArray1D(zip, field.owner, field.name);
                        if (arr != null && rr1d.value() >= 0 && rr1d.value() < arr.length) {
                            return arr[rr1d.value()];
                        }
                    }
                }
            }
            if (loadOp == Opcodes.FALOAD) {
                // 1D static shape: <field>:[F; <row expr>; FALOAD
                RowResolution rr = resolveRowExpression(prev1, state);
                if (rr != null) {
                    AbstractInsnNode getstaticNode = AsmKit.previousReal(rr.startNode());
                    if (getstaticNode instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && "[F".equals(field.desc)) {
                        float[] arr = AsmKit.readStaticFloatArray1D(zip, field.owner, field.name);
                        if (arr != null && rr.value() >= 0 && rr.value() < arr.length) {
                            return arr[rr.value()];
                        }
                    }
                }
                // 1D local shape: ALOAD <slot>; <row expr>; FALOAD - the slot must hold a
                // tracked float[] populated by an earlier NEWARRAY + ASTORE + (FASTORE)*
                // sequence. Silverfish's post-loop layer bones use
                // {@code aload_2; iconst_<idx>; faload} to read its cumulative-pivot cache.
                if (rr != null) {
                    AbstractInsnNode aloadNode = AsmKit.previousReal(rr.startNode());
                    if (aloadNode instanceof VarInsnNode aload && aload.getOpcode() == Opcodes.ALOAD) {
                        float[] arr = state.localFloatArrays.get(aload.var);
                        if (arr != null && rr.value() >= 0 && rr.value() < arr.length) {
                            return arr[rr.value()];
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Resolution result for a row-index expression preceding an {@code AALOAD} or
         * {@code [IF]ALOAD}: the literal index value and the first real instruction in the
         * expression. The caller scans backward from {@code startNode().getPrevious()} to find
         * the {@code GETSTATIC} of the array field.
         */
        private record RowResolution(int value, @NotNull AbstractInsnNode startNode) {}

        /**
         * Resolves a row-index expression at {@code endNode}, returning the literal value and
         * the expression's starting instruction. Supports:
         * <ul>
         *   <li>{@code ILOAD slot} - the simple case, when {@link #resolveSlotInt} can resolve
         *       the slot via {@link ParseState#numericLocals} or
         *       {@link ParseState#paramIntValues}.</li>
         *   <li>{@code ILOAD slot; <int lit>; IADD} / {@code ISUB} - silverfish / endermite
         *       update {@code f += sizes[i][2] + sizes[i+1][2]} which compiles to
         *       {@code iload <slot>; iconst_1; iadd; aaload}.</li>
         * </ul>
         * Returns {@code null} when the expression doesn't match a supported shape or any
         * piece is non-literal.
         */
        private static @Nullable RowResolution resolveRowExpression(
            @Nullable AbstractInsnNode endNode,
            @NotNull ParseState state
        ) {
            if (endNode == null) return null;
            // Literal int row: silverfish's post-loop layer bones use ICONST_2 / ICONST_4 /
            // ICONST_1 as direct row indices into BODY_SIZES.
            Integer literalRow = AsmKit.readIntLiteral(endNode);
            if (literalRow != null) {
                return new RowResolution(literalRow, endNode);
            }
            if (endNode instanceof VarInsnNode iload && iload.getOpcode() == Opcodes.ILOAD) {
                Integer v = resolveSlotInt(state, iload.var);
                if (v != null) return new RowResolution(v, iload);
            }
            if (endNode.getOpcode() == Opcodes.IADD || endNode.getOpcode() == Opcodes.ISUB) {
                AbstractInsnNode rhs = AsmKit.previousReal(endNode);
                AbstractInsnNode lhs = rhs == null ? null : AsmKit.previousReal(rhs);
                if (rhs != null && lhs instanceof VarInsnNode iload && iload.getOpcode() == Opcodes.ILOAD) {
                    Integer rhsLit = AsmKit.readIntLiteral(rhs);
                    Integer base = resolveSlotInt(state, iload.var);
                    if (rhsLit != null && base != null) {
                        int v = endNode.getOpcode() == Opcodes.IADD ? base + rhsLit : base - rhsLit;
                        return new RowResolution(v, iload);
                    }
                }
            }
            return null;
        }

        /**
         * Returns the resolved int for a JVM local slot - first checking
         * {@link ParseState#numericLocals} (where in-body {@code ISTORE}s are captured), then
         * {@link ParseState#paramIntValues} (where the for-loop unroller injects the iterator
         * value and {@code captureInlineParams} injects call-site literals). Returns
         * {@code null} when neither holds a value, signalling the static-array fold to fall
         * through.
         */
        private static @Nullable Integer resolveSlotInt(@NotNull ParseState state, int slot) {
            Number local = state.numericLocals.get(slot);
            if (local != null) return local.intValue();
            if (state.paramIntValues != null && slot >= 0 && slot < state.paramIntValues.length) {
                return state.paramIntValues[slot];
            }
            return null;
        }

        /**
         * Decodes an int, float, or double literal from the instruction, returning the boxed
         * numeric value or {@code null} when the node is not a compile-time numeric push. The
         * geometry walker tracks these on a single {@code Number}-typed stack so a downstream
         * {@code addBox(FFFFFF)} can pop floats from the same list that earlier collected ints
         * for an {@code addBox(name,FFFIIIII)} variant. Doubles ({@code DCONST_0/1},
         * {@code LDC2_W}) feed the {@code Mth.cos(D)F} / {@code Mth.sin(D)F} dispatch in
         * {@link #handleMethodInsn} so vanilla's inline trig in {@code createBodyLayer} (e.g.
         * {@code WitherBossModel}'s tail offset {@code -2 + cos(0.2042) * 10}) folds at parse time.
         */
        private static @Nullable Number readNumericLiteral(@NotNull AbstractInsnNode node) {
            Integer asInt = AsmKit.readIntLiteral(node);
            if (asInt != null) return asInt;
            Float asFloat = AsmKit.readFloatLiteral(node);
            if (asFloat != null) return asFloat;
            return AsmKit.readDoubleLiteral(node);
        }

        /**
         * Builder-dispatch int pop. Routes through {@link AsmKit.LiteralStack#popIntOrZero}
         * so the non-literal sentinel (pushed by {@link AsmKit.LiteralStack#pushNonLiteral})
         * fires the canonical "non-literal argument consumed" WARN tagged with the entity id.
         * Empty stack is silent zero - matches the upstream "accounting boundary" convention.
         */
        private static int popIntWithDiagnostics(@NotNull ParseState state, @NotNull String where) {
            if (state.diagnostics == null || state.currentSource == null) {
                // No diagnostic sink attached - fall back to the silent-coerce path so legacy
                // callers (single-source parsing without a Diagnostics) don't NPE.
                Number top = state.numStack.popNumber();
                return top == null ? 0 : top.intValue();
            }
            return state.numStack.popIntOrZero(state.diagnostics, state.currentSource.entityId(), where);
        }

        /**
         * Float-typed counterpart of {@link #popIntWithDiagnostics}.
         */
        private static float popFloatWithDiagnostics(@NotNull ParseState state, @NotNull String where) {
            if (state.diagnostics == null || state.currentSource == null) {
                Number top = state.numStack.popNumber();
                return top == null ? 0f : top.floatValue();
            }
            return state.numStack.popFloatOrZero(state.diagnostics, state.currentSource.entityId(), where);
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

        // Per-model inventory_transform tuples (A10) and tinted-id set (A9) now live in
        // {@link InventoryTransformDecomposer} (bytecode-driven) and {@link TintDiscovery}
        // respectively. {@link #convert} consumes both as parameters; nothing is hardcoded
        // here. The skull_dragon_head tz=1.25 special case is recovered by
        // {@link #recenterInventoryTransformsByBbox} as a post-pass over the decomposer's
        // output. See those classes for the per-model derivation provenance.

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
            Vector2f cubeUv = new Vector2f(cube.u, cube.v);
            Vector3f cubeSize = new Vector3f(cube.sw, cube.sh, cube.sd);
            for (EntityFace face : EntityFace.CACHED_VALUES) {
                Vector4f rect = face.defaultUv(cubeUv, cubeSize);
                // Expand the normalized pixel-space rect into TL/BL/BR/TR corners scaled into
                // the 0-16 block-UV space (block-model UV is independent of texture size; the
                // runtime multiplies u by texW/16 and v by texH/16 to recover pixel coords).
                Vector2f[] tlBlBrTr = {
                    new Vector2f(rect.x() * scaleU, rect.y() * scaleV),
                    new Vector2f(rect.x() * scaleU, rect.w() * scaleV),
                    new Vector2f(rect.z() * scaleU, rect.w() * scaleV),
                    new Vector2f(rect.z() * scaleU, rect.y() * scaleV)
                };
                Vector2f[] perVertexUvs = face.permuteToPolygonOrder(tlBlBrTr);
                emitBlockFace(face, perVertexUvs, blockCorners, box, faces, emitTintIndex);
            }

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

        /**
         * Axis-aligned rotation the renderer should apply to an element - one of x/y/z in degrees.
         */
        private record ElementRotationInfo(@NotNull String axis, float angle) {}

        /**
         * Determines which block face the four transformed vertices of an entity face land on,
         * matches them to that block face's TL/BL/BR/TR corners (per
         * {@link BlockFace}'s vertex-index conventions), and emits a
         * single block face entry whose UV rectangle and rotation tag reproduce the per-vertex UVs.
         */
        private static void emitBlockFace(
            @NotNull EntityFace face,
            @NotNull Vector2f @NotNull [] perVertexUvs,
            float @NotNull [] @NotNull [] blockCorners,
            @NotNull Box box,
            @NotNull JsonObject facesOut,
            boolean emitTintIndex
        ) {
            float[][] p = face.cornersOf(blockCorners);
            float[] p0 = p[0];
            float[] p1 = p[1];
            float[] p2 = p[2];
            float[] p3 = p[3];

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
            // them would let the axis-snapping default to DOWN (BlockFace.fromNormal's fall-through
            // for an all-zero normal picks the X axis, then EAST/WEST by sign; either lands on
            // some block-face slot and clobbers its real UV). Skip any face whose normal vanishes.
            float normalLenSq = normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2];
            if (normalLenSq < 1e-6f) return;
            BlockFace blockFace = BlockFace.fromNormal(new Vector3f(normal[0], normal[1], normal[2]));
            Vector3f[] blockFaceCorners = blockFace.corners(box);

            // For each transformed vertex of the entity face, read the UV it carries (already
            // in vanilla polygon-vertex order via EntityFace.permuteToPolygonOrder), then attach
            // that UV to whichever block-face corner the vertex landed at.
            float[][] blockCornerUv = new float[4][2];
            for (int i = 0; i < 4; i++) {
                int blockCorner = matchCorner(p[i], blockFaceCorners);
                blockCornerUv[blockCorner] = new float[]{ perVertexUvs[i].x(), perVertexUvs[i].y() };
            }

            UvRect uvRect = resolveUvRotation(blockCornerUv);

            // Zero-thickness cubes (decorated_pot's side panels) emit both entity NORTH and
            // SOUTH faces from the same quad - vanilla uses {@code EnumSet.of(Direction.NORTH)}
            // to render only one side but we don't model that filter. The "extra" face's UV
            // wraps past the texture edge (SOUTH formula gives u1 = u + 2w, which lands beyond
            // the 0..16 block-UV range for anything wider than half the texture). Skip those
            // out-of-bounds faces so we don't spray garbage texels onto the back of panels.
            Vector4f bounds = uvRect.bounds();
            if (bounds.x() < -0.01f || bounds.z() < -0.01f || bounds.x() > 16.01f || bounds.z() > 16.01f
                || bounds.y() < -0.01f || bounds.w() < -0.01f || bounds.y() > 16.01f || bounds.w() > 16.01f)
                return;

            JsonObject blockFaceJson = new JsonObject();
            blockFaceJson.addProperty("texture", "#entity");
            JsonArray uvArr = new JsonArray();
            uvArr.add(round2(bounds.x())); uvArr.add(round2(bounds.y())); uvArr.add(round2(bounds.z())); uvArr.add(round2(bounds.w()));
            blockFaceJson.add("uv", uvArr);
            if (uvRect.rotation() != 0) blockFaceJson.addProperty("rotation", uvRect.rotation());
            if (emitTintIndex) blockFaceJson.addProperty("tintindex", 0);
            facesOut.add(blockFace.direction(), blockFaceJson);
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
                    return new UvRect(new Vector4f(tlOld[0], tlOld[1], brOld[0], brOld[1]), r * 90);
            }
            return new UvRect(Vector4f.ZERO, 0);
        }

        /**
         * Returns the index (0=TL, 1=BL, 2=BR, 3=TR) of {@code blockFaceCorners} closest to {@code position}.
         */
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

        /**
         * {@code true} when two floats are within {@code 1e-4} of each other.
         */
        private static boolean approxEqual(float a, float b) {
            return Math.abs(a - b) < 1e-4f;
        }

        /**
         * Multiplies two 3x3 matrices, returning {@code a * b}.
         */
        private static double[][] matMul3(double[][] a, double[][] b) {
            double[][] r = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            return r;
        }

        /**
         * Rounds {@code v} to 2 decimal places for readable JSON output.
         */
        private static float round2(double v) {
            return (float) (Math.round(v * 100.0) / 100.0);
        }

        /**
         * A cube's origin, size, and UV offset as parsed from one entry of {@code bones[].cubes[]}.
         */
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

            /**
             * Applies scale + pivot + inventory transform (or Y-flip) + inventory yaw, skipping bone rotation.
             */
            float @NotNull [] applyNoBoneRot(float @NotNull [] corner) {
                return applyChain(corner, false);
            }

            /**
             * Applies scale, bone rotation, pivot, inventory transform (or Y-flip), then inventory yaw.
             */
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

        /**
         * The resolved UV rectangle plus a rotation tag from per-corner UV sampling.
         */
        private record UvRect(@NotNull Vector4f bounds, int rotation) {}

    }

}
