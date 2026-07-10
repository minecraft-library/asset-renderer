package lib.minecraft.renderer.tooling2.bridge;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The legacy key-choreography contracts, quarantined in one class (10-bridge SS4.1, decision 27):
 * the tooling2 flows emit v2's own declared orders (SPINE SS4); only the bridge replays legacy's,
 * and all of that replay knowledge lives here so it dies with the bridge.
 *
 * <p>The canonical-SHA proof is order-sensitive by design (10-bridge SS3.2) - Gson preserves member
 * insertion order through parse-&gt;re-serialise, so reconstructing legacy bytes means replaying not
 * just every value but every KEY SEQUENCE. Where an order is a plain sort or falls out of the v2
 * file's own order, no contract is needed; the members below capture the orders legacy pinned by
 * choreography rather than by rule.
 */
final class LegacyOrder {

    /**
     * The legacy {@code block_models} entry key order - the impure merge's only surviving fact
     * (legacy re-read prior output to preserve order, {@code buildMergedOutput}), NOT derivable
     * from any walk (10-bridge SS4.1 / SS5.3). Transcribed from the checked-in resource; the v2
     * superset entries ({@code enchanting_table}, {@code lectern}) are absent, so this list also
     * filters the v2 catalog down to the legacy 22.
     */
    /**
     * The legacy flat-row key order (the {@code EntityRowField} contributor-enum declaration order):
     * the sequence the base row's members are emitted in before {@code EntityFamilyJsonWriter.group}
     * regroups them into the family form.
     */
    static final @NotNull List<String> ENTITY_FLAT_ROW_KEYS = List.of(
        "geometry_ref", "texture_ref", "armor_type", "renderer_scale", "overlays", "block_overlays",
        "setup_yaw_addend", "base_tint", "hidden_bones", "bone_toggles");

    /**
     * The family-level carried-field order ({@code EntityFamilyJsonWriter.copyBaseOptionalFields}):
     * the fields that ride the family level after {@code geometry_ref}/{@code armor_type}, before
     * {@code family_of}/{@code axes}/{@code layers}.
     */
    static final @NotNull List<String> FAMILY_CARRIED_ORDER = List.of(
        "renderer_scale", "setup_yaw_addend", "base_tint", "hidden_bones", "bone_toggles",
        "overlays", "block_overlays");

    /** The legacy overlay-row key order ({@code EntityRuntimeJsonWriter} OVERLAYS contributor). */
    static final @NotNull List<String> OVERLAY_ROW_KEYS = List.of(
        "geometry_ref", "texture_ref", "emissive", "tint_color", "tint_by", "texture_by",
        "shearable", "requires_tint", "requires_charged", "inflate", "skip_bounds", "blend", "alpha", "retain_bones");

    /** The legacy block-overlay row key order. */
    static final @NotNull List<String> BLOCK_OVERLAY_ROW_KEYS = List.of(
        "block_id", "attached_bone", "selectable", "transforms");

    static final @NotNull List<String> BLOCK_MODELS_KEYS = List.of(
        "minecraft:shulker_box",
        "minecraft:chest",
        "minecraft:bed_head",
        "minecraft:sign",
        "minecraft:wall_sign",
        "minecraft:hanging_sign",
        "minecraft:hanging_sign_attached",
        "minecraft:wall_hanging_sign",
        "minecraft:conduit",
        "minecraft:bell_body",
        "minecraft:decorated_pot",
        "minecraft:copper_golem_statue",
        "minecraft:skull_head",
        "minecraft:skull_dragon_head",
        "minecraft:skull_humanoid_head",
        "minecraft:skull_piglin_head",
        "minecraft:banner",
        "minecraft:wall_banner",
        "minecraft:bed_foot",
        "minecraft:decorated_pot_sides",
        "minecraft:banner_flag",
        "minecraft:wall_banner_flag");

    private LegacyOrder() {
    }

}
