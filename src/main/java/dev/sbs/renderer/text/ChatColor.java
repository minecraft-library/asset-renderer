package dev.sbs.renderer.text;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.Objects;
import java.util.Optional;

/**
 * A Minecraft chat color.
 * <p>
 * Two flavours are supported:
 * <ul>
 * <li><b>{@link Legacy}</b> - the fixed enum of vanilla 1.8.9-style color codes ({@code 0-9},
 * {@code a-f}) with their baked foreground RGB values. Callers normally reference these
 * directly (e.g. {@code ChatColor.Legacy.WHITE}).</li>
 * <li><b>{@link Custom}</b> - a record for arbitrary RGB colors introduced in modern Minecraft
 * text components (e.g. {@code "color": "#FF00FF"}). Construct via the {@link #of(int)
 * shorthand factory} or the {@link #builder()} for shadow overrides.</li>
 * </ul>
 * Each color carries a foreground {@link Color} and a shadow {@link Color} derived as
 * {@code rgb >> 2} per channel - the same {@code color * 0.25f} formula used by
 * {@code Font#getShadowColor} from 1.13 onward (see {@code ARGB.scaleRGB} in 26.1).
 * <p>
 * This drops the 1.8.9 {@code colorCode[]} table quirk that produced {@code 0x2A2A00} for
 * gold's shadow - on that version, the {@code if (i == 6) k += 85} bump in
 * {@code FontRenderer}'s constructor was applied to foreground gold but not to the shadow
 * palette slot, leaving gold's shadow red at {@code 0x2A} instead of {@code 0x3F}. Every
 * version from 1.13 onward fixes this by computing shadow on each draw.
 */
public sealed interface ChatColor permits ChatColor.Legacy, ChatColor.Custom {

    /**
     * The foreground color.
     *
     * @return the foreground {@link Color}
     */
    @NotNull Color color();

    /**
     * The background (shadow) color derived via the 1.13+ formula
     * {@code (rgb & 0xFCFCFC) >> 2}.
     *
     * @return the shadow {@link Color}
     */
    @NotNull Color backgroundColor();

    /**
     * The single-character legacy code ({@code 0-9}, {@code a-f}) if this is a
     * {@link Legacy} color, otherwise empty. {@link Custom} colors have no legacy
     * representation.
     *
     * @return the code character if any
     */
    @NotNull Optional<Character> code();

    /**
     * The foreground color as a packed ARGB int.
     *
     * @return the foreground ARGB value
     */
    default int rgb() {
        return this.color().getRGB();
    }

    /**
     * The background (shadow) color as a packed ARGB int.
     *
     * @return the background ARGB value
     */
    default int backgroundRgb() {
        return this.backgroundColor().getRGB();
    }

