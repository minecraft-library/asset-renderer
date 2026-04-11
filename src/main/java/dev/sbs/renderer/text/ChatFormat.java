package dev.sbs.renderer.text;

import dev.simplified.util.RegexUtil;
import dev.simplified.util.StringUtil;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.regex.Pattern;

/**
 * Minecraft format codes ({@code k-o}, {@code r}).
 * <p>
 * These modify text style without setting a color.
 */
@Getter
public enum ChatFormat {

    OBFUSCATED('k', 0xFFFFFF),
    BOLD('l', 0xFFFF55),
    STRIKETHROUGH('m', 0xFFFFFF),
    UNDERLINE('n', 0xFFFFFF),
    ITALIC('o', 0x5555FF),
    RESET('r', 0x000000);

    public static final char SECTION_SYMBOL = '\u00a7';

    private final char code;
    private final @NotNull Color backgroundColor;
    private final @NotNull String toString;

    ChatFormat(char code, int brgb) {
        this.code = code;
        this.backgroundColor = new Color(brgb);
        this.toString = new String(new char[]{ SECTION_SYMBOL, code });
    }

    /**
     * Looks up a format by its single-character code.
     *
     * @param code the code character ({@code k-o}, {@code r})
     * @return the matching format, or {@code null} if the code is not a format
     */
    public static @Nullable ChatFormat of(char code) {
        for (ChatFormat format : values())
            if (format.code == code) return format;

        return null;
    }

    /**
     * Looks up a format by its enum name (case-sensitive).
     *
     * @param name the enum constant name
     * @return the matching format, or {@code null} if not found
     */
    public static @Nullable ChatFormat of(@NotNull String name) {
        for (ChatFormat format : values())
            if (format.name().equals(name)) return format;

        return null;
    }

    /**
     * Returns {@code true} when the character is a valid color or format code.
     */
    public static boolean isValid(char code) {
        return ChatColor.of(code) != null || ChatFormat.of(code) != null;
    }

    /**
     * Strips all color and format codes from the given string.
     */
    public static @NotNull String stripColor(@NotNull String value) {
        return RegexUtil.strip(StringUtil.defaultString(value), RegexUtil.VANILLA_PATTERN);
    }

    /**
     * Translates alternate color code characters to the section symbol.
     */
    public static @NotNull String translateAlternateColorCodes(char altColorChar, @NotNull String value) {
        Pattern replaceAltColor = Pattern.compile(String.format("(?<!%s)%<s([0-9a-fk-orA-FK-OR])", altColorChar));
        return RegexUtil.replaceColor(value, replaceAltColor);
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
