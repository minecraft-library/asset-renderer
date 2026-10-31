package lib.minecraft.renderer;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.StaticImageData;
import dev.simplified.image.pixel.Background;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.bake.gui.MenuLayout;
import lib.minecraft.renderer.bake.gui.TextField;
import lib.minecraft.renderer.bake.gui.TextKit;
import lib.minecraft.renderer.bake.gui.Window;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.call.result.MenuResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.slot.MenuSlot;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.index.SubstitutionCollector;
import lib.minecraft.renderer.engine.frame.FrameCompositor;
import lib.minecraft.renderer.engine.frame.FrameLayer;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.renderer.vanilla.gui.ScreenMetrics;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Renders an inventory-style menu by laying its cells out as a {@link ScreenMetrics} and painting them
 * through a {@link Window}, then placing the caller's slot content on the cells that layout produced
 * and an icon on each mark whose face the window's own paint leaves bare, and answers a
 * {@link MenuResult} naming where each cell's content and each icon it placed was drawn, beside the
 * stand-ins its chrome drew.
 * <p>
 * There is one flow, because a menu is one arithmetic and one painter. What a caller chooses is which
 * screen the client ships, what goes in its cells and what paints its chrome; none of the three is a
 * render path of its own. A menu whose slots sit where no shipped screen puts them is one of those
 * screens with the caller's own slot map over it.
 * <p>
 * A menu speaks Minecraft pixels throughout and reaches output pixels at {@link #PX_SCALE}, the font's
 * scale, at which a window lays its own chrome in exact blocks and the GUI sprites it draws at
 * output-pixel resolution.
 */
@Parity(claim = "menu-closure", mode = Mode.DEMOTE, subject = Subject.MENU)
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
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
     * The renderer context resolving chrome art and each slot's item.
     */
    private final @NotNull RendererContext context;

    /**
     * Lays the menu's screen out, paints its chrome through the window the options select, and places
     * every populated cell on it. The menu draws on a renderer built over a context recording the
     * stand-ins its chrome draws; each cell's content and each icon placed on a mark is a render of its
     * own, carrying its own, and a mark whose icon the window's own paint carries is chrome whole.
     *
     * @param options the menu render options
     * @return the composited menu, the stand-ins its chrome drew, and where each cell's content and each
     *     icon placed on a mark was drawn
     */
    @Override
    public @NotNull MenuResult render(@NotNull MenuOptions options) {
        SubstitutionCollector chrome = new SubstitutionCollector();
        return new MenuRenderer(this.context.collecting(chrome)).draw(options, chrome);
    }

    /**
     * Draws the menu {@link #render} answers through this renderer's own context, which records into the
     * given collector.
     *
     * @param options the menu render options
     * @param chrome the collector this renderer's context records into, which the chrome's stand-ins
     *     reach
     * @return the composited menu, the stand-ins its chrome drew, and where each cell's content and each
     *     icon placed on a mark was drawn
     */
    @NotNull MenuResult draw(@NotNull MenuOptions options, @NotNull SubstitutionCollector chrome) {
        validateScale(options);

        ScreenMetrics screen = options.screen();
        MenuLayout layout = MenuLayout.of(screen, options.isPlayerInventory());
        Window window = windowOf(this.context, options);
        validateExtent(window, screen, layout);
        validateSlots(options, layout);

        ItemRenderer itemRenderer = new ItemRenderer(this.context);
        LayerStack<FrameLayer> stack = new LayerStack<>();
        ConcurrentList<MenuResult.Slot> slots = Concurrent.newList();
        ConcurrentList<MenuResult.Icon> icons = Concurrent.newList();
        place(stack, MenuSlot.CHROME, chromeOf(window, layout));

        placeDecorationIcons(window, layout, stack, itemRenderer, icons);
        placeSlots(options, layout, stack, itemRenderer, slots);
        appendFillerLayers(options, layout, stack, itemRenderer, slots);
        TextPalette palette = this.context.findTextPalette().orElse(TextPalette.VANILLA);
        placeLabels(options, layout, palette, stack);
        TextField.placeFieldText(options, layout, palette, stack);

        return new MenuResult(composite(layout, stack, options), chrome.snapshot(), slots, icons);
    }

    // ---------------------------------------------------------------------------------------
    // Geometry: one screen per type, one layout per screen.
    // ---------------------------------------------------------------------------------------

    /**
     * Lays a menu out, drawing the player's section only where the caller asked for it.
     *
     * @param options the menu render options
     * @return the layout
     */
    static @NotNull MenuLayout layoutOf(@NotNull MenuOptions options) {
        return MenuLayout.of(options.screen(), options.isPlayerInventory());
    }

    /**
     * Returns the window a menu's chrome is painted by - the art the caller named, composed for the
     * menu's screen and resolved for the panel it lays out, or the theme's drawn geometry where they
     * named none.
     *
     * @param context the renderer context resolving the art
     * @param options the menu render options
     * @return the window
     */
    static @NotNull Window windowOf(@NotNull RendererContext context, @NotNull MenuOptions options) {
        return options.getChromeSprite()
            .<Window>map(id -> Window.Sliced.resolve(
                context, id, options.getCellSprite(), options.screen(), layoutOf(options)))
            .orElse(Window.Theme.of(options.getThemeStyle()));
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers.
    // ---------------------------------------------------------------------------------------

    /**
     * Appends a single {@link FramePlacement} to {@code stack} under {@code slot}, wrapped as a
     * {@link FrameLayer}, so a placement lands in its slot without repeating the
     * {@code sink -> sink.add(...)} wrapper at each call site.
     */
    private static void place(@NotNull LayerStack<FrameLayer> stack, @NotNull MenuSlot slot, @NotNull FramePlacement placement) {
        stack.append(slot, sink -> sink.add(placement));
    }

    /**
     * Paints the panel, every cell and every mark of a layout at the font's scale.
     *
     * @param window the window painting the chrome
     * @param layout the laid-out panel
     * @return the painted chrome buffer
     */
    static @NotNull PixelBuffer paintChrome(@NotNull Window window, @NotNull MenuLayout layout) {
        PixelBuffer chrome = PixelBuffer.canvas(layout.width() * PX_SCALE, layout.height() * PX_SCALE);

        window.paintPanel(chrome, layout.box());
        for (ScreenMetrics.Cell cell : layout.cells())
            window.paintCell(chrome, Window.Box.of(cell));
        for (Mark.Placement mark : layout.marks())
            window.paintDecoration(chrome, Window.Box.of(mark), mark.kind());

        return chrome;
    }

    /**
     * The chrome layer of a menu, which carries the panel and its cells and no ink at all - a label is
     * a layer of its own, so nothing a menu draws later can overpaint chrome that was drawn earlier
     * into one buffer.
     *
     * @param window the window painting the chrome
     * @param layout the laid-out panel
     * @return the chrome placement at the panel's origin
     */
    static @NotNull FramePlacement chromeOf(@NotNull Window window, @NotNull MenuLayout layout) {
        return new FramePlacement(0, 0, StaticImageData.of(paintChrome(window, layout).toBufferedImage()));
    }

    /**
     * Places a menu's labels over its chrome. Nothing is appended where the menu has no label to draw.
     *
     * @param options the menu render options
     * @param layout the laid-out panel
     * @param palette the colours the stack's text shader draws in place of the labels' own
     * @param stack the layer stack to append to
     */
    static void placeLabels(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull TextPalette palette,
        @NotNull LayerStack<FrameLayer> stack
    ) {
        labelLayer(options, layout, palette)
            .ifPresent(labels -> place(stack, MenuSlot.TEXT, new FramePlacement(0, 0, labels)));
    }

    /**
     * Places content in a cell, centred - one Minecraft pixel in on an ordinary cell of 18 and five
     * in on a crafting result of 26.
     *
     * @param cell the cell to place in
     * @param content what to draw in it
     * @return the placement, in output pixels
     */
    static @NotNull FramePlacement inCell(@NotNull ScreenMetrics.Cell cell, @NotNull ImageData content) {
        int inset = (cell.size() - CONTENT_MCPX) / 2;
        return new FramePlacement((cell.x() + inset) * PX_SCALE, (cell.y() + inset) * PX_SCALE, content);
    }

    /**
     * Renders each of the caller's populated slots onto the cell its index reaches.
     *
     * @param options the menu render options
     * @param layout the laid-out panel
     * @param stack the layer stack to append to
     * @param itemRenderer the renderer an item slot's content goes through
     * @param slots the slot placements to append to
     */
    static void placeSlots(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull ItemRenderer itemRenderer,
        @NotNull ConcurrentList<MenuResult.Slot> slots
    ) {
        ConcurrentList<ScreenMetrics.Cell> cells = layout.slotCells();

        for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet()) {
            RenderResult rendered = contentOf(entry.getValue(), itemRenderer);
            ImageData image = rendered.image();

            FramePlacement placement = inCell(cells.get(entry.getKey()), image);
            place(stack, MenuSlot.SLOT, placement);
            slots.add(new MenuResult.Slot(entry.getKey(), MenuSlot.SLOT,
                placement.x(), placement.y(), image.getWidth(), image.getHeight(), rendered));
        }
    }

    /**
     * Draws what a slot holds. An item is sized here because a cell's size is the renderer's to know;
     * a render the caller produced arrives at the size they gave it.
     *
     * @param content what the slot holds
     * @param itemRenderer the renderer an item goes through
     * @return the content's result - its image and the stand-ins drawn in it
     */
    static @NotNull RenderResult contentOf(@NotNull MenuOptions.MenuSlotContent content, @NotNull ItemRenderer itemRenderer) {
        return switch (content) {
            case MenuOptions.MenuSlotContent.Item item -> itemRenderer.render(intoSlot(item.options()));
            case MenuOptions.MenuSlotContent.Rendered rendered -> rendered.content().get();
        };
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
     * Draws the icon on each of a screen's marks that the window's own paint leaves to the renderer.
     * <p>
     * Where a window paints a mark's frame alone, the frame is chrome and the icon a render, drawn by
     * different parties: the theme paints the raised button - its bevel in its own inks, and beneath the
     * item the two texels of the client's book the item leaves clear - and this places the item on its
     * face. That split is what gives a pack its say - the item resolves through the pack stack like any
     * other, so redrawing it redraws the button's book. A window sliced from art draws the client's
     * button sprite, which carries its own book, so nothing is placed over it.
     *
     * @param window the window that painted the chrome, which answers which marks' icons its paint carries
     * @param layout the laid-out panel
     * @param stack the layer stack to append to
     * @param itemRenderer the renderer an icon goes through
     * @param icons the icon placements to append to
     */
    static void placeDecorationIcons(
        @NotNull Window window,
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull ItemRenderer itemRenderer,
        @NotNull ConcurrentList<MenuResult.Icon> icons
    ) {
        ConcurrentList<Mark.Placement> marks = layout.marks();

        for (int index = 0; index < marks.size(); index++) {
            Mark.Placement mark = marks.get(index);
            Optional<ResourceId> icon = mark.icon();
            if (icon.isEmpty() || window.paintsIcon(mark.kind())) continue;

            RenderResult rendered = itemRenderer.render(ItemOptions.builder()
                .itemId(icon.get().id())
                .type(ItemOptions.Type.GUI_ICON)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(CONTENT_PX).build())
                .build());
            ImageData image = rendered.image();

            Mark.Inset inset = mark.kind().iconInset().orElseThrow();
            int x = (mark.x() + inset.x()) * PX_SCALE;
            int y = (mark.y() + inset.y()) * PX_SCALE;
            place(stack, MenuSlot.CONTENT, new FramePlacement(x, y, image));
            icons.add(new MenuResult.Icon(index, mark.kind(), x, y, image.getWidth(), image.getHeight(), rendered));
        }
    }

    /**
     * Appends filler layers to every cell the caller populated none of, according to
     * {@link MenuOptions#getFill() options.fill}.
     * <p>
     * The cells are collected before the item is, so a fill with nowhere to draw resolves nothing.
     * What it names is a whole item render, and a menu whose every cell the caller populated is the
     * case where that render is thrown away - including the missing picture a fill naming something
     * unresolvable would have reported over a menu it was never going to draw on.
     * <p>
     * Every vacant cell is placed under its own slot index and holds the one fill render's result.
     *
     * @param options the menu render options
     * @param layout the laid-out panel
     * @param stack the layer stack to append to
     * @param itemRenderer the renderer the fill goes through
     * @param slots the slot placements to append to
     */
    static void appendFillerLayers(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull ItemRenderer itemRenderer,
        @NotNull ConcurrentList<MenuResult.Slot> slots
    ) {
        Optional<ResourceId> filler = options.getFill().itemId();
        if (filler.isEmpty()) return;

        ConcurrentList<ScreenMetrics.Cell> cells = layout.slotCells();
        int[] vacant = IntStream.range(0, cells.size())
            .filter(index -> !options.getSlots().containsKey(index))
            .toArray();

        if (vacant.length == 0) return;

        ItemOptions fillerOptions = ItemOptions.builder()
            .itemId(filler.get().id())
            .type(ItemOptions.Type.GUI_ICON)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(CONTENT_PX).build())
            .build();
        RenderResult filled = itemRenderer.render(fillerOptions);
        ImageData fillerImage = filled.image();

        for (int index : vacant) {
            FramePlacement placement = inCell(cells.get(index), fillerImage);
            place(stack, MenuSlot.CONTENT, placement);
            slots.add(new MenuResult.Slot(index, MenuSlot.CONTENT,
                placement.x(), placement.y(), fillerImage.getWidth(), fillerImage.getHeight(), filled));
        }
    }

    /**
     * Final composite step: folds the layer stack into placements and merges them through
     * {@link FrameCompositor#merge}, which answers one still frame where no placement animates and
     * animated output where any does.
     * <p>
     * What animates is read off the folded placements, because the caller's
     * {@link MenuOptions#getLayerDecorator() decorator} splices during the fold, so a layer it appends
     * exists only there - after every producer has answered.
     *
     * @param layout the laid-out panel, whose size at the font's scale is the canvas
     * @param stack the layer stack every producer appended to
     * @param options the menu render options, supplying the decorator and the frame rate
     * @return the composited menu
     */
    static @NotNull ImageData composite(
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull MenuOptions options
    ) {
        ConcurrentList<FramePlacement> placements = Concurrent.newList();
        Layers.foldInto(stack, options.getLayerDecorator(), placements);
        return FrameCompositor.merge(placements, layout.width() * PX_SCALE, layout.height() * PX_SCALE,
            options.getFramesPerSecond(), Background.TRANSPARENT);
    }

    /**
     * Rejects a scale a title cannot be drawn at. {@link MinecraftGraphics} converts Minecraft pixels
     * to output ones through the compile-time {@link MinecraftFont#MC_PIXEL_SCALE}, so a panel painted
     * at any other scale would carry a label that does not line up with it. The member exists to make
     * that pin visible rather than to offer a choice.
     */
    static void validateScale(@NotNull MenuOptions options) {
        if (options.getPxScale() == PX_SCALE) return;

        throw new RenderException(
            "Menu scale '%d' is not the scale a title rasterises at (expected '%d')",
            options.getPxScale(), PX_SCALE
        );
    }

    /**
     * Rejects a panel too small to be drawn, which is the larger of two independent floors on each
     * axis - what the window's art needs to paint a frame, and what the screen needs to hold a cell.
     * <p>
     * Neither implies the other, so neither alone is the guard. Vanilla's drawn geometry closes at
     * eight Minecraft pixels square, which a chest of no rows and no columns still clears with
     * nowhere to put a cell; and a window sliced from art carries its border and every anchored
     * feature in that floor, which can want more room than a screen full of cells would.
     */
    static void validateExtent(@NotNull Window window, @NotNull ScreenMetrics screen, @NotNull MenuLayout layout) {
        Window.Extent art = window.minimum();
        Window.Extent content = MenuLayout.minimum(screen);
        int width = Math.max(art.width(), content.width());
        int height = Math.max(art.height(), content.height());

        if (layout.width() >= width && layout.height() >= height) return;

        throw new RenderException(
            "Menu panel '%dx%d' is under the '%dx%d' its window paints and its screen fills",
            layout.width(), layout.height(), width, height
        );
    }

    /**
     * Validates that every caller-supplied slot index addresses a cell the laid-out screen has, so an
     * out-of-range slot fails fast rather than producing a silently clipped output.
     */
    static void validateSlots(@NotNull MenuOptions options, @NotNull MenuLayout layout) {
        int maxSlot = layout.slotCells().size() - 1;

        for (Integer slot : options.getSlots().keySet()) {
            if (slot < 0 || slot > maxSlot)
                throw new RenderException("Slot '%d' is out of range for menu type '%s' (max '%d')", slot, options.getType(), maxSlot);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Labels.
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the label layer for a menu - the container's own title, and the player's label where the
     * player section is drawn. When a label carries an obfuscated segment ({@code §k}) the layer is
     * animated over one second of unique frames; otherwise it is a single frame. Either is held
     * premultiplied, as text laid on a clear buffer is.
     * <p>
     * Empty where a menu has no label to draw at all, which is what keeps a caller who asked for no
     * title from paying for a whole transparent canvas per frame.
     *
     * @param options the menu options, supplying the labels, their colour and the frame rate
     * @param layout the laid-out panel, supplying both anchors
     * @param palette the colours the stack's text shader draws in place of the labels' own
     * @return the label layer, empty where there is nothing to draw
     */
    static @NotNull Optional<ImageData> labelLayer(@NotNull MenuOptions options, @NotNull MenuLayout layout, @NotNull TextPalette palette) {
        ConcurrentList<Label> labels = Concurrent.newList();

        if (!options.getTitle().isEmpty()) {
            LineSegment title = parse(options.getTitle());
            labels.add(new Label(title, layout.titleAnchor(TextKit.measureLineMcPixels(title))));
        }

        if (!options.getInventoryTitle().isEmpty())
            layout.inventoryAnchor().ifPresent(anchor ->
                labels.add(new Label(parse(options.getInventoryTitle()), anchor)));

        if (labels.isEmpty()) return Optional.empty();

        int w = layout.width() * PX_SCALE;
        int h = layout.height() * PX_SCALE;
        int argb = options.getDefaultTitleArgb();
        boolean animated = labels.stream().anyMatch(label -> isObfuscated(label.line()));

        if (!animated) {
            PixelBuffer buffer = PixelBuffer.canvas(w, h);
            drawLabels(buffer, labels, argb, palette, 0);
            return Optional.of(Timeline.still(buffer));
        }

        // One second of unique obfuscation frames: a wall-rate loop of framesPerSecond frames, the
        // frame index and each label's number being what TextKit seeds that label's scramble from.
        return Optional.of(new Timeline.FpsLoop(options.getFramesPerSecond(), options.getFramesPerSecond())
            .wrap(f -> {
                PixelBuffer frameBuffer = PixelBuffer.canvas(w, h);
                drawLabels(frameBuffer, labels, argb, palette, f);
                return frameBuffer;
            }));
    }

    /**
     * One label and where it starts.
     *
     * @param line the parsed text
     * @param anchor the panel-relative origin of its glyph cells
     */
    private record Label(@NotNull LineSegment line, @NotNull MenuLayout.Origin anchor) {}

    /**
     * Parses caller text on the section sign, which is the prefix the client's own format codes carry.
     * An ampersand is a character a title may hold and is left as one.
     */
    private static @NotNull LineSegment parse(@NotNull String text) {
        return TextSegment.fromLegacy(text, SECTION_SIGN);
    }

    /**
     * Returns whether any segment of a line is obfuscated ({@code §k}), which is what makes the label
     * layer animate.
     */
    private static boolean isObfuscated(@NotNull LineSegment line) {
        for (TextSegment segment : line.getSegments())
            if (segment.isObfuscated()) return true;

        return false;
    }

    /**
     * Draws every label at its anchor, without a drop shadow - the client's own label draws decline
     * one.
     * <p>
     * An anchor is the top of a glyph cell, which the vanilla font opens a capital on, and the draw
     * takes the baseline an ascent below it. The ascent is asked for in Minecraft pixels, because the
     * metrics answer in output ones by default and the draw is in Minecraft ones.
     * <p>
     * Each label is drawn as its index in the list - the title first, the player's label after it -
     * which with the frame seeds its scramble, so two labels of one frame scramble apart.
     *
     * @param buffer the buffer the labels are drawn into
     * @param labels the labels, the title first
     * @param defaultArgb the colour of a label segment carrying none of its own
     * @param palette the colours the stack's text shader draws in place of the labels' own
     * @param frame the animation frame drawn
     */
    private static void drawLabels(
        @NotNull PixelBuffer buffer,
        @NotNull ConcurrentList<Label> labels,
        int defaultArgb,
        @NotNull TextPalette palette,
        long frame
    ) {
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();

        for (int i = 0; i < labels.size(); i++) {
            Label label = labels.get(i);
            TextKit.drawLine(g, label.line(), label.anchor().x(), label.anchor().y() + ascentMcPx,
                defaultArgb, palette, i, frame, frame, false);
        }
    }

}
