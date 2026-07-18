package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.option.spec.DyeColor;
import lib.minecraft.renderer.pipeline.load.BundledResource;
import lib.minecraft.renderer.pipeline.load.ResourceDocument;
import lib.minecraft.renderer.pipeline.loader.BlockEntityShadowDiagnostics;
import lib.minecraft.renderer.pipeline.load.BlockRendererOverrides;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads block-entity model geometry from {@code /lib/minecraft/renderer/block_models.json}, the
 * tooling-generated catalog keyed by block-entity-model id, joined against
 * {@code block_geometry.json} (the bone trees). Each entry carries the ASM-extracted geometry, y_axis
 * source convention, inventory transform, tinted flag, optional sub-model parts, and the list of block
 * variants + entity-texture paths that render as this entity model. The pattern-derived per-block
 * fields ({@code iconRotation} on beds, {@code additive} on bells, {@code tint} on banners) are
 * emitted directly into the block entries by the tooling's id-pattern walker.
 *
 * <p>The output is a flat map of block id to {@link Block.Entity} carrying its geometry as a
 * parent-relative bone tree ({@link Block.Entity.BoneModel}, the same schema as
 * {@code entity_geometry.json}) plus the render presentation metadata and the entity texture
 * reference. These blocks render hierarchically through {@link BlockGeometryKit#buildFromBones} with
 * a presentation transform, rather than the plain-block {@link BlockGeometryKit#buildFromElements}
 * path.
 *
 * <p>Reads every {@code models} entry through one generic path - {@code enchanting_table} and
 * {@code lectern} included, with no per-entry roster filtering. Each entry's {@code geometry}
 * coordinate resolves into {@code block_geometry.geometries}; a dangling coordinate fails LOUD
 * ({@link PipelineException}). Texture paths are reduced to the runtime {@code minecraft:<sub-path>}
 * form the texture resolver indexes on.
 */
@UtilityClass
public class BlockModelLoader {

    private static final @NotNull String MODELS_RESOURCE = "block_models.json";
    private static final @NotNull String GEOMETRY_RESOURCE = "block_geometry.json";
    private static final @NotNull String TEXTURE_PREFIX = "textures/";
    private static final @NotNull String TEXTURE_SUFFIX = ".png";

    /**
     * The result of loading {@code block_models.json}: the per-block-id primary geometry
     * ({@link #models}) plus any state-conditional geometry ({@link #variants}) a block-entity
     * model registers under a blockstate variant key. The pipeline context merges {@link #variants}
     * into each block's {@link Block#variants()} so the standard variant path selects them - the
     * ceiling hanging sign's straight-chain mesh is bound to {@code attached=true} this way.
     *
     * @param models block id to its primary (default-state) block-entity model
     * @param variants block id to its {@code variantKey -> geometry-bearing variant} map
     */
    public record LoadResult(
        @NotNull ConcurrentMap<String, Block.Entity> models,
        @NotNull ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variants
    ) {

        /**
         * The block-entity-backed block ids - the primary model ids unioned with the state-conditional
         * variant ids - the set the shadow diagnostic and the block-index attach passes key off.
         *
         * @return the union of primary and variant block-entity-backed ids
         */
        public @NotNull Set<String> blockEntityBackedIds() {
            Set<String> ids = new HashSet<>(this.models.keySet());
            ids.addAll(this.variants.keySet());
            return ids;
        }
    }

    /**
     * Loads block-entity geometry from the classpath snapshot with no pack override channel applied.
     *
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if the resource is missing or cannot be parsed
     */
    public static @NotNull LoadResult load() {
        return load(Diagnostics.root("blockModels", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Loads block-entity geometry from the classpath snapshot with the pack {@code renderer/*.json}
     * override channel applied, then reports any pack shipping a vanilla-form model / blockstate for a
     * code-rendered block entity. A vanilla-only stack ships no override, so the result is
     * byte-identical to {@link #load()} and the shadow report emits nothing.
     *
     * @param stack the resolved pack stack whose {@code renderer/*.json} override files are consulted
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if the resource is missing or cannot be parsed, or a pack override file
     *     fails format-2 envelope validation
     */
    public static @NotNull LoadResult load(@NotNull PackStack stack) {
        Diagnostics diagnostics = Diagnostics.root("blockModels", Diagnostics.Output.CONSOLE, null);
        LoadResult result = load(diagnostics, BlockRendererOverrides.gather(stack, diagnostics));
        BlockEntityShadowDiagnostics.report(stack, result.blockEntityBackedIds());
        return result;
    }

    /**
     * Reads the block-entity model catalog natively from the bundled classpath snapshot, with no pack
     * override channel applied.
     *
     * @param diagnostics the scope envelope and read warnings are recorded to
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if a resource is missing, malformed, or a geometry coordinate dangles
     */
    public static @NotNull LoadResult load(@NotNull Diagnostics diagnostics) {
        return load(diagnostics, BlockRendererOverrides.EMPTY);
    }

    /**
     * Reads the block-entity model catalog from the bundled classpath snapshot, then overlays the
     * pack-supplied {@code renderer/*.json} override channel per top-level entry: a pack {@code models}
     * entry replaces the classpath model of the same id, and a pack {@code geometries} entry replaces
     * the classpath bone tree at the same coordinate. An overridden id still renders through
     * {@link BlockGeometryKit#buildFromBones} - same kit, same lighting, same parity locks.
     *
     * @param diagnostics the scope envelope and read warnings are recorded to
     * @param overrides the gathered pack override channel; {@link BlockRendererOverrides#EMPTY} for a
     *     vanilla-only stack, which leaves the result byte-identical to the classpath snapshot
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if a resource is missing, malformed, or a geometry coordinate dangles
     */
    public static @NotNull LoadResult load(@NotNull Diagnostics diagnostics, @NotNull BlockRendererOverrides overrides) {
        ResourceDocument modelsDoc = BundledResource.read(MODELS_RESOURCE, BundledResource.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        ResourceDocument geometryDoc = BundledResource.read(GEOMETRY_RESOURCE, BundledResource.MissingPolicy.REQUIRED, diagnostics).orElseThrow();

        JsonNode models = modelsDoc.payload().get("models");
        JsonNode geometries = geometryDoc.payload().get("geometries");

        // Overlay the pack override channel per top-level entry (later-wins), so a pack replaces one
        // block-entity model or one geometry coordinate without shipping the whole snapshot.
        models.putAll(overrides.models());
        geometries.putAll(overrides.geometries());

        HashMap<String, Block.Entity> result = new HashMap<>();
        HashMap<String, HashMap<String, Block.Variant>> variantModels = new HashMap<>();

        for (Map.Entry<String, JsonNode> entry : models.members()) {
            String modelId = entry.getKey();
            JsonNode model = entry.getValue();
            Optional<JsonNode> blocks = model.findArray("blocks");
            if (blocks.isEmpty()) continue;   // part-only sub-models carry no blocks[]

            Block.Entity.BoneModel boneModel = buildBoneModel(modelId, model, geometries);
            int iconRotation = 0;
            boolean additive = false;
            JsonNode icon = model.get("icon");
            if (icon != null) {
                iconRotation = icon.getInt("rotation", 0);
                additive = icon.getBool("additive", false);
            }

            for (JsonNode block : blocks.get().elements()) {
                String blockId = block.getString("block");
                String textureId = stripTexture(block.getString("texture"));

                // A block bound to a blockstate "variant" contributes a state-conditional model, not
                // the block's primary geometry (e.g. the ceiling hanging sign's straight-chain mesh
                // under "attached=true"). Rotation/uvlock are unused here, so 0/0/false.
                if (block.has("variant")) {
                    variantModels.computeIfAbsent(blockId, k -> new HashMap<>())
                        .put(block.getString("variant"),
                            new Block.Variant(modelId, 0, 0, false, new Block.BoneGeometry(boneModel)));
                    continue;
                }

                ArrayList<Block.Entity.Part> parts = buildParts(models, model, geometries, textureId);
                int tintArgb = block.has("tint") ? resolveTint(block.getString("tint"), diagnostics) : ColorMath.WHITE;
                result.put(blockId, new Block.Entity(boneModel, textureId, tintArgb, iconRotation, Concurrent.adoptList(parts), additive));
            }
        }

        ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variants = Concurrent.newMap();
        for (Map.Entry<String, HashMap<String, Block.Variant>> variant : variantModels.entrySet())
            variants.put(variant.getKey(), Concurrent.adoptMap(variant.getValue()).toUnmodifiable());

        return new LoadResult(Concurrent.adoptMap(result).toUnmodifiable(), variants.toUnmodifiable());
    }

    /**
     * Builds a bone model from a model entry: resolves its {@code geometry} coordinate into the
     * geometry file and reads the entry's presentation metadata ({@code y_axis}, nested
     * {@code inventory}, {@code tinted}).
     */
    private static Block.Entity.BoneModel buildBoneModel(@NotNull String modelId, @NotNull JsonNode model, @NotNull JsonNode geometries) {
        // A block-bearing model entry must name a geometry coordinate; a pack renderer/block_models.json
        // override that omits it fails clearly (model-id attributed) rather than raising a bare NPE.
        if (!model.has("geometry") || !model.get("geometry").isPrimitive())
            throw new PipelineException("Block model '%s' has no 'geometry' coordinate", modelId);
        String coordinate = model.getString("geometry");
        JsonNode geometry = geometries.get(coordinate);
        if (geometry == null)
            throw new PipelineException("Block model '%s' references geometry '%s' which is absent from block_geometry", modelId, coordinate);
        EntityModelData mesh = geometry.as(EntityModelData.class);

        boolean sourceYUp = "UP".equals(model.getString("y_axis"));
        boolean tinted = model.getBool("tinted", false);

        float inventoryYRotation = 0f;
        boolean entityFlip = false;
        float @Nullable [] inventoryTransform = null;
        JsonNode inventory = model.get("inventory");
        if (inventory != null) {
            inventoryYRotation = inventory.getFloat("y_rotation", 0f);
            entityFlip = inventory.getBool("flip", false);
            Optional<JsonNode> transformArray = inventory.findArray("transform");
            if (transformArray.isPresent()) {
                JsonNode transform = transformArray.get();
                inventoryTransform = new float[transform.size()];
                for (int i = 0; i < transform.size(); i++) inventoryTransform[i] = transform.at(i).floatValue(0f);
            }
        }
        return new Block.Entity.BoneModel(mesh, sourceYUp, inventoryYRotation, entityFlip, inventoryTransform, tinted);
    }

    /**
     * Builds the sub-model parts of a model entry. Each part references a sibling model id in the same
     * {@code models} map; its texture is the part's own full path (stripped) when present, else the
     * parent block's texture.
     */
    private static @NotNull ArrayList<Block.Entity.Part> buildParts(@NotNull JsonNode models, @NotNull JsonNode model, @NotNull JsonNode geometries, @NotNull String parentTextureId) {
        ArrayList<Block.Entity.Part> parts = new ArrayList<>();
        Optional<JsonNode> partsArray = model.findArray("parts");
        if (partsArray.isEmpty()) return parts;

        for (JsonNode part : partsArray.get().elements()) {
            String partModelId = part.getString("model");
            JsonNode partModel = models.get(partModelId);
            if (partModel == null) continue;

            Block.Entity.BoneModel partBone = buildBoneModel(partModelId, partModel, geometries);
            String partTexture = part.has("texture") ? stripTexture(part.getString("texture")) : parentTextureId;
            float[] offset = {0, 0, 0};
            Optional<JsonNode> offsetArray = part.findArray("offset");
            if (offsetArray.isPresent()) {
                JsonNode off = offsetArray.get();
                offset = new float[]{off.at(0).floatValue(0f), off.at(1).floatValue(0f), off.at(2).floatValue(0f)};
            }
            parts.add(new Block.Entity.Part(partBone, partTexture, offset));
        }
        return parts;
    }

    /**
     * Reduces a full asset texture path to the runtime {@code minecraft:<sub-path>} id the texture
     * resolver indexes on - dropping {@code textures/} and {@code .png}, keeping the namespace.
     */
    private static @NotNull String stripTexture(@NotNull String path) {
        int colon = path.indexOf(':');
        String namespace = path.substring(0, colon + 1);
        String rest = path.substring(colon + 1);
        if (rest.startsWith(TEXTURE_PREFIX)) rest = rest.substring(TEXTURE_PREFIX.length());
        if (rest.endsWith(TEXTURE_SUFFIX)) rest = rest.substring(0, rest.length() - TEXTURE_SUFFIX.length());
        return namespace + rest;
    }

    /**
     * Resolves a banner {@code tint} DyeColor name to its ARGB; an unknown name warns and falls back
     * to white. The {@code tint} is always a DyeColor name, never a hex.
     */
    private static int resolveTint(@NotNull String name, @NotNull Diagnostics diagnostics) {
        DyeColor dye = DyeColor.ofName(name);
        if (dye != null) return dye.argb();
        diagnostics.warn("unknown block tint dye '%s' - using white", name);
        return ColorMath.WHITE;
    }

}
