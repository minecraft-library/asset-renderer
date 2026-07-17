package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.compose.FrameCompositor;
import lib.minecraft.renderer.engine.compose.FramePlacement;
import lib.minecraft.renderer.engine.compose.Timeline;
import lib.minecraft.renderer.engine.compose.layer.FrameLayer;
import lib.minecraft.renderer.engine.compose.layer.Layers;
import lib.minecraft.renderer.engine.compose.layer.LayerStack;
import lib.minecraft.renderer.engine.kit.NineSliceKit;
import lib.minecraft.renderer.engine.kit.TextKit;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
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

    /**
     * Edge length in pixels of one square inventory slot cell.
     */
    static final int SLOT_SIZE = 36;

    /**
     * Padding in pixels between the canvas edge and the slot grid.
     */
    static final int INSET = 4;

    /**
     * Height in pixels of the title band above the slot grid.
     */
    static final int TITLE_HEIGHT = 24;

    /**
     * Height in pixels reserved below the slot row for the anvil XP-cost label, added only when a
     * cost is present.
     */
    static final int XP_LABEL_HEIGHT = 20;

    // --- Shared SkyBlock chest (9x6) dimensions ---

    /**
     * Column count of the SkyBlock chest container (9 wide).
     */
    static final int SKYBLOCK_CHEST_COLS = 9;

    /**
     * Row count of the SkyBlock chest container (6 tall).
     */
    static final int SKYBLOCK_CHEST_ROWS = 6;

    /**
     * Total addressable slots in the SkyBlock chest container ({@code 9 * 6 = 54}).
     */
    static final int SKYBLOCK_CHEST_SLOTS = SKYBLOCK_CHEST_COLS * SKYBLOCK_CHEST_ROWS;

    // --- Shared vanilla chrome palette ---

    /**
     * Vanilla slot highlight / outer-bevel edge colour (white).
     */
    static final int CHROME_BORDER_HIGHLIGHT = 0xFFFFFFFF;

    /**
     * Vanilla slot shadow / inner-bevel edge colour (mid-grey).
     */
    static final int CHROME_BORDER_SHADOW = 0xFF555555;

    /**
     * Interior fill colour of the vanilla anvil rename textbox.
     */
    static final int ANVIL_TEXTBOX_BEIGE = 0xFFE5D4AC;

    /**
     * The vanilla inventory slot-cell sprite id ({@code textures/gui/sprites/container/slot.png}) - the
     * real 18x18 sunken slot texture blitted per slot in place of the former hand-drawn bevels.
     */
    static final @NotNull String SLOT_SPRITE_ID = "minecraft:gui/sprites/container/slot";

    /**
     * The slot sprite's scaling: {@code stretch} (the vanilla slot ships no {@code gui.scaling} mcmeta,
     * so it inherits the spec default), stretched to each slot rect via {@link NineSliceKit}.
     */
    private static final @NotNull MCMeta.GuiScaling SLOT_SCALING = new MCMeta.GuiScaling(
        MCMeta.GuiScaling.Type.STRETCH, -1, -1, new MCMeta.GuiScaling.Border(0, 0, 0, 0), false);

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
     * Appends filler layers to every non-claimed chest slot according to
     * {@link MenuOptions#getFill() options.fill}. Returns whether any of the filler layers
     * resolved to animated content so the caller can keep its {@code anyAnimated} flag
     * accurate.
     */
    static boolean appendFillerLayers(
        @NotNull MenuOptions options,
        @NotNull LayerStack<FrameLayer> stack,
        @NotNull ItemRenderer itemRenderer,
        @NotNull ConcurrentSet<Integer> claimed
    ) {
        if (options.getFill() == MenuOptions.Fill.EMPTY) return false;

        ItemOptions fillerOptions = switch (options.getFill()) {
            case BLACK_STAINED_GLASS_PANE -> ItemOptions.builder()
                .itemId("minecraft:black_stained_glass_pane")
                .type(ItemOptions.Type.GUI_2D)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SLOT_SIZE - 4).build())
                .build();
            case EMPTY -> throw new RenderException("EMPTY handled above");
        };
        ImageData fillerImage = itemRenderer.render(fillerOptions);
        boolean fillerAnimated = fillerImage.isAnimated();

        for (int chestSlot = 0; chestSlot < SKYBLOCK_CHEST_SLOTS; chestSlot++) {
            if (claimed.contains(chestSlot)) continue;
            place(stack, MenuSlot.CONTENT, new FramePlacement(
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
     * {@link FrameCompositor#merge}.
     */
    static @NotNull ImageData composite(
        int canvasW, int canvasH,
        @NotNull LayerStack<FrameLayer> stack,
        boolean anyAnimated,
        @NotNull MenuOptions options
    ) {
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
                throw new RenderException("Slot '%d' is out of range for menu type '%s' (max '%d')", slot, options.getType(), maxSlot);
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
    static void drawGenericChrome(@NotNull PixelBuffer chrome, int rows, int cols, @NotNull MenuOptions options) {
        MenuOptions.Theme theme = options.getTheme();
        chrome.fill(theme.getBackgroundArgb());

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x = INSET + col * SLOT_SIZE;
                int y = INSET + TITLE_HEIGHT + row * SLOT_SIZE;
                chrome.fillRect(x, y, SLOT_SIZE - 2, SLOT_SIZE - 2, theme.getSlotArgb());
            }
        }
    }

    /**
     * Erases a slot position by overpainting it with the given background colour. Used by the
     * vanilla crafting layout to remove the beveled slot insets at column 3, rows 0 and 2
     * where the arrow area should look like empty chrome rather than an empty slot.
     */
    static void drawSlotBackground(@NotNull PixelBuffer chrome, int col, int row, int argb) {
        int x = INSET + col * SLOT_SIZE;
        int y = INSET + TITLE_HEIGHT + row * SLOT_SIZE;
        chrome.fillRect(x, y, SLOT_SIZE, SLOT_SIZE, argb);
    }

    /**
     * Draws a vanilla-style Minecraft chest GUI chrome onto the buffer. The outer border uses a raised
     * 2-pixel bevel (light highlight on top/left, dark shadow on bottom/right) and the title band the
     * classic inventory palette ({@code 0xFFC6C6C6} background, white highlights, dark-gray shadows);
     * each slot cell is blitted from the vanilla {@code container/slot} sprite via {@link NineSliceKit}
     * in place of the former hand-drawn bevels.
     *
     * @param chrome the chrome buffer to draw onto
     * @param rows the slot-grid row count
     * @param cols the slot-grid column count
     * @param slotSprite the resolved vanilla slot sprite, or empty to leave slots as bare panel
     */
    static void drawVanillaChestChrome(@NotNull PixelBuffer chrome, int rows, int cols, @NotNull Optional<PixelBuffer> slotSprite) {
        int w = chrome.width();
        int h = chrome.height();

        final int background = 0xFFC6C6C6;
        final int borderHighlight = CHROME_BORDER_HIGHLIGHT;
        final int borderShadow = CHROME_BORDER_SHADOW;
        final int titleBand = 0xFFB4B4B4;
        final int borderThickness = 2;

        chrome.fill(background);

        chrome.fillRect(0, 0, w, borderThickness, borderHighlight);
        chrome.fillRect(0, 0, borderThickness, h, borderHighlight);
        chrome.fillRect(0, h - borderThickness, w, borderThickness, borderShadow);
        chrome.fillRect(w - borderThickness, 0, borderThickness, h, borderShadow);

        chrome.fillRect(borderThickness, borderThickness,
            w - 2 * borderThickness, TITLE_HEIGHT - borderThickness, titleBand);
        chrome.fillRect(borderThickness, TITLE_HEIGHT,
            w - 2 * borderThickness, 1, borderShadow);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int sx = INSET + col * SLOT_SIZE;
                int sy = INSET + TITLE_HEIGHT + row * SLOT_SIZE;
                drawSlotCell(chrome, sx, sy, SLOT_SIZE - 2, SLOT_SIZE - 2, slotSprite);
            }
        }
    }

    /**
     * Blits one inventory slot cell into the given rect from the vanilla {@code container/slot} sprite
     * via {@link NineSliceKit} (stretched to the rect). An empty sprite - the slot texture
     * unresolved - draws nothing, leaving the panel background showing (missing textures drop, no
     * procedural fallback).
     *
     * @param chrome the chrome buffer to draw onto
     * @param x the slot rect x origin
     * @param y the slot rect y origin
     * @param w the slot rect width
     * @param h the slot rect height
     * @param slotSprite the resolved slot sprite, or empty to draw nothing
     */
    static void drawSlotCell(@NotNull PixelBuffer chrome, int x, int y, int w, int h, @NotNull Optional<PixelBuffer> slotSprite) {
        if (slotSprite.isEmpty()) return;
        NineSliceKit.draw(chrome, slotSprite.get(), SLOT_SCALING, x, y, w, h, MinecraftFont.MC_PIXEL_SCALE, 1.0f);
    }

    /**
     * Resolves the vanilla {@code container/slot} sprite through the pack stack (a grayscale texture,
     * decoded gamma-safe), pinned to its tick-0 frame when a pack ships an animated variant, or empty
     * when no pack supplies it.
     *
     * @param context the renderer context resolving the slot texture
     * @return the decoded slot sprite, or empty when unresolved
     */
    static @NotNull Optional<PixelBuffer> resolveSlotSprite(@NotNull RendererContext context) {
        return new Textures(context).tryResolveTextureAtTick(SLOT_SPRITE_ID, 0);
    }

    /**
     * Draws a craft arrow centred on the given (col, row) slot position. The arrow bounding
     * box is inset a third of the slot on each side so the arrow sits visually within the slot
     * without touching the beveled edges.
     */
    static void drawCraftArrowInSlot(@NotNull PixelBuffer chrome, int col, int row) {
        int padX = SLOT_SIZE / 6;
        int padY = SLOT_SIZE / 3;
        int x = INSET + col * SLOT_SIZE + padX;
        int y = INSET + TITLE_HEIGHT + row * SLOT_SIZE + padY;
        int w = SLOT_SIZE - 2 * padX;
        int h = SLOT_SIZE - 2 * padY;
        drawCraftArrow(chrome, x, y, w, h, CHROME_BORDER_SHADOW);
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

    /**
     * Draws a craft arrow at an explicit Y position (rather than a slot row index). Used by
     * layouts that have custom vertical spacing above the slot row, like the vanilla anvil
     * with its rename textbox sitting between the title and the slot row.
     */
    static void drawCraftArrowInSlotAt(@NotNull PixelBuffer chrome, int col, int y) {
        int padX = SLOT_SIZE / 6;
        int padY = SLOT_SIZE / 3;
        int x = INSET + col * SLOT_SIZE + padX;
        int w = SLOT_SIZE - 2 * padX;
        int h = SLOT_SIZE - 2 * padY;
        drawCraftArrow(chrome, x, y + padY, w, h, CHROME_BORDER_SHADOW);
    }

    /**
     * Draws a centred plus sign inside the slot-sized cell at the given column and explicit Y
     * position. Proportions match the craft arrow so the two decorations line up visually.
     */
    static void drawPlusInSlot(@NotNull PixelBuffer chrome, int col, int y) {
        int pad = SLOT_SIZE / 4;
        int x = INSET + col * SLOT_SIZE + pad;
        int size = SLOT_SIZE - 2 * pad;
        drawPlus(chrome, x, y + pad, size, CHROME_BORDER_SHADOW);
    }

    /**
     * Draws a simple {@code +} sign inside the given bounding square. The horizontal and
     * vertical bars both have a thickness proportional to the box size (roughly a quarter).
     */
    static void drawPlus(@NotNull PixelBuffer chrome, int x, int y, int size, int argb) {
        if (size <= 0) return;
        int thickness = Math.max(2, size / 4);
        int offset = (size - thickness) / 2;
        chrome.fillRect(x, y + offset, size, thickness, argb);
        chrome.fillRect(x + offset, y, thickness, size, argb);
    }

    /**
     * Draws a single slot cell (matching the {@link #drawVanillaChestChrome} slot style) at the given
     * pixel position from the vanilla {@code container/slot} sprite. Used by layouts that place slot
     * cells outside the regular grid, e.g. the vanilla anvil slot row below the rename textbox.
     *
     * @param chrome the chrome buffer to draw onto
     * @param x the slot rect x origin
     * @param y the slot rect y origin
     * @param w the slot rect width
     * @param h the slot rect height
     * @param slotSprite the resolved slot sprite, or empty to draw nothing
     */
    static void drawSlotInset(@NotNull PixelBuffer chrome, int x, int y, int w, int h, @NotNull Optional<PixelBuffer> slotSprite) {
        drawSlotCell(chrome, x, y, w, h, slotSprite);
    }

    /**
     * Draws the beige rename textbox used by the vanilla anvil. A dark outer border wraps a
     * cream-coloured interior, matching the classic Minecraft anvil GUI textbox style where
     * the player types a new item name. Does not render any text - callers that want a
     * placeholder or a typed string need to blit it on top after this call.
     */
    static void drawRenameTextbox(@NotNull PixelBuffer chrome, int x, int y, int width, int height) {
        if (width <= 4 || height <= 4) return;
        final int borderColor = 0xFF373737;
        final int beigeColor = ANVIL_TEXTBOX_BEIGE;

        chrome.fillRect(x, y, width, height, borderColor);
        chrome.fillRect(x + 2, y + 2, width - 4, height - 4, beigeColor);
    }

    /**
     * Draws a stylised hammer sprite programmatically in the given colour. The sprite has a
     * rectangular head occupying the top third of the bounding box and a handle roughly a
     * quarter of the width, centred horizontally beneath the head.
     */
    static void drawHammer(@NotNull PixelBuffer chrome, int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) return;
        int headHeight = Math.max(3, height / 3);
        int handleWidth = Math.max(2, width / 4);
        int handleHeight = height - headHeight;
        int handleX = x + (width - handleWidth) / 2;
        int handleY = y + headHeight;

        chrome.fillRect(x, y, width, headHeight, argb);
        chrome.fillRect(handleX, handleY, handleWidth, handleHeight, argb);
    }

    // ---------------------------------------------------------------------------------------
    // Title / label drawing helpers.
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the chrome {@link ImageData} layer for a menu. When the title contains
     * obfuscated segments ({@code §k}), the result is an animated {@link ImageData} with
     * one second of unique obfuscation frames; otherwise a single static frame is returned.
     *
     * @param baseChrome the chrome buffer with all static pixel content already drawn
     * @param options the menu options (supplies title and FPS)
     * @param titleX the horizontal origin for the title text
     * @param defaultTitleArgb the colour used for title segments with no explicit colour
     * @return a static or animated chrome layer
     */
    static @NotNull ImageData renderChrome(
        @NotNull PixelBuffer baseChrome,
        @NotNull MenuOptions options,
        int titleX,
        int defaultTitleArgb
    ) {
        String title = options.getTitle();
        if (title.isEmpty())
            return StaticImageData.of(baseChrome.toBufferedImage());

        LineSegment titleLine = ColorSegment.fromLegacy(title);
        boolean animated = hasTitleObfuscation(titleLine);

        if (!animated) {
            drawTitleSegments(baseChrome, titleLine, titleX, INSET, TITLE_HEIGHT, defaultTitleArgb, 0);
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
                drawTitleSegments(frameBuffer, titleLine, titleX, INSET, TITLE_HEIGHT, defaultTitleArgb, f);
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
     * Renders pre-parsed title segments onto the buffer. Delegates font style resolution,
     * colour mapping, shadow, and obfuscation to {@link TextKit}.
     */
    private static void drawTitleSegments(
        @NotNull PixelBuffer buffer,
        @NotNull LineSegment titleLine,
        int titleX, int bandTop, int bandHeight,
        int defaultArgb,
        long frameSeed
    ) {
        MinecraftFontMetrics metrics = MinecraftFont.REGULAR.getFontMetrics();
        int textY = bandTop + (bandHeight - metrics.getHeight()) / 2 + metrics.getAscent();
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        TextKit.drawLine(g, titleLine, titleX / MinecraftFont.MC_PIXEL_SCALE, textY / MinecraftFont.MC_PIXEL_SCALE, defaultArgb, frameSeed);
    }

    /**
     * Renders the textbox label inside the rename textbox interior. The text is drawn in
     * white, left-aligned with a small horizontal padding. Does nothing when the label is
     * empty.
     */
    static void drawTextboxLabel(
        @NotNull PixelBuffer buffer,
        @NotNull String label,
        int innerX, int innerY, int innerH
    ) {
        if (label.isEmpty()) return;

        MinecraftFontMetrics metrics = MinecraftFont.REGULAR.getFontMetrics();
        int textY = innerY + (innerH - metrics.getHeight()) / 2 + metrics.getAscent();
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        TextKit.drawText(g, label, (innerX + 2) / MinecraftFont.MC_PIXEL_SCALE, textY / MinecraftFont.MC_PIXEL_SCALE, MinecraftFont.REGULAR, ColorMath.WHITE);
    }

    /**
     * Renders the XP cost label right-aligned in the given area, displayed as
     * {@code "Enchantment Cost: X"} in green ({@code 0x80FF20}), matching the vanilla
     * Minecraft anvil style. Does nothing when cost is zero or negative.
     */
    static void drawXpCost(@NotNull PixelBuffer buffer, int cost, int canvasW, int areaTop, int areaHeight) {
        if (cost <= 0) return;

        String text = "Enchantment Cost: " + cost;
        int xpGreen = 0xFF80FF20;
        MinecraftFontMetrics metrics = MinecraftFont.REGULAR.getFontMetrics();
        int textX = canvasW - INSET - 4 - TextKit.measureText(text, MinecraftFont.REGULAR);
        int textY = areaTop + (areaHeight - metrics.getHeight()) / 2 + metrics.getAscent();
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        TextKit.drawText(g, text, textX / MinecraftFont.MC_PIXEL_SCALE, textY / MinecraftFont.MC_PIXEL_SCALE, MinecraftFont.REGULAR, xpGreen);
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

        /**
         * The renderer context, forwarded to the per-slot {@link ItemRenderer}.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            int rows = resolveRows(options);
            int cols = resolveColumns(options);
            int canvasW = cols * SLOT_SIZE + 2 * INSET;
            int canvasH = rows * SLOT_SIZE + 2 * INSET + TITLE_HEIGHT;

            PixelBuffer chrome = PixelBuffer.create(canvasW, canvasH);
            drawGenericChrome(chrome, rows, cols, options);
            int defaultTitleArgb = options.getTheme().getDefaultTitleArgb();
            ImageData chromeData = renderChrome(chrome, options, INSET + 4, defaultTitleArgb);

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            LayerStack<FrameLayer> stack = new LayerStack<>();
            place(stack, MenuSlot.CHROME, new FramePlacement(0, 0, chromeData));

            boolean anyAnimated = chromeData.isAnimated();
            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet()) {
                int slotIndex = entry.getKey();
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;

                int col = slotIndex % cols;
                int row = slotIndex / cols;
                int x = INSET + col * SLOT_SIZE;
                int y = INSET + TITLE_HEIGHT + row * SLOT_SIZE;
                place(stack, MenuSlot.SLOT, new FramePlacement(x, y, rendered));
            }

            return composite(canvasW, canvasH, stack, anyAnimated, options);
        }

        /**
         * Resolves the row count for a generic menu type: {@code PLAYER} is fixed at 4,
         * {@code CHEST}/{@code CUSTOM} take the options' row count, and {@code SLOT} is a single
         * row. The dedicated-renderer types are unreachable here and throw defensively.
         *
         * @param options the menu render options
         * @return the row count for the generic grid
         * @throws RenderException when called for a type owned by a dedicated sub-renderer
         */
        private static int resolveRows(@NotNull MenuOptions options) {
            return switch (options.getType()) {
                case PLAYER -> 4;
                case CHEST, CUSTOM -> options.getRows();
                case SLOT -> 1;
                case VANILLA_CRAFTING, VANILLA_ANVIL, SKYBLOCK_CRAFTING, SKYBLOCK_ANVIL ->
                    throw new RenderException("Type '%s' uses a dedicated renderer", options.getType());
            };
        }

        /**
         * Resolves the column count for a generic menu type: {@code PLAYER}/{@code CHEST} are fixed
         * at 9 wide, {@code CUSTOM} takes the options' column count, and {@code SLOT} is a single
         * column. The dedicated-renderer types are unreachable here and throw defensively.
         *
         * @param options the menu render options
         * @return the column count for the generic grid
         * @throws RenderException when called for a type owned by a dedicated sub-renderer
         */
        private static int resolveColumns(@NotNull MenuOptions options) {
            return switch (options.getType()) {
                case PLAYER, CHEST -> 9;
                case CUSTOM -> options.getColumns();
                case SLOT -> 1;
                case VANILLA_CRAFTING, VANILLA_ANVIL, SKYBLOCK_CRAFTING, SKYBLOCK_ANVIL ->
                    throw new RenderException("Type '%s' uses a dedicated renderer", options.getType());
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

        /**
         * Canvas column count: the 3-wide input grid, a spacer/arrow column, and the output column.
         */
        private static final int COLS = 5;

        /**
         * Canvas row count matching the 3x3 input grid height.
         */
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

        /**
         * The renderer context, forwarded to the per-slot {@link ItemRenderer}.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            int canvasW = COLS * SLOT_SIZE + 2 * INSET;
            int canvasH = ROWS * SLOT_SIZE + 2 * INSET + TITLE_HEIGHT;

            Optional<PixelBuffer> slotSprite = resolveSlotSprite(this.context);
            PixelBuffer chrome = PixelBuffer.create(canvasW, canvasH);
            drawVanillaChestChrome(chrome, ROWS, COLS, slotSprite);
            drawSlotBackground(chrome, 3, 0, 0xFFC6C6C6);
            drawSlotBackground(chrome, 3, 2, 0xFFC6C6C6);
            drawCraftArrowInSlot(chrome, 3, 1);
            ImageData chromeData = renderChrome(chrome, options, INSET + 4, 0xFF404040);

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            LayerStack<FrameLayer> stack = new LayerStack<>();
            place(stack, MenuSlot.CHROME, new FramePlacement(0, 0, chromeData));

            boolean anyAnimated = chromeData.isAnimated();
            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet()) {
                int callerSlot = entry.getKey();
                int[] coord = SLOT_COORDS[callerSlot];
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;

                int x = INSET + coord[0] * SLOT_SIZE;
                int y = INSET + TITLE_HEIGHT + coord[1] * SLOT_SIZE;
                place(stack, MenuSlot.SLOT, new FramePlacement(x, y, rendered));
            }

            return composite(canvasW, canvasH, stack, anyAnimated, options);
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

        /**
         * Canvas column count laid out as {@code [input1] [+] [input2] [arrow] [output]}.
         */
        private static final int COLS = 5;

        /**
         * Height in pixels of the rename-textbox band between the title and the slot row.
         */
        private static final int TEXTBOX_HEIGHT = 30;

        /**
         * Caller slot index to column on the single slot row of the vanilla anvil layout.
         * Index 0 is the first input, index 1 is the second input, index 2 is the output. The
         * columns leave slot 1 (the {@code +} sign) and slot 3 (the craft arrow) as decorative
         * columns.
         */
        private static final int @NotNull [] SLOT_COLS = { 0, 2, 4 };

        /**
         * The renderer context, forwarded to the per-slot {@link ItemRenderer}.
         */
        private final @NotNull RendererContext context;

        /** {@inheritDoc} */
        @Override
        public @NotNull ImageData render(@NotNull MenuOptions options) {
            validateSlots(options);

            int canvasW = COLS * SLOT_SIZE + 2 * INSET;
            int xpLabelHeight = options.getXpCost() > 0 ? XP_LABEL_HEIGHT : 0;
            int canvasH = TITLE_HEIGHT + TEXTBOX_HEIGHT + SLOT_SIZE + 2 * INSET + xpLabelHeight;

            Optional<PixelBuffer> slotSprite = resolveSlotSprite(this.context);
            PixelBuffer chrome = PixelBuffer.create(canvasW, canvasH);
            drawVanillaChestChrome(chrome, 0, COLS, slotSprite);
            drawHammer(chrome, INSET + 4, (TITLE_HEIGHT - 16) / 2 + 2, 16, 16, CHROME_BORDER_HIGHLIGHT);

            int textboxX = INSET + SLOT_SIZE / 2;
            int textboxY = INSET + TITLE_HEIGHT + 4;
            int textboxW = canvasW - 2 * INSET - SLOT_SIZE;
            int textboxH = TEXTBOX_HEIGHT - 8;
            drawRenameTextbox(chrome, textboxX, textboxY, textboxW, textboxH);

            int slotRowY = INSET + TITLE_HEIGHT + TEXTBOX_HEIGHT;
            for (int col : SLOT_COLS) {
                int sx = INSET + col * SLOT_SIZE;
                drawSlotInset(chrome, sx, slotRowY, SLOT_SIZE - 2, SLOT_SIZE - 2, slotSprite);
            }

            drawPlusInSlot(chrome, 1, slotRowY);
            drawCraftArrowInSlotAt(chrome, 3, slotRowY);

            drawTextboxLabel(chrome, options.getTextboxLabel(), textboxX + 2, textboxY + 2, textboxH - 4);
            drawXpCost(chrome, options.getXpCost(), canvasW, slotRowY + SLOT_SIZE, xpLabelHeight);

            ImageData chromeData = renderChrome(chrome, options, INSET + 24, 0xFF404040);

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            LayerStack<FrameLayer> stack = new LayerStack<>();
            place(stack, MenuSlot.CHROME, new FramePlacement(0, 0, chromeData));

            boolean anyAnimated = chromeData.isAnimated();
            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet()) {
                int callerSlot = entry.getKey();
                int col = SLOT_COLS[callerSlot];
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;
                int x = INSET + col * SLOT_SIZE;
                place(stack, MenuSlot.SLOT, new FramePlacement(x, slotRowY, rendered));
            }

            return composite(canvasW, canvasH, stack, anyAnimated, options);
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

            int canvasW = SKYBLOCK_CHEST_COLS * SLOT_SIZE + 2 * INSET;
            int canvasH = SKYBLOCK_CHEST_ROWS * SLOT_SIZE + 2 * INSET + TITLE_HEIGHT;

            Optional<PixelBuffer> slotSprite = resolveSlotSprite(this.context);
            PixelBuffer chrome = PixelBuffer.create(canvasW, canvasH);
            drawVanillaChestChrome(chrome, SKYBLOCK_CHEST_ROWS, SKYBLOCK_CHEST_COLS, slotSprite);
            drawCraftArrowInSlot(chrome, ARROW_SLOT % SKYBLOCK_CHEST_COLS, ARROW_SLOT / SKYBLOCK_CHEST_COLS);
            ImageData chromeData = renderChrome(chrome, options, INSET + 4, 0xFF404040);

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            LayerStack<FrameLayer> stack = new LayerStack<>();
            place(stack, MenuSlot.CHROME, new FramePlacement(0, 0, chromeData));

            ConcurrentSet<Integer> claimed = Concurrent.newSet();
            for (int chestSlot : SLOT_MAP) claimed.add(chestSlot);
            claimed.add(ARROW_SLOT);

            boolean anyAnimated = chromeData.isAnimated();
            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet()) {
                int callerSlot = entry.getKey();
                int chestSlot = SLOT_MAP[callerSlot];
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;
                place(stack, MenuSlot.SLOT, new FramePlacement(chestSlotX(chestSlot), chestSlotY(chestSlot), rendered));
            }

            anyAnimated |= appendFillerLayers(options, stack, itemRenderer, claimed);

            return composite(canvasW, canvasH, stack, anyAnimated, options);
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

            int canvasW = SKYBLOCK_CHEST_COLS * SLOT_SIZE + 2 * INSET;
            int canvasH = SKYBLOCK_CHEST_ROWS * SLOT_SIZE + 2 * INSET + TITLE_HEIGHT;

            Optional<PixelBuffer> slotSprite = resolveSlotSprite(this.context);
            PixelBuffer chrome = PixelBuffer.create(canvasW, canvasH);
            drawVanillaChestChrome(chrome, SKYBLOCK_CHEST_ROWS, SKYBLOCK_CHEST_COLS, slotSprite);
            ImageData chromeData = renderChrome(chrome, options, INSET + 4, 0xFF404040);

            ItemRenderer itemRenderer = new ItemRenderer(this.context);
            BlockRenderer blockRenderer = new BlockRenderer(this.context);
            LayerStack<FrameLayer> stack = new LayerStack<>();
            place(stack, MenuSlot.CHROME, new FramePlacement(0, 0, chromeData));

            ConcurrentSet<Integer> claimed = Concurrent.newSet();
            for (int chestSlot : SLOT_MAP) claimed.add(chestSlot);
            claimed.add(DECORATION_SLOT);
            for (int chestSlot : RED_PANE_SLOTS) claimed.add(chestSlot);

            boolean anyAnimated = chromeData.isAnimated();

            for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : options.getSlots().entrySet()) {
                int callerSlot = entry.getKey();
                int chestSlot = SLOT_MAP[callerSlot];
                MenuOptions.MenuSlotContent content = entry.getValue();
                ImageData rendered = itemRenderer.render(content.options());
                if (rendered.isAnimated()) anyAnimated = true;
                place(stack, MenuSlot.SLOT, new FramePlacement(chestSlotX(chestSlot), chestSlotY(chestSlot), rendered));
            }

            BlockOptions decorationOptions = BlockOptions.builder()
                .blockId("minecraft:anvil")
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .output(OutputOptions.builder().canvasSize(SLOT_SIZE - 4).antiAlias(false).build())
                .build();
            ImageData decoration = blockRenderer.render(decorationOptions);
            if (decoration.isAnimated()) anyAnimated = true;
            place(stack, MenuSlot.CONTENT, new FramePlacement(
                chestSlotX(DECORATION_SLOT) + 2,
                chestSlotY(DECORATION_SLOT) + 2,
                decoration
            ));

            ItemOptions redPaneOptions = ItemOptions.builder()
                .itemId("minecraft:red_stained_glass_pane")
                .type(ItemOptions.Type.GUI_2D)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SLOT_SIZE - 4).build())
                .build();
            ImageData redPane = itemRenderer.render(redPaneOptions);
            if (redPane.isAnimated()) anyAnimated = true;
            for (int chestSlot : RED_PANE_SLOTS) {
                place(stack, MenuSlot.CONTENT, new FramePlacement(
                    chestSlotX(chestSlot) + 2,
                    chestSlotY(chestSlot) + 2,
                    redPane
                ));
            }

            anyAnimated |= appendFillerLayers(options, stack, itemRenderer, claimed);
            return composite(canvasW, canvasH, stack, anyAnimated, options);
        }

    }

}
