package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.compose.FrameCompositor;
import lib.minecraft.renderer.engine.compose.FramePlacement;
import lib.minecraft.renderer.engine.compose.MenuLayout;
import lib.minecraft.renderer.engine.compose.MenuScreen;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.compose.Window;
import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.kit.TextKit;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.option.slot.MenuSlot;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftFontMetrics;
import lib.minecraft.text.font.MinecraftGraphics;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Renders an inventory-style menu by laying its cells out as a {@link MenuScreen} and painting them
 * through a {@link Window}, then placing the caller's slot content on the cells that layout produced.
 * <p>
 * Every type resolves to a screen and every screen to a {@link MenuLayout}, so the geometry is one
 * arithmetic and the chrome is one painter. What still varies by type is which cell a caller's slot
 * index reaches and what decoration is drawn beside it, which is what the sub-renderers are:
 * <ul>
 * <li>{@link Generic}, {@link VanillaCrafting} and {@link VanillaAnvil} - a caller's slot index is
 * the layout's own, so the three share one flow.</li>
 * <li>{@link SkyblockCrafting} - a nine by six chest whose ten functional slots sit at their own
 * chest positions, with a craft arrow between the grid and the output.</li>
 * <li>{@link SkyblockAnvil} - the same chest with three functional slots, an isometric anvil
 * decoration and a red-pane border.</li>
 * </ul>
 * A menu speaks Minecraft pixels throughout and reaches output pixels only through
 * {@link #PX_SCALE}, which is what keeps the chrome exact rather than resampled.
 */
public final class MenuRenderer implements Renderer<MenuOptions> {

    /**
     * Output pixels one Minecraft pixel occupies on a side. It is the font's, because a title drawn
     * at any other scale would not line up with the panel it sits on.
     */
    static final int PX_SCALE = MinecraftFont.MC_PIXEL_SCALE;

    /**
     * The top edge of a title, in Minecraft pixels from the panel's own.
     */
    private static final int TITLE_TOP = 6;

    /**
     * Output pixels between a cell's own edge and the content drawn in it.
     */
    private static final int CONTENT_INSET = 2;

    /**
     * Row count of the SkyBlock chest container (6 tall).
     */
    static final int SKYBLOCK_CHEST_ROWS = 6;

    /**
     * Column count of the SkyBlock chest container (9 wide).
     */
    static final int SKYBLOCK_CHEST_COLS = 9;

    /**
     * Shared renderer for the flat-grid {@code PLAYER}/{@code CHEST}/{@code CUSTOM}/{@code SLOT} types.
     */
    private final @NotNull Generic generic;

    /**
     * Renderer for the vanilla crafting-table menu.
     */
    private final @NotNull VanillaCrafting vanillaCrafting;

    /**
     * Renderer for the vanilla anvil menu.
     */
    private final @NotNull VanillaAnvil vanillaAnvil;

    /**
     * Renderer for the SkyBlock crafting menu.
     */
    private final @NotNull SkyblockCrafting skyblockCrafting;

    /**
     * Renderer for the SkyBlock "Combine Items" anvil menu.
     */
    private final @NotNull SkyblockAnvil skyblockAnvil;

    /**
     * Constructs a new {@code MenuRenderer} bound to the given renderer context, eagerly building
     * each sub-renderer so a {@link #render} call is a plain type dispatch.
     *
     * @param context the renderer context supplying pack / model / texture lookups
     */
    public MenuRenderer(@NotNull RendererContext context) {
        this.generic = new Generic(context);
        this.vanillaCrafting = new VanillaCrafting(context);
        this.vanillaAnvil = new VanillaAnvil(context);
        this.skyblockCrafting = new SkyblockCrafting(context);
        this.skyblockAnvil = new SkyblockAnvil(context);
    }

    /**
     * Validates the fill option, then dispatches to the sub-renderer keyed by
     * {@link MenuOptions#getType()}.
     *
     * @param options the menu render options
     * @return the composited menu image
     */
    @Override
    public @NotNull ImageData render(@NotNull MenuOptions options) {
        validateFill(options);

        return switch (options.getType()) {
            case VANILLA_CRAFTING -> this.vanillaCrafting.render(options);
            case VANILLA_ANVIL -> this.vanillaAnvil.render(options);
            case SKYBLOCK_CRAFTING -> this.skyblockCrafting.render(options);
            case SKYBLOCK_ANVIL -> this.skyblockAnvil.render(options);
            case PLAYER, CHEST, CUSTOM, SLOT -> this.generic.render(options);
        };
    }

    // ---------------------------------------------------------------------------------------
    // Geometry: one screen per type, one layout per screen.
    // ---------------------------------------------------------------------------------------

    /**
     * Returns the screen a menu type is laid out as. The two SkyBlock menus are a six-row chest with
     * their own slot maps over it, which is what a Hypixel menu is.
     *
     * @param options the menu render options
     * @return the screen
     */
    static @NotNull MenuScreen screenOf(@NotNull MenuOptions options) {
        return switch (options.getType()) {
            case PLAYER -> MenuScreen.grid(4, MenuScreen.COLUMNS);
            case CHEST -> MenuScreen.chest(options.getRows());
            case CUSTOM -> MenuScreen.grid(options.getRows(), options.getColumns());
            case SLOT -> MenuScreen.grid(1, 1);
            case VANILLA_CRAFTING -> MenuScreen.craftingTable();
            case VANILLA_ANVIL -> MenuScreen.anvil();
            case SKYBLOCK_CRAFTING, SKYBLOCK_ANVIL -> MenuScreen.chest(SKYBLOCK_CHEST_ROWS);
        };
    }

    /**
     * Lays a menu out, drawing the player's section only where the caller asked for it.
     *
     * @param options the menu render options
     * @return the layout
     */
    static @NotNull MenuLayout layoutOf(@NotNull MenuOptions options) {
        return screenOf(options).layout(options.isPlayerInventory());
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers reachable from every sub-renderer.
    // ---------------------------------------------------------------------------------------

    /**
     * Appends a single {@link FramePlacement} to {@code stack} under {@code slot}, wrapped as a
     * {@link FrameLayer}. Shared by every menu sub-renderer so a placement lands in its slot without
     * repeating the {@code sink -> sink.add(...)} wrapper at each call site.
     */
    private static void place(@NotNull LayerStack<FrameLayer> stack, @NotNull MenuSlot slot, @NotNull FramePlacement placement) {
        stack.append(slot, sink -> sink.add(placement));
    }

    /**
     * Paints the panel and every cell of a layout, in Minecraft pixels replicated to output ones.
     *
     * @param options the menu render options, supplying the window
     * @param layout the laid-out panel
     * @return the painted chrome buffer
     */
    static @NotNull PixelBuffer paintChrome(@NotNull MenuOptions options, @NotNull MenuLayout layout) {
        PixelBuffer chrome = PixelBuffer.create(layout.width() * PX_SCALE, layout.height() * PX_SCALE);
        Window window = options.getTheme();

        window.paintPanel(chrome, layout.box(PX_SCALE));
        for (MenuLayout.Cell cell : layout.cells())
            window.paintCell(chrome, cell.box(PX_SCALE));

        return chrome;
    }

    /**
     * Returns the output-pixel origin of a cell's content, one content inset in from its own corner.
     */
    static @NotNull FramePlacement inCell(@NotNull MenuLayout.Cell cell, @NotNull ImageData content) {
        return new FramePlacement(
            cell.x() * PX_SCALE + CONTENT_INSET, cell.y() * PX_SCALE + CONTENT_INSET, content);
    }

    /**
     * Returns the output-pixel side of the content a cell holds, which is the cell less its two
     * content insets.
     */
    static int contentSize(@NotNull MenuLayout.Cell cell) {
        return cell.size() * PX_SCALE - 2 * CONTENT_INSET;
    }

    /**
     * Renders each of the caller's populated slots onto the cell its index reaches, returning whether
     * any of them animated.
     *
     * @param options the menu render options
     * @param layout the laid-out panel
     * @param stack the layer stack to append to
     * @param itemRenderer the renderer each slot's content goes through
     * @param cellOf maps a caller's slot index onto an index into the layout's addressable cells
     * @return whether any slot resolved to animated content
     */
    static boolean placeSlots(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull ItemRenderer itemRenderer,
        @NotNull IntUnaryOperator cellOf
    ) {
        ConcurrentList<MenuLayout.Cell> cells = layout.slotCells();
        boolean anyAnimated = false;

        for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet()) {
            MenuLayout.Cell cell = cells.get(cellOf.applyAsInt(entry.getKey()));
            ImageData rendered = itemRenderer.render(entry.getValue().options());
            if (rendered.isAnimated()) anyAnimated = true;

            place(stack, MenuSlot.SLOT, new FramePlacement(cell.x() * PX_SCALE, cell.y() * PX_SCALE, rendered));
        }

        return anyAnimated;
    }

    /**
     * Appends filler layers to every non-claimed cell according to {@link MenuOptions#getFill()
     * options.fill}. Returns whether any of the filler layers resolved to animated content so the
     * caller can keep its {@code anyAnimated} flag accurate.
     */
    static boolean appendFillerLayers(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull ItemRenderer itemRenderer,
        @NotNull ConcurrentSet<Integer> claimed
    ) {
        if (options.getFill() == MenuOptions.Fill.EMPTY) return false;

        ConcurrentList<MenuLayout.Cell> cells = layout.slotCells();
        ItemOptions fillerOptions = switch (options.getFill()) {
            case BLACK_STAINED_GLASS_PANE -> ItemOptions.builder()
                .itemId("minecraft:black_stained_glass_pane")
                .type(ItemOptions.Type.GUI_ICON)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(contentSize(cells.getFirst())).build())
                .build();
            case EMPTY -> throw new RenderException("EMPTY handled above");
        };
        ImageData fillerImage = itemRenderer.render(fillerOptions);

        for (int chestSlot = 0; chestSlot < cells.size(); chestSlot++) {
            if (claimed.contains(chestSlot)) continue;
            place(stack, MenuSlot.CONTENT, inCell(cells.get(chestSlot), fillerImage));
        }

        return fillerImage.isAnimated();
    }

    /**
     * Final composite step shared by every render path. Fast-paths a single-frame static
     * composite when nothing is animated; otherwise promotes everything to animated output via
     * {@link FrameCompositor#merge}.
     */
    static @NotNull ImageData composite(
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        boolean anyAnimated,
        @NotNull MenuOptions options
    ) {
        int canvasW = layout.width() * PX_SCALE;
        int canvasH = layout.height() * PX_SCALE;
        ConcurrentList<FramePlacement> placements = Concurrent.newList();
        Layers.foldInto(stack, options.getLayerDecorator(), placements);

        if (!anyAnimated) {
            PixelBuffer buffer = PixelBuffer.create(canvasW, canvasH);

            for (FramePlacement placement : placements)
                buffer.blit(placement.source().toPixelBuffer(), placement.x(), placement.y());

            return Timeline.still(buffer);
        }

        return FrameCompositor.merge(placements, canvasW, canvasH, options.getFramesPerSecond(), Background.TRANSPARENT);
    }

    /**
     * Rejects non-{@code EMPTY} {@link MenuOptions#getFill() fill} on menu types that do not
     * support decorative fillers. Only the SkyBlock menu types ({@code SKYBLOCK_CRAFTING},
     * {@code SKYBLOCK_ANVIL}) wrap their functional slots in a larger container with filler
     * slots; every other type is rejected with a clear message so operator misconfiguration is
     * caught at render time rather than producing a blank menu.
     */
    static void validateFill(@NotNull MenuOptions options) {
        if (options.getFill() == MenuOptions.Fill.EMPTY) return;
        if (isSkyblockType(options.getType())) return;

        throw new RenderException(
            "Fill option '%s' is only supported for SKYBLOCK menu types; got '%s'",
            options.getFill(), options.getType()
        );
    }

    private static boolean isSkyblockType(@NotNull MenuOptions.Type type) {
        return type == MenuOptions.Type.SKYBLOCK_CRAFTING
            || type == MenuOptions.Type.SKYBLOCK_ANVIL;
    }

    /**
     * Validates that every caller-supplied slot index sits within the legal range for the
     * menu type. Each sub-renderer calls this at the top of its {@code render} so out-of-range
     * slots fail fast with a descriptive {@link RenderException} rather than producing
     * a silently clipped output.
     */
    static void validateSlots(@NotNull MenuOptions options) {
        int maxSlot = switch (options.getType()) {
            case PLAYER -> 35;
            case CHEST -> options.getRows() * 9 - 1;
            case CUSTOM -> options.getRows() * options.getColumns() - 1;
            case SLOT -> 0;
            case VANILLA_CRAFTING, SKYBLOCK_CRAFTING -> 9;
            case VANILLA_ANVIL -> 2;
            case SKYBLOCK_ANVIL -> 2;
        };

        for (Integer slot : options.getSlots().keySet()) {
            if (slot < 0 || slot > maxSlot)
                throw new RenderException("Slot '%d' is out of range for menu type '%s' (max '%d')", slot, options.getType(), maxSlot);
        }
    }

    /**
     * Draws a craft arrow centred in a cell, at a third of the cell's height and two thirds of its
     * width. Nothing vanilla ships carries one - a container that has an arrow has it baked into its
     * own art - so this serves the SkyBlock menus, which have no art to take it from.
     *
     * @param chrome the chrome buffer to draw onto
     * @param cell the cell to centre the arrow in
     * @param argb the arrow colour
     */
    static void drawCraftArrowInCell(@NotNull PixelBuffer chrome, @NotNull MenuLayout.Cell cell, int argb) {
        int side = cell.size() * PX_SCALE;
        int padX = side / 6;
        int padY = side / 3;
        drawCraftArrow(chrome,
            cell.x() * PX_SCALE + padX, cell.y() * PX_SCALE + padY,
            side - 2 * padX, side - 2 * padY, argb);
    }

    /**
     * Draws a right-pointing arrow programmatically: a rectangular body on the left and a
     * triangular head tapering to a single pixel on the right. The caller supplies a bounding
     * box; the arrow fits within it.
     * <p>
     * Proportions: the body occupies roughly the left 60% of the bounding box, with a height
     * that is about one third of the total. The head occupies the remaining 40% and extends
     * vertically almost to the top and bottom of the bounding box at its base before tapering
     * linearly to the tip at the bounding box's right edge.
     *
     * @param chrome the target buffer
     * @param x the bounding box minimum X
     * @param y the bounding box minimum Y
     * @param width the bounding box width
     * @param height the bounding box height
     * @param argb the arrow colour
     */
    static void drawCraftArrow(@NotNull PixelBuffer chrome, int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) return;

        int bodyWidth = Math.max(1, width * 3 / 5);
        int bodyThickness = Math.max(2, height / 3);
        int bodyY = y + (height - bodyThickness) / 2;

        chrome.fillRect(x, bodyY, bodyWidth, bodyThickness, argb);

        int headLength = width - bodyWidth;
        if (headLength <= 0) return;

        int headBaseHalf = Math.max(1, (height - 1) / 2);
        int centerY = y + height / 2;

        for (int i = 0; i < headLength; i++) {
            int halfH = headBaseHalf * (headLength - i) / headLength;
            int hx = x + bodyWidth + i;
            int hTop = centerY - halfH;
            int hHeight = 2 * halfH + 1;
            chrome.fillRect(hx, hTop, 1, hHeight, argb);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Title drawing.
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the chrome {@link ImageData} layer for a menu. When the title contains
     * obfuscated segments ({@code §k}), the result is an animated {@link ImageData} with
     * one second of unique obfuscation frames; otherwise a single static frame is returned.
     *
     * @param baseChrome the chrome buffer with all static pixel content already drawn
     * @param options the menu options (supplies title and FPS)
     * @param titleX the title's left edge, in Minecraft pixels from the panel's own
     * @return a static or animated chrome layer
     */
    static @NotNull ImageData chromeLayer(
        @NotNull PixelBuffer baseChrome,
        @NotNull MenuOptions options,
        int titleX
    ) {
        String title = options.getTitle();
        if (title.isEmpty())
            return StaticImageData.of(baseChrome.toBufferedImage());

        LineSegment titleLine = ColorSegment.fromLegacy(title);
        boolean animated = hasTitleObfuscation(titleLine);
        int argb = options.getDefaultTitleArgb();

        if (!animated) {
            drawTitle(baseChrome, titleLine, titleX, argb, 0);
            return StaticImageData.of(baseChrome.toBufferedImage());
        }

        int w = baseChrome.width();
        int h = baseChrome.height();
        // One second of unique obfuscation frames: a wall-rate loop of framesPerSecond frames, the
        // frame index doubling as the per-frame obfuscation seed.
        return new Timeline.FpsLoop(options.getFramesPerSecond(), options.getFramesPerSecond())
            .wrap(f -> {
                PixelBuffer frameBuffer = PixelBuffer.create(w, h);
                frameBuffer.blit(baseChrome, 0, 0);
                drawTitle(frameBuffer, titleLine, titleX, argb, f);
                return frameBuffer;
            });
    }

    /**
     * Returns whether any segment of the title line is obfuscated ({@code §k}), which forces the
     * chrome layer to be rendered as an animated multi-frame result.
     */
    private static boolean hasTitleObfuscation(@NotNull LineSegment line) {
        for (ColorSegment segment : line.getSegments())
            if (segment.isObfuscated()) return true;

        return false;
    }

    /**
     * Renders pre-parsed title segments at the panel's title anchor. The anchor is a cell top and the
     * draw takes a baseline, so the font's ascent is what separates them.
     */
    private static void drawTitle(
        @NotNull PixelBuffer buffer,
        @NotNull LineSegment titleLine,
        int titleX,
        int defaultArgb,
        long frameSeed
    ) {
        MinecraftFontMetrics metrics = MinecraftFont.Vanilla.REGULAR.metrics();
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        TextKit.drawLine(g, titleLine, titleX, TITLE_TOP + metrics.getAscent(), defaultArgb, frameSeed);
    }

    // ---------------------------------------------------------------------------------------
    // Sub-renderers.
    // ---------------------------------------------------------------------------------------

    /**
     * Renders a menu whose caller slot indices are its layout's own, which is every screen that
     * carries no slot map of its own.
     */
    private static @NotNull ImageData renderDirect(@NotNull RendererContext context, @NotNull MenuOptions options) {
        validateSlots(options);

        MenuScreen screen = screenOf(options);
        MenuLayout layout = screen.layout(options.isPlayerInventory());
        ImageData chromeData = chromeLayer(paintChrome(options, layout), options, screen.titleX());

        LayerStack<FrameLayer> stack = new LayerStack<>();
        place(stack, MenuSlot.CHROME, new FramePlacement(0, 0, chromeData));

        boolean anyAnimated = chromeData.isAnimated();
        anyAnimated |= placeSlots(options, layout, stack, new ItemRenderer(context), index -> index);

        return composite(layout, stack, anyAnimated, options);
    }

    /**
     * Renderer for the grid types that name no vanilla screen of their own:
     * {@link MenuOptions.Type#PLAYER PLAYER}, {@link MenuOptions.Type#CHEST CHEST},
     * {@link MenuOptions.Type#CUSTOM CUSTOM} and {@link MenuOptions.Type#SLOT SLOT}.
     */
    @RequiredArgsConstructor
    public static final class Generic implements Renderer<MenuOptions> {

        /**
         * The renderer context, forwarded to the per-slot {@link ItemRenderer}.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            return renderDirect(this.context, options);
        }

    }

    /**
     * Renderer for the vanilla crafting table - a three by three whose columns sit left of centre,
     * with the result in a cell of 26. Caller slots {@code 0..8} are the grid in reading order and
     * slot {@code 9} is the result.
     */
    @RequiredArgsConstructor
    public static final class VanillaCrafting implements Renderer<MenuOptions> {

        /**
         * The renderer context, forwarded to the per-slot {@link ItemRenderer}.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            return renderDirect(this.context, options);
        }

    }

    /**
     * Renderer for the vanilla anvil - two inputs and a result on one row, at the spacing the anvil's
     * own menu declares. Caller slots {@code 0} and {@code 1} are the inputs and slot {@code 2} is
     * the result.
     */
    @RequiredArgsConstructor
    public static final class VanillaAnvil implements Renderer<MenuOptions> {

        /**
         * The renderer context, forwarded to the per-slot {@link ItemRenderer}.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            return renderDirect(this.context, options);
        }

    }

    /**
     * Dedicated renderer for the SkyBlock crafting menu. Validates the caller's slot input
     * (max 9), lays out a nine by six chest, draws a craft arrow between the grid and the output,
     * translates caller slots 0..9 into chest positions via {@link #SLOT_MAP}, and fills the
     * remaining 44 chest slots according to {@link MenuOptions#getFill() options.fill}.
     */
    @RequiredArgsConstructor
    public static final class SkyblockCrafting implements Renderer<MenuOptions> {

        /**
         * Maps the 10 caller slot indices (0..8 for the 3x3 crafting grid in reading order, 9
         * for the craft-result output) to their positions on the underlying 9x6 Hypixel chest.
         * The 3x3 grid sits at chest slots 10/11/12/19/20/21/28/29/30 and the output is at chest
         * slot 23, matching the standard "Craft Item" menu.
         */
        private static final int @NotNull [] SLOT_MAP = {
            10, 11, 12,
            19, 20, 21,
            28, 29, 30,
            23
        };

        /**
         * Chest slot where the craft arrow is drawn, between the grid and slot 23.
         */
        private static final int ARROW_SLOT = 22;

        /**
         * The renderer context, forwarded to the per-slot {@link ItemRenderer}.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            MenuScreen screen = screenOf(options);
            MenuLayout layout = screen.layout(options.isPlayerInventory());
            PixelBuffer chrome = paintChrome(options, layout);
            drawCraftArrowInCell(chrome, layout.slotCells().get(ARROW_SLOT), options.getTheme().palette().shadow());
            ImageData chromeData = chromeLayer(chrome, options, screen.titleX());

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            LayerStack<FrameLayer> stack = new LayerStack<>();
            place(stack, MenuSlot.CHROME, new FramePlacement(0, 0, chromeData));

            ConcurrentSet<Integer> claimed = Concurrent.newSet();
            for (int chestSlot : SLOT_MAP) claimed.add(chestSlot);
            claimed.add(ARROW_SLOT);

            boolean anyAnimated = chromeData.isAnimated();
            anyAnimated |= placeSlots(options, layout, stack, itemRenderer, callerSlot -> SLOT_MAP[callerSlot]);
            anyAnimated |= appendFillerLayers(options, layout, stack, itemRenderer, claimed);

            return composite(layout, stack, anyAnimated, options);
        }

    }

    /**
     * Dedicated renderer for the SkyBlock "Combine Items" anvil menu. Uses the same 9x6 chest
     * as SkyBlock crafting but with three caller slots (two inputs + output), a baked-in
     * isometric anvil decoration at chest slot 22, red stained glass panes along the hardcoded
     * decorative slots, and the {@link MenuOptions#getFill() fill} option applied to
     * everything else.
     */
    @RequiredArgsConstructor
    public static final class SkyblockAnvil implements Renderer<MenuOptions> {

        /**
         * Maps the 3 caller slot indices to their chest positions on the SkyBlock "Combine
         * Items" anvil: {@code 0} = first input, {@code 1} = second input, {@code 2} = output.
         */
        private static final int @NotNull [] SLOT_MAP = { 29, 33, 13 };

        /**
         * Chest slot where the decorative isometric anvil is rendered.
         */
        private static final int DECORATION_SLOT = 22;

        /**
         * Chest slots permanently filled with red stained glass panes - a decorative border
         * around the functional slots plus the entire navigation row at the bottom of the
         * chest.
         */
        private static final int @NotNull [] RED_PANE_SLOTS = {
            11, 12, 14, 15, 20, 24,
            45, 46, 47, 48, 49, 50, 51, 52, 53
        };

        /**
         * The renderer context, forwarded to the per-slot {@link ItemRenderer} and the anvil
         * decoration's {@link BlockRenderer}.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            MenuScreen screen = screenOf(options);
            MenuLayout layout = screen.layout(options.isPlayerInventory());
            ImageData chromeData = chromeLayer(paintChrome(options, layout), options, screen.titleX());

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            BlockRenderer blockRenderer = new BlockRenderer(this.context);
            LayerStack<FrameLayer> stack = new LayerStack<>();
            place(stack, MenuSlot.CHROME, new FramePlacement(0, 0, chromeData));

            ConcurrentSet<Integer> claimed = Concurrent.newSet();
            for (int chestSlot : SLOT_MAP) claimed.add(chestSlot);
            claimed.add(DECORATION_SLOT);
            for (int chestSlot : RED_PANE_SLOTS) claimed.add(chestSlot);

            ConcurrentList<MenuLayout.Cell> cells = layout.slotCells();
            boolean anyAnimated = chromeData.isAnimated();
            anyAnimated |= placeSlots(options, layout, stack, itemRenderer, callerSlot -> SLOT_MAP[callerSlot]);

            MenuLayout.Cell decorationCell = cells.get(DECORATION_SLOT);
            BlockOptions decorationOptions = BlockOptions.builder()
                .blockId("minecraft:anvil")
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .output(OutputOptions.builder().canvasSize(contentSize(decorationCell)).antiAlias(false).build())
                .build();
            ImageData decoration = blockRenderer.render(decorationOptions);
            if (decoration.isAnimated()) anyAnimated = true;
            place(stack, MenuSlot.CONTENT, inCell(decorationCell, decoration));

            ItemOptions redPaneOptions = ItemOptions.builder()
                .itemId("minecraft:red_stained_glass_pane")
                .type(ItemOptions.Type.GUI_ICON)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(contentSize(decorationCell)).build())
                .build();
            ImageData redPane = itemRenderer.render(redPaneOptions);
            if (redPane.isAnimated()) anyAnimated = true;
            for (int chestSlot : RED_PANE_SLOTS)
                place(stack, MenuSlot.CONTENT, inCell(cells.get(chestSlot), redPane));

            anyAnimated |= appendFillerLayers(options, layout, stack, itemRenderer, claimed);
            return composite(layout, stack, anyAnimated, options);
        }

    }

}
