package dev.sbs.renderer;

import dev.sbs.renderer.draw.Canvas;
import dev.sbs.renderer.draw.ColorKit;
import dev.sbs.renderer.draw.FrameMerger;
import dev.sbs.renderer.engine.RenderEngine;
import dev.sbs.renderer.engine.RendererContext;
import dev.sbs.renderer.engine.TextEngine;
import dev.sbs.renderer.options.BlockOptions;
import dev.sbs.renderer.options.ItemOptions;
import dev.sbs.renderer.options.MenuOptions;
import dev.sbs.renderer.text.MinecraftFont;
import dev.sbs.renderer.text.segment.ColorSegment;
import dev.sbs.renderer.text.segment.LineSegment;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.StaticImageData;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renders an inventory-style menu (chest, player, crafting, anvil) by dispatching to one of
 * five sub-renderers based on {@link MenuOptions#getType()}.
 * <p>
 * Each sub-renderer is a {@code public static final} inner class implementing
 * {@link Renderer Renderer&lt;MenuOptions&gt;}:
 * <ul>
 * <li>{@link Generic} - shared flow for {@link MenuOptions.Type#PLAYER PLAYER},
 * {@link MenuOptions.Type#CHEST CHEST}, {@link MenuOptions.Type#CUSTOM CUSTOM}, and
 * {@link MenuOptions.Type#SLOT SLOT}. Builds a rectangular grid with the generic theme
 * chrome.</li>
 * <li>{@link VanillaCrafting} - 5x3 canvas for the Minecraft crafting table with a 3x3 grid
 * and an output slot at column 4.</li>
 * <li>{@link VanillaAnvil} - 5-column canvas with a rename textbox and the three input /
 * decoration / output slot row.</li>
 * <li>{@link SkyblockCrafting} - 9x6 Hypixel chest chrome with the SkyBlock crafting layout
 * baked into chest slots.</li>
 * <li>{@link SkyblockAnvil} - 9x6 chest chrome with the SkyBlock combine menu slots, an
 * isometric anvil decoration, and red stained glass pane borders.</li>
 * </ul>
 * Shared chrome drawing (chest bevels, slot insets, craft arrows, plus signs, hammer sprite,
 * rename textbox), slot coordinate math, filler application, composite/merge, and validation
 * helpers live as package-private static methods on this class so every sub-renderer reaches
 * them without duplication. The sub-renderers each own only the constants and layout logic
 * unique to their menu type.
 */
public final class MenuRenderer implements Renderer<MenuOptions> {

    // --- Shared geometry constants ---

    static final int SLOT_SIZE = 36;
    static final int INSET = 4;
    static final int TITLE_HEIGHT = 24;
    static final int XP_LABEL_HEIGHT = 20;

    // --- Shared SkyBlock chest (9x6) dimensions ---

    static final int SKYBLOCK_CHEST_COLS = 9;
    static final int SKYBLOCK_CHEST_ROWS = 6;
    static final int SKYBLOCK_CHEST_SLOTS = SKYBLOCK_CHEST_COLS * SKYBLOCK_CHEST_ROWS;

    private final @NotNull Generic generic;
    private final @NotNull VanillaCrafting vanillaCrafting;
    private final @NotNull VanillaAnvil vanillaAnvil;
    private final @NotNull SkyblockCrafting skyblockCrafting;
    private final @NotNull SkyblockAnvil skyblockAnvil;

    public MenuRenderer(@NotNull RendererContext context) {
        this.generic = new Generic(context);
        this.vanillaCrafting = new VanillaCrafting(context);
        this.vanillaAnvil = new VanillaAnvil(context);
        this.skyblockCrafting = new SkyblockCrafting(context);
        this.skyblockAnvil = new SkyblockAnvil(context);
    }

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
    // Shared helpers reachable from every sub-renderer.
    // ---------------------------------------------------------------------------------------

    /**
     * Appends filler layers to every non-claimed chest slot according to
     * {@link MenuOptions#getFill() options.fill}. Returns whether any of the filler layers
     * resolved to animated content so the caller can keep its {@code anyAnimated} flag
     * accurate.
     */
    static boolean appendFillerLayers(
        @NotNull MenuOptions options,
        @NotNull ConcurrentList<FrameMerger.Layer> layers,
        @NotNull ItemRenderer itemRenderer,
        @NotNull Set<Integer> claimed
    ) {
        if (options.getFill() == MenuOptions.Fill.EMPTY) return false;

        ItemOptions fillerOptions = switch (options.getFill()) {
            case BLACK_STAINED_GLASS_PANE -> ItemOptions.builder()
                .itemId("minecraft:black_stained_glass_pane")
                .type(ItemOptions.Type.GUI_2D)
                .outputSize(SLOT_SIZE - 4)
                .build();
            case EMPTY -> throw new IllegalStateException("EMPTY handled above");
        };
        ImageData fillerImage = itemRenderer.render(fillerOptions);
        boolean fillerAnimated = fillerImage.isAnimated();

        for (int chestSlot = 0; chestSlot < SKYBLOCK_CHEST_SLOTS; chestSlot++) {
            if (claimed.contains(chestSlot)) continue;
            layers.add(new FrameMerger.Layer(
                chestSlotX(chestSlot) + 2,
                chestSlotY(chestSlot) + 2,
                fillerImage
            ));
        }

        return fillerAnimated;
    }

    /**
     * Final composite step shared by every render path. Fast-paths a single-frame static
     * composite when nothing is animated; otherwise promotes everything to animated output via
     * {@link FrameMerger#merge}.
     */
    static @NotNull ImageData composite(
        int canvasW, int canvasH,
        @NotNull ConcurrentList<FrameMerger.Layer> layers,
        boolean anyAnimated,
        @NotNull MenuOptions options
    ) {
        if (!anyAnimated) {
            Canvas canvas = Canvas.of(canvasW, canvasH);

            for (FrameMerger.Layer layer : layers)
                canvas.blit(PixelBuffer.wrap(layer.source().toBufferedImage()), layer.x(), layer.y());

            return RenderEngine.staticFrame(canvas);
        }

        return FrameMerger.merge(layers, canvasW, canvasH, options.getFramesPerSecond(), ColorKit.TRANSPARENT);
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

        throw new IllegalArgumentException(
            "Fill option " + options.getFill() + " is only supported for SKYBLOCK menu types; got " + options.getType()
        );
    }

    private static boolean isSkyblockType(@NotNull MenuOptions.Type type) {
        return type == MenuOptions.Type.SKYBLOCK_CRAFTING
            || type == MenuOptions.Type.SKYBLOCK_ANVIL;
    }

    /**
     * Validates that every caller-supplied slot index sits within the legal range for the
     * menu type. Each sub-renderer calls this at the top of its {@code render} so out-of-range
     * slots fail fast with a descriptive {@link IllegalArgumentException} rather than producing
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
                throw new IllegalArgumentException("Slot " + slot + " is out of range for menu type " + options.getType() + " (max " + maxSlot + ")");
        }
    }

    /**
     * Returns the canvas X origin of a chest slot by its zero-based 9x6 index.
     */
    static int chestSlotX(int chestSlot) {
        return INSET + (chestSlot % SKYBLOCK_CHEST_COLS) * SLOT_SIZE;
    }

    /**
     * Returns the canvas Y origin of a chest slot by its zero-based 9x6 index.
     */
    static int chestSlotY(int chestSlot) {
        return INSET + (chestSlot / SKYBLOCK_CHEST_COLS) * SLOT_SIZE + TITLE_HEIGHT;
    }

    /**
     * Flat-theme chrome for the generic menu types. Uses the {@link MenuOptions#getTheme()
     * theme} to pick background + slot colours, then fills a rectangular slot grid with no
     * beveled edges.
     */
    static void drawGenericChrome(@NotNull Canvas canvas, int rows, int cols, @NotNull MenuOptions options) {
        int backgroundArgb = switch (options.getTheme()) {
            case VANILLA -> 0xFFC6C6C6;
            case DARK -> 0xFF303030;
            case SKYBLOCK -> 0xFF1E1E2E;
        };
        canvas.fill(backgroundArgb);

        int slotArgb = switch (options.getTheme()) {
            case VANILLA -> 0xFF8B8B8B;
            case DARK -> 0xFF1A1A1A;
            case SKYBLOCK -> 0xFF111122;
        };

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x = INSET + col * SLOT_SIZE;
                int y = INSET + TITLE_HEIGHT + row * SLOT_SIZE;
                fillRect(canvas, x, y, SLOT_SIZE - 2, SLOT_SIZE - 2, slotArgb);
            }
        }
    }

    static void fillRect(@NotNull Canvas canvas, int x, int y, int w, int h, int argb) {
        int canvasW = canvas.width();
        int canvasH = canvas.height();
        for (int dy = 0; dy < h; dy++) {
            int py = y + dy;
            if (py < 0 || py >= canvasH) continue;
            for (int dx = 0; dx < w; dx++) {
                int px = x + dx;
                if (px < 0 || px >= canvasW) continue;
                canvas.getBuffer().setPixel(px, py, argb);
            }
        }
    }

    /**
     * Erases a slot position by overpainting it with the given background colour. Used by the
     * vanilla crafting layout to remove the beveled slot insets at column 3, rows 0 and 2
     * where the arrow area should look like empty chrome rather than an empty slot.
     */
    static void drawSlotBackground(@NotNull Canvas canvas, int col, int row, int argb) {
        int x = INSET + col * SLOT_SIZE;
        int y = INSET + TITLE_HEIGHT + row * SLOT_SIZE;
        fillRect(canvas, x, y, SLOT_SIZE, SLOT_SIZE, argb);
    }

    /**
     * Draws a programmatic vanilla-style Minecraft chest GUI chrome onto the canvas. Outer
     * border uses a raised 2-pixel bevel (light highlight on top/left, dark shadow on
     * bottom/right); each slot uses an inverted sunken bevel so the interior appears inset.
     * Colors match the classic Minecraft inventory palette ({@code 0xFFC6C6C6} background,
     * {@code 0xFF8B8B8B} slot fill, white highlights, dark-gray shadows).
     */
    static void drawVanillaChestChrome(@NotNull Canvas canvas, int rows, int cols) {
        int w = canvas.width();
        int h = canvas.height();

        final int background = 0xFFC6C6C6;
        final int borderHighlight = 0xFFFFFFFF;
        final int borderShadow = 0xFF555555;
        final int slotFill = 0xFF8B8B8B;
        final int slotShadow = 0xFF373737;
        final int slotHighlight = 0xFFFFFFFF;
        final int titleBand = 0xFFB4B4B4;
        final int borderThickness = 2;

        canvas.fill(background);

        fillRect(canvas, 0, 0, w, borderThickness, borderHighlight);
        fillRect(canvas, 0, 0, borderThickness, h, borderHighlight);
        fillRect(canvas, 0, h - borderThickness, w, borderThickness, borderShadow);
        fillRect(canvas, w - borderThickness, 0, borderThickness, h, borderShadow);

        fillRect(canvas, borderThickness, borderThickness,
            w - 2 * borderThickness, TITLE_HEIGHT - borderThickness, titleBand);
        fillRect(canvas, borderThickness, TITLE_HEIGHT,
            w - 2 * borderThickness, 1, borderShadow);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int sx = INSET + col * SLOT_SIZE;
                int sy = INSET + TITLE_HEIGHT + row * SLOT_SIZE;
                int sw = SLOT_SIZE - 2;
                int sh = SLOT_SIZE - 2;

                fillRect(canvas, sx, sy, sw, sh, slotFill);
                fillRect(canvas, sx, sy, sw, 1, slotShadow);
                fillRect(canvas, sx, sy, 1, sh, slotShadow);
                fillRect(canvas, sx, sy + sh - 1, sw, 1, slotHighlight);
                fillRect(canvas, sx + sw - 1, sy, 1, sh, slotHighlight);
            }
        }
    }

    /**
     * Draws a craft arrow centred on the given (col, row) slot position. The arrow bounding
     * box is inset a third of the slot on each side so the arrow sits visually within the slot
     * without touching the beveled edges.
     */
    static void drawCraftArrowInSlot(@NotNull Canvas canvas, int col, int row) {
        int padX = SLOT_SIZE / 6;
        int padY = SLOT_SIZE / 3;
        int x = INSET + col * SLOT_SIZE + padX;
        int y = INSET + TITLE_HEIGHT + row * SLOT_SIZE + padY;
        int w = SLOT_SIZE - 2 * padX;
        int h = SLOT_SIZE - 2 * padY;
        drawCraftArrow(canvas, x, y, w, h, 0xFF555555);
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
     * @param canvas the target canvas
     * @param x the bounding box minimum X
     * @param y the bounding box minimum Y
     * @param width the bounding box width
     * @param height the bounding box height
     * @param argb the arrow colour
     */
    static void drawCraftArrow(@NotNull Canvas canvas, int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) return;

        int bodyWidth = Math.max(1, width * 3 / 5);
        int bodyThickness = Math.max(2, height / 3);
        int bodyY = y + (height - bodyThickness) / 2;

        fillRect(canvas, x, bodyY, bodyWidth, bodyThickness, argb);

        int headLength = width - bodyWidth;
        if (headLength <= 0) return;

        int headBaseHalf = Math.max(1, (height - 1) / 2);
        int centerY = y + height / 2;

        for (int i = 0; i < headLength; i++) {
            int halfH = headBaseHalf * (headLength - i) / headLength;
            int hx = x + bodyWidth + i;
            int hTop = centerY - halfH;
            int hHeight = 2 * halfH + 1;
            fillRect(canvas, hx, hTop, 1, hHeight, argb);
        }
    }

    /**
     * Draws a craft arrow at an explicit Y position (rather than a slot row index). Used by
     * layouts that have custom vertical spacing above the slot row, like the vanilla anvil
     * with its rename textbox sitting between the title and the slot row.
     */
    static void drawCraftArrowInSlotAt(@NotNull Canvas canvas, int col, int y) {
        int padX = SLOT_SIZE / 6;
        int padY = SLOT_SIZE / 3;
        int x = INSET + col * SLOT_SIZE + padX;
        int w = SLOT_SIZE - 2 * padX;
        int h = SLOT_SIZE - 2 * padY;
        drawCraftArrow(canvas, x, y + padY, w, h, 0xFF555555);
    }

    /**
     * Draws a centred plus sign inside the slot-sized cell at the given column and explicit Y
     * position. Proportions match the craft arrow so the two decorations line up visually.
     */
    static void drawPlusInSlot(@NotNull Canvas canvas, int col, int y) {
        int pad = SLOT_SIZE / 4;
        int x = INSET + col * SLOT_SIZE + pad;
        int size = SLOT_SIZE - 2 * pad;
        drawPlus(canvas, x, y + pad, size, 0xFF555555);
    }

    /**
     * Draws a simple {@code +} sign inside the given bounding square. The horizontal and
     * vertical bars both have a thickness proportional to the box size (roughly a quarter).
     */
    static void drawPlus(@NotNull Canvas canvas, int x, int y, int size, int argb) {
        if (size <= 0) return;
        int thickness = Math.max(2, size / 4);
        int offset = (size - thickness) / 2;
        fillRect(canvas, x, y + offset, size, thickness, argb);
        fillRect(canvas, x + offset, y, thickness, size, argb);
    }

    /**
     * Draws a single sunken slot inset (matching the {@link #drawVanillaChestChrome} slot
     * style) at the given pixel position. Used by layouts that want to place slot cells
     * outside the regular grid that {@code drawVanillaChestChrome} fills, e.g. the vanilla
     * anvil slot row below the rename textbox.
     */
    static void drawSlotInset(@NotNull Canvas canvas, int x, int y, int w, int h) {
        final int slotFill = 0xFF8B8B8B;
        final int slotShadow = 0xFF373737;
        final int slotHighlight = 0xFFFFFFFF;
        fillRect(canvas, x, y, w, h, slotFill);
        fillRect(canvas, x, y, w, 1, slotShadow);
        fillRect(canvas, x, y, 1, h, slotShadow);
        fillRect(canvas, x, y + h - 1, w, 1, slotHighlight);
        fillRect(canvas, x + w - 1, y, 1, h, slotHighlight);
    }

    /**
     * Draws the beige rename textbox used by the vanilla anvil. A dark outer border wraps a
     * cream-coloured interior, matching the classic Minecraft anvil GUI textbox style where
     * the player types a new item name. Does not render any text - callers that want a
     * placeholder or a typed string need to blit it on top after this call.
     */
    static void drawRenameTextbox(@NotNull Canvas canvas, int x, int y, int width, int height) {
        if (width <= 4 || height <= 4) return;
        final int borderColor = 0xFF373737;
        final int beigeColor = 0xFFE5D4AC;

        fillRect(canvas, x, y, width, height, borderColor);
        fillRect(canvas, x + 2, y + 2, width - 4, height - 4, beigeColor);
    }

    /**
     * Draws a stylised hammer sprite programmatically in the given colour. The sprite has a
     * rectangular head occupying the top third of the bounding box and a handle roughly a
     * quarter of the width, centred horizontally beneath the head.
     */
    static void drawHammer(@NotNull Canvas canvas, int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) return;
        int headHeight = Math.max(3, height / 3);
        int handleWidth = Math.max(2, width / 4);
        int handleHeight = height - headHeight;
        int handleX = x + (width - handleWidth) / 2;
        int handleY = y + headHeight;

        fillRect(canvas, x, y, width, headHeight, argb);
        fillRect(canvas, handleX, handleY, handleWidth, handleHeight, argb);
    }

    // ---------------------------------------------------------------------------------------
    // Title / label drawing helpers.
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the chrome {@link ImageData} layer for a menu. When the title contains
     * obfuscated segments ({@code §k}), the result is an animated {@link ImageData} with
     * one second of unique obfuscation frames; otherwise a single static frame is returned.
     * <p>
     * The caller must draw all pixel-based chrome and any static text labels onto
     * {@code baseChrome} and {@linkplain Canvas#disposeGraphics() dispose its graphics}
     * before calling this method.
     *
     * @param baseChrome the chrome canvas with all static content already drawn
     * @param options the menu options (supplies title and FPS)
     * @param titleX the horizontal origin for the title text
     * @param defaultTitleColor the colour used for title segments with no explicit colour
     * @return a static or animated chrome layer
     */
    static @NotNull ImageData renderChrome(
        @NotNull Canvas baseChrome,
        @NotNull MenuOptions options,
        int titleX,
        @NotNull Color defaultTitleColor
    ) {
        String title = options.getTitle();
        if (title.isEmpty())
            return StaticImageData.of(baseChrome.getBuffer().toBufferedImage());

        LineSegment titleLine = ColorSegment.fromLegacy(title);
        boolean animated = hasTitleObfuscation(titleLine);

        if (!animated) {
            drawTitleSegments(baseChrome, titleLine, titleX, INSET, TITLE_HEIGHT, defaultTitleColor, 0);
            baseChrome.disposeGraphics();
            return StaticImageData.of(baseChrome.getBuffer().toBufferedImage());
        }

        PixelBuffer base = baseChrome.getBuffer();
        int w = baseChrome.width();
        int h = baseChrome.height();
        int frameCount = options.getFramesPerSecond();
        ConcurrentList<PixelBuffer> frames = Concurrent.newList();

        for (int i = 0; i < frameCount; i++) {
            Canvas frame = Canvas.of(w, h);
            frame.blit(base, 0, 0);
            drawTitleSegments(frame, titleLine, titleX, INSET, TITLE_HEIGHT, defaultTitleColor, i);
            frame.disposeGraphics();
            frames.add(frame.getBuffer());
        }

        int delayMs = Math.max(1, Math.round(1000f / options.getFramesPerSecond()));
        return RenderEngine.output(frames, delayMs);
    }

    private static boolean hasTitleObfuscation(@NotNull LineSegment line) {
        for (ColorSegment segment : line.getSegments())
            if (segment.isObfuscated()) return true;
        return false;
    }

    /**
     * Renders pre-parsed title segments onto the canvas. Delegates font style resolution,
     * colour mapping, shadow, and obfuscation to {@link TextEngine}.
     */
    private static void drawTitleSegments(
        @NotNull Canvas canvas,
        @NotNull LineSegment titleLine,
        int titleX, int bandTop, int bandHeight,
        @NotNull Color defaultColor,
        long frameSeed
    ) {
        Graphics2D g = canvas.graphics();
        g.setFont(MinecraftFont.REGULAR.getActual());
        FontMetrics fm = g.getFontMetrics();
        int textY = bandTop + (bandHeight - fm.getHeight()) / 2 + fm.getAscent();
        TextEngine.drawLine(canvas, titleLine, titleX, textY, defaultColor, frameSeed);
    }

    /**
     * Renders the textbox label inside the rename textbox interior. The text is drawn in
     * white, left-aligned with a small horizontal padding. Does nothing when the label is
     * empty.
     */
    static void drawTextboxLabel(
        @NotNull Canvas canvas,
        @NotNull String label,
        int innerX, int innerY, int innerH
    ) {
        if (label.isEmpty()) return;

        Graphics2D g = canvas.graphics();
        g.setFont(MinecraftFont.REGULAR.getActual());
        FontMetrics fm = g.getFontMetrics();
        int textY = innerY + (innerH - fm.getHeight()) / 2 + fm.getAscent();
        TextEngine.drawText(canvas, label, innerX + 2, textY, MinecraftFont.REGULAR.getActual(), Color.WHITE);
    }

    /**
     * Renders the XP cost label right-aligned in the given area, displayed as
     * {@code "Enchantment Cost: X"} in green ({@code 0x80FF20}), matching the vanilla
     * Minecraft anvil style. Does nothing when cost is zero or negative.
     */
    static void drawXpCost(@NotNull Canvas canvas, int cost, int canvasW, int areaTop, int areaHeight) {
        if (cost <= 0) return;

        String text = "Enchantment Cost: " + cost;
        Font font = MinecraftFont.REGULAR.getActual();
        Graphics2D g = canvas.graphics();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textX = canvasW - INSET - 4 - fm.stringWidth(text);
        int textY = areaTop + (areaHeight - fm.getHeight()) / 2 + fm.getAscent();
        TextEngine.drawText(canvas, text, textX, textY, font, new Color(0x80FF20));
    }

    // ---------------------------------------------------------------------------------------
    // Sub-renderers.
    // ---------------------------------------------------------------------------------------

    /**
     * Generic rectangular menu renderer for the simple types that just place caller slots on a
     * flat-themed grid without any decoration: {@link MenuOptions.Type#PLAYER PLAYER},
     * {@link MenuOptions.Type#CHEST CHEST}, {@link MenuOptions.Type#CUSTOM CUSTOM},
     * {@link MenuOptions.Type#SLOT SLOT}.
     */
    @RequiredArgsConstructor
    public static final class Generic implements Renderer<MenuOptions> {

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            int rows = resolveRows(options);
            int cols = resolveColumns(options);
            int canvasW = cols * SLOT_SIZE + 2 * INSET;
            int canvasH = rows * SLOT_SIZE + 2 * INSET + TITLE_HEIGHT;

            Canvas chromeCanvas = Canvas.of(canvasW, canvasH);
            drawGenericChrome(chromeCanvas, rows, cols, options);
            Color defaultTitleColor = switch (options.getTheme()) {
                case VANILLA -> new Color(0x404040);
                case DARK, SKYBLOCK -> Color.WHITE;
            };
            ImageData chromeData = renderChrome(chromeCanvas, options, INSET + 4, defaultTitleColor);

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
            layers.add(new FrameMerger.Layer(0, 0, chromeData));

            boolean anyAnimated = chromeData.isAnimated();
            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet().stream().toList()) {
                int slotIndex = entry.getKey();
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;

                int col = slotIndex % cols;
                int row = slotIndex / cols;
                int x = INSET + col * SLOT_SIZE;
                int y = INSET + TITLE_HEIGHT + row * SLOT_SIZE;
                layers.add(new FrameMerger.Layer(x, y, rendered));
            }

            return composite(canvasW, canvasH, layers, anyAnimated, options);
        }

        private static int resolveRows(@NotNull MenuOptions options) {
            return switch (options.getType()) {
                case PLAYER -> 4;
                case CHEST, CUSTOM -> options.getRows();
                case SLOT -> 1;
                case VANILLA_CRAFTING, VANILLA_ANVIL, SKYBLOCK_CRAFTING, SKYBLOCK_ANVIL ->
                    throw new IllegalStateException("Type " + options.getType() + " uses a dedicated renderer");
            };
        }

        private static int resolveColumns(@NotNull MenuOptions options) {
            return switch (options.getType()) {
                case PLAYER, CHEST -> 9;
                case CUSTOM -> options.getColumns();
                case SLOT -> 1;
                case VANILLA_CRAFTING, VANILLA_ANVIL, SKYBLOCK_CRAFTING, SKYBLOCK_ANVIL ->
                    throw new IllegalStateException("Type " + options.getType() + " uses a dedicated renderer");
            };
        }

    }

    /**
     * Dedicated renderer for the vanilla Minecraft crafting table. Uses a 5x3 canvas with the
     * 3x3 input grid at columns 0..2 and the output slot at column 4, row 1, with a craft
     * arrow drawn at column 3. Fixes the pre-existing bug where slot 9 overflowed a generic
     * 3x3 resolveRows/resolveColumns layout.
     */
    @RequiredArgsConstructor
    public static final class VanillaCrafting implements Renderer<MenuOptions> {

        private static final int COLS = 5;
        private static final int ROWS = 3;

        /**
         * Caller slot index to (col, row) position on the 5x3 vanilla crafting canvas. Slots
         * 0..8 form the 3x3 input grid in reading order, slot 9 is the output at column 4,
         * row 1.
         */
        private static final int @NotNull [] @NotNull [] SLOT_COORDS = {
            {0, 0}, {1, 0}, {2, 0},
            {0, 1}, {1, 1}, {2, 1},
            {0, 2}, {1, 2}, {2, 2},
            {4, 1}
        };

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            int canvasW = COLS * SLOT_SIZE + 2 * INSET;
            int canvasH = ROWS * SLOT_SIZE + 2 * INSET + TITLE_HEIGHT;

            Canvas chromeCanvas = Canvas.of(canvasW, canvasH);
            drawVanillaChestChrome(chromeCanvas, ROWS, COLS);
            drawSlotBackground(chromeCanvas, 3, 0, 0xFFC6C6C6);
            drawSlotBackground(chromeCanvas, 3, 2, 0xFFC6C6C6);
            drawCraftArrowInSlot(chromeCanvas, 3, 1);
            ImageData chromeData = renderChrome(chromeCanvas, options, INSET + 4, new Color(0x404040));

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
            layers.add(new FrameMerger.Layer(0, 0, chromeData));

            boolean anyAnimated = chromeData.isAnimated();
            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet().stream().toList()) {
                int callerSlot = entry.getKey();
                int[] coord = SLOT_COORDS[callerSlot];
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;

                int x = INSET + coord[0] * SLOT_SIZE;
                int y = INSET + TITLE_HEIGHT + coord[1] * SLOT_SIZE;
                layers.add(new FrameMerger.Layer(x, y, rendered));
            }

            return composite(canvasW, canvasH, layers, anyAnimated, options);
        }

    }

    /**
     * Dedicated renderer for the vanilla Minecraft anvil menu. Uses a 5-column canvas with a
     * rename textbox spanning the full width under the title bar, then a single slot row laid
     * out as {@code [input1] [+] [input2] [arrow] [output]}. The title bar carries a white
     * programmatic hammer sprite in its top-left corner.
     */
    @RequiredArgsConstructor
    public static final class VanillaAnvil implements Renderer<MenuOptions> {

        private static final int COLS = 5;
        private static final int TEXTBOX_HEIGHT = 30;

        /**
         * Caller slot index to column on the single slot row of the vanilla anvil layout.
         * Index 0 is the first input, index 1 is the second input, index 2 is the output. The
         * columns leave slot 1 (the {@code +} sign) and slot 3 (the craft arrow) as decorative
         * columns.
         */
        private static final int @NotNull [] SLOT_COLS = { 0, 2, 4 };

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            int canvasW = COLS * SLOT_SIZE + 2 * INSET;
            int xpLabelHeight = options.getXpCost() > 0 ? XP_LABEL_HEIGHT : 0;
            int canvasH = TITLE_HEIGHT + TEXTBOX_HEIGHT + SLOT_SIZE + 2 * INSET + xpLabelHeight;

            Canvas chromeCanvas = Canvas.of(canvasW, canvasH);
            drawVanillaChestChrome(chromeCanvas, 0, COLS);
            drawHammer(chromeCanvas, INSET + 4, (TITLE_HEIGHT - 16) / 2 + 2, 16, 16, 0xFFFFFFFF);

            int textboxX = INSET + SLOT_SIZE / 2;
            int textboxY = INSET + TITLE_HEIGHT + 4;
            int textboxW = canvasW - 2 * INSET - SLOT_SIZE;
            int textboxH = TEXTBOX_HEIGHT - 8;
            drawRenameTextbox(chromeCanvas, textboxX, textboxY, textboxW, textboxH);

            int slotRowY = INSET + TITLE_HEIGHT + TEXTBOX_HEIGHT;
            for (int col : SLOT_COLS) {
                int sx = INSET + col * SLOT_SIZE;
                drawSlotInset(chromeCanvas, sx, slotRowY, SLOT_SIZE - 2, SLOT_SIZE - 2);
            }
            drawPlusInSlot(chromeCanvas, 1, slotRowY);
            drawCraftArrowInSlotAt(chromeCanvas, 3, slotRowY);

            drawTextboxLabel(chromeCanvas, options.getTextboxLabel(),
                textboxX + 2, textboxY + 2, textboxH - 4);
            drawXpCost(chromeCanvas, options.getXpCost(), canvasW,
                slotRowY + SLOT_SIZE, xpLabelHeight);
            chromeCanvas.disposeGraphics();

            ImageData chromeData = renderChrome(chromeCanvas, options, INSET + 24, new Color(0x404040));

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
            layers.add(new FrameMerger.Layer(0, 0, chromeData));

            boolean anyAnimated = chromeData.isAnimated();
            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet().stream().toList()) {
                int callerSlot = entry.getKey();
                int col = SLOT_COLS[callerSlot];
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;

                int x = INSET + col * SLOT_SIZE;
                layers.add(new FrameMerger.Layer(x, slotRowY, rendered));
            }

            return composite(canvasW, canvasH, layers, anyAnimated, options);
        }

    }

    /**
     * Dedicated renderer for the SkyBlock crafting menu. Validates the caller's slot input
     * (max 9), draws a 9x6 vanilla-style gray chest chrome with a craft arrow between the grid
     * and the output, translates caller slots 0..9 into chest positions via
     * {@link #SLOT_MAP}, and fills the remaining 44 chest slots according to
     * {@link MenuOptions#getFill() options.fill}.
     */
    @RequiredArgsConstructor
    public static final class SkyblockCrafting implements Renderer<MenuOptions> {

        /**
         * Maps the 10 caller slot indices (0..8 for the 3x3 crafting grid in reading order, 9
         * for the craft-result output) to their positions on the underlying 9x6 Hypixel chest.
         * The 3x3 grid sits at chest slots 10/11/12/19/20/21/28/29/30 and the output is at
         * chest slot 23, matching the standard "Craft Item" menu.
         */
        private static final int @NotNull [] SLOT_MAP = {
            10, 11, 12,
            19, 20, 21,
            28, 29, 30,
            23
        };

        /** Chest slot where the craft arrow is drawn, between the grid and slot 23. */
        private static final int ARROW_SLOT = 22;

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            int canvasW = SKYBLOCK_CHEST_COLS * SLOT_SIZE + 2 * INSET;
            int canvasH = SKYBLOCK_CHEST_ROWS * SLOT_SIZE + 2 * INSET + TITLE_HEIGHT;

            Canvas chromeCanvas = Canvas.of(canvasW, canvasH);
            drawVanillaChestChrome(chromeCanvas, SKYBLOCK_CHEST_ROWS, SKYBLOCK_CHEST_COLS);
            drawCraftArrowInSlot(chromeCanvas,
                ARROW_SLOT % SKYBLOCK_CHEST_COLS,
                ARROW_SLOT / SKYBLOCK_CHEST_COLS);
            ImageData chromeData = renderChrome(chromeCanvas, options, INSET + 4, new Color(0x404040));

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
            layers.add(new FrameMerger.Layer(0, 0, chromeData));

            Set<Integer> claimed = new HashSet<>();
            for (int chestSlot : SLOT_MAP) claimed.add(chestSlot);
            claimed.add(ARROW_SLOT);

            boolean anyAnimated = chromeData.isAnimated();
            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet().stream().toList()) {
                int callerSlot = entry.getKey();
                int chestSlot = SLOT_MAP[callerSlot];
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;
                layers.add(new FrameMerger.Layer(chestSlotX(chestSlot), chestSlotY(chestSlot), rendered));
            }

            anyAnimated |= appendFillerLayers(options, layers, itemRenderer, claimed);

            return composite(canvasW, canvasH, layers, anyAnimated, options);
        }

    }

    /**
     * Dedicated renderer for the SkyBlock "Combine Items" anvil menu. Uses the same 9x6 chest
     * canvas as SkyBlock crafting but with three caller slots (two inputs + output), a baked-in
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

        /** Chest slot where the decorative isometric anvil is rendered. */
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

        private final @NotNull RendererContext context;

        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            int canvasW = SKYBLOCK_CHEST_COLS * SLOT_SIZE + 2 * INSET;
            int canvasH = SKYBLOCK_CHEST_ROWS * SLOT_SIZE + 2 * INSET + TITLE_HEIGHT;

            Canvas chromeCanvas = Canvas.of(canvasW, canvasH);
            drawVanillaChestChrome(chromeCanvas, SKYBLOCK_CHEST_ROWS, SKYBLOCK_CHEST_COLS);
            ImageData chromeData = renderChrome(chromeCanvas, options, INSET + 4, new Color(0x404040));

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            BlockRenderer blockRenderer = new BlockRenderer(this.context);
            ConcurrentList<FrameMerger.Layer> layers = Concurrent.newList();
            layers.add(new FrameMerger.Layer(0, 0, chromeData));

            Set<Integer> claimed = new HashSet<>();
            for (int chestSlot : SLOT_MAP) claimed.add(chestSlot);
            claimed.add(DECORATION_SLOT);
            for (int chestSlot : RED_PANE_SLOTS) claimed.add(chestSlot);

            boolean anyAnimated = chromeData.isAnimated();

            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet().stream().toList()) {
                int callerSlot = entry.getKey();
                int chestSlot = SLOT_MAP[callerSlot];
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;
                layers.add(new FrameMerger.Layer(chestSlotX(chestSlot), chestSlotY(chestSlot), rendered));
            }

            BlockOptions decorationOptions = BlockOptions.builder()
                .blockId("minecraft:anvil")
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .outputSize(SLOT_SIZE - 4)
                .antiAlias(false)
                .build();
            ImageData decoration = blockRenderer.render(decorationOptions);
            if (decoration.isAnimated()) anyAnimated = true;
            layers.add(new FrameMerger.Layer(
                chestSlotX(DECORATION_SLOT) + 2,
                chestSlotY(DECORATION_SLOT) + 2,
                decoration
            ));

            ItemOptions redPaneOptions = ItemOptions.builder()
                .itemId("minecraft:red_stained_glass_pane")
                .type(ItemOptions.Type.GUI_2D)
                .outputSize(SLOT_SIZE - 4)
                .build();
            ImageData redPane = itemRenderer.render(redPaneOptions);
            if (redPane.isAnimated()) anyAnimated = true;
            for (int chestSlot : RED_PANE_SLOTS) {
                layers.add(new FrameMerger.Layer(
                    chestSlotX(chestSlot) + 2,
                    chestSlotY(chestSlot) + 2,
                    redPane
                ));
            }

            anyAnimated |= appendFillerLayers(options, layers, itemRenderer, claimed);

            return composite(canvasW, canvasH, layers, anyAnimated, options);
        }

    }

}
