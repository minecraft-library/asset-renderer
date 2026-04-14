package dev.sbs.renderer.kit;

import dev.sbs.renderer.text.font.MinecraftFont;
import dev.sbs.renderer.text.font.TextKit;
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
public class ItemBarKit {

    private static final int LOGICAL_CANVAS = 16;
    private static final int BAR_X = 2;
    private static final int BAR_Y = 13;
    private static final int BAR_BG_WIDTH = 13;
    private static final int BAR_FG_WIDTH = 12;

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
        int fgColor = ColorMath.hsvToArgb(fraction * 120f, 1f, 1f);

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
     * Draws a stack count in the bottom-right corner of a GUI item buffer using the supplied font.
     * <p>
     * The number is rendered with a one-pixel black drop shadow and the main glyphs in white,
     * matching vanilla's stack count style. No-op when {@code count <= 1}.
     *
     * @param buffer the buffer to draw on
     * @param count the stack count
     * @param font the font to use for the digits
     */
    public static void drawStackCount(@NotNull PixelBuffer buffer, int count, @NotNull MinecraftFont font) {
        if (count <= 1) return;

        String text = Integer.toString(count);
        int scale = Math.max(1, buffer.width() / LOGICAL_CANVAS);
        int textWidth = TextKit.measureText(text, font) * scale;
        int padding = scale;
        int x = buffer.width() - textWidth - padding;
        int y = buffer.height() - padding;

        TextKit.drawText(buffer, text, x, y, font, ColorMath.WHITE, scale);
    }

}
