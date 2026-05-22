package lib.minecraft.renderer.tooling.util;

import lib.minecraft.renderer.pipeline.util.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Centralised JVM internal-name constants for every vanilla Minecraft class referenced by
 * the {@code asset-renderer} tooling layer. One file, one place to update on every Minecraft
 * version bump that renames or repackages a vanilla class.
 *
 * <p>Constants are composed from a small set of private package-root prefixes
 * ({@code VANILLA_SOURCE_ROOT}, {@code CLIENT_ROOT}, {@code MODEL_ROOT}, ...) so a Mojang
 * package rename can be made in one line. The roots are private because callers never
 * construct ad-hoc class names - they reference the named final constants below.
 *
 * <p>All names match the JVM convention - slash-separated package, dollar sign for nested
 * classes ({@code SkullBlock$Types}), no leading {@code L} or trailing {@code ;}. The one
 * package-prefix constant ({@link #EFFECT_PACKAGE_PREFIX}) keeps its trailing slash because
 * its consumer ({@code isNewInstance(node, prefix)}) wants a {@code startsWith} predicate.
 *
 * <p>The sibling {@link VanillaSourcePaths} holds runtime resource-pack paths
 * ({@code assets/minecraft/}, {@code data/minecraft/}, {@code minecraft:} namespace).
 * The two files split along the runtime / dev-time tooling boundary: paths feed production
 * loaders; class internal names feed dev-time ASM walkers.
 *
 * <p>Method-name constants ({@code "register"}, {@code "of"}, {@code "setupRotations"},
 * {@code "withDefaultNamespace"}, etc.) stay local to the individual parser that uses them.
 * Centralizing names would imply shared meaning that doesn't exist - two parsers both
 * calling a {@code "register"} method on unrelated vanilla classes is coincidence, not a
 * common pattern.
 *
 * @see VanillaSourcePaths
 * @see AsmKit
 */
@UtilityClass
public final class VanillaSourceClasses {

    // ============================================================================================
    // Package roots (private)
    // ============================================================================================

    /** Top-level vanilla package prefix. */
    private static final @NotNull String VANILLA_SOURCE_ROOT = "net/minecraft/";

    // --- client subtree -------------------------------------------------------------------------

    /** Client-side classes (renderer, model, color, resources). */
    private static final @NotNull String CLIENT_ROOT = VANILLA_SOURCE_ROOT + "client/";

    /** Model geometry primitives ({@code ModelPart}, {@code PartPose}, ...). */
    private static final @NotNull String MODEL_ROOT = CLIENT_ROOT + "model/geom/";

    /** Mesh / layer / cube builders ({@code LayerDefinition.create}, {@code CubeListBuilder}). */
    private static final @NotNull String MODEL_BUILDERS_ROOT = MODEL_ROOT + "builders/";

    /** Renderer base classes. */
    private static final @NotNull String RENDERER_ROOT = CLIENT_ROOT + "renderer/";

    /** Block-entity renderer subtree ({@code BlockEntityRenderers}, {@code SkullBlockRenderer}, ...). */
    private static final @NotNull String BLOCK_ENTITY_RENDERER_ROOT = RENDERER_ROOT + "blockentity/";

    /** Entity renderer subtree ({@code EntityRenderers} and friends). */
    private static final @NotNull String ENTITY_RENDERER_ROOT = RENDERER_ROOT + "entity/";

    /** Special-purpose renderers ({@code ChestSpecialRenderer}). */
    private static final @NotNull String SPECIAL_RENDERER_ROOT = RENDERER_ROOT + "special/";

    /** Color / tint subtree ({@code ColorLerper}, {@code BlockColors}). */
    private static final @NotNull String COLOR_ROOT = CLIENT_ROOT + "color/";

    /** Block-color subtree ({@code BlockColors}, {@code BlockTintSources}). */
    private static final @NotNull String BLOCK_COLOR_ROOT = COLOR_ROOT + "block/";

    /** Client-side resource holders ({@code DefaultPlayerSkin}). */
    private static final @NotNull String CLIENT_RESOURCES_ROOT = CLIENT_ROOT + "resources/";

    // --- world subtree --------------------------------------------------------------------------

    /** World-side classes (entities, blocks, items, effects). */
    private static final @NotNull String WORLD_ROOT = VANILLA_SOURCE_ROOT + "world/";

    /** Entity subtree ({@code EntityType}, {@code MobCategory}, {@code LivingEntity}). */
    private static final @NotNull String ENTITY_ROOT = WORLD_ROOT + "entity/";

    /** Animal / golem subtree. */
    private static final @NotNull String GOLEM_ROOT = ENTITY_ROOT + "animal/golem/";

    /** Level / world structure subtree. */
    private static final @NotNull String LEVEL_ROOT = WORLD_ROOT + "level/";

    /** Block subtree ({@code Blocks}, {@code ChestBlock}, {@code BedBlock}, ...). */
    private static final @NotNull String BLOCK_ROOT = LEVEL_ROOT + "block/";

    /** Block-entity subtree ({@code BlockEntityType}, {@code BannerPattern}). */
    private static final @NotNull String BLOCK_ENTITY_ROOT = BLOCK_ROOT + "entity/";

    /** Block state-property enums ({@code WoodType}, {@code BellAttachType}). */
    private static final @NotNull String BLOCK_PROPERTIES_ROOT = BLOCK_ROOT + "state/properties/";

    /** Mob-effect subtree. */
    private static final @NotNull String EFFECT_ROOT = WORLD_ROOT + "effect/";

    /** Item subtree ({@code DyeColor}). */
    private static final @NotNull String ITEM_ROOT = WORLD_ROOT + "item/";

    // --- resources ------------------------------------------------------------------------------

    /** Resource-location subtree ({@code Identifier}). */
    private static final @NotNull String RESOURCES_ROOT = VANILLA_SOURCE_ROOT + "resources/";

    // ============================================================================================
    // Model geometry
    // ============================================================================================

    /** Entity model bone primitive. */
    public static final @NotNull String MODEL_PART = MODEL_ROOT + "ModelPart";

    /** Static-field registry of all baked {@code ModelLayerLocation}s. */
    public static final @NotNull String MODEL_LAYERS = MODEL_ROOT + "ModelLayers";

    /** Identifier wrapping a model layer (key into {@code ModelLayers}). */
    public static final @NotNull String MODEL_LAYER_LOCATION = MODEL_ROOT + "ModelLayerLocation";

    /** Per-renderer registry of baked layers. */
    public static final @NotNull String ENTITY_MODEL_SET = MODEL_ROOT + "EntityModelSet";

    /** Static map of {@code ModelLayer -> LayerDefinition} populated in {@code <clinit>}. */
    public static final @NotNull String LAYER_DEFINITIONS = MODEL_ROOT + "LayerDefinitions";

    /** Bone pose record (offset + rotation), wrapped by {@code PartPose.offsetAndRotation}. */
    public static final @NotNull String PART_POSE = MODEL_ROOT + "PartPose";

    /** Bone-name constant catalogue ({@code "head"}, {@code "right_arm"}, ...). */
    public static final @NotNull String PART_NAMES = MODEL_ROOT + "PartNames";

    /** Compiled mesh bound to an entity renderer. */
    public static final @NotNull String LAYER_DEFINITION = MODEL_BUILDERS_ROOT + "LayerDefinition";

    /** Pre-compile mesh factory product, wrapped by {@code LayerDefinition.create}. */
    public static final @NotNull String MESH_DEFINITION = MODEL_BUILDERS_ROOT + "MeshDefinition";

    /** Post-bake mesh transform (e.g. cat's {@code scaling(0.8f)}). */
    public static final @NotNull String MESH_TRANSFORMER = MODEL_BUILDERS_ROOT + "MeshTransformer";

    /** Per-bone cube list accumulator used by every vanilla {@code createBodyLayer}. */
    public static final @NotNull String CUBE_LIST_BUILDER = MODEL_BUILDERS_ROOT + "CubeListBuilder";

    /** Per-bone inflate / deflate factor applied to every cube on the bone. */
    public static final @NotNull String CUBE_DEFORMATION = MODEL_BUILDERS_ROOT + "CubeDeformation";

    /** Per-bone definition entry; the {@code addOrReplaceChild} call target. */
    public static final @NotNull String PART_DEFINITION = MODEL_BUILDERS_ROOT + "PartDefinition";

    // ============================================================================================
    // Entity registries
    // ============================================================================================

    /** Entity-type registry class. */
    public static final @NotNull String ENTITY_TYPE = ENTITY_ROOT + "EntityType";

    /** Builder companion used by {@code EntityType.<clinit>} registrations. */
    public static final @NotNull String ENTITY_TYPE_BUILDER = ENTITY_TYPE + "$Builder";

    /** Per-mob spawn category enum (MONSTER, CREATURE, AMBIENT, ...). */
    public static final @NotNull String MOB_CATEGORY = ENTITY_ROOT + "MobCategory";

    /** Living-entity base class - used as the {@code extendsClass} predicate target for mob discovery. */
    public static final @NotNull String LIVING_ENTITY = ENTITY_ROOT + "LivingEntity";

    /** Entity-renderer registry class. */
    public static final @NotNull String ENTITY_RENDERERS = ENTITY_RENDERER_ROOT + "EntityRenderers";

    // ============================================================================================
    // Block-entity registries
    // ============================================================================================

    /** Block-entity type registry class. */
    public static final @NotNull String BLOCK_ENTITY_TYPE = BLOCK_ENTITY_ROOT + "BlockEntityType";

    /** Block-entity renderer registry class. */
    public static final @NotNull String BLOCK_ENTITY_RENDERERS = BLOCK_ENTITY_RENDERER_ROOT + "BlockEntityRenderers";

    /** Hardcoded special chest renderer (vanilla's {@code ChestSpecialRenderer.<clinit>} pairs DyeColor / blocks). */
    public static final @NotNull String CHEST_SPECIAL_RENDERER = SPECIAL_RENDERER_ROOT + "ChestSpecialRenderer";

    /** Skull renderer - holds the {@code SKULL_RENDERERS} map. */
    public static final @NotNull String SKULL_BLOCK_RENDERER = BLOCK_ENTITY_RENDERER_ROOT + "SkullBlockRenderer";

    /** Conduit renderer. */
    public static final @NotNull String CONDUIT_RENDERER = BLOCK_ENTITY_RENDERER_ROOT + "ConduitRenderer";

    /** Bell renderer. */
    public static final @NotNull String BELL_RENDERER = BLOCK_ENTITY_RENDERER_ROOT + "BellRenderer";

    // ============================================================================================
    // Block classes
    // ============================================================================================

    /** Block-id constant table - {@code Blocks.<NAME>} GETSTATIC. */
    public static final @NotNull String BLOCKS = BLOCK_ROOT + "Blocks";

    /** Vanilla chest block (oak / spruce / ... variants). */
    public static final @NotNull String CHEST_BLOCK = BLOCK_ROOT + "ChestBlock";

    /** Trapped chest variant. */
    public static final @NotNull String TRAPPED_CHEST_BLOCK = BLOCK_ROOT + "TrappedChestBlock";

    /** Ender chest variant. */
    public static final @NotNull String ENDER_CHEST_BLOCK = BLOCK_ROOT + "EnderChestBlock";

    /** Copper chest base block (MC 1.21+). */
    public static final @NotNull String COPPER_CHEST_BLOCK = BLOCK_ROOT + "CopperChestBlock";

    /** Weathered copper chest variants (waxed unaffected, exposed, weathered, oxidized). */
    public static final @NotNull String WEATHERING_COPPER_CHEST_BLOCK = BLOCK_ROOT + "WeatheringCopperChestBlock";

    /** Weathering enum referenced by copper chest / copper golem statue classes. */
    public static final @NotNull String WEATHERING_COPPER_STATE = BLOCK_ROOT + "WeatheringCopper$WeatherState";

    /** Bed block (per-dye-color via static factory). */
    public static final @NotNull String BED_BLOCK = BLOCK_ROOT + "BedBlock";

    /** Shulker box block (per-dye-color). */
    public static final @NotNull String SHULKER_BOX_BLOCK = BLOCK_ROOT + "ShulkerBoxBlock";

    /** Standing sign block - per wood type. */
    public static final @NotNull String STANDING_SIGN_BLOCK = BLOCK_ROOT + "StandingSignBlock";

    /** Wall sign block - per wood type. */
    public static final @NotNull String WALL_SIGN_BLOCK = BLOCK_ROOT + "WallSignBlock";

    /** Ceiling hanging sign - per wood type. */
    public static final @NotNull String CEILING_HANGING_SIGN_BLOCK = BLOCK_ROOT + "CeilingHangingSignBlock";

    /** Wall hanging sign - per wood type. */
    public static final @NotNull String WALL_HANGING_SIGN_BLOCK = BLOCK_ROOT + "WallHangingSignBlock";

    /** Standing banner block - per dye color. */
    public static final @NotNull String BANNER_BLOCK = BLOCK_ROOT + "BannerBlock";

    /** Wall banner block - per dye color. */
    public static final @NotNull String WALL_BANNER_BLOCK = BLOCK_ROOT + "WallBannerBlock";

    /** Copper golem statue base block. */
    public static final @NotNull String COPPER_GOLEM_STATUE_BLOCK = BLOCK_ROOT + "CopperGolemStatueBlock";

    /** Weathered copper golem statue variants. */
    public static final @NotNull String WEATHERING_COPPER_GOLEM_STATUE_BLOCK = BLOCK_ROOT + "WeatheringCopperGolemStatueBlock";

    /** Skull-block {@code $Types} nested enum (player, zombie, skeleton, ...). */
    public static final @NotNull String SKULL_TYPES = BLOCK_ROOT + "SkullBlock$Types";

    // ============================================================================================
    // Color / tint
    // ============================================================================================

    /** Dye colour enum (16 vanilla colours, used by banner / shulker / bed). */
    public static final @NotNull String DYE_COLOR = ITEM_ROOT + "DyeColor";

    /** Block colour registry - {@code createDefault} is the tint walker's entry point. */
    public static final @NotNull String BLOCK_COLORS = BLOCK_COLOR_ROOT + "BlockColors";

    /** Static factory for {@code BlockTintSource} producers (grass, foliage, redstone, ...). */
    public static final @NotNull String BLOCK_TINT_SOURCES = BLOCK_COLOR_ROOT + "BlockTintSources";

    /** Color lerper {@code $Type} enum referenced by sheep / cat overlay layers. */
    public static final @NotNull String COLOR_LERPER_TYPE = COLOR_ROOT + "ColorLerper$Type";

    /** Copper-golem oxidation enum (none / exposed / weathered / oxidized) - drives variant texture. */
    public static final @NotNull String COPPER_GOLEM_OXIDATION_LEVELS = GOLEM_ROOT + "CopperGolemOxidationLevels";

    // ============================================================================================
    // Effects
    // ============================================================================================

    /** Mob-effect registry class - {@code <clinit>} is the potion colour walker's entry point. */
    public static final @NotNull String MOB_EFFECTS = EFFECT_ROOT + "MobEffects";

    /** Package prefix used to gate {@code NEW MobEffect-or-subclass} matches (kept with trailing slash for {@code startsWith}). */
    public static final @NotNull String EFFECT_PACKAGE_PREFIX = EFFECT_ROOT;

    // ============================================================================================
    // Properties / types
    // ============================================================================================

    /** Wood type enum (oak, spruce, birch, ...) - keyed by sign / banner variants. */
    public static final @NotNull String WOOD_TYPE = BLOCK_PROPERTIES_ROOT + "WoodType";

    /** Bell attach-type enum (floor, ceiling, single wall, double wall). */
    public static final @NotNull String BELL_ATTACH_TYPE = BLOCK_PROPERTIES_ROOT + "BellAttachType";

    // ============================================================================================
    // Banners
    // ============================================================================================

    /** Banner pattern enum - referenced by {@code BannerPattern.getColor}. */
    public static final @NotNull String BANNER_PATTERN = BLOCK_ENTITY_ROOT + "BannerPattern";

    /** Pattern-layer holder - signals patterned-tint pipeline. */
    public static final @NotNull String BANNER_PATTERN_LAYERS = BLOCK_ENTITY_ROOT + "BannerPatternLayers";

    // ============================================================================================
    // Resources / rendering
    // ============================================================================================

    /** Identifier (formerly {@code ResourceLocation}) - wraps texture paths. */
    public static final @NotNull String IDENTIFIER = RESOURCES_ROOT + "Identifier";

    /** Default player-skin holder - referenced by skull-renderer for the fallback face. */
    public static final @NotNull String DEFAULT_PLAYER_SKIN = CLIENT_RESOURCES_ROOT + "DefaultPlayerSkin";

}
