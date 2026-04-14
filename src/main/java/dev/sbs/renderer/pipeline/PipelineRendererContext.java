package dev.sbs.renderer.pipeline;

import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextureEngine;
import dev.sbs.renderer.geometry.Biome;
import dev.sbs.renderer.geometry.BlockFace;
import dev.sbs.renderer.asset.Block;
import dev.sbs.renderer.asset.BlockTag;
import dev.sbs.renderer.asset.pack.ColorMap;
import dev.sbs.renderer.asset.Entity;
import dev.sbs.renderer.asset.Item;
import dev.sbs.renderer.asset.pack.Texture;
import dev.sbs.renderer.asset.pack.TexturePack;
import dev.sbs.renderer.asset.pack.AnimationData;
import dev.sbs.renderer.asset.model.BlockModelData;
import dev.sbs.renderer.asset.model.EntityModelData;
import dev.sbs.renderer.asset.model.ItemModelData;
import dev.sbs.renderer.asset.model.ModelElement;
import dev.sbs.renderer.asset.model.ModelFace;
import dev.sbs.renderer.pipeline.loader.BlockEntityModelLoader;
import dev.sbs.renderer.pipeline.loader.BlockTintsLoader;
import dev.sbs.renderer.pipeline.loader.EntityModelLoader;
import dev.sbs.renderer.pipeline.loader.OverlayResolver;
import dev.sbs.renderer.tooling.ToolingColorMaps;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link RendererContext} backed by a single {@link AssetPipeline.Result}.
 * <p>
 * The factory method materialises every parsed {@link BlockModelData} and {@link ItemModelData}
 * entry into a corresponding {@link Block} / {@link Item} entity eagerly, keyed by the derived
 * namespaced id ({@code minecraft:block/grass_block} - &gt; {@code minecraft:grass_block}). Texture
 * pixels are loaded lazily from disk on first {@link #resolveTexture(String)} call and memoised
 * in a per-context cache so repeated lookups stay cheap.
 * <p>
 * Block face bindings are flattened eagerly: the first element's face map is walked, each face's
 * {@code #variable} reference is dereferenced against the model's texture variable map, and the
 * resolved texture id is stored under the vanilla direction key ({@code down}, {@code up},
 * {@code north}, {@code south}, {@code west}, {@code east}) on the block entity. The underlying
 * variable map is preserved under its original keys for models that rely on the
 * {@code all} / {@code side} / {@code particle} fallback chain.
 * <p>
 * Biome colormaps and the {@link Biome.TintTarget} of every known vanilla tinted block are wired
 * through to render time: {@link ToolingColorMaps.Parser} loads {@code grass.png}, {@code foliage.png},
 * and {@code dry_foliage.png} into {@link ColorMap} entities, and {@link BlockTintsLoader}
 * supplies the {@code minecraft:grass_block} - to - {@code GRASS} (etc.) mapping verified against
 * the bytecode of {@code BlockColors$createDefault} in the 26.1 client jar. Entity definitions
 * are still empty - {@link #findEntity(String)} returns {@link Optional#empty()} until a future
 * pipeline phase ships an entity loader.
 */
@RequiredArgsConstructor
public final class PipelineRendererContext implements RendererContext {

    private final @NotNull Path textureRoot;
    private final @NotNull ConcurrentList<TexturePack> packs;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;
    private final @NotNull ConcurrentMap<String, Item> itemIndex;
    private final @NotNull ConcurrentMap<String, Entity> entityIndex;
    private final @NotNull ConcurrentMap<String, Texture> textureIndex;
    private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMapIndex;
    private final @NotNull ConcurrentMap<String, BlockTag> blockTagIndex;
    private final @NotNull ImageFactory imageFactory = new ImageFactory();
    private final @NotNull ConcurrentMap<String, PixelBuffer> textureCache = Concurrent.newMap();

    /**
     * Builds a context from a completed pipeline result.
     * <p>
     * Block and item entity materialisation happens eagerly so every {@code findBlock} /
     * {@code findItem} lookup is a pure map access. Textures stay on disk until
     * {@link #resolveTexture(String)} is called for them the first time.
     *
     * @param result the pipeline result to wrap
     * @return a new context scoped to the given result
     */
    public static @NotNull PipelineRendererContext of(@NotNull AssetPipeline.Result result) {
        ConcurrentList<TexturePack> packs = Concurrent.newList();
        packs.add(result.getVanillaPack());

        ConcurrentMap<String, Block.Tint> tints = result.getBlockTints();
        ConcurrentMap<String, String> itemDefs = result.getItemDefinitions();
        ConcurrentMap<String, ConcurrentMap<String, Block.Variant>> variantMap = result.getBlockStates();
        ConcurrentMap<String, Block.Multipart> multipartMap = result.getBlockMultiparts();
        BlockEntityModelLoader.LoadResult blockEntityResult = BlockEntityModelLoader.load();
        ConcurrentMap<String, Block.EntityMapping> entityMappings = blockEntityResult.getMappings();

        // Build reverse tag index (tag id → block ids becomes block id → tag names)
        ConcurrentMap<String, BlockTag> tagMap = result.getBlockTags();
        ConcurrentMap<String, ConcurrentList<String>> reverseTagIndex = Concurrent.newMap();
        for (Map.Entry<String, BlockTag> tagEntry : tagMap.entrySet()) {
            for (String blockId : tagEntry.getValue().getValues())
                reverseTagIndex.computeIfAbsent(blockId, k -> Concurrent.newList()).add(tagEntry.getKey());
        }

        ConcurrentMap<String, Block> blockIndex = Concurrent.newMap();
        for (Map.Entry<String, BlockModelData> blockEntry : result.getBlockModels().entrySet()) {
            String modelId = blockEntry.getKey();
            BlockModelData model = blockEntry.getValue();
            String blockId = stripPrefix(modelId, ":block/");
            String name = localName(modelId);

            // Use the item definition's model override when the inventory rendering model
            // differs from the blockstate model (e.g. piston -> piston_inventory).
            BlockModelData modelToUse = model;
            String itemModelRef = itemDefs.get(blockId);
            if (itemModelRef != null && !itemModelRef.equals(modelId)) {
                BlockModelData override = result.getBlockModels().get(itemModelRef);
                if (override != null)
                    modelToUse = override;
            }

            ConcurrentMap<String, String> textures = Concurrent.newMap();
            textures.putAll(modelToUse.getTextures());
            flattenElementFaces(modelToUse, textures);

            Block.Tint tint = tints.getOrDefault(blockId, new Block.Tint(Biome.TintTarget.NONE, Optional.empty()));
            ConcurrentMap<String, Block.Variant> variants = variantMap.getOrDefault(blockId, Concurrent.newMap());
            Optional<Block.Multipart> multipart = Optional.ofNullable(multipartMap.get(blockId));
            ConcurrentList<String> tags = reverseTagIndex.getOrDefault(blockId, Concurrent.newList());
            Optional<Block.EntityMapping> entityMapping = entityMappings.getOptional(blockId);

            blockIndex.put(blockId, new Block(blockId, "minecraft", name, modelToUse, textures, variants, multipart, tags, tint, entityMapping));
        }

        ConcurrentMap<String, Item> itemIndex = Concurrent.newMap();
        ConcurrentMap<String, int[]> spawnEggColors = Concurrent.newMap();
        for (Map.Entry<String, ItemModelData> itemEntry : result.getItemModels().entrySet()) {
            String modelId = itemEntry.getKey();
            ItemModelData model = itemEntry.getValue();
            String itemId = stripPrefix(modelId, ":item/");
            String name = localName(modelId);
            ConcurrentMap<String, String> textures = Concurrent.newMap();
            textures.putAll(model.getTextures());
            Optional<Item.Overlay> overlay = OverlayResolver.resolve(itemId, model, spawnEggColors);
            itemIndex.put(itemId, new Item(itemId, "minecraft", name, model, textures, 0, 64, overlay));
        }

        ConcurrentMap<String, Entity> entityIndex = Concurrent.newMap();
        for (Map.Entry<String, EntityModelLoader.EntityDefinition> entityEntry : EntityModelLoader.load().entrySet())
            entityIndex.put(entityEntry.getKey(), new Entity(entityEntry.getKey(), "minecraft", localName(entityEntry.getKey()), entityEntry.getValue().model(), entityEntry.getValue().textureId()));

        // Block entity models (chest, sign, bed, etc.) are registered alongside mob entities.
        entityIndex.putAll(blockEntityResult.getEntities());

        ConcurrentMap<String, Texture> textureIndex = Concurrent.newMap();
        for (Texture texture : result.getTextures())
            textureIndex.put(texture.getId(), texture);

        ConcurrentMap<ColorMap.Type, ColorMap> colorMapIndex = Concurrent.newMap();
        for (ColorMap colorMap : result.getColorMaps())
            colorMapIndex.put(colorMap.getType(), colorMap);

        Path textureRoot = result.getPackRoot()
            .resolve("assets")
            .resolve("minecraft")
            .resolve("textures");

        return new PipelineRendererContext(textureRoot, packs, blockIndex, itemIndex, entityIndex, textureIndex, colorMapIndex, tagMap);
    }

    @Override
    public @NotNull ConcurrentList<TexturePack> activePacks() {
        return this.packs;
    }

    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
        String normalized = textureId.contains(":") ? textureId : "minecraft:" + textureId;
        PixelBuffer cached = this.textureCache.get(normalized);
        if (cached != null) return Optional.of(cached);

        Texture texture = this.textureIndex.get(normalized);
        if (texture == null) return Optional.empty();

        Path file = this.textureRoot.resolve(texture.getRelativePath());
        PixelBuffer buffer = PixelBuffer.wrap(this.imageFactory.fromFile(file.toFile()).toBufferedImage());
        this.textureCache.put(normalized, buffer);
        return Optional.of(buffer);
    }

    @Override
    public @NotNull Optional<ColorMap> colorMap(@NotNull ColorMap.Type type) {
        return this.colorMapIndex.getOptional(type);
    }

    @Override
    public @NotNull Optional<Block> findBlock(@NotNull String id) {
        return this.blockIndex.getOptional(id);
    }

    @Override
    public @NotNull Optional<Item> findItem(@NotNull String id) {
        return this.itemIndex.getOptional(id);
    }

    @Override
    public @NotNull Optional<Entity> findEntity(@NotNull String id) {
        return this.entityIndex.getOptional(id);
    }

    @Override
    public @NotNull Optional<AnimationData> animationFor(@NotNull String textureId) {
        String normalized = textureId.contains(":") ? textureId : "minecraft:" + textureId;
        Texture texture = this.textureIndex.get(normalized);
        return texture == null ? Optional.empty() : texture.getAnimation();
    }

    @Override
    public @NotNull ConcurrentList<String> knownBlockIds() {
        ConcurrentList<String> ids = Concurrent.newList();
        ids.addAll(this.blockIndex.keySet());
        ids.sort((a, b) -> {
            String groupA = primaryTag(a);
            String groupB = primaryTag(b);
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(groupA, groupB);
            return cmp != 0 ? cmp : String.CASE_INSENSITIVE_ORDER.compare(a, b);
        });
        return ids;
    }

    @Override
    public @NotNull ConcurrentList<String> knownItemIds() {
        ConcurrentList<String> ids = Concurrent.newList();
        ids.addAll(this.itemIndex.keySet());
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }

    /**
     * Registers an entity definition so it can be looked up by
     * {@link #findEntity(String)}. Callers supply the model data directly since vanilla
     * Minecraft does not ship entity model JSON files - entity geometry is hardcoded in the
     * client source and changes between versions.
     *
     * @param id the namespaced entity id (e.g. {@code "minecraft:zombie"})
     * @param model the bone/cube tree describing the entity's geometry
     * @param textureId the default texture reference, or empty to require an override at render time
     */
    public void registerEntity(
        @NotNull String id,
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureId
    ) {
        this.entityIndex.put(id, new Entity(id, "minecraft", localName(id), model, textureId));
    }

    /**
     * Returns the most specific tag name for a block (the tag with fewest members), or the
     * block's material prefix as a fallback for untagged blocks. Used as the primary sort key
     * so semantically related blocks cluster together in atlas output.
     */
    private @NotNull String primaryTag(@NotNull String blockId) {
        Block block = this.blockIndex.get(blockId);
        if (block != null && !block.getTags().isEmpty()) {
            return block.getTags()
                .stream()
                .filter(this.blockTagIndex::containsKey)
                .min(java.util.Comparator.comparingInt(tag -> this.blockTagIndex.get(tag).getValues().size()))
                .orElse(blockId);
        }

        // Prefix heuristic: extract material from "minecraft:oak_stairs" → "oak"
        String name = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        int lastUnderscore = name.lastIndexOf('_');
        return lastUnderscore > 0 ? "~" + name.substring(0, lastUnderscore) : "~" + name;
    }

    /**
     * Removes the first occurrence of a {@code :prefix/} segment from a namespaced model id,
     * collapsing the result into a plain entity id. Returns the input untouched when the
     * segment is absent.
     */
    private static @NotNull String stripPrefix(@NotNull String modelId, @NotNull String segment) {
        int idx = modelId.indexOf(segment);
        if (idx < 0) return modelId;
        return modelId.substring(0, idx + 1) + modelId.substring(idx + segment.length());
    }

    /**
     * Returns the last path segment of a namespaced model id, used to populate the entity's
     * {@code name} column. Example: {@code minecraft:block/grass_block} - &gt; {@code grass_block}.
     */
    private static @NotNull String localName(@NotNull String modelId) {
        int slash = modelId.lastIndexOf('/');
        if (slash >= 0) return modelId.substring(slash + 1);
        int colon = modelId.lastIndexOf(':');
        return colon >= 0 ? modelId.substring(colon + 1) : modelId;
    }

    /**
     * Walks the first element in a block model and writes the resolved direction-to-texture
     * mapping into the supplied textures map. Each face's {@code #variable} reference is
     * dereferenced against the model's texture variable map until it bottoms out at a concrete
     * namespaced id or fails; unresolved or absent faces leave the direction key alone so the
     * fallback chain can take over.
     * <p>
     * Only the first element is considered. Vanilla cube blocks always use a single element, and
     * multi-element models (chests, doors, pistons) would otherwise trample each other writing
     * contradictory bindings into the same direction key. A later pipeline phase will walk all
     * elements and pick a representative face per direction for multi-element blocks.
     */
    private static void flattenElementFaces(@NotNull BlockModelData model, @NotNull ConcurrentMap<String, String> textures) {
        if (model.getElements().isEmpty()) return;
        ModelElement element = model.getElements().getFirst();

        for (BlockFace blockFace : BlockFace.values()) {
            ModelFace face = element.getFaces().get(blockFace.direction());
            if (face == null) continue;
            String textureRef = face.getTexture();
            if (textureRef.isBlank()) continue;
            String resolved = TextureEngine.dereferenceVariable(textureRef, model.getTextures());
            if (resolved.startsWith("#")) continue;
            textures.put(blockFace.direction(), resolved);
        }
    }

}
