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
import lib.minecraft.renderer.asset.binding.DyeColor;
import lib.minecraft.renderer.asset.model.BlockModelData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.kit.BlockGeometryKit;
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
 * Loads block entity geometry from {@code /lib/minecraft/renderer/block_entities.json},
 * the tooling-generated catalog keyed by entity-model id. Each entry carries the
 * ASM-extracted geometry, y_axis source convention, inventory transform, tinted flag,
 * optional sub-model parts, and the list of block variants + entity-texture paths that
 * render as this entity model. The pattern-derived per-block fields
 * ({@code iconRotation} on beds, {@code additive} on bells, {@code tint} on banners) are
 * emitted directly into the block entries by the tooling's id-pattern walker.
 *
 * <p>The output is a flat map of block id to {@link Block.Entity} carrying a populated
 * {@link BlockModelData} (with real {@link ModelElement elements}) and the entity texture
 * reference. These blocks render through the standard block model path
 * ({@link BlockGeometryKit#buildFromElements}) with no entity model pipeline.
 */
@UtilityClass
public class BlockEntityLoader {

    private static final @NotNull String BLOCK_ENTITIES_PATH = "/lib/minecraft/renderer/block_entities.json";
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Loads block entity geometry and wiring, producing a map of block id to model + texture.
     *
     * @return the loaded block entity entries keyed by block id
     * @throws PipelineException if either resource is missing or cannot be parsed
     */
    public static @NotNull ConcurrentMap<String, Block.Entity> load() {
        JsonObject entitiesRoot = readJson(BLOCK_ENTITIES_PATH);

        JsonObject entities = entitiesRoot.has("entities")
            ? entitiesRoot.getAsJsonObject("entities")
            : entitiesRoot;

        HashMap<String, Block.Entity> result = new HashMap<>();

        for (Map.Entry<String, JsonElement> entityEntry : entities.entrySet()) {
            String modelId = entityEntry.getKey();
            if (modelId.equals("//") || !entityEntry.getValue().isJsonObject()) continue;
            JsonObject entity = entityEntry.getValue().getAsJsonObject();

            JsonObject modelJson = entity.has("model") ? entity.getAsJsonObject("model") : null;
            if (modelJson == null) {
                System.err.printf("  Warning: no model for entity '%s'%n", modelId);
                continue;
            }

            JsonArray entityParts = entity.has("parts") && entity.get("parts").isJsonArray()
                ? entity.getAsJsonArray("parts")
                : null;

            JsonArray blocks = entity.has("blocks") ? entity.getAsJsonArray("blocks") : null;
            if (blocks == null) continue;

            for (JsonElement blockEl : blocks) {
                JsonObject block = blockEl.getAsJsonObject();
                String blockId = block.get("blockId").getAsString();
                String textureId = block.get("textureId").getAsString();

                BlockModelData modelData = parseBlockModelData(modelJson, textureId);

                ArrayList<Block.Entity.Part> parts = new ArrayList<>();
                if (entityParts != null) {
                    for (JsonElement partEl : entityParts) {
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

                        JsonObject partEntity = entities.has(partModelId) ? entities.getAsJsonObject(partModelId) : null;
                        if (partEntity == null) continue;
                        JsonObject partModelJson = partEntity.has("model") ? partEntity.getAsJsonObject("model") : null;
                        if (partModelJson == null) continue;
                        BlockModelData partData = parseBlockModelData(partModelJson, partTexture);
                        parts.add(new Block.Entity.Part(partModelId, partData, partTexture, offset));
                    }
                }

                boolean multiBlock = extentsExceedBlock(modelData);
                if (!multiBlock) {
                    for (Block.Entity.Part part : parts) {
                        if (partExceedsBlock(part)) { multiBlock = true; break; }
                    }
                }

                // Per-block fields read straight off the tooling-emitted block entry.
                // ToolingBlockEntities pattern-matches iconRotation (beds), tint (banners), and
                // additive (bells) on block id and bakes the result into the JSON.
                int iconRotation = block.has("iconRotation") ? block.get("iconRotation").getAsInt() : 0;
                int tintArgb = block.has("tint") ? resolveTint(block.get("tint").getAsString()) : ColorMath.WHITE;
                boolean additive = block.has("additive") && block.get("additive").getAsBoolean();

                result.put(blockId, new Block.Entity(modelId, modelData, textureId, tintArgb, iconRotation, multiBlock, Concurrent.adoptList(parts), additive));
            }
        }

        return Concurrent.adoptMap(result).toUnmodifiable();
    }

    /**
     * Reads a JSON resource off the classpath, throwing {@link PipelineException} on
     * missing resource, empty payload, or parse failure.
     */
    private static @NotNull JsonObject readJson(@NotNull String path) {
        try (InputStream stream = BlockEntityLoader.class.getResourceAsStream(path)) {
            if (stream == null)
                throw new PipelineException("Block entity resource '%s' not found", path);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null)
                throw new PipelineException("Block entity resource '%s' is empty", path);
            return root;
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load block entity resource '%s'", path);
        }
    }

    /**
     * Returns {@code true} when any element of {@code model} escapes the {@code 0..16} block bbox.
     */
    private static boolean extentsExceedBlock(@NotNull BlockModelData model) {
        for (ModelElement me : model.getElements()) {
            if (me.getFrom()[0] < -0.1f || me.getFrom()[1] < -0.1f || me.getFrom()[2] < -0.1f ||
                me.getTo()[0] > 16.1f || me.getTo()[1] > 16.1f || me.getTo()[2] > 16.1f) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when a part, after its offset is applied, escapes the {@code 0..16}
     * block bbox. Checked separately from the primary model so multi-block detection stays
     * accurate after the loader stopped eagerly merging parts.
     */
    private static boolean partExceedsBlock(@NotNull Block.Entity.Part part) {
        float[] offset = part.offset();
        for (ModelElement me : part.model().getElements()) {
            if (me.getFrom()[0] + offset[0] < -0.1f || me.getFrom()[1] + offset[1] < -0.1f || me.getFrom()[2] + offset[2] < -0.1f ||
                me.getTo()[0] + offset[0] > 16.1f || me.getTo()[1] + offset[1] > 16.1f || me.getTo()[2] + offset[2] > 16.1f) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a block model JSON object (with elements array) into a {@link BlockModelData},
     * including the texture variable binding so {@code #entity} resolves at render time.
     */
    private static @NotNull BlockModelData parseBlockModelData(@NotNull JsonObject json, @NotNull String textureId) {
        JsonObject modelJson = new JsonObject();

        if (json.has("elements"))
            modelJson.add("elements", json.getAsJsonArray("elements"));

        JsonObject textures = new JsonObject();
        textures.addProperty("entity", textureId);
        modelJson.add("textures", textures);

        return GSON.fromJson(modelJson, BlockModelData.class);
    }

    /**
     * Resolves an overrides JSON {@code tint} string to an ARGB int. Values are interpreted as:
     * <ul>
     * <li>{@link DyeColor.Vanilla} enum names (case-sensitive, e.g. {@code "RED"}, {@code "LIGHT_BLUE"})
     *     - preferred, carries the canonical {@code textureDiffuseColor} value from vanilla</li>
     * <li>Hex colour string ({@code #RRGGBB}, {@code #AARRGGBB}, or {@code 0x}-prefixed) for
     *     custom colours outside the sixteen vanilla dyes</li>
     * </ul>
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
