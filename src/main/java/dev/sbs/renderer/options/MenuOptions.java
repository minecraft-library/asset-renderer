package dev.sbs.renderer.options;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFormat;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Configures a single {@code MenuRenderer} invocation. Supports vanilla menu types plus a custom
 * grid mode with caller-controlled row and column dimensions.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class MenuOptions {

    /** Menu layout type */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.CHEST;

    /** Number of slot rows */
    @lombok.Builder.Default
    private final int rows = 3;

    /** Number of slot columns */
    @lombok.Builder.Default
    private final int columns = 9;

    /** Slot index to content mapping */
    @lombok.Builder.Default
    private final @NotNull ConcurrentMap<Integer, MenuSlotContent> slots = Concurrent.newMap();

    /** Menu title text rendered in the header */
    @lombok.Builder.Default
    private final @NotNull String title = "";

    /**
     * Text rendered inside the rename textbox on vanilla anvil menus. Ignored by every other
     * menu type. Supports plain text only (no legacy format codes) - drawn in white on the
     * beige textbox interior.
     */
    @lombok.Builder.Default
    private final @NotNull String textboxLabel = "";

    /**
     * XP level cost displayed below the slot row on vanilla anvil menus as
     * {@code "Enchantment Cost: X"} in green. A value of zero or less suppresses the label
     * entirely. Ignored by every other menu type.
     */
    @lombok.Builder.Default
    private final int xpCost = 0;

    /** Visual theme applied to the menu chrome */
    @lombok.Builder.Default
    private final @NotNull Theme theme = Theme.VANILLA;

    /**
     * How non-functional slots should be rendered in menu layouts that wrap their functional
     * slots in a larger container ({@link Type#SKYBLOCK_CRAFTING} at the moment). Ignored by
     * layouts that do not have filler slots.
     */
    @lombok.Builder.Default
    private final @NotNull Fill fill = Fill.EMPTY;

    /** Target frame rate for animated output when any slot contains an animated item. */
    @lombok.Builder.Default
    private final int framesPerSecond = 30;

    /** Output image format */
    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    public @NotNull MenuOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull MenuOptions defaults() {
        return builder().build();
    }

    /**
     * The content of a single menu slot: the item to render plus its options and stack count.
     *
     * @param itemId the namespaced item id
     * @param options the item render options (controls GUI vs 3D, enchanted state, CIT context, etc.)
     * @param count the stack size, rendered as the corner number when greater than 1
     */
    public record MenuSlotContent(
        @NotNull String itemId,
        @NotNull ItemOptions options,
        int count
    ) {

        /**
         * Creates a slot holding a single unenchanted GUI item at stack size 1.
         *
         * @param itemId the namespaced item id
         * @return the slot content
         */
        public static @NotNull MenuSlotContent of(@NotNull String itemId) {
            ItemOptions options = ItemOptions.builder()
                .itemId(itemId)
                .type(ItemOptions.Type.GUI_2D)
                .build();
            return new MenuSlotContent(itemId, options, 1);
        }

        /**
         * Creates a slot holding the given item with a stack count.
         *
         * @param itemId the namespaced item id
         * @param count the stack size
         * @return the slot content
         */
        public static @NotNull MenuSlotContent of(@NotNull String itemId, int count) {
            ItemOptions options = ItemOptions.builder()
                .itemId(itemId)
                .type(ItemOptions.Type.GUI_2D)
                .build();
            return new MenuSlotContent(itemId, options, count);
        }

    }

    /** The supported menu types. */
    public enum Type {

        /** The 4x9 player inventory view (9 hotbar + 27 main). */
        PLAYER,

        /** A chest with configurable {@code rows} (3 for single, 6 for double). */
        CHEST,

        /** A custom rows x columns grid with no hard-coded dimensions. */
        CUSTOM,

        /** A single 1x1 slot. */
        SLOT,

        /**
         * The vanilla 3x3 crafting table layout: 9-slot input grid plus a separate output slot
         * across a craft arrow. Caller slots {@code 0..8} map to the grid in reading order and
         * slot {@code 9} is the output.
         */
        VANILLA_CRAFTING,

        /**
         * The Hypixel SkyBlock crafting menu: a 9x6 chest container that wraps the 3x3 input
         * grid at chest positions {@code 10-12/19-21/28-30} with the output at chest slot
         * {@code 23} and decorative filler around the functional slots. Caller slots
         * {@code 0..8} map to the grid in reading order and slot {@code 9} is the output.
         */
        SKYBLOCK_CRAFTING,

        /** The vanilla 2-input 1-output anvil. */
        VANILLA_ANVIL,

        /**
         * The Hypixel SkyBlock "Combine Items" anvil menu: a 9x6 chest with an isometric anvil
         * decoration, red-glass borders, and three caller-controlled slots.
         * <ul>
         * <li>Slot {@code 0} (first input) -> chest slot {@code 29}</li>
         * <li>Slot {@code 1} (second input) -> chest slot {@code 33}</li>
         * <li>Slot {@code 2} (output) -> chest slot {@code 13}</li>
         * </ul>
         * The decorative isometric anvil sits at chest slot {@code 22} and red stained glass
         * panes fill chest slots {@code 11, 12, 14, 15, 20, 24} plus the entire bottom row
         * ({@code 45..53}).
         */
        SKYBLOCK_ANVIL
    }

    /** Visual theme applied to the menu chrome. */
    public enum Theme {

        VANILLA,

        DARK,

        SKYBLOCK

    }

    /**
     * How non-functional (filler/border) slots should be rendered in menu layouts that have
     * them, such as {@link Type#SKYBLOCK_CRAFTING} which wraps the 3x3 crafting grid inside a
     * 9x6 chest container.
     */
    public enum Fill {

        /**
         * Fill every non-functional slot with a {@code minecraft:black_stained_glass_pane} GUI
         * icon, matching the standard Hypixel menu border. The item must be resolvable through
         * the active {@link dev.sbs.renderer.engine.RendererContext}.
         */
        BLACK_STAINED_GLASS_PANE,

        /** Leave non-functional slots transparent so the chrome shows through. */
        EMPTY

    }

}
