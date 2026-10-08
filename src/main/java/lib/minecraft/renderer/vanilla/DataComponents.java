package lib.minecraft.renderer.vanilla;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The data components vanilla 26.1 registers, as vanilla's {@code DataComponents} lists them, and the
 * predicate types a {@code minecraft:component} condition tests a stack's components by.
 *
 * <p>A component is keyed by its path under {@code minecraft:} - the form a definition's id takes once
 * its namespace is read as vanilla's - and a predicate type by its qualified id, the form a component
 * condition names one in. {@link #CUSTOM_DATA} is both: a registered component, and the one predicate
 * type that reads it.
 *
 * <p>A fixed roster rather than pack state - a pack names which component a test reads, never that a
 * new component exists.
 */
@UtilityClass
@Parity(claim = "asset-layer")
public class DataComponents {

    /** The prefix a 26.1 component patch writes before the id of a component the stack removes. */
    public static final @NotNull String REMOVED = "!";

    /** The {@code minecraft:custom_data} id - a registered component, and the predicate type that tests it. */
    public static final @NotNull String CUSTOM_DATA = "minecraft:custom_data";

    /**
     * The data components vanilla 26.1 registers, by path under {@code minecraft:} - the
     * vanilla-namespace ids a presence test, a {@code has_component} condition and a component select
     * may name.
     */
    private static final @NotNull ConcurrentSet<String> REGISTERED = Concurrent.newUnmodifiableSet(
        "custom_data", "max_stack_size", "max_damage", "damage", "unbreakable", "use_effects", "custom_name",
        "minimum_attack_charge", "damage_type", "item_name", "item_model", "lore", "rarity", "enchantments",
        "can_place_on", "can_break", "attribute_modifiers", "custom_model_data", "tooltip_display",
        "repair_cost", "creative_slot_lock", "enchantment_glint_override", "intangible_projectile", "food",
        "consumable", "use_remainder", "use_cooldown", "damage_resistant", "tool", "weapon", "attack_range",
        "enchantable", "equippable", "repairable", "glider", "tooltip_style", "death_protection",
        "blocks_attacks", "piercing_weapon", "kinetic_weapon", "swing_animation", "additional_trade_cost",
        "stored_enchantments", "dye", "dyed_color", "map_color", "map_id", "map_decorations",
        "map_post_processing", "charged_projectiles", "bundle_contents", "potion_contents",
        "potion_duration_scale", "suspicious_stew_effects", "writable_book_content", "written_book_content",
        "trim", "debug_stick_state", "entity_data", "bucket_entity_data", "block_entity_data", "instrument",
        "provides_trim_material", "ominous_bottle_amplifier", "jukebox_playable", "provides_banner_patterns",
        "recipes", "lodestone_tracker", "firework_explosion", "fireworks", "profile", "note_block_sound",
        "banner_patterns", "base_color", "pot_decorations", "container", "block_state", "bees", "lock",
        "container_loot", "break_sound", "villager/variant", "wolf/variant", "wolf/sound_variant",
        "wolf/collar", "fox/variant", "salmon/size", "parrot/variant", "tropical_fish/pattern",
        "tropical_fish/base_color", "tropical_fish/pattern_color", "mooshroom/variant", "rabbit/variant",
        "pig/variant", "pig/sound_variant", "cow/variant", "cow/sound_variant", "chicken/variant",
        "chicken/sound_variant", "zombie_nautilus/variant", "frog/variant", "horse/variant",
        "painting/variant", "llama/variant", "axolotl/variant", "cat/variant", "cat/sound_variant",
        "cat/collar", "sheep/color", "shulker/color");

    /** The registered components vanilla 26.1 gives no codec, by path under {@code minecraft:}. */
    private static final @NotNull ConcurrentSet<String> WITHOUT_CODEC = Concurrent.newUnmodifiableSet(
        "creative_slot_lock", "additional_trade_cost", "map_post_processing");

    /** The component predicate types vanilla 26.1 registers, by qualified id. */
    private static final @NotNull ConcurrentSet<String> PREDICATE_TYPES = Concurrent.newUnmodifiableSet(
        "minecraft:damage", "minecraft:enchantments", "minecraft:stored_enchantments",
        "minecraft:potion_contents", CUSTOM_DATA, "minecraft:container", "minecraft:bundle_contents",
        "minecraft:firework_explosion", "minecraft:fireworks", "minecraft:writable_book_content",
        "minecraft:written_book_content", "minecraft:attribute_modifiers", "minecraft:trim",
        "minecraft:jukebox_playable", "minecraft:villager/variant");

    /**
     * Whether vanilla 26.1 registers a data component at a path.
     *
     * @param path the component's path under {@code minecraft:} (e.g. {@code dyed_color})
     * @return whether the path names a registered component
     */
    public static boolean isRegistered(@NotNull String path) {
        return REGISTERED.contains(path);
    }

    /**
     * Whether a data component is registered with a codec, which is what decodes a value of it - the
     * three vanilla 26.1 registers without one carry none, and neither does a path that names no
     * registered component.
     *
     * @param path the component's path under {@code minecraft:} (e.g. {@code dyed_color})
     * @return whether the path names a registered component that has a codec
     */
    public static boolean hasCodec(@NotNull String path) {
        return isRegistered(path) && !WITHOUT_CODEC.contains(path);
    }

    /**
     * Whether vanilla 26.1 registers a component predicate type under an id.
     *
     * @param id the predicate type's qualified id (e.g. {@code minecraft:damage})
     * @return whether the id names a registered predicate type, {@link #CUSTOM_DATA} included
     */
    public static boolean isPredicateType(@NotNull String id) {
        return PREDICATE_TYPES.contains(id);
    }

}
