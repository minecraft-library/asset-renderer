package lib.minecraft.renderer.pipeline.pack;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Shared path / namespace constants for every pipeline loader that walks a pre-extracted
 * Minecraft client jar or resource pack.
 * <p>
 * Every vanilla asset sits under {@link #VANILLA_ASSET_ROOT} or {@link #VANILLA_DATA_ROOT},
 * keyed by the {@link #MINECRAFT_NAMESPACE} prefix when referenced from JSON. Moving these
 * into one holder keeps the paths in lock-step if Mojang ever renames them, avoids subtle
 * typo drift between loaders, and gives each constant a single javadoc home.
 */
@UtilityClass
public class VanillaSourcePaths {

    /**
     * Default Minecraft resource namespace prefix for unqualified ids ({@code "stone"} &rarr;
     * {@code "minecraft:stone"}).
     */
    public static final @NotNull String MINECRAFT_NAMESPACE = "minecraft:";

    /**
     * Common jar subtree root every asset loader descends into, and the extraction prefix filter
     * for the asset subtree (models, blockstates, textures).
     */
    public static final @NotNull String VANILLA_ASSET_ROOT = "assets/minecraft/";

    /**
     * Extraction prefix filter for the data subtree (tags, recipes, world-gen).
     */
    public static final @NotNull String VANILLA_DATA_ROOT = "data/minecraft/";

    /**
     * Relative subpath (under {@code assets/<namespace>/}) of the block model subtree.
     */
    public static final @NotNull String MODELS_BLOCK_SUBDIR = "models/block";

    /**
     * Relative subpath (under {@code assets/<namespace>/}) of the item model subtree.
     */
    public static final @NotNull String MODELS_ITEM_SUBDIR = "models/item";

    /**
     * Relative subpath (under {@code assets/<namespace>/}) of the blockstate subtree.
     */
    public static final @NotNull String BLOCKSTATES_SUBDIR = "blockstates";

    /**
     * Relative subpath (under {@code assets/<namespace>/}) of the item definition subtree
     * (vanilla 26.1+ {@code items/}).
     */
    public static final @NotNull String ITEMS_SUBDIR = "items";

    /**
     * Relative subpath (under {@code assets/<namespace>/}) of the atlas source subtree
     * ({@code atlases/}), whose {@code paletted_permutations} entries drive texture synthesis.
     */
    public static final @NotNull String ATLASES_SUBDIR = "atlases";

    /**
     * Relative subpath (under {@code assets/<namespace>/}) of the equipment model subtree
     * ({@code equipment/}), one json per equipment asset (armor material, elytra, mob saddle / body).
     */
    public static final @NotNull String EQUIPMENT_SUBDIR = "equipment";

    /**
     * Model kind segment for block model ids ({@code <namespace>:block/...}).
     */
    public static final @NotNull String BLOCK_KIND = "block";

    /**
     * Model kind segment for item model ids ({@code <namespace>:item/...}).
     */
    public static final @NotNull String ITEM_KIND = "item";

    /**
     * The {@code assets/<namespace>/<subdir>} directory for a pack namespace.
     *
     * @param namespace the pack namespace (e.g. {@code minecraft}, {@code hypixel_skyblock})
     * @param subdir the relative subtree (e.g. {@link #MODELS_BLOCK_SUBDIR})
     * @return the namespaced assets directory path
     */
    public static @NotNull String assetSubdir(@NotNull String namespace, @NotNull String subdir) {
        return "assets/" + namespace + "/" + subdir;
    }

    /**
     * The model-id prefix {@code <namespace>:<kind>/} for inheritance chains and scanned model ids
     * (e.g. {@code minecraft:block/}, {@code hypixel_skyblock:item/}).
     *
     * @param namespace the pack namespace
     * @param kind the model kind segment ({@link #BLOCK_KIND} or {@link #ITEM_KIND})
     * @return the model-id prefix
     */
    public static @NotNull String modelIdPrefix(@NotNull String namespace, @NotNull String kind) {
        return namespace + ":" + kind + "/";
    }

    /**
     * The flat namespaced-id prefix {@code <namespace>:} used when deriving blockstate block ids and
     * item-definition item ids from their file paths (e.g. {@code minecraft:}, {@code hypixel_skyblock:}).
     *
     * @param namespace the pack namespace
     * @return the namespaced-id prefix
     */
    public static @NotNull String namespacePrefix(@NotNull String namespace) {
        return namespace + ":";
    }

    /**
     * Whether a model reference is a block model - its path segment (after any {@code namespace:}
     * prefix) starts with {@code block/}. Namespace-agnostic ({@code minecraft:block/x} and
     * {@code skyblock:block/x} both qualify), so block-item detection carries across packs.
     *
     * @param modelRef the model reference (e.g. {@code minecraft:block/piston_inventory})
     * @return {@code true} when the reference points at a block model
     */
    public static boolean isBlockModelRef(@NotNull String modelRef) {
        int colon = modelRef.indexOf(':');
        String path = colon < 0 ? modelRef : modelRef.substring(colon + 1);
        return path.startsWith(BLOCK_KIND + "/");
    }

    /**
     * Root directory for pack texture indexing.
     */
    public static final @NotNull String TEXTURES_DIR = VANILLA_ASSET_ROOT + "textures";

    /**
     * Texture path prefix used when converting {@code assets/minecraft/textures/X.png} &rarr;
     * {@code minecraft:X}.
     */
    public static final @NotNull String TEXTURES_PREFIX = VANILLA_ASSET_ROOT + "textures/";

    /**
     * OptiFine CIT (custom item texture) subtree path.
     */
    public static final @NotNull String OPTIFINE_CIT_DIR = VANILLA_ASSET_ROOT + "optifine/cit";

    /**
     * MCPatcher CIT subtree path.
     */
    public static final @NotNull String MCPATCHER_CIT_DIR = VANILLA_ASSET_ROOT + "mcpatcher/cit";

    /**
     * OptiFine CTM (connected-textures) subtree path.
     */
    public static final @NotNull String OPTIFINE_CTM_DIR = VANILLA_ASSET_ROOT + "optifine/ctm";

    /**
     * MCPatcher CTM subtree path.
     */
    public static final @NotNull String MCPATCHER_CTM_DIR = VANILLA_ASSET_ROOT + "mcpatcher/ctm";

    /**
     * OptiFine colour-properties override file.
     */
    public static final @NotNull String OPTIFINE_COLOR_PROPS = VANILLA_ASSET_ROOT + "optifine/color.properties";

    /**
     * MCPatcher colour-properties override file.
     */
    public static final @NotNull String MCPATCHER_COLOR_PROPS = VANILLA_ASSET_ROOT + "mcpatcher/color.properties";

}
