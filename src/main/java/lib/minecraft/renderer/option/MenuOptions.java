package lib.minecraft.renderer.option;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.compose.Window;
import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.option.slot.MenuSlot;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * Configures a single {@link MenuRenderer} invocation.
 *
 * <p>Supports vanilla menu types ({@link Type#PLAYER}, {@link Type#CHEST},
 * {@link Type#VANILLA_CRAFTING}, {@link Type#VANILLA_ANVIL}), Hypixel SkyBlock menu types
 * ({@link Type#SKYBLOCK_CRAFTING}, {@link Type#SKYBLOCK_ANVIL}), plus two caller-driven layouts:
 * <ul>
 *   <li><b>{@link Type#CUSTOM}</b> - a {@code rows x columns} grid with arbitrary dimensions, on a
 *       panel the window paints to fit it, and per-slot item icons drawn through
 *       {@link ItemRenderer}.</li>
 *   <li><b>{@link Type#SLOT}</b> - a single inventory slot, useful for previewing one item in
 *       the menu chrome.</li>
 * </ul>
 *
 * <p><b>Slot population.</b> {@link #getSlots() slots} maps zero-based slot indices to
 * {@link MenuSlotContent} (item id + {@link ItemOptions} + stack count), which the renderer
 * dispatches to {@link ItemRenderer} per slot. Unmapped slots stay transparent.
 *
 * @see lib.minecraft.renderer.MenuRenderer
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class MenuOptions implements RenderOptions {

    /**
     * Menu layout type - selects the chrome and slot geometry.
     */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.CHEST;

    /**
     * Number of slot rows - honoured by {@link Type#CHEST} and {@link Type#CUSTOM}; other types
     * use their own fixed grids.
     */
    @lombok.Builder.Default
    private final int rows = 3;

    /**
     * Number of slot columns - honoured by {@link Type#CUSTOM}; other types use their own fixed
     * grids.
     */
    @lombok.Builder.Default
    private final int columns = 9;

    /**
     * Zero-based slot index to content mapping; unmapped slots render transparent.
     */
    @lombok.Builder.Default
    private final @NotNull ConcurrentMap<Integer, MenuSlotContent> slots = Concurrent.newMap();

    /**
     * Menu title text rendered in the header; empty suppresses the title bar text.
     */
    @lombok.Builder.Default
    private final @NotNull String title = "";

    /**
     * Label drawn above the player's own cells, and only where {@link #playerInventory} draws them.
     * Empty suppresses it.
     */
    @lombok.Builder.Default
    private final @NotNull String inventoryTitle = "Inventory";

    /**
     * ARGB both labels are drawn in where a segment names no colour of its own. The default is what
     * vanilla draws a container's labels in.
     */
    @lombok.Builder.Default
    private final int defaultTitleArgb = 0xFF404040;

    /**
     * Whether the player's own inventory and hotbar are drawn below the container's cells. A caller
     * gets the container section alone and asks for the band.
     */
    @lombok.Builder.Default
    private final boolean playerInventory = false;

    /**
     * The window the chrome is painted by, which is vanilla's geometry in one of its palettes.
     */
    @lombok.Builder.Default
    private final @NotNull Window.Theme theme = Window.Theme.VANILLA;

    /**
     * How non-functional slots should be rendered in menu layouts that wrap their functional
     * slots in a larger container ({@link Type#SKYBLOCK_CRAFTING} at the moment). Ignored by
     * layouts that do not have filler slots.
     */
    @lombok.Builder.Default
    private final @NotNull Fill fill = Fill.EMPTY;

    /**
     * Target frame rate for animated output when any slot contains an animated item.
     */
    @lombok.Builder.Default
    private final int framesPerSecond = 30;

    /**
     * Transform applied to the default menu {@link LayerStack} of {@link FrameLayer}s before it runs,
     * letting callers splice custom layers relative to the built-in {@link MenuSlot}s (chrome, item slots,
     * content, text). Defaults to {@linkplain UnaryOperator#identity() identity}.
     */
    @lombok.Builder.Default
    private final @NotNull UnaryOperator<LayerStack<FrameLayer>> layerDecorator = UnaryOperator.identity();

    /**
     * A builder pre-populated with this instance's field values, for deriving a variant.
     *
     * @return the seeded builder
     */
    public @NotNull MenuOptionsBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * The default menu options - an empty single {@linkplain Type#CHEST chest} (3 rows x 9 columns)
     * on the {@linkplain Window.Theme#VANILLA vanilla} theme at 30 fps, with no player section.
     *
     * @return the default options
     */
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
                .type(ItemOptions.Type.GUI_ICON)
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
                .type(ItemOptions.Type.GUI_ICON)
                .build();
            return new MenuSlotContent(itemId, options, count);
        }

    }

    /**
     * The supported menu types.
     */
    public enum Type {

        /**
         * The 4x9 player inventory view (9 hotbar + 27 main).
         */
        PLAYER,

        /**
         * A chest with configurable {@code rows} (3 for single, 6 for double).
         */
        CHEST,

        /**
         * A custom rows x columns grid with no hard-coded dimensions.
         */
        CUSTOM,

        /**
         * A single 1x1 slot.
         */
        SLOT,

        /**
         * The vanilla crafting table: a three by three input grid whose columns sit left of centre,
         * and a result cell of 26 across from it. Caller slots {@code 0..8} map to the grid in
         * reading order and slot {@code 9} is the result.
         */
        VANILLA_CRAFTING,

        /**
         * The Hypixel SkyBlock crafting menu: a 9x6 chest container that wraps the 3x3 input
         * grid at chest positions {@code 10-12/19-21/28-30} with the output at chest slot
         * {@code 23} and decorative filler around the functional slots. Caller slots
         * {@code 0..8} map to the grid in reading order and slot {@code 9} is the output.
         */
        SKYBLOCK_CRAFTING,

        /**
         * The vanilla 2-input 1-output anvil.
         */
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

    /**
     * How non-functional (filler/border) slots should be rendered in menu layouts that have
     * them, such as {@link Type#SKYBLOCK_CRAFTING} which wraps the 3x3 crafting grid inside a
     * 9x6 chest container.
     */
    public enum Fill {

        /**
         * Fill every non-functional slot with a {@code minecraft:black_stained_glass_pane} GUI
         * icon, matching the standard Hypixel menu border. The item must be resolvable through
         * the active {@link RendererContext}.
         */
        BLACK_STAINED_GLASS_PANE,

        /**
         * Leave non-functional slots transparent so the chrome shows through.
         */
        EMPTY

    }

}
