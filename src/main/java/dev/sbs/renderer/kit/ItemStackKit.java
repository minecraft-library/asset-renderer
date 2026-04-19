package dev.sbs.renderer.kit;

import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Draws the vanilla-style durability bar and stack count overlay on top of a GUI item icon.
 * <p>
 * Both helpers operate on a {@link PixelBuffer} sized at a multiple of 16 pixels. Coordinates
 * and sizes are specified in the 16x16 logical space and scaled up to the buffer pixel size
 * automatically, so a 256x256 buffer renders a 13*16=208 pixel wide bar, matching vanilla's
 * GUI scale 16.
 */
@UtilityClass
public class ItemStackKit {

    private static final int LOGICAL_CANVAS = 16;
    private static final int BAR_X = 2;
    private static final int BAR_Y = 13;
    private static final int BAR_BG_WIDTH = 13;
    private static final int BAR_FG_WIDTH = 12;

    /**
     * HSV hue in degrees that maps to full green - the upper endpoint of the durability bar's
     * colour sweep. Empty durability corresponds to hue 0 (red); vanilla's
     * {@code ItemRenderer.renderItemBar} lerps from 0 to this value as damage approaches zero.
     */
    private static final float DURABILITY_FULL_HUE_DEGREES = 120f;

    /**
     * X offset from the icon origin to the right edge of the stack count text, in GUI pixels.
     * Matches vanilla {@code GuiGraphicsExtractor.itemCount}'s {@code x + 19 - 2}, where
     * {@code 19} is the inventory slot width and {@code 2} is the right-edge inset. For a
     * standalone 16-wide icon this places the text's right edge 1 GUI pixel past the icon
     * (the overhang that normally bleeds into the slot border in a full inventory UI).
     */
    private static final int STACK_COUNT_RIGHT_GUI = LOGICAL_CANVAS + 1;

    /**
     * Y offset from the icon origin to the top of the stack count text, in GUI pixels. Matches
     * vanilla {@code y + 6 + 3}. Text occupies rows 9..17 and extends 1-2 GUI pixels past the
     * icon bottom, matching vanilla's slot overhang.
     */
    private static final int STACK_COUNT_TOP_GUI = 9;

    /**
     * Draws a durability bar at the bottom-left of a GUI item buffer.
     * <p>
     * The bar consists of a 13x1 black background row at logical Y 13, plus a 12x1 foreground row
     * at logical Y 14 whose length is proportional to {@code maxDurability - damage}. The
     * foreground colour interpolates from green (full) to red (empty) using an HSV hue sweep, the
     * same formula vanilla uses in {@code net.minecraft.client.renderer.ItemRenderer.renderItemBar}.
     * <p>
     * No-op when {@code damage <= 0} or {@code maxDurability <= 0}.
     *
     * @param buffer the buffer to draw on
     * @param damage the current damage value
     * @param maxDurability the maximum durability
     */
    public static void drawDamageBar(@NotNull PixelBuffer buffer, int damage, int maxDurability) {
        if (damage <= 0 || maxDurability <= 0) return;

        int remaining = Math.max(0, maxDurability - damage);
        float fraction = (float) remaining / (float) maxDurability;
        int fgWidth = Math.clamp(Math.round(BAR_FG_WIDTH * fraction), 0, BAR_FG_WIDTH);
        int fgColor = ColorMath.hsvToArgb(fraction * DURABILITY_FULL_HUE_DEGREES, 1f, 1f);

        int scale = Math.max(1, buffer.width() / LOGICAL_CANVAS);
        int barX = BAR_X * scale;
        int barY = BAR_Y * scale;
        int cellH = scale;

        // Background: 13 wide x 1 tall black row at logical (2, 13)
        buffer.fillRect(barX, barY, BAR_BG_WIDTH * scale, cellH, ColorMath.BLACK);

        // Foreground: 12 wide x 1 tall coloured row at logical (2, 14), proportional width
        int fgPxWidth = fgWidth * scale;
        if (fgPxWidth > 0)
            buffer.fillRect(barX, barY + cellH, fgPxWidth, cellH, fgColor);
    }

    /**
     * Draws a stack count in the bottom-right corner of a GUI item buffer using the supplied
     * font, matching vanilla {@code GuiGraphicsExtractor.itemCount}: white with a drop shadow,
     * right-aligned so the text ends one GUI pixel past the icon's right edge, top of text at
     * row 9.
     * <p>
     * The text is rasterized at the font's native mcPixel resolution into a scratch buffer,
     * then {@link PixelBuffer#blitScaled} copies that scratch into the target at the icon's
     * GUI scale. No-op when {@code count <= 1}.
     *
     * @param buffer the buffer to draw on - expected to be a multiple of 16 pixels per side
     * @param count the stack count
     * @param font the font to use for the digits
     */
    public static void drawStackCount(@NotNull PixelBuffer buffer, int count, @NotNull MinecraftFont font) {
        if (count <= 1) return;

        String text = Integer.toString(count);
        int guiScale = Math.max(1, buffer.width() / LOGICAL_CANVAS);

        // Text footprint in native (mcPixel) terms.
        int textWidthMcPx = TextKit.measureTextMcPixels(text, font);
        int ascentMcPx = font.getFontMetrics().getAscentMcPixels();
        int descentMcPx = font.getFontMetrics().getDescentMcPixels();

        // Shadow is 1 mcPx down-right of the main pass, so the scratch needs 1 extra mcPx on
        // the right + bottom to catch it. The font renders 1 mcPx of left bearing for some
        // glyphs, so include 1 extra mcPx on the left as safety margin.
        int shadowPadMcPx = 1;
        int leftPadMcPx = 1;
        int scratchWMcPx = leftPadMcPx + textWidthMcPx + shadowPadMcPx;
        int scratchHMcPx = ascentMcPx + descentMcPx + shadowPadMcPx;
        int scratchW = scratchWMcPx * MinecraftFont.MC_PIXEL_SCALE;
        int scratchH = scratchHMcPx * MinecraftFont.MC_PIXEL_SCALE;

        PixelBuffer scratch = PixelBuffer.create(scratchW, scratchH);
        MinecraftGraphics g = new MinecraftGraphics(scratch);
        // Draw with the cursor offset by leftPadMcPx and the baseline at ascent-from-top.
        TextKit.drawText(g, text, leftPadMcPx, ascentMcPx, font, ColorMath.WHITE);

        // Vanilla GUI-pixel anchor: text's right edge at STACK_COUNT_RIGHT_GUI, text top at
        // STACK_COUNT_TOP_GUI. Convert to actual buffer pixels via guiScale. The scratch's
        // logical x=0 corresponds to (text left edge - leftPadMcPx), so shift by that pad.
        int textLeftGui = STACK_COUNT_RIGHT_GUI - textWidthMcPx;
        int scratchOriginX = (textLeftGui - leftPadMcPx) * guiScale;
        int scratchOriginY = STACK_COUNT_TOP_GUI * guiScale;
        int destW = scratchWMcPx * guiScale;
        int destH = scratchHMcPx * guiScale;

        buffer.blitScaled(scratch, scratchOriginX, scratchOriginY, destW, destH);
    }

}
