package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.EntityModelData.Bone;
import lib.minecraft.renderer.asset.model.EntityModelData.Cube;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.ToolingException;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.face.EntityFace;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.tensor.Box;
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
import lib.minecraft.renderer.tooling.parser.GeometryParser;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.FastTrig;
import lib.minecraft.renderer.tooling.util.JsonOptional;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
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
 * Entry point invoked by the {@code blockModels} Gradle task.
 *
 * <p>Downloads the deobfuscated Minecraft client jar, parses every block-entity model class
 * via {@link GeometryParser}, and writes the result to
 * {@code src/main/resources/lib/minecraft/renderer/block_models.json}.
 *
 * <p>Output composition:
 * <ul>
 *   <li><b>Geometry</b> - decomposed from each block-entity model class's layer factory
 *       bytecode ({@code createBodyLayer} / {@code createSingleBodyLayer} /
 *       {@code createHeadLayer} / {@code createFootLayer} / {@code createFlagLayer}). Y-axis
 *       normalised to the canonical Y-down convention via {@link YAxis YAxis}.</li>
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
            // Absolute-flattened emission: BlockModelConverter walks bones without composing the
            // parent chain, so it requires each bone's pivot to already be world-absolute.
            ConcurrentMap<String, JsonObject> models = GeometryParser.parse(jarPath, sources, diagnostics, false);
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

            JsonObject blockModels = BlockModelConverter.convert(models, inventoryTransforms, entityRenderFlips, tinted);
            Map<String, String> bannerTintByBlockId = BlockListDiscovery.bannerTintByBlockId(zip, diagnostics);
            merged = buildMergedOutput(blockModels, models, blockList, inventoryTransforms, tinted, bannerTintByBlockId);
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
     *
     * @param parsedModels the parsed entity models keyed by id (source of the cube tree)
     * @param inventoryTransforms the decomposer's tuples, mutated in place when a shift applies
     * @param diag the diagnostics sink for recenter-fired info entries
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
     *
     * @param model the parsed entity model (walked for its bone/cube tree)
     * @param invTransform the {@code [tx, ty, tz, pitch, yaw, roll, scale?]} inventory transform tuple
     * @return the {@code [xMin, yMin, zMin, xMax, yMax, zMax]} bbox, or {@code null} when the model has no cubes
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
            JsonArray cubes = JsonOptional.optArray(bone, "cubes");
            if (cubes == null || cubes.isEmpty()) continue;

            float boneScale = JsonOptional.optFloat(bone, "scale", 1f);
            JsonArray pivotArr = JsonOptional.optArray(bone, "pivot");
            float bpx = pivotArr != null ? pivotArr.get(0).getAsFloat() : 0f;
            float bpy = pivotArr != null ? pivotArr.get(1).getAsFloat() : 0f;
            float bpz = pivotArr != null ? pivotArr.get(2).getAsFloat() : 0f;

            JsonArray rotArr = JsonOptional.optArray(bone, "rotation");
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
                JsonArray oArr = JsonOptional.optArray(cube, "origin");
                JsonArray sArr = JsonOptional.optArray(cube, "size");
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
     *
     * @param rxR X-rotation in radians (applied first)
     * @param ryR Y-rotation in radians (applied second)
     * @param rzR Z-rotation in radians (applied last)
     * @return the composed 3x3 {@code Rz · Ry · Rx} matrix
     */
    private static double @NotNull [] @NotNull [] rotationZYX(double rxR, double ryR, double rzR) {
        double[][] mRx = {{ 1, 0, 0 }, { 0, Math.cos(rxR), -Math.sin(rxR) }, { 0, Math.sin(rxR), Math.cos(rxR) }};
        double[][] mRy = {{ Math.cos(ryR), 0, Math.sin(ryR) }, { 0, 1, 0 }, { -Math.sin(ryR), 0, Math.cos(ryR) }};
        double[][] mRz = {{ Math.cos(rzR), -Math.sin(rzR), 0 }, { Math.sin(rzR), Math.cos(rzR), 0 }, { 0, 0, 1 }};
        return mat3Mul(mat3Mul(mRz, mRy), mRx);
    }

    /**
     * 3x3 matrix multiply returning {@code a · b}.
     *
     * @param a left 3x3 operand
     * @param b right 3x3 operand
     * @return the product {@code a · b}
     */
    private static double @NotNull [] @NotNull [] mat3Mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i][j] = a[i][0]*b[0][j] + a[i][1]*b[1][j] + a[i][2]*b[2][j];
        return r;
    }

    /**
     * Composes the unified {@code block_models.json} output. Parses the existing file (if
     * present) to preserve hand-curated fields ({@code blocks} variants, {@code parts}
     * shape) that are not yet auto-discovered, then overwrites the auto-derivable fields
     * ({@code model} geometry from the ASM parse, {@code y_axis} + {@code inventory_transform}
     * + {@code tinted} from the current Java literals) so re-running the task is idempotent.
     *
     * @param blockModels the converted block-model elements keyed by entity-model id
     * @param parsedEntityModels the raw parsed entity models (carry {@code y_axis})
     * @param blockList the per-entity block/parts catalog from {@link BlockListDiscovery}
     * @param inventoryTransforms the decomposed inventory transform tuples by id
     * @param tintedModelIds the ids flagged as tinted
     * @param bannerTintByBlockId the per-block-id dye map for banner {@code tint} fields
     * @return the merged root JSON object
     * @throws IOException if the existing output file cannot be read
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

            JsonObject converted = blockModels.has(modelId) && blockModels.get(modelId).isJsonObject()
                ? blockModels.getAsJsonObject(modelId)
                : null;
            JsonObject parsedEntity = parsedEntityModels.get(modelId);
            if (converted == null && parsedEntity == null) continue;

            JsonObject modelOut = new JsonObject();
            if (converted != null)
                modelOut.add("model", buildModelSubobject(converted));

            String yAxis = parsedEntity != null && parsedEntity.has("y_axis")
                ? parsedEntity.get("y_axis").getAsString()
                : "DOWN";
            modelOut.addProperty("y_axis", yAxis);
            modelOut.addProperty("inventory_y_rotation", 0);

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
     * Extracts the model-body subobject ({@code textureWidth}, {@code textureHeight},
     * {@code elements}) from a {@link BlockModelConverter#convert converted} entry.
     *
     * @param converted one converted block-model entry
     * @return the model-body subobject carrying only the texture dimensions and elements
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
     *
     * @return the {@code "//"} header string describing the generator, layout, and golden-test guard
     */
    private static @NotNull String mergedHeader() {
        return "Generated by ToolingBlockModels (tooling/blockModels Gradle task). Unified "
            + "block-entity catalog keyed by entity-model id: each entry carries the ASM-extracted "
            + "geometry (elements from LayerDefinition bytecode), metadata (y_axis source "
            + "convention, inventory_y_rotation GUI-facing fix, inventory_transform decomposed "
            + "from the Renderer's PoseStack, tinted flag), optional sub-model parts with their "
            + "render offsets, and the list of block variants that render as this entity model "
            + "along with their entity-texture paths. Supersedes the former split between "
            + "tile_entity_models.json (generated geometry) and tile_entity_mappings.json "
            + "(hand-edited block bindings); both source files are now derived in one pass from "
            + "the 26.1 client jar. Per-block atlas/GUI fields (iconRotation on beds, additive on "
            + "bells, per-block tint on banners) are pattern-matched onto block entries by "
            + "applyPerBlockFamilyFields at tooling time. "
            + "Run the tooling/blockModels Gradle task to refresh; BlockModelsGoldenTest "
            + "guards against silent drift via a SHA-256 over the canonical JSON.";
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
         * those classes for provenance. The {@code entityRenderFlips} map (also from
         * {@link InventoryTransformDecomposer#resolveEntityRenderFlips}) gates the entity-render
         * {@code scale(-1, -1, 1)} flip on each inventory-transform-less model's item
         * {@code display.gui} roll; ids absent from the map default to the flip.
         *
         * @param entityModels the parsed entity models keyed by entity-model id
         * @param inventoryTransforms the decomposed {@code [tx, ty, tz, pitch, yaw, roll, scale?]} tuples by id
         * @param entityRenderFlips the per-id entity-flip gate for inventory-transform-less models
         * @param tintedIds the ids whose faces carry {@code tintindex: 0}
         * @return a JSON object of block-model elements keyed by entity-model id
         */
        static @NotNull JsonObject convert(
            @NotNull ConcurrentMap<String, JsonObject> entityModels,
            @NotNull Map<String, float[]> inventoryTransforms,
            @NotNull Map<String, Boolean> entityRenderFlips,
            @NotNull Set<String> tintedIds
        ) {
            JsonObject result = new JsonObject();
            result.addProperty("//", "Generated block model elements from entity model geometry. Run tooling/blockModels to refresh.");

            for (Map.Entry<String, JsonObject> entry : entityModels.entrySet()) {
                String modelId = entry.getKey();
                JsonObject entityModel = entry.getValue();

                int texW = JsonOptional.optInt(entityModel, "textureWidth", 64);
                int texH = JsonOptional.optInt(entityModel, "textureHeight", 64);

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
                float invYRot = JsonOptional.optFloat(entityModel, "inventory_y_rotation", 0f);

                // Whether this model's geometry takes vanilla's entity-render scale(-1, -1, 1)
                // flip when baked into an inventory icon. Read from the item display.gui roll
                // (180 -> flip), defaulting to the flip for flat-sprite icons absent from the map.
                boolean entityRenderFlip = entityRenderFlips.getOrDefault(modelId, Boolean.TRUE);

                // For Y-UP source models (chest), the parser pre-flipped to Y-DOWN so the rest of
                // the pipeline sees a uniform convention - but that flip swaps which corner of the
                // cube vanilla calls "v19" (its yMin in source coords, which is the cube's
                // render-bottom corner and carries a specific UV via the SOUTH/NORTH/etc polygon
                // assignments). Our entityCorners array indexes vertices by the post-flip Y values,
                // so for Y-UP source we swap yLo <-> yHi to recover vanilla's labels.
                boolean isYUpSource = "UP".equals(JsonOptional.optString(entityModel, "y_axis", "DOWN"));
                boolean emitTintIndex = tintedIds.contains(modelId);

                JsonArray elements = new JsonArray();
                for (Map.Entry<String, JsonElement> boneEntry : bones.entrySet()) {
                    JsonObject bone = boneEntry.getValue().getAsJsonObject();
                    CubeTransform transform = CubeTransform.of(bone, invTransform, invYRot, entityRenderFlip);

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
         *
         * @param cube the parsed cube geometry
         * @param transform the bone + inventory transform chain for this cube's bone
         * @param isYUpSource whether the source model was Y-up (drives the corner yLo/yHi swap)
         * @param texW entity texture width in pixels
         * @param texH entity texture height in pixels
         * @param emitTintIndex whether to emit {@code tintindex: 0} on each face
         * @return the block-model element JSON ({@code from}/{@code to}/optional {@code rotation}/{@code faces})
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
            JsonArray from = new JsonArray(); from.add(roundCoord(box.minX())); from.add(roundCoord(box.minY())); from.add(roundCoord(box.minZ()));
            JsonArray to = new JsonArray(); to.add(roundCoord(box.maxX())); to.add(roundCoord(box.maxY())); to.add(roundCoord(box.maxZ()));
            element.add("from", from);
            element.add("to", to);
            if (blockRot != null) {
                // Rotation origin is the bone pivot translated through the non-rotation chain,
                // matching how vanilla's ModelPart renders: translate(pivot) * R_bone * ...cube
                // - the pivot lands at the same point whether or not we apply the bone rotation.
                float[] origin = transform.applyNoBoneRot(new float[]{ 0f, 0f, 0f });
                JsonObject rotObj = new JsonObject();
                JsonArray originArr = new JsonArray();
                originArr.add(roundCoord(origin[0])); originArr.add(roundCoord(origin[1])); originArr.add(roundCoord(origin[2]));
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
         *
         * @param axis the block axis the rotation is about, one of {@code "x"}/{@code "y"}/{@code "z"}
         * @param angle the signed rotation angle in degrees
         */
        private record ElementRotationInfo(@NotNull String axis, float angle) {}

        /**
         * Determines which block face the four transformed vertices of an entity face land on,
         * matches them to that block face's TL/BL/BR/TR corners (per
         * {@link BlockFace}'s vertex-index conventions), and emits a
         * single block face entry whose UV rectangle and rotation tag reproduce the per-vertex UVs.
         *
         * @param face the entity face being projected
         * @param perVertexUvs the four per-vertex UVs in vanilla polygon-vertex order
         * @param blockCorners the eight transformed cube corners in block space
         * @param box the axis-aligned block bbox of {@code blockCorners}
         * @param facesOut the faces JSON object mutated in place with the emitted face
         * @param emitTintIndex whether to emit {@code tintindex: 0} on the face
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
            uvArr.add(roundCoord(bounds.x())); uvArr.add(roundCoord(bounds.y())); uvArr.add(roundCoord(bounds.z())); uvArr.add(roundCoord(bounds.w()));
            blockFaceJson.add("uv", uvArr);
            if (uvRect.rotation() != 0) blockFaceJson.addProperty("rotation", uvRect.rotation());
            if (emitTintIndex) blockFaceJson.addProperty("tintindex", 0);
            facesOut.add(blockFace.direction(), blockFaceJson);
        }

        /**
         * Resolves the four per-corner UVs at TL/BL/BR/TR (one of D4's eight orientations of a
         * UV rectangle) into a ({@code u0, v0, u1, v1}) rectangle plus a 0/90/180/270 rotation
         * tag. Implicit u/v flips are expressed by allowing {@code u0 > u1} or {@code v0 > v1}.
         *
         * @param blockCornerUv the four per-corner UVs indexed by block-face corner (0=TL, 1=BL, 2=BR, 3=TR)
         * @return the resolved rectangle plus rotation tag, or a zero rect + {@code 0} when no orientation matches
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
         *
         * @param position the transformed vertex to match
         * @param blockFaceCorners the block face's four corners in TL/BL/BR/TR order
         * @return the index of the nearest corner
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
         *
         * @param a first value
         * @param b second value
         * @return whether {@code a} and {@code b} are within {@code 1e-4}
         */
        private static boolean approxEqual(float a, float b) {
            return Math.abs(a - b) < 1e-4f;
        }

        /**
         * Multiplies two 3x3 matrices, returning {@code a · b}.
         *
         * @param a left 3x3 operand
         * @param b right 3x3 operand
         * @return the product {@code a · b}
         */
        private static double[][] matMul3(double[][] a, double[][] b) {
            double[][] r = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            return r;
        }

        /**
         * Rounds {@code v} to 5 decimal places for readable JSON output. 5 places preserve
         * vanilla's {@code CubeDeformation} inflate values (e.g. {@code 0.015}) and bone-composed
         * fractional coordinates to sub-pixel accuracy.
         *
         * @param v the coordinate to round
         * @return {@code v} rounded to 5 decimal places
         */
        private static float roundCoord(double v) {
            return (float) (Math.round(v * 100000.0) / 100000.0);
        }

        /**
         * A cube's origin, size, UV offset, and inflate as parsed from one entry of
         * {@code bones[].cubes[]}.
         *
         * @param inflate vanilla {@code CubeDeformation} grow, in entity-pixel units; the cube's
         *  geometry expands outward by this on every axis ({@code origin - inflate} to
         *  {@code origin + size + inflate}) while the UV rectangle stays keyed to the unscaled
         *  {@code size}. copper_golem_statue uses {@code +0.015} (head) / {@code -0.015}
         *  (antennae); all other block-entity cubes are {@code 0}.
         */
        private record CubeDef(float ox, float oy, float oz, float sw, float sh, float sd, int u, int v, float inflate) {

            /**
             * Parses one {@code bones[].cubes[]} entry into a {@code CubeDef}. The {@code inflate}
             * key is optional (defaulting to {@code 0}); {@code origin}/{@code size}/{@code uv} are
             * required by the parser contract.
             *
             * @param cube one cube JSON object from the parsed entity model
             * @return the parsed cube geometry
             */
            static @NotNull CubeDef of(@NotNull JsonObject cube) {
                JsonArray originArr = cube.getAsJsonArray("origin");
                JsonArray sizeArr = cube.getAsJsonArray("size");
                JsonArray uvArr = cube.getAsJsonArray("uv");
                return new CubeDef(
                    originArr.get(0).getAsFloat(), originArr.get(1).getAsFloat(), originArr.get(2).getAsFloat(),
                    sizeArr.get(0).getAsFloat(), sizeArr.get(1).getAsFloat(), sizeArr.get(2).getAsFloat(),
                    uvArr.get(0).getAsInt(), uvArr.get(1).getAsInt(),
                    JsonOptional.optFloat(cube, "inflate", 0f)
                );
            }

            /**
             * Returns the eight entity-space corners of this cube in vanilla {@code v19..v26}
             * bit-pattern order (xMax?, yMax?, zMax? selecting one of 8 corners), grown outward
             * by {@link #inflate} on every axis to match vanilla's {@code CubeDeformation}.
             * <p>
             * Vanilla labels {@code v19..v22} as the "yMin" vertices and {@code v23..v26} as the
             * "yMax" vertices BY THE SOURCE Y CONVENTION. For Y-DOWN source the post-flip Y
             * coords match (yMin in source == yMin in our cube origin), but for Y-UP source the
             * parser pre-flipped Y and the meaning of yMin/yMax is inverted - swap yLo/yHi to
             * recover vanilla's labels.
             *
             * @param yUpSource whether the source model was Y-up (parser pre-flipped it), requiring the yLo/yHi swap
             * @return the eight corners as {@code [x, y, z]} triples in {@code v19..v26} order
             */
            float @NotNull [] @NotNull [] entityCorners(boolean yUpSource) {
                float xLo = ox - inflate,      xHi = ox + sw + inflate;
                float zLo = oz - inflate,      zHi = oz + sd + inflate;
                float yLoRaw = oy - inflate,   yHiRaw = oy + sh + inflate;
                float yLo = yUpSource ? yHiRaw : yLoRaw;
                float yHi = yUpSource ? yLoRaw : yHiRaw;
                return new float[][]{
                    { xLo, yLo, zLo },
                    { xHi, yLo, zLo },
                    { xHi, yHi, zLo },
                    { xLo, yHi, zLo },
                    { xLo, yLo, zHi },
                    { xHi, yLo, zHi },
                    { xHi, yHi, zHi },
                    { xLo, yHi, zHi }
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
            float invYRot,
            boolean entityFlip
        ) {

            /**
             * Builds the transform for one bone: reads its {@code pivot}, {@code rotation}, and
             * {@code scale}, precomputes the {@code Rz · Ry · Rx} bone-rotation matrix (or leaves
             * it {@code null} when all three Euler angles are zero), and carries the model-level
             * inventory transform, inventory yaw, and entity-flip gate through unchanged.
             *
             * @param bone one {@code bones[]} entry from the parsed entity model
             * @param invTransform the model's decomposed {@code [tx, ty, tz, pitch, yaw, roll, scale?]}
             *  inventory transform, or {@code null} when the model has none (takes the entity-flip path)
             * @param invYRot inventory yaw applied around block centre (baked +180 for the chest)
             * @param entityFlip whether the entity-render {@code scale(-1, -1, 1)} X negation applies
             *  on the no-inventory-transform path
             * @return the assembled bone transform
             */
            static @NotNull CubeTransform of(@NotNull JsonObject bone, float @Nullable [] invTransform, float invYRot, boolean entityFlip) {
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

                float scale = JsonOptional.optFloat(bone, "scale", 1f);

                // Bone rotation matrix: Rz * Ry * Rx (matches vanilla's Quaternionf.rotationZYX,
                // which applies X first, then Y, then Z).
                double rxR = Math.toRadians(brx), ryR = Math.toRadians(bry), rzR = Math.toRadians(brz);
                double[][] mRx = {{ 1, 0, 0 }, { 0, Math.cos(rxR), -Math.sin(rxR) }, { 0, Math.sin(rxR), Math.cos(rxR) }};
                double[][] mRy = {{ Math.cos(ryR), 0, Math.sin(ryR) }, { 0, 1, 0 }, { -Math.sin(ryR), 0, Math.cos(ryR) }};
                double[][] mRz = {{ Math.cos(rzR), -Math.sin(rzR), 0 }, { Math.sin(rzR), Math.cos(rzR), 0 }, { 0, 0, 1 }};
                double[][] boneRot = hasBoneRot ? matMul3(matMul3(mRz, mRy), mRx) : null;

                return new CubeTransform(boneRot, scale, px, py, pz, invTransform, invYRot, entityFlip);
            }

            /**
             * Applies scale + pivot + inventory transform (or Y-flip) + inventory yaw, skipping bone rotation.
             *
             * @param corner the entity-space corner {@code [x, y, z]}
             * @return the transformed block-space corner
             */
            float @NotNull [] applyNoBoneRot(float @NotNull [] corner) {
                return applyChain(corner, false);
            }

            /**
             * Applies scale, bone rotation, pivot, inventory transform (or Y-flip), then inventory yaw.
             *
             * @param corner the entity-space corner {@code [x, y, z]}
             * @return the transformed block-space corner
             */
            float @NotNull [] apply(float @NotNull [] corner) {
                return applyChain(corner, true);
            }

            /**
             * Runs one entity-space corner through the full chain: uniform scale, optional bone
             * rotation, pivot offset, then either the inventory transform (uniform inv-scale &rarr;
             * {@code Rx(pitch)} &rarr; translate) or - when no inventory transform is present -
             * vanilla's entity-render {@code scale(-1, -1, 1)} flip, and finally the inventory yaw
             * about block centre {@code (8, 8, 8)}.
             *
             * @param corner the entity-space corner {@code [x, y, z]}
             * @param withBoneRot whether to apply the bone rotation matrix (skipped for the runtime-rotation path)
             * @return the transformed block-space corner
             */
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
                    // No inventory transform: apply vanilla's entity-render flip scale(-1,-1,1)
                    // (a 180-degree roll about Z), NOT a bare Y-flip. The X negation is the
                    // missing half: cy=-cy alone is a reflection (det=-1) that X-mirrors the model.
                    // Symmetric block-entity skins (skull, sign) hid it; the copper-golem statue's
                    // asymmetric face/body exposed the inside-out, eyes-on-the-wrong-side render.
                    //
                    // The cx half is gated on {@code entityFlip}, read from the item icon's
                    // display.gui roll (InventoryTransformDecomposer#resolveEntityRenderFlips):
                    // copper_golem_statue's roll of 180 IS this scale(-1,-1,1), so it flips; chest's
                    // roll of 0 means its X is instead handled by the baked invYRot=180 yaw, so it
                    // must NOT take cx=-cx (that flips about 0 and double-flips it out of the block).
                    if (entityFlip) cx = -cx;
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
             *
             * @return the axis-aligned block rotation, or {@code null} when there is no bone
             *  rotation, no inventory transform, or the conjugated axis is diagonal / a reflection
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
         *
         * @param bounds the {@code (u0, v0, u1, v1)} rectangle, with {@code u0 > u1} or
         *  {@code v0 > v1} expressing an implicit axis flip
         * @param rotation the block-face UV rotation tag, one of {@code 0}/{@code 90}/{@code 180}/{@code 270} degrees
         */
        private record UvRect(@NotNull Vector4f bounds, int rotation) {}

    }

}
