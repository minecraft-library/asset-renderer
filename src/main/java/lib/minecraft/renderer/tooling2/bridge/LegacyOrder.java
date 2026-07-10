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
