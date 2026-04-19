package lib.minecraft.renderer.pipeline;

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
public class VanillaPaths {

    /** Default Minecraft resource namespace prefix for unqualified ids (e.g. {@code "stone"} → {@code "minecraft:stone"}). */
    public static final @NotNull String MINECRAFT_NAMESPACE = "minecraft:";

    /** Common jar subtree root every asset loader descends into. */
    public static final @NotNull String VANILLA_ASSET_ROOT = "assets/minecraft/";

    /** Jar extraction filter for the data subtree (tags, recipes, world-gen). */
    public static final @NotNull String VANILLA_DATA_ROOT = "data/minecraft/";

    /** Directory containing vanilla block model JSON files. */
    public static final @NotNull String MODEL_BLOCK_DIR = VANILLA_ASSET_ROOT + "models/block";

    /** Directory containing vanilla item model JSON files. */
    public static final @NotNull String MODEL_ITEM_DIR = VANILLA_ASSET_ROOT + "models/item";

    /** Parent-id prefix for block model inheritance chains. */
    public static final @NotNull String MODEL_BLOCK_ID_PREFIX = MINECRAFT_NAMESPACE + "block/";

    /** Parent-id prefix for item model inheritance chains. */
    public static final @NotNull String MODEL_ITEM_ID_PREFIX = MINECRAFT_NAMESPACE + "item/";

    /** Directory containing blockstate JSON files. */
    public static final @NotNull String BLOCKSTATES_DIR = VANILLA_ASSET_ROOT + "blockstates";

    /** Directory containing item definition JSON files (vanilla 26.1+ {@code items/} subtree). */
    public static final @NotNull String ITEMS_DIR = VANILLA_ASSET_ROOT + "items";

    /** Root directory for pack texture indexing. */
    public static final @NotNull String TEXTURES_DIR = VANILLA_ASSET_ROOT + "textures";

    /** Texture path prefix used when converting {@code assets/minecraft/textures/X.png} → {@code minecraft:X}. */
    public static final @NotNull String TEXTURES_PREFIX = VANILLA_ASSET_ROOT + "textures/";

    /** OptiFine CIT (custom item texture) subtree path. */
    public static final @NotNull String OPTIFINE_CIT_DIR = VANILLA_ASSET_ROOT + "optifine/cit";

    /** MCPatcher CIT subtree path. */
    public static final @NotNull String MCPATCHER_CIT_DIR = VANILLA_ASSET_ROOT + "mcpatcher/cit";

    /** OptiFine CTM (connected-textures) subtree path. */
    public static final @NotNull String OPTIFINE_CTM_DIR = VANILLA_ASSET_ROOT + "optifine/ctm";

    /** MCPatcher CTM subtree path. */
    public static final @NotNull String MCPATCHER_CTM_DIR = VANILLA_ASSET_ROOT + "mcpatcher/ctm";

    /** OptiFine colour-properties override file. */
    public static final @NotNull String OPTIFINE_COLOR_PROPS = VANILLA_ASSET_ROOT + "optifine/color.properties";

    /** MCPatcher colour-properties override file. */
    public static final @NotNull String MCPATCHER_COLOR_PROPS = VANILLA_ASSET_ROOT + "mcpatcher/color.properties";

}
