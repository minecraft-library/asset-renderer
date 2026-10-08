package lib.minecraft.renderer.vanilla;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * The dispatch properties vanilla 26.1 registers for the three dispatch nodes of an item definition -
 * the {@code condition}, {@code select} and {@code range_dispatch} properties vanilla's
 * {@code ConditionalItemModelProperties}, {@code SelectItemModelProperties} and
 * {@code RangeSelectItemModelProperties} map, by path under {@code minecraft:} - and the select
 * properties whose case values are registry identifiers.
 *
 * <p>A fixed roster rather than pack state - a pack names which property a node dispatches on, never
 * that a new property exists.
 */
@UtilityClass
@Parity(subject = {Subject.BLOCK, Subject.ENTITY, Subject.ITEM, Subject.MENU})
@Parity(claim = "asset-layer")
public class ItemModelProperties {

    /** The {@code condition} properties vanilla 26.1 registers. */
    private static final @NotNull ConcurrentSet<String> CONDITION = Concurrent.newUnmodifiableSet(
        "custom_model_data", "using_item", "broken", "damaged", "fishing_rod/cast", "has_component",
        "bundle/has_selected_item", "selected", "carried", "extended_view", "keybind_down", "view_entity",
        "component");

    /** The {@code select} properties vanilla 26.1 registers. */
    private static final @NotNull ConcurrentSet<String> SELECT = Concurrent.newUnmodifiableSet(
        "custom_model_data", "main_hand", "charge_type", "trim_material", "block_state", "display_context",
        "local_time", "context_entity_type", "context_dimension", "component");

    /** The {@code range_dispatch} properties vanilla 26.1 registers. */
    private static final @NotNull ConcurrentSet<String> RANGE = Concurrent.newUnmodifiableSet(
        "custom_model_data", "bundle/fullness", "damage", "cooldown", "time", "compass", "crossbow/pull",
        "use_cycle", "use_duration", "count");

    /** The select properties whose case values are registry identifiers, compared qualified. */
    private static final @NotNull ConcurrentSet<String> IDENTIFIER_VALUED = Concurrent.newUnmodifiableSet(
        "trim_material", "context_dimension", "context_entity_type");

    /**
     * Whether vanilla 26.1 registers a {@code condition} property at a path.
     *
     * @param path the property's path under {@code minecraft:} (e.g. {@code using_item})
     * @return whether a condition node may dispatch on the property
     */
    public static boolean isCondition(@NotNull String path) {
        return CONDITION.contains(path);
    }

    /**
     * Whether vanilla 26.1 registers a {@code select} property at a path.
     *
     * @param path the property's path under {@code minecraft:} (e.g. {@code trim_material})
     * @return whether a select node may dispatch on the property
     */
    public static boolean isSelect(@NotNull String path) {
        return SELECT.contains(path);
    }

    /**
     * Whether vanilla 26.1 registers a {@code range_dispatch} property at a path.
     *
     * @param path the property's path under {@code minecraft:} (e.g. {@code time})
     * @return whether a range dispatch node may dispatch on the property
     */
    public static boolean isRange(@NotNull String path) {
        return RANGE.contains(path);
    }

    /**
     * Whether a select property's case values are registry identifiers, which compare qualified to
     * {@code minecraft:}.
     *
     * @param path the select property's path under {@code minecraft:} (e.g. {@code trim_material})
     * @return whether the property's values are identifiers
     */
    public static boolean isIdentifierValued(@NotNull String path) {
        return IDENTIFIER_VALUED.contains(path);
    }

}
