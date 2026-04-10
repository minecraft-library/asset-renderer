package dev.sbs.renderer.pipeline;

import dev.sbs.renderer.biome.BiomeTintTarget;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.exception.RendererException;
import dev.sbs.renderer.model.Block;
import dev.sbs.renderer.model.ColorMap;
import dev.sbs.renderer.model.Entity;
import dev.sbs.renderer.model.Item;
import dev.sbs.renderer.model.Texture;
import dev.sbs.renderer.model.TexturePack;
import dev.sbs.renderer.model.asset.AnimationData;
import dev.sbs.renderer.model.asset.BlockModelData;
import dev.sbs.renderer.model.asset.ItemModelData;
import dev.sbs.renderer.model.asset.ModelElement;
import dev.sbs.renderer.model.asset.ModelFace;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.PixelBuffer;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
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
 * Biome colormaps and the {@link BiomeTintTarget} of every known vanilla tinted block are wired
 * through to render time: {@link ColorMapReader} loads {@code grass.png}, {@code foliage.png},
 * and {@code dry_foliage.png} into {@link ColorMap} entities, and {@link VanillaTintsLoader}
 * supplies the {@code minecraft:grass_block} - to - {@code GRASS} (etc.) mapping verified against
 * the bytecode of {@code BlockColors$createDefault} in the 26.1 client jar. Entity definitions
 * are still empty - {@link #findEntity(String)} returns {@link Optional#empty()} until a future
 * pipeline phase ships an entity loader.
 */
public final class PipelineRendererContext implements RendererContext {

    /**
     * The six vanilla face direction keys, iterated in the canonical order that
     * {@link dev.sbs.renderer.BlockRenderer BlockRenderer} expects when walking cube faces.
     */
    private static final @NotNull String @NotNull [] FACE_DIRECTIONS =
        { "down", "up", "north", "south", "west", "east" };

    private static final @NotNull Reflection<Block> BLOCK_REFLECTION = new Reflection<>(Block.class);
    private static final @NotNull FieldAccessor<String> BLOCK_ID = BLOCK_REFLECTION.getField("id");
    private static final @NotNull FieldAccessor<String> BLOCK_NAMESPACE = BLOCK_REFLECTION.getField("namespace");
    private static final @NotNull FieldAccessor<String> BLOCK_NAME = BLOCK_REFLECTION.getField("name");
    private static final @NotNull FieldAccessor<BlockModelData> BLOCK_MODEL = BLOCK_REFLECTION.getField("model");
    private static final @NotNull FieldAccessor<ConcurrentMap<String, String>> BLOCK_TEXTURES = BLOCK_REFLECTION.getField("textures");
    private static final @NotNull FieldAccessor<Block.Tint> BLOCK_TINT = BLOCK_REFLECTION.getField("tint");

    private static final @NotNull Reflection<Item> ITEM_REFLECTION = new Reflection<>(Item.class);
    private static final @NotNull FieldAccessor<String> ITEM_ID = ITEM_REFLECTION.getField("id");
    private static final @NotNull FieldAccessor<String> ITEM_NAMESPACE = ITEM_REFLECTION.getField("namespace");
    private static final @NotNull FieldAccessor<String> ITEM_NAME = ITEM_REFLECTION.getField("name");
    private static final @NotNull FieldAccessor<ItemModelData> ITEM_MODEL = ITEM_REFLECTION.getField("model");
    private static final @NotNull FieldAccessor<ConcurrentMap<String, String>> ITEM_TEXTURES = ITEM_REFLECTION.getField("textures");

    private final @NotNull Path textureRoot;
    private final @NotNull ConcurrentList<TexturePack> packs;
    private final @NotNull ConcurrentMap<String, Block> blockIndex;
    private final @NotNull ConcurrentMap<String, Item> itemIndex;
    private final @NotNull ConcurrentMap<String, Texture> textureIndex;
    private final @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMapIndex;
    private final @NotNull ConcurrentMap<String, PixelBuffer> textureCache = Concurrent.newMap();

    private PipelineRendererContext(
        @NotNull Path textureRoot,
        @NotNull ConcurrentList<TexturePack> packs,
        @NotNull ConcurrentMap<String, Block> blockIndex,
        @NotNull ConcurrentMap<String, Item> itemIndex,
        @NotNull ConcurrentMap<String, Texture> textureIndex,
        @NotNull ConcurrentMap<ColorMap.Type, ColorMap> colorMapIndex
    ) {
        this.textureRoot = textureRoot;
        this.packs = packs;
        this.blockIndex = blockIndex;
        this.itemIndex = itemIndex;
        this.textureIndex = textureIndex;
        this.colorMapIndex = colorMapIndex;
    }

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

        ConcurrentMap<String, Block> blockIndex = Concurrent.newMap();
        result.getBlockModels().forEach((modelId, model) -> {
            String blockId = stripPrefix(modelId, ":block/");
            String name = localName(modelId);
            blockIndex.put(blockId, newBlock(blockId, name, model, tints.get(blockId)));
        });

        ConcurrentMap<String, Item> itemIndex = Concurrent.newMap();
        result.getItemModels().forEach((modelId, model) -> {
            String itemId = stripPrefix(modelId, ":item/");
            String name = localName(modelId);
            itemIndex.put(itemId, newItem(itemId, name, model));
        });

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

        return new PipelineRendererContext(textureRoot, packs, blockIndex, itemIndex, textureIndex, colorMapIndex);
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
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) return Optional.empty();
            PixelBuffer buffer = PixelBuffer.wrap(image);
            this.textureCache.put(normalized, buffer);
            return Optional.of(buffer);
        } catch (IOException ex) {
            throw new RendererException(ex, "Failed to load texture '%s' from '%s'", normalized, file);
        }
    }

    @Override
    public @NotNull Optional<ColorMap> colorMap(@NotNull ColorMap.Type type) {
        return Optional.ofNullable(this.colorMapIndex.get(type));
    }

    @Override
    public @NotNull Optional<Block> findBlock(@NotNull String id) {
        return Optional.ofNullable(this.blockIndex.get(id));
    }

    @Override
    public @NotNull Optional<Item> findItem(@NotNull String id) {
        return Optional.ofNullable(this.itemIndex.get(id));
    }

    @Override
    public @NotNull Optional<Entity> findEntity(@NotNull String id) {
        return Optional.empty();
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
        return ids;
    }

    @Override
    public @NotNull ConcurrentList<String> knownItemIds() {
        ConcurrentList<String> ids = Concurrent.newList();
        ids.addAll(this.itemIndex.keySet());
        return ids;
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
     * Materialises a {@link Block} entity from a parsed model.
     * <p>
     * The resulting {@code textures} map contains both the original variable bindings from the
     * model JSON ({@code all}, {@code top}, {@code side}, {@code particle}, ...) and a flattened
     * direction-to-texture map derived from the model's first element face bindings. The direction
     * keys let {@link dev.sbs.renderer.BlockRenderer BlockRenderer} pick up the most specific
     * match per face without a second resolution pass at render time; the original keys survive
     * so the {@code all} / {@code side} / {@code particle} fallback chain still works for blocks
     * whose models do not expose element faces (e.g. {@code item/generated}-parented block items).
     * <p>
     * When a {@link Block.Tint} is supplied (the block id appears in any pack-bundled tints
     * table) the entity's {@code tint} field is set so
     * {@link dev.sbs.renderer.BlockRenderer BlockRenderer} samples the matching colormap or
     * hardcoded ARGB at render time. Untinted blocks keep the default ({@code NONE}, no constant).
     */
    private static @NotNull Block newBlock(
        @NotNull String id,
        @NotNull String name,
        @NotNull BlockModelData model,
        @org.jetbrains.annotations.Nullable Block.Tint tint
    ) {
        Block block = new Block();
        BLOCK_ID.set(block, id);
        BLOCK_NAMESPACE.set(block, "minecraft");
        BLOCK_NAME.set(block, name);
        BLOCK_MODEL.set(block, model);

        ConcurrentMap<String, String> textures = Concurrent.newMap();
        textures.putAll(model.getTextures());
        flattenElementFaces(model, textures);
        BLOCK_TEXTURES.set(block, textures);

        if (tint != null)
            BLOCK_TINT.set(block, tint);

        return block;
    }

    /**
     * Materialises an {@link Item} entity from a parsed model. The model's texture variable
     * bindings are copied verbatim into the entity's {@code textures} column so the flat-item
     * sprite path in {@link dev.sbs.renderer.ItemRenderer.Gui2D ItemRenderer.Gui2D} picks up
     * {@code layer0}, {@code layer1}, etc. directly.
     */
    private static @NotNull Item newItem(@NotNull String id, @NotNull String name, @NotNull ItemModelData model) {
        Item item = new Item();
        ITEM_ID.set(item, id);
        ITEM_NAMESPACE.set(item, "minecraft");
        ITEM_NAME.set(item, name);
        ITEM_MODEL.set(item, model);

        ConcurrentMap<String, String> textures = Concurrent.newMap();
        textures.putAll(model.getTextures());
        ITEM_TEXTURES.set(item, textures);
        return item;
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

        for (String direction : FACE_DIRECTIONS) {
            ModelFace face = element.getFaces().get(direction);
            if (face == null) continue;
            String textureRef = face.getTexture();
            if (textureRef.isBlank()) continue;
            String resolved = dereferenceVariable(textureRef, model.getTextures());
            if (resolved.startsWith("#")) continue;
            textures.put(direction, resolved);
        }
    }

    /**
     * Walks a {@code #variable} chain until it terminates at a concrete namespaced id or fails
     * to resolve. Cycle-guarded so a malformed pack cannot hang the loader.
     */
    private static @NotNull String dereferenceVariable(@NotNull String reference, @NotNull ConcurrentMap<String, String> variables) {
        String current = reference;
        ConcurrentSet<String> visited = Concurrent.newSet();
        while (current.startsWith("#")) {
            if (!visited.add(current)) return current;
            String next = variables.get(current.substring(1));
            if (next == null) return current;
            current = next;
        }
        return current;
    }

}
