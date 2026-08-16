package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockStateKey;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.spec.DyeColor;
import lib.minecraft.renderer.pipeline.loader.BlockModelReader.BlockModelEntry;
import lib.minecraft.renderer.pipeline.loader.BlockModelReader.BlockRef;
import lib.minecraft.renderer.pipeline.loader.BlockModelReader.InventoryDto;
import lib.minecraft.renderer.pipeline.loader.BlockModelReader.PartRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles the runtime block-entity index from the two pure reads: joins each raw
 * {@link BlockModelEntry}'s {@code geometry} coordinate against the geometry table, pivots the
 * model-id-keyed catalog into a block-id-keyed map, fans out each entry's {@code blocks[]}, joins
 * {@code parts[]} against sibling models, and applies the leaf transforms (dye tint, texture strip).
 * A {@code blocks[]} entry bound to a blockstate variant key contributes a state-conditional
 * {@link Block.Variant} instead of the block's primary {@link Block.Entity}.
 */
@UtilityClass
public final class BlockEntityAssembler {

    private static final @NotNull String TEXTURE_PREFIX = "textures/";
    private static final @NotNull String TEXTURE_SUFFIX = ".png";

    /**
     * Joins the raw model catalog against the geometry table and pivots it into the runtime block-entity
     * index.
     *
     * @param models the raw model catalog keyed by model id
     * @param geometries the geometry table keyed by coordinate
     * @return the primary models keyed by block id plus any per-variant state-conditional models
     * @throws PipelineException if a geometry coordinate dangles or a model entry has no coordinate
     */
    static @NotNull BlockModelLoader.LoadResult assemble(
            @NotNull Map<String, BlockModelEntry> models,
            @NotNull Map<String, EntityModelData> geometries) {
        HashMap<String, Block.Entity> result = new HashMap<>();
        HashMap<String, HashMap<String, Block.Variant>> variantModels = new HashMap<>();

        for (Map.Entry<String, BlockModelEntry> entry : models.entrySet()) {
            String modelId = entry.getKey();
            BlockModelEntry model = entry.getValue();
            if (model.blocks() == null) continue;   // part-only sub-models carry no blocks[]

            Block.Entity.BoneModel boneModel = buildBoneModel(modelId, model, geometries);
            int iconRotation = model.icon() != null ? model.icon().rotation() : 0;
            boolean additive = model.icon() != null && model.icon().additive();

            for (BlockRef block : model.blocks()) {
                String blockId = block.block();
                String textureId = stripTexture(block.texture());

                // A block bound to a blockstate "variant" contributes a state-conditional model, not
                // the block's primary geometry (e.g. the ceiling hanging sign's straight-chain mesh
                // under "attached=true"). Rotation/uvlock are unused here, so 0/0/false, and a
                // block-entity mesh is never one of an authored array, so there is nothing to draw.
                if (block.variant() != null) {
                    variantModels.computeIfAbsent(blockId, k -> new HashMap<>())
                        .put(block.variant(),
                            new Block.Variant(modelId, 0, 0, false, new Block.BoneGeometry(boneModel),
                                BlockStateKey.parse(block.variant()), Optional.empty()));
                    continue;
                }

                ArrayList<Block.Entity.Part> parts = buildParts(models, model, geometries, textureId);
                int tintArgb = block.tint() != null ? resolveTint(block.tint()) : ColorMath.WHITE;
                result.put(blockId, new Block.Entity(boneModel, textureId, tintArgb, iconRotation, Concurrent.adoptList(parts), additive));
            }
        }

        HashMap<String, ConcurrentMap<String, Block.Variant>> variants = new HashMap<>();
        for (Map.Entry<String, HashMap<String, Block.Variant>> variant : variantModels.entrySet())
            variants.put(variant.getKey(), Concurrent.adoptMap(variant.getValue()).toUnmodifiable());

        return new BlockModelLoader.LoadResult(
            Concurrent.adoptMap(result).toUnmodifiable(), Concurrent.adoptMap(variants).toUnmodifiable());
    }

    /**
     * Builds a bone model from a model entry: resolves its {@code geometry} coordinate into the geometry
     * table and reads the entry's presentation metadata ({@code y_axis}, nested {@code inventory},
     * {@code tinted}).
     */
    private static Block.Entity.BoneModel buildBoneModel(@NotNull String modelId, @NotNull BlockModelEntry model, @NotNull Map<String, EntityModelData> geometries) {
        // A block-bearing model entry must name a geometry coordinate; a pack renderer/block_models.json
        // override that omits it fails clearly (model-id attributed) rather than raising a bare NPE.
        if (model.geometry() == null)
            throw new PipelineException("Block model '%s' has no 'geometry' coordinate", modelId);
        String coordinate = model.geometry();
        EntityModelData mesh = geometries.get(coordinate);
        if (mesh == null)
            throw new PipelineException("Block model '%s' references geometry '%s' which is absent from block_geometry", modelId, coordinate);

        boolean sourceYUp = "UP".equals(model.yAxis());
        boolean tinted = model.tinted();

        float inventoryYRotation = 0f;
        boolean entityFlip = false;
        float @Nullable [] inventoryTransform = null;
        InventoryDto inventory = model.inventory();
        if (inventory != null) {
            inventoryYRotation = inventory.yRotation();
            entityFlip = inventory.flip();
            if (inventory.transform() != null) inventoryTransform = inventory.transform();
        }
        return new Block.Entity.BoneModel(mesh, sourceYUp, inventoryYRotation, entityFlip, inventoryTransform, tinted);
    }

    /**
     * Builds the sub-model parts of a model entry. Each part references a sibling model id in the same
     * catalog; its texture is the part's own full path (stripped) when present, else the parent block's
     * texture.
     */
    private static @NotNull ArrayList<Block.Entity.Part> buildParts(@NotNull Map<String, BlockModelEntry> models, @NotNull BlockModelEntry model, @NotNull Map<String, EntityModelData> geometries, @NotNull String parentTextureId) {
        ArrayList<Block.Entity.Part> parts = new ArrayList<>();
        if (model.parts() == null) return parts;

        for (PartRef part : model.parts()) {
            BlockModelEntry partModel = models.get(part.model());
            if (partModel == null) continue;

            Block.Entity.BoneModel partBone = buildBoneModel(part.model(), partModel, geometries);
            String partTexture = part.texture() != null ? stripTexture(part.texture()) : parentTextureId;
            float[] offset = part.offset() != null ? part.offset() : new float[]{0, 0, 0};
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
     * Resolves a banner {@code tint} DyeColor name to its ARGB; an unknown name falls back to white. The
     * {@code tint} is always a DyeColor name, never a hex.
     */
    private static int resolveTint(@NotNull String name) {
        DyeColor dye = DyeColor.ofName(name);
        if (dye != null) return dye.argb();
        // TODO: restore pipeline diagnostics
        // diagnostics.warn("unknown block tint dye '%s' - using white", name);
        return ColorMath.WHITE;
    }
}
