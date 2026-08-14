package lib.minecraft.renderer.option;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.compose.MenuScreen;
import lib.minecraft.renderer.engine.compose.Window;
import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.option.slot.MenuSlot;
import lib.minecraft.text.font.MinecraftFont;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Configures a single {@link MenuRenderer} invocation.
 *
 * <p>{@link Type} names the screen, which is one of the containers the client ships. Every one of
 * them resolves to a {@link MenuScreen} carrying the cell origins and band depths measured off that
 * screen's own art, so what a caller chooses is an identity rather than a set of numbers.
 * {@link Type#CHEST} is the one that takes dimensions, because the client composes it from one sheet
 * at any row count.
 *
 * <p><b>Slot population.</b> {@link #getSlots() slots} maps zero-based slot indices onto the cells
 * the chosen screen lays out, in that screen's own order - its own cells first and a result cell
 * last. Unmapped slots stay transparent unless {@link #getFill() fill} names something to draw in
 * them. A {@link MenuSlotContent} is either an item the renderer draws to the cell's size through
 * {@link ItemRenderer}, or a render the caller has already sized.
 *
 * @see lib.minecraft.renderer.MenuRenderer
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class MenuOptions implements RenderOptions {

    /**
     * The cell art the client ships, which is what a sliced window takes for its cells where a
     * caller names no other.
     */
    private static final @NotNull String SLOT_SPRITE = "minecraft:gui/sprites/container/slot";

    /**
     * Menu layout type - selects the chrome and slot geometry.
     */
    @lombok.Builder.Default
    private final @NotNull Type type = Type.CHEST;

    /**
     * Number of slot rows, read by {@link Type#CHEST} alone. Every other screen ships one grid and
     * carries its own.
     */
    @lombok.Builder.Default
    private final int rows = 3;

    /**
     * Number of slot columns, read by {@link Type#CHEST} alone. Nine is the chest the client
     * composes; any other width is a panel it ships no sheet for, and is laid out as a plain grid.
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
     * The window the chrome is painted by where {@link #chromeSprite} names no art, which is
     * vanilla's geometry in one of its palettes.
     */
    @lombok.Builder.Default
    private final @NotNull Window.Theme theme = Window.Theme.VANILLA;

    /**
     * The art the panel is sliced out of, empty where it is drawn from rules in the
     * {@linkplain #theme theme}'s ink. Art named here and not resolvable raises rather than falling
     * back, so a pack that ships a broken panel is distinguishable from one that ships none.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ResourceId> chromeSprite = Optional.empty();

    /**
     * The art one cell is sliced out of, read only where {@link #chromeSprite} names a panel. Empty
     * where that panel's art draws its own cells.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ResourceId> cellSprite = Optional.of(ResourceId.parse(SLOT_SPRITE));

    /**
     * Output pixels one Minecraft pixel occupies on a side. It is the font's, and a menu refuses any
     * other value: a title is rasterised through that constant, so a panel drawn at a second scale
     * would carry a label that does not line up with it.
     */
    @lombok.Builder.Default
    private final int pxScale = MinecraftFont.MC_PIXEL_SCALE;

    /**
     * What to draw in the cells a caller populated none of. Ignored where nothing is left over.
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
     * What one menu slot holds.
     * <p>
     * The two arms differ in who decides how big the content is. An {@link Item} is sized by the
     * renderer, which is the only party that knows what a cell comes to; a {@link Rendered} was
     * produced by the caller and is drawn at whatever size it arrived at, which is what lets a block
     * or any other render sit in a slot.
     */
    public sealed interface MenuSlotContent {

        /**
         * Creates a slot holding one item at its GUI icon.
         *
         * @param itemId the namespaced item id
         * @return the slot content
         */
        static @NotNull MenuSlotContent of(@NotNull String itemId) {
            return of(ItemOptions.builder()
                .itemId(itemId)
                .type(ItemOptions.Type.GUI_ICON)
                .build());
        }

        /**
         * Creates a slot holding an item drawn to the caller's own options.
         *
         * @param options the item render options
         * @return the slot content
         */
        static @NotNull MenuSlotContent of(@NotNull ItemOptions options) {
            return new Item(options);
        }

        /**
         * Creates a slot holding a render the caller produces, drawn as it arrives.
         *
         * @param content supplies the render, called once per menu render
         * @return the slot content
         */
        static @NotNull MenuSlotContent of(@NotNull Supplier<ImageData> content) {
            return new Rendered(content);
        }

        /**
         * An item the renderer draws at the size the cell holding it comes to.
         *
         * @param options the item render options, whose output canvas the renderer answers for
         */
        record Item(@NotNull ItemOptions options) implements MenuSlotContent {}

        /**
         * A render the caller has already sized, centred in its cell as it arrives.
         *
         * @param content supplies the render
         */
        record Rendered(@NotNull Supplier<ImageData> content) implements MenuSlotContent {}

    }

    /**
     * The screens a menu can be, each one the client ships.
     * <p>
     * A menu whose slots sit at positions no shipped screen declares is one of these with its own
     * slot map over it, which is a caller's arithmetic rather than a type of its own - a server menu
     * is a chest whatever it draws in the cells.
     */
    public enum Type {

        /**
         * The player's own inventory, four rows of nine - three of main inventory and the hotbar.
         */
        PLAYER,

        /**
         * A chest, at the {@link MenuOptions#getRows() rows} and {@link MenuOptions#getColumns()
         * columns} asked for. Nine columns is the panel the client composes out of one sheet at any
         * row count; any other width is a plain grid, there being no sheet to compose it from.
         */
        CHEST,

        /**
         * A shulker box, three rows of nine, whose label band is one pixel short of a chest's.
         */
        SHULKER_BOX,

        /**
         * A hopper, one row of five centred cells sitting two pixels lower than a chest's first row.
         */
        HOPPER,

        /**
         * A dispenser, a centred three by three, and the one shipped screen that centres its title.
         */
        DISPENSER,

        /**
         * A crafting table: a three by three whose columns sit four pixels left of centre, and a
         * result cell of 26 across from it. Slots {@code 0..8} are the grid in reading order and
         * slot {@code 9} is the result.
         */
        CRAFTING_TABLE,

        /**
         * An anvil, two inputs and a result at the spacing its own menu declares. Slots {@code 0}
         * and {@code 1} are the inputs and slot {@code 2} is the result.
         */
        ANVIL

    }

    /**
     * What to draw in the cells a caller populated none of.
     * <p>
     * A filler is an item like any other, so this names one rather than offering a choice between
     * the ones a server menu happens to reach for. The item must be resolvable through the active
     * {@link RendererContext}, and it is drawn to the cell's size the way a populated slot's is.
     *
     * @param itemId the item drawn in each of those cells, empty to leave them transparent so the
     *     chrome shows through
     */
    public record Fill(@NotNull Optional<ResourceId> itemId) {

        /**
         * Leaves every cell the caller populated none of transparent.
         */
        public static final @NotNull Fill EMPTY = new Fill(Optional.empty());

        /**
         * Creates a fill drawing one item in each of those cells.
         *
         * @param itemId the namespaced item id
         * @return the fill
         */
        public static @NotNull Fill of(@NotNull String itemId) {
            return of(ResourceId.parse(itemId));
        }

        /**
         * Creates a fill drawing one item in each of those cells.
         *
         * @param itemId the item id
         * @return the fill
         */
        public static @NotNull Fill of(@NotNull ResourceId itemId) {
            return new Fill(Optional.of(itemId));
        }

    }

}
