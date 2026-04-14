package dev.sbs.renderer.text;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;

/**
 * Minecraft color codes ({@code 0-9}, {@code a-f}).
 * <p>
 * They carry a foreground and background (shadow) color.
 */
@Getter
public enum ChatColor {

    BLACK('0', 0x000000, 0x000000),
    DARK_BLUE('1', 0x0000AA, 0x00002A),
    DARK_GREEN('2', 0x00AA00, 0x002A00),
    DARK_AQUA('3', 0x00AAAA, 0x002A2A),
    DARK_RED('4', 0xAA0000, 0x2A0000),
    DARK_PURPLE('5', 0xAA00AA, 0x2A002A),
    GOLD('6', 0xFFAA00, 0x2A2A00),
    GRAY('7', 0xAAAAAA, 0x2A2A2A),
    DARK_GRAY('8', 0x555555, 0x151515),
    BLUE('9', 0x5555FF, 0x15153F),
    GREEN('a', 0x55FF55, 0x153F15),
    AQUA('b', 0x55FFFF, 0x153F3F),
    RED('c', 0xFF5555, 0x3F1515),
    LIGHT_PURPLE('d', 0xFF55FF, 0x3F153F),
    YELLOW('e', 0xFFFF55, 0x3F3F15),
    WHITE('f', 0xFFFFFF, 0x3F3F3F);

    private final char code;
    private final @NotNull Color color;
    private final @NotNull Color backgroundColor;
    private final @NotNull String toString;

    ChatColor(char code, int rgb, int brgb) {
        this.code = code;
        this.color = new Color(rgb);
        this.backgroundColor = new Color(brgb);
        this.toString = new String(new char[]{ ChatFormat.SECTION_SYMBOL, code });
    }

    /**
     * Returns the foreground color with a custom alpha channel.
     *
     * @param alpha the alpha value in {@code [0, 255]}
     * @return the color with the specified alpha
     */
    public @NotNull Color getColor(int alpha) {
        return new Color(this.color.getRed(), this.color.getGreen(), this.color.getBlue(), alpha);
    }

    public int getRGB() {
        return this.color.getRGB();
    }

    /**
     * The background (shadow) color for this chat color as a packed ARGB int.
     *
     * @return the background ARGB value
     */
    public int getBackgroundRGB() {
        return this.backgroundColor.getRGB();
    }

    /**
     * Returns the next color in ordinal order, wrapping around to {@link #BLACK}.
     */
    public @NotNull ChatColor getNextColor() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * Returns {@code true} when the character is a valid color code.
     */
    public static boolean isValid(char code) {
        return ChatColor.of(code) != null;
    }

    /**
     * Looks up a color by its single-character code.
     *
     * @param code the code character ({@code 0-9}, {@code a-f})
     * @return the matching color, or {@code null} if the code is not a color
     */
    public static @Nullable ChatColor of(char code) {
        for (ChatColor color : values())
            if (color.code == code) return color;

        return null;
    }

    /**
     * Looks up a color by its enum name (case-sensitive).
     *
     * @param name the enum constant name
     * @return the matching color, or {@code null} if not found
     */
    public static @Nullable ChatColor of(@NotNull String name) {
        for (ChatColor color : values())
            if (color.name().equals(name)) return color;

        return null;
    }

    public @NotNull String toLegacyString() {
        return this.toString;
    }

    public @NotNull String toJsonString() {
        return this.name().toLowerCase();
    }

    @Override
    public @NotNull String toString() {
        return this.toString;
    }

}