    /**
     * Returns the foreground color with a custom alpha channel.
     *
     * @param alpha the alpha value in {@code [0, 255]}
     * @return the color with the specified alpha
     */
    default @NotNull Color withAlpha(int alpha) {
        Color color = this.color();
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /**
     * The legacy string representation ({@link ChatFormat#SECTION_SYMBOL} + code) for
     * {@link Legacy} colors, or an empty string for {@link Custom} colors which have no
     * single-character code.
     *
     * @return the legacy string
     */
    default @NotNull String toLegacyString() {
        return this.code()
            .map(code -> new String(new char[]{ ChatFormat.SECTION_SYMBOL, code }))
            .orElse("");
    }

    /**
     * The JSON text-component {@code color} representation - lowercase name for
     * {@link Legacy} colors, {@code #RRGGBB} hex for {@link Custom} colors.
     *
     * @return the JSON string
     */
    @NotNull String toJsonString();

    /**
     * Returns the vanilla shadow color for a given foreground RGB, computed as
     * {@code (rgb & 0xFCFCFC) >> 2} - equivalent to multiplying each RGB channel by
     * {@code 0.25f} and truncating. Alpha bits are discarded.
     *
     * @param color the 24-bit foreground color
     * @return the 24-bit shadow color
     */
    static @NotNull Color shadowOf(@NotNull Color color) {
        return new Color((color.getRGB() & 0xFCFCFC) >> 2);
    }

    /**
     * Returns {@code true} when the character is a valid {@link Legacy} color code.
     *
     * @param code the code character
     * @return whether the code maps to a known legacy color
     */
    static boolean isValid(char code) {
        return Legacy.of(code) != null;
    }

    /**
     * Looks up a {@link Legacy} color by its single-character code.
     *
     * @param code the code character ({@code 0-9}, {@code a-f})
     * @return the matching color, or {@code null} if the code is not a color
     */
    static @Nullable ChatColor of(char code) {
        return Legacy.of(code);
    }

    /**
     * Looks up a {@link Legacy} color by its enum name (case-sensitive).
     *
     * @param name the enum constant name
     * @return the matching color, or {@code null} if not found
     */
    static @Nullable ChatColor of(@NotNull String name) {
        return Legacy.of(name);
    }

    /**
     * Creates a {@link Custom} color with the given packed RGB foreground. The alpha channel
     * is forced to {@code 0xFF} and the shadow color is derived via {@link #shadowOf(Color)}.
     *
     * @param rgb the packed RGB foreground
     * @return a new custom color
     */
    static @NotNull ChatColor of(int rgb) {
        return builder().color(rgb).build();
    }

    /**
     * Parses a JSON text-component {@code color} string - either a lowercase {@link Legacy}
     * name ({@code "red"}) or a {@code #RRGGBB} hex literal for a {@link Custom} color.
     *
     * @param value the JSON color string
     * @return the parsed color, or {@code null} if the string is not recognised
     */
    static @Nullable ChatColor fromJsonString(@NotNull String value) {
        if (value.startsWith("#") && value.length() == 7) {
            try {
                return of(Integer.parseInt(value.substring(1), 16));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return Legacy.of(value.toUpperCase());
    }

    /**
     * Creates a builder for a {@link Custom} color.
     *
     * @return a new builder
     */
    static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Vanilla legacy chat colors ({@code 0-9}, {@code a-f}) with baked foreground and
     * derived shadow RGB values.
     */
    @Getter
    @Accessors(fluent = true)
    enum Legacy implements ChatColor {

        BLACK('0', new Color(0x000000)),
        DARK_BLUE('1', new Color(0x0000AA)),
        DARK_GREEN('2', new Color(0x00AA00)),
        DARK_AQUA('3', new Color(0x00AAAA)),
        DARK_RED('4', new Color(0xAA0000)),
        DARK_PURPLE('5', new Color(0xAA00AA)),
        GOLD('6', new Color(0xFFAA00)),
        GRAY('7', new Color(0xAAAAAA)),
        DARK_GRAY('8', new Color(0x555555)),
        BLUE('9', new Color(0x5555FF)),
        GREEN('a', new Color(0x55FF55)),
        AQUA('b', new Color(0x55FFFF)),
        RED('c', new Color(0xFF5555)),
        LIGHT_PURPLE('d', new Color(0xFF55FF)),
        YELLOW('e', new Color(0xFFFF55)),
        WHITE('f', new Color(0xFFFFFF));

        @Getter(AccessLevel.NONE)
        private final char code;
        private final @NotNull Color color;
        private final @NotNull Color backgroundColor;
        @Getter(AccessLevel.NONE)
        private final @NotNull String legacyString;

        Legacy(char code, @NotNull Color color) {
            this.code = code;
            this.color = color;
            this.backgroundColor = shadowOf(color);
            this.legacyString = new String(new char[]{ ChatFormat.SECTION_SYMBOL, code });
        }

        @Override
        public @NotNull Optional<Character> code() {
            return Optional.of(this.code);
        }

        /**
         * The raw legacy code character.
         *
         * @return the code character
         */
        public char codeChar() {
            return this.code;
        }

        @Override
        public @NotNull String toLegacyString() {
            return this.legacyString;
        }

        @Override
        public @NotNull String toJsonString() {
            return this.name().toLowerCase();
        }

        /**
         * Returns the next color in ordinal order, wrapping around to {@link #BLACK}.
         *
         * @return the next legacy color
         */
        public @NotNull Legacy nextColor() {
            return values()[(ordinal() + 1) % values().length];
        }

        /**
         * Looks up a legacy color by its single-character code.
         *
         * @param code the code character ({@code 0-9}, {@code a-f})
         * @return the matching color, or {@code null} if the code is not a color
         */
        public static @Nullable Legacy of(char code) {
            for (Legacy color : values())
                if (color.code == code) return color;

            return null;
        }

        /**
         * Looks up a legacy color by its enum name (case-sensitive).
         *
         * @param name the enum constant name
         * @return the matching color, or {@code null} if not found
         */
        public static @Nullable Legacy of(@NotNull String name) {
            for (Legacy color : values())
                if (color.name().equals(name)) return color;

            return null;
        }

        @Override
        public @NotNull String toString() {
            return this.legacyString;
        }

    }

    /**
     * A modern text-component color with arbitrary RGB. Emitted as {@code #RRGGBB} in JSON
     * and has no {@link Legacy} code representation.
     *
     * @param color the foreground color
     * @param backgroundColor the shadow color
     */
    record Custom(
        @NotNull Color color,
        @NotNull Color backgroundColor
    ) implements ChatColor {

        public Custom {
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(backgroundColor, "backgroundColor");
        }

        @Override
        public @NotNull Optional<Character> code() {
            return Optional.empty();
        }

        @Override
        public @NotNull String toJsonString() {
            return String.format("#%06X", this.color.getRGB() & 0xFFFFFF);
        }

        @Override
        public @NotNull String toString() {
            return this.toJsonString();
        }

    }

    /**
     * Mutable builder for a {@link Custom} color.
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    final class Builder {

        private @NotNull Color color = Color.WHITE;
        private @NotNull Optional<Color> backgroundColor = Optional.empty();

        /**
         * Sets the foreground color.
         *
         * @param color the foreground color
         * @return this builder
         */
        public @NotNull Builder color(@NotNull Color color) {
            this.color = color;
            return this;
        }

        /**
         * Sets the foreground color from a packed RGB int. The alpha channel is forced to
         * {@code 0xFF}.
         *
         * @param rgb the packed RGB foreground
         * @return this builder
         */
        public @NotNull Builder color(int rgb) {
            this.color = new Color(rgb & 0xFFFFFF);
            return this;
        }

        /**
         * Overrides the shadow color. When unset the shadow is derived via
         * {@link ChatColor#shadowOf(Color)}.
         *
         * @param color the shadow color
         * @return this builder
         */
        public @NotNull Builder backgroundColor(@NotNull Color color) {
            this.backgroundColor = Optional.of(color);
            return this;
        }

        /**
         * Overrides the shadow color from a packed RGB int. The alpha channel is forced to
         * {@code 0xFF}.
         *
         * @param rgb the packed RGB shadow color
         * @return this builder
         */
        public @NotNull Builder backgroundColor(int rgb) {
            this.backgroundColor = Optional.of(new Color(rgb & 0xFFFFFF));
            return this;
        }

        /**
         * Builds the custom color, deriving the shadow via {@link ChatColor#shadowOf(Color)}
         * when no override was set.
         *
         * @return the built custom color
         */
        public @NotNull Custom build() {
            return new Custom(this.color, this.backgroundColor.orElseGet(() -> shadowOf(this.color)));
        }

    }

}
