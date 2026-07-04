package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.request.DyeColor;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads block-entity model geometry from {@code /lib/minecraft/renderer/block_models.json},
 * the tooling-generated catalog keyed by block-entity-model id. Each entry carries the
 * ASM-extracted geometry, y_axis source convention, inventory transform, tinted flag,
 * optional sub-model parts, and the list of block variants + entity-texture paths that
 * render as this entity model. The pattern-derived per-block fields
 * ({@code iconRotation} on beds, {@code additive} on bells, {@code tint} on banners) are
 * emitted directly into the block entries by the tooling's id-pattern walker.
 *
 * <p>The output is a flat map of block id to {@link Block.Entity} carrying its geometry as a
 * parent-relative bone tree ({@link Block.Entity.BoneModel}, the same schema as
 * {@code entity_geometry.json}) plus the render presentation metadata and the entity texture
 * reference. These blocks render hierarchically through
 * {@link BlockGeometryKit#buildFromBones} with a presentation transform, rather than the
 * plain-block {@link BlockGeometryKit#buildFromElements} path.
 */
@UtilityClass
public class BlockModelLoader {

    private static final @NotNull String BLOCK_MODELS_PATH = "/lib/minecraft/renderer/block_models.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * The result of loading {@code block_models.json}: the per-block-id primary geometry
     * ({@link #models}) plus any state-conditional geometry ({@link #variants}) a block-entity
     * model registers under a blockstate variant key. The pipeline context merges {@link #variants}
     * into each block's {@link Block#getVariants()} so the standard variant path selects them - the
     * ceiling hanging sign's straight-chain mesh is bound to {@code attached=true} this way.
     *
     * @param models block id to its primary (default-state) block-entity model
     * @param variants block id to its {@code variantKey -> geometry-bearing variant} map
     */
    public record LoadResult(
        @NotNull ConcurrentMap<String, Block.Entity> models,
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variants
    ) {}

    /**
     * Loads block-entity geometry and wiring from {@code block_models.json}.
     * <p>
     * The catalog is keyed by block-entity-model id; entries live under a {@code "models"}
     * envelope object when present, otherwise the root object is the model map directly. Each model
     * entry supplies the shared {@code "model"} geometry, optional sub-model {@code "parts"}, and a
     * {@code "blocks"} array binding that geometry to concrete block ids with their entity textures.
     * The {@code "//"} comment key and non-object / model-less entries are skipped. Blocks flagged
     * with a {@code "variant"} route to {@link LoadResult#variants()} instead of the primary map.
     *
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if the resource is missing or cannot be parsed
     */
    public static @NotNull LoadResult load() {
        JsonObject root = readJson(BLOCK_MODELS_PATH);

        JsonObject models = root.has("models")
            ? root.getAsJsonObject("models")
            : root;

        HashMap<String, Block.Entity> result = new HashMap<>();
        HashMap<String, HashMap<String, Block.Variant>> variantModels = new HashMap<>();

        for (Map.Entry<String, JsonElement> modelEntry : models.entrySet()) {
            String modelId = modelEntry.getKey();
            if (modelId.equals("//") || !modelEntry.getValue().isJsonObject()) continue;
            JsonObject modelObj = modelEntry.getValue().getAsJsonObject();

            JsonObject modelJson = modelObj.has("model") ? modelObj.getAsJsonObject("model") : null;
            if (modelJson == null) {
                System.err.printf("  Warning: no model for entry '%s'%n", modelId);
                continue;
            }

            JsonArray modelParts = modelObj.has("parts") && modelObj.get("parts").isJsonArray()
                ? modelObj.getAsJsonArray("parts")
                : null;

            JsonArray blocks = modelObj.has("blocks") ? modelObj.getAsJsonArray("blocks") : null;
            if (blocks == null) continue;

            for (JsonElement blockEl : blocks) {
                JsonObject block = blockEl.getAsJsonObject();
                String blockId = block.get("blockId").getAsString();
                String textureId = block.get("textureId").getAsString();

                // Every block entity carries a relative bone/cube tree under model.bones, composed
                // hierarchically at render time via BlockGeometryKit#buildFromBones with a
                // presentation transform. A geometry-less entry would be a tooling error - skip it.
                if (!modelJson.has("bones")) {
                    System.err.printf("  Warning: model '%s' has no bones - skipping block '%s'%n", modelId, blockId);
                    continue;
                }
                Block.Entity.BoneModel boneModel = parseBoneModel(modelJson, modelObj);

                // A block listed under a blockstate "variant" contributes a state-conditional model,
                // not the block's primary geometry: register it as a bone-geometry Block.Variant for
                // the runtime variant path (rotation/uvlock unused here, so 0/0/false). The ceiling
                // hanging sign's straight-chain mesh is bound here under "attached=true".
                if (block.has("variant")) {
                    variantModels.computeIfAbsent(blockId, k -> new HashMap<>())
                        .put(block.get("variant").getAsString(), new Block.Variant(modelId, 0, 0, false, new Block.BoneGeometry(boneModel)));
                    continue;
                }

                ArrayList<Block.Entity.Part> parts = new ArrayList<>();
                if (modelParts != null) {
                    for (JsonElement partEl : modelParts) {
                        JsonObject partObj = partEl.getAsJsonObject();
                        String partModelId = partObj.get("model").getAsString();
                        // Entity-level parts[*].texture pins a constant (e.g. decorated_pot_sides
                        // always uses entity/decorated_pot/decorated_pot_side); when absent, the
                        // part's texture tracks its parent block's primary textureId (e.g.
                        // white_bed's bed_foot renders with entity/bed/white).
                        String partTexture = partObj.has("texture")
                            ? partObj.get("texture").getAsString()
                            : textureId;
                        float[] offset = new float[]{ 0, 0, 0 };
                        if (partObj.has("offset") && partObj.get("offset").isJsonArray()) {
                            JsonArray off = partObj.getAsJsonArray("offset");
                            offset = new float[]{ off.get(0).getAsFloat(), off.get(1).getAsFloat(), off.get(2).getAsFloat() };
                        }

                        JsonObject partModel = models.has(partModelId) ? models.getAsJsonObject(partModelId) : null;
                        if (partModel == null) continue;
                        JsonObject partModelJson = partModel.has("model") ? partModel.getAsJsonObject("model") : null;
                        if (partModelJson == null || !partModelJson.has("bones")) continue;
                        Block.Entity.BoneModel partBone = parseBoneModel(partModelJson, partModel);
                        parts.add(new Block.Entity.Part(partBone, partTexture, offset));
                    }
                }

                // Per-block fields read straight off the tooling-emitted block entry.
                // ToolingBlockModels pattern-matches iconRotation (beds), tint (banners), and
                // additive (bells) on block id and bakes the result into the JSON.
                int iconRotation = block.has("iconRotation") ? block.get("iconRotation").getAsInt() : 0;
                int tintArgb = block.has("tint") ? resolveTint(block.get("tint").getAsString()) : ColorMath.WHITE;
                boolean additive = block.has("additive") && block.get("additive").getAsBoolean();

                result.put(blockId, new Block.Entity(boneModel, textureId, tintArgb, iconRotation, Concurrent.adoptList(parts), additive));
            }
        }

        ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variants = Concurrent.newMap();
        for (Map.Entry<String, HashMap<String, Block.Variant>> e : variantModels.entrySet())
            variants.put(e.getKey(), Concurrent.adoptMap(e.getValue()).toUnmodifiable());

        return new LoadResult(
            Concurrent.adoptMap(result).toUnmodifiable(),
            variants.toUnmodifiable()
        );
    }

    /**
     * Reads a JSON resource off the classpath as a {@link JsonObject}.
     *
     * @param path the absolute classpath resource path
     * @return the parsed root object
     * @throws PipelineException if the resource is missing, empty, or fails to parse
     */
    private static @NotNull JsonObject readJson(@NotNull String path) {
        try (InputStream stream = BlockModelLoader.class.getResourceAsStream(path)) {
            if (stream == null)
                throw new PipelineException("Block model resource '%s' not found", path);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null)
                throw new PipelineException("Block model resource '%s' is empty", path);
            return root;
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load block model resource '%s'", path);
        }
    }

    /**
     * Parses a bone-format block-entity model (a {@code model.bones} relative bone/cube tree, same
     * schema as {@code entity_geometry.json}) into a {@link Block.Entity.BoneModel} carrying the
     * geometry plus the render-time presentation metadata read off the entry object
     * ({@code inventory_y_rotation}, {@code entity_flip}, {@code inventory_transform}, {@code tinted}).
     * The caller has already confirmed the {@code model} sub-object carries {@code bones}.
     *
     * @param modelJson the {@code model} sub-object carrying {@code bones} + texture dimensions
     * @param entry the block-entity catalog entry, carrying the presentation metadata alongside {@code model}
     * @return the parsed bone model + presentation
     */
    private static @NotNull Block.Entity.BoneModel parseBoneModel(@NotNull JsonObject modelJson, @NotNull JsonObject entry) {
        EntityModelData model = GSON.fromJson(modelJson, EntityModelData.class);

        boolean sourceYUp = entry.has("y_axis") && "UP".equals(entry.get("y_axis").getAsString());
        float inventoryYRotation = entry.has("inventory_y_rotation") ? entry.get("inventory_y_rotation").getAsFloat() : 0f;
        boolean entityFlip = entry.has("entity_flip") && entry.get("entity_flip").getAsBoolean();
        boolean tinted = entry.has("tinted") && entry.get("tinted").getAsBoolean();

        float[] inventoryTransform = null;
        if (entry.has("inventory_transform") && entry.get("inventory_transform").isJsonArray()) {
            JsonArray arr = entry.getAsJsonArray("inventory_transform");
            inventoryTransform = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++)
                inventoryTransform[i] = arr.get(i).getAsFloat();
        }

        return new Block.Entity.BoneModel(model, sourceYUp, inventoryYRotation, entityFlip, inventoryTransform, tinted);
    }

    /**
     * Resolves a {@code block_models.json} block entry's {@code "tint"} string to an ARGB int
     * (used for banner colours). Values are interpreted as:
     * <ul>
     * <li>{@link DyeColor.Vanilla} enum names (case-sensitive, e.g. {@code "RED"}, {@code "LIGHT_BLUE"})
     *     - preferred, carries the canonical {@code textureDiffuseColor} value from vanilla</li>
     * <li>Hex colour string ({@code #RRGGBB}, {@code #AARRGGBB}, or {@code 0x}-prefixed) for
     *     custom colours outside the sixteen vanilla dyes; a 6-digit value is forced fully opaque</li>
     * </ul>
     *
     * @param value the dye enum name or hex colour string
     * @return the resolved ARGB colour
     */
    private static int resolveTint(@NotNull String value) {
        @Nullable DyeColor dye = DyeColor.ofName(value);
        if (dye != null) return dye.argb();
        String trimmed = value.startsWith("#") ? value.substring(1)
            : value.startsWith("0x") || value.startsWith("0X") ? value.substring(2)
            : value;
        long packed = Long.parseLong(trimmed, 16);
        if (trimmed.length() <= 6) packed |= 0xFF000000L;
        return (int) packed;
    }

}
