package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.compose.Decoration;
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
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.option.slot.MenuSlot;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * Renders an inventory-style menu by laying its cells out as a {@link MenuScreen} and painting them
 * through a {@link Window}, then placing the caller's slot content on the cells that layout produced.
 * <p>
 * There is one flow, because a menu is one arithmetic and one painter. What a caller chooses is which
 * screen the client ships, what goes in its cells and what paints its chrome; none of the three is a
 * render path of its own. A menu whose slots sit where no shipped screen puts them is one of those
 * screens with the caller's own slot map over it.
 * <p>
 * A menu speaks Minecraft pixels throughout and reaches output pixels only through {@link #PX_SCALE},
 * which is what keeps the chrome exact rather than resampled.
 */
@Parity(claim = "menu-closure", mode = Mode.DEMOTE, subject = Subject.MENU)
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
     * Constructs a new {@code MenuRenderer} bound to the given renderer context.
     *
     * @param context the renderer context supplying pack / model / texture lookups
     */
    public MenuRenderer(@NotNull RendererContext context) {
        this.context = context;
    }

    /**
     * Lays the menu's screen out, paints its chrome through the window the options select, and places
     * every populated cell on it.
     *
     * @param options the menu render options
     * @return the composited menu image
     */
    @Override
    public @NotNull ImageData render(@NotNull MenuOptions options) {
        validateScale(options);

        MenuScreen screen = options.screen();
        MenuLayout layout = screen.layout(options.isPlayerInventory());
        Window window = windowOf(this.context, options);
        validateExtent(window, screen, layout);
        validateSlots(options, layout);

        ItemRenderer itemRenderer = new ItemRenderer(this.context);
        LayerStack<FrameLayer> stack = new LayerStack<>();
        place(stack, MenuSlot.CHROME, chromeOf(window, layout));

        boolean anyAnimated = placeDecorationIcons(layout, stack, itemRenderer);
        anyAnimated |= placeSlots(options, layout, stack, itemRenderer);
        anyAnimated |= appendFillerLayers(options, layout, stack, itemRenderer);
        anyAnimated |= placeLabels(options, layout, stack);
        placeFieldText(options, layout, stack);

        return composite(layout, stack, anyAnimated, options);
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
        return options.screen().layout(options.isPlayerInventory());
    }

    /**
     * Returns the window a menu's chrome is painted by - the art the caller named, or the theme's
     * drawn geometry where they named none.
     *
     * @param context the renderer context resolving the art
     * @param options the menu render options
     * @return the window
     */
    static @NotNull Window windowOf(@NotNull RendererContext context, @NotNull MenuOptions options) {
        return options.getChromeSprite()
            .<Window>map(id -> Window.Sliced.resolve(context, id, options.getCellSprite()))
            .orElse(options.getTheme());
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
     * Paints the panel and every cell of a layout, in Minecraft pixels replicated to output ones.
     *
     * @param window the window painting the chrome
     * @param layout the laid-out panel
     * @return the painted chrome buffer
     */
    static @NotNull PixelBuffer paintChrome(@NotNull Window window, @NotNull MenuLayout layout) {
        PixelBuffer chrome = PixelBuffer.create(layout.width() * PX_SCALE, layout.height() * PX_SCALE);

        window.paintPanel(chrome, layout.box(PX_SCALE));
        for (MenuLayout.Cell cell : layout.cells())
            window.paintCell(chrome, cell.box(PX_SCALE));
        for (MenuLayout.Mark mark : layout.marks())
            window.paintDecoration(chrome, mark.box(PX_SCALE), mark.kind());

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
     * @param itemRenderer the renderer an item slot's content goes through
     * @return whether any slot resolved to animated content
     */
    static boolean placeSlots(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull ItemRenderer itemRenderer
    ) {
        ConcurrentList<MenuLayout.Cell> cells = layout.slotCells();
        boolean anyAnimated = false;

        for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet()) {
            ImageData rendered = contentOf(entry.getValue(), itemRenderer);
            if (rendered.isAnimated()) anyAnimated = true;

            place(stack, MenuSlot.SLOT, inCell(cells.get(entry.getKey()), rendered));
        }

        return anyAnimated;
    }

    /**
     * Draws what a slot holds. An item is sized here because a cell's size is the renderer's to know;
     * a render the caller produced arrives at the size they gave it.
     *
     * @param content what the slot holds
     * @param itemRenderer the renderer an item goes through
     * @return the drawn content
     */
    static @NotNull ImageData contentOf(@NotNull MenuOptions.MenuSlotContent content, @NotNull ItemRenderer itemRenderer) {
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
     * Draws the icon each of a screen's marks carries, returning whether any of them animated.
     * <p>
     * A mark's frame is chrome and its icon is a render, so the two are drawn by different parties:
     * the window paints the raised button in its own inks and this places the item on its face. That
     * split is what gives a pack its say - the item resolves through the pack stack like any other,
     * so redrawing it redraws the button.
     *
     * @param layout the laid-out panel
     * @param stack the layer stack to append to
     * @param itemRenderer the renderer an icon goes through
     * @return whether any icon resolved to animated content
     */
    static boolean placeDecorationIcons(
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull ItemRenderer itemRenderer
    ) {
        boolean anyAnimated = false;

        for (MenuLayout.Mark mark : layout.marks()) {
            Optional<ResourceId> icon = mark.icon();
            if (icon.isEmpty()) continue;

            ImageData rendered = itemRenderer.render(ItemOptions.builder()
                .itemId(icon.get().id())
                .type(ItemOptions.Type.GUI_ICON)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(CONTENT_PX).build())
                .build());
            if (rendered.isAnimated()) anyAnimated = true;

            Decoration.Inset inset = mark.kind().iconInset().orElseThrow();
            place(stack, MenuSlot.CONTENT, new FramePlacement(
                (mark.x() + inset.x()) * PX_SCALE,
                (mark.y() + inset.y()) * PX_SCALE, rendered));
        }

        return anyAnimated;
    }

    /**
     * The insert caret's own extent - one Minecraft pixel wide, opening a pixel above the glyph
     * cells and running four below them, which is the bar vanilla fills rather than a glyph.
     */
    private static final int CARET_RISE = 1, CARET_HEIGHT = 11;

    /**
     * Draws what a screen's text field holds and the caret marking where typing would continue.
     * <p>
     * The field's well is chrome and its text is a render, the same split a button's face takes: the
     * window sinks the well in its own inks and this puts the text in it. Nothing is drawn for a
     * screen that has no field, which is every screen but the anvil.
     * <p>
     * The text is drawn plain rather than parsed for format codes, because the client's own field
     * filters them out of what can be typed, and it carries a drop shadow where a container's labels
     * decline one - it is a widget's text and not the panel's.
     *
     * @param options the menu render options, supplying the text and whether a caret is drawn
     * @param layout the laid-out panel, carrying the field among its marks
     * @param stack the layer stack to append to
     */
    static void placeFieldText(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack
    ) {
        Optional<MenuLayout.Mark> field = layout.marks().stream()
            .filter(mark -> mark.kind().textWell().isPresent())
            .findFirst();
        if (field.isEmpty()) return;

        Decoration.TextWell well = field.get().kind().textWell().orElseThrow();
        String typed = typedInto(options.getFieldText(), well.maxLength());
        String shown = visibleTail(typed, well.innerWidth());
        if (shown.isEmpty() && !options.isCaret()) return;

        PixelBuffer buffer = PixelBuffer.create(layout.width() * PX_SCALE, layout.height() * PX_SCALE);
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        int textX = field.get().x() + well.inset().x();
        int textY = field.get().y() + well.inset().y();
        int baseline = textY + MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        int drawn = shown.isEmpty() ? 0
            : TextKit.drawLine(g, plain(shown), textX, baseline, well.argb(), 0L, 0L, true);

        if (options.isCaret()) drawCaret(g, buffer, typed, textX + drawn, textY, baseline, well);

        place(stack, MenuSlot.TEXT, new FramePlacement(0, 0, StaticImageData.of(buffer.toBufferedImage())));
    }

    /**
     * What a field actually holds, which is the caller's text cut to the field's own cap the way the
     * client's own cuts anything typed past it.
     *
     * @param text what the caller asked for
     * @param maxLength how many characters the field accepts
     * @return the text the field holds
     */
    static @NotNull String typedInto(@NotNull String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * The part of the text a field shows - its longest tail that fits, because a field scrolls to
     * keep the end of what was typed in view rather than the beginning.
     *
     * @param typed what the field holds
     * @param innerWidth how wide the text may run, in Minecraft pixels
     * @return the visible tail, empty where even the last character does not fit
     */
    static @NotNull String visibleTail(@NotNull String typed, int innerWidth) {
        for (int from = 0; from < typed.length(); from++) {
            String tail = typed.substring(from);
            if (TextKit.measureLineMcPixels(plain(tail)) <= innerWidth) return tail;
        }

        return "";
    }

    /**
     * Draws the caret in whichever of its two forms the text's length selects.
     * <p>
     * Vanilla appends an underscore glyph after the text, and stops being able to once the field is
     * full - there is no position past the last character to append at - so at the cap it fills a
     * bar beside it instead. The glyph carries the text's drop shadow and the bar carries none,
     * being a fill rather than a draw.
     */
    private static void drawCaret(
        @NotNull MinecraftGraphics g, @NotNull PixelBuffer buffer,
        String typed, int caretX, int textY, int baseline, @NotNull Decoration.TextWell well
    ) {
        if (typed.length() < well.maxLength()) {
            TextKit.drawLine(g, plain("_"), caretX, baseline, well.argb(), 0L, 0L, true);
            return;
        }

        for (int y = 0; y < CARET_HEIGHT * PX_SCALE; y++) {
            int py = (textY - CARET_RISE) * PX_SCALE + y;
            if (py < 0 || py >= buffer.height()) continue;

            for (int x = 0; x < PX_SCALE; x++) {
                int px = caretX * PX_SCALE + x;
                if (px < 0 || px >= buffer.width()) continue;
                buffer.setPixel(px, py, well.argb());
            }
        }
    }

    /**
     * One run of unstyled text as a line, for the draws that take a caller's characters as they
     * arrived rather than as a format string.
     */
    private static @NotNull LineSegment plain(@NotNull String text) {
        return LineSegment.builder().withSegments(new ColorSegment(text)).build();
    }

    /**
     * Appends filler layers to every cell the caller populated none of, according to
     * {@link MenuOptions#getFill() options.fill}. Returns whether the filler resolved to animated
     * content so the caller can keep its {@code anyAnimated} flag accurate.
     */
    static boolean appendFillerLayers(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull ItemRenderer itemRenderer
    ) {
        Optional<ResourceId> filler = options.getFill().itemId();
        if (filler.isEmpty()) return false;

        ConcurrentList<MenuLayout.Cell> cells = layout.slotCells();
        ItemOptions fillerOptions = ItemOptions.builder()
            .itemId(filler.get().id())
            .type(ItemOptions.Type.GUI_ICON)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(CONTENT_PX).build())
            .build();
        ImageData fillerImage = itemRenderer.render(fillerOptions);

        for (int index = 0; index < cells.size(); index++) {
            if (options.getSlots().containsKey(index)) continue;
            place(stack, MenuSlot.CONTENT, inCell(cells.get(index), fillerImage));
        }

        return fillerImage.isAnimated();
    }

    /**
     * Final composite step. Fast-paths a single-frame static composite when nothing is animated;
     * otherwise promotes everything to animated output via {@link FrameCompositor#merge}.
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
    static void validateExtent(@NotNull Window window, @NotNull MenuScreen screen, @NotNull MenuLayout layout) {
        Window.Extent art = window.minimum();
        Window.Extent content = screen.minimum();
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
     * takes the baseline an ascent below it. The ascent is asked for in Minecraft pixels, because the
     * metrics answer in output ones by default and the draw is in Minecraft ones.
     */
    private static void drawLabels(
        @NotNull PixelBuffer buffer,
        @NotNull ConcurrentList<Label> labels,
        int defaultArgb,
        long frameSeed
    ) {
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        int ascentMcPx = MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();

        for (Label label : labels)
            TextKit.drawLine(g, label.line(), label.anchor().x(), label.anchor().y() + ascentMcPx,
                defaultArgb, frameSeed, frameSeed, false);
    }

}
