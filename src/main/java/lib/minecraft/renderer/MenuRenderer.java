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
import java.util.Optional;
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
     * The prefix a caller's format codes carry, which is the client's own rather than the ampersand a
     * chat plugin uses.
     */
    private static final char SECTION_SIGN = '§';

    /**
     * The side of the content a cell holds, in Minecraft pixels. Every cell holds the same amount of
     * it however big the cell is - a crafting result is a cell of 26 around an item of 16, which is
     * why this is a constant and the inset that centres it is not.
     */
    private static final int CONTENT_MCPX = 16;

    /**
     * The side of the content a cell holds, in output pixels.
     */
    static final int CONTENT_PX = CONTENT_MCPX * PX_SCALE;

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
     * The chrome layer of a menu, which carries the panel and its cells and no ink at all - a label is
     * a layer of its own, so nothing a menu draws later can overpaint chrome that was drawn earlier
     * into one buffer.
     *
     * @param options the menu render options
     * @param layout the laid-out panel
     * @return the chrome placement at the panel's origin
     */
    static @NotNull FramePlacement chromeOf(@NotNull MenuOptions options, @NotNull MenuLayout layout) {
        return new FramePlacement(0, 0, StaticImageData.of(paintChrome(options, layout).toBufferedImage()));
    }

    /**
     * Places a menu's labels over its chrome, returning whether they animated. Nothing is appended
     * where the menu has no label to draw.
     *
     * @param options the menu render options
     * @param layout the laid-out panel
     * @param stack the layer stack to append to
     * @return whether the label layer animates
     */
    static boolean placeLabels(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack
    ) {
        Optional<ImageData> labels = labelLayer(options, layout);
        if (labels.isEmpty()) return false;

        place(stack, MenuSlot.TEXT, new FramePlacement(0, 0, labels.get()));
        return labels.get().isAnimated();
    }

    /**
     * Places content in a cell, centred - one Minecraft pixel in on an ordinary cell of 18 and five
     * in on a crafting result of 26.
     *
     * @param cell the cell to place in
     * @param content what to draw in it
     * @return the placement, in output pixels
     */
    static @NotNull FramePlacement inCell(@NotNull MenuLayout.Cell cell, @NotNull ImageData content) {
        int inset = (cell.size() - CONTENT_MCPX) / 2;
        return new FramePlacement((cell.x() + inset) * PX_SCALE, (cell.y() + inset) * PX_SCALE, content);
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
            ImageData rendered = itemRenderer.render(intoSlot(entry.getValue().options()));
            if (rendered.isAnimated()) anyAnimated = true;

            place(stack, MenuSlot.SLOT, inCell(cell, rendered));
        }

        return anyAnimated;
    }

    /**
     * Returns the caller's item options at the size a slot holds.
     * <p>
     * A slot is a fixed size and the item in it is drawn to fit, which is why the size is the
     * renderer's answer rather than the caller's: an item asked for at its own default carries a
     * canvas of its own, and nothing about that canvas knows what a slot is. Everything else the
     * caller asked for survives.
     *
     * @param options the caller's item options
     * @return the options at a slot's content size
     */
    static @NotNull ItemOptions intoSlot(@NotNull ItemOptions options) {
        return options.mutate()
            .output(options.getOutput().mutate().canvasSize(CONTENT_PX).build())
            .build();
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
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(CONTENT_PX).build())
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
    // Labels.
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the label layer for a menu - the container's own title, and the player's label where the
     * player section is drawn. When a label carries an obfuscated segment ({@code §k}) the layer is
     * animated over one second of unique frames; otherwise it is a single frame.
     * <p>
     * Empty where a menu has no label to draw at all, which is what keeps a caller who asked for no
     * title from paying for a whole transparent canvas per frame.
     *
     * @param options the menu options, supplying the labels, their colour and the frame rate
     * @param layout the laid-out panel, supplying both anchors
     * @return the label layer, empty where there is nothing to draw
     */
    static @NotNull Optional<ImageData> labelLayer(@NotNull MenuOptions options, @NotNull MenuLayout layout) {
        ConcurrentList<Label> labels = Concurrent.newList();

        if (!options.getTitle().isEmpty())
            labels.add(new Label(parse(options.getTitle()), layout.titleAnchor()));

        if (!options.getInventoryTitle().isEmpty())
            layout.inventoryAnchor().ifPresent(anchor ->
                labels.add(new Label(parse(options.getInventoryTitle()), anchor)));

        if (labels.isEmpty()) return Optional.empty();

        int w = layout.width() * PX_SCALE;
        int h = layout.height() * PX_SCALE;
        int argb = options.getDefaultTitleArgb();
        boolean animated = labels.stream().anyMatch(label -> isObfuscated(label.line()));

        if (!animated) {
            PixelBuffer buffer = PixelBuffer.create(w, h);
            drawLabels(buffer, labels, argb, 0);
            return Optional.of(StaticImageData.of(buffer.toBufferedImage()));
        }

        // One second of unique obfuscation frames: a wall-rate loop of framesPerSecond frames, the
        // frame index doubling as the per-frame obfuscation seed.
        return Optional.of(new Timeline.FpsLoop(options.getFramesPerSecond(), options.getFramesPerSecond())
            .wrap(f -> {
                PixelBuffer frameBuffer = PixelBuffer.create(w, h);
                drawLabels(frameBuffer, labels, argb, f);
                return frameBuffer;
            }));
    }

    /**
     * One label and where it starts.
     *
     * @param line the parsed text
     * @param anchor the panel-relative origin of its glyph cells
     */
    private record Label(@NotNull LineSegment line, @NotNull MenuLayout.Anchor anchor) {}

    /**
     * Parses caller text on the section sign, which is the prefix the client's own format codes carry.
     * An ampersand is a character a title may hold and is left as one.
     */
    private static @NotNull LineSegment parse(@NotNull String text) {
        return ColorSegment.fromLegacy(text, SECTION_SIGN);
    }

    /**
     * Returns whether any segment of a line is obfuscated ({@code §k}), which is what makes the label
     * layer animate.
     */
    private static boolean isObfuscated(@NotNull LineSegment line) {
        for (ColorSegment segment : line.getSegments())
            if (segment.isObfuscated()) return true;

        return false;
    }

    /**
     * Draws every label at its anchor, without a drop shadow - the client's own label draws decline
     * one.
     * <p>
     * An anchor is the top of a glyph cell, which the vanilla font opens a capital on, and the draw
     * takes the baseline an ascent below it. The ascent is read in output pixels where the draw is in
     * Minecraft ones, so it is converted rather than added.
     */
    private static void drawLabels(
        @NotNull PixelBuffer buffer,
        @NotNull ConcurrentList<Label> labels,
        int defaultArgb,
        long frameSeed
    ) {
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscent() / PX_SCALE;

        for (Label label : labels)
            TextKit.drawLine(g, label.line(), label.anchor().x(), label.anchor().y() + ascentMcPx,
                defaultArgb, frameSeed, frameSeed, false);
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

        MenuLayout layout = layoutOf(options);
        LayerStack<FrameLayer> stack = new LayerStack<>();
        place(stack, MenuSlot.CHROME, chromeOf(options, layout));

        boolean anyAnimated = placeSlots(options, layout, stack, new ItemRenderer(context), index -> index);
        anyAnimated |= placeLabels(options, layout, stack);

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

            MenuLayout layout = layoutOf(options);
            PixelBuffer chrome = paintChrome(options, layout);
            drawCraftArrowInCell(chrome, layout.slotCells().get(ARROW_SLOT), options.getTheme().palette().shadow());

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            LayerStack<FrameLayer> stack = new LayerStack<>();
            place(stack, MenuSlot.CHROME, new FramePlacement(0, 0, StaticImageData.of(chrome.toBufferedImage())));

            ConcurrentSet<Integer> claimed = Concurrent.newSet();
            for (int chestSlot : SLOT_MAP) claimed.add(chestSlot);
            claimed.add(ARROW_SLOT);

            boolean anyAnimated = placeSlots(options, layout, stack, itemRenderer, callerSlot -> SLOT_MAP[callerSlot]);
            anyAnimated |= appendFillerLayers(options, layout, stack, itemRenderer, claimed);
            anyAnimated |= placeLabels(options, layout, stack);

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

            MenuLayout layout = layoutOf(options);
            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            BlockRenderer blockRenderer = new BlockRenderer(this.context);
            LayerStack<FrameLayer> stack = new LayerStack<>();
            place(stack, MenuSlot.CHROME, chromeOf(options, layout));

            ConcurrentSet<Integer> claimed = Concurrent.newSet();
            for (int chestSlot : SLOT_MAP) claimed.add(chestSlot);
            claimed.add(DECORATION_SLOT);
            for (int chestSlot : RED_PANE_SLOTS) claimed.add(chestSlot);

            ConcurrentList<MenuLayout.Cell> cells = layout.slotCells();
            boolean anyAnimated = placeSlots(options, layout, stack, itemRenderer, callerSlot -> SLOT_MAP[callerSlot]);

            MenuLayout.Cell decorationCell = cells.get(DECORATION_SLOT);
            BlockOptions decorationOptions = BlockOptions.builder()
                .blockId("minecraft:anvil")
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .output(OutputOptions.builder().canvasSize(CONTENT_PX).antiAlias(false).build())
                .build();
            ImageData decoration = blockRenderer.render(decorationOptions);
            if (decoration.isAnimated()) anyAnimated = true;
            place(stack, MenuSlot.CONTENT, inCell(decorationCell, decoration));

            ItemOptions redPaneOptions = ItemOptions.builder()
                .itemId("minecraft:red_stained_glass_pane")
                .type(ItemOptions.Type.GUI_ICON)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(CONTENT_PX).build())
                .build();
            ImageData redPane = itemRenderer.render(redPaneOptions);
            if (redPane.isAnimated()) anyAnimated = true;
            for (int chestSlot : RED_PANE_SLOTS)
                place(stack, MenuSlot.CONTENT, inCell(cells.get(chestSlot), redPane));

            anyAnimated |= appendFillerLayers(options, layout, stack, itemRenderer, claimed);
            anyAnimated |= placeLabels(options, layout, stack);
            return composite(layout, stack, anyAnimated, options);
        }

    }

}
